package com.newhorizon.sessionflow;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.CallbackBridge;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.spi.FileSystemProvider;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Installs the New Horizon server entry and owns the one-session connection flow.
 *
 * <p>This source intentionally uses the 1.20.1 SRG names because the bundled JAR is
 * compiled directly against the production Minecraft/Forge artifacts shipped on the
 * phone. Keeping it here makes the APK asset reproducible without modifying the
 * original reference JAR.</p>
 */
@Mod(NewHorizonSessionFlow.MOD_ID)
public final class NewHorizonSessionFlow {
    public static final String MOD_ID = "newhorizon_session_flow";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String SERVER_ADDRESS = "127.0.0.1:9055";
    private static final String SERVER_NAME = "New Horizon";
    private static final int CONNECT_DELAY_TICKS = 20;
    private static final int SERVER_SAVE_RETRY_TICKS = 20;
    private static final int GAMEPLAY_POST_BOOTSTRAP_DIET_TICKS = 40;

    /**
     * Runs from the same JAR as a Java agent, before Forge creates its named
     * module layer. That keeps this tiny CDS bridge in the unnamed module to
     * which JREUtils grants access to jdk.internal.misc.
     */
    public static final class NhCdsAgent {
        private NhCdsAgent() {
        }

        public static void premain(String agentArgs,
                java.lang.instrument.Instrumentation instrumentation) {
            if (Boolean.getBoolean("newhorizon.lite")) {
                startLiteGovernor(instrumentation);
            }
            String archivePath = System.getProperty("newhorizon.cdsArchivePath", "");
            if (archivePath.isEmpty()) return;

            File marker = new File(archivePath + ".ready");
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
            Thread dumpThread = new Thread(() -> {
                try {
                    boolean ready = false;
                    for (int attempt = 0; attempt < 180; attempt++) {
                        if (marker.isFile()) {
                            ready = true;
                            break;
                        }
                        Thread.sleep(1000L);
                    }
                    if (!ready) {
                        System.err.println("[NHCDS-Agent] readiness timeout; dump skipped");
                        return;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    marker.delete();
                    Thread.sleep(5000L);

                    File archive = new File(archivePath);
                    System.err.println("[NHCDS-Agent] dynamic archive dump starting path="
                            + archivePath);
                    Class<?> cdsClass = Class.forName("jdk.internal.misc.CDS");
                    Method dumpMethod = cdsClass.getDeclaredMethod(
                            "dumpSharedArchive", boolean.class, String.class);
                    dumpMethod.setAccessible(true);
                    dumpMethod.invoke(null, false, archivePath);
                    System.err.println("[NHCDS-Agent] dynamic archive dump complete path="
                            + archivePath + " bytes=" + archive.length());
                } catch (Throwable throwable) {
                    System.err.println("[NHCDS-Agent] dynamic archive dump failed path="
                            + archivePath);
                    throwable.printStackTrace(System.err);
                }
            }, "NH-Dynamic-CDS-Agent");
            dumpThread.setDaemon(true);
            dumpThread.start();
        }

        /**
         * Direct-SRG mode has no Forge event bus. Wait for Minecraft's own
         * singleton and schedule the existing post-bootstrap diet on its render
         * executor once the title screen has remained stable for two seconds.
         */
        private static void startLiteGovernor(
                java.lang.instrument.Instrumentation instrumentation) {
            Thread governorThread = new Thread(() -> {
                try {
                    Class<?> minecraftClass = null;
                    for (int attempt = 0; attempt < 2400 && minecraftClass == null; attempt++) {
                        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                            if ("net.minecraft.client.Minecraft".equals(loadedClass.getName())) {
                                minecraftClass = loadedClass;
                                break;
                            }
                        }
                        if (minecraftClass == null) Thread.sleep(250L);
                    }
                    if (minecraftClass == null) {
                        System.err.println("[NHLite-Agent] Minecraft class timeout");
                        return;
                    }

                    Method getInstance = minecraftClass.getDeclaredMethod("m_91087_");
                    getInstance.setAccessible(true);
                    Field screenField = minecraftClass.getDeclaredField("f_91080_");
                    screenField.setAccessible(true);
                    Method execute = minecraftClass.getMethod("execute", Runnable.class);

                    Object minecraft = null;
                    int stableTitlePolls = 0;
                    for (int attempt = 0; attempt < 2400; attempt++) {
                        minecraft = getInstance.invoke(null);
                        if (minecraft == null) {
                            Thread.sleep(250L);
                            continue;
                        }
                        Object screen = screenField.get(minecraft);
                        String screenName = screen == null ? "null" : screen.getClass().getName();
                        boolean readyScreen = "net.minecraft.client.gui.screens.TitleScreen"
                                .equals(screenName)
                                || "net.minecraft.client.gui.screens.DisconnectedScreen"
                                .equals(screenName);
                        stableTitlePolls = readyScreen ? stableTitlePolls + 1 : 0;
                        if (stableTitlePolls >= 8) break;
                        Thread.sleep(250L);
                    }
                    if (stableTitlePolls < 8 || minecraft == null) {
                        System.err.println("[NHLite-Agent] stable title screen timeout");
                        return;
                    }

                    Class<?> governorClass = Class.forName(
                            "com.newhorizon.sessionflow.NewHorizonSessionFlow$NhMemoryGovernor",
                            true, NhCdsAgent.class.getClassLoader());
                    Method applyDiet = governorClass.getDeclaredMethod(
                            "applyDisconnectedScreenTextureDiet", minecraftClass);
                    applyDiet.setAccessible(true);
                    Method prepareLatePass = governorClass.getDeclaredMethod(
                            "prepareLateLiteReloadDietPass");
                    prepareLatePass.setAccessible(true);
                    Object targetMinecraft = minecraft;
                    Runnable task = () -> {
                        try {
                            System.err.println("[NHLite-Agent] applying standalone post-bootstrap diet");
                            applyDiet.invoke(null, targetMinecraft);
                            System.err.println("[NHLite-Agent] standalone post-bootstrap diet complete");
                        } catch (Throwable throwable) {
                            System.err.println("[NHLite-Agent] standalone diet failed");
                            throwable.printStackTrace(System.err);
                        }
                    };
                    execute.invoke(minecraft, task);

                    // TitleScreen becomes visible before the asynchronous
                    // vanilla atlas reload has uploaded its final pages. The
                    // first pass therefore protects bootstrap pressure, while
                    // this pass removes caches/textures recreated by that reload.
                    Thread.sleep(15_000L);
                    Runnable lateTask = () -> {
                        try {
                            Object screen = screenField.get(targetMinecraft);
                            String screenName = screen == null
                                    ? "null" : screen.getClass().getName();
                            boolean safeMenu = "net.minecraft.client.gui.screens.TitleScreen"
                                    .equals(screenName)
                                    || "net.minecraft.client.gui.screens.DisconnectedScreen"
                                    .equals(screenName);
                            if (!safeMenu) {
                                System.err.println("[NHLite-Agent] late reload diet skipped screen="
                                        + screenName);
                                return;
                            }
                            System.err.println("[NHLite-Agent] applying late reload diet");
                            prepareLatePass.invoke(null);
                            applyDiet.invoke(null, targetMinecraft);
                            System.err.println("[NHLite-Agent] late reload diet complete");
                        } catch (Throwable throwable) {
                            System.err.println("[NHLite-Agent] late reload diet failed");
                            throwable.printStackTrace(System.err);
                        }
                    };
                    execute.invoke(minecraft, lateTask);
                } catch (Throwable throwable) {
                    System.err.println("[NHLite-Agent] bootstrap failed");
                    throwable.printStackTrace(System.err);
                }
            }, "NH-Lite-Governor-Agent");
            governorThread.setDaemon(true);
            governorThread.start();
            System.err.println("[NHLite-Agent] direct SRG governor armed");
        }
    }

    /** Temporary low-overhead allocator ownership diagnostic for the lite client. */
    public static final class NhNativeAllocationTracker {
        private static final long MIN_TRACKED_BYTES = 256L * 1024L;
        private static final String REQUEST_SINK_PROPERTY =
                "newhorizon.nativeRequestSink";
        private static final java.util.concurrent.ConcurrentHashMap<String, long[]>
                REQUESTS = new java.util.concurrent.ConcurrentHashMap<>();
        private static volatile boolean installed;

        private NhNativeAllocationTracker() {
        }

        public static void install(java.lang.instrument.Instrumentation instrumentation) {
            if (installed || !Boolean.getBoolean("newhorizon.nativeAllocTrace")) {
                return;
            }
            installed = true;
            System.getProperties().put(REQUEST_SINK_PROPERTY,
                    (java.util.function.LongConsumer)
                            NhNativeAllocationTracker::requested);
            instrumentation.addTransformer(new java.lang.instrument.ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className,
                        Class<?> classBeingRedefined,
                        java.security.ProtectionDomain protectionDomain,
                        byte[] classfileBuffer) {
                    if (!"org/lwjgl/system/MemoryUtil".equals(className)) {
                        return null;
                    }
                    try {
                        org.objectweb.asm.ClassReader reader =
                                new org.objectweb.asm.ClassReader(classfileBuffer);
                        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                                reader, org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
                        org.objectweb.asm.ClassVisitor visitor = new org.objectweb.asm.ClassVisitor(
                                org.objectweb.asm.Opcodes.ASM9, writer) {
                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(int access,
                                    String name, String descriptor, String signature,
                                    String[] exceptions) {
                                org.objectweb.asm.MethodVisitor delegate = super.visitMethod(
                                        access, name, descriptor, signature, exceptions);
                                boolean alloc = "nmemAlloc".equals(name)
                                        && "(J)J".equals(descriptor);
                                boolean calloc = "nmemCalloc".equals(name)
                                        && "(JJ)J".equals(descriptor);
                                boolean realloc = "nmemRealloc".equals(name)
                                        && "(JJ)J".equals(descriptor);
                                boolean free = "nmemFree".equals(name)
                                        && "(J)V".equals(descriptor);
                                if (!alloc && !calloc && !realloc && !free) {
                                    return delegate;
                                }
                                return new org.objectweb.asm.MethodVisitor(
                                        org.objectweb.asm.Opcodes.ASM9, delegate) {
                                    @Override
                                    public void visitCode() {
                                        super.visitCode();
                                        if (alloc) {
                                            emitRequested(this, 0);
                                        } else if (calloc) {
                                            emitRequestedProduct(this, 0, 2);
                                        } else if (realloc) {
                                            emitRequested(this, 2);
                                        }
                                    }
                                };
                            }
                        };
                        reader.accept(visitor, 0);
                        System.err.println("[NHNativeAllocTrace] MemoryUtil instrumented");
                        return writer.toByteArray();
                    } catch (Throwable throwable) {
                        System.err.println("[NHNativeAllocTrace] transform failed " + throwable);
                        return null;
                    }
                }
            });
            System.err.println("[NHNativeAllocTrace] armed thresholdKb="
                    + (MIN_TRACKED_BYTES / 1024L));
        }

        private static void emitRequested(org.objectweb.asm.MethodVisitor visitor,
                int bytesLocal) {
            visitor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                    "java/lang/System", "getProperties",
                    "()Ljava/util/Properties;", false);
            visitor.visitLdcInsn(REQUEST_SINK_PROPERTY);
            visitor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            visitor.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST,
                    "java/util/function/LongConsumer");
            visitor.visitVarInsn(org.objectweb.asm.Opcodes.LLOAD, bytesLocal);
            visitor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE,
                    "java/util/function/LongConsumer", "accept", "(J)V", true);
        }

        private static void emitRequestedProduct(
                org.objectweb.asm.MethodVisitor visitor,
                int countLocal, int bytesLocal) {
            visitor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                    "java/lang/System", "getProperties",
                    "()Ljava/util/Properties;", false);
            visitor.visitLdcInsn(REQUEST_SINK_PROPERTY);
            visitor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                    "java/util/Properties", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            visitor.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST,
                    "java/util/function/LongConsumer");
            visitor.visitVarInsn(org.objectweb.asm.Opcodes.LLOAD, countLocal);
            visitor.visitVarInsn(org.objectweb.asm.Opcodes.LLOAD, bytesLocal);
            visitor.visitInsn(org.objectweb.asm.Opcodes.LMUL);
            visitor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE,
                    "java/util/function/LongConsumer", "accept", "(J)V", true);
        }

        public static void requested(long bytes) {
            if (bytes < MIN_TRACKED_BYTES) {
                return;
            }
            String stack = captureStack();
            REQUESTS.compute(stack, (ignored, totals) -> {
                long[] result = totals == null ? new long[2] : totals;
                result[0] += bytes;
                result[1]++;
                return result;
            });
        }

        public static void dumpOutstanding(String phase) {
            if (!installed) {
                return;
            }
            long totalBytes = 0L;
            long requestCount = 0L;
            for (long[] totals : REQUESTS.values()) {
                totalBytes += totals[0];
                requestCount += totals[1];
            }
            List<Map.Entry<String, long[]>> entries =
                    new ArrayList<>(REQUESTS.entrySet());
            entries.sort((left, right) -> Long.compare(
                    right.getValue()[0], left.getValue()[0]));
            System.err.println("[NHNativeAllocTrace] phase=" + phase
                    + " requestedMb=" + (totalBytes / (1024L * 1024L))
                    + " requests=" + requestCount
                    + " groups=" + entries.size());
            for (int index = 0; index < Math.min(16, entries.size()); index++) {
                Map.Entry<String, long[]> entry = entries.get(index);
                System.err.println("[NHNativeAllocTrace] rank=" + (index + 1)
                        + " mb=" + (entry.getValue()[0] / (1024L * 1024L))
                        + " blocks=" + entry.getValue()[1]
                        + " stack=" + entry.getKey());
            }
        }

        private static String captureStack() {
            StringBuilder builder = new StringBuilder(512);
            int kept = 0;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String owner = frame.getClassName();
                if (owner.equals(Thread.class.getName())
                        || owner.contains("NhNativeAllocationTracker")
                        || owner.equals("org.lwjgl.system.MemoryUtil")) {
                    continue;
                }
                if (kept++ > 0) {
                    builder.append(" <- ");
                }
                builder.append(owner).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber());
                if (kept >= 10) {
                    break;
                }
            }
            return builder.toString();
        }
    }

    public NewHorizonSessionFlow() {
        logInfo("active version=1.0.41-vanilla-runtime-capture");
        NhGameMemoryProfiler.NhAnonTimeline.mark("mod-construct");
    }

    private static void logInfo(String message) {
        LOGGER.info("[NewHorizonSessionFlow] {}", message);
        System.err.println("[NewHorizonSessionFlow] " + message);
    }

    private static void logWarn(String message, Throwable throwable) {
        LOGGER.warn("[NewHorizonSessionFlow] " + message, throwable);
        System.err.println("[NewHorizonSessionFlow] " + message);
        if (throwable != null) {
            throwable.printStackTrace(System.err);
        }
    }

    private static void logWarn(String message) {
        LOGGER.warn("[NewHorizonSessionFlow] {}", message);
        System.err.println("[NewHorizonSessionFlow] " + message);
    }

    static void captureInfo(String message) {
        logInfo("[NHVanillaCapture] " + message);
    }

    static void captureWarning(String message, Throwable throwable) {
        logWarn("[NHVanillaCapture] " + message, throwable);
    }

    private static boolean isGameplayActive(Minecraft minecraft, Screen screen) {
        return minecraft != null && screen == null;
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ClientEvents {
        private static boolean joinedMultiplayer;
        private static boolean closingAfterDisconnect;
        private static boolean connectStarted;
        private static boolean serverSaved;
        private static boolean gameplayTimelineMarked;
        private static boolean gameplayPostBootstrapDietApplied;
        private static boolean localWorldStarted;
        private static int titleTicks;
        private static int serverSaveRetryTicks;
        private static int ungrabTicks;
        private static int gameplayPostBootstrapDietTicks;
        private static int vanillaCaptureTicks;

        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Minecraft minecraft = Minecraft.m_91087_();
            Screen screen = minecraft.f_91080_;
            if (isGameplayActive(minecraft, screen)) {
                joinedMultiplayer = true;
                if (!gameplayTimelineMarked) {
                    gameplayTimelineMarked = true;
                    NhGameMemoryProfiler.NhAnonTimeline.mark("gameplay-first-tick");
                }
                boolean lowPressure = Boolean.getBoolean("newhorizon.lowPressure");
                if (!lowPressure && Boolean.getBoolean("newhorizon.vanillaCapture")) {
                    vanillaCaptureTicks++;
                    if (vanillaCaptureTicks >= 20) {
                        VanillaRuntimeCapture.start(minecraft);
                    }
                }
                gameplayPostBootstrapDietTicks++;
                if (lowPressure && !gameplayPostBootstrapDietApplied) {
                    if (gameplayPostBootstrapDietTicks
                            >= GAMEPLAY_POST_BOOTSTRAP_DIET_TICKS) {
                        gameplayPostBootstrapDietApplied = true;
                        logInfo("successful-gameplay post-bootstrap diet starting"
                                + " delayTicks=" + gameplayPostBootstrapDietTicks);
                        // A failed first connection already runs this diet from the
                        // disconnected screen. A successful first connection used to
                        // skip it entirely, leaving the 260+ MiB bootstrap heap and
                        // Forge discovery metadata cold enough for Android to move
                        // roughly 300 MiB into zram. Run the same proven two-GC path
                        // after the first world has rendered for two seconds.
                        NhMemoryGovernor.applyDisconnectedScreenTextureDiet(minecraft);
                    }
                }
                if (lowPressure) NhMemoryGovernor.tick(minecraft);
                NhGameMemoryProfiler.tick(minecraft);
                repairUnexpectedUngrab(minecraft);
                return;
            }

            ungrabTicks = 0;

            if (screen == null) {
                return;
            }

            if (!(screen instanceof TitleScreen)) {
                titleTicks = 0;
                return;
            }

            if (isLocalWorldTestEnabled()) {
                if (localWorldStarted || connectStarted || closingAfterDisconnect) {
                    return;
                }

                titleTicks++;
                if (titleTicks < CONNECT_DELAY_TICKS) {
                    return;
                }

                connectStarted = true;
                localWorldStarted = true;
                try {
                    openLocalTestWorld(minecraft, localWorldName());
                } catch (Throwable throwable) {
                    connectStarted = false;
                    localWorldStarted = false;
                    titleTicks = 0;
                    logWarn("local superflat test launch failed", throwable);
                }
                return;
            }

            persistServerWhenDue(minecraft);

            if (connectStarted || closingAfterDisconnect) {
                return;
            }

            titleTicks++;
            if (titleTicks < CONNECT_DELAY_TICKS) {
                return;
            }

            connectStarted = true;
            try {
                connectToNewHorizon(minecraft, screen);
            } catch (Throwable throwable) {
                connectStarted = false;
                titleTicks = 0;
                logWarn("auto-connect failed; it can retry from the title screen", throwable);
            }
        }

        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START) {
                return;
            }

            Minecraft minecraft = Minecraft.m_91087_();
            RelativeTouch.applyPendingDelta(minecraft);
        }

        @SubscribeEvent
        public static void onScreenOpening(ScreenEvent.Opening event) {
            Screen newScreen = event.getNewScreen();
            Minecraft minecraft = Minecraft.m_91087_();
            logInfo("screen old="
                    + screenName(event.getCurrentScreen())
                    + " new=" + screenName(newScreen)
                    + " grabbed=" + minecraft.f_91067_.m_91600_());
            NhGameMemoryProfiler.NhAnonTimeline.mark("screen:" + screenName(newScreen));
            if (Boolean.getBoolean("newhorizon.lowPressure")
                    && newScreen instanceof DisconnectedScreen) {
                // Run after the screen transition so the title panorama is no longer
                // a live render dependency. This deliberately avoids a renderer reload.
                minecraft.execute(() -> NhMemoryGovernor.applyDisconnectedScreenTextureDiet(minecraft));
            }
            if (!joinedMultiplayer
                    && isMultiplayerScreen(event.getCurrentScreen())
                    && newScreen instanceof TitleScreen) {
                closingAfterDisconnect = true;
                minecraft.execute(minecraft::m_91395_);
                return;
            }

            if (joinedMultiplayer
                    && !closingAfterDisconnect
                    && (newScreen instanceof TitleScreen
                    || newScreen instanceof DisconnectedScreen)) {
                closingAfterDisconnect = true;
                joinedMultiplayer = false;
                minecraft.execute(minecraft::m_91395_);
                return;
            }

            if (!joinedMultiplayer
                    && (newScreen instanceof TitleScreen
                    || newScreen instanceof DisconnectedScreen)) {
                // The previous attempt did not reach a multiplayer world. Returning to
                // the title screen must permit a fresh attempt instead of latching forever.
                connectStarted = false;
                titleTicks = 0;
                gameplayTimelineMarked = false;
                localWorldStarted = false;
            }
        }

        private static void repairUnexpectedUngrab(Minecraft minecraft) {
            boolean gameplayOwnsInput = isGameplayActive(minecraft, minecraft.f_91080_);
            if (!gameplayOwnsInput || minecraft.f_91067_.m_91600_()) {
                ungrabTicks = 0;
                return;
            }

            ungrabTicks++;
            if (ungrabTicks < 1) {
                return;
            }

            ungrabTicks = 0;
            logInfo("repairing unexpected mouse release"
                    + " screen=null active=true");
            minecraft.f_91067_.m_91601_();
        }

        private static String screenName(Screen screen) {
            return screen == null ? "null" : screen.getClass().getName();
        }

        private static boolean isMultiplayerScreen(Screen screen) {
            if (screen == null) {
                return false;
            }
            String name = screen.getClass().getName();
            return name.equals("net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen")
                    || name.endsWith(".JoinMultiplayerScreen");
        }

        private static void persistServerWhenDue(Minecraft minecraft) {
            if (serverSaved) {
                return;
            }
            if (serverSaveRetryTicks > 0) {
                serverSaveRetryTicks--;
                return;
            }

            serverSaved = ensureServerSaved(minecraft);
            if (!serverSaved) {
                serverSaveRetryTicks = SERVER_SAVE_RETRY_TICKS;
            }
        }

        private static boolean ensureServerSaved(Minecraft minecraft) {
            try {
                ServerList serverList = new ServerList(minecraft);
                serverList.m_105431_();

                for (int index = 0; index < serverList.m_105445_(); index++) {
                    ServerData existing = serverList.m_105432_(index);
                    if (existing != null
                            && SERVER_ADDRESS.equalsIgnoreCase(existing.f_105363_)) {
                        if (!SERVER_NAME.equals(existing.f_105362_)) {
                            existing.f_105362_ = SERVER_NAME;
                            serverList.m_105442_();
                            logInfo("updated saved server "
                                    + SERVER_ADDRESS);
                        } else {
                            logInfo("saved server already present "
                                    + SERVER_ADDRESS);
                        }
                        return true;
                    }
                }

                serverList.m_105440_(new ServerData(
                        SERVER_NAME,
                        SERVER_ADDRESS,
                        false
                ));
                serverList.m_105442_();
                logInfo("saved server " + SERVER_ADDRESS);
                return true;
            } catch (Throwable throwable) {
                logWarn("could not save server; retry scheduled", throwable);
                return false;
            }
        }

        private static void connectToNewHorizon(Minecraft minecraft, Screen parent) {
            logInfo("auto-connect starting " + SERVER_ADDRESS);
            ServerData serverData = new ServerData(SERVER_NAME, SERVER_ADDRESS, false);
            ServerAddress address = ServerAddress.m_171864_(SERVER_ADDRESS);
            ConnectScreen.m_278792_(parent, minecraft, address, serverData, false);
        }

        private static boolean isLocalWorldTestEnabled() {
            return Boolean.parseBoolean(System.getProperty("newhorizon.localWorldTest", "false"));
        }

        private static String localWorldName() {
            String name = System.getProperty("newhorizon.localWorldName", "New_Horizon_GPU_Test");
            return name == null || name.trim().isEmpty() ? "New_Horizon_GPU_Test" : name.trim();
        }

        private static String localWorldDisplayName() {
            String name = System.getProperty(
                    "newhorizon.localWorldDisplayName", "New Horizon - Teste GPU");
            return name == null || name.trim().isEmpty()
                    ? "New Horizon - Teste GPU" : name.trim();
        }

        private static void openLocalTestWorld(Minecraft minecraft, String worldName) throws Exception {
            File worldDirectory = new File(new File(minecraft.f_91069_, "saves"), worldName);
            if (new File(worldDirectory, "level.dat").isFile()
                    || new File(worldDirectory, "level.dat_old").isFile()) {
                logInfo("local-world quick-play starting " + worldName);
                openExistingLocalWorld(minecraft, worldName);
                return;
            }

            logInfo("creating local superflat GPU test world " + worldName);
            LevelSettings settings = new LevelSettings(
                    localWorldDisplayName(),
                    GameType.CREATIVE,
                    false,
                    Difficulty.PEACEFUL,
                    true,
                    new GameRules(),
                    WorldDataConfiguration.f_244649_);
            WorldOptions options = new WorldOptions(0x4E485F4750555F54L, false, false);
            minecraft.m_231466_().m_233157_(worldName, settings, options, registryAccess -> {
                WorldPreset flatPreset = registryAccess
                        .m_175515_(Registries.f_256729_)
                        .m_246971_(WorldPresets.f_226438_)
                        .m_203334_();
                return flatPreset.m_247748_();
            });
        }

        private static void openExistingLocalWorld(Minecraft minecraft, String worldName)
                throws Exception {
            Class<?> quickPlayClass = Class.forName("net.minecraft.client.quickplay.QuickPlay");
            Method joinSingleplayer = quickPlayClass.getDeclaredMethod("m_278782_", Minecraft.class, String.class);
            joinSingleplayer.setAccessible(true);
            joinSingleplayer.invoke(null, minecraft, worldName);
        }
    }

    /**
     * Logs a coarse in-game memory inventory before we choose what to cut. The
     * process buckets come from /proc and the object counts come from Minecraft
     * client containers through reflection, so a missing obfuscated field logs
     * as -1 instead of crashing the client.
     */
    private static final class NhGameMemoryProfiler {
        private static final int SAMPLE_EVERY_TICKS = 200;
        private static final boolean ENABLED = false;
        private static final int MAX_DETAILS = 12;
        private static int tickCounter;
        private static boolean reflectionWarningLogged;

        private NhGameMemoryProfiler() {
        }

        private static void tick(Minecraft minecraft) {
            if (!ENABLED) {
                return;
            }
            tickCounter++;
            if (tickCounter < SAMPLE_EVERY_TICKS) {
                return;
            }
            tickCounter = 0;

            try {
                logProfile(minecraft);
            } catch (Throwable throwable) {
                if (!reflectionWarningLogged) {
                    reflectionWarningLogged = true;
                    logWarn("[NHGameMem] profiler failed once; continuing with partial data", throwable);
                }
            }
        }

        private static void logProfile(Minecraft minecraft) {
            Runtime runtime = Runtime.getRuntime();
            long heapMaxMb = bytesToMb(runtime.maxMemory());
            long heapCommittedMb = bytesToMb(runtime.totalMemory());
            long heapUsedMb = bytesToMb(runtime.totalMemory() - runtime.freeMemory());
            ProcMemory proc = ProcMemory.read();

            Object level = readField(minecraft, "f_91073_");
            Object options = readField(minecraft, "f_91066_");
            Object textureManager = invokeNoArg(minecraft, "m_91097_", "getTextureManager");
            Object levelRenderer = readField(minecraft, "f_91060_");
            Object soundManager = invokeNoArg(minecraft, "m_91106_", "getSoundManager");

            int renderDistance = readOptionInt(options, "m_231984_", "f_92106_");
            int simulationDistance = readOptionInt(options, "m_232001_", "f_193768_");
            int fpsLimit = readOptionInt(options, "m_232035_", "f_92113_");
            String particles = readOptionString(options, "m_231929_", null);
            String graphicsMode = readOptionString(options, "m_231818_", "f_92042_");

            int entities = countIterable(invokeNoArg(level, "m_104735_", "entitiesForRendering"));
            FieldCounts levelTopFields = countContainerFields(level, "");
            FieldCounts levelEntityFields = countContainerFields(level, "entity");
            FieldCounts levelBlockEntityFields = countContainerFields(level, "block");
            FieldCounts levelChunkFields = countContainerFields(level, "chunk");
            FieldCounts textureFields = countContainerFields(textureManager, "");
            TextureStats textureStats = TextureStats.read(textureManager);
            FieldCounts rendererTopFields = countContainerFields(levelRenderer, "");
            FieldCounts rendererFields = countContainerFields(levelRenderer, "chunk");
            FieldCounts soundFields = countContainerFields(soundManager, "");

            logInfo(String.format(Locale.ROOT,
                    "[NHGameMem] proc pssMb=%d rssMb=%d swapPssMb=%d vmSwapMb=%d "
                            + "heapUsedMb=%d heapCommittedMb=%d heapMaxMb=%d threads=%d "
                            + "options renderDistance=%d simulationDistance=%d fpsLimit=%d particles=%s graphics=%s "
                            + "entities=%d levelTop=%s entityContainers=%s blockEntityContainers=%s "
                            + "chunkContainers=%s rendererTop=%s renderChunkContainers=%s "
                            + "textureStats=%s textureContainers=%s soundContainers=%s "
                            + "androidMem=%s",
                    proc.pssMb, proc.rssMb, proc.swapPssMb, proc.vmSwapMb,
                    heapUsedMb, heapCommittedMb, heapMaxMb,
                    Thread.getAllStackTraces().size(),
                    renderDistance, simulationDistance, fpsLimit, particles, graphicsMode,
                    entities, levelTopFields, levelEntityFields, levelBlockEntityFields,
                    levelChunkFields, rendererTopFields, rendererFields,
                    textureStats, textureFields, soundFields,
                    safeString(NhMemoryGovernor.MemoryState.readFromBridgeSummary())));

            logInfo("[NHGameDeep] " + DeepStats.read(minecraft, textureManager, levelRenderer, soundManager));
            FullWeightStats.log(minecraft, textureManager, levelRenderer, soundManager);
            NhAnonTimeline.mark("profile-sample");
        }

        private static int readOptionInt(Object options, String methodName, String fieldName) {
            Object value = readOptionValue(options, methodName, fieldName);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        }

        private static String readOptionString(Object options, String methodName, String fieldName) {
            Object value = readOptionValue(options, methodName, fieldName);
            return value == null ? "null" : value.toString();
        }

        private static Object readOptionValue(Object options, String methodName, String fieldName) {
            try {
                Object option = methodName == null ? null : invokeNoArg(options, methodName);
                if (option == null && fieldName != null) {
                    option = readField(options, fieldName);
                }
                return option == null ? null : invokeNoArg(option, "m_231551_", "c", "get");
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object invokeNoArg(Object target, String... names) {
            if (target == null) {
                return null;
            }
            for (String name : names) {
                try {
                    Method method = target.getClass().getMethod(name);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                }
                try {
                    Method method = target.getClass().getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        private static Object readField(Object target, String name) {
            if (target == null || name == null) {
                return null;
            }
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                    type = type.getSuperclass();
                }
            }
            return null;
        }

        private static FieldCounts countContainerFields(Object owner, String filter) {
            FieldCounts counts = new FieldCounts();
            if (owner == null) {
                return counts;
            }
            String normalizedFilter = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
            Class<?> type = owner.getClass();
            while (type != null) {
                Field[] fields = type.getDeclaredFields();
                for (Field field : fields) {
                    String haystack = (field.getName() + " " + field.getType().getName())
                            .toLowerCase(Locale.ROOT);
                    if (!normalizedFilter.isEmpty() && !haystack.contains(normalizedFilter)) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object value = field.get(owner);
                        int count = countContainer(value);
                        if (count >= 0) {
                            counts.add(field.getName(), count);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                type = type.getSuperclass();
            }
            return counts;
        }

        private static Iterable<?> iterableValues(Object value) {
            if (value instanceof Map<?, ?>) {
                return ((Map<?, ?>) value).values();
            }
            if (value instanceof Iterable<?>) {
                return (Iterable<?>) value;
            }
            return null;
        }

        private static int countContainer(Object value) {
            if (value == null) {
                return -1;
            }
            if (value instanceof Map<?, ?>) {
                return ((Map<?, ?>) value).size();
            }
            if (value instanceof Collection<?>) {
                return ((Collection<?>) value).size();
            }
            Class<?> type = value.getClass();
            if (type.isArray()) {
                return Array.getLength(value);
            }
            try {
                Method size = type.getMethod("size");
                if (size.getParameterTypes().length == 0) {
                    Object result = size.invoke(value);
                    if (result instanceof Number) {
                        return ((Number) result).intValue();
                    }
                }
            } catch (Throwable ignored) {
            }
            if (value instanceof Iterable<?>) {
                return countIterable(value);
            }
            return -1;
        }

        private static int countIterable(Object iterable) {
            if (!(iterable instanceof Iterable<?>)) {
                return -1;
            }
            int count = 0;
            for (Object ignored : (Iterable<?>) iterable) {
                count++;
                if (count >= 100000) {
                    return count;
                }
            }
            return count;
        }

        private static long readStatusValueMb(String key) {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(key)) {
                        return kbToMb(parseLeadingLong(line.substring(key.length())));
                    }
                }
            } catch (Throwable ignored) {
            }
            return -1L;
        }

        private static ProcMemory readSmapsRollup() {
            ProcMemory memory = new ProcMemory();
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/smaps_rollup"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Pss:")) {
                        memory.pssMb = kbToMb(parseLeadingLong(line.substring(4)));
                    } else if (line.startsWith("Rss:")) {
                        memory.rssMb = kbToMb(parseLeadingLong(line.substring(4)));
                    } else if (line.startsWith("SwapPss:")) {
                        memory.swapPssMb = kbToMb(parseLeadingLong(line.substring(8)));
                    }
                }
            } catch (Throwable ignored) {
            }
            return memory;
        }

        private static long parseLeadingLong(String value) {
            String trimmed = value.trim();
            int end = 0;
            while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
                end++;
            }
            if (end == 0) {
                return 0L;
            }
            try {
                return Long.parseLong(trimmed.substring(0, end));
            } catch (Throwable ignored) {
                return 0L;
            }
        }

        private static long bytesToMb(long bytes) {
            return bytes <= 0L ? 0L : bytes / (1024L * 1024L);
        }

        private static long kbToMb(long kb) {
            return kb <= 0L ? 0L : kb / 1024L;
        }

        private static String safeString(String value) {
            if (value == null || value.isEmpty()) {
                return "null";
            }
            return value.replace('\n', ' ').replace('\r', ' ');
        }

        private static final class TextureStats {
            private static final int GL_TEXTURE_2D = 3553;
            private static final int GL_TEXTURE_BINDING_2D = 32873;
            private static final int GL_TEXTURE_WIDTH = 4096;
            private static final int GL_TEXTURE_HEIGHT = 4097;
            private static Method glBindTexture;
            private static Method glGetTexLevelParameteri;
            private static Method glGetInteger;
            int containerTextures;
            int queriedTextures;
            long estimatedKb;
            String details = "[]";

            static TextureStats read(Object textureManager) {
                TextureStats stats = new TextureStats();
                if (textureManager == null || !ensureGlMethods()) {
                    return stats;
                }

                List<TextureEntry> entries = new ArrayList<>();
                Class<?> type = textureManager.getClass();
                while (type != null) {
                    Field[] fields = type.getDeclaredFields();
                    for (Field field : fields) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(textureManager);
                            Iterable<?> iterable = iterableValues(value);
                            if (iterable == null) {
                                continue;
                            }
                            if (value instanceof Map<?, ?>) {
                                for (Map.Entry<?, ?> mapEntry : ((Map<?, ?>) value).entrySet()) {
                                    Object texture = mapEntry.getValue();
                                    readTextureEntry(stats, entries, texture, String.valueOf(mapEntry.getKey()));
                                }
                                continue;
                            }
                            for (Object texture : iterable) {
                                if (texture == null) {
                                    continue;
                                }
                                readTextureEntry(stats, entries, texture, null);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    type = type.getSuperclass();
                }

                entries.sort((left, right) -> Long.compare(right.estimatedKb, left.estimatedKb));
                StringBuilder builder = new StringBuilder("[");
                int limit = Math.min(MAX_DETAILS, entries.size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(entries.get(i));
                }
                builder.append(']');
                stats.details = builder.toString();
                return stats;
            }

            private static void readTextureEntry(TextureStats stats, List<TextureEntry> entries,
                                                 Object texture, String name) {
                if (texture == null) {
                    return;
                }
                stats.containerTextures++;
                int id = findTextureId(texture);
                if (id <= 0) {
                    return;
                }
                TextureEntry entry = queryTexture(id, texture.getClass().getSimpleName());
                entry.name = name;
                if (entry.estimatedKb > 0L) {
                    stats.queriedTextures++;
                    stats.estimatedKb += entry.estimatedKb;
                    entries.add(entry);
                }
            }

            private static boolean ensureGlMethods() {
                if (glBindTexture != null && glGetTexLevelParameteri != null && glGetInteger != null) {
                    return true;
                }
                try {
                    Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
                    glBindTexture = gl11.getMethod("glBindTexture", int.class, int.class);
                    glGetTexLevelParameteri = gl11.getMethod(
                            "glGetTexLevelParameteri", int.class, int.class, int.class);
                    glGetInteger = gl11.getMethod("glGetInteger", int.class);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }

            private static int findTextureId(Object texture) {
                Object preferred = invokeNoArg(texture, "m_117963_", "getId", "getGlId");
                if (preferred instanceof Number) {
                    int id = ((Number) preferred).intValue();
                    if (id > 0) {
                        return id;
                    }
                }
                Method[] methods = texture.getClass().getMethods();
                for (Method method : methods) {
                    if (method.getParameterTypes().length != 0
                            || method.getReturnType() != int.class
                            || "hashCode".equals(method.getName())) {
                        continue;
                    }
                    try {
                        method.setAccessible(true);
                        int id = ((Number) method.invoke(texture)).intValue();
                        if (id > 0 && id < 1000000) {
                            return id;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return -1;
            }

            private static TextureEntry queryTexture(int id, String className) {
                TextureEntry entry = new TextureEntry();
                entry.id = id;
                entry.className = className;
                int previous = 0;
                try {
                    previous = ((Number) glGetInteger.invoke(null, GL_TEXTURE_BINDING_2D)).intValue();
                    glBindTexture.invoke(null, GL_TEXTURE_2D, id);
                    for (int level = 0; level < 16; level++) {
                        int width = ((Number) glGetTexLevelParameteri.invoke(
                                null, GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH)).intValue();
                        int height = ((Number) glGetTexLevelParameteri.invoke(
                                null, GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT)).intValue();
                        if (width <= 0 || height <= 0) {
                            break;
                        }
                        if (level == 0) {
                            entry.width = width;
                            entry.height = height;
                        }
                        entry.levels++;
                        entry.estimatedKb += ((long) width * (long) height * 4L) / 1024L;
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        glBindTexture.invoke(null, GL_TEXTURE_2D, previous);
                    } catch (Throwable ignored) {
                    }
                }
                return entry;
            }

            @Override
            public String toString() {
                return "containers=" + containerTextures
                        + " queried=" + queriedTextures
                        + " estimatedMb=" + kbToMb(estimatedKb)
                        + details;
            }
        }

        private static final class TextureEntry {
            int id;
            int width;
            int height;
            int levels;
            long estimatedKb;
            String className;
            String name;

            @Override
            public String toString() {
                String label = name == null || name.isEmpty() || "null".equals(name)
                        ? className
                        : className + "@" + shortName(name);
                return label + "#" + id + "=" + width + "x" + height
                        + "x" + levels + ":" + kbToMb(estimatedKb) + "MB";
            }
        }

        private static String shortName(String value) {
            if (value == null) {
                return "null";
            }
            String clean = value.replace(' ', '_');
            return clean.length() <= 64 ? clean : clean.substring(0, 64) + "...";
        }

        private static final class DeepStats {
            private static String read(Object minecraft, Object textureManager,
                                       Object levelRenderer, Object soundManager) {
                StringBuilder builder = new StringBuilder(1024);
                appendFontStats(builder, minecraft);
                appendOwner(builder, "renderer", levelRenderer, 10);
                appendNamedField(builder, "renderer.f_109451_", levelRenderer, "f_109451_");
                appendNamedField(builder, "renderer.f_109452_", levelRenderer, "f_109452_");
                appendOwner(builder, "textures", textureManager, 8);
                appendOwner(builder, "models", readField(minecraft, "f_91051_"), 8);
                appendOwner(builder, "itemRenderer", readField(minecraft, "f_90995_"), 8);
                appendOwner(builder, "blockRenderer", readField(minecraft, "f_91052_"), 8);
                appendOwner(builder, "renderBuffers", readField(minecraft, "f_90993_"), 8);
                appendOwner(builder, "mainTarget", readField(minecraft, "f_91042_"), 8);
                appendOwner(builder, "entityModels", readField(minecraft, "f_167844_"), 8);
                appendOwner(builder, "search", readField(minecraft, "f_90997_"), 8);
                appendOwner(builder, "sounds", soundManager, 8);
                return builder.toString();
            }

            private static void appendFontStats(StringBuilder builder, Object minecraft) {
                Object fontManager = readField(minecraft, "f_91045_");
                List<Object> fontSets = new ArrayList<>();
                Object missingFontSet = readField(fontManager, "f_94998_");
                if (missingFontSet != null) {
                    fontSets.add(missingFontSet);
                }
                Object sets = readField(fontManager, "f_94999_");
                if (sets instanceof Map<?, ?>) {
                    for (Object fontSet : ((Map<?, ?>) sets).values()) {
                        if (fontSet != null) {
                            fontSets.add(fontSet);
                        }
                    }
                }

                int pages = 0;
                int bakedGlyphEntries = 0;
                int glyphInfoEntries = 0;
                int randomGlyphBuckets = 0;
                for (Object fontSet : fontSets) {
                    pages += Math.max(0, countContainer(readField(fontSet, "f_95059_")));
                    bakedGlyphEntries += deepCountCodepointMap(readField(fontSet, "f_95056_"));
                    glyphInfoEntries += deepCountCodepointMap(readField(fontSet, "f_95057_"));
                    randomGlyphBuckets += Math.max(0, countContainer(readField(fontSet, "f_95058_")));
                }
                builder.append("font{sets=").append(fontSets.size())
                        .append(",pages=").append(pages)
                        .append(",bakedGlyphEntries=").append(bakedGlyphEntries)
                        .append(",glyphInfoEntries=").append(glyphInfoEntries)
                        .append(",randomGlyphBuckets=").append(randomGlyphBuckets)
                        .append("} ");
            }

            private static int deepCountCodepointMap(Object map) {
                if (map == null) {
                    return 0;
                }
                int total = 0;
                Class<?> type = map.getClass();
                while (type != null) {
                    for (Field field : type.getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(map);
                            int count = countContainer(value);
                            if (count > 0) {
                                total += count;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    type = type.getSuperclass();
                }
                return total;
            }

            private static void appendNamedField(StringBuilder builder, String label,
                                                 Object owner, String fieldName) {
                Object value = readField(owner, fieldName);
                builder.append(label).append('{')
                        .append(describeValue(value, 4))
                        .append("} ");
            }

            private static void appendOwner(StringBuilder builder, String label,
                                            Object owner, int limit) {
                builder.append(label).append('{');
                if (owner == null) {
                    builder.append("null} ");
                    return;
                }
                builder.append(owner.getClass().getSimpleName()).append(':');
                List<FieldCountEntry> entries = new ArrayList<>();
                Class<?> type = owner.getClass();
                while (type != null) {
                    for (Field field : type.getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(owner);
                            int count = countContainer(value);
                            if (count >= 0) {
                                entries.add(new FieldCountEntry(field.getName()
                                        + "/" + field.getType().getSimpleName()
                                        + "/" + valueClassName(value), count));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    type = type.getSuperclass();
                }
                entries.sort((left, right) -> Integer.compare(right.count, left.count));
                for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(entries.get(i));
                }
                builder.append("} ");
            }

            private static String describeValue(Object value, int sampleLimit) {
                if (value == null) {
                    return "null";
                }
                int count = countContainer(value);
                StringBuilder builder = new StringBuilder();
                builder.append(valueClassName(value)).append("#").append(count);
                Iterable<?> iterable = iterableValues(value);
                if (iterable != null) {
                    builder.append('[');
                    int index = 0;
                    for (Object item : iterable) {
                        if (index > 0) {
                            builder.append(',');
                        }
                        builder.append(valueClassName(item));
                        index++;
                        if (index >= sampleLimit) {
                            break;
                        }
                    }
                    builder.append(']');
                }
                return builder.toString();
            }

            private static String valueClassName(Object value) {
                return value == null ? "null" : value.getClass().getSimpleName();
            }
        }

        private static final class NhAnonTimeline {
            private static final Object LOCK = new Object();
            private static long sequence;
            private static boolean running;
            private static long lastSizeMb = -1L;
            private static long lastRssMb = -1L;
            private static long lastPssMb = -1L;
            private static long lastSwapMb = -1L;

            private NhAnonTimeline() {
            }

            private static void mark(String phase) {
                final long id;
                synchronized (LOCK) {
                    id = ++sequence;
                    if (running) {
                        return;
                    }
                    running = true;
                }

                Thread thread = new Thread(() -> {
                    try {
                        FullWeightStats.Snapshot snapshot = FullWeightStats.readLargestAnonSnapshot();
                        long deltaSize = lastSizeMb < 0L ? 0L : snapshot.sizeMb - lastSizeMb;
                        long deltaRss = lastRssMb < 0L ? 0L : snapshot.rssMb - lastRssMb;
                        long deltaPss = lastPssMb < 0L ? 0L : snapshot.pssMb - lastPssMb;
                        long deltaSwap = lastSwapMb < 0L ? 0L : snapshot.swapMb - lastSwapMb;
                        lastSizeMb = snapshot.sizeMb;
                        lastRssMb = snapshot.rssMb;
                        lastPssMb = snapshot.pssMb;
                        lastSwapMb = snapshot.swapMb;
                        logInfo("[NHAnonTimeline] seq=" + id
                                + " phase=" + safePhase(phase)
                                + " sizeMb=" + snapshot.sizeMb
                                + " rssMb=" + snapshot.rssMb
                                + " pssMb=" + snapshot.pssMb
                                + " swapMb=" + snapshot.swapMb
                                + " dSizeMb=" + deltaSize
                                + " dRssMb=" + deltaRss
                                + " dPssMb=" + deltaPss
                                + " dSwapMb=" + deltaSwap
                                + " kind=" + snapshot.kind
                                + " perms=" + snapshot.perms
                                + " flags=" + snapshot.flags
                                + " name=" + snapshot.name);
                    } catch (Throwable throwable) {
                        logInfo("[NHAnonTimeline] seq=" + id
                                + " phase=" + safePhase(phase)
                                + " failed=" + throwable.getClass().getSimpleName());
                    } finally {
                        synchronized (LOCK) {
                            running = false;
                        }
                    }
                }, "NH-AnonTimeline");
                thread.setDaemon(true);
                thread.start();
            }

            private static String safePhase(String phase) {
                if (phase == null) {
                    return "null";
                }
                return shortName(phase.replace(' ', '_').replace(',', '_'));
            }
        }

        private static final class FullWeightStats {
            private static final int DETAIL_LIMIT = 10;

            private static Snapshot readLargestAnonSnapshot() {
                return SmapsStats.read().largestAnonSnapshot();
            }

            private static String readMapStats() {
                return SmapsStats.read().formatMaps();
            }

            private static void log(Object minecraft, Object textureManager,
                                    Object levelRenderer, Object soundManager) {
                try {
                    SmapsStats smaps = SmapsStats.read();
                    logInfo("[NHWeightProc] " + smaps.formatBuckets());
                    logInfo("[NHWeightMaps] " + smaps.formatMaps());
                } catch (Throwable throwable) {
                    logInfo("[NHWeightProc] failed=" + throwable.getClass().getSimpleName());
                }
                try {
                    logInfo("[NHWeightJava] " + readJavaPools());
                } catch (Throwable throwable) {
                    logInfo("[NHWeightJava] failed=" + throwable.getClass().getSimpleName());
                }
                try {
                    logInfo("[NHWeightModels] " + readModelStats(readField(minecraft, "f_91051_")));
                } catch (Throwable throwable) {
                    logInfo("[NHWeightModels] failed=" + throwable.getClass().getSimpleName());
                }
                try {
                    logInfo("[NHWeightSounds] " + readSoundStats(soundManager));
                } catch (Throwable throwable) {
                    logInfo("[NHWeightSounds] failed=" + throwable.getClass().getSimpleName());
                }
                try {
                    logInfo("[NHWeightOwners] "
                            + "textureManager=" + deepOwnerBytes(textureManager)
                            + " levelRenderer=" + deepOwnerBytes(levelRenderer)
                            + " renderBuffers=" + deepOwnerBytes(readField(minecraft, "f_90993_"))
                            + " itemRenderer=" + deepOwnerBytes(readField(minecraft, "f_90995_"))
                            + " mainTarget=" + deepOwnerBytes(readField(minecraft, "f_91042_"))
                            + " entityModels=" + deepOwnerBytes(readField(minecraft, "f_167844_")));
                } catch (Throwable throwable) {
                    logInfo("[NHWeightOwners] failed=" + throwable.getClass().getSimpleName());
                }
            }

            private static String readJavaPools() {
                StringBuilder builder = new StringBuilder(512);
                Runtime runtime = Runtime.getRuntime();
                builder.append("heapUsedMb=").append(bytesToMb(runtime.totalMemory() - runtime.freeMemory()))
                        .append(" heapCommittedMb=").append(bytesToMb(runtime.totalMemory()))
                        .append(" heapMaxMb=").append(bytesToMb(runtime.maxMemory()));
                builder.append(" pools=[");
                int index = 0;
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    try {
                        long used = pool.getUsage() == null ? -1L : pool.getUsage().getUsed();
                        long committed = pool.getUsage() == null ? -1L : pool.getUsage().getCommitted();
                        if (index++ > 0) {
                            builder.append(',');
                        }
                        builder.append(shortName(pool.getName()))
                                .append(":used=").append(bytesToMb(used))
                                .append(" committed=").append(bytesToMb(committed));
                    } catch (Throwable ignored) {
                    }
                }
                builder.append("] buffers=[");
                index = 0;
                for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                    if (index++ > 0) {
                        builder.append(',');
                    }
                    builder.append(pool.getName())
                            .append(":count=").append(pool.getCount())
                            .append(" usedMb=").append(bytesToMb(pool.getMemoryUsed()))
                            .append(" capacityMb=").append(bytesToMb(pool.getTotalCapacity()));
                }
                builder.append("] gc=[");
                index = 0;
                for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    if (index++ > 0) {
                        builder.append(',');
                    }
                    builder.append(shortName(gc.getName()))
                            .append(":count=").append(gc.getCollectionCount())
                            .append(" timeMs=").append(gc.getCollectionTime());
                }
                builder.append(']');
                return builder.toString();
            }

            private static String readModelStats(Object modelManager) {
                Object bakedMap = readField(modelManager, "f_119397_");
                Object groupMap = readField(modelManager, "f_119404_");
                IdentityHashMap<Object, Boolean> uniqueModels = new IdentityHashMap<>();
                HashMap<String, Integer> keyBuckets = new HashMap<>();
                HashMap<String, Integer> valueClasses = new HashMap<>();
                int entries = 0;
                if (bakedMap instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) bakedMap).entrySet()) {
                        entries++;
                        addBucket(keyBuckets, modelKeyBucket(String.valueOf(entry.getKey())));
                        Object value = entry.getValue();
                        if (value != null) {
                            uniqueModels.put(value, Boolean.TRUE);
                            addBucket(valueClasses, value.getClass().getSimpleName());
                        }
                    }
                }
                return "bakedEntries=" + entries
                        + " uniqueBakedModels=" + uniqueModels.size()
                        + " groupEntries=" + countContainer(groupMap)
                        + " keyBuckets=" + topBuckets(keyBuckets, DETAIL_LIMIT)
                        + " valueClasses=" + topBuckets(valueClasses, DETAIL_LIMIT)
                        + " bakedApprox=" + approximateContainerWeight(bakedMap)
                        + " groupsApprox=" + approximateContainerWeight(groupMap);
            }

            private static String readSoundStats(Object soundManager) {
                Object first = readField(soundManager, "f_244170_");
                Object second = readField(soundManager, "f_120348_");
                HashMap<String, Integer> firstClasses = classBuckets(first);
                HashMap<String, Integer> secondClasses = classBuckets(second);
                return "f_244170=" + countContainer(first)
                        + " classes=" + topBuckets(firstClasses, DETAIL_LIMIT)
                        + " approx=" + approximateContainerWeight(first)
                        + " f_120348=" + countContainer(second)
                        + " classes=" + topBuckets(secondClasses, DETAIL_LIMIT)
                        + " approx=" + approximateContainerWeight(second);
            }

            private static HashMap<String, Integer> classBuckets(Object container) {
                HashMap<String, Integer> buckets = new HashMap<>();
                Iterable<?> iterable = iterableValues(container);
                if (iterable != null) {
                    for (Object value : iterable) {
                        addBucket(buckets, value == null ? "null" : value.getClass().getSimpleName());
                    }
                }
                return buckets;
            }

            private static String deepOwnerBytes(Object owner) {
                if (owner == null) {
                    return "null";
                }
                long approx = 0L;
                int containers = 0;
                HashMap<String, Integer> fields = new HashMap<>();
                Class<?> type = owner.getClass();
                while (type != null) {
                    for (Field field : type.getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(owner);
                            int count = countContainer(value);
                            if (count >= 0) {
                                containers++;
                                approx += approximateBytes(value);
                                addBucket(fields, field.getName() + "/" + valueClassName(value) + "#" + count);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    type = type.getSuperclass();
                }
                return owner.getClass().getSimpleName()
                        + "{containers=" + containers
                        + ",approxKb=" + (approx / 1024L)
                        + ",top=" + topBuckets(fields, 5) + "}";
            }

            private static String valueClassName(Object value) {
                return value == null ? "null" : value.getClass().getSimpleName();
            }

            private static String approximateContainerWeight(Object value) {
                return "count=" + countContainer(value)
                        + ",approxKb=" + (approximateBytes(value) / 1024L);
            }

            private static long approximateBytes(Object value) {
                int count = countContainer(value);
                if (count <= 0) {
                    return 0L;
                }
                Class<?> type = value.getClass();
                if (type.isArray()) {
                    Class<?> component = type.getComponentType();
                    int elementSize = component.isPrimitive() ? primitiveSize(component) : 8;
                    return 16L + (long) count * elementSize;
                }
                if (value instanceof Map<?, ?>) {
                    return 64L + (long) count * 64L;
                }
                if (value instanceof Collection<?>) {
                    return 48L + (long) count * 24L;
                }
                return 32L + (long) count * 16L;
            }

            private static int primitiveSize(Class<?> type) {
                if (type == boolean.class || type == byte.class) return 1;
                if (type == char.class || type == short.class) return 2;
                if (type == int.class || type == float.class) return 4;
                if (type == long.class || type == double.class) return 8;
                return 4;
            }

            private static String modelKeyBucket(String key) {
                String lower = key.toLowerCase(Locale.ROOT);
                String namespace = "unknown";
                int colon = lower.indexOf(':');
                if (colon > 0) {
                    namespace = lower.substring(0, colon);
                }
                String path = colon >= 0 ? lower.substring(colon + 1) : lower;
                String type;
                if (path.contains("inventory") || path.contains("#inventory")) {
                    type = "inventory";
                } else if (path.contains("item/") || path.contains("/item")) {
                    type = "item";
                } else if (path.contains("block/") || path.contains("/block")) {
                    type = "block";
                } else if (path.contains("builtin") || path.contains("generated")) {
                    type = "builtin";
                } else {
                    type = "other";
                }
                return namespace + "/" + type;
            }

            private static void addBucket(HashMap<String, Integer> buckets, String key) {
                buckets.put(key, buckets.getOrDefault(key, 0) + 1);
            }

            private static String topBuckets(HashMap<String, Integer> buckets, int limit) {
                List<Map.Entry<String, Integer>> entries = new ArrayList<>(buckets.entrySet());
                entries.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
                StringBuilder builder = new StringBuilder("[");
                for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(shortName(entries.get(i).getKey()))
                            .append('=').append(entries.get(i).getValue());
                }
                builder.append(']');
                return builder.toString();
            }

            private static final class SmapsStats {
                private final HashMap<String, SmapsBucket> buckets = new HashMap<>();
                private final List<SmapsMapEntry> maps = new ArrayList<>();

                static SmapsStats read() {
                    SmapsStats stats = new SmapsStats();
                    stats.readFile();
                    return stats;
                }

                private void readFile() {
                    String current = "unknown";
                    String currentHeader = "unknown";
                    SmapsMapEntry currentEntry = null;
                    boolean inMapping = false;
                    try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/smaps"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (isMappingHeader(line)) {
                                if (inMapping) {
                                    add(currentEntry);
                                }
                                currentHeader = line;
                                current = classifyMapping(line);
                                currentEntry = new SmapsMapEntry();
                                currentEntry.bucket = current;
                                currentEntry.header = sanitizeHeader(line);
                                currentEntry.rawHeader = shortName(line.trim());
                                parseHeader(line, currentEntry);
                                inMapping = true;
                            } else if (line.startsWith("Pss:")) {
                                if (currentEntry != null) currentEntry.pssKb = parseLeadingLong(line.substring(4));
                            } else if (line.startsWith("Rss:")) {
                                if (currentEntry != null) currentEntry.rssKb = parseLeadingLong(line.substring(4));
                            } else if (line.startsWith("Size:")) {
                                if (currentEntry != null) currentEntry.sizeKb = parseLeadingLong(line.substring(5));
                            } else if (line.startsWith("Shared_Clean:")) {
                                if (currentEntry != null) currentEntry.sharedCleanKb = parseLeadingLong(line.substring(13));
                            } else if (line.startsWith("Shared_Dirty:")) {
                                if (currentEntry != null) currentEntry.sharedDirtyKb = parseLeadingLong(line.substring(13));
                            } else if (line.startsWith("Private_Clean:")) {
                                if (currentEntry != null) currentEntry.privateCleanKb = parseLeadingLong(line.substring(14));
                            } else if (line.startsWith("Private_Dirty:")) {
                                if (currentEntry != null) currentEntry.privateDirtyKb = parseLeadingLong(line.substring(14));
                            } else if (line.startsWith("Swap:")) {
                                if (currentEntry != null) currentEntry.swapKb = parseLeadingLong(line.substring(5));
                            } else if (line.startsWith("SwapPss:")) {
                                if (currentEntry != null) currentEntry.swapPssKb = parseLeadingLong(line.substring(8));
                            } else if (line.startsWith("AnonHugePages:")) {
                                if (currentEntry != null) currentEntry.anonHugePagesKb = parseLeadingLong(line.substring(14));
                            } else if (line.startsWith("VmFlags:")) {
                                if (currentEntry != null) currentEntry.vmFlags = shortName(line.substring(8).trim());
                            }
                        }
                        if (inMapping) {
                            add(currentEntry);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                private static boolean isMappingHeader(String line) {
                    return line.length() > 16
                            && line.indexOf('-') > 0
                            && line.indexOf(':') > 0
                            && line.charAt(0) != ' ';
                }

                private static String classifyMapping(String header) {
                    String lower = header.toLowerCase(Locale.ROOT);
                    String name = "";
                    int slash = lower.indexOf('/');
                    int bracket = lower.indexOf('[');
                    int start = slash >= 0 ? slash : bracket;
                    if (start >= 0) {
                        name = lower.substring(start);
                    }
                    if (lower.contains("[heap]")) return "heap";
                    if (lower.contains("[stack")) return "stack";
                    if (name.contains(".so")) return "so";
                    if (name.contains(".jar")) return "jar";
                    if (name.contains(".dex")) return "dex";
                    if (name.contains(".apk")) return "apk";
                    if (name.contains(".ttf") || name.contains(".otf")) return "fontfile";
                    if (name.contains("/dev/")) return "dev";
                    if (name.contains("kgsl") || name.contains("gpu")) return "gpu";
                    if (name.contains("ashmem")) return "ashmem";
                    if (name.isEmpty()) return "anon";
                    return shortName(name);
                }

                private static void parseHeader(String header, SmapsMapEntry entry) {
                    if (header == null || entry == null) {
                        return;
                    }
                    try {
                        String[] parts = header.trim().split("\\s+");
                        if (parts.length > 0) {
                            String[] range = parts[0].split("-");
                            if (range.length == 2) {
                                entry.start = Long.parseUnsignedLong(range[0], 16);
                                entry.end = Long.parseUnsignedLong(range[1], 16);
                            }
                        }
                        if (parts.length > 1) {
                            entry.perms = parts[1];
                        }
                        if (parts.length > 2) {
                            entry.offset = parts[2];
                        }
                    } catch (Throwable ignored) {
                    }
                }

                private void add(SmapsMapEntry entry) {
                    if (entry == null) {
                        return;
                    }
                    SmapsBucket bucket = buckets.computeIfAbsent(entry.bucket, ignored -> new SmapsBucket());
                    bucket.pssKb += entry.pssKb;
                    bucket.swapKb += entry.swapPssKb;
                    bucket.count++;
                    if (entry.pssKb > 0L || entry.swapPssKb > 0L || entry.swapKb > 0L) {
                        entry.kind = inferMapKind(entry);
                        maps.add(entry);
                    }
                }

                private static String inferMapKind(SmapsMapEntry entry) {
                    String header = entry.rawHeader == null ? "" : entry.rawHeader.toLowerCase(Locale.ROOT);
                    String name = entry.header == null ? "" : entry.header.toLowerCase(Locale.ROOT);
                    String perms = entry.perms == null ? "" : entry.perms;
                    String flags = entry.vmFlags == null ? "" : entry.vmFlags;
                    long sizeMb = kbToMb(entry.sizeKb);
                    if (name.contains("libgl") || name.contains("libgallium") || name.contains("kgsl") || name.contains("vulkan")) {
                        return "graphics-driver";
                    }
                    if (name.contains("lwjgl") || name.contains("glfw") || name.contains("openal")) {
                        return "lwjgl/native-lib";
                    }
                    if (name.contains("libjvm") || name.contains("/runtimes/internal-17") || name.contains("server/lib")) {
                        return "jvm-runtime";
                    }
                    if (name.contains("[anon:libc_malloc]")) {
                        return "bionic-malloc-arena";
                    }
                    if (name.contains("[anon:jemalloc]")) {
                        return "jemalloc-arena";
                    }
                    if (name.contains("[anon:js-gc-heap]")) {
                        return "js-gc-heap";
                    }
                    if (name.contains("[anon:dalvik")) {
                        return "android-runtime";
                    }
                    if (name.contains(".dex") || name.contains(".oat") || name.contains(".art")) {
                        return "android-code-cache";
                    }
                    if (name.contains(".apk") || name.contains(".jar")) {
                        return "mapped-archive";
                    }
                    if (name.contains(".so")) {
                        return "native-library";
                    }
                    if (perms.contains("x")) {
                        return "jit-or-executable-anon";
                    }
                    if (header.contains("00:00 0") && sizeMb >= 256 && perms.startsWith("rw")) {
                        if (flags.contains("nh") || entry.anonHugePagesKb > 0L) {
                            return "large-anon-native/hugepage";
                        }
                        return "large-anon-native-reservation";
                    }
                    if (header.contains("00:00 0") && perms.startsWith("rw")) {
                        return "anonymous-rw";
                    }
                    return entry.bucket;
                }

                private static String sanitizeHeader(String header) {
                    if (header == null) {
                        return "null";
                    }
                    String clean = header.trim().replace('\t', ' ');
                    while (clean.contains("  ")) {
                        clean = clean.replace("  ", " ");
                    }
                    int pathStart = clean.indexOf('/');
                    int bracketStart = clean.indexOf('[');
                    int start = pathStart >= 0 ? pathStart : bracketStart;
                    if (start >= 0) {
                        clean = clean.substring(start);
                    }
                    return shortName(clean);
                }

                private String formatBuckets() {
                    List<Map.Entry<String, SmapsBucket>> entries = new ArrayList<>(buckets.entrySet());
                    entries.sort((left, right) -> Long.compare(right.getValue().swapKb, left.getValue().swapKb));
                    long totalPss = 0L;
                    long totalSwap = 0L;
                    for (SmapsBucket bucket : buckets.values()) {
                        totalPss += bucket.pssKb;
                        totalSwap += bucket.swapKb;
                    }
                    StringBuilder builder = new StringBuilder();
                    builder.append("totalPssMb=").append(kbToMb(totalPss))
                            .append(" totalSwapMb=").append(kbToMb(totalSwap))
                            .append(" topSwap=[");
                    for (int i = 0; i < Math.min(DETAIL_LIMIT, entries.size()); i++) {
                        if (i > 0) {
                            builder.append(',');
                        }
                        Map.Entry<String, SmapsBucket> entry = entries.get(i);
                        builder.append(entry.getKey())
                                .append(":pss=").append(kbToMb(entry.getValue().pssKb))
                                .append(" swap=").append(kbToMb(entry.getValue().swapKb))
                                .append(" maps=").append(entry.getValue().count);
                    }
                    builder.append(']');
                    return builder.toString();
                }

                private String formatMaps() {
                    maps.sort((left, right) -> {
                        int bySwap = Long.compare(right.swapPssKb, left.swapPssKb);
                        return bySwap != 0 ? bySwap : Long.compare(right.pssKb, left.pssKb);
                    });
                    StringBuilder builder = new StringBuilder();
                    builder.append("topSwapMaps=[");
                    for (int i = 0; i < Math.min(DETAIL_LIMIT, maps.size()); i++) {
                        if (i > 0) {
                            builder.append(',');
                        }
                        SmapsMapEntry entry = maps.get(i);
                        appendMap(builder, entry);
                    }
                    builder.append("] giantAnonMaps=[");
                    int giantCount = 0;
                    for (SmapsMapEntry entry : maps) {
                        if (entry.sizeKb >= 64L * 1024L && ("anon".equals(entry.bucket) || "anonymous-rw".equals(entry.kind) || entry.kind.startsWith("large-anon"))) {
                            if (giantCount++ > 0) {
                                builder.append(',');
                            }
                            appendMap(builder, entry);
                            if (giantCount >= DETAIL_LIMIT) {
                                break;
                            }
                        }
                    }
                    builder.append("] topPssMaps=[");
                    maps.sort((left, right) -> Long.compare(right.pssKb, left.pssKb));
                    for (int i = 0; i < Math.min(DETAIL_LIMIT, maps.size()); i++) {
                        if (i > 0) {
                            builder.append(',');
                        }
                        SmapsMapEntry entry = maps.get(i);
                        appendMap(builder, entry);
                    }
                    builder.append("] topSizeMaps=[");
                    maps.sort((left, right) -> Long.compare(right.sizeKb, left.sizeKb));
                    for (int i = 0; i < Math.min(DETAIL_LIMIT, maps.size()); i++) {
                        if (i > 0) {
                            builder.append(',');
                        }
                        appendMap(builder, maps.get(i));
                    }
                    builder.append(']');
                    return builder.toString();
                }

                private Snapshot largestAnonSnapshot() {
                    SmapsMapEntry best = null;
                    for (SmapsMapEntry entry : maps) {
                        if (entry == null) {
                            continue;
                        }
                        boolean anonymous = "anon".equals(entry.bucket)
                                || "anonymous-rw".equals(entry.kind)
                                || (entry.kind != null && entry.kind.startsWith("large-anon"));
                        if (!anonymous) {
                            continue;
                        }
                        if (best == null
                                || entry.sizeKb > best.sizeKb
                                || (entry.sizeKb == best.sizeKb && entry.swapPssKb > best.swapPssKb)) {
                            best = entry;
                        }
                    }
                    if (best == null) {
                        return new Snapshot();
                    }
                    Snapshot snapshot = new Snapshot();
                    snapshot.sizeMb = kbToMb(best.sizeKb);
                    snapshot.rssMb = kbToMb(best.rssKb);
                    snapshot.pssMb = kbToMb(best.pssKb);
                    snapshot.swapMb = kbToMb(best.swapPssKb);
                    snapshot.kind = best.kind == null ? "null" : best.kind;
                    snapshot.perms = best.perms == null ? "null" : best.perms;
                    snapshot.flags = best.vmFlags == null ? "null" : best.vmFlags;
                    snapshot.name = best.header == null ? "null" : best.header;
                    return snapshot;
                }

                private static void appendMap(StringBuilder builder, SmapsMapEntry entry) {
                    builder.append(entry.bucket)
                            .append('/').append(entry.kind)
                            .append(":size=").append(kbToMb(entry.sizeKb))
                            .append(" rss=").append(kbToMb(entry.rssKb))
                            .append(" pss=").append(kbToMb(entry.pssKb))
                            .append(" priv=").append(kbToMb(entry.privateCleanKb + entry.privateDirtyKb))
                            .append(" sh=").append(kbToMb(entry.sharedCleanKb + entry.sharedDirtyKb))
                            .append(" swap=").append(kbToMb(entry.swapPssKb))
                            .append(" swapRaw=").append(kbToMb(entry.swapKb))
                            .append(" huge=").append(kbToMb(entry.anonHugePagesKb))
                            .append(" perms=").append(entry.perms)
                            .append(" flags=").append(entry.vmFlags)
                            .append(" name=").append(entry.header);
                }
            }

            private static final class SmapsBucket {
                long pssKb;
                long swapKb;
                int count;
            }

            private static final class SmapsMapEntry {
                String bucket;
                String header;
                String rawHeader;
                String kind;
                String perms;
                String offset;
                String vmFlags;
                long start;
                long end;
                long sizeKb;
                long rssKb;
                long pssKb;
                long swapKb;
                long swapPssKb;
                long privateCleanKb;
                long privateDirtyKb;
                long sharedCleanKb;
                long sharedDirtyKb;
                long anonHugePagesKb;
            }

            private static final class Snapshot {
                long sizeMb = -1L;
                long rssMb = -1L;
                long pssMb = -1L;
                long swapMb = -1L;
                String kind = "none";
                String perms = "none";
                String flags = "none";
                String name = "none";
            }
        }

        private static final class ProcMemory {
            long pssMb = -1L;
            long rssMb = -1L;
            long swapPssMb = -1L;
            long vmSwapMb = -1L;

            static ProcMemory read() {
                ProcMemory memory = readSmapsRollup();
                if (memory.rssMb < 0L) {
                    memory.rssMb = readStatusValueMb("VmRSS:");
                }
                memory.vmSwapMb = readStatusValueMb("VmSwap:");
                return memory;
            }
        }

        private static final class FieldCounts {
            private final List<FieldCountEntry> entries = new ArrayList<>();
            private int total;

            void add(String name, int count) {
                total += Math.max(0, count);
                entries.add(new FieldCountEntry(name, count));
            }

            @Override
            public String toString() {
                if (entries.isEmpty()) {
                    return "total=0[]";
                }
                entries.sort((left, right) -> Integer.compare(right.count, left.count));
                StringBuilder builder = new StringBuilder("total=");
                builder.append(total).append('[');
                int limit = Math.min(MAX_DETAILS, entries.size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(entries.get(i));
                }
                builder.append(']');
                return builder.toString();
            }
        }

        private static final class FieldCountEntry {
            final String name;
            final int count;

            FieldCountEntry(String name, int count) {
                this.name = name;
                this.count = count;
            }

            @Override
            public String toString() {
                return name + "=" + count;
            }
        }
    }

    /**
     * Reads the Android-side NH memory state and tightens expensive Minecraft
     * client options before Samsung KPM/LMKD reaches the hard zRAM thrash zone.
     *
     * <p>This first build tightens quickly and does not restore automatically.
     * Restoring render distance while the player is moving can allocate chunks,
     * rebuild renderers and create the hitch we are trying to avoid.</p>
     */
    private static final class NhMemoryGovernor {
        private static final String STATE_PATH =
                "/data/user/0/com.newhorizon.minecraft/cache/nh_mem_state.json";
        private static final String FALLBACK_STATE_PATH =
                "/data/data/com.newhorizon.minecraft/cache/nh_mem_state.json";
        // The Android sampler updates once per second. Polling its cached state
        // four times per second only created JSON/reflection churn after the
        // quality profile had already settled. Two seconds is still fast enough
        // for one-way pressure cuts and keeps the render thread quiet.
        private static final int SAMPLE_EVERY_TICKS = 40;
        private static final int STALE_STATE_MS = 5000;
        private static final String[] REQUIRED_MODEL_IDS = {
                "air", "stone", "dirt", "grass_block", "cobblestone", "bedrock",
                "oak_planks", "oak_log", "oak_leaves", "glass", "chest", "barrel",
                "crafting_table", "furnace", "anvil", "bookshelf", "water", "lava",
                "sand", "gravel", "obsidian", "fire", "tnt", "ladder", "torch",
                "white_wool", "red_wool", "blue_wool", "lime_wool", "yellow_wool",
                "bow", "arrow", "shield", "fishing_rod", "ender_pearl", "snowball",
                "egg", "diamond_sword", "iron_sword", "stone_sword", "wooden_sword",
                "diamond_pickaxe", "iron_pickaxe", "apple", "golden_apple", "bread",
                "potion", "splash_potion", "water_bucket", "lava_bucket", "compass",
                "clock", "map", "player_head", "leather_helmet", "leather_chestplate",
                "leather_leggings", "leather_boots", "iron_helmet", "iron_chestplate",
                "iron_leggings", "iron_boots", "diamond_helmet", "diamond_chestplate",
                "diamond_leggings", "diamond_boots"
        };
        private static final String[] REQUIRED_MODEL_NEEDLES =
                buildRequiredModelNeedles();

        private static int tickCounter;
        private static int activeLevel;
        private static int lastDesiredLevel = -1;
        private static boolean reflectionFailureLogged;
        private static boolean optionsFileDietApplied;
        private static boolean fontTrimFailureLogged;
        private static boolean unicodeGlyphDietFailureLogged;
        private static boolean textureDietFailureLogged;
        private static boolean modelDietFailureLogged;
        private static boolean modelRegistryDietFailureLogged;
        private static boolean atlasImageDietFailureLogged;
        private static boolean entityLayerDietFailureLogged;
        private static boolean searchTreeDietFailureLogged;
        private static boolean panoramaDietApplied;
        private static boolean unicodeGlyphDietApplied;
        private static boolean modelGroupDietApplied;
        private static boolean modelRegistryDietApplied;
        private static boolean atlasImageDietApplied;
        private static boolean entityLayerDietApplied;
        private static boolean searchTreeDietApplied;
        private static boolean soundDietApplied;
        private static boolean bootstrapUnionFileSystemDietApplied;
        private static boolean bootstrapUnionFileSystemDietFailureLogged;
        private static boolean idleZipInflaterDietApplied;
        private static boolean idleZipInflaterDietFailureLogged;
        private static boolean bootstrapMetadataDietApplied;
        private static boolean bootstrapMetadataDietFailureLogged;
        private static boolean classHistogramLogged;
        private static boolean compilerCodeListWritten;
        private static boolean compilerCodeHeapAnalyticsWritten;
        private static boolean heapDumpWritten;
        private static boolean dynamicCdsDumpScheduled;
        private static int fontTrimCooldownTicks;
        private static boolean heapReturnRequested;
        private static boolean heapReturnDisabledLogged;

        private NhMemoryGovernor() {
        }

        private static void tick(Minecraft minecraft) {
            tickCounter++;
            if (tickCounter < SAMPLE_EVERY_TICKS) {
                return;
            }
            tickCounter = 0;

            MemoryState state = MemoryState.read();
            if (state == null) {
                return;
            }

            int desiredLevel = clamp(state.level, 0, 3);
            // Swap PSS is a historical residency counter, not current pressure.
            // React to the pressure level supplied by NHRamGuard and to truly
            // low physical headroom. A quiet 65+ MiB in zram must not trigger
            // repeated GC/reload work or destructive quality cuts.
            if (state.headroomMb > 0 && state.headroomMb <= 180) {
                desiredLevel = Math.max(desiredLevel, 3);
            } else if (state.headroomMb > 0 && state.headroomMb <= 260) {
                desiredLevel = Math.max(desiredLevel, 2);
            }
            if (desiredLevel == 0 || desiredLevel < activeLevel) {
                return;
            }
            if (desiredLevel == activeLevel && desiredLevel == lastDesiredLevel) {
                return;
            }

            lastDesiredLevel = desiredLevel;
            boolean changed = applyProfile(minecraft, desiredLevel);
            if (changed || desiredLevel != activeLevel) {
                activeLevel = Math.max(activeLevel, desiredLevel);
                logInfo("[NHMemoryGovernor] applied level=" + activeLevel
                        + " source=" + state.source
                        + " changed=" + changed
                        + " availMb=" + state.availMb
                        + " headroomMb=" + state.headroomMb
                        + " swapMb=" + state.swapMb
                        + " graphicsMb=" + state.graphicsMb
                        + " pssMb=" + state.pssMb
                        + " summary=" + state.summary);
                NhGameMemoryProfiler.NhAnonTimeline.mark("governor-level-" + activeLevel + "-changed-" + changed);
            }
        }

        private static void requestHeapReturn(String reason) {
            if (!runtimeHeapReturnEnabled()) {
                if (!heapReturnDisabledLogged) {
                    heapReturnDisabledLogged = true;
                    logInfo("[NHHeapReturn] runtime disabled by newhorizon.heapReturn=false");
                }
                return;
            }
            // Exactly one cleanup is allowed after bootstrap. Repeating full
            // GC/arena purges while Android is reclaiming memory faults cold
            // pages back from zRAM and creates the very stall we are avoiding.
            if (heapReturnRequested) {
                return;
            }
            heapReturnRequested = true;
            Thread thread = new Thread(() -> {
                try {
                    // Let the render thread finish the world-entry frame and the
                    // Gecko bridge publish its first texture before touching the
                    // VM heap.  This is a single post-bootstrap compaction, never
                    // a reactive pressure loop.
                    Thread.sleep(2500L);
                    Runtime runtime = Runtime.getRuntime();
                    long beforeCommitted = runtime.totalMemory() / (1024L * 1024L);
                    long beforeUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
                    logInfo("[NHHeapReturn] one-shot request reason=" + reason
                            + " beforeCommittedMb=" + beforeCommitted
                            + " beforeUsedMb=" + beforeUsed);
                    String nativeResult = "not-called";
                    try {
                        Object result = CallbackBridge.class
                                .getMethod("nativeHotspotHeapReturn", String.class)
                                .invoke(null, reason);
                        nativeResult = String.valueOf(result);
                    } catch (Throwable throwable) {
                        nativeResult = "failed:" + describeThrowableChain(throwable);
                        logInfo("[NHHeapReturn] native failed reason=" + reason
                                + " error=" + nativeResult);
                    }
                    long afterCommitted = runtime.totalMemory() / (1024L * 1024L);
                    long afterUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
                    logInfo("[NHHeapReturn] one-shot completed reason=" + reason
                            + " afterCommittedMb=" + afterCommitted
                            + " afterUsedMb=" + afterUsed
                            + " native=" + nativeResult);
                    NhGameMemoryProfiler.NhAnonTimeline.mark("heap-return-one-shot-" + reason);
                } catch (Throwable throwable) {
                    logInfo("[NHHeapReturn] failed reason=" + reason
                            + " error=" + describeThrowableChain(throwable));
                }
            }, "NH-HeapReturn");
            thread.setDaemon(true);
            thread.start();
        }

        private static boolean runtimeHeapReturnEnabled() {
            String value = System.getProperty("newhorizon.heapReturn", "false");
            return "1".equals(value)
                    || "true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value)
                    || "on".equalsIgnoreCase(value);
        }

        private static String describeThrowableChain(Throwable throwable) {
            if (throwable == null) {
                return "null";
            }
            StringBuilder builder = new StringBuilder(256);
            Throwable current = throwable;
            int depth = 0;
            while (current != null && depth < 6) {
                if (depth > 0) {
                    builder.append(" <- ");
                }
                builder.append(current.getClass().getName());
                String message = current.getMessage();
                if (message != null && message.length() > 0) {
                    builder.append(":").append(message.replace('\n', ' ').replace('\r', ' '));
                }
                current = current.getCause();
                depth++;
            }
            return builder.toString();
        }

        private static boolean applyProfile(Minecraft minecraft, int level) {
            try {
                Object options = readField(minecraft, "f_91066_");
                if (options == null) {
                    return false;
                }

                int renderDistance = level >= 3 ? 3 : level >= 2 ? 4 : 5;
                int simulationDistance = 5;
                int fps = 60;
                // 1.20.1 rejects sub-0.5 entity distance values in this build.
                // Keep the value legal; an illegal emergency profile causes the
                // client to log, reject and churn options on the render thread.
                double entityScale = 0.50D;

                boolean changed = false;
                changed |= setMaxIntOption(options, "m_231984_", "f_92106_", renderDistance);
                changed |= setMaxIntOption(options, "m_232001_", "f_193768_", simulationDistance);
                changed |= setMaxDoubleOption(options, "m_232018_", "f_92112_", entityScale);
                changed |= setMaxIntOption(options, "m_232035_", "f_92113_", fps);
                changed |= setMaxIntOption(options, null, "f_92027_", 0);
                changed |= setMaxIntOption(options, null, "f_92032_", 0);
                changed |= setBooleanOption(options, "m_231818_", "f_92042_", false);
                changed |= setEnumOption(options, "m_231929_", null, "MINIMAL", 2);
                changed |= setEnumOption(options, "m_232050_", "f_231792_", "OFF", 0);
                changed |= applyOptionsFileDiet(minecraft, level);
                // Never mutate live texture, font or baked-model registries.
                // WebDisplays and late entity/model creation depend on them.

                if (changed) {
                    invokeNoArg(options, "m_92169_", "aq", "save");
                    reloadLevelRenderer(minecraft);
                }
                return changed;
            } catch (Throwable throwable) {
                if (!reflectionFailureLogged) {
                    reflectionFailureLogged = true;
                    logWarn("[NHMemoryGovernor] disabled after reflection failure", throwable);
                }
                return false;
            }
        }

        private static boolean unloadInvisibleMenuTextures(Minecraft minecraft, int level) {
            if (panoramaDietApplied || level < 2) {
                return false;
            }
            panoramaDietApplied = true;

            try {
                Object textureManager = invokeNoArg(minecraft, "m_91097_", "getTextureManager");
                int removed = 0;
                removed += removeNamedTextures(textureManager, "f_118468_");
                removed += removeNamedTextures(textureManager, "f_118470_");
                if (removed > 0) {
                    logInfo("[NHMemoryGovernor] invisible menu textures unloaded"
                            + " level=" + level
                            + " removed=" + removed
                            + " targets=title-panorama/menu-background");
                    return true;
                }
                logInfo("[NHMemoryGovernor] invisible menu texture scan"
                        + " level=" + level
                        + " removed=0");
            } catch (Throwable throwable) {
                if (!textureDietFailureLogged) {
                    textureDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] invisible menu texture unload failed", throwable);
                }
            }
            return false;
        }

        /** Re-arms only caches that Minecraft's asynchronous resource reload recreates. */
        private static void prepareLateLiteReloadDietPass() {
            panoramaDietApplied = false;
            unicodeGlyphDietApplied = false;
            modelGroupDietApplied = false;
            modelRegistryDietApplied = false;
            atlasImageDietApplied = false;
            entityLayerDietApplied = false;
            searchTreeDietApplied = false;
            // Diagnostic builds capture both the premature and the final
            // post-reload live sets; the histogram command also performs a
            // full live-object collection before the second diet.
            if (Boolean.parseBoolean(System.getProperty(
                    "newhorizon.classHistogram", "false"))) {
                classHistogramLogged = false;
            }
        }

        private static void applyDisconnectedScreenTextureDiet(Minecraft minecraft) {
            try {
                // Gameplay resources are deliberately immutable here. Baked-model,
                // atlas-image, font-provider and entity-layer trimming all remove
                // data that resource packs, WebDisplays or late entity creation may
                // still request. Those cuts reduced PSS, but produced missing
                // textures and "No model for layer" failures, so they are not valid
                // low-memory optimisations.
                // Keep every mod filesystem alive. Closing UnionFS instances made
                // lazy client initialization fail in earlier builds. Once the Gecko
                // MCEF warmup is complete, however, Forge's annotation scan output
                // and ModLauncher's audit history have already done their job. They
                // are not class bytes, runtime registries or transformer state.
                boolean changed = false;
                logInfo("[NHNativeRoot] "
                        + NhGameMemoryProfiler.FullWeightStats.readJavaPools());
                boolean browserStackReady = logRequiredBrowserStackStatus();
                ZipInflaterDiet zipDiet = new ZipInflaterDiet();
                BootstrapMetadataDiet metadataDiet = new BootstrapMetadataDiet();
                if (browserStackReady) {
                    zipDiet = purgeIdleZipInflaters();
                    metadataDiet = clearBootstrapMetadata();
                    changed = zipDiet.total() > 0 || metadataDiet.removedTotal() > 0;
                } else {
                    logInfo("[NHMemoryGovernor] bootstrap metadata cleanup skipped"
                            + " reason=browser-stack-not-ready");
                }
                logHotspotClassHistogramOnce();
                writeHotspotCompilerCodeListOnce();
                writeHotspotCompilerCodeHeapAnalyticsOnce();
                writeHotspotHeapDumpOnce(minecraft);
                logInfo("[NHMemoryGovernor] post-bootstrap compatibility cleanup"
                        + " changed=" + changed
                        + " gameplayAssetsPreserved=true"
                        + " webDisplaysCompatible=true"
                        + " modFileSystemsPreserved=true"
                        + " forgeRuntimeRegistriesPreserved=true"
                        + " scanClassesReleased=" + metadataDiet.scanClasses
                        + " scanAnnotationsReleased=" + metadataDiet.scanAnnotations
                        + " auditEntriesReleased=" + metadataDiet.auditEntries
                        + " idleZipObjectsReleased=" + zipDiet.total()
                        + " rendererReload=false");
                NhGameMemoryProfiler.NhAnonTimeline.mark(
                        "post-bootstrap-compatible-cleanup-changed-" + changed);
                scheduleDynamicCdsDump();
                requestHeapReturn("ultralight-post-bootstrap-once");
            } catch (Throwable throwable) {
                if (!textureDietFailureLogged) {
                    textureDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] disconnected-screen texture diet failed", throwable);
                }
            }
        }

        private static boolean logRequiredBrowserStackStatus() {
            try {
                Class<?> modListClass = Class.forName("net.minecraftforge.fml.ModList");
                Object modList = modListClass.getMethod("get").invoke(null);
                Method isLoaded = modListClass.getMethod("isLoaded", String.class);
                boolean webDisplaysLoaded = Boolean.TRUE.equals(
                        isLoaded.invoke(modList, "webdisplays"));
                boolean mcefLoaded = Boolean.TRUE.equals(
                        isLoaded.invoke(modList, "mcef"));

                boolean mcefInitialized = false;
                if (mcefLoaded) {
                    Class<?> mcefClass = Class.forName("com.cinemamod.mcef.MCEF");
                    mcefInitialized = Boolean.TRUE.equals(
                            mcefClass.getMethod("isInitialized").invoke(null));
                }
                logInfo("[NHBrowserStack] webDisplaysLoaded=" + webDisplaysLoaded
                        + " mcefLoaded=" + mcefLoaded
                        + " mcefInitialized=" + mcefInitialized
                        + " lateClassLoadingPreserved=true");
                return webDisplaysLoaded && mcefLoaded && mcefInitialized;
            } catch (Throwable throwable) {
                logWarn("[NHBrowserStack] status probe failed", throwable);
                return false;
            }
        }

        /**
         * EntityRenderDispatcher and BlockEntityRenderDispatcher bake every
         * LayerDefinition while the resource reload is running. Afterwards
         * their renderers own the live ModelPart trees; EntityModelSet keeps
         * the 204 source LayerDefinitions only to support another reload. The
         * reload listener recreates the map before rebuilding the renderers,
         * so dropping this consumed source graph cannot remove a live model.
         */
        private static int releaseBakedEntityLayerDefinitions(Minecraft minecraft) {
            if (entityLayerDietApplied) {
                return 0;
            }
            entityLayerDietApplied = true;
            try {
                Object entityModelSet = readField(minecraft, "f_167844_");
                Object layerDefinitions = readField(entityModelSet, "f_171099_");
                int before = countContainer(layerDefinitions);
                if (before <= 0) {
                    return 0;
                }
                if (!writeField(entityModelSet, "f_171099_", new HashMap<>())) {
                    clearContainer(layerDefinitions);
                }
                int after = countContainer(readField(entityModelSet, "f_171099_"));
                logInfo("[NHMemoryGovernor] baked entity layer definitions released"
                        + " layers=" + before + "->" + after
                        + " liveRendererModelsKept=true");
                return Math.max(0, before - after);
            } catch (Throwable throwable) {
                if (!entityLayerDietFailureLogged) {
                    entityLayerDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] entity layer definition release failed", throwable);
                }
                return 0;
            }
        }

        /**
         * SecureJarHandler creates a second, unfiltered UnionFileSystem while it
         * discovers almost every library.  Once Forge has built its real Jar
         * objects those discovery file systems are no longer used, but the
         * provider registry keeps them (and every ZipFS central directory) alive.
         *
         * <p>Do not close the four unfiltered file systems which are themselves
         * the live Forge language-provider Jars.  Filtered file systems are also
         * left untouched.  The remaining entries have no live Jar owner after
         * bootstrap and closing them removes their provider entry and closes the
         * embedded ZipFS/file channel.</p>
         */
        private static int closeUnusedBootstrapUnionFileSystems() {
            if (bootstrapUnionFileSystemDietApplied) {
                return 0;
            }
            bootstrapUnionFileSystemDietApplied = true;

            try {
                int providersSeen = 0;
                int candidates = 0;
                int protectedLanguageJars = 0;
                int closed = 0;

                for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
                    if (!"cpw.mods.niofs.union.UnionFileSystemProvider"
                            .equals(provider.getClass().getName())) {
                        continue;
                    }
                    providersSeen++;

                    Object registryObject = readPrivateFieldWithUnsafe(provider, "fileSystems");
                    if (!(registryObject instanceof Map<?, ?> registry)) {
                        continue;
                    }

                    Object[] fileSystems;
                    synchronized (registry) {
                        fileSystems = registry.values().toArray();
                    }

                    for (Object fileSystem : fileSystems) {
                        if (fileSystem == null
                                || !"cpw.mods.niofs.union.UnionFileSystem"
                                .equals(fileSystem.getClass().getName())) {
                            continue;
                        }

                        // A non-null filter identifies the UnionFS backing a
                        // real SecureJar (or another still-active filtered view).
                        if (invokeNoArg(fileSystem, "getFilesystemFilter") != null) {
                            continue;
                        }

                        Object primaryPathObject = invokeNoArg(
                                fileSystem, "getPrimaryPath");
                        String primaryPath = primaryPathObject == null
                                ? "null"
                                : primaryPathObject.toString();
                        if (isForgeLanguageProviderJar(primaryPath)) {
                            protectedLanguageJars++;
                            continue;
                        }

                        candidates++;
                        try {
                            Method close = fileSystem.getClass().getMethod("close");
                            close.invoke(fileSystem);
                            closed++;
                        } catch (Throwable throwable) {
                            logWarn("[NHMemoryGovernor] bootstrap UnionFS close failed path="
                                    + primaryPath, throwable);
                        }
                    }
                }

                logInfo("[NHMemoryGovernor] bootstrap UnionFS diet"
                        + " providers=" + providersSeen
                        + " candidates=" + candidates
                        + " protectedLanguageJars=" + protectedLanguageJars
                        + " closed=" + closed);
                return closed;
            } catch (Throwable throwable) {
                if (!bootstrapUnionFileSystemDietFailureLogged) {
                    bootstrapUnionFileSystemDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] bootstrap UnionFS diet failed", throwable);
                }
                return 0;
            }
        }

        private static boolean isForgeLanguageProviderJar(String path) {
            if (path == null) {
                return false;
            }
            String normalized = path.toLowerCase(Locale.ROOT);
            return normalized.contains("/net/minecraftforge/fmlcore/")
                    || normalized.contains("/net/minecraftforge/lowcodelanguage/")
                    || normalized.contains("/net/minecraftforge/mclanguage/")
                    || normalized.contains("/net/minecraftforge/javafmllanguage/");
        }

        /**
         * Ends only the idle compression objects cached by active ZipFS
         * instances. Inflaters currently owned by open streams are not present
         * in these lists and are therefore untouched. A later resource read can
         * lazily create a fresh inflater without reopening or closing the JAR.
         */
        private static ZipInflaterDiet purgeIdleZipInflaters() {
            ZipInflaterDiet result = new ZipInflaterDiet();
            if (idleZipInflaterDietApplied) {
                return result;
            }
            idleZipInflaterDietApplied = true;

            try {
                for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
                    if (!"jdk.nio.zipfs.ZipFileSystemProvider"
                            .equals(provider.getClass().getName())) {
                        continue;
                    }
                    result.providers++;
                    Object registryObject = readPrivateFieldWithUnsafe(provider, "filesystems");
                    if (!(registryObject instanceof Map<?, ?> registry)) {
                        continue;
                    }
                    Object[] fileSystems;
                    synchronized (registry) {
                        fileSystems = registry.values().toArray();
                    }
                    result.fileSystems += fileSystems.length;
                    for (Object fileSystem : fileSystems) {
                        result.inflaters += endIdleCompressionObjects(
                                readPrivateFieldWithUnsafe(fileSystem, "inflaters"), true);
                        result.deflaters += endIdleCompressionObjects(
                                readPrivateFieldWithUnsafe(fileSystem, "deflaters"), false);
                    }
                }
                logInfo("[NHMemoryGovernor] idle ZipFS compression pool diet"
                        + " providers=" + result.providers
                        + " fileSystems=" + result.fileSystems
                        + " inflatersEnded=" + result.inflaters
                        + " deflatersEnded=" + result.deflaters
                        + " activeStreamsUntouched=true");
            } catch (Throwable throwable) {
                if (!idleZipInflaterDietFailureLogged) {
                    idleZipInflaterDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] idle ZipFS compression pool diet failed", throwable);
                }
            }
            return result;
        }

        private static int endIdleCompressionObjects(Object poolObject, boolean inflaterPool) {
            if (!(poolObject instanceof List<?> pool)) {
                return 0;
            }
            Object[] idle;
            synchronized (pool) {
                idle = pool.toArray();
                pool.clear();
            }
            int ended = 0;
            for (Object compressionObject : idle) {
                try {
                    if (inflaterPool && compressionObject instanceof Inflater inflater) {
                        inflater.end();
                        ended++;
                    } else if (!inflaterPool && compressionObject instanceof Deflater deflater) {
                        deflater.end();
                        ended++;
                    }
                } catch (Throwable ignored) {
                }
            }
            return ended;
        }

        private static final class ZipInflaterDiet {
            int providers;
            int fileSystems;
            int inflaters;
            int deflaters;

            int total() {
                return inflaters + deflaters;
            }
        }

        /**
         * Forge's annotation/class scan and ModLauncher audit trail are consumed
         * before the first Minecraft screen is shown. They are diagnostic and
         * discovery products, not runtime registries. Keeping them retains the
         * names and ASM Types for every class in every mod.
         */
        private static BootstrapMetadataDiet clearBootstrapMetadata() {
            BootstrapMetadataDiet result = new BootstrapMetadataDiet();
            if (bootstrapMetadataDietApplied) {
                return result;
            }
            bootstrapMetadataDietApplied = true;

            try {
                Class<?> modListClass = Class.forName("net.minecraftforge.fml.ModList");
                Object modList = modListClass.getMethod("get").invoke(null);
                Object scanDataObject = modListClass.getMethod("getAllScanData")
                        .invoke(modList);
                if (scanDataObject instanceof Iterable<?> scanDataList) {
                    for (Object scanData : scanDataList) {
                        if (scanData == null) {
                            continue;
                        }
                        Object classes = invokeNoArg(scanData, "getClasses");
                        Object annotations = invokeNoArg(scanData, "getAnnotations");
                        result.scanClasses += Math.max(0, countContainer(classes));
                        result.scanAnnotations += Math.max(0, countContainer(annotations));
                        clearContainer(classes);
                        clearContainer(annotations);

                        Object targets = readPrivateFieldWithUnsafe(scanData, "modTargets");
                        result.languageTargets += Math.max(0, countContainer(targets));
                        clearContainer(targets);
                        Object scanners = readPrivateFieldWithUnsafe(
                                scanData, "functionalScanners");
                        result.functionalScanners += Math.max(0, countContainer(scanners));
                        clearContainer(scanners);
                    }
                }

                // The audit trail is queried only for transformation diagnostics.
                // The transformer itself remains fully installed for any class
                // that is loaded later during gameplay.
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Object classTransformer = readPrivateFieldWithUnsafe(
                        loader, "classTransformer");
                Object auditTrail = readPrivateFieldWithUnsafe(
                        classTransformer, "auditTrail");
                Object audit = readPrivateFieldWithUnsafe(auditTrail, "audit");
                result.auditEntries = Math.max(0, countContainer(audit));
                clearContainer(audit);

                logInfo("[NHMemoryGovernor] bootstrap metadata diet"
                        + " scanClasses=" + result.scanClasses
                        + " scanAnnotations=" + result.scanAnnotations
                        + " languageTargets=" + result.languageTargets
                        + " functionalScanners=" + result.functionalScanners
                        + " auditEntries=" + result.auditEntries
                        + " runtimeRegistriesKept=true"
                        + " transformerKept=true");
            } catch (Throwable throwable) {
                if (!bootstrapMetadataDietFailureLogged) {
                    bootstrapMetadataDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] bootstrap metadata diet failed", throwable);
                }
            }
            return result;
        }

        private static final class BootstrapMetadataDiet {
            int scanClasses;
            int scanAnnotations;
            int languageTargets;
            int functionalScanners;
            int auditEntries;

            int removedTotal() {
                return scanClasses + scanAnnotations + languageTargets
                        + functionalScanners + auditEntries;
            }
        }

        /**
         * Reads a private field without opening SecureJarHandler's module.  The
         * field is only used to snapshot the provider's own registry; mutations
         * still go through UnionFileSystem.close().
         */
        private static Object readPrivateFieldWithUnsafe(Object owner, String fieldName)
                throws ReflectiveOperationException {
            if (owner == null) {
                return null;
            }
            Field targetField = null;
            Class<?> ownerType = owner.getClass();
            while (ownerType != null && targetField == null) {
                try {
                    targetField = ownerType.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                    ownerType = ownerType.getSuperclass();
                }
            }
            if (targetField == null) {
                throw new NoSuchFieldException(fieldName);
            }
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method objectFieldOffset = unsafeClass.getMethod(
                    "objectFieldOffset", Field.class);
            long offset = ((Number) objectFieldOffset.invoke(unsafe, targetField)).longValue();
            Method getObject = unsafeClass.getMethod(
                    "getObject", Object.class, long.class);
            return getObject.invoke(unsafe, owner, offset);
        }

        private static void scheduleDynamicCdsDump() {
            if (dynamicCdsDumpScheduled) return;

            String archivePath = System.getProperty("newhorizon.cdsArchivePath", "");
            if (archivePath.isEmpty()) return;
            dynamicCdsDumpScheduled = true;
            try {
                File marker = new File(archivePath + ".ready");
                Files.write(marker.toPath(), new byte[] {1});
                logInfo("[NHCDS] dynamic archive readiness signaled path="
                        + marker.getAbsolutePath());
            } catch (Throwable throwable) {
                logWarn("[NHCDS] failed to signal dynamic archive readiness", throwable);
            }
        }

        private static boolean disableDedicatedClientSound(Minecraft minecraft) {
            if (soundDietApplied) {
                return false;
            }

            try {
                Object soundManager = invokeNoArg(minecraft, "m_91106_", "getSoundManager");
                if (soundManager == null) {
                    return false;
                }

                int registryBefore = countContainer(readField(soundManager, "f_120348_"));
                int resourcesBefore = countContainer(readField(soundManager, "f_244170_"));
                invokeNoArg(soundManager, "m_120405_", "stop");
                invokeNoArg(soundManager, "m_120406_", "destroy");
                clearContainer(readField(soundManager, "f_120348_"));
                clearContainer(readField(soundManager, "f_244170_"));
                soundDietApplied = true;
                logInfo("[NHMemoryGovernor] dedicated audio engine released"
                        + " soundEvents=" + registryBefore + "->0"
                        + " soundResources=" + resourcesBefore + "->0"
                        + " policy=silent-client");
                return true;
            } catch (Throwable throwable) {
                logWarn("[NHMemoryGovernor] dedicated audio release failed; leaving audio active", throwable);
                return false;
            }
        }

        private static void clearContainer(Object value) {
            if (value instanceof Map<?, ?>) {
                ((Map<?, ?>) value).clear();
            } else if (value instanceof Collection<?>) {
                ((Collection<?>) value).clear();
            } else if (value != null) {
                invokeNoArg(value, "clear");
            }
        }

        private static void writeHotspotHeapDumpOnce(Minecraft minecraft) {
            if (heapDumpWritten
                    || !Boolean.parseBoolean(System.getProperty(
                    "newhorizon.heapDump", "false"))) {
                return;
            }
            heapDumpWritten = true;
            try {
                Object directory = readField(minecraft, "f_91069_");
                File gameDirectory = directory instanceof File
                        ? (File) directory
                        : new File(".");
                File logs = new File(gameDirectory, "logs");
                //noinspection ResultOfMethodCallIgnored
                logs.mkdirs();
                File dump = new File(logs, "nh-heap.hprof");
                if (dump.exists() && dump.length() > 0L) {
                    boolean readable = dump.setReadable(true, false);
                    logInfo("[NHHeapDump] existing dump exported path="
                            + dump.getAbsolutePath()
                            + " bytes=" + dump.length()
                            + " readable=" + readable);
                    return;
                }
                Class<?> diagnosticType = Class.forName(
                        "com.sun.management.HotSpotDiagnosticMXBean");
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object diagnostic = ManagementFactory.getPlatformMXBean(
                        (Class) diagnosticType);
                Method dumpHeap = diagnosticType.getMethod(
                        "dumpHeap", String.class, boolean.class);
                dumpHeap.invoke(diagnostic, dump.getAbsolutePath(), Boolean.TRUE);
                boolean readable = dump.setReadable(true, false);
                logInfo("[NHHeapDump] written path=" + dump.getAbsolutePath()
                        + " bytes=" + dump.length()
                        + " liveOnly=true"
                        + " readable=" + readable);
            } catch (Throwable throwable) {
                logWarn("[NHHeapDump] failed", throwable);
            }
        }

        /**
         * Releases CPU-side pixels for static atlas sprites after their initial
         * upload. TextureAtlasSprite keeps its UV/size metadata and the GPU atlas
         * remains owned by LTW. Animated sprites retain their NativeImages because
         * their tickers upload new frames later.
         */
        private static AtlasImageDiet releaseStaticAtlasImages(Minecraft minecraft) {
            AtlasImageDiet result = new AtlasImageDiet();
            if (atlasImageDietApplied) {
                return result;
            }
            try {
                Object modelManager = readField(minecraft, "f_91051_");
                Object atlasSet = readField(modelManager, "f_119398_");
                Object atlasEntries = readField(atlasSet, "f_244518_");
                if (!(atlasEntries instanceof Map<?, ?>)) {
                    return result;
                }

                IdentityHashMap<Object, Boolean> countedImages = new IdentityHashMap<>();
                for (Object atlasEntry : ((Map<?, ?>) atlasEntries).values()) {
                    Object atlas = invokeNoArg(atlasEntry, "f_244361_");
                    Object contentsObject = readField(atlas, "f_118263_");
                    if (!(contentsObject instanceof List<?>)) {
                        continue;
                    }
                    result.atlases++;
                    List<Object> animatedContents = new ArrayList<>();
                    for (Object contents : (List<?>) contentsObject) {
                        if (contents == null) {
                            continue;
                        }
                        boolean animated = readField(contents, "f_244575_") != null;
                        Object images = readField(contents, "f_243731_");
                        if (animated) {
                            animatedContents.add(contents);
                            result.animatedSprites++;
                            if (images != null && images.getClass().isArray()) {
                                int length = Array.getLength(images);
                                for (int index = 0; index < length; index++) {
                                    Object image = Array.get(images, index);
                                    if (image == null
                                            || countedImages.put(image, Boolean.TRUE) != null) {
                                        continue;
                                    }
                                    int width = numberValue(invokeNoArg(
                                            image, "m_84982_", "getWidth"));
                                    int height = numberValue(invokeNoArg(
                                            image, "m_85084_", "getHeight"));
                                    if (width > 0 && height > 0) {
                                        result.animatedEstimatedBytes +=
                                                (long) width * (long) height * 4L;
                                    }
                                    result.animatedImages++;
                                }
                            }
                            continue;
                        }

                        if (images != null && images.getClass().isArray()) {
                            int length = Array.getLength(images);
                            for (int index = 0; index < length; index++) {
                                Object image = Array.get(images, index);
                                if (image == null || countedImages.put(image, Boolean.TRUE) != null) {
                                    continue;
                                }
                                int width = numberValue(invokeNoArg(image, "m_84982_", "getWidth"));
                                int height = numberValue(invokeNoArg(image, "m_85084_", "getHeight"));
                                if (width > 0 && height > 0) {
                                    result.estimatedBytes += (long) width * (long) height * 4L;
                                }
                                result.releasedImages++;
                            }
                        }
                        closeQuietly(contents);
                        result.staticSprites++;
                    }
                    if (!writeField(atlas, "f_118263_", animatedContents)) {
                        throw new IllegalStateException("TextureAtlas.f_118263_ is not writable");
                    }
                }
                atlasImageDietApplied = true;
                logInfo("[NHMemoryGovernor] static atlas CPU images released"
                        + " atlases=" + result.atlases
                        + " staticSprites=" + result.staticSprites
                        + " images=" + result.releasedImages
                        + " estimatedMb=" + result.estimatedMb()
                        + " animatedSpritesKept=" + result.animatedSprites
                        + " animatedImagesKept=" + result.animatedImages
                        + " animatedEstimatedMb=" + result.animatedEstimatedMb()
                        + " gpuAtlasesKept=true");
            } catch (Throwable throwable) {
                if (!atlasImageDietFailureLogged) {
                    atlasImageDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] static atlas CPU image release failed", throwable);
                }
            }
            return result;
        }

        private static int numberValue(Object value) {
            return value instanceof Number ? ((Number) value).intValue() : -1;
        }

        private static final class AtlasImageDiet {
            int atlases;
            int staticSprites;
            int animatedSprites;
            int animatedImages;
            int releasedImages;
            long estimatedBytes;
            long animatedEstimatedBytes;

            long estimatedMb() {
                return estimatedBytes / (1024L * 1024L);
            }

            long animatedEstimatedMb() {
                return animatedEstimatedBytes / (1024L * 1024L);
            }
        }

        private static void logHotspotClassHistogramOnce() {
            if (classHistogramLogged
                    || !Boolean.parseBoolean(System.getProperty(
                    "newhorizon.classHistogram", "false"))) {
                return;
            }
            classHistogramLogged = true;
            try {
                MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                ObjectName diagnostic = new ObjectName(
                        "com.sun.management:type=DiagnosticCommand");
                Object value = server.invoke(
                        diagnostic,
                        "gcClassHistogram",
                        new Object[]{new String[]{"-all=false"}},
                        new String[]{"[Ljava.lang.String;"});
                String[] lines = String.valueOf(value).split("\\R");
                StringBuilder top = new StringBuilder(8192);
                int limit = Math.min(lines.length, 90);
                for (int i = 0; i < limit; i++) {
                    top.append(lines[i]).append('\n');
                }
                logInfo("[NHClassHistogram] topLines=" + limit + "\n" + top);
            } catch (Throwable throwable) {
                logWarn("[NHClassHistogram] unavailable", throwable);
            }
        }

        /** Writes HotSpot's JIT address ranges for same-process native profiling. */
        private static void writeHotspotCompilerCodeListOnce() {
            if (compilerCodeListWritten) {
                return;
            }
            String outputPath = System.getProperty(
                    "newhorizon.compilerCodeListPath", "");
            if (outputPath.isEmpty()) {
                return;
            }
            compilerCodeListWritten = true;
            try {
                MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                ObjectName diagnostic = new ObjectName(
                        "com.sun.management:type=DiagnosticCommand");
                Object value = server.invoke(
                        diagnostic,
                        "compilerCodelist",
                        new Object[]{new String[0]},
                        new String[]{"[Ljava.lang.String;"});
                File output = new File(outputPath);
                Files.writeString(output.toPath(), String.valueOf(value),
                        StandardCharsets.UTF_8);
                logInfo("[NHCompilerCodeList] written path="
                        + output.getAbsolutePath()
                        + " bytes=" + output.length());
            } catch (Throwable throwable) {
                logWarn("[NHCompilerCodeList] unavailable", throwable);
            }
        }

        /**
         * Writes native-wrapper and CodeHeap details that compilerCodelist omits.
         * This lets a same-boot heapprofd trace resolve allocations whose caller
         * is an anonymous HotSpot native wrapper rather than compiled Java code.
         */
        private static void writeHotspotCompilerCodeHeapAnalyticsOnce() {
            if (compilerCodeHeapAnalyticsWritten) {
                return;
            }
            String outputPath = System.getProperty(
                    "newhorizon.compilerCodeHeapAnalyticsPath", "");
            if (outputPath.isEmpty()) {
                return;
            }
            compilerCodeHeapAnalyticsWritten = true;
            try {
                MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                ObjectName diagnostic = new ObjectName(
                        "com.sun.management:type=DiagnosticCommand");
                Object value = server.invoke(
                        diagnostic,
                        "compilerCodeHeapAnalytics",
                        new Object[]{new String[]{"all", "4096"}},
                        new String[]{"[Ljava.lang.String;"});
                File output = new File(outputPath);
                Files.writeString(output.toPath(), String.valueOf(value),
                        StandardCharsets.UTF_8);
                logInfo("[NHCompilerCodeHeapAnalytics] written path="
                        + output.getAbsolutePath()
                        + " bytes=" + output.length());
            } catch (Throwable throwable) {
                logWarn("[NHCompilerCodeHeapAnalytics] unavailable", throwable);
            }
        }

        /**
         * Keeps only the baked models useful to the dedicated New Horizon flow.
         *
         * <p>All lookups involved already have Minecraft's valid missing-model
         * fallback. Non-vanilla namespaces are retained wholesale so Forge,
         * WebDisplays and MCEF models remain available. For vanilla we keep the
         * compact set used by the SkyWars map, combat inventory and HUD. This
         * releases the thousands of baked variants for blocks and items that the
         * dedicated server never sends.</p>
         */
        private static ModelRegistryDiet trimDedicatedModelRegistries(Minecraft minecraft) {
            ModelRegistryDiet result = new ModelRegistryDiet();
            if (modelRegistryDietApplied) {
                return result;
            }

            try {
                Object modelManager = readField(minecraft, "f_91051_");
                Object bakedObject = readField(modelManager, "f_119397_");
                result.bakedBefore = countContainer(bakedObject);
                if (bakedObject instanceof Map<?, ?>) {
                    Map<Object, Object> kept = new HashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) bakedObject).entrySet()) {
                        if (keepDedicatedModelName(String.valueOf(entry.getKey()))) {
                            kept.put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (!writeField(modelManager, "f_119397_", kept)) {
                        throw new IllegalStateException("ModelManager.f_119397_ is not writable");
                    }
                    result.bakedAfter = kept.size();
                }

                Object blockShaper = readField(modelManager, "f_119399_");
                Object blockMapObject = readField(blockShaper, "f_110877_");
                result.blockBefore = countContainer(blockMapObject);
                if (blockMapObject instanceof Map<?, ?>) {
                    Map<Object, Object> kept = new IdentityHashMap<>();
                    Field blockStateCacheField = null;
                    boolean blockStateCacheFieldResolved = false;
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) blockMapObject).entrySet()) {
                        if (keepDedicatedModelName(String.valueOf(entry.getKey()))) {
                            kept.put(entry.getKey(), entry.getValue());
                        } else {
                            if (!blockStateCacheFieldResolved) {
                                blockStateCacheFieldResolved = true;
                                blockStateCacheField = resolveField(
                                        entry.getKey(), "f_60593_");
                            }
                            try {
                                if (blockStateCacheField == null) {
                                    result.blockStateCacheClearFailures++;
                                } else {
                                    // BlockStateBase methods deliberately fall back to
                                    // the owning Block when this optional cache is absent.
                                    blockStateCacheField.set(entry.getKey(), null);
                                    result.blockStateCachesCleared++;
                                }
                            } catch (Throwable throwable) {
                                result.blockStateCacheClearFailures++;
                            }
                        }
                    }
                    if (!writeField(blockShaper, "f_110877_", kept)) {
                        throw new IllegalStateException("BlockModelShaper.f_110877_ is not writable");
                    }
                    result.blockAfter = kept.size();
                }

                Object itemRenderer = readField(minecraft, "f_90995_");
                Object itemShaper = invokeNoArg(itemRenderer, "m_115103_");
                Object locationsObject = readField(itemShaper, "f_109388_");
                Object modelsObject = readField(itemShaper, "f_109389_");
                result.itemLocationsBefore = countContainer(locationsObject);
                result.itemModelsBefore = countContainer(modelsObject);
                java.util.HashSet<Object> keptItemIds = new java.util.HashSet<>();
                if (locationsObject instanceof Map<?, ?>) {
                    java.util.Iterator<? extends Map.Entry<?, ?>> iterator =
                            ((Map<?, ?>) locationsObject).entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<?, ?> entry = iterator.next();
                        if (keepDedicatedModelName(String.valueOf(entry.getValue()))) {
                            keptItemIds.add(entry.getKey());
                        } else {
                            iterator.remove();
                        }
                    }
                }
                if (modelsObject instanceof Map<?, ?>) {
                    java.util.Iterator<? extends Map.Entry<?, ?>> iterator =
                            ((Map<?, ?>) modelsObject).entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<?, ?> entry = iterator.next();
                        if (!keptItemIds.contains(entry.getKey())) {
                            iterator.remove();
                        }
                    }
                }
                result.itemLocationsAfter = countContainer(locationsObject);
                result.itemModelsAfter = countContainer(modelsObject);
                modelRegistryDietApplied = true;
                logInfo("[NHMemoryGovernor] dedicated baked-model diet applied"
                        + " baked=" + result.bakedBefore + "->" + result.bakedAfter
                        + " blockStates=" + result.blockBefore + "->" + result.blockAfter
                        + " blockStateCachesCleared=" + result.blockStateCachesCleared
                        + " blockStateCacheClearFailures=" + result.blockStateCacheClearFailures
                        + " itemLocations=" + result.itemLocationsBefore + "->" + result.itemLocationsAfter
                        + " itemModels=" + result.itemModelsBefore + "->" + result.itemModelsAfter
                        + " policy=non-vanilla-plus-skywars-core");
            } catch (Throwable throwable) {
                if (!modelRegistryDietFailureLogged) {
                    modelRegistryDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] dedicated baked-model diet failed; "
                            + "leaving remaining registries intact", throwable);
                }
            }
            return result;
        }

        private static boolean keepDedicatedModelName(String value) {
            if (value == null) {
                return false;
            }
            // Resource locations and block-state strings are already normalized
            // to lowercase by Minecraft. Avoid allocating a lowercase copy and
            // dozens of token Strings for every one of the ~50k registry entries.
            if (!value.contains("minecraft:")) {
                return true;
            }
            if (value.contains("missing")) {
                return true;
            }
            for (String needle : REQUIRED_MODEL_NEEDLES) {
                if (value.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        private static String[] buildRequiredModelNeedles() {
            String[] needles = new String[REQUIRED_MODEL_IDS.length * 4];
            int index = 0;
            for (String id : REQUIRED_MODEL_IDS) {
                needles[index++] = "minecraft:" + id + "#";
                needles[index++] = "minecraft:" + id + "}";
                needles[index++] = "minecraft:block/" + id;
                needles[index++] = "minecraft:item/" + id;
            }
            return needles;
        }

        private static final class ModelRegistryDiet {
            int bakedBefore;
            int bakedAfter;
            int blockBefore;
            int blockAfter;
            int blockStateCachesCleared;
            int blockStateCacheClearFailures;
            int itemLocationsBefore;
            int itemLocationsAfter;
            int itemModelsBefore;
            int itemModelsAfter;

            int removedTotal() {
                return Math.max(0, bakedBefore - bakedAfter)
                        + Math.max(0, blockBefore - blockAfter)
                        + Math.max(0, itemLocationsBefore - itemLocationsAfter)
                        + Math.max(0, itemModelsBefore - itemModelsAfter);
            }
        }

        /**
         * Releases the populated creative-item, tag and recipe search indexes.
         *
         * The dedicated server flow never opens the creative inventory or the
         * vanilla recipe search UI. We keep each TreeEntry registered and ask
         * it to rebuild from an empty list, so callers still receive a valid
         * SearchTree instead of failing on a missing registry key.
         */
        private static int emptyDedicatedClientSearchTrees(Minecraft minecraft) {
            if (searchTreeDietApplied) {
                return 0;
            }
            try {
                Object searchRegistry = readField(minecraft, "f_90997_");
                Object entryMap = readField(searchRegistry, "f_119944_");
                if (!(entryMap instanceof Map<?, ?>)) {
                    return 0;
                }

                int emptied = 0;
                for (Object treeEntry : ((Map<?, ?>) entryMap).values()) {
                    if (treeEntry == null) {
                        continue;
                    }
                    ArrayList<Object> emptyValues = new ArrayList<>();
                    Method rebuild = findOneArgMethod(
                            treeEntry.getClass(), emptyValues.getClass(), "m_235245_");
                    if (rebuild == null) {
                        throw new NoSuchMethodException(
                                "SearchRegistry.TreeEntry.m_235245_(List)");
                    }
                    rebuild.setAccessible(true);
                    rebuild.invoke(treeEntry, emptyValues);
                    emptied++;
                }
                searchTreeDietApplied = true;
                logInfo("[NHMemoryGovernor] dedicated search trees emptied"
                        + " entries=" + emptied
                        + " targets=creative-names/tags/recipes");
                return emptied;
            } catch (Throwable throwable) {
                if (!searchTreeDietFailureLogged) {
                    searchTreeDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] dedicated search tree diet failed; "
                            + "leaving vanilla indexes intact", throwable);
                }
                return 0;
            }
        }

        private static int removeNamedTextures(Object textureManager, String fieldName) throws Exception {
            Object mapObject = readField(textureManager, fieldName);
            if (!(mapObject instanceof Map<?, ?>)) {
                return 0;
            }
            int removed = 0;
            java.util.Iterator<? extends Map.Entry<?, ?>> iterator =
                    ((Map<?, ?>) mapObject).entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                String name = String.valueOf(entry.getKey());
                if (!isInvisibleMenuTexture(name)) {
                    continue;
                }
                Object texture = entry.getValue();
                iterator.remove();
                closeQuietly(texture);
                removed++;
            }
            return removed;
        }

        private static boolean isInvisibleMenuTexture(String name) {
            if (name == null) {
                return false;
            }
            return name.contains("textures/gui/title/background/panorama_")
                    || name.contains("textures/gui/title/background/panorama")
                    || name.contains("textures/gui/title/minecraft")
                    || name.contains("textures/gui/title/edition")
                    || name.contains("textures/gui/title/mojangstudios");
        }

        private static boolean trimModelGroupCache(Minecraft minecraft, int level) {
            if (modelGroupDietApplied || level < 2) {
                return false;
            }
            modelGroupDietApplied = true;

            try {
                Object modelManager = readField(minecraft, "f_91051_");
                Object groupMap = readField(modelManager, "f_119404_");
                int before = countContainer(groupMap);
                if (before <= 0) {
                    logInfo("[NHMemoryGovernor] model group cache scan"
                            + " level=" + level
                            + " entries=" + before
                            + " cleared=0");
                    return false;
                }

                trySetDefaultReturnValue(groupMap, -1);
                invokeNoArg(groupMap, "clear");
                int after = countContainer(groupMap);
                logInfo("[NHMemoryGovernor] model group cache cleared"
                        + " level=" + level
                        + " before=" + before
                        + " after=" + after
                        + " target=ModelManager.f_119404_");
                return true;
            } catch (Throwable throwable) {
                if (!modelDietFailureLogged) {
                    modelDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] model group cache clear failed", throwable);
                }
            }
            return false;
        }

        private static void trySetDefaultReturnValue(Object map, int value) {
            if (map == null) {
                return;
            }
            try {
                Method method = map.getClass().getMethod("defaultReturnValue", int.class);
                method.setAccessible(true);
                method.invoke(map, Integer.valueOf(value));
            } catch (Throwable ignored) {
            }
        }

        private static int countContainer(Object value) {
            if (value == null) {
                return -1;
            }
            if (value instanceof Map<?, ?>) {
                return ((Map<?, ?>) value).size();
            }
            if (value instanceof Collection<?>) {
                return ((Collection<?>) value).size();
            }
            Class<?> type = value.getClass();
            if (type.isArray()) {
                return Array.getLength(value);
            }
            try {
                Method size = type.getMethod("size");
                if (size.getParameterTypes().length == 0) {
                    Object result = size.invoke(value);
                    if (result instanceof Number) {
                        return ((Number) result).intValue();
                    }
                }
            } catch (Throwable ignored) {
            }
            return -1;
        }

        private static void closeQuietly(Object value) {
            if (value == null) {
                return;
            }
            try {
                if (value instanceof AutoCloseable) {
                    ((AutoCloseable) value).close();
                } else {
                    invokeNoArg(value, "close");
                }
            } catch (Throwable ignored) {
            }
        }

        /**
         * Drops the vanilla Unihex provider after resource loading.
         *
         * <p>The provider eagerly retains a glyph object for essentially the
         * complete Unicode plane (more than one hundred thousand entries in
         * 1.20.1).  The dedicated low-memory client keeps the bitmap/ASCII
         * providers used by the HUD and menus; uncommon codepoints simply use
         * FontSet's normal missing-glyph fallback.</p>
         */
        private static UnicodeGlyphDiet trimDedicatedUnicodeGlyphProviders(
                Minecraft minecraft) {
            UnicodeGlyphDiet result = new UnicodeGlyphDiet();
            if (unicodeGlyphDietApplied) {
                return result;
            }

            try {
                Object fontManager = readField(minecraft, "f_91045_");
                if (fontManager == null) {
                    return result;
                }

                List<Object> fontSets = new ArrayList<>();
                Object missingFontSet = readField(fontManager, "f_94998_");
                if (missingFontSet != null) {
                    fontSets.add(missingFontSet);
                }
                Object sets = readField(fontManager, "f_94999_");
                if (sets instanceof Map<?, ?>) {
                    for (Object fontSet : ((Map<?, ?>) sets).values()) {
                        if (fontSet != null && !containsIdentity(fontSets, fontSet)) {
                            fontSets.add(fontSet);
                        }
                    }
                }

                IdentityHashMap<Object, Boolean> removed = new IdentityHashMap<>();
                for (Object fontSet : fontSets) {
                    result.removedReferences += replaceUnihexProviderList(
                            fontSet, "f_95055_", removed);
                }
                result.removedReferences += replaceUnihexProviderList(
                        fontManager, "f_283839_", removed);

                // Cached glyph results hold the Unihex Glyph/Contents graph even
                // after its provider list is replaced.
                for (Object fontSet : fontSets) {
                    clearFontSetCaches(fontSet);
                }
                for (Object provider : removed.keySet()) {
                    closeQuietly(provider);
                }

                result.removedProviders = removed.size();
                unicodeGlyphDietApplied = true;
                logInfo("[NHMemoryGovernor] Unicode glyph provider diet applied"
                        + " fontSets=" + fontSets.size()
                        + " providers=" + result.removedProviders
                        + " references=" + result.removedReferences
                        + " fallback=bitmap-ascii-plus-missing-glyph");
            } catch (Throwable throwable) {
                if (!unicodeGlyphDietFailureLogged) {
                    unicodeGlyphDietFailureLogged = true;
                    logWarn("[NHMemoryGovernor] Unicode glyph provider diet failed; "
                            + "leaving remaining font providers intact", throwable);
                }
            }
            return result;
        }

        private static int replaceUnihexProviderList(Object owner, String fieldName,
                IdentityHashMap<Object, Boolean> removed) {
            Object providersObject = readField(owner, fieldName);
            if (!(providersObject instanceof List<?>)) {
                return 0;
            }

            List<Object> kept = new ArrayList<>();
            int removedReferences = 0;
            for (Object provider : (List<?>) providersObject) {
                if (provider != null
                        && "net.minecraft.client.gui.font.providers.UnihexProvider"
                        .equals(provider.getClass().getName())) {
                    removed.put(provider, Boolean.TRUE);
                    removedReferences++;
                } else {
                    kept.add(provider);
                }
            }
            if (removedReferences > 0 && !writeField(owner, fieldName, kept)) {
                throw new IllegalStateException(owner.getClass().getName()
                        + "." + fieldName + " is not writable");
            }
            return removedReferences;
        }

        private static final class UnicodeGlyphDiet {
            int removedProviders;
            int removedReferences;
        }

        private static boolean trimFontTexturePages(Minecraft minecraft, int level) {
            if (level < 2) {
                return false;
            }
            if (fontTrimCooldownTicks > 0) {
                fontTrimCooldownTicks--;
                return false;
            }
            fontTrimCooldownTicks = level >= 3 ? 12 : 40;

            try {
                Object fontManager = readField(minecraft, "f_91045_");
                if (fontManager == null) {
                    return false;
                }

                int globalKeepPages = level >= 3 ? 4 : 6;
                List<Object> fontSets = new ArrayList<>();
                Object missingFontSet = readField(fontManager, "f_94998_");
                if (missingFontSet != null) {
                    fontSets.add(missingFontSet);
                }
                Object sets = readField(fontManager, "f_94999_");
                if (sets instanceof Map<?, ?>) {
                    for (Object fontSet : ((Map<?, ?>) sets).values()) {
                        if (fontSet != null && !containsIdentity(fontSets, fontSet)) {
                            fontSets.add(fontSet);
                        }
                    }
                }

                IdentityHashMap<Object, Object> pageOwners = new IdentityHashMap<>();
                List<Object> pages = new ArrayList<>();
                for (Object fontSet : fontSets) {
                    Object texturesObject = readField(fontSet, "f_95059_");
                    if (!(texturesObject instanceof List<?>)) {
                        continue;
                    }
                    for (Object texture : (List<?>) texturesObject) {
                        if (texture != null && !pageOwners.containsKey(texture)) {
                            pageOwners.put(texture, fontSet);
                            pages.add(texture);
                        }
                    }
                }

                if (pages.size() <= globalKeepPages) {
                    logInfo("[NHMemoryGovernor] font texture pages observed"
                            + " level=" + level
                            + " sets=" + fontSets.size()
                            + " pages=" + pages.size()
                            + " globalKeepPages=" + globalKeepPages
                            + " trimmed=0");
                    return false;
                }

                int trimmedPages = 0;
                for (int i = pages.size() - 1; i >= globalKeepPages; i--) {
                    Object texture = pages.get(i);
                    Object owner = pageOwners.get(texture);
                    if (removeTextureFromFontSet(owner, texture)) {
                        closeFontTexture(texture);
                        trimmedPages++;
                    }
                }

                if (trimmedPages > 0) {
                    for (Object fontSet : fontSets) {
                        clearFontSetCaches(fontSet);
                    }
                    logInfo("[NHMemoryGovernor] font texture pages trimmed"
                            + " level=" + level
                            + " sets=" + fontSets.size()
                            + " beforePages=" + pages.size()
                            + " globalKeepPages=" + globalKeepPages
                            + " pages=" + trimmedPages);
                    return true;
                }
            } catch (Throwable throwable) {
                if (!fontTrimFailureLogged) {
                    fontTrimFailureLogged = true;
                    logWarn("[NHMemoryGovernor] font texture trim failed; leaving vanilla fonts intact", throwable);
                }
            }
            return false;
        }

        private static boolean containsIdentity(List<Object> values, Object needle) {
            for (Object value : values) {
                if (value == needle) {
                    return true;
                }
            }
            return false;
        }

        private static boolean removeTextureFromFontSet(Object fontSet, Object texture) {
            if (fontSet == null) {
                return false;
            }
            Object texturesObject = readField(fontSet, "f_95059_");
            if (!(texturesObject instanceof List<?>)) {
                return false;
            }
            List<?> textures = (List<?>) texturesObject;
            for (int i = textures.size() - 1; i >= 0; i--) {
                if (textures.get(i) == texture) {
                    textures.remove(i);
                    return true;
                }
            }
            return false;
        }

        private static void clearFontSetCaches(Object fontSet) {
            if (fontSet == null) {
                return;
            }
            clearCodepointCache(readField(fontSet, "f_95056_"));
            clearCodepointCache(readField(fontSet, "f_95057_"));
            Object randomGlyphs = readField(fontSet, "f_95058_");
            if (randomGlyphs instanceof Map<?, ?>) {
                ((Map<?, ?>) randomGlyphs).clear();
            } else if (randomGlyphs != null) {
                invokeNoArg(randomGlyphs, "clear");
            }
        }

        private static void closeFontTexture(Object texture) throws Exception {
            if (texture instanceof AutoCloseable) {
                ((AutoCloseable) texture).close();
            } else if (texture != null) {
                invokeNoArg(texture, "close");
            }
        }

        private static void clearCodepointCache(Object cache) {
            if (cache == null) {
                return;
            }
            invokeNoArg(cache, "m_284192_", "clear");
        }

        private static boolean applyOptionsFileDiet(Minecraft minecraft, int level) {
            if (optionsFileDietApplied || level < 2) {
                return false;
            }
            optionsFileDietApplied = true;

            File gameDirectory = getGameDirectory(minecraft);
            if (gameDirectory == null) {
                return false;
            }

            File optionsFile = new File(gameDirectory, "options.txt");
            try {
                LinkedHashMap<String, String> options = new LinkedHashMap<>();
                if (optionsFile.isFile()) {
                    List<String> lines = Files.readAllLines(optionsFile.toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        int separator = line.indexOf(':');
                        if (separator <= 0) {
                            continue;
                        }
                        options.put(line.substring(0, separator), line.substring(separator + 1));
                    }
                }

                boolean changed = false;
                changed |= putOption(options, "forceUnicodeFont", "false");
                changed |= putOption(options, "mipmapLevels", "0");
                changed |= putOption(options, "biomeBlendRadius", "0");
                changed |= putOption(options, "entityShadows", "false");
                changed |= putOption(options, "particles", "0");
                changed |= putOption(options, "graphicsMode", "fast");
                changed |= putOption(options, "prioritizeChunkUpdates", "none");
                if (level >= 3) {
                    changed |= putOption(options, "renderDistance", "2");
                    changed |= putOption(options, "simulationDistance", "4");
                    changed |= putOption(options, "maxFps", "60");
                }

                if (!changed) {
                    logInfo("[NHMemoryGovernor] ram extreme options already present"
                            + " path=" + optionsFile.getAbsolutePath());
                    return false;
                }

                List<String> output = new ArrayList<>(options.size());
                for (Map.Entry<String, String> entry : options.entrySet()) {
                    output.add(entry.getKey() + ":" + entry.getValue());
                }
                Files.write(optionsFile.toPath(), output, StandardCharsets.UTF_8);
                logInfo("[NHMemoryGovernor] ram extreme options.txt diet applied"
                        + " path=" + optionsFile.getAbsolutePath()
                        + " forceUnicodeFont=false mipmapLevels=0 biomeBlendRadius=0");
                return true;
            } catch (Throwable throwable) {
                logWarn("[NHMemoryGovernor] options.txt diet failed", throwable);
                return false;
            }
        }

        private static boolean putOption(Map<String, String> options, String key, String value) {
            String current = options.get(key);
            if (value.equals(current)) {
                return false;
            }
            options.put(key, value);
            return true;
        }

        private static File getGameDirectory(Minecraft minecraft) {
            Object directory = readField(minecraft, "f_91069_");
            if (directory instanceof File) {
                return (File) directory;
            }
            directory = readField(minecraft, "gameDirectory");
            if (directory instanceof File) {
                return (File) directory;
            }
            return new File(".");
        }

        private static boolean setMaxIntOption(Object options, String methodName,
                                               String fieldName, int maxValue) throws Exception {
            Object option = getOption(options, methodName, fieldName);
            if (option == null) {
                return false;
            }
            Object current = invokeGetter(option);
            if (!(current instanceof Number)) {
                return false;
            }
            int currentValue = ((Number) current).intValue();
            int target = Math.min(currentValue, maxValue);
            if (target == currentValue) {
                return false;
            }
            invokeSetter(option, Integer.valueOf(target));
            return true;
        }

        private static boolean setMaxDoubleOption(Object options, String methodName,
                                                  String fieldName, double maxValue) throws Exception {
            Object option = getOption(options, methodName, fieldName);
            if (option == null) {
                return false;
            }
            Object current = invokeGetter(option);
            if (!(current instanceof Number)) {
                return false;
            }
            double currentValue = ((Number) current).doubleValue();
            double target = Math.min(currentValue, maxValue);
            if (Math.abs(target - currentValue) < 0.0001D) {
                return false;
            }
            invokeSetter(option, Double.valueOf(target));
            return true;
        }

        private static boolean setBooleanOption(Object options, String methodName,
                                                String fieldName, boolean target) throws Exception {
            Object option = getOption(options, methodName, fieldName);
            if (option == null) {
                return false;
            }
            Object current = invokeGetter(option);
            if (!(current instanceof Boolean) || ((Boolean) current).booleanValue() == target) {
                return false;
            }
            invokeSetter(option, Boolean.valueOf(target));
            return true;
        }

        private static boolean setEnumOption(Object options, String methodName, String fieldName,
                                             String targetName, int fallbackOrdinal) throws Exception {
            Object option = getOption(options, methodName, fieldName);
            if (option == null) {
                return false;
            }
            Object current = invokeGetter(option);
            if (!(current instanceof Enum<?>)) {
                return false;
            }
            Object target = findEnumValue(current.getClass(), targetName, fallbackOrdinal);
            if (target == null || target == current) {
                return false;
            }
            invokeSetter(option, target);
            return true;
        }

        private static Object getOption(Object options, String methodName, String fieldName) throws Exception {
            if (methodName != null) {
                Object fromMethod = invokeNoArg(options, methodName);
                if (fromMethod != null) {
                    return fromMethod;
                }
            }
            return fieldName == null ? null : readField(options, fieldName);
        }

        private static Object invokeGetter(Object option) throws Exception {
            Object value = invokeNoArg(option, "m_231551_", "c", "get");
            if (value != null) {
                return value;
            }
            throw new NoSuchMethodException("OptionInstance getter");
        }

        private static void invokeSetter(Object option, Object value) throws Exception {
            Method method = findOneArgMethod(option.getClass(), value.getClass(),
                    "m_231514_", "a", "set");
            if (method == null) {
                throw new NoSuchMethodException("OptionInstance setter");
            }
            method.setAccessible(true);
            method.invoke(option, value);
        }

        private static Method findOneArgMethod(Class<?> owner, Class<?> valueClass,
                                               String... names) {
            for (String name : names) {
                for (Method method : owner.getMethods()) {
                    if (matchesOneArg(method, name, valueClass)) {
                        return method;
                    }
                }
                for (Method method : owner.getDeclaredMethods()) {
                    if (matchesOneArg(method, name, valueClass)) {
                        return method;
                    }
                }
            }
            return null;
        }

        private static boolean matchesOneArg(Method method, String name, Class<?> valueClass) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 1) {
                return false;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            return parameter.isAssignableFrom(valueClass)
                    || wrapPrimitive(parameter).isAssignableFrom(wrapPrimitive(valueClass));
        }

        private static Class<?> wrapPrimitive(Class<?> type) {
            if (!type.isPrimitive()) {
                return type;
            }
            if (type == int.class) return Integer.class;
            if (type == double.class) return Double.class;
            if (type == float.class) return Float.class;
            if (type == boolean.class) return Boolean.class;
            if (type == long.class) return Long.class;
            if (type == short.class) return Short.class;
            if (type == byte.class) return Byte.class;
            if (type == char.class) return Character.class;
            return type;
        }

        private static Object findEnumValue(Class<?> enumClass, String targetName, int fallbackOrdinal) {
            Object[] values = enumClass.getEnumConstants();
            if (values == null || values.length == 0) {
                return null;
            }
            for (Object value : values) {
                String name = ((Enum<?>) value).name();
                if (targetName.equalsIgnoreCase(name)) {
                    return value;
                }
            }
            if (fallbackOrdinal >= 0 && fallbackOrdinal < values.length) {
                return values[fallbackOrdinal];
            }
            return values[0];
        }

        private static Object invokeNoArg(Object target, String... names) {
            for (String name : names) {
                try {
                    Method method = target.getClass().getMethod(name);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                }
                try {
                    Method method = target.getClass().getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        private static Object readField(Object target, String name) {
            if (target == null) {
                return null;
            }
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                    type = type.getSuperclass();
                }
            }
            return null;
        }

        private static boolean writeField(Object target, String name, Object value) {
            Field field = resolveField(target, name);
            if (field == null) {
                return false;
            }
            try {
                field.set(target, value);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static Field resolveField(Object target, String name) {
            if (target == null) {
                return null;
            }
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                    type = type.getSuperclass();
                }
            }
            return null;
        }

        private static void reloadLevelRenderer(Minecraft minecraft) {
            Object levelRenderer = readField(minecraft, "f_91060_");
            if (levelRenderer != null) {
                invokeNoArg(levelRenderer, "m_109818_", "f", "allChanged", "reload");
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static final class MemoryState {
            int level;
            long timestampMs;
            int availMb;
            int headroomMb;
            int pssMb;
            int swapMb;
            int graphicsMb;
            String source;
            String summary;

            static MemoryState read() {
                MemoryState bridgeState = readFromBridge();
                if (bridgeState != null) {
                    return bridgeState;
                }
                File file = new File(STATE_PATH);
                if (!file.isFile()) {
                    file = new File(FALLBACK_STATE_PATH);
                }
                if (!file.isFile()) {
                    return null;
                }
                StringBuilder builder = new StringBuilder(256);
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }
                } catch (Throwable ignored) {
                    return null;
                }
                String json = builder.toString();
                MemoryState state = new MemoryState();
                state.level = (int) number(json, "level", 0);
                state.timestampMs = number(json, "timestampMs", 0);
                state.availMb = (int) number(json, "availMb", 0);
                state.headroomMb = (int) number(json, "headroomMb", 0);
                state.pssMb = (int) number(json, "pssMb", 0);
                state.swapMb = (int) number(json, "swapMb", 0);
                state.graphicsMb = (int) number(json, "graphicsMb", 0);
                state.source = "file";
                state.summary = "file-state";
                if (state.timestampMs > 0
                        && System.currentTimeMillis() - state.timestampMs > STALE_STATE_MS) {
                    return null;
                }
                return state;
            }

            private static MemoryState readFromBridge() {
                try {
                    String summary = CallbackBridge.getNewHorizonMemoryPressureSummary();
                    if (summary == null || summary.length() == 0) {
                        return null;
                    }
                    MemoryState state = new MemoryState();
                    state.level = clamp((int) numberAny(summary, 0,
                            "\"level\":", "level=", "level"), 0, 3);
                    if (state.level == 0) {
                        state.level = clamp(CallbackBridge.getNewHorizonMemoryPressureLevel(), 0, 3);
                    }
                    state.timestampMs = System.currentTimeMillis();
                    state.source = "bridge";
                    state.summary = summary;
                    state.availMb = (int) numberAny(summary, 0,
                            "\"availMb\":", "availMb=", "availMb");
                    state.headroomMb = (int) numberAny(summary, 0,
                            "\"headroomMb\":", "headroomMb=", "headroomMb");
                    state.pssMb = (int) numberAny(summary, 0,
                            "\"pssMb\":", "pssMb=", "pssMb");
                    state.swapMb = (int) numberAny(summary, 0,
                            "\"swapPssMb\":", "swapPssMb=", "\"swapMb\":", "swapMb=", "swapMb");
                    state.graphicsMb = (int) numberAny(summary, 0,
                            "\"graphicsMb\":", "graphicsMb=", "graphicsMb");
                    return state;
                } catch (Throwable ignored) {
                    return null;
                }
            }

            private static String readFromBridgeSummary() {
                try {
                    return CallbackBridge.getNewHorizonMemoryPressureSummary();
                } catch (Throwable ignored) {
                    return "";
                }
            }

            private static long number(String json, String key, long fallback) {
                String needle = key.startsWith("\"") || key.endsWith("=")
                        ? key
                        : "\"" + key + "\":";
                int start = json.indexOf(needle);
                if (start < 0) {
                    return fallback;
                }
                start += needle.length();
                int end = start;
                while (end < json.length()) {
                    char c = json.charAt(end);
                    if ((c < '0' || c > '9') && c != '-') {
                        break;
                    }
                    end++;
                }
                if (end <= start) {
                    return fallback;
                }
                try {
                    return Long.parseLong(json.substring(start, end));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }

            private static long numberAny(String text, long fallback, String... keys) {
                if (text == null || keys == null) {
                    return fallback;
                }
                for (String key : keys) {
                    long value = number(text, key, Long.MIN_VALUE);
                    if (value != Long.MIN_VALUE) {
                        return value;
                    }
                }
                return fallback;
            }
        }
    }

    /**
     * Consumes Android touch deltas directly on the render thread. The native GLFW
     * cursor remains anchored, while vanilla MouseHandler still owns sensitivity,
     * cinematic smoothing, invert-Y and player rotation.
     */
    private static final class RelativeTouch {
        private static final int MAX_NATIVE_INIT_ATTEMPTS = 120;

        private static Field accumulatedX;
        private static Field accumulatedY;
        private static boolean active;
        private static boolean permanentlyDisabled;
        private static int nativeInitAttempts;

        private RelativeTouch() {
        }

        private static void applyPendingDelta(Minecraft minecraft) {
            if (!ensureInitialized()) {
                return;
            }

            MouseHandler mouse = minecraft.f_91067_;
            long packed = CallbackBridge.nativeConsumeRelativeTouchDelta();
            if (!isGameplayActive(minecraft, minecraft.f_91080_)
                    || !mouse.m_91600_()) {
                // Never let movement submitted under a previous gameplay state leak
                // into the first frame after a menu, focus or world transition.
                return;
            }

            if (packed == 0L) {
                return;
            }

            float deltaX = Float.intBitsToFloat((int) (packed >>> 32));
            float deltaY = Float.intBitsToFloat((int) packed);
            if (!Float.isFinite(deltaX) || !Float.isFinite(deltaY)) {
                disable(new IllegalStateException("non-finite relative delta"));
                return;
            }

            try {
                accumulatedX.setDouble(mouse, accumulatedX.getDouble(mouse) + deltaX);
                accumulatedY.setDouble(mouse, accumulatedY.getDouble(mouse) + deltaY);
                mouse.m_91523_();
            } catch (Throwable throwable) {
                disable(throwable);
            }
        }

        private static boolean ensureInitialized() {
            if (active) {
                return true;
            }
            if (permanentlyDisabled || nativeInitAttempts >= MAX_NATIVE_INIT_ATTEMPTS) {
                return false;
            }

            nativeInitAttempts++;
            try {
                if (accumulatedX == null || accumulatedY == null) {
                    accumulatedX = MouseHandler.class.getDeclaredField("f_91516_");
                    accumulatedY = MouseHandler.class.getDeclaredField("f_91517_");
                    accumulatedX.setAccessible(true);
                    accumulatedY.setAccessible(true);
                }
                CallbackBridge.nativeSetRelativeTouchMode(true);
                active = true;
                logInfo("relative touch active"
                        + " fields=f_91516_,f_91517_");
                return true;
            } catch (UnsatisfiedLinkError error) {
                if (nativeInitAttempts == 1 || nativeInitAttempts == 30
                        || nativeInitAttempts == MAX_NATIVE_INIT_ATTEMPTS) {
                    logWarn("relative touch native bridge"
                            + " not ready attempt=" + nativeInitAttempts);
                }
                return false;
            } catch (Throwable throwable) {
                disable(throwable);
                return false;
            }
        }

        private static void disable(Throwable throwable) {
            active = false;
            permanentlyDisabled = true;
            try {
                CallbackBridge.nativeSetRelativeTouchMode(false);
            } catch (Throwable ignored) {
                // The old absolute GLFW path remains the safe fallback.
            }
            logWarn("relative touch disabled; falling back to GLFW cursor deltas", throwable);
        }
    }
}
