package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.ThinClientRuntime;
import com.newhorizon.thinclient.audio.ThinSoundEngine;
import com.newhorizon.thinclient.audio.LocalPlayerSounds;
import com.newhorizon.thinclient.display.DisplayController;
import com.newhorizon.thinclient.display.DisplaySide;
import com.newhorizon.thinclient.display.GeckoNativeBridge;
import com.newhorizon.thinclient.display.VirtualDisplay;
import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;
import com.newhorizon.thinclient.inventory.InventoryProtocol;
import com.newhorizon.thinclient.inventory.ConsumableUse;
import com.newhorizon.thinclient.protocol.MinecraftConnection;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.world.BlockStatePhysics;
import com.newhorizon.thinclient.world.SurfaceChunk;
import com.newhorizon.thinclient.world.EntityTracker;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/** Minimal LWJGL scene; Pojav routes its desktop GL calls through LTW. */
public final class LtwScene implements AutoCloseable {
    // A vanilla view distance of four is a complete 9x9 square, not 64 slots.
    private static final int MAX_CHUNKS = 9 * 9;
    private static final double WALK_SPEED = 4.317;
    private static final double SPRINT_SPEED = WALK_SPEED * 1.30;
    private static final double SNEAK_SPEED = WALK_SPEED * 0.30;
    private static final double GROUND_ACCELERATION = 42.0;
    private static final double AIR_ACCELERATION = 10.0;
    // LivingEntity travel: flying input is followed by 0.91 horizontal drag.
    private static final double VANILLA_FLIGHT_SPEED_SCALE = 20.0 / (1.0 - 0.91);
    // LocalPlayer adds flyingSpeed * 3 and Player retains 0.6 vertical velocity.
    private static final double VANILLA_VERTICAL_FLIGHT_SPEED_SCALE =
            20.0 * 3.0 / (1.0 - 0.60);
    private static final double GROUND_DRAG_PER_TICK = 0.60;
    private static final double AIR_DRAG_PER_TICK = 0.91;
    private static final double FLIGHT_DRAG_PER_TICK = 0.91;
    private static final double VERTICAL_FLIGHT_DRAG_PER_TICK = 0.60;
    private static final double VERTICAL_DRAG_PER_TICK = 0.98;
    private static final double WATER_DRAG_PER_TICK = 0.80;
    private static final double LAVA_DRAG_PER_TICK = 0.50;
    private static final double WATER_MOVE_SPEED = 2.35;
    private static final double WATER_SPRINT_SPEED = 4.60;
    private static final double LAVA_MOVE_SPEED = 1.15;
    private static final double WATER_ASCEND_SPEED = 3.20;
    private static final double LAVA_ASCEND_SPEED = 2.00;
    private static final double FLUID_GRAVITY = 8.0;
    private static final double MOTION_EPSILON = 0.002;
    private static final double PLAYER_RADIUS = 0.30;
    private static final double PLAYER_HEIGHT = 1.80;
    private static final double SNEAK_HEIGHT = 1.50;
    private static final float STANDING_EYE_HEIGHT = 1.62f;
    private static final float SNEAKING_EYE_HEIGHT = 1.27f;
    private static final int COMMAND_PRESS_SHIFT = 0;
    private static final int COMMAND_RELEASE_SHIFT = 1;
    private static final int COMMAND_START_SPRINT = 3;
    private static final int COMMAND_STOP_SPRINT = 4;
    private static final double GRAVITY = 32.0;
    private static final double TERMINAL_VELOCITY = -78.0;
    private static final double JUMP_VELOCITY = 8.4;
    private static final double MAX_PHYSICS_STEP = 0.18;
    private static final double COLLISION_EPSILON = 1.0e-5;
    private static final double VANILLA_TICK_SECONDS = 0.05;
    private static final long MOVEMENT_INTERVAL_NANOS = 50_000_000L;
    private static final long DOUBLE_TAP_NANOS = 450_000_000L;
    private static final long SERVER_SETTLE_NANOS = 350_000_000L;
    private static final float MOUSE_SENSITIVITY = 0.12f;
    private static final int MAX_DISPLAYS = 8;
    private static final int GPU_TEXTURES_PER_DISPLAY = 3;
    private static final double DISPLAY_INTERACTION_RANGE = 32.0;
    private static final int MOUSE_EVENT_CAPACITY = 16;
    private static final double VANILLA_INTERACTION_RANGE = 4.5;
    private static final double BLOCK_RAY_STEP = 0.02;
    private static final long DESTROY_SWING_INTERVAL_NANOS = 250_000_000L;
    private static final long USE_REPEAT_INTERVAL_NANOS = 200_000_000L;
    private static final int ACTION_START_DESTROY_BLOCK = 0;
    private static final int ACTION_ABORT_DESTROY_BLOCK = 1;
    private static final int ACTION_STOP_DESTROY_BLOCK = 2;
    private static final int FIRST_PERSON_VERTEX_FLOATS = 9;
    private static final float[] FISHING_ROD_POSE = fishingRodPose();
    private static final int FIRST_PERSON_MAX_VERTICES = 16384;
    private static final int SELECTION_VERTEX_FLOATS = 7;
    private static final int SELECTION_VERTEX_COUNT = 24;
    private static final long TARGET_UPDATE_NANOS = 50_000_000L;
    private static final int LEGACY_BREAK_TICKS = 18;
    private final ThinClientRuntime runtime;
    private final MinecraftConnection connection;
    private final ThinSoundEngine audio;
    private final LocalPlayerSounds localSounds;
    private final com.newhorizon.thinclient.audio.AmbientSounds ambientSounds;
    private long nextMiningSoundNanos;
    private final GpuChunk[] gpuChunks = new GpuChunk[MAX_CHUNKS];
    private final GpuChunk[] remappedGpuChunks = new GpuChunk[MAX_CHUNKS];
    private final long[] deferredMeshKeys=new long[MAX_CHUNKS];
    private final long[] deferredMeshEpochs=new long[MAX_CHUNKS];
    private final int[] deferredMeshBytes=new int[MAX_CHUNKS];
    private long gpuPlayerChunk=Long.MIN_VALUE;
    private int gpuPressureEvents;
    private final ChunkMeshWorker meshWorker;
    private final long[] meshVersions=new long[MAX_CHUNKS*3+1];
    private ChunkMeshWorker.Result pendingMesh;
    private GpuChunk uploadGpu;
    private int uploadOffset;
    private long streamLogAt,streamMaxFrame,streamMaxInput,streamMaxUpload,streamMaxRender;
    private int streamFrames;
    private final double[] cameraPosition = new double[3];
    /** Previous 20 Hz physics state and the partial-tick camera shown by LTW. */
    private final double[] previousPhysicsPosition = new double[3];
    private final double[] renderCameraPosition = new double[3];
    private final float[] cameraRotation = new float[2];
    private final double[] serverPosition = new double[3];
    private final float[] serverRotation = new float[2];
    private final IntBuffer framebufferWidth = BufferUtils.createIntBuffer(1);
    private final IntBuffer framebufferHeight = BufferUtils.createIntBuffer(1);
    private final DoubleBuffer cursorX = BufferUtils.createDoubleBuffer(1);
    private final DoubleBuffer cursorY = BufferUtils.createDoubleBuffer(1);
    private final boolean[] pressedKeys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final DisplayController.Renderable[] displaySnapshots =
            new DisplayController.Renderable[MAX_DISPLAYS];
    private final DisplayGpu[] displayGpu = new DisplayGpu[MAX_DISPLAYS+2];
    private final com.newhorizon.thinclient.display.MinePadController minePads;
    private final float[][] minePadMatrices=new float[2][];
    private final FloatBuffer displayVertices = BufferUtils.createFloatBuffer(20);
    private final FloatBuffer firstPersonVertices = BufferUtils.createFloatBuffer(
            FIRST_PERSON_MAX_VERTICES * FIRST_PERSON_VERTEX_FLOATS);
    private final FloatBuffer selectionVertices = BufferUtils.createFloatBuffer(
            SELECTION_VERTEX_COUNT * SELECTION_VERTEX_FLOATS);
    private final float[] modBeamCorners=new float[24];
    private static final int[][] MOD_BEAM_FACES={{0,1,5,4},{0,3,7,4},{1,2,6,5},{3,2,6,7}};
    private final HitResult displayHit = new HitResult();
    private final VanillaHitResult vanillaHit = new VanillaHitResult();
    private final VanillaHitResult heldHit = new VanillaHitResult();
    private final VanillaHitResult selectionHit = new VanillaHitResult();
    private final EntityTracker.Hit entityHit = new EntityTracker.Hit();
    private final EntityRenderer entityRenderer;
    private final EnvironmentRenderer environmentRenderer;
    private final int[] mouseEventButtons = new int[MOUSE_EVENT_CAPACITY];
    private final int[] mouseEventActions = new int[MOUSE_EVENT_CAPACITY];
    private long window;
    private final RailAtlas railAtlas = new RailAtlas();
    private final BlockAtlas blockAtlas = new BlockAtlas();
    private final ByteBuffer breakingVertices=BufferUtils.createByteBuffer(36*SurfaceMesher.VERTEX_BYTES);
    private int breakingVbo;
    private final BiomeTintAtlas biomeTints=new BiomeTintAtlas();
    private int shaderProgram;
    private int vao;
    private int cameraUniform;
    private int rotationUniform;
    private int aspectUniform;
    private int projectionUniform;
    private int mediumUniform;
    private int materialPassUniform;
    private int displayShaderProgram;
    private int displayVao;
    private int displayVbo;
    private int displayCameraUniform;
    private int displayRotationUniform;
    private int displayAspectUniform;
    private int displayProjectionUniform;
    private int displayTextureUniform;
    private int firstPersonShaderProgram;
    private int firstPersonVao;
    private int firstPersonVbo;
    private int firstPersonAspectUniform;
    private int firstPersonProjectionUniform;
    private int firstPersonSwingUniform;
    private int firstPersonEatUniform;
    private int firstPersonHandUniform;
    private int selectionShaderProgram;
    private int selectionVao;
    private int selectionVbo;
    private int selectionCameraUniform;
    private int selectionRotationUniform;
    private int selectionAspectUniform;
    private int selectionProjectionUniform;
    private boolean lastModBeam,lastPadSide=true,firstPersonModPose,buildingOffhand;
    private float lastModSwing=-1,buildingModSwing;
    private int firstPersonArmVertexCount, firstPersonOffhandStart;
    private boolean lastShieldPose,lastFishingCast;
    private int lastDrawStage=-1;
    private long itemUseStarted;
    private long useCompletionAtStart;
    private int lastConsumeTick;
    private int consumeParticleItem;
    private int firstPersonVertexCount;
    private volatile boolean stopRequested;
    private boolean released;
    private boolean glInitialized;
    private int loggedWorldChunks = -1;
    private int meshTruncationLogCount;
    private long lastCoverageCheckNanos;
    private long lastCoverageChunkKey = Long.MIN_VALUE;
    private int lastMissingChunkCount = -1;
    private boolean cameraInitialized;
    private boolean cursorInitialized;
    private boolean inputLogged;
    private double lastCursorX;
    private double lastCursorY;
    private long lastFrameNanos;
    private long lastMovementNanos;
    private long lastWorldRevision = -1L;
    private long lastTeleportRevision = -1L;
    private long lastActiveMovementNanos;
    private GLFWKeyCallbackI keyCallback;
    private GLFWCursorPosCallbackI cursorCallback;
    private GLFWMouseButtonCallbackI mouseButtonCallback;
    private volatile double callbackCursorX;
    private volatile double callbackCursorY;
    private volatile boolean callbackCursorReady;
    private int keyEventLogCount;
    private int displaySnapshotCount;
    private int mouseEventRead;
    private int mouseEventWrite;
    private int activePrimaryBrowser = -1;
    private int activeSecondaryBrowser = -1;
    private boolean displayLaserEnabled, displayLaserPermissionKnown;
    private int activePrimaryX;
    private int activePrimaryY;
    private int activeSecondaryX;
    private int activeSecondaryY;
    private int displayFrameLogCount;
    private int mouseEventLogCount;
    private boolean primaryHeld;
    private boolean secondaryHeld;
    private final com.newhorizon.thinclient.inventory.UsePressState usePress=new com.newhorizon.thinclient.inventory.UsePressState();
    private boolean escapeWasPressed;
    private long nextEffectHudNanos;
    private long nextCombatHudNanos;
    private final double[] combatMotion=new double[3];
    private final com.newhorizon.thinclient.world.BoatMotion vehicleMotion=new com.newhorizon.thinclient.world.BoatMotion();
    private final EntityTracker.Vehicle ridingVehicle=new EntityTracker.Vehicle();
    private final double[] rideCorrection=new double[5],ridePrevious=new double[5],rideSeat=new double[3];
    private int mountedId=-1,jumpChargeTicks;
    private long nextRideHud,rideTickNanos;
    private boolean wasSleeping,wakeRequested,dismountRequested,mountJumpPressed,wasControlling;
    private int chargingHand;
    private boolean chargingItem;
    private String chargingMaterial = "";
    private int chargingSlot;
    private long chargingGeneration;
    private boolean destroyingBlock;
    private long nextDestroySwingNanos;
    private long nextUseNanos;
    private long destroyStartNanos;
    private long destroyRequiredNanos;
    private long swingStartNanos = Long.MIN_VALUE;
    private long lastTargetUpdateNanos;
    private int selectionMeshX = Integer.MIN_VALUE;
    private int selectionMeshY;
    private int selectionMeshZ;
    private int selectionMeshState;
    private boolean selectionMeshVisible;
    private double verticalVelocity;
    private double horizontalVelocityX;
    private double horizontalVelocityZ;
    private double flightVerticalVelocity;
    private boolean wasFlying;
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean jumpWasPressed;
    private boolean sneaking;
    private boolean sprinting;
    private final com.newhorizon.thinclient.world.SwimmingState swimming = new com.newhorizon.thinclient.world.SwimmingState();
    private boolean sentSneaking;
    private boolean sentSprinting;
    private long lastForwardPressNanos;
    private long lastJumpPressNanos;
    private boolean flightToggleRequested;
    private volatile boolean inventoryToggleRequested;
    private volatile boolean diagnosticToggleRequested;
    private final com.newhorizon.thinclient.world.PerspectiveCamera viewCamera=new com.newhorizon.thinclient.world.PerspectiveCamera();
    private final com.newhorizon.thinclient.world.LocalPlayerVisual localVisual=new com.newhorizon.thinclient.world.LocalPlayerVisual();
    private boolean debugVisible,hudHidden;
    private final DebugOverlay debugOverlay=new DebugOverlay();
    private long nextGameHud,frameWindow;
    private int renderedFrames,framesPerSecond;
    private final long[] sentSkinRevision=new long[64];
    private com.newhorizon.thinclient.world.PlayerSkins.Skin localSkin;
    private long localSkinRevision;
    private boolean inventoryOpen;
    private boolean chatOpen;
    private String chatRequested;
    private int lastMovementPhysicsFlags = -1;
    private double vanillaPhysicsAccumulator;
    private long lastInventoryRevision = -1L;
    private long lastHotbarRevision = -1L;
    private long lastFirstPersonRevision = -1L;

    public LtwScene(ThinClientRuntime runtime, MinecraftConnection connection, ThinSoundEngine audio) {
        this.runtime = runtime;
        this.connection = connection;
        this.audio = audio;
        minePads=new com.newhorizon.thinclient.display.MinePadController(runtime.displays,new com.newhorizon.thinclient.display.MinePadController.Port(){
            public void create(int id,String url,boolean transparent){GeckoNativeBridge.createMinePad(id,url);}
            public void resize(int id,int width,int height){new GeckoNativeBridge().resize(id,width,height);}
            public void navigate(int id,String url){GeckoNativeBridge.navigateMinePad(id,url);}
            public void setVisible(int id,boolean visible){new GeckoNativeBridge().setVisible(id,visible);}
            public void setFocused(int id,boolean focused){new GeckoNativeBridge().setFocused(id,focused);}
            public void destroy(int id){new GeckoNativeBridge().destroy(id);}
            public void show(int id,String url,int index,String[] urls,boolean editing){GeckoNativeBridge.showMinePad(id,url,index,urls,editing);}
            public void hide(){GeckoNativeBridge.hideMinePad();}
            public void error(String message){GeckoNativeBridge.chatMessage("{\"text\":"+com.newhorizon.thinclient.protocol.ChatProtocol.quote(message)+",\"color\":\"red\"}");}
        },(id,action,index,url)->connection.sendCustomPayload("webdisplays:packetsystem",com.newhorizon.thinclient.display.MinePadData.request(id,action,index,url)));
        this.localSounds = new LocalPlayerSounds(runtime.world, runtime.sounds);
        this.ambientSounds = new com.newhorizon.thinclient.audio.AmbientSounds(runtime, audio);
        meshWorker=new ChunkMeshWorker(runtime.world,runtime.budget,gpuChunks.length);
        try {
            for (int index = 0; index < displaySnapshots.length; index++) {
                displaySnapshots[index] = new DisplayController.Renderable();
            }
            entityRenderer = new EntityRenderer(runtime.entities);
            entityRenderer.players(runtime.players,runtime.playerSkins);
            environmentRenderer = new EnvironmentRenderer(runtime.environment);
        } catch (RuntimeException | Error throwable) {
            meshWorker.close();
            throw throwable;
        }
    }

    /** Owns the render thread until the Android/GLFW surface is closed. */
    public void run() {
        System.out.println("[NH-THIN] glfwInit begin");
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        System.out.println("[NH-THIN] glfwInit complete");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        window = GLFW.glfwCreateWindow(854, 480, "New Horizon Thin", 0, 0);
        if (window == 0) throw new IllegalStateException("GLFW window creation failed");
        System.out.println("[NH-THIN] GLFW window ready");
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        installInputCallbacks();
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        GL.createCapabilities();
        initializeGl();
        debugOverlay.vendor=GL33.glGetString(GL33.GL_VENDOR);debugOverlay.renderer=GL33.glGetString(GL33.GL_RENDERER);debugOverlay.glVersion=GL33.glGetString(GL33.GL_VERSION);
        System.out.println("[NH-THIN] LTW GL scene ready");

        ScreenEffects.reset();
        glInitialized = true;
        lastFrameNanos = System.nanoTime();
        long nextTabletWorldFrame=0;
        while (!stopRequested && !GLFW.glfwWindowShouldClose(window)) {
            long debugStart=System.nanoTime();
            GLFW.glfwPollEvents();
            boolean escape=GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE)==GLFW.GLFW_PRESS;
            if (escape && !escapeWasPressed) {
                if(minePads.isOpen())minePads.hide();
                else if(chatOpen){chatOpen=false;GeckoNativeBridge.hideChat();}
                else if(runtime.riding.sleeping())wakeRequested=true;
                else if(inventoryOpen) inventoryToggleRequested=true;
                else GLFW.glfwSetWindowShouldClose(window, true);
            }
            escapeWasPressed=escape;
            updateInputAndMovement();
            // Keep input/network polling responsive while Gecko owns the visible tablet.
            // The dimmed world behind it needs only ten frames per second.
            long frameNow=System.nanoTime();
            if(minePads.isOpen()&&frameNow<nextTabletWorldFrame){
                java.util.concurrent.locks.LockSupport.parkNanos(4_000_000L);continue;
            }
            nextTabletWorldFrame=minePads.isOpen()?frameNow+100_000_000L:0;
            if (cameraInitialized) {
                runtime.entities.localPlayer(connection.playerEntityId(),renderCameraPosition[0],renderCameraPosition[1],renderCameraPosition[2]);
                runtime.entities.localRopeView(cameraRotation[0],cameraRotation[1],
                        !"FISHING_ROD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(runtime.inventory.selectedHotbarMaterial())));
                updateFishingLineTip();
                audio.updateListener(renderCameraPosition[0], renderCameraPosition[1] + eyeHeight(),
                        renderCameraPosition[2], cameraRotation[0], cameraRotation[1]);
                audio.setLocalPlayer(connection.playerEntityId(), cameraPosition[1]);
                localSounds.update(System.nanoTime());
                runtime.entities.worldParticles.ambient(runtime.world,cameraPosition[0],cameraPosition[1],cameraPosition[2],System.nanoTime());
                ambientSounds.update(System.nanoTime(), cameraPosition[0], cameraPosition[1],
                        cameraPosition[1] + eyeHeight(), cameraPosition[2], connection.canFly());
            }
            long debugInput=System.nanoTime();
            synchronizeWorldMeshes();
            long debugMesh=System.nanoTime();
            drawFrame();
            long debugRender=System.nanoTime();
            GLFW.glfwSwapBuffers(window);
            long debugEnd=System.nanoTime();debugOverlay.frames.add(debugEnd-debugStart,debugInput-debugStart,debugMesh-debugInput,debugRender-debugMesh,debugEnd-debugRender);
            streamFrames++;streamMaxFrame=Math.max(streamMaxFrame,debugEnd-debugStart);
            streamMaxInput=Math.max(streamMaxInput,debugInput-debugStart);streamMaxUpload=Math.max(streamMaxUpload,debugMesh-debugInput);
            streamMaxRender=Math.max(streamMaxRender,debugRender-debugMesh);
            if(streamLogAt==0)streamLogAt=debugEnd;
            if(debugEnd-streamLogAt>=10_000_000_000L){System.out.println("[NH-STREAM] frames="+streamFrames+" maxMs frame="+streamMaxFrame/1_000_000+" input="+streamMaxInput/1_000_000+" upload="+streamMaxUpload/1_000_000+" render="+streamMaxRender/1_000_000+" meshKiB="+runtime.budget.used(MemoryCategory.MESH)/1024+" pressure="+gpuPressureEvents);streamLogAt=debugEnd;streamFrames=0;streamMaxFrame=streamMaxInput=streamMaxUpload=streamMaxRender=0;}
        }
    }

    public void requestStop() {
        stopRequested = true;
        meshWorker.close();
    }

    private void installInputCallbacks() {
        keyCallback = (callbackWindow, key, scanCode, action, modifiers) -> {
            if (key >= 0 && key < pressedKeys.length) {
                pressedKeys[key] = action != GLFW.GLFW_RELEASE;
            }
            if(chatOpen||minePads.isOpen())return;
            if (action == GLFW.GLFW_PRESS) {
                if(!inventoryOpen&&(key==GLFW.GLFW_KEY_T||key==GLFW.GLFW_KEY_SLASH)){
                    chatRequested=key==GLFW.GLFW_KEY_SLASH?"/":"";return;
                }
                if (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_UP) {
                    long now = System.nanoTime();
                    if (now - lastForwardPressNanos <= DOUBLE_TAP_NANOS) {
                        sprinting = true;
                    }
                    lastForwardPressNanos = now;
                }
                if (key == GLFW.GLFW_KEY_SPACE) {
                    long now = System.nanoTime();
                    if (now - lastJumpPressNanos <= DOUBLE_TAP_NANOS) {
                        flightToggleRequested = true;
                    }
                    lastJumpPressNanos = now;
                }
                if (key == GLFW.GLFW_KEY_E && !runtime.combat.dead()) inventoryToggleRequested = true;
                if(key==GLFW.GLFW_KEY_F && !inventoryOpen && !runtime.combat.dead()) {
                    releaseVanillaItem();
                    try {connection.sendPlayerAction(6,0,0,0,0);}
                    catch(IOException | ProtocolException error) {throw new IllegalStateException("Unable to swap hands",error);}
                }
                if(key==GLFW.GLFW_KEY_F3){
                    if((modifiers&GLFW.GLFW_MOD_ALT)!=0){debugOverlay.chart=!debugOverlay.chart;debugVisible=true;}
                    else if((modifiers&GLFW.GLFW_MOD_SHIFT)!=0){debugOverlay.pie=!debugOverlay.pie;debugVisible=true;}
                    else diagnosticToggleRequested=true;
                    nextGameHud=0;
                }
                if(key==GLFW.GLFW_KEY_Q&&pressedKeys[GLFW.GLFW_KEY_F3]){debugOverlay.help=!debugOverlay.help;debugVisible=true;nextGameHud=0;}
                if(key==GLFW.GLFW_KEY_F5){viewCamera.cycle();nextGameHud=0;}
                if(key==GLFW.GLFW_KEY_F1){hudHidden=!hudHidden;nextGameHud=0;}
                if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                    releaseVanillaItem();
                    sendInventoryRequest(InventoryProtocol.selectRequest(
                            key - GLFW.GLFW_KEY_1));
                }
            }
            if(key==GLFW.GLFW_KEY_TAB)nextGameHud=0;
            if (keyEventLogCount < 8) {
                keyEventLogCount++;
                System.out.println("[NH-THIN] key event key=" + key
                        + " action=" + action);
            }
        };
        cursorCallback = (callbackWindow, x, y) -> {
            callbackCursorX = x;
            callbackCursorY = y;
            callbackCursorReady = true;
        };
        mouseButtonCallback = (callbackWindow, button, action, modifiers) -> {
            if ((button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                    && (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_RELEASE)) {
                queueMouseEvent(button, action);
                if (mouseEventLogCount < 8) {
                    mouseEventLogCount++;
                    System.out.println("[NH-THIN] mouse button event button="
                            + button + " action=" + action);
                }
            }
        };
        GLFW.glfwSetKeyCallback(window, keyCallback);
        GLFW.glfwSetCursorPosCallback(window, cursorCallback);
        GLFW.glfwSetMouseButtonCallback(window, mouseButtonCallback);
    }

    private void updateInputAndMovement() {
        long now = System.nanoTime();
        double seconds = Math.min(0.05, Math.max(0.0,
                (now - lastFrameNanos) / 1_000_000_000.0));
        lastFrameNanos = now;

        if (!cameraInitialized) {
            if (!runtime.world.hasPlayerPosition()) return;
            runtime.world.copyPlayerView(cameraPosition, cameraRotation);
            snapRenderCameraToPhysics();
            lastWorldRevision = runtime.world.playerRevision();
            lastTeleportRevision = runtime.world.teleportRevision();
            cameraInitialized = true;
        }

        processClientUi();
        updateDisplayInputPermission();
        updateGameHud(now);
        minePads.update(runtime.inventory,now);
        runtime.combat.selectWeapon(runtime.inventory.selectedHotbarMaterial(),now);
        int motionMode=runtime.combat.drainMotion(combatMotion);
        if(motionMode!=0) {
            horizontalVelocityX=(motionMode==1?0:horizontalVelocityX)+combatMotion[0]*20;
            verticalVelocity=(motionMode==1?0:verticalVelocity)+combatMotion[1]*20;
            horizontalVelocityZ=(motionMode==1?0:horizontalVelocityZ)+combatMotion[2]*20;
            if(verticalVelocity>0)onGround=false;
            lastActiveMovementNanos=now;
        }
        if(now>=nextCombatHudNanos) {
            double ey=cameraPosition[1]+eyeHeight();
            boolean underwater=(runtime.world.physicsFlagsInBox(cameraPosition[0]-.01,ey-.01,cameraPosition[2]-.01,
                    cameraPosition[0]+.01,ey+.01,cameraPosition[2]+.01)&BlockStatePhysics.WATER)!=0;
            GeckoNativeBridge.updateCombat(runtime.combat.encodeForUi(now,runtime.effects,
                    runtime.inventory.isCreativeMode(),selectionHit.type==VanillaHitResult.ENTITY && runtime.entities.alive(selectionHit.entityId))+","+(underwater?1:0),inventoryOpen||minePads.isOpen());
            nextCombatHudNanos=now+(minePads.isOpen()?250_000_000L:50_000_000L);
        }
        if(runtime.combat.dead()) {
            minePads.hide();
            if(now>=nextRideHud){GeckoNativeBridge.updateRiding(0,"",false);nextRideHud=now+150_000_000L;}
            if(inventoryOpen) {inventoryOpen=false;GeckoNativeBridge.hideInventory();sendInventoryRequest(InventoryProtocol.closeRequest());}
            releaseVanillaItem();primaryHeld=secondaryHeld=false;stopVanillaAttack();
            java.util.Arrays.fill(pressedKeys,false);mouseEventRead=mouseEventWrite;return;
        }
        if(now>=nextEffectHudNanos) {
            GeckoNativeBridge.updateEffects(runtime.effects.encodeForUi());
            nextEffectHudNanos=now+250_000_000L;
        }
        if (inventoryOpen || chatOpen || minePads.isOpen()) {
            releaseVanillaItem();
            secondaryHeld=false;
            primaryHeld=false;
            stopVanillaAttack();
            callbackCursorReady = false;
            mouseEventRead = mouseEventWrite;
            java.util.Arrays.fill(pressedKeys,false);
        }

        if(updateAttachedState(now))return;
        if(!inventoryOpen&&!chatOpen&&!minePads.isOpen()) updateCameraRotation();
        resolvePlayerOverlap();
        double forward = axis(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP)
                - axis(GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN);
        double strafe = axis(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT)
                - axis(GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT);
        updateMovementState(forward);
        boolean horizontalInput = forward != 0.0 || strafe != 0.0;
        int physicsFlags = playerPhysicsFlags();
        if (physicsFlags != lastMovementPhysicsFlags) {
            lastMovementPhysicsFlags = physicsFlags;
            System.out.println("[NH-THIN] vanilla movement medium=" + physicsFlags);
        }
        if (connection.isFlying() || flightToggleRequested) {
            runtime.entities.worldParticles.movement(runtime.world,cameraPosition[0],cameraPosition[1],cameraPosition[2],0,0,0,false,false,true,false,System.nanoTime());
            vanillaPhysicsAccumulator = 0.0;
            updateVerticalPhysics(seconds, physicsFlags);
            updateHorizontalPhysics(forward, strafe, horizontalInput, seconds,
                    physicsFlags);
            snapRenderCameraToPhysics();
        } else {
            vanillaPhysicsAccumulator = Math.min(0.15,
                    vanillaPhysicsAccumulator + seconds);
            while (vanillaPhysicsAccumulator >= VANILLA_TICK_SECONDS) {
                copyPosition(cameraPosition, previousPhysicsPosition);
                updateMovementState(forward);
                physicsFlags = playerPhysicsFlags();
                updateVanillaPhysicsTick(forward, strafe, horizontalInput,
                        physicsFlags);
                localSounds.movement(cameraPosition[0], cameraPosition[1], cameraPosition[2],
                        cameraPosition[0] - previousPhysicsPosition[0],
                        cameraPosition[1] - previousPhysicsPosition[1],
                        cameraPosition[2] - previousPhysicsPosition[2],
                        horizontalVelocityX / 20, verticalVelocity / 20, horizontalVelocityZ / 20,
                        onGround, sneaking, connection.isFlying(), physicsFlags);
                runtime.entities.worldParticles.movement(runtime.world,cameraPosition[0],cameraPosition[1],cameraPosition[2],
                        cameraPosition[0]-previousPhysicsPosition[0],cameraPosition[1]-previousPhysicsPosition[1],cameraPosition[2]-previousPhysicsPosition[2],
                        onGround,sprinting,connection.isFlying(),(physicsFlags&(BlockStatePhysics.WATER|BlockStatePhysics.LAVA))!=0,System.nanoTime());
                vanillaPhysicsAccumulator -= VANILLA_TICK_SECONDS;
            }
            interpolateRenderCamera();
        }
        if (horizontalInput && !inputLogged) {
            inputLogged = true;
            System.out.println("[NH-THIN] movement input active");
        }
        boolean flightInput = connection.isFlying()
                && (pressedKeys[GLFW.GLFW_KEY_SPACE]
                || pressedKeys[GLFW.GLFW_KEY_LEFT_SHIFT]
                || pressedKeys[GLFW.GLFW_KEY_RIGHT_SHIFT]);
        boolean moving = horizontalInput || flightInput
                || horizontalVelocityX * horizontalVelocityX
                + horizontalVelocityZ * horizontalVelocityZ
                > MOTION_EPSILON * MOTION_EPSILON
                || Math.abs(verticalVelocity) > MOTION_EPSILON
                || Math.abs(flightVerticalVelocity) > MOTION_EPSILON;
        if (moving) lastActiveMovementNanos = now;
        reconcileServerPosition(moving, now);
        if (connection.isFlying()) snapRenderCameraToPhysics();
        runtime.displays.updatePlayer(runtime.world.dimension(),
                cameraPosition[0], cameraPosition[1], cameraPosition[2]);

        if (now - lastMovementNanos >= MOVEMENT_INTERVAL_NANOS) {
            try {
                if (connection.sendMovement(cameraPosition[0], cameraPosition[1],
                        cameraPosition[2], cameraRotation[0], cameraRotation[1], onGround)) {
                    lastMovementNanos = now;
                }
            } catch (IOException | ProtocolException exception) {
                throw new IllegalStateException("Unable to send player movement", exception);
            }
        }
    }

    /** Riding and sleep replace walking physics; server state owns attachment transitions. */
    private boolean updateAttachedState(long now) {
        int player=connection.playerEntityId(),vehicle=runtime.riding.vehicleOf(player);
        boolean asleep=runtime.riding.sleeping();
        if(now>=nextRideHud){
            GeckoNativeBridge.updateRiding(asleep?1:vehicle>=0?2:0,asleep?"Dormindo":vehicle>=0?"Agache para desmontar":"",inventoryOpen);
            nextRideHud=now+150_000_000L;
        }
        if(asleep){
            if(!wasSleeping){wasSleeping=true;releaseVanillaItem();stopVanillaAttack();primaryHeld=secondaryHeld=false;
                if(inventoryOpen){inventoryOpen=false;GeckoNativeBridge.hideInventory();sendInventoryRequest(InventoryProtocol.closeRequest());}}
            if(wakeRequested){wakeRequested=false;try{connection.ridingCommand(2,0);}catch(IOException|ProtocolException e){throw new IllegalStateException("Wake request",e);}}
            if(runtime.riding.bedPresent()){
                long bed=runtime.riding.bed();int x=com.newhorizon.thinclient.world.BedGeometry.x(bed),y=com.newhorizon.thinclient.world.BedGeometry.y(bed),z=com.newhorizon.thinclient.world.BedGeometry.z(bed);
                cameraPosition[0]=x+.5;cameraPosition[1]=y+.6875;cameraPosition[2]=z+.5;
                cameraRotation[0]=com.newhorizon.thinclient.world.BedGeometry.yaw(runtime.world.blockStateAt(x,y,z));cameraRotation[1]=0;
            }
            verticalVelocity=horizontalVelocityX=horizontalVelocityZ=0;vanillaPhysicsAccumulator=0;snapRenderCameraToPhysics();
            mouseEventRead=mouseEventWrite;cursorInitialized=false;
            if(now-lastMovementNanos>=1_000_000_000L)try{connection.sendMovement(cameraPosition[0],cameraPosition[1],cameraPosition[2],cameraRotation[0],cameraRotation[1],true);lastMovementNanos=now;}catch(IOException|ProtocolException e){throw new IllegalStateException("Sleeping position",e);}
            return true;
        }
        if(wasSleeping){wasSleeping=false;wakeRequested=false;runtime.world.copyPlayerView(cameraPosition,cameraRotation);cursorInitialized=false;snapRenderCameraToPhysics();}
        if(vehicle<0){
            if(mountedId>=0){runtime.entities.localVehicle(-1,0,0,0,0,0);mountedId=-1;wasControlling=false;dismountRequested=false;mountJumpPressed=false;
                runtime.world.copyPlayerView(cameraPosition,cameraRotation);verticalVelocity=horizontalVelocityX=horizontalVelocityZ=0;lastActiveMovementNanos=0;vanillaPhysicsAccumulator=0;snapRenderCameraToPhysics();}
            return false;
        }
        if(!inventoryOpen&&!chatOpen&&!minePads.isOpen())updateCameraRotation();
        if(!runtime.entities.vehicle(vehicle,now,ridingVehicle))return true;
        boolean first=runtime.riding.seatOf(player)==0;
        boolean boat=com.newhorizon.thinclient.world.RidingState.boat(ridingVehicle.type);
        boolean horse=com.newhorizon.thinclient.world.RidingState.horse(ridingVehicle.type);
        boolean controlling=first&&runtime.riding.rootOf(player)==vehicle&&(boat||(horse&&(ridingVehicle.horseFlags&6)==6));
        if(mountedId!=vehicle||wasControlling!=controlling){
            mountedId=vehicle;wasControlling=controlling;rideTickNanos=now;mountJumpPressed=false;jumpChargeTicks=0;dismountRequested=false;
            vehicleMotion.reset(ridingVehicle.x,ridingVehicle.y,ridingVehicle.z,ridingVehicle.yaw,ridingVehicle.pitch);captureRidePrevious();
            verticalVelocity=horizontalVelocityX=horizontalVelocityZ=0;vanillaPhysicsAccumulator=0;sprinting=sneaking=false;swimming.swimming=swimming.prone=false;sendVanillaMovementState();
        }
        if(runtime.riding.drainCorrection(runtime.riding.rootOf(player),rideCorrection)){vehicleMotion.reset(rideCorrection[0],rideCorrection[1],rideCorrection[2],(float)rideCorrection[3],(float)rideCorrection[4]);captureRidePrevious();}
        float forward=inventoryOpen||chatOpen||minePads.isOpen()?0:(float)(axis(GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_UP)-axis(GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_DOWN));
        float sideways=inventoryOpen||chatOpen||minePads.isOpen()?0:(float)(axis(GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_LEFT)-axis(GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_RIGHT));
        boolean jump=!inventoryOpen&&!chatOpen&&!minePads.isOpen()&&pressedKeys[GLFW.GLFW_KEY_SPACE];
        boolean exit=dismountRequested||(!inventoryOpen&&!chatOpen&&!minePads.isOpen()&&(pressedKeys[GLFW.GLFW_KEY_LEFT_SHIFT]||pressedKeys[GLFW.GLFW_KEY_RIGHT_SHIFT]));
        if(now-rideTickNanos>150_000_000L)rideTickNanos=now-150_000_000L;
        try{
            while(now-rideTickNanos>=50_000_000L){rideTickNanos+=50_000_000L;
                connection.sendRiderInput(sideways,forward,jump,exit,cameraRotation[0],cameraRotation[1]);
                float power=0;
                if(horse&&controlling){
                    if(jump){jumpChargeTicks=mountJumpPressed?jumpChargeTicks+1:0;mountJumpPressed=true;}
                    else if(mountJumpPressed){int strength=(int)(com.newhorizon.thinclient.world.MountMotion.jumpPower(jumpChargeTicks)*100);connection.ridingCommand(5,strength);power=strength>=90?1:.4f+.4f*strength/90;mountJumpPressed=false;jumpChargeTicks=0;}
                }
                if(controlling){
                    captureRidePrevious();
                    if(boat){float before=vehicleMotion.yaw;vehicleMotion.tick(runtime.world,sideways>0,sideways<0,forward>0,forward<0);cameraRotation[0]+=vehicleMotion.yaw-before;connection.sendPaddles(vehicleMotion.leftPaddle,vehicleMotion.rightPaddle);}
                    else com.newhorizon.thinclient.world.MountMotion.tick(vehicleMotion,runtime.world,ridingVehicle,forward,sideways,cameraRotation[0],power);
                    connection.sendVehicleMovement(vehicleMotion.x,vehicleMotion.y,vehicleMotion.z,vehicleMotion.yaw,vehicleMotion.pitch);
                }
            }
        }catch(IOException|ProtocolException e){throw new IllegalStateException("Riding update",e);}
        if(controlling){
            double alpha=Math.min(1,Math.max(0,(now-rideTickNanos)/50_000_000.0));
            runtime.entities.localVehicle(vehicle,ridePrevious[0]+(vehicleMotion.x-ridePrevious[0])*alpha,ridePrevious[1]+(vehicleMotion.y-ridePrevious[1])*alpha,
                    ridePrevious[2]+(vehicleMotion.z-ridePrevious[2])*alpha,(float)(ridePrevious[3]+wrapDegrees(vehicleMotion.yaw-(float)ridePrevious[3])*alpha),vehicleMotion.pitch);
        }
        else runtime.entities.localVehicle(-1,0,0,0,0,0);
        if(boat){float base=controlling?vehicleMotion.yaw:ridingVehicle.yaw;cameraRotation[0]=base+Math.max(-105,Math.min(105,wrapDegrees(cameraRotation[0]-base)));}
        if(runtime.entities.position(player,now,rideSeat)){System.arraycopy(rideSeat,0,cameraPosition,0,3);snapRenderCameraToPhysics();}
        lastMovementNanos=lastActiveMovementNanos=now;
        runtime.displays.updatePlayer(runtime.world.dimension(),cameraPosition[0],cameraPosition[1],cameraPosition[2]);
        return true;
    }

    private void captureRidePrevious(){ridePrevious[0]=vehicleMotion.x;ridePrevious[1]=vehicleMotion.y;ridePrevious[2]=vehicleMotion.z;ridePrevious[3]=vehicleMotion.yaw;}

    private void updateGameHud(long now){
        if(now<nextGameHud)return;nextGameHud=now+250_000_000L;
        boolean tab=pressedKeys[GLFW.GLFW_KEY_TAB]&&!inventoryOpen&&!chatOpen&&!minePads.isOpen();
        java.util.UUID id=runtime.players.localId();
        com.newhorizon.thinclient.world.PlayerSkins.Slot local=runtime.playerSkins.request(id,runtime.players.textures(id));
        if(local!=null&&localSkinRevision!=local.revision){localSkin=local.skin;localSkinRevision=local.revision;lastFirstPersonRevision=-1;}
        if(tab)for(com.newhorizon.thinclient.world.PlayerListState.Entry e:runtime.players.listed())runtime.playerSkins.request(e.id,e.textures);
        int uploads=0;for(com.newhorizon.thinclient.world.PlayerSkins.Slot skin:runtime.playerSkins.snapshot()){
            long revision=skin.revision;if(sentSkinRevision[skin.index]!=revision){
                GeckoNativeBridge.playerSkin(skin.id.toString(),skin.skin.encoded(),skin.skin.slim);sentSkinRevision[skin.index]=revision;if(++uploads>=4)break;
            }
        }
        String debug="";
        if(debugVisible){
            debugOverlay.fps=framesPerSecond;debugOverlay.width=framebufferWidth.get(0);debugOverlay.height=framebufferHeight.get(0);
            debugOverlay.entities=entityRenderer.visibleEntities();debugOverlay.particles=entityRenderer.visibleParticles();debugOverlay.sounds=audio==null?0:audio.activeSources();debugOverlay.mood=ambientSounds.mood();
            debugOverlay.meshes=debugOverlay.vertices=debugOverlay.pending=0;
            for(GpuChunk gpu:gpuChunks)if(gpu!=null&&gpu.vertexCount>0){debugOverlay.meshes++;debugOverlay.vertices+=gpu.vertexCount;}
            for(int i=0;i<runtime.world.size();i++){SurfaceChunk chunk=runtime.world.chunkAt(i);boolean ready=false;for(GpuChunk gpu:gpuChunks)if(gpu!=null&&gpu.chunkX==chunk.chunkX&&gpu.chunkZ==chunk.chunkZ&&gpu.revision==chunk.revision){ready=true;break;}if(!ready)debugOverlay.pending++;}
            debugOverlay.targetEntity=selectionHit.type==VanillaHitResult.ENTITY?selectionHit.entityId:-1;
            debug=debugOverlay.encode(runtime,now,cameraPosition[0],cameraPosition[1],cameraPosition[2],eyeHeight(),cameraRotation[0],cameraRotation[1]);
        }
        GeckoNativeBridge.updateHud(runtime.hud.encode(runtime.players,tab,now),debug,tab,hudHidden,inventoryOpen||minePads.isOpen(),viewCamera.mode);
    }

    private void processClientUi() {
        if (diagnosticToggleRequested) {
            diagnosticToggleRequested = false;
            debugVisible=!debugVisible;nextGameHud=0;
        }
        if(runtime.riding.sleeping())inventoryToggleRequested=false;
        if(chatRequested!=null){
            if(!inventoryOpen){
                chatOpen=true;GeckoNativeBridge.showChat(chatRequested);
                java.util.Arrays.fill(pressedKeys,false);flightToggleRequested=false;
                releaseVanillaItem();primaryHeld=secondaryHeld=false;stopVanillaAttack();
                mouseEventRead=mouseEventWrite;callbackCursorReady=false;
            }
            chatRequested=null;
        }
        if(chatOpen||minePads.isOpen())inventoryToggleRequested=false;
        if (inventoryToggleRequested) {
            inventoryToggleRequested = false;
            if (inventoryOpen) {
                inventoryOpen = false;
                GeckoNativeBridge.hideInventory();
                sendInventoryRequest(InventoryProtocol.closeRequest());
                GeckoNativeBridge.updateHotbar(runtime.inventory.encodeForUi());
            } else {
                inventoryOpen = true;
                java.util.Arrays.fill(pressedKeys,false);
                lastInventoryRevision = -1L;
                GeckoNativeBridge.hideHotbar();
                GeckoNativeBridge.showInventory(runtime.inventory.encodeForUi());
                sendInventoryRequest(InventoryProtocol.openRequest());
            }
        }

        for (int index = 0; index < 128; index++) {
            String[] event = GeckoNativeBridge.pollEvent();
            if (event == null) break;
            if (event.length < 3) continue;
            if("nh-display-link".equals(event[1])){
                if(mayInteractWithDisplay())minePads.receiveDisplayUrl(runtime.inventory,event[2],System.nanoTime());
                continue;
            }
            if(event[1].startsWith("nh-pad-")){
                try{
                    int browser=Integer.parseInt(event[0]);
                    if("nh-pad-action".equals(event[1])){String[] args=event[2].split("\n",3);if(args.length==3)minePads.action(Integer.parseInt(args[0]),Integer.parseInt(args[1]),args[2]);}
                    else if("nh-pad-location".equals(event[1]))minePads.location(browser,event[2]);
                    else if(browser==minePads.activeBrowser()){
                        if("nh-pad-popup".equals(event[1]))minePads.action(1,0,event[2]);
                        if("nh-pad-window-close".equals(event[1]))minePads.action(3,-1,"");
                    }
                }catch(IllegalArgumentException ignored){}
                java.util.Arrays.fill(pressedKeys,false);mouseEventRead=mouseEventWrite;callbackCursorReady=false;
                continue;
            }
            if("nh-chat-close".equals(event[1])||"nh-chat-send".equals(event[1])){
                boolean send="nh-chat-send".equals(event[1]);
                chatOpen=false;java.util.Arrays.fill(pressedKeys,false);
                mouseEventRead=mouseEventWrite;callbackCursorReady=false;
                if(send)try{
                    if(!connection.sendChat(event[2]))GeckoNativeBridge.chatMessage("{\"text\":\"Mensagem n\u00e3o enviada: conex\u00e3o indispon\u00edvel.\",\"color\":\"red\"}");
                }catch(IOException|ProtocolException|IllegalArgumentException error){
                    GeckoNativeBridge.chatMessage("{\"text\":\"N\u00e3o foi poss\u00edvel enviar a mensagem.\",\"color\":\"red\"}");
                }
                continue;
            }
            if("nh-debug-chart".equals(event[1])){debugOverlay.chart=!debugOverlay.chart;debugVisible=true;nextGameHud=0;continue;}
            if("nh-debug-pie".equals(event[1])){debugOverlay.pie=!debugOverlay.pie;debugVisible=true;nextGameHud=0;continue;}
            if("nh-riding-exit".equals(event[1])) {if(runtime.riding.sleeping())wakeRequested=true;else dismountRequested=true;continue;}
            if("nh-combat-respawn".equals(event[1])) {
                try {connection.requestRespawn();}
                catch(IOException | ProtocolException error) {throw new IllegalStateException("Unable to request respawn",error);}
                continue;
            }
            if ("nh-inventory-click".equals(event[1])) {
                try {
                    String[] click = event[2].split(",", 3);
                    int slot = Integer.parseInt(click[0]);
                    int button = click.length > 1
                            ? Integer.parseInt(click[1]) : InventoryProtocol.BUTTON_LEFT;
                    boolean shift = click.length > 2 && "1".equals(click[2]);
                    if (inventoryOpen) {
                        sendInventoryRequest(InventoryProtocol.clickRequest(slot, button, shift));
                    }
                } catch (IllegalArgumentException ignored) {
                    System.out.println("[NH-THIN] ignored invalid inventory click");
                }
            } else if ("nh-creative-set-slot".equals(event[1])) {
                String[] fields = event[2].split(",", 5);
                if (fields.length == 5) {
                    try {
                        int menuSlot = Integer.parseInt(fields[0]);
                        int protocolItemId = Integer.parseInt(fields[1]);
                        int amount = Integer.parseInt(fields[2]);
                        String material = fields[3];
                        String nbtHex = fields[4];
                        if (connection.setCreativeSlot(menuSlot, protocolItemId,
                                amount, nbtHex) && menuSlot >= 0) {
                            runtime.inventory.setCreativeSlot(menuSlot, material, amount,protocolItemId,nbtHex);
                        }
                    } catch (IOException | ProtocolException | IllegalArgumentException exception) {
                        System.err.println("[NH-THIN] creative slot rejected: "
                                + exception.getMessage());
                    }
                }
            } else if ("nh-creative-cursor".equals(event[1])) {
                try {
                    int action = Integer.parseInt(event[2]);
                    if (inventoryOpen) {
                        sendInventoryRequest(InventoryProtocol.creativeCursorRequest(action));
                    }
                } catch (IllegalArgumentException ignored) {
                    System.out.println("[NH-THIN] ignored invalid creative cursor action");
                }
            } else if ("nh-inventory-close".equals(event[1])) {
                if (inventoryOpen) {
                    inventoryOpen = false;
                    sendInventoryRequest(InventoryProtocol.closeRequest());
                    GeckoNativeBridge.updateHotbar(runtime.inventory.encodeForUi());
                }
            }
        }

        long revision = runtime.inventory.revision();
        String encoded = null;
        if (!inventoryOpen && revision != lastHotbarRevision) {
            lastHotbarRevision = revision;
            encoded = runtime.inventory.encodeForUi();
            GeckoNativeBridge.updateHotbar(encoded);
        }
        if (inventoryOpen && revision != lastInventoryRevision) {
            lastInventoryRevision = revision;
            if (encoded == null) encoded = runtime.inventory.encodeForUi();
            GeckoNativeBridge.showInventory(encoded);
        }
    }

    private void sendInventoryRequest(ByteBuffer request) {
        try {
            connection.sendCustomPayload(InventoryProtocol.CHANNEL, request);
        } catch (IOException | ProtocolException | IllegalStateException exception) {
            System.out.println("[NH-THIN] inventory request failed: "
                    + exception.getMessage());
        }
    }

    private void updateMovementState(double forward) {
        boolean water=(playerPhysicsFlags()&BlockStatePhysics.WATER)!=0;
        if(forward>0 && (pressedKeys[GLFW.GLFW_KEY_LEFT_CONTROL] || pressedKeys[GLFW.GLFW_KEY_RIGHT_CONTROL]))sprinting=true;
        if(runtime.combat.usingItem() || (!connection.canFly() && !runtime.combat.canSprint()))sprinting=false;
        boolean wantsSneak = pressedKeys[GLFW.GLFW_KEY_LEFT_SHIFT]
                || pressedKeys[GLFW.GLFW_KEY_RIGHT_SHIFT];
        boolean standingFits=!collidesAtHeight(cameraPosition[0],cameraPosition[1],cameraPosition[2],PLAYER_HEIGHT);
        boolean crouchingFits=!collidesAtHeight(cameraPosition[0],cameraPosition[1],cameraPosition[2],SNEAK_HEIGHT);
        if (forward <= 0.0 || wantsSneak&&!water) sprinting = false;
        boolean wasSwimming=swimming.swimming,wasProne=swimming.prone;
        swimming.update(sprinting,water,waterAtHeight(eyeHeight()-.11111111),waterAtHeight(.01),
                connection.isFlying()||mountedId>=0||runtime.combat.dead(),standingFits,crouchingFits);
        sneaking=!swimming.prone&&!water&&!connection.isFlying()&&(wantsSneak||!standingFits&&crouchingFits);
        if(sneaking)sprinting=false;
        if(wasSwimming!=swimming.swimming || wasProne!=swimming.prone)
            System.out.println("[NH-THIN] vanilla swimming="+swimming.swimming+" prone="+swimming.prone+" eye="+eyeHeight());
        sendVanillaMovementState();
    }

    private double playerHeight(){return swimming.prone?.6:sneaking?SNEAK_HEIGHT:PLAYER_HEIGHT;}

    private boolean waterAtHeight(double height){
        double x=cameraPosition[0],y=cameraPosition[1]+height,z=cameraPosition[2];
        return runtime.world.fluidDepthInBox(BlockStatePhysics.WATER,x-.001,y,z-.001,x+.001,y+.001,z+.001)>0;
    }

    private void sendVanillaMovementState() {
        try {
            boolean shift=pressedKeys[GLFW.GLFW_KEY_LEFT_SHIFT]||pressedKeys[GLFW.GLFW_KEY_RIGHT_SHIFT];
            if (shift != sentSneaking) {
                int command = shift ? COMMAND_PRESS_SHIFT : COMMAND_RELEASE_SHIFT;
                if (connection.sendPlayerCommand(command)) {
                    sentSneaking = shift;
                    System.out.println("[NH-THIN] vanilla sneak=" + shift);
                }
            }
            if (sprinting != sentSprinting) {
                int command = sprinting ? COMMAND_START_SPRINT : COMMAND_STOP_SPRINT;
                if (connection.sendPlayerCommand(command)) {
                    sentSprinting = sprinting;
                    System.out.println("[NH-THIN] vanilla sprint=" + sprinting);
                }
            }
        } catch (IOException | ProtocolException exception) {
            throw new IllegalStateException("Unable to send vanilla player state", exception);
        }
    }

    private void resolvePlayerOverlap() {
        if (!runtime.world.hasCollisionData()
                || !collidesAt(cameraPosition[0], cameraPosition[1], cameraPosition[2])) {
            return;
        }
        double originalY = cameraPosition[1];
        for (int step = 1; step <= 60; step++) {
            double candidateY = originalY + step * 0.05;
            if (!collidesAt(cameraPosition[0], candidateY, cameraPosition[2])
                    && collidesAt(cameraPosition[0], candidateY - 0.05,
                    cameraPosition[2])) {
                cameraPosition[1] = candidateY;
                verticalVelocity = 0.0;
                onGround = true;
                System.out.println("[NH-THIN] resolved collision overlap y="
                        + originalY + " -> " + candidateY);
                return;
            }
        }
    }

    private int playerPhysicsFlags() {
        double height = playerHeight();
        return runtime.world.physicsFlagsInBox(
                cameraPosition[0] - PLAYER_RADIUS,
                cameraPosition[1] + COLLISION_EPSILON,
                cameraPosition[2] - PLAYER_RADIUS,
                cameraPosition[0] + PLAYER_RADIUS,
                cameraPosition[1] + height - COLLISION_EPSILON,
                cameraPosition[2] + PLAYER_RADIUS);
    }

    private int supportPhysicsFlags() {
        return runtime.world.physicsFlagsAt(
                (int) Math.floor(cameraPosition[0]),
                (int) Math.floor(cameraPosition[1] - 0.05),
                (int) Math.floor(cameraPosition[2]));
    }

    /** One LivingEntity#travel tick using vanilla 1.20.1 units and ordering. */
    private void updateVanillaPhysicsTick(double forward, double strafe,
                                          boolean hasInput, int physicsFlags) {
        boolean jumpPressed = pressedKeys[GLFW.GLFW_KEY_SPACE];
        boolean descend = pressedKeys[GLFW.GLFW_KEY_LEFT_SHIFT]
                || pressedKeys[GLFW.GLFW_KEY_RIGHT_SHIFT];
        boolean inWater = (physicsFlags & BlockStatePhysics.WATER) != 0;
        boolean inLava = !inWater && (physicsFlags & BlockStatePhysics.LAVA) != 0;
        boolean inWeb = (physicsFlags & BlockStatePhysics.COBWEB) != 0;
        boolean inBerry = (physicsFlags & BlockStatePhysics.SWEET_BERRY) != 0;

        // Entity.makeStuckInBlock is consumed immediately before move().
        if (inWeb) {
            horizontalVelocityX *= 0.25;
            verticalVelocity *= 0.05;
            horizontalVelocityZ *= 0.25;
        } else if (inBerry) {
            horizontalVelocityX *= 0.80;
            verticalVelocity *= 0.75;
            horizontalVelocityZ *= 0.80;
        }

        onGround = collidesAt(cameraPosition[0], cameraPosition[1] - 0.001,
                cameraPosition[2]);
        if (jumpPressed && !jumpWasPressed) {
            if (!inWater && !inLava && onGround) {
                int supportY = (int) Math.floor(cameraPosition[1] - 0.5000001);
                float jumpFactor = runtime.world.jumpFactorAt(
                        (int) Math.floor(cameraPosition[0]), supportY,
                        (int) Math.floor(cameraPosition[2]));
                verticalVelocity = (0.42 * jumpFactor + runtime.effects.jumpBoost()) * 20.0;
                if (sprinting) {
                    double yaw = Math.toRadians(cameraRotation[0]);
                    horizontalVelocityX -= Math.sin(yaw) * 0.2 * 20.0;
                    horizontalVelocityZ += Math.cos(yaw) * 0.2 * 20.0;
                }
                onGround = false;
            }
        }
        if ((inWater || inLava) && jumpPressed) verticalVelocity += 0.04 * 20.0;
        if ((inWater || inLava) && descend) verticalVelocity -= 0.04 * 20.0;
        if(swimming.swimming)verticalVelocity=com.newhorizon.thinclient.world.SwimmingState.steer(
                verticalVelocity,cameraRotation[1],jumpPressed,waterAtHeight(.9));
        jumpWasPressed = jumpPressed;

        double inputForward = forward;
        double inputStrafe = strafe;
        if (sneaking) {
            inputForward *= 0.30;
            inputStrafe *= 0.30;
        }
        if(chargingItem) { inputForward*=.2; inputStrafe*=.2; }
        double inputLength = Math.sqrt(inputForward * inputForward
                + inputStrafe * inputStrafe);
        if (inputLength > 1.0) {
            inputForward /= inputLength;
            inputStrafe /= inputLength;
        }

        if (inWater || inLava) {
            addVanillaRelativeMotion(inputForward, inputStrafe, 0.02f);
        } else {
            int supportY = (int) Math.floor(cameraPosition[1] - 0.5000001);
            float friction = runtime.world.frictionAt(
                    (int) Math.floor(cameraPosition[0]), supportY,
                    (int) Math.floor(cameraPosition[2]));
            float movementSpeed = (float)runtime.effects.movementSpeed()*(sprinting?1.3f:1f);
            float relativeSpeed = onGround
                    ? movementSpeed * (0.21600002f / (friction * friction * friction))
                    : sprinting ? 0.026f : 0.02f;
            addVanillaRelativeMotion(inputForward, inputStrafe, relativeSpeed);
        }

        boolean climbing = (physicsFlags & (BlockStatePhysics.CLIMBABLE
                | BlockStatePhysics.POWDER_SNOW)) != 0;
        if (climbing) {
            horizontalVelocityX = clamp(horizontalVelocityX, -3.0, 3.0);
            horizontalVelocityZ = clamp(horizontalVelocityZ, -3.0, 3.0);
            verticalVelocity = Math.max(verticalVelocity, -3.0);
            if (sneaking && verticalVelocity < 0.0) verticalVelocity = 0.0;
        }

        moveVertical(verticalVelocity / 20.0);
        moveHorizontal(horizontalVelocityX / 20.0, horizontalVelocityZ / 20.0);

        if (climbing && hasInput && horizontalCollision) {
            verticalVelocity = 0.2 * 20.0;
        }

        if (inWater) {
            float drag = runtime.effects.level(30)>0?.96f:sprinting ? 0.90f : 0.80f;
            horizontalVelocityX *= drag;
            verticalVelocity *= 0.80;
            horizontalVelocityZ *= drag;
            if(!sprinting)verticalVelocity = vanillaFluidGravity(verticalVelocity, runtime.effects.gravity(verticalVelocity));
        } else if (inLava) {
            double height = playerHeight();
            double depth = runtime.world.fluidDepthInBox(BlockStatePhysics.LAVA,
                    cameraPosition[0] - PLAYER_RADIUS,
                    cameraPosition[1], cameraPosition[2] - PLAYER_RADIUS,
                    cameraPosition[0] + PLAYER_RADIUS,
                    cameraPosition[1] + height,
                    cameraPosition[2] + PLAYER_RADIUS);
            horizontalVelocityX *= 0.50;
            verticalVelocity *= depth <= 0.4 ? 0.80 : 0.50;
            horizontalVelocityZ *= 0.50;
            if (depth <= 0.4) verticalVelocity = vanillaFluidGravity(
                    verticalVelocity, runtime.effects.gravity(verticalVelocity));
            verticalVelocity -= runtime.effects.gravity(verticalVelocity)/4 * 20.0;
        } else {
            int supportY = (int) Math.floor(cameraPosition[1] - 0.5000001);
            float friction = runtime.world.frictionAt(
                    (int) Math.floor(cameraPosition[0]), supportY,
                    (int) Math.floor(cameraPosition[2]));
            float drag = onGround ? friction * 0.91f : 0.91f;
            if (!onGround || runtime.effects.level(25)>0)
                verticalVelocity=runtime.effects.airborneVelocity(verticalVelocity);
            else verticalVelocity*=.98;
            horizontalVelocityX *= drag;
            horizontalVelocityZ *= drag;
        }

        int feetY = (int) Math.floor(cameraPosition[1] - 0.2000001);
        float speedFactor = runtime.world.speedFactorAt(
                (int) Math.floor(cameraPosition[0]), feetY,
                (int) Math.floor(cameraPosition[2]));
        horizontalVelocityX *= speedFactor;
        horizontalVelocityZ *= speedFactor;
        if (!hasInput) {
            if (Math.abs(horizontalVelocityX) < MOTION_EPSILON) horizontalVelocityX = 0.0;
            if (Math.abs(horizontalVelocityZ) < MOTION_EPSILON) horizontalVelocityZ = 0.0;
        }
    }

    private void addVanillaRelativeMotion(double forward, double strafe, float speed) {
        if (forward * forward + strafe * strafe < 1.0e-7) return;
        double radians = Math.toRadians(cameraRotation[0]);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        horizontalVelocityX += (-sin * forward - cos * strafe) * speed * 20.0;
        horizontalVelocityZ += (cos * forward - sin * strafe) * speed * 20.0;
    }

    private static double vanillaFluidGravity(double velocity, double gravity) {
        double perTick = velocity / 20.0;
        if (velocity <= 0.0 && Math.abs(perTick - 0.005) >= 0.003
                && Math.abs(perTick - gravity / 16.0) < 0.003) {
            return -0.003 * 20.0;
        }
        return velocity - gravity / 16.0 * 20.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void updateHorizontalPhysics(double forward, double strafe,
                                         boolean hasInput, double seconds,
                                         int physicsFlags) {
        boolean inWater = (physicsFlags & BlockStatePhysics.WATER) != 0;
        boolean inLava = (physicsFlags & BlockStatePhysics.LAVA) != 0;
        boolean inWeb = (physicsFlags & BlockStatePhysics.COBWEB) != 0;
        double desiredX = 0.0;
        double desiredZ = 0.0;
        if (hasInput) {
            double length = Math.sqrt(forward * forward + strafe * strafe);
            forward /= length;
            strafe /= length;
            double radians = Math.toRadians(cameraRotation[0]);
            double sin = Math.sin(radians);
            double cos = Math.cos(radians);
            double speed;
            if (connection.isFlying()) {
                double baseFlightSpeed = Math.max(WALK_SPEED,
                        connection.flyingSpeed() * VANILLA_FLIGHT_SPEED_SCALE);
                speed = baseFlightSpeed * (sprinting ? 2.0 : 1.0);
            } else if (inWater) {
                speed = sprinting ? WATER_SPRINT_SPEED : WATER_MOVE_SPEED;
            } else if (inLava) {
                speed = LAVA_MOVE_SPEED;
            } else {
                speed = sneaking ? SNEAK_SPEED
                        : sprinting ? SPRINT_SPEED : WALK_SPEED;
                int support = supportPhysicsFlags();
                if ((support & (BlockStatePhysics.SOUL_SAND
                        | BlockStatePhysics.HONEY)) != 0) speed *= 0.40;
            }
            if ((physicsFlags & BlockStatePhysics.POWDER_SNOW) != 0) {
                speed *= 0.45;
            } else if ((physicsFlags & BlockStatePhysics.SWEET_BERRY) != 0) {
                speed *= 0.80;
            }
            desiredX = (-sin * forward - cos * strafe) * speed;
            desiredZ = (cos * forward - sin * strafe) * speed;
        }

        if (connection.isFlying()) {
            double response = Math.pow(FLIGHT_DRAG_PER_TICK, seconds * 20.0);
            horizontalVelocityX = desiredX
                    + (horizontalVelocityX - desiredX) * response;
            horizontalVelocityZ = desiredZ
                    + (horizontalVelocityZ - desiredZ) * response;
        } else if (inWater || inLava) {
            double mediumDrag = inWater ? WATER_DRAG_PER_TICK : LAVA_DRAG_PER_TICK;
            double response = Math.pow(mediumDrag, seconds * 20.0);
            if (hasInput) {
                horizontalVelocityX = desiredX
                        + (horizontalVelocityX - desiredX) * response;
                horizontalVelocityZ = desiredZ
                        + (horizontalVelocityZ - desiredZ) * response;
            } else {
                horizontalVelocityX *= response;
                horizontalVelocityZ *= response;
            }
        } else if (hasInput) {
            double acceleration = onGround ? GROUND_ACCELERATION : AIR_ACCELERATION;
            double deltaX = desiredX - horizontalVelocityX;
            double deltaZ = desiredZ - horizontalVelocityZ;
            double deltaLength = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            double maximumDelta = acceleration * seconds;
            if (deltaLength > maximumDelta && deltaLength > 0.0) {
                double scale = maximumDelta / deltaLength;
                deltaX *= scale;
                deltaZ *= scale;
            }
            horizontalVelocityX += deltaX;
            horizontalVelocityZ += deltaZ;
        }

        if (!connection.isFlying() && !inWater && !inLava && !hasInput) {
            double perTick = onGround ? GROUND_DRAG_PER_TICK : AIR_DRAG_PER_TICK;
            double drag = Math.pow(perTick, seconds * 20.0);
            horizontalVelocityX *= drag;
            horizontalVelocityZ *= drag;
        }
        if (!connection.isFlying() && inWeb) {
            double webDrag = Math.pow(0.25, seconds * 20.0);
            horizontalVelocityX *= webDrag;
            horizontalVelocityZ *= webDrag;
        }
        if (!hasInput) {
            if (Math.abs(horizontalVelocityX) < MOTION_EPSILON) {
                horizontalVelocityX = 0.0;
            }
            if (Math.abs(horizontalVelocityZ) < MOTION_EPSILON) {
                horizontalVelocityZ = 0.0;
            }
        }
        moveHorizontal(horizontalVelocityX * seconds,
                horizontalVelocityZ * seconds);
    }

    private void moveHorizontal(double dx, double dz) {
        if (!runtime.world.hasCollisionData()) {
            cameraPosition[0] += dx;
            cameraPosition[2] += dz;
            return;
        }
        double baseX = cameraPosition[0];
        double baseY = cameraPosition[1];
        double baseZ = cameraPosition[2];
        double clippedX = dx;
        double clippedZ = dz;
        if (Math.abs(dx) < Math.abs(dz)) {
            clippedZ = clipAxis(2, dz);
            cameraPosition[2] += clippedZ;
            clippedX = clipAxis(0, dx);
            cameraPosition[0] += clippedX;
        } else {
            clippedX = clipAxis(0, dx);
            cameraPosition[0] += clippedX;
            clippedZ = clipAxis(2, dz);
            cameraPosition[2] += clippedZ;
        }
        boolean blockedX = Math.abs(clippedX - dx) > 1.0e-7;
        boolean blockedZ = Math.abs(clippedZ - dz) > 1.0e-7;

        // Entity#move compares the ordinary collision result with a 0.6-block
        // step candidate. This is what lets the player walk onto slabs, paths
        // and the lower portions of stair VoxelShapes without jumping.
        if ((blockedX || blockedZ) && onGround && !sneaking) {
            double normalX = cameraPosition[0];
            double normalZ = cameraPosition[2];
            double normalDistance = (normalX - baseX) * (normalX - baseX)
                    + (normalZ - baseZ) * (normalZ - baseZ);
            cameraPosition[0] = baseX;
            cameraPosition[1] = baseY;
            cameraPosition[2] = baseZ;
            double stepUp = clipAxis(1, 0.6);
            cameraPosition[1] += stepUp;
            double stepX = clipAxis(0, dx);
            cameraPosition[0] += stepX;
            double stepZ = clipAxis(2, dz);
            cameraPosition[2] += stepZ;
            double stepDown = clipAxis(1, -stepUp);
            cameraPosition[1] += stepDown;
            double stepDistance = stepX * stepX + stepZ * stepZ;
            if (stepUp <= 1.0e-7 || stepDistance <= normalDistance) {
                cameraPosition[0] = normalX;
                cameraPosition[1] = baseY;
                cameraPosition[2] = normalZ;
            } else {
                clippedX = stepX;
                clippedZ = stepZ;
                blockedX = Math.abs(clippedX - dx) > 1.0e-7;
                blockedZ = Math.abs(clippedZ - dz) > 1.0e-7;
            }
        }
        horizontalCollision = blockedX || blockedZ;
        // Vanilla resolves vertical motion before the horizontal axes. While the
        // player is rising, keep the horizontal impulse so it can resume as soon
        // as the feet clear a one-block obstacle.
        boolean preserveJumpImpulse = !connection.isFlying()
                && !onGround && verticalVelocity > 0.0;
        if (blockedX && !preserveJumpImpulse) horizontalVelocityX = 0.0;
        if (blockedZ && !preserveJumpImpulse) horizontalVelocityZ = 0.0;
    }

    private boolean hasSneakSupport(double x, double z) {
        return !sneaking || (!onGround && Math.abs(verticalVelocity) > 0.001)
                || collidesAtHeight(x, cameraPosition[1] - 0.05, z, SNEAK_HEIGHT);
    }

    private void updateVerticalPhysics(double seconds, int physicsFlags) {
        boolean jumpPressed = pressedKeys[GLFW.GLFW_KEY_SPACE];
        if (flightToggleRequested) {
            flightToggleRequested = false;
            try {
                if (connection.canFly()
                        && connection.setFlying(!connection.isFlying())) {
                    verticalVelocity = 0.0;
                    System.out.println("[NH-THIN] vanilla flying="
                            + connection.isFlying());
                } else {
                    System.out.println("[NH-THIN] flight ignored: server mayFly=false");
                }
            } catch (IOException | ProtocolException exception) {
                throw new IllegalStateException("Unable to toggle vanilla flight", exception);
            }
        }
        if (connection.isFlying()) {
            double verticalInput = (jumpPressed ? 1.0 : 0.0)
                    - ((pressedKeys[GLFW.GLFW_KEY_LEFT_SHIFT]
                    || pressedKeys[GLFW.GLFW_KEY_RIGHT_SHIFT]) ? 1.0 : 0.0);
            double flightSpeed = Math.max(4.0,
                    connection.flyingSpeed() * VANILLA_VERTICAL_FLIGHT_SPEED_SCALE);
            double desiredVelocity = verticalInput * flightSpeed;
            double response = Math.pow(VERTICAL_FLIGHT_DRAG_PER_TICK,
                    seconds * 20.0);
            flightVerticalVelocity = desiredVelocity
                    + (flightVerticalVelocity - desiredVelocity) * response;
            if (verticalInput == 0.0
                    && Math.abs(flightVerticalVelocity) < MOTION_EPSILON) {
                flightVerticalVelocity = 0.0;
            }
            moveVertical(flightVerticalVelocity * seconds);
            verticalVelocity = 0.0;
            wasFlying = true;
            jumpWasPressed = jumpPressed;
            return;
        }
        if (wasFlying) {
            verticalVelocity = flightVerticalVelocity;
            flightVerticalVelocity = 0.0;
            wasFlying = false;
        }
        if (!runtime.world.hasCollisionData()) {
            onGround = true;
            verticalVelocity = 0.0;
            jumpWasPressed = jumpPressed;
            return;
        }

        boolean inWater = (physicsFlags & BlockStatePhysics.WATER) != 0;
        boolean inLava = (physicsFlags & BlockStatePhysics.LAVA) != 0;
        if (inWater || inLava) {
            boolean descend = pressedKeys[GLFW.GLFW_KEY_LEFT_SHIFT]
                    || pressedKeys[GLFW.GLFW_KEY_RIGHT_SHIFT];
            double input = (jumpPressed ? 1.0 : 0.0) - (descend ? 1.0 : 0.0);
            double perTick = inWater ? WATER_DRAG_PER_TICK : LAVA_DRAG_PER_TICK;
            double drag = Math.pow(perTick, seconds * 20.0);
            if (input != 0.0) {
                double target = input * (inWater
                        ? WATER_ASCEND_SPEED : LAVA_ASCEND_SPEED);
                verticalVelocity = target + (verticalVelocity - target) * drag;
            } else {
                verticalVelocity = (verticalVelocity - FLUID_GRAVITY * seconds) * drag;
            }
            if ((physicsFlags & BlockStatePhysics.BUBBLE_UP) != 0) {
                verticalVelocity = Math.min(8.0,
                        verticalVelocity + 12.0 * seconds);
            } else if ((physicsFlags & BlockStatePhysics.BUBBLE_DOWN) != 0) {
                verticalVelocity = Math.max(-8.0,
                        verticalVelocity - 12.0 * seconds);
            }
            moveVertical(verticalVelocity * seconds);
            jumpWasPressed = jumpPressed;
            return;
        }

        boolean climbing = (physicsFlags & (BlockStatePhysics.CLIMBABLE
                | BlockStatePhysics.POWDER_SNOW)) != 0;
        if (climbing) {
            boolean horizontalInput = pressedKeys[GLFW.GLFW_KEY_W]
                    || pressedKeys[GLFW.GLFW_KEY_S]
                    || pressedKeys[GLFW.GLFW_KEY_A]
                    || pressedKeys[GLFW.GLFW_KEY_D]
                    || pressedKeys[GLFW.GLFW_KEY_UP]
                    || pressedKeys[GLFW.GLFW_KEY_DOWN]
                    || pressedKeys[GLFW.GLFW_KEY_LEFT]
                    || pressedKeys[GLFW.GLFW_KEY_RIGHT];
            if (jumpPressed) {
                verticalVelocity = Math.max(verticalVelocity, 4.0);
            } else if (sneaking) {
                verticalVelocity = Math.max(0.0, verticalVelocity);
            } else if (horizontalInput) {
                verticalVelocity = Math.max(verticalVelocity, 2.35);
            } else {
                verticalVelocity = Math.max(verticalVelocity, -3.0);
            }
            moveVertical(verticalVelocity * seconds);
            verticalVelocity *= Math.pow(0.91, seconds * 20.0);
            jumpWasPressed = jumpPressed;
            return;
        }

        onGround = collidesAt(cameraPosition[0], cameraPosition[1] - 0.05,
                cameraPosition[2]);
        if (jumpPressed && !jumpWasPressed && onGround) {
            verticalVelocity = JUMP_VELOCITY+runtime.effects.jumpBoost()*20;
            onGround = false;
        }
        jumpWasPressed = jumpPressed;

        moveVertical(verticalVelocity * seconds);
        if (onGround) {
            verticalVelocity = 0.0;
        } else {
            verticalVelocity = Math.max(TERMINAL_VELOCITY,
                    (verticalVelocity - GRAVITY * seconds)
                            * Math.pow(VERTICAL_DRAG_PER_TICK, seconds * 20.0));
            if ((physicsFlags & BlockStatePhysics.COBWEB) != 0) {
                verticalVelocity *= Math.pow(0.05, seconds * 20.0);
            } else if ((physicsFlags & BlockStatePhysics.SWEET_BERRY) != 0) {
                verticalVelocity *= Math.pow(0.75, seconds * 20.0);
            }
            onGround = collidesAt(cameraPosition[0], cameraPosition[1] - 0.05,
                    cameraPosition[2]);
            if (onGround && verticalVelocity < 0.0) verticalVelocity = 0.0;
        }
    }

    private void moveVertical(double dy) {
        if (Math.abs(dy) < 1.0e-7) return;
        if (!runtime.world.hasCollisionData()) {
            cameraPosition[1] += dy;
            return;
        }
        double clipped = clipAxis(1, dy);
        cameraPosition[1] += clipped;
        if (Math.abs(clipped - dy) > 1.0e-7) {
            if (dy < 0.0) onGround = true;
            verticalVelocity = 0.0;
            flightVerticalVelocity = 0.0;
        } else {
            onGround = false;
        }
    }

    private boolean collidesAt(double x, double y, double z) {
        return collidesAtHeight(x, y, z, playerHeight());
    }

    private boolean collidesAtHeight(double x, double y, double z, double height) {
        return runtime.world.collidesBox(
                x - PLAYER_RADIUS + COLLISION_EPSILON,
                y + COLLISION_EPSILON,
                z - PLAYER_RADIUS + COLLISION_EPSILON,
                x + PLAYER_RADIUS - COLLISION_EPSILON,
                y + height - COLLISION_EPSILON,
                z + PLAYER_RADIUS - COLLISION_EPSILON);
    }

    private double clipAxis(int axis, double movement) {
        double height = playerHeight();
        return runtime.world.clipMovement(axis,
                cameraPosition[0] - PLAYER_RADIUS,
                cameraPosition[1],
                cameraPosition[2] - PLAYER_RADIUS,
                cameraPosition[0] + PLAYER_RADIUS,
                cameraPosition[1] + height,
                cameraPosition[2] + PLAYER_RADIUS,
                movement);
    }

    private void updateCameraRotation() {
        double currentX;
        double currentY;
        if (callbackCursorReady) {
            currentX = callbackCursorX;
            currentY = callbackCursorY;
        } else {
            cursorX.clear();
            cursorY.clear();
            GLFW.glfwGetCursorPos(window, cursorX, cursorY);
            currentX = cursorX.get(0);
            currentY = cursorY.get(0);
        }
        if (!cursorInitialized) {
            cursorInitialized = true;
            lastCursorX = currentX;
            lastCursorY = currentY;
            return;
        }
        double deltaX = currentX - lastCursorX;
        double deltaY = currentY - lastCursorY;
        lastCursorX = currentX;
        lastCursorY = currentY;
        if (Math.abs(deltaX) > 4096.0 || Math.abs(deltaY) > 4096.0) return;
        cameraRotation[0] = wrapDegrees(cameraRotation[0]
                + (float) deltaX * MOUSE_SENSITIVITY);
        cameraRotation[1] = Math.max(-89.9f, Math.min(89.9f,
                cameraRotation[1] + (float) deltaY * MOUSE_SENSITIVITY));
    }

    private void reconcileServerPosition(boolean moving, long now) {
        long teleportRevision = runtime.world.teleportRevision();
        if (teleportRevision != lastTeleportRevision) {
            lastTeleportRevision = teleportRevision;
            lastWorldRevision = runtime.world.playerRevision();
            runtime.world.copyPlayerView(cameraPosition, cameraRotation);
            verticalVelocity = 0.0;
            horizontalVelocityX = 0.0;
            horizontalVelocityZ = 0.0;
            flightVerticalVelocity = 0.0;
            sprinting = false;
            vanillaPhysicsAccumulator = 0.0;
            snapRenderCameraToPhysics();
            return;
        }
        long revision = runtime.world.playerRevision();
        if (revision == lastWorldRevision) return;
        lastWorldRevision = revision;
        runtime.world.copyPlayerView(serverPosition, serverRotation);
        // Bridge snapshots are delayed acknowledgements, not teleports. Applying
        // one while input is active caused visible rubber-banding on slow ticks.
        if (moving || now - lastActiveMovementNanos < SERVER_SETTLE_NANOS) return;
        double dx = serverPosition[0] - cameraPosition[0];
        double dy = serverPosition[1] - cameraPosition[1];
        double dz = serverPosition[2] - cameraPosition[2];
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > 0.0025) {
            cameraPosition[0] = serverPosition[0];
            cameraPosition[1] = serverPosition[1];
            cameraPosition[2] = serverPosition[2];
            verticalVelocity = 0.0;
            horizontalVelocityX = 0.0;
            horizontalVelocityZ = 0.0;
            flightVerticalVelocity = 0.0;
            vanillaPhysicsAccumulator = 0.0;
            snapRenderCameraToPhysics();
        }
    }

    /**
     * Matches Minecraft's partial-tick entity interpolation: gameplay and
     * packets stay on exact 50 ms states while rendering moves continuously
     * from the preceding state to the current one.
     */
    private void interpolateRenderCamera() {
        double partialTick = Math.max(0.0, Math.min(1.0,
                vanillaPhysicsAccumulator / VANILLA_TICK_SECONDS));
        for (int axis = 0; axis < 3; axis++) {
            renderCameraPosition[axis] = previousPhysicsPosition[axis]
                    + (cameraPosition[axis] - previousPhysicsPosition[axis])
                    * partialTick;
        }
    }

    private void snapRenderCameraToPhysics() {
        copyPosition(cameraPosition, previousPhysicsPosition);
        copyPosition(cameraPosition, renderCameraPosition);
    }

    private static void copyPosition(double[] source, double[] destination) {
        destination[0] = source[0];
        destination[1] = source[1];
        destination[2] = source[2];
    }

    private int axis(int primaryKey, int alternateKey) {
        return pressedKeys[primaryKey] || pressedKeys[alternateKey] ? 1 : 0;
    }

    private static float wrapDegrees(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    private void initializeGl() {
        shaderProgram = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        railAtlas.initializeGl();blockAtlas.initializeGl();
        breakingVbo=GL33.glGenBuffers();GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,breakingVbo);GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)breakingVertices.capacity(),GL33.GL_DYNAMIC_DRAW);
        cameraUniform = GL33.glGetUniformLocation(shaderProgram, "uCamera");
        rotationUniform = GL33.glGetUniformLocation(shaderProgram, "uRotation");
        aspectUniform = GL33.glGetUniformLocation(shaderProgram, "uAspect");
        projectionUniform = GL33.glGetUniformLocation(shaderProgram, "uProjection");
        mediumUniform = GL33.glGetUniformLocation(shaderProgram, "uMedium");
        materialPassUniform = GL33.glGetUniformLocation(shaderProgram, "uMaterialPass");
        displayShaderProgram = linkProgram(DISPLAY_VERTEX_SHADER, DISPLAY_FRAGMENT_SHADER);
        displayCameraUniform = GL33.glGetUniformLocation(displayShaderProgram, "uCamera");
        displayRotationUniform = GL33.glGetUniformLocation(displayShaderProgram, "uRotation");
        displayAspectUniform = GL33.glGetUniformLocation(displayShaderProgram, "uAspect");
        displayProjectionUniform = GL33.glGetUniformLocation(
                displayShaderProgram, "uProjection");
        displayTextureUniform = GL33.glGetUniformLocation(displayShaderProgram, "uTexture");
        firstPersonShaderProgram = linkProgram(
                FIRST_PERSON_VERTEX_SHADER, FIRST_PERSON_FRAGMENT_SHADER);
        firstPersonAspectUniform = GL33.glGetUniformLocation(
                firstPersonShaderProgram, "uAspect");
        firstPersonProjectionUniform = GL33.glGetUniformLocation(
                firstPersonShaderProgram, "uProjection");
        firstPersonSwingUniform = GL33.glGetUniformLocation(
                firstPersonShaderProgram, "uSwing");
        firstPersonEatUniform=GL33.glGetUniformLocation(firstPersonShaderProgram,"uEat");
        firstPersonHandUniform=GL33.glGetUniformLocation(firstPersonShaderProgram,"uHandKind");
        selectionShaderProgram = linkProgram(VERTEX_SHADER, SELECTION_FRAGMENT_SHADER);
        selectionCameraUniform = GL33.glGetUniformLocation(selectionShaderProgram, "uCamera");
        selectionRotationUniform = GL33.glGetUniformLocation(selectionShaderProgram, "uRotation");
        selectionAspectUniform = GL33.glGetUniformLocation(selectionShaderProgram, "uAspect");
        selectionProjectionUniform = GL33.glGetUniformLocation(
                selectionShaderProgram, "uProjection");
        entityRenderer.initializeGl();
        environmentRenderer.initializeGl();
        vao = GL33.glGenVertexArrays();
        GL33.glBindVertexArray(vao);
        GL33.glEnableVertexAttribArray(0);
        GL33.glEnableVertexAttribArray(1);
        displayVao = GL33.glGenVertexArrays();
        displayVbo = GL33.glGenBuffers();
        GL33.glBindVertexArray(displayVao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, displayVbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER, 20L * Float.BYTES, GL33.GL_STREAM_DRAW);
        GL33.glEnableVertexAttribArray(0);
        GL33.glEnableVertexAttribArray(1);
        GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 5 * Float.BYTES, 0L);
        GL33.glVertexAttribPointer(1, 2, GL33.GL_FLOAT, false, 5 * Float.BYTES,
                3L * Float.BYTES);
        firstPersonVao = GL33.glGenVertexArrays();
        firstPersonVbo = GL33.glGenBuffers();
        GL33.glBindVertexArray(firstPersonVao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, firstPersonVbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,
                (long) firstPersonVertices.capacity() * Float.BYTES, GL33.GL_DYNAMIC_DRAW);
        GL33.glEnableVertexAttribArray(0);
        GL33.glEnableVertexAttribArray(1);
        GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false,
                FIRST_PERSON_VERTEX_FLOATS * Float.BYTES, 0L);
        GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false,
                FIRST_PERSON_VERTEX_FLOATS * Float.BYTES, 3L * Float.BYTES);
        GL33.glEnableVertexAttribArray(2);
        GL33.glVertexAttribPointer(2,2,GL33.GL_FLOAT,false,FIRST_PERSON_VERTEX_FLOATS*Float.BYTES,7L*Float.BYTES);
        selectionVao = GL33.glGenVertexArrays();
        selectionVbo = GL33.glGenBuffers();
        GL33.glBindVertexArray(selectionVao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, selectionVbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,
                (long) selectionVertices.capacity() * Float.BYTES, GL33.GL_DYNAMIC_DRAW);
        GL33.glEnableVertexAttribArray(0);
        GL33.glEnableVertexAttribArray(1);
        GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false,
                SELECTION_VERTEX_FLOATS * Float.BYTES, 0L);
        GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false,
                SELECTION_VERTEX_FLOATS * Float.BYTES, 3L * Float.BYTES);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        GL33.glEnable(GL33.GL_CULL_FACE);
        // The corrected camera mirrors view-space X so Minecraft's screen-right
        // direction agrees with its yaw/movement axes. That reflection reverses
        // projected triangle winding; declare clockwise faces as front-facing
        // instead of accidentally discarding the visible half of every block.
        GL33.glFrontFace(GL33.GL_CW);
        GL33.glCullFace(GL33.GL_BACK);
        GL33.glClearColor(0.42f, 0.65f, 0.88f, 1f);
    }

    private void synchronizeWorldMeshes() {
        final int active = runtime.world.size();
        if (active != loggedWorldChunks) {
            loggedWorldChunks = active;
            if (active == 1 || active == 49 || (active & 7) == 0) {
                System.out.println("[NH-THIN] rendered world chunks=" + active);
            }
        }
        long now = System.nanoTime();
        if (cameraInitialized && now - lastCoverageCheckNanos >= 1_000_000_000L) {
            lastCoverageCheckNanos = now;
            long chunkKey = runtime.world.playerChunkKey();
            int missing = runtime.world.missingFullChunksAroundPlayer(4);
            if (chunkKey != lastCoverageChunkKey || missing != lastMissingChunkCount) {
                lastCoverageChunkKey = chunkKey;
                lastMissingChunkCount = missing;
                System.out.println("[NH-THIN] world coverage center="
                        + (int) (chunkKey >> 32) + "," + (int) chunkKey
                        + " missing=" + missing + "/81");
            }
        }
        int count=runtime.world.copyMeshVersions(meshVersions);
        long epoch=meshVersions[meshVersions.length-1];
        long playerChunk=runtime.world.playerChunkKey();
        long available=runtime.budget.limit(MemoryCategory.MESH)-runtime.budget.used(MemoryCategory.MESH);
        for(int i=0;i<MAX_CHUNKS;i++)if(deferredMeshBytes[i]>0&&(playerChunk!=gpuPlayerChunk||available>=deferredMeshBytes[i])) {
            long key=deferredMeshKeys[i];runtime.world.requestMeshRefresh((int)(key>>32),(int)key,deferredMeshEpochs[i]);deferredMeshBytes[i]=0;
        }
        gpuPlayerChunk=playerChunk;
        // World slots may move when the server forgets a chunk. Match by identity,
        // retaining still-visible buffers and releasing genuinely obsolete ones.
        java.util.Arrays.fill(remappedGpuChunks,null);
        for(int i=0;i<gpuChunks.length;i++) {
            GpuChunk gpu=gpuChunks[i];
            if(gpu==null)continue;
            int target=-1;
            if(gpu.epoch==epoch)for(int j=0;j<count;j++)if(gpu.chunkX==meshVersions[j*3]&&gpu.chunkZ==meshVersions[j*3+1]){target=j;break;}
            if(target<0)releaseGpuChunk(gpu);else remappedGpuChunks[target]=gpu;
        }
        System.arraycopy(remappedGpuChunks,0,gpuChunks,0,MAX_CHUNKS);
        if(pendingMesh==null) {
            pendingMesh=meshWorker.poll();uploadOffset=0;
            if(pendingMesh!=null) {
                int slot=pendingMesh.slot;
                // Reject stale work before reserving or allocating any GPU memory.
                if(slot>=count||pendingMesh.epoch!=epoch||pendingMesh.x!=meshVersions[slot*3]||pendingMesh.z!=meshVersions[slot*3+1]){
                    meshWorker.release(pendingMesh);pendingMesh=null;return;
                }
                if(uploadGpu==null)uploadGpu=allocateGpuChunk();
                if(!ensureGpuCapacity(uploadGpu,pendingMesh.geometry.remaining())){
                    deferMesh(slot,pendingMesh.x,pendingMesh.z,epoch,pendingMesh.geometry.remaining());
                    releaseGpuChunk(uploadGpu);uploadGpu=null;
                    meshWorker.release(pendingMesh);pendingMesh=null;return;
                }
                // Orphan the spare, never the visible mesh. Old geometry and light
                // remain paired until the replacement is completely uploaded.
                GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,uploadGpu.vbo);
                GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)uploadGpu.capacityBytes,GL33.GL_DYNAMIC_DRAW);
                GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,uploadGpu.lightVbo);
                GL33.glBufferData(GL33.GL_ARRAY_BUFFER,(long)uploadGpu.capacityBytes/8,GL33.GL_DYNAMIC_DRAW);
            }
        }
        if(pendingMesh==null)return;
        ChunkMeshWorker.Result mesh=pendingMesh;int slot=mesh.slot;
        if(slot>=count||mesh.epoch!=epoch||mesh.x!=meshVersions[slot*3]||mesh.z!=meshVersions[slot*3+1]) {
            meshWorker.release(mesh);pendingMesh=null;return;
        }
        // At most 128 KiB of geometry plus 16 KiB of light per frame.
        int end=Math.min(mesh.geometry.limit(),uploadOffset+128*1024);
        if(end>uploadOffset) {
            ByteBuffer part=mesh.geometry.duplicate();part.position(uploadOffset);part.limit(end);
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,uploadGpu.vbo);
            GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,uploadOffset,part);
            part=mesh.light.duplicate();part.position(uploadOffset/8);part.limit(end/8);
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,uploadGpu.lightVbo);
            GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,uploadOffset/8,part);
        }
        uploadOffset=end;
        if(end==mesh.geometry.limit()) {
            uploadGpu.chunkX=mesh.x;uploadGpu.chunkZ=mesh.z;uploadGpu.revision=mesh.revision;
            uploadGpu.epoch=mesh.epoch;uploadGpu.vertexCount=mesh.vertices;
            GpuChunk previous=gpuChunks[slot];gpuChunks[slot]=uploadGpu;uploadGpu=previous;
            meshWorker.release(mesh);pendingMesh=null;
        }
    }

    private GpuChunk allocateGpuChunk() {
        MemoryBudget.Lease lease = runtime.budget.reserve(
                MemoryCategory.MESH, 0);
        try {
            int vbo = GL33.glGenBuffers();
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo);
            int lightVbo=GL33.glGenBuffers();GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,lightVbo);
            return new GpuChunk(vbo,lightVbo,lease,0);
        } catch (RuntimeException | Error throwable) {
            lease.close();
            throw throwable;
        }
    }

    private boolean ensureGpuCapacity(GpuChunk gpu, int requiredBytes) {
        int capacity=MeshBufferSizing.capacity(requiredBytes);
        MemoryBudget.Lease resized=runtime.budget.tryResize(gpu.lease,capacity+capacity/8);
        while(resized==null) {
            // Under exceptional pressure prioritize nearby geometry. Keep the
            // previous visible mesh until its replacement is fully uploaded.
            int victim=-1;long farthest=meshDistance(pendingMesh.x,pendingMesh.z);
            for(int i=0;i<gpuChunks.length;i++)if(gpuChunks[i]!=null){
                GpuChunk c=gpuChunks[i];long distance=meshDistance(c.chunkX,c.chunkZ);
                if(distance>farthest){farthest=distance;victim=i;}
            }
            if(victim<0)return false;
            GpuChunk c=gpuChunks[victim];deferMesh(victim,c.chunkX,c.chunkZ,c.epoch,c.capacityBytes);
            releaseGpuChunk(c);gpuChunks[victim]=null;
            resized=runtime.budget.tryResize(gpu.lease,capacity+capacity/8);
        }
        gpu.lease=resized;gpu.capacityBytes=capacity;return true;
    }

    private long meshDistance(int x,int z){
        long key=runtime.world.playerChunkKey(),dx=(long)x-(int)(key>>32),dz=(long)z-(int)key;
        return Math.max(Math.abs(dx),Math.abs(dz))*1_000_000L+dx*dx+dz*dz;
    }
    private void deferMesh(int slot,int x,int z,long epoch,int bytes){
        deferredMeshKeys[slot]=(long)x<<32|z&0xffffffffL;deferredMeshEpochs[slot]=epoch;
        int capacity=MeshBufferSizing.capacity(bytes);deferredMeshBytes[slot]=capacity+capacity/8;
        if(++gpuPressureEvents<=4 || (gpuPressureEvents&63)==0)
            System.out.println("[NH-STREAM] mesh pressure deferred="+x+","+z+" used="+runtime.budget.used(MemoryCategory.MESH));
    }
    private void releaseGpuChunk(GpuChunk gpu){
        GL33.glDeleteBuffers(gpu.vbo);GL33.glDeleteBuffers(gpu.lightVbo);gpu.lease.close();
    }

    private void drawFrame() {
        framebufferWidth.clear();
        framebufferHeight.clear();
        GLFW.glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
        int width = Math.max(1, framebufferWidth.get(0));
        int height = Math.max(1, framebufferHeight.get(0));
        long visualNow=System.nanoTime();
        renderedFrames++;if(visualNow-frameWindow>=1_000_000_000L){framesPerSecond=frameWindow==0?0:(int)(renderedFrames*1_000_000_000L/(visualNow-frameWindow));renderedFrames=0;frameWindow=visualNow;}
        viewCamera.update(runtime.world,renderCameraPosition[0],renderCameraPosition[1]+eyeHeight(),renderCameraPosition[2],cameraRotation[0],cameraRotation[1]);
        com.newhorizon.thinclient.world.EntityTracker.Renderable local=localVisual.model;
        local.entityId=connection.playerEntityId();local.profileId=runtime.players.localId();local.sneaking=sneaking;
        local.swimming=swimming.swimming;local.prone=swimming.prone;local.inWater=(playerPhysicsFlags()&BlockStatePhysics.WATER)!=0;
        local.riding=runtime.riding.vehicleOf(local.entityId)>=0;local.sleeping=runtime.riding.sleeping();local.burning=runtime.combat.burning();
        local.invisible=runtime.effects.level(14)>0;local.hurt=runtime.combat.hurtRoll(visualNow)!=0;local.onGround=onGround;
        local.swingProgress=currentSwingProgress();local.blocking=chargingItem && "SHIELD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(chargingMaterial));
        localVisual.update(visualNow,renderCameraPosition[0],renderCameraPosition[1],renderCameraPosition[2],cameraRotation[0],cameraRotation[1]);
        entityRenderer.localPlayer(viewCamera.mode==0?null:local);
        float eyeX = (float) viewCamera.x;
        float eyeY = (float) viewCamera.y;
        float eyeZ = (float) viewCamera.z;
        int eyeFlags = runtime.world.physicsFlagsInBox(
                eyeX - 0.01, eyeY - 0.01, eyeZ - 0.01,
                eyeX + 0.01, eyeY + 0.01, eyeZ + 0.01);
        float medium = (eyeFlags & BlockStatePhysics.WATER) != 0 ? 1.0f
                : (eyeFlags & BlockStatePhysics.LAVA) != 0 ? 2.0f : 0.0f;
        long environmentNow = System.nanoTime();
        ScreenEffects.update(runtime.effects,environmentNow);
        ScreenEffects.hurt(runtime.combat.hurtRoll(environmentNow));
        com.newhorizon.thinclient.audio.BiomeSoundRegistry.Biome weatherBiome=runtime.soundBiomes.get(
                runtime.world.biomeAt((int)Math.floor(eyeX),(int)Math.floor(eyeY),(int)Math.floor(eyeZ)));
        environmentRenderer.prepare(environmentNow,runtime.world.dimension(),weatherBiome,(int)medium,SceneAtmosphere.VIEW_CHUNKS,viewCamera.yaw);
        SceneAtmosphere.update(environmentRenderer.atmosphere,runtime.world,eyeX,eyeY,eyeZ);
        environmentRenderer.snowing(weatherBiome!=null&&weatherBiome.temperature<.15f);
        if(ScreenEffects.obscuresSky()) GL33.glClearColor(0,0,0,1);
        else GL33.glClearColor(environmentRenderer.clearRed(),
                environmentRenderer.clearGreen(), environmentRenderer.clearBlue(), 1f);
        GL33.glViewport(0, 0, width, height);
        GL33.glClear(GL33.GL_COLOR_BUFFER_BIT | GL33.GL_DEPTH_BUFFER_BIT);
        if (medium == 0.0f && !ScreenEffects.obscuresSky()) {
            environmentRenderer.drawSky(eyeX, eyeY, eyeZ,
                    viewCamera.yaw, viewCamera.pitch, width / (float) height,
                    projectionScale());
        }
        ScreenEffects.bind(shaderProgram);
        GL33.glUniform3f(cameraUniform, eyeX, eyeY, eyeZ);
        GL33.glUniform2f(rotationUniform, viewCamera.yaw, viewCamera.pitch);
        GL33.glUniform1f(aspectUniform, width / (float) height);
        GL33.glUniform1f(projectionUniform, projectionScale());
        GL33.glUniform1f(mediumUniform, medium);
        GL33.glBindVertexArray(vao);
        GL33.glUniform1f(materialPassUniform, 0.0f);
        drawWorldChunks();

        // Dynamic entities share one cube VBO and one bounded instancing upload.
        // Draw them between solid terrain and liquids so water overlays them.
        entityRenderer.draw(eyeX, eyeY, eyeZ, viewCamera.yaw, viewCamera.pitch,
                width / (float) height, projectionScale(), medium,
                connection.playerEntityId());
        ScreenEffects.bind(shaderProgram);
        GL33.glBindVertexArray(vao);

        // Vanilla separates translucent liquids from the opaque terrain pass.
        // Reuse the same bounded VBOs: the shader's alpha-byte material tag
        // rejects the other class, so no second CPU mesh or RAM copy exists.
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
        GL33.glDepthMask(false);
        GL33.glDisable(GL33.GL_CULL_FACE);
        GL33.glUniform1f(materialPassUniform, 1.0f);
        drawWorldChunks();
        GL33.glEnable(GL33.GL_CULL_FACE);
        GL33.glDepthMask(true);
        GL33.glDisable(GL33.GL_BLEND);
        if (medium == 0.0f && !ScreenEffects.obscuresSky()) {
            environmentRenderer.drawWeather(eyeX, eyeY, eyeZ,
                    viewCamera.yaw, viewCamera.pitch, width / (float) height,
                    projectionScale());
        }
        displaySnapshotCount = runtime.displays.snapshotVisible(displaySnapshots);
        processMouseEvents();
        drawBreakingTexture();
        if(!hudHidden)drawBlockSelection(width, height);
        drawDisplays(width, height);
        drawFirstPerson(width, height);
        if(viewCamera.mode==0&&!hudHidden){blockAtlas.bind(1);entityRenderer.drawFireOverlay(runtime.combat.burning(),width/(float)height);}
    }

    private void drawWorldChunks() {
        biomeTints.update(runtime.soundBiomes);biomeTints.bind();
        GL33.glUniform1i(GL33.glGetUniformLocation(shaderProgram,"uBiomeTints"),2);
        blockAtlas.update(System.nanoTime());blockAtlas.bind(1);
        GL33.glUniform1i(GL33.glGetUniformLocation(shaderProgram,"uBlockAtlas"),1);
        GL33.glUniform2f(GL33.glGetUniformLocation(shaderProgram,"uBlockAtlasSize"),BlockAtlas.WIDTH,BlockAtlas.HEIGHT);
        GL33.glUniform1i(GL33.glGetUniformLocation(shaderProgram,"uGrassSide"),com.newhorizon.thinclient.world.VanillaBlockTextures.tileId("grass_block_side"));
        GL33.glUniform1i(GL33.glGetUniformLocation(shaderProgram,"uGrassOverlay"),com.newhorizon.thinclient.world.VanillaBlockTextures.tileId("grass_block_side_overlay"));
        railAtlas.bind();
        GL33.glUniform1i(GL33.glGetUniformLocation(shaderProgram,"uRailAtlas"),0);
        for (GpuChunk chunk : gpuChunks) {
            if (chunk == null || chunk.vertexCount == 0) continue;
            double dx=Math.max(0,Math.max(chunk.chunkX*16-renderCameraPosition[0],renderCameraPosition[0]-(chunk.chunkX*16+16)));
            double dz=Math.max(0,Math.max(chunk.chunkZ*16-renderCameraPosition[2],renderCameraPosition[2]-(chunk.chunkZ*16+16)));
            if(dx*dx+dz*dz>SceneAtmosphere.VIEW_CHUNKS*SceneAtmosphere.VIEW_CHUNKS*256)continue;
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, chunk.vbo);
            GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false,
                    SurfaceMesher.VERTEX_BYTES, 0L);
            GL33.glVertexAttribPointer(1, 4, GL33.GL_UNSIGNED_BYTE, true,
                    SurfaceMesher.VERTEX_BYTES, 12L);
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,chunk.lightVbo);
            GL33.glEnableVertexAttribArray(2);GL33.glVertexAttribPointer(2,2,GL33.GL_UNSIGNED_BYTE,false,2,0L);
            GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, chunk.vertexCount);
        }
    }

    private void drawFirstPerson(int framebufferWidth, int framebufferHeight) {
        if(viewCamera.mode!=0||hudHidden)return;
        if(runtime.effects.level(14)>0) return;
        if (inventoryOpen || minePads.isOpen() || runtime.combat.dead() || runtime.riding.sleeping()) return;
        boolean shieldPose=chargingItem && "SHIELD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(chargingMaterial));
        int drawStage=chargingItem && chargingHand==0 && "BOW".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(chargingMaterial))
                ? (System.nanoTime()-itemUseStarted>=900_000_000L?2:System.nanoTime()-itemUseStarted>=650_000_000L?1:0):-1;
        boolean fishingCast=runtime.entities.fishing(connection.playerEntityId());
        String held=runtime.inventory.selectedHotbarMaterial();
        boolean modPose=isMinePad(held)||isLaserPointer(held),beam=primaryHeld||secondaryHeld,padSide=!sneaking;
        float modSwing=modPose?currentSwingProgress():0;
        long revision = runtime.inventory.revision();
        if (fishingCast!=lastFishingCast || revision != lastFirstPersonRevision || shieldPose!=lastShieldPose || drawStage!=lastDrawStage
                || beam!=lastModBeam || padSide!=lastPadSide || modPose!=firstPersonModPose || modSwing!=lastModSwing) {
            lastModBeam=beam;lastPadSide=padSide;lastModSwing=modSwing;
            lastFishingCast=fishingCast;lastFirstPersonRevision = revision;lastShieldPose=shieldPose;lastDrawStage=drawStage;
            rebuildFirstPersonMesh(runtime.inventory.selectedHotbarMaterial(),
                    runtime.inventory.selectedHotbarAmount());
        }
        if (firstPersonVertexCount == 0) return;
        // Vanilla renders the hand after the level with a fresh depth surface.
        // Keep depth testing between the item's own faces so it is genuinely 3D.
        GL33.glClear(GL33.GL_DEPTH_BUFFER_BIT);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        GL33.glDisable(GL33.GL_CULL_FACE);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
        ScreenEffects.bind(firstPersonShaderProgram);
        GL33.glUniform1f(firstPersonAspectUniform,
                framebufferWidth / (float) framebufferHeight);
        GL33.glUniform1f(firstPersonProjectionUniform,
                vanillaFirstPersonProjectionScale());
        blockAtlas.bind(1);
        GL33.glUniform1i(GL33.glGetUniformLocation(firstPersonShaderProgram,"uBlockAtlas"),1);
        GL33.glUniform1i(GL33.glGetUniformLocation(firstPersonShaderProgram,"uGrassSide"),com.newhorizon.thinclient.world.VanillaBlockTextures.tileId("grass_block_side"));
        GL33.glUniform1i(GL33.glGetUniformLocation(firstPersonShaderProgram,"uGrassOverlay"),com.newhorizon.thinclient.world.VanillaBlockTextures.tileId("grass_block_side_overlay"));
        GL33.glUniform1f(firstPersonSwingUniform, firstPersonModPose||chargingItem?0:currentSwingProgress());
        applyFirstPersonUse(0);
        GL33.glBindVertexArray(firstPersonVao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, firstPersonVbo);
        GL33.glUniform1i(firstPersonHandUniform,firstPersonModPose?0:2);
        GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, firstPersonArmVertexCount);
        int itemVertexCount = firstPersonOffhandStart - firstPersonArmVertexCount;
        if (itemVertexCount > 0) {
            GL33.glUniform1i(firstPersonHandUniform,firstPersonModPose?0:1);
            GL33.glDrawArrays(GL33.GL_TRIANGLES, firstPersonArmVertexCount,
                    itemVertexCount);
        }
        if(firstPersonVertexCount>firstPersonOffhandStart) {
            GL33.glUniform1i(firstPersonHandUniform,0);
            GL33.glUniform1f(firstPersonSwingUniform,0);
            applyFirstPersonUse(1);
            GL33.glDrawArrays(GL33.GL_TRIANGLES,firstPersonOffhandStart,firstPersonVertexCount-firstPersonOffhandStart);
        }
        GL33.glUniform3f(firstPersonEatUniform,0,0,1);
        drawMinePadScreen(false,framebufferWidth/(float)framebufferHeight);
        drawMinePadScreen(true,framebufferWidth/(float)framebufferHeight);
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glEnable(GL33.GL_CULL_FACE);
    }

    private void drawBreakingTexture() {
        if(!destroyingBlock||inventoryOpen||runtime.inventory.isCreativeMode()||destroyRequiredNanos<=0)return;
        int stage=Math.min(9,(int)((System.nanoTime()-destroyStartNanos)*10/destroyRequiredNanos));
        int tile=com.newhorizon.thinclient.world.VanillaBlockTextures.tileId("destroy_stage_"+Math.max(0,stage));
        if(tile<0)return;
        int count=SurfaceMesher.texturedBox(breakingVertices,heldHit.blockX,heldHit.blockY,heldHit.blockZ,.002f,SurfaceMesher.TEXTURED|tile|(1<<17)|(1<<19));
        breakingVertices.flip();ScreenEffects.bind(shaderProgram);blockAtlas.bind(1);
        GL33.glUniform1f(materialPassUniform,0);GL33.glBindVertexArray(vao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,breakingVbo);
        SceneAtmosphere.eyeAttribute(2);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,0,breakingVertices);
        GL33.glVertexAttribPointer(0,3,GL33.GL_FLOAT,false,16,0L);GL33.glVertexAttribPointer(1,4,GL33.GL_UNSIGNED_BYTE,true,16,12L);
        int crumbling=GL33.glGetUniformLocation(shaderProgram,"uCrumbling");
        GL33.glUniform1f(crumbling,1);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFuncSeparate(GL33.GL_DST_COLOR,GL33.GL_SRC_COLOR,GL33.GL_ONE,GL33.GL_ZERO);
        GL33.glDepthMask(false);GL33.glDrawArrays(GL33.GL_TRIANGLES,0,count);GL33.glDepthMask(true);
        GL33.glUniform1f(crumbling,0);
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA,GL33.GL_ONE_MINUS_SRC_ALPHA);
        GL33.glDisable(GL33.GL_BLEND);
    }

    private void applyFirstPersonUse(int hand) {
        int duration=chargingItem&&chargingHand==hand?ConsumableUse.durationTicks(chargingMaterial):0;
        float remaining=ConsumableUse.remaining(duration,System.nanoTime()-itemUseStarted);
        GL33.glUniform3f(firstPersonEatUniform,ConsumableUse.blend(duration,remaining),
                ConsumableUse.bob(duration,remaining),hand==0?1:-1);
    }

    private void drawBlockSelection(int framebufferWidth, int framebufferHeight) {
        long now = System.nanoTime();
        if (now - lastTargetUpdateNanos >= TARGET_UPDATE_NANOS) {
            lastTargetUpdateNanos = now;
            if (mayInteractWithDisplay()&&findDisplayHit(displayHit)) {
                selectionHit.clear();
            } else {
                findVanillaHit(selectionHit);
            }
        }
        if (selectionHit.type != VanillaHitResult.BLOCK) {
            selectionMeshVisible = false;
            return;
        }
        if (!selectionMeshVisible || selectionMeshX != selectionHit.blockX
                || selectionMeshY != selectionHit.blockY
                || selectionMeshZ != selectionHit.blockZ
                || selectionMeshState != runtime.world.blockStateAt(selectionHit.blockX,selectionHit.blockY,selectionHit.blockZ)) {
            uploadSelectionBox(selectionHit.blockX, selectionHit.blockY,
                    selectionHit.blockZ);
        }

        ScreenEffects.bind(selectionShaderProgram);
        GL33.glUniform3f(selectionCameraUniform, (float)viewCamera.x, (float)viewCamera.y, (float)viewCamera.z);
        GL33.glUniform2f(selectionRotationUniform, viewCamera.yaw, viewCamera.pitch);
        GL33.glUniform1f(selectionAspectUniform,
                framebufferWidth / (float) framebufferHeight);
        GL33.glUniform1f(selectionProjectionUniform, projectionScale());
        GL33.glBindVertexArray(selectionVao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, selectionVbo);
        GL33.glDisable(GL33.GL_CULL_FACE);
        GL33.glLineWidth(2.0f);
        GL33.glDrawArrays(GL33.GL_LINES, 0, SELECTION_VERTEX_COUNT);
        GL33.glEnable(GL33.GL_CULL_FACE);
    }

    private void uploadSelectionBox(int blockX, int blockY, int blockZ) {
        final float epsilon = 0.003f;
        float x0 = blockX - epsilon;
        float y0 = blockY - epsilon;
        float z0 = blockZ - epsilon;
        float x1 = blockX + 1f + epsilon;
        float y1 = blockY + com.newhorizon.thinclient.world.RailState.outlineHeight(runtime.world.blockStateAt(blockX,blockY,blockZ)) + epsilon;
        float z1 = blockZ + 1f + epsilon;
        selectionVertices.clear();
        putSelectionEdge(x0, y0, z0, x1, y0, z0);
        putSelectionEdge(x1, y0, z0, x1, y0, z1);
        putSelectionEdge(x1, y0, z1, x0, y0, z1);
        putSelectionEdge(x0, y0, z1, x0, y0, z0);
        putSelectionEdge(x0, y1, z0, x1, y1, z0);
        putSelectionEdge(x1, y1, z0, x1, y1, z1);
        putSelectionEdge(x1, y1, z1, x0, y1, z1);
        putSelectionEdge(x0, y1, z1, x0, y1, z0);
        putSelectionEdge(x0, y0, z0, x0, y1, z0);
        putSelectionEdge(x1, y0, z0, x1, y1, z0);
        putSelectionEdge(x1, y0, z1, x1, y1, z1);
        putSelectionEdge(x0, y0, z1, x0, y1, z1);
        selectionVertices.flip();
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, selectionVbo);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, selectionVertices);
        selectionMeshX = blockX;
        selectionMeshY = blockY;
        selectionMeshZ = blockZ;
        selectionMeshState = runtime.world.blockStateAt(blockX,blockY,blockZ);
        selectionMeshVisible = true;
    }

    private void putSelectionEdge(float x0, float y0, float z0,
                                  float x1, float y1, float z1) {
        putSelectionVertex(x0, y0, z0);
        putSelectionVertex(x1, y1, z1);
    }

    private void putSelectionVertex(float x, float y, float z) {
        selectionVertices.put(x).put(y).put(z)
                .put(0.02f).put(0.02f).put(0.02f).put(0.95f);
    }

    private float currentSwingProgress() {
        if (swingStartNanos == Long.MIN_VALUE) return 0f;
        long elapsed = System.nanoTime() - swingStartNanos;
        long duration=swingDuration();
        if (elapsed < 0L || elapsed >= duration) return 0f;
        return elapsed / (float) duration;
    }

    private long swingDuration() {
        int haste=runtime.effects.level(3),fatigue=runtime.effects.level(4);
        return Math.max(1,haste>0?6-haste:fatigue>0?6+fatigue*2:6)*50_000_000L;
    }
    private void swingMainHand() throws IOException, ProtocolException {
        if(connection.swingMainHand()) {
            long now=System.nanoTime();
            if(swingStartNanos==Long.MIN_VALUE || now-swingStartNanos>=swingDuration()/2)swingStartNanos=now;
        }
    }

    private void rebuildFirstPersonMesh(String material, int amount) {
        firstPersonVertices.clear();
        minePadMatrices[0]=minePadMatrices[1]=null;
        buildingOffhand=false;
        buildingModSwing=currentSwingProgress();
        firstPersonModPose=isMinePad(material)||isLaserPointer(material);
        boolean holdingItem=amount>0 && material!=null && !material.isEmpty()
                && !"AIR".equalsIgnoreCase(material) && !"minecraft:air".equalsIgnoreCase(material);
        // ItemInHandRenderer uses the bare-arm branch only for an empty main hand.
        // Rendering it underneath an ordinary held item creates a false grip on the forearm.
        // MinePadRenderer owns a supporting arm, unlike ordinary item renderers.
        if(!holdingItem||isMinePad(material))addVanillaRightArm(isMinePad(material)?buildingModSwing:0);
        firstPersonArmVertexCount = firstPersonVertices.position()
                / FIRST_PERSON_VERTEX_FLOATS;

        if (holdingItem) {
            addVanillaHeldItem(material.toLowerCase(Locale.ROOT),runtime.inventory.selectedHotbarTag());
        }

        firstPersonOffhandStart=firstPersonVertices.position()/FIRST_PERSON_VERTEX_FLOATS;
        if("SHIELD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(runtime.inventory.offhandMaterial())))
            addHeldShield(true,lastShieldPose && chargingHand==1);
        String offhand=com.newhorizon.thinclient.world.ProjectileKind.materialKey(runtime.inventory.offhandMaterial());
        if(!offhand.isEmpty()&&!"AIR".equals(offhand)&&!"SHIELD".equals(offhand)) {
            int start=firstPersonVertices.position();
            buildingOffhand=true;
            if(isMinePad(offhand))addVanillaRightArm(0);
            addVanillaHeldItem(runtime.inventory.offhandMaterial().toLowerCase(Locale.ROOT),runtime.inventory.offhandTag());
            for(int i=start;i<firstPersonVertices.position();i+=FIRST_PERSON_VERTEX_FLOATS)
                firstPersonVertices.put(i,-firstPersonVertices.get(i));
            buildingOffhand=false;
        }
        firstPersonVertices.flip();
        firstPersonVertexCount = firstPersonVertices.remaining()
                / FIRST_PERSON_VERTEX_FLOATS;
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, firstPersonVbo);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, firstPersonVertices);
    }

    /** The idle right-hand path from ItemInHandRenderer in Minecraft 1.20.1. */
    private void addVanillaRightArm(float swing) {
        float[] transform = identityMatrix();
        float root=(float)Math.sin(Math.sqrt(swing)*Math.PI),twice=(float)Math.sin(Math.sqrt(swing)*Math.PI*2);
        translate(transform, 0.64000005f-.3f*root, -.6f+.4f*twice, -.71999997f-.4f*(float)Math.sin(swing*Math.PI));
        rotateY(transform, 45f);
        rotateY(transform,70f*root);rotateZ(transform,-20f*(float)Math.sin(swing*swing*Math.PI));
        translate(transform, -1f, 3.6f, 3.5f);
        rotateZ(transform, 120f);
        rotateX(transform, 200f);
        rotateY(transform, -135f);
        translate(transform, 5.6f, 0f, 0f);

        // HumanoidModel wide right arm: 4 x 12 x 4, pivot (-5, 2, 0).
        addVanillaArmCuboid(transform, 0f, 16);
        // PlayerModel's optional jacket layer. The built-in Steve layer is
        // transparent, but retaining the 0.25 expansion keeps the model exact.
        addVanillaArmCuboid(transform, 0.25f, 32);
    }
    private void addVanillaArmCuboid(float[] transform, float inflate, int textureV) {
        int armWidth=localSkin!=null&&localSkin.slim?3:4;
        float pivotY=armWidth==3?2.5f:2;
        float x0 = (-5f - (armWidth-1) - inflate) / 16f;
        float x1 = (-5f + 1f + inflate) / 16f;
        float y0 = (pivotY - 2f - inflate) / 16f;
        float y1 = (pivotY + 10f + inflate) / 16f;
        float z0 = (-2f - inflate) / 16f;
        float z1 = (2f + inflate) / 16f;
        float stepX = (x1 - x0) / armWidth;
        float stepY = (y1 - y0) / 12f;
        float stepZ = (z1 - z0) * 0.25f;

        addSkinFace(transform, 44, textureV, armWidth, 4,
                x0, y0, z1, stepX, 0f, 0f, 0f, 0f, -stepZ, 0.90f);
        addSkinFace(transform, 44+armWidth, textureV, armWidth, 4,
                x0, y1, z0, stepX, 0f, 0f, 0f, 0f, stepZ, 0.60f);
        addSkinFace(transform, 40, textureV + 4, 4, 12,
                x0, y0, z0, 0f, 0f, stepZ, 0f, stepY, 0f, 0.78f);
        addSkinFace(transform, 44, textureV + 4, armWidth, 12,
                x1, y0, z0, -stepX, 0f, 0f, 0f, stepY, 0f, 1.00f);
        addSkinFace(transform, 44+armWidth, textureV + 4, 4, 12,
                x1, y0, z1, 0f, 0f, -stepZ, 0f, stepY, 0f, 0.82f);
        addSkinFace(transform, 48+armWidth, textureV + 4, armWidth, 12,
                x0, y0, z1, stepX, 0f, 0f, 0f, stepY, 0f, 0.70f);
    }

    private void addSkinFace(float[] transform, int textureX, int textureY,
                             int width, int height,
                             float originX, float originY, float originZ,
                             float stepUx, float stepUy, float stepUz,
                             float stepVx, float stepVy, float stepVz,
                             float shade) {
        addPixelSkinFace(transform,false,textureX,textureY,width,height,originX,originY,originZ,
                stepUx,stepUy,stepUz,stepVx,stepVy,stepVz,shade);
    }

    private void addPixelSkinFace(float[] transform,boolean shield,int textureX,int textureY,int width,int height,
                                  float originX,float originY,float originZ,float stepUx,float stepUy,float stepUz,
                                  float stepVx,float stepVy,float stepVz,float shade) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = shield ? VanillaShieldSkin.pixel(textureX+x,textureY+y) : localSkin==null?VanillaRightArmSkin.pixel(textureX+x,textureY+y):localSkin.pixels[(textureY+y)*64+textureX+x];
                if ((argb >>> 24) == 0) continue;
                float px = originX + stepUx * x + stepVx * y;
                float py = originY + stepUy * x + stepVy * y;
                float pz = originZ + stepUz * x + stepVz * y;
                addFirstPersonQuad3d(transform,
                        px, py, pz,
                        px + stepUx, py + stepUy, pz + stepUz,
                        px + stepUx + stepVx, py + stepUy + stepVy,
                        pz + stepUz + stepVz,
                        px + stepVx, py + stepVy, pz + stepVz,
                        argb, shade);
            }
        }
    }

    private void addVanillaHeldItem(String material,String tag) {
        if (material.contains("laserpointer") || material.contains("laser_pointer")) {
            addWebDisplaysLaserPointer();
            return;
        }
        if (material.contains("minepad") || material.contains("tablet")) {
            addWebDisplaysMinePad();
            return;
        }
        if("SHIELD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(material))) {
            addHeldShield(false,lastShieldPose && chargingHand==0);return;
        }
        String sprite=lastDrawStage>=0 && "BOW".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(material))
                ? "bow_pulling_"+lastDrawStage:material;
        if(lastFishingCast && "FISHING_ROD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(material)))sprite="fishing_rod_cast";
        int[] pixels=CombatItemSprites.load(sprite,tag);
        if(pixels!=null) {
            if("FISHING_ROD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(material)))addFishingRod(pixels);
            else addGeneratedHeldItem(sprite,pixels);
            return;
        }
        if(addTexturedHeldBlock(material))return;
        addFallbackHeldItem(material);
    }

    private static boolean isMinePad(String material){return material!=null&&(material.toLowerCase(Locale.ROOT).contains("minepad")||material.toLowerCase(Locale.ROOT).contains("tablet"));}
    private static boolean isLaserPointer(String material){return material!=null&&(material.toLowerCase(Locale.ROOT).contains("laserpointer")||material.toLowerCase(Locale.ROOT).contains("laser_pointer"));}

    /** Vanilla ShieldModel plate/handle dimensions and native UV net. */
    private void addHeldShield(boolean left,boolean blocking) {
        float side=left?-1:1;
        float[] transform=identityMatrix();
        translate(transform,side*(blocking?.27f:.65f),blocking?-.22f:-.50f,blocking?-.70f:-.88f);
        rotateY(transform,side*(blocking?8:32));rotateZ(transform,side*5);
        scale(transform,1f,-1f,1f);
        addShieldCuboid(transform,0,0,-6,-11,-2,12,22,1);
        addShieldCuboid(transform,26,0,-1,-3,-1,2,6,6);
    }

    private void addShieldCuboid(float[] m,int u,int v,int x,int y,int z,int w,int h,int d) {
        float a=x/16f,b=y/16f,c=z/16f,xx=(x+w)/16f,yy=(y+h)/16f,zz=(z+d)/16f,q=1f/16f;
        addPixelSkinFace(m,true,u+d,v,w,d,a,b,zz,q,0,0,0,0,-q,.9f);
        addPixelSkinFace(m,true,u+d+w,v,w,d,a,yy,c,q,0,0,0,0,q,.6f);
        addPixelSkinFace(m,true,u,v+d,d,h,a,b,c,0,0,q,0,q,0,.78f);
        addPixelSkinFace(m,true,u+d,v+d,w,h,xx,b,c,-q,0,0,0,q,0,1f);
        addPixelSkinFace(m,true,u+d+w,v+d,d,h,xx,b,zz,0,0,-q,0,q,0,.82f);
        addPixelSkinFace(m,true,u+2*d+w,v+d,w,h,a,b,zz,q,0,0,0,q,0,.85f);
    }

    /** Exact idle body emitted by WebDisplays' LaserPointerRenderer. */
    private void addWebDisplaysLaserPointer() {
        float[] transform = laserPointerPose(buildingOffhand?0:buildingModSwing);
        int gray = 0xff808080;
        addFirstPersonQuad3d(transform,
                0f, 0f, 0f, 1f, 0f, 0f,
                1f, 0f, 4f, 0f, 0f, 4f, gray, 1f);
        addFirstPersonQuad3d(transform,
                0f, 0f, 0f, 0f, -1f, 0f,
                0f, -1f, 4f, 0f, 0f, 4f, gray, 1f);
        addFirstPersonQuad3d(transform,
                1f, 0f, 0f, 1f, -1f, 0f,
                1f, -1f, 4f, 1f, 0f, 4f, gray, 1f);
        addFirstPersonQuad3d(transform,
                0f, -1f, 4f, 1f, -1f, 4f,
                1f, 0f, 4f, 0f, 0f, 4f, gray, 1f);
        if(primaryHeld||secondaryHeld)addOriginalModBeam(transform);
    }

    private static float[] laserPointerPose(float swing) {
        float[] transform=vanillaRightHandItemPose(0,swing);
        // WebDisplays' renderer continues from the common vanilla arm pose.
        // These are model-local transforms, not a second item position.
        rotateY(transform, -30f);
        translate(transform, 0f, 0.2f, 0f);
        rotateX(transform, 10f);
        scale(transform, 0.0625f, 0.0625f, 0.0625f);

        return transform;
    }

    private void addOriginalModBeam(float[] pose) {
        // LaserPointerRenderer.drawLineBetween: four quads joining model-local aperture
        // (0.25..0.5, -0.5..-0.25, 0.5) to its original camera-space far rectangle.
        float far=(float)(Math.sqrt(40.5*40.5+4001*4001+100.5*100.5)*.5);
        for(int i=0;i<4;i++) {
            float x=(i==1||i==2)?.5f:.25f,y=i>=2?-.5f:-.25f,z=.5f;
            modBeamCorners[i*3]=pose[0]*x+pose[4]*y+pose[8]*z+pose[12];
            modBeamCorners[i*3+1]=pose[1]*x+pose[5]*y+pose[9]*z+pose[13];
            modBeamCorners[i*3+2]=pose[2]*x+pose[6]*y+pose[10]*z+pose[14];
            modBeamCorners[12+i*3]=(i==1||i==2)?4:-6;modBeamCorners[13+i*3]=i>=2?-7:3;modBeamCorners[14+i*3]=-far;
        }
        int start=firstPersonVertices.position();float[] identity=identityMatrix();
        for(int[] quad:MOD_BEAM_FACES){int a=quad[0]*3,b=quad[1]*3,c=quad[2]*3,d=quad[3]*3;
            addFirstPersonQuad3d(identity,modBeamCorners[a],modBeamCorners[a+1],modBeamCorners[a+2],modBeamCorners[b],modBeamCorners[b+1],modBeamCorners[b+2],
                    modBeamCorners[c],modBeamCorners[c+1],modBeamCorners[c+2],modBeamCorners[d],modBeamCorners[d+1],modBeamCorners[d+2],0xff800000,1);
        }
        for(int i=start;i<firstPersonVertices.position();i+=FIRST_PERSON_VERTEX_FLOATS)firstPersonVertices.put(i+7,-2f);
    }

    /**
     * WebDisplays 2.0.2's first-person MinePad path. This deliberately does
     * not use item/generated: the mod owns a dedicated model and pose.
     */
    private void addWebDisplaysMinePad() {
        float[] transform = minePadPose(!sneaking,buildingOffhand?0:buildingModSwing);
        minePadMatrices[buildingOffhand?1:0]=transform;

        final float width = 0.8740625f;
        final float height = 0.4395f;
        addFirstPersonQuad3d(transform,
                0f, 0f, 0f, width, 0f, 0f,
                width, height, 0f, 0f, height, 0f,
                0xff000000, 1f);
        addTextureRegion(transform, ModItemSprites.MINEPAD_MODEL,
                ModItemSprites.MINEPAD_MODEL_SIZE,
                1, 19, 0, 12,
                0f, width, -1f / 23f, height + 1f / 23f, 0.0002f);
        addTextureRegion(transform, ModItemSprites.MINEPAD_MODEL,
                ModItemSprites.MINEPAD_MODEL_SIZE,
                0, 20, 1, 10,
                -1f / 21f, width + 1f / 21f, 0f, height, 0.0004f);
    }

    private static float[] minePadPose(boolean side,float swing) {
        float[] transform=vanillaRightHandItemPose(0,swing);
        rotateY(transform, -45f);

        // ClientConfig.sidePad defaults to true. In the normal, non-sneaking
        // state MinePadRenderer therefore takes its exact side-held branch.
        if(side) {
            translate(transform, 0f, 0f, -0.2f);
            rotateY(transform, -20f);
            translate(transform, -.525f, -.1f, 0f);
            rotateZ(transform, 1f);
        } else translate(transform,-1.065f,0,0);
        translate(transform, 0.063f, 0.28f, 0.001f);
        return transform;
    }

    private void addTextureRegion(float[] transform, int[] pixels,
                                  int textureSize,
                                  int sourceLeft, int sourceRight,
                                  int sourceTop, int sourceBottom,
                                  float left, float right,
                                  float bottom, float top, float z) {
        int sourceWidth = sourceRight - sourceLeft;
        int sourceHeight = sourceBottom - sourceTop;
        for (int py = sourceTop; py < sourceBottom; py++) {
            float yTop = top - (py - sourceTop) * (top - bottom) / sourceHeight;
            float yBottom = top - (py + 1 - sourceTop)
                    * (top - bottom) / sourceHeight;
            for (int px = sourceLeft; px < sourceRight; px++) {
                int argb = pixels[py * textureSize + px];
                if ((argb >>> 24) == 0) continue;
                float xLeft = left + (px - sourceLeft)
                        * (right - left) / sourceWidth;
                float xRight = left + (px + 1 - sourceLeft)
                        * (right - left) / sourceWidth;
                addFirstPersonQuad3d(transform,
                        xLeft, yBottom, z, xRight, yBottom, z,
                        xRight, yTop, z, xLeft, yTop, z, argb, 1f);
            }
        }
    }

    /** Original handheld_rod.json display transform, after the common hand pose. */
    private static float[] fishingRodPose() {
        float[] transform=vanillaRightHandItemPose(0f,0f);
        rotateY(transform,-45f);
        translate(transform,0f,1.6f/16f,.8f/16f);
        rotateY(transform,90f);rotateZ(transform,25f);
        scale(transform,.68f,.68f,.68f);translate(transform,-.5f,-.5f,-.5f);
        return transform;
    }

    private void addFishingRod(int[] pixels) {
        addGeneratedItemModel(FISHING_ROD_POSE,pixels,ModItemSprites.SIZE,ModItemSprites.SIZE);
    }

    private void updateFishingLineTip() {
        if(viewCamera.mode!=0){runtime.entities.clearLocalFishingTip();return;}
        boolean left=!"FISHING_ROD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(runtime.inventory.selectedHotbarMaterial()));
        float[] m=FISHING_ROD_POSE;
        // The uppermost tip of the original cast sprite (pixels 13..14, row 1).
        float x=m[0]*(14f/16f)+m[4]*(14.5f/16f)+m[8]*.5f+m[12];
        float y=m[1]*(14f/16f)+m[5]*(14.5f/16f)+m[9]*.5f+m[13];
        float z=m[2]*(14f/16f)+m[6]*(14.5f/16f)+m[10]*.5f+m[14];
        if(left)x=-x;
        else {
            float swing=currentSwingProgress();double root=Math.sqrt(swing);
            x-=.4f*(float)Math.sin(root*Math.PI);
            y+=.2f*(float)Math.sin(root*Math.PI*2);
            z-=.2f*(float)Math.sin(swing*Math.PI);
        }
        float ratio=vanillaFirstPersonProjectionScale()/projectionScale();
        runtime.entities.localFishingTip(x*ratio,y*ratio,z,eyeHeight());
    }

    private void addGeneratedHeldItem(String material,int[] pixels) {
        float[] transform = vanillaRightHandItemPose(0f, 0f);
        rotateY(transform, -45f);
        float[] pose=VanillaItemSprites.transform(material);
        if(pose!=null) {
            translate(transform,pose[3]/16f,pose[4]/16f,pose[5]/16f);
            rotateX(transform,pose[0]);rotateY(transform,pose[1]);rotateZ(transform,pose[2]);
            scale(transform,pose[6],pose[7],pose[8]);
        } else {
            translate(transform,0f,0f,0f);rotateY(transform,-90f);
            scale(transform,.55f,.55f,.55f);
        }
        translate(transform, -.5f, -.5f, -.5f);
        addGeneratedItemModel(transform, pixels, ModItemSprites.SIZE,
                ModItemSprites.SIZE);
    }

    /**
     * Minecraft 1.20.1 ItemInHandRenderer's common right-hand pose.
     *
     * <p>LTW receives the same view-space coordinates used by Forge's
     * RenderHandEvent, so this matrix intentionally has no screen-space
     * correction. Every item renderer receives this same vanilla base;
     * renderers may only append their own model-local transforms.</p>
     */
    private static float[] vanillaRightHandItemPose(float equipProgress,
                                                     float swingProgress) {
        float[] transform = identityMatrix();
        float sqrtSwing = (float) Math.sqrt(Math.max(0f, swingProgress));
        translate(transform,
                -0.4f * (float) Math.sin(sqrtSwing * Math.PI),
                0.2f * (float) Math.sin(sqrtSwing * Math.PI * 2.0),
                -0.2f * (float) Math.sin(swingProgress * Math.PI));
        translate(transform, 0.56f,
                -0.52f - equipProgress * 0.6f, -0.72f);

        float swingSquared = (float) Math.sin(
                swingProgress * swingProgress * Math.PI);
        float swingRoot = (float) Math.sin(sqrtSwing * Math.PI);
        rotateY(transform, 45f - swingSquared * 20f);
        rotateZ(transform, -swingRoot * 20f);
        rotateX(transform, -swingRoot * 80f);
        return transform;
    }

    private void addGeneratedItemModel(float[] transform, int[] pixels,
                                       int width, int height) {
        // ItemModelGenerator uses z=7.5 and z=8.5 in a 0..16 model cube.
        float backZ = 7.5f / 16f;
        float frontZ = 8.5f / 16f;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = pixels[y * width + x];
                if (rgb == 0) continue;
                int argb = 0xff000000 | rgb;
                float left = x / (float) width;
                float right = (x + 1f) / width;
                float top = 1f - y / (float) height;
                float bottom = 1f - (y + 1f) / height;

                addFirstPersonQuad3d(transform,
                        left, top, frontZ, left, bottom, frontZ,
                        right, bottom, frontZ, right, top, frontZ, argb, 1f);
                addFirstPersonQuad3d(transform,
                        right, top, backZ, right, bottom, backZ,
                        left, bottom, backZ, left, top, backZ, argb, 0.82f);

                if (spritePixel(pixels, width, height, x - 1, y) == 0) {
                    addFirstPersonQuad3d(transform,
                            left, top, backZ, left, bottom, backZ,
                            left, bottom, frontZ, left, top, frontZ, argb, 0.70f);
                }
                if (spritePixel(pixels, width, height, x + 1, y) == 0) {
                    addFirstPersonQuad3d(transform,
                            right, top, frontZ, right, bottom, frontZ,
                            right, bottom, backZ, right, top, backZ, argb, 0.82f);
                }
                if (spritePixel(pixels, width, height, x, y - 1) == 0) {
                    addFirstPersonQuad3d(transform,
                            left, top, backZ, left, top, frontZ,
                            right, top, frontZ, right, top, backZ, argb, 0.90f);
                }
                if (spritePixel(pixels, width, height, x, y + 1) == 0) {
                    addFirstPersonQuad3d(transform,
                            left, bottom, frontZ, left, bottom, backZ,
                            right, bottom, backZ, right, bottom, frontZ, argb, 0.58f);
                }
            }
        }
    }

    private boolean addTexturedHeldBlock(String material) {
        if(HeldItemTextureRoute.select(material)!=HeldItemTextureRoute.BLOCK)return false;
        int state=com.newhorizon.thinclient.world.VanillaBlockTextures.defaultState(material);
        if(state<0)return false;
        float[] transform=vanillaRightHandItemPose(0f,0f);
        rotateY(transform,-45f);rotateY(transform,45f);
        scale(transform,.4f,.4f,.4f);translate(transform,-.5f,-.5f,-.5f);
        HeldBlockMesh.append(firstPersonVertices,state,transform);return true;
    }

    private void addFallbackHeldItem(String material) {
        float[] transform = vanillaRightHandItemPose(0f, 0f);
        rotateY(transform, -45f);
        translate(transform, 1.13f / 16f, 3.2f / 16f, 1.13f / 16f);
        rotateY(transform, -90f);
        rotateZ(transform, 25f);
        scale(transform, 0.68f, 0.68f, 0.68f);
        int hash = material.hashCode();
        int rgb = 0x505050 | ((hash >>> 3) & 0x7f7f7f);
        addSolidCuboid(transform, -0.16f, -0.26f, -0.06f,
                0.16f, 0.26f, 0.06f, 0xff000000 | rgb);
    }

    private void addSolidCuboid(float[] transform,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1, int argb) {
        addFirstPersonQuad3d(transform, x0, y0, z1, x0, y1, z1,
                x1, y1, z1, x1, y0, z1, argb, 1f);
        addFirstPersonQuad3d(transform, x1, y0, z0, x1, y1, z0,
                x0, y1, z0, x0, y0, z0, argb, 0.70f);
        addFirstPersonQuad3d(transform, x0, y0, z0, x0, y1, z0,
                x0, y1, z1, x0, y0, z1, argb, 0.78f);
        addFirstPersonQuad3d(transform, x1, y0, z1, x1, y1, z1,
                x1, y1, z0, x1, y0, z0, argb, 0.82f);
        addFirstPersonQuad3d(transform, x0, y0, z0, x0, y0, z1,
                x1, y0, z1, x1, y0, z0, argb, 0.90f);
        addFirstPersonQuad3d(transform, x0, y1, z1, x0, y1, z0,
                x1, y1, z0, x1, y1, z1, argb, 0.58f);
    }

    private void addFirstPersonQuad3d(float[] transform,
                                      float x0, float y0, float z0,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float x3, float y3, float z3,
                                      int argb, float shade) {
        if(firstPersonVertices.remaining()<6*FIRST_PERSON_VERTEX_FLOATS)return;
        putFirstPersonVertex3d(transform, x0, y0, z0, argb, shade);
        putFirstPersonVertex3d(transform, x1, y1, z1, argb, shade);
        putFirstPersonVertex3d(transform, x2, y2, z2, argb, shade);
        putFirstPersonVertex3d(transform, x0, y0, z0, argb, shade);
        putFirstPersonVertex3d(transform, x2, y2, z2, argb, shade);
        putFirstPersonVertex3d(transform, x3, y3, z3, argb, shade);
    }

    private void putFirstPersonVertex3d(float[] matrix, float x, float y, float z,
                                        int argb, float shade) {
        float transformedX = matrix[0] * x + matrix[4] * y + matrix[8] * z
                + matrix[12];
        float transformedY = matrix[1] * x + matrix[5] * y + matrix[9] * z
                + matrix[13];
        float transformedZ = matrix[2] * x + matrix[6] * y + matrix[10] * z
                + matrix[14];
        firstPersonVertices.put(transformedX).put(transformedY).put(transformedZ)
                .put(((argb >>> 16) & 0xff) / 255f * shade)
                .put(((argb >>> 8) & 0xff) / 255f * shade)
                .put((argb & 0xff) / 255f * shade)
                .put(((argb >>> 24) & 0xff) / 255f).put(-1f).put(-1f);
    }

    private static float[] identityMatrix() {
        float[] matrix = new float[16];
        matrix[0] = 1f;
        matrix[5] = 1f;
        matrix[10] = 1f;
        matrix[15] = 1f;
        return matrix;
    }

    private static void translate(float[] matrix, float x, float y, float z) {
        float[] translation = identityMatrix();
        translation[12] = x;
        translation[13] = y;
        translation[14] = z;
        multiply(matrix, translation);
    }

    private static void scale(float[] matrix, float x, float y, float z) {
        float[] scaling = identityMatrix();
        scaling[0] = x;
        scaling[5] = y;
        scaling[10] = z;
        multiply(matrix, scaling);
    }

    private static void rotateX(float[] matrix, float degrees) {
        float radians = (float) Math.toRadians(degrees);
        float sine = (float) Math.sin(radians);
        float cosine = (float) Math.cos(radians);
        float[] rotation = identityMatrix();
        rotation[5] = cosine;
        rotation[6] = sine;
        rotation[9] = -sine;
        rotation[10] = cosine;
        multiply(matrix, rotation);
    }

    private static void rotateY(float[] matrix, float degrees) {
        float radians = (float) Math.toRadians(degrees);
        float sine = (float) Math.sin(radians);
        float cosine = (float) Math.cos(radians);
        float[] rotation = identityMatrix();
        rotation[0] = cosine;
        rotation[2] = -sine;
        rotation[8] = sine;
        rotation[10] = cosine;
        multiply(matrix, rotation);
    }

    private static void rotateZ(float[] matrix, float degrees) {
        float radians = (float) Math.toRadians(degrees);
        float sine = (float) Math.sin(radians);
        float cosine = (float) Math.cos(radians);
        float[] rotation = identityMatrix();
        rotation[0] = cosine;
        rotation[1] = sine;
        rotation[4] = -sine;
        rotation[5] = cosine;
        multiply(matrix, rotation);
    }

    private static void multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                result[column * 4 + row] = left[row] * right[column * 4]
                        + left[4 + row] * right[column * 4 + 1]
                        + left[8 + row] * right[column * 4 + 2]
                        + left[12 + row] * right[column * 4 + 3];
            }
        }
        System.arraycopy(result, 0, left, 0, 16);
    }

    private static int spritePixel(int[] pixels, int width, int height, int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0;
        return pixels[y * width + x];
    }

    private void drawMinePadScreen(boolean offhand,float aspect){
        int id=minePads.heldBrowser(offhand);float[] m=minePadMatrices[offhand?1:0];if(id<0||m==null)return;
        DisplayGpu gpu=displayGpuFor(id,com.newhorizon.thinclient.display.MinePadController.WIDTH,com.newhorizon.thinclient.display.MinePadController.HEIGHT);
        if(gpu==null)return;gpu.seen=true;
        if(!gpu.registered)gpu.registered=GeckoNativeBridge.registerGpuConsumerTextures(id,gpu.width,gpu.height,gpu.textureIds);
        if(!gpu.registered)return;int texture=GeckoNativeBridge.gpuSharedTextureId(id,gpu.width,gpu.height);
        if(texture==-2){gpu.registered=false;return;}if(texture<=0||!gpu.owns(texture))return;
        drawMinePadTexture(m,offhand,aspect,texture);
    }

    private void drawMinePadTexture(float[] m,boolean offhand,float aspect,int texture){
        ScreenEffects.bind(displayShaderProgram);
        GL33.glUniform1i(GL33.glGetUniformLocation(displayShaderProgram,"uHand"),1);
        GL33.glUniform1f(displayAspectUniform,aspect);GL33.glUniform1f(displayProjectionUniform,vanillaFirstPersonProjectionScale());GL33.glUniform1i(displayTextureUniform,0);
        displayVertices.clear();
        for(int corner=0;corner<4;corner++){
            float x=(corner&1)==0?0:.8740625f,y=corner<2?.4395f:0,z=.001f;
            float px=m[0]*x+m[4]*y+m[8]*z+m[12],py=m[1]*x+m[5]*y+m[9]*z+m[13],pz=m[2]*x+m[6]*y+m[10]*z+m[14];
            putDisplayVertex(offhand?-px:px,py,pz,offhand?1-(corner&1):corner&1,corner<2?0:1);
        }
        displayVertices.flip();GL33.glBindVertexArray(displayVao);GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER,displayVbo);GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER,0,displayVertices);
        GL33.glActiveTexture(GL33.GL_TEXTURE0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,texture);
        // Keep the coplanar browser above the black model screen even on mobile depth buffers.
        GL33.glEnable(GL33.GL_POLYGON_OFFSET_FILL);GL33.glPolygonOffset(-1f,-4f);
        GL33.glDrawArrays(GL33.GL_TRIANGLE_STRIP,0,4);
        GL33.glDisable(GL33.GL_POLYGON_OFFSET_FILL);
        GL33.glUniform1i(GL33.glGetUniformLocation(displayShaderProgram,"uHand"),0);GL33.glBindTexture(GL33.GL_TEXTURE_2D,0);
    }

    private void drawDisplays(int framebufferWidth, int framebufferHeight) {
        for (DisplayGpu gpu : displayGpu) {
            if (gpu != null) gpu.seen = false;
        }
        if (displaySnapshotCount > 0) {
            ScreenEffects.bind(displayShaderProgram);
            GL33.glUniform1i(GL33.glGetUniformLocation(displayShaderProgram,"uHand"),0);
            GL33.glUniform3f(displayCameraUniform,(float)viewCamera.x,(float)viewCamera.y,(float)viewCamera.z);
            GL33.glUniform2f(displayRotationUniform, viewCamera.yaw, viewCamera.pitch);
            GL33.glUniform1f(displayAspectUniform,
                    framebufferWidth / (float) framebufferHeight);
            GL33.glUniform1f(displayProjectionUniform, projectionScale());
            GL33.glUniform1i(displayTextureUniform, 0);
            GL33.glBindVertexArray(displayVao);
            GL33.glDisable(GL33.GL_CULL_FACE);
            GL33.glEnable(GL33.GL_BLEND);
            GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
            GL33.glActiveTexture(GL33.GL_TEXTURE0);
            for (int index = 0; index < displaySnapshotCount; index++) {
                DisplayController.Renderable renderable = displaySnapshots[index];
                DisplayGpu gpu = displayGpuFor(renderable.browserId,
                        renderable.display.pixelWidth, renderable.display.pixelHeight);
                if (gpu == null) continue;
                gpu.seen = true;
                if (!gpu.registered) {
                    gpu.registered = GeckoNativeBridge.registerGpuConsumerTextures(
                            gpu.browserId, gpu.width, gpu.height, gpu.textureIds);
                }
                if (!gpu.registered) continue;
                int texture = GeckoNativeBridge.gpuSharedTextureId(
                        gpu.browserId, gpu.width, gpu.height);
                if (texture == -2) {
                    gpu.registered = false;
                    continue;
                }
                if (texture <= 0 || !gpu.owns(texture)) continue;
                putDisplayQuad(renderable.display);
                GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, displayVbo);
                GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, displayVertices);
                GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture);
                GL33.glDrawArrays(GL33.GL_TRIANGLE_STRIP, 0, 4);
                if (!gpu.frameLogged && displayFrameLogCount < MAX_DISPLAYS) {
                    gpu.frameLogged = true;
                    displayFrameLogCount++;
                    System.out.println("[NH-THIN] Gecko GPU display ready browser="
                            + gpu.browserId + " size=" + gpu.width + "x" + gpu.height);
                }
            }
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, 0);
            GL33.glDisable(GL33.GL_BLEND);
            GL33.glEnable(GL33.GL_CULL_FACE);
        }
        for (int index = 0; index < displayGpu.length; index++) {
            DisplayGpu gpu = displayGpu[index];
            // Gecko's overlay retires only the producer surface. Keep this consumer pool,
            // including its last complete frame, until the item itself is no longer held.
            if(gpu!=null&&(gpu.browserId==minePads.heldBrowser(false)||gpu.browserId==minePads.heldBrowser(true)))gpu.seen=true;
            if (gpu != null && !gpu.seen) {
                releaseDisplayGpu(gpu);
                displayGpu[index] = null;
            }
        }
    }

    private DisplayGpu displayGpuFor(int browserId, int width, int height) {
        int free = -1;
        for (int index = 0; index < displayGpu.length; index++) {
            DisplayGpu gpu = displayGpu[index];
            if (gpu == null) {
                if (free < 0) free = index;
                continue;
            }
            if (gpu.browserId == browserId) {
                if (gpu.width == width && gpu.height == height) return gpu;
                releaseDisplayGpu(gpu);
                displayGpu[index] = null;
                free = index;
                break;
            }
        }
        if (free < 0) return null;
        DisplayGpu gpu = new DisplayGpu(browserId, width, height);
        for (int index = 0; index < gpu.textureIds.length; index++) {
            int texture = GL33.glGenTextures();
            gpu.textureIds[index] = texture;
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER,
                    GL33.GL_LINEAR);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER,
                    GL33.GL_LINEAR);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_S,
                    GL33.GL_CLAMP_TO_EDGE);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_T,
                    GL33.GL_CLAMP_TO_EDGE);
            // The tablet page is opaque. In LOW_BPP, RGB8 becomes RGB565 instead
            // of RGBA4: more color precision at the same two bytes per pixel.
            boolean tablet=browserId==minePads.heldBrowser(false)||browserId==minePads.heldBrowser(true);
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, tablet?GL33.GL_RGB8:GL33.GL_RGBA8,
                    width, height, 0, tablet?GL33.GL_RGB:GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
        }
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, 0);
        displayGpu[free] = gpu;
        return gpu;
    }

    private void releaseDisplayGpu(DisplayGpu gpu) {
        GeckoNativeBridge.gpuSharedTextureId(gpu.browserId, 0, 0);
        for (int texture : gpu.textureIds) {
            if (texture != 0) GL33.glDeleteTextures(texture);
        }
    }

    private void putDisplayQuad(VirtualDisplay display) {
        float[] right = rightAxis(display.side);
        float[] up = upAxis(display.side);
        float[] forward = forwardAxis(display.side);
        float halfWidth = display.widthBlocks * 0.5f;
        float halfHeight = display.heightBlocks * 0.5f;
        float centerX = (float) display.x + forward[0] * 0.008f;
        float centerY = (float) display.y + forward[1] * 0.008f;
        float centerZ = (float) display.z + forward[2] * 0.008f;
        displayVertices.clear();
        putDisplayVertex(centerX - right[0] * halfWidth + up[0] * halfHeight,
                centerY - right[1] * halfWidth + up[1] * halfHeight,
                centerZ - right[2] * halfWidth + up[2] * halfHeight, 0f, 0f);
        putDisplayVertex(centerX + right[0] * halfWidth + up[0] * halfHeight,
                centerY + right[1] * halfWidth + up[1] * halfHeight,
                centerZ + right[2] * halfWidth + up[2] * halfHeight, 1f, 0f);
        putDisplayVertex(centerX - right[0] * halfWidth - up[0] * halfHeight,
                centerY - right[1] * halfWidth - up[1] * halfHeight,
                centerZ - right[2] * halfWidth - up[2] * halfHeight, 0f, 1f);
        putDisplayVertex(centerX + right[0] * halfWidth - up[0] * halfHeight,
                centerY + right[1] * halfWidth - up[1] * halfHeight,
                centerZ + right[2] * halfWidth - up[2] * halfHeight, 1f, 1f);
        displayVertices.flip();
    }

    private void putDisplayVertex(float x, float y, float z, float u, float v) {
        displayVertices.put(x).put(y).put(z).put(u).put(v);
    }

    private synchronized void queueMouseEvent(int button, int action) {
        int next = (mouseEventWrite + 1) % MOUSE_EVENT_CAPACITY;
        if (next == mouseEventRead) {
            mouseEventRead = (mouseEventRead + 1) % MOUSE_EVENT_CAPACITY;
        }
        mouseEventButtons[mouseEventWrite] = button;
        mouseEventActions[mouseEventWrite] = action;
        mouseEventWrite = next;
    }

    private void processMouseEvents() {
        int packed;
        while ((packed = pollMouseEvent()) >= 0) {
            int button = packed >>> 2;
            int action = packed & 3;
            processMouseEvent(button, action);
        }
        processHeldMouseActions();
    }

    private synchronized int pollMouseEvent() {
        if (mouseEventRead == mouseEventWrite) return -1;
        int button = mouseEventButtons[mouseEventRead];
        int action = mouseEventActions[mouseEventRead];
        mouseEventRead = (mouseEventRead + 1) % MOUSE_EVENT_CAPACITY;
        return (button << 2) | action;
    }

    private void processMouseEvent(int button, int action) {
        if(inventoryOpen||chatOpen||minePads.isOpen()||runtime.combat.dead()||runtime.riding.sleeping())return;
        boolean primary = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        if (action == GLFW.GLFW_PRESS) {
            if (primary) primaryHeld = true;
            else secondaryHeld = true;
            if(!primary){
                int padSlot=isMinePad(runtime.inventory.selectedHotbarMaterial())?runtime.inventory.selectedHotbar():isMinePad(runtime.inventory.offhandMaterial())?40:-1;
                if(padSlot>=0){minePads.open(runtime.inventory,padSlot,sneaking,System.nanoTime());
                    primaryHeld=secondaryHeld=false;releaseVanillaItem();stopVanillaAttack();java.util.Arrays.fill(pressedKeys,false);flightToggleRequested=false;return;}
            }
            if (processDisplayMousePress(primary)) return;
            if (primary) beginVanillaAttack();
            else useVanillaTarget();
            return;
        }

        if (primary) {primaryHeld = false;runtime.combat.releaseAttack();}
        else secondaryHeld = false;
        if (processDisplayMouseRelease(primary)) return;
        if (primary) stopVanillaAttack();
        else releaseVanillaItem();
    }

    private boolean mayInteractWithDisplay() {
        return com.newhorizon.thinclient.display.DisplayInteractionPolicy.allowed(
                runtime.inventory.selectedHotbarMaterial(),runtime.inventory.offhandMaterial(),
                inventoryOpen||chatOpen||minePads.isOpen()||runtime.riding.sleeping(),runtime.combat.dead());
    }
    private void updateDisplayInputPermission() {
        boolean allowed=mayInteractWithDisplay();
        if(displayLaserPermissionKnown&&allowed==displayLaserEnabled)return;
        displayLaserPermissionKnown=true;
        displayLaserEnabled=allowed;
        GeckoNativeBridge.displayLaser(allowed);
        if(!allowed) {
            if(activePrimaryBrowser>=0)GeckoNativeBridge.cancelDisplayInput(activePrimaryBrowser);
            if(activeSecondaryBrowser>=0)GeckoNativeBridge.cancelDisplayInput(activeSecondaryBrowser);
            activePrimaryBrowser=activeSecondaryBrowser=-1;
        }
    }

    private boolean processDisplayMousePress(boolean primary) {
            updateDisplayInputPermission();
            if(!mayInteractWithDisplay())return false;
            if (!findDisplayHit(displayHit)) {
                if (mouseEventLogCount < 12) {
                    mouseEventLogCount++;
                    System.out.println("[NH-THIN] display pointer miss view="
                            + cameraPosition[0] + "," + cameraPosition[1] + ","
                            + cameraPosition[2] + " yaw=" + cameraRotation[0]
                            + " pitch=" + cameraRotation[1]
                            + " visible=" + displaySnapshotCount);
                }
                return false;
            }
            if (mouseEventLogCount < 12) {
                mouseEventLogCount++;
                System.out.println("[NH-THIN] display pointer hit browser="
                        + displayHit.browserId + " x=" + displayHit.pixelX
                        + " y=" + displayHit.pixelY);
            }
            runtime.displays.focusBrowser(displayHit.browserId);
            int cefButton = primary ? 0 : 2;
            int modifiers = primary ? 16 : 64;
            GeckoNativeBridge.mouse(displayHit.browserId, 1,
                    displayHit.pixelX, displayHit.pixelY, 1, cefButton, modifiers);
            if (primary) {
                activePrimaryBrowser = displayHit.browserId;
                activePrimaryX = displayHit.pixelX;
                activePrimaryY = displayHit.pixelY;
            } else {
                activeSecondaryBrowser = displayHit.browserId;
                activeSecondaryX = displayHit.pixelX;
                activeSecondaryY = displayHit.pixelY;
            }
            return true;
    }

    private boolean processDisplayMouseRelease(boolean primary) {
            int browser = primary ? activePrimaryBrowser : activeSecondaryBrowser;
            if (browser < 0) return false;
            if(!mayInteractWithDisplay()) {
                GeckoNativeBridge.cancelDisplayInput(browser);
                if(primary)activePrimaryBrowser=-1;else activeSecondaryBrowser=-1;
                return true;
            }
            int x = primary ? activePrimaryX : activeSecondaryX;
            int y = primary ? activePrimaryY : activeSecondaryY;
            if (findDisplayHit(displayHit) && displayHit.browserId == browser) {
                x = displayHit.pixelX;
                y = displayHit.pixelY;
            }
            GeckoNativeBridge.mouse(browser, 0, x, y, 1,
                    primary ? 0 : 2, 0);
            if (primary) activePrimaryBrowser = -1;
            else activeSecondaryBrowser = -1;
            return true;
    }

    private void beginVanillaAttack() {
        long now=System.nanoTime();
        if(chargingItem||runtime.riding.sleeping()||connection.isSpectator()||!runtime.combat.mayAttack(now))return;
        findVanillaHit(vanillaHit);
        try {
            if (vanillaHit.type == VanillaHitResult.ENTITY) {
                connection.sendMovement(cameraPosition[0],cameraPosition[1],cameraPosition[2],cameraRotation[0],cameraRotation[1],onGround);
                connection.attackEntity(vanillaHit.entityId, sneaking);
                if(sprinting && runtime.combat.attackStrength(now,.5f)>.9f) {
                    horizontalVelocityX*=.6;horizontalVelocityZ*=.6;sprinting=false;sendVanillaMovementState();
                }
                runtime.combat.attacked(now,false,runtime.inventory.isCreativeMode());
                swingMainHand();
                logVanillaInteraction("attack entity=" + vanillaHit.entityId);
                return;
            }
            if (vanillaHit.type == VanillaHitResult.BLOCK) {
                expectBlockBreak(vanillaHit.blockX, vanillaHit.blockY,
                        vanillaHit.blockZ, System.nanoTime());
                connection.sendPlayerAction(ACTION_START_DESTROY_BLOCK,
                        vanillaHit.blockX, vanillaHit.blockY, vanillaHit.blockZ,
                        vanillaHit.direction);
                swingMainHand();
                heldHit.copyBlockFrom(vanillaHit);
                destroyingBlock = true;
                destroyStartNanos = System.nanoTime();
                nextMiningSoundNanos = destroyStartNanos;
                int breakTicks = vanillaHit.breakTicks > 0
                        ? vanillaHit.breakTicks : LEGACY_BREAK_TICKS;
                destroyRequiredNanos = breakTicks * 50_000_000L;
                nextDestroySwingNanos = destroyStartNanos
                        + DESTROY_SWING_INTERVAL_NANOS;
                logVanillaInteraction("start block=" + vanillaHit.blockX + ","
                        + vanillaHit.blockY + "," + vanillaHit.blockZ
                        + " face=" + vanillaHit.direction);
                return;
            }
            swingMainHand();
            runtime.combat.attacked(now,true,runtime.inventory.isCreativeMode());
            logVanillaInteraction("attack miss");
        } catch (IOException | ProtocolException exception) {
            throw new IllegalStateException("Unable to send vanilla attack", exception);
        }
    }

    private void stopVanillaAttack() {
        if (!destroyingBlock) return;
        try {
            int action = System.nanoTime() - destroyStartNanos >= destroyRequiredNanos
                    ? ACTION_STOP_DESTROY_BLOCK : ACTION_ABORT_DESTROY_BLOCK;
            connection.sendPlayerAction(action,
                    heldHit.blockX, heldHit.blockY, heldHit.blockZ,
                    heldHit.direction);
            logVanillaInteraction((action == ACTION_STOP_DESTROY_BLOCK
                    ? "stop block=" : "abort block=") + heldHit.blockX + ","
                    + heldHit.blockY + "," + heldHit.blockZ);
        } catch (IOException | ProtocolException exception) {
            throw new IllegalStateException("Unable to stop vanilla attack", exception);
        } finally {
            destroyingBlock = false;
        }
    }

    private void useVanillaTarget() {
        if(runtime.combat.dead()||runtime.riding.sleeping()||inventoryOpen||chatOpen||minePads.isOpen()||connection.isSpectator())return;
        if (chargingItem) return;
        String material=runtime.inventory.selectedHotbarMaterial();
        findVanillaHit(vanillaHit);
        chargingHand=0;
        if((!com.newhorizon.thinclient.world.ProjectileKind.airUseItem(material) || runtime.itemUse.coolingDown(material,System.nanoTime()))
                && "SHIELD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(runtime.inventory.offhandMaterial()))
                && !targetConsumesUse()
                && !(vanillaHit.type==VanillaHitResult.BLOCK && com.newhorizon.thinclient.world.ProjectileKind.blockUseItem(material))) {material=runtime.inventory.offhandMaterial();chargingHand=1;}
        if(!com.newhorizon.thinclient.world.ProjectileKind.airUseItem(material)
                && !(vanillaHit.type==VanillaHitResult.BLOCK && com.newhorizon.thinclient.world.ProjectileKind.blockUseItem(material))
                && "FISHING_ROD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(runtime.inventory.offhandMaterial()))) {
            material=runtime.inventory.offhandMaterial();chargingHand=1;
        }
        // Try food in the offhand when the main hand has no usable air action.
        String offhand=runtime.inventory.offhandMaterial();
        if((!com.newhorizon.thinclient.world.ProjectileKind.airUseItem(material)
                || ConsumableUse.durationTicks(material)>0&&!ConsumableUse.canStart(material,runtime.combat.food(),runtime.inventory.isCreativeMode()))
                && ConsumableUse.canStart(offhand,runtime.combat.food(),runtime.inventory.isCreativeMode())
                && !targetConsumesUse()
                && !(vanillaHit.type==VanillaHitResult.BLOCK&&com.newhorizon.thinclient.world.ProjectileKind.blockUseItem(material))) {
            material=offhand;chargingHand=1;
        }
        if(runtime.itemUse.coolingDown(material,System.nanoTime()))return;
        try {
            // The server launches with its last received rotation, so flush aim first.
            connection.sendMovement(cameraPosition[0],cameraPosition[1],cameraPosition[2],
                    cameraRotation[0],cameraRotation[1],onGround);
            int leadHand=com.newhorizon.thinclient.world.ProjectileKind.leadHand(runtime.inventory.selectedHotbarMaterial(),runtime.inventory.offhandMaterial());
            if(vanillaHit.type==VanillaHitResult.ENTITY && (leadHand>=0 || runtime.entities.typeOf(vanillaHit.entityId)==58
                    || runtime.entities.leashes.holder(vanillaHit.entityId)==connection.playerEntityId())) {
                connection.interactEntity(vanillaHit.entityId,sneaking,Math.max(0,leadHand));swingMainHand();
                nextUseNanos=System.nanoTime()+USE_REPEAT_INTERVAL_NANOS;return;
            }
            if(vanillaHit.type==VanillaHitResult.ENTITY && com.newhorizon.thinclient.world.RidingState.mountable(runtime.entities.typeOf(vanillaHit.entityId))){
                connection.interactEntity(vanillaHit.entityId,sneaking);nextUseNanos=System.nanoTime()+USE_REPEAT_INTERVAL_NANOS;return;
            }
            if (com.newhorizon.thinclient.world.ProjectileKind.airUseItem(material)
                    && !targetConsumesUse()) {
                if(!usePress.allows(material))return;
                if(ConsumableUse.durationTicks(material)>0
                        && !ConsumableUse.canStart(material,runtime.combat.food(),runtime.inventory.isCreativeMode()))return;
                useCompletionAtStart=runtime.combat.completedUses();
                if(connection.useHandItem(chargingHand)) {
                    usePress.used(material);
                    if("FISHING_ROD".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(material)))
                        System.out.println("[NH-FISHING] use hand="+chargingHand+" hookPresent="+runtime.entities.fishing(connection.playerEntityId())+" timeMs="+System.currentTimeMillis());
                    chargingItem=com.newhorizon.thinclient.world.ProjectileKind.chargedItem(material);
                    chargingMaterial=material; chargingSlot=runtime.inventory.selectedHotbar();
                    chargingGeneration=runtime.itemUse.generation();itemUseStarted=System.nanoTime();
                    lastConsumeTick=0;
                    consumeParticleItem=0;
                    if(ConsumableUse.durationTicks(material)>0&&!ConsumableUse.drink(material)) {
                        String key=com.newhorizon.thinclient.world.ProjectileKind.materialKey(material).toLowerCase(Locale.ROOT);
                        for(int id=1;id<1255;id++)if(key.equals(DroppedItemRenderer.name(id))){consumeParticleItem=id;break;}
                    }
                    runtime.combat.use(chargingItem,chargingHand==1);
                    if(!chargingItem) swingMainHand();
                    logVanillaInteraction("projectile use="+material+" held="+chargingItem);
                }
                nextUseNanos=System.nanoTime()+USE_REPEAT_INTERVAL_NANOS;
                return;
            }
            if (vanillaHit.type == VanillaHitResult.ENTITY) {
                connection.interactEntity(vanillaHit.entityId, sneaking);
                logVanillaInteraction("use entity=" + vanillaHit.entityId);
            } else if (vanillaHit.type == VanillaHitResult.BLOCK) {
                localSounds.expectPlace(vanillaHit.blockX, vanillaHit.blockY, vanillaHit.blockZ,
                        vanillaHit.direction, runtime.inventory.selectedHotbarMaterial(), System.nanoTime());
                boolean used=connection.useItemOn(vanillaHit.blockX, vanillaHit.blockY,
                        vanillaHit.blockZ, vanillaHit.direction,
                        vanillaHit.hitX - vanillaHit.blockX,
                        vanillaHit.hitY - vanillaHit.blockY,
                        vanillaHit.hitZ - vanillaHit.blockZ,
                        false);
                if(used)swingMainHand();
                logVanillaInteraction("use block=" + vanillaHit.blockX + ","
                        + vanillaHit.blockY + "," + vanillaHit.blockZ
                        + " face=" + vanillaHit.direction);
            } else {
                connection.useMainHandItem();
                logVanillaInteraction("use item");
            }
            nextUseNanos = System.nanoTime() + USE_REPEAT_INTERVAL_NANOS;
        } catch (IOException | ProtocolException exception) {
            throw new IllegalStateException("Unable to send vanilla use", exception);
        }
    }

    private void expectBlockBreak(int x,int y,int z,long now) {
        localSounds.expectBreak(x,y,z,now);
        runtime.entities.worldParticles.expectBreak(x,y,z,runtime.world.blockStateAt(x,y,z),now);
    }
    private void processHeldMouseActions() {
        long now = System.nanoTime();
        if(chargingItem&&ConsumableUse.durationTicks(chargingMaterial)>0
                && runtime.combat.completedUses()!=useCompletionAtStart) {
            // Entity event 9 confirms completion even if creative mode keeps the stack unchanged.
            // Never finish early just because the local animation reached its final frame.
            logVanillaInteraction("consume complete="+chargingMaterial+" elapsedMs="+(now-itemUseStarted)/1_000_000);
            consumeCrumbs(now,16);
            chargingItem=false;runtime.combat.use(false,false);
            nextUseNanos=now+USE_REPEAT_INTERVAL_NANOS;
        }
        if(chargingItem && (inventoryOpen || !secondaryHeld
                || chargingSlot!=runtime.inventory.selectedHotbar()
                || !chargingMaterial.equals(chargingHand==1?runtime.inventory.offhandMaterial():runtime.inventory.selectedHotbarMaterial())
                || runtime.itemUse.coolingDown(chargingMaterial,now)
                || chargingGeneration!=runtime.itemUse.generation())) releaseVanillaItem();
        updateConsumeEffects(now);
        if (primaryHeld && activePrimaryBrowser < 0 && destroyingBlock
                && !runtime.inventory.isCreativeMode() && now >= nextMiningSoundNanos) {
            localSounds.hitBlock(heldHit.blockX, heldHit.blockY, heldHit.blockZ);
            runtime.entities.worldParticles.hitBlock(runtime.world,heldHit.blockX,heldHit.blockY,heldHit.blockZ,heldHit.direction,now);
            nextMiningSoundNanos = now + 200_000_000L;
        }
        if (primaryHeld && activePrimaryBrowser < 0 && destroyingBlock
                && now >= nextDestroySwingNanos) {
            findVanillaHit(vanillaHit);
            try {
                if (!vanillaHit.sameBlock(heldHit)) {
                    connection.sendPlayerAction(ACTION_ABORT_DESTROY_BLOCK,
                            heldHit.blockX, heldHit.blockY, heldHit.blockZ,
                            heldHit.direction);
                    destroyingBlock = false;
                    if (vanillaHit.type == VanillaHitResult.BLOCK) {
                        expectBlockBreak(vanillaHit.blockX, vanillaHit.blockY,
                                vanillaHit.blockZ, now);
                        connection.sendPlayerAction(ACTION_START_DESTROY_BLOCK,
                                vanillaHit.blockX, vanillaHit.blockY,
                                vanillaHit.blockZ, vanillaHit.direction);
                        heldHit.copyBlockFrom(vanillaHit);
                        destroyingBlock = true;
                        destroyStartNanos = now;
                        int breakTicks = vanillaHit.breakTicks > 0
                                ? vanillaHit.breakTicks : LEGACY_BREAK_TICKS;
                        destroyRequiredNanos = breakTicks * 50_000_000L;
                    }
                }
                if (destroyingBlock) swingMainHand();
            } catch (IOException | ProtocolException exception) {
                throw new IllegalStateException("Unable to continue vanilla attack", exception);
            }
            nextDestroySwingNanos = now + DESTROY_SWING_INTERVAL_NANOS;
        }
        if (primaryHeld && activePrimaryBrowser < 0 && destroyingBlock
                && now - destroyStartNanos >= destroyRequiredNanos) {
            try {
                expectBlockBreak(heldHit.blockX, heldHit.blockY, heldHit.blockZ, now);
                connection.sendPlayerAction(ACTION_STOP_DESTROY_BLOCK,
                        heldHit.blockX, heldHit.blockY, heldHit.blockZ,
                        heldHit.direction);
                swingMainHand();
                logVanillaInteraction("complete block=" + heldHit.blockX + ","
                        + heldHit.blockY + "," + heldHit.blockZ);
            } catch (IOException | ProtocolException exception) {
                throw new IllegalStateException("Unable to finish vanilla attack", exception);
            }
            destroyingBlock = false;
        }
        if (!inventoryOpen && secondaryHeld && !chargingItem
                && activeSecondaryBrowser < 0 && now >= nextUseNanos) {
            useVanillaTarget();
        }
    }

    private void releaseVanillaItem() {
        usePress.release();
        runtime.combat.use(false,false);
        if(!chargingItem) return;
        chargingItem=false;
        try {
            if(chargingGeneration!=runtime.itemUse.generation()) return;
            connection.sendMovement(cameraPosition[0],cameraPosition[1],cameraPosition[2],
                    cameraRotation[0],cameraRotation[1],onGround);
            connection.sendPlayerAction(5,0,0,0,0); // RELEASE_USE_ITEM, BlockPos.ZERO, DOWN
            logVanillaInteraction("item release="+chargingMaterial+" elapsedMs="+(System.nanoTime()-itemUseStarted)/1_000_000);
        } catch(IOException | ProtocolException error) {
            throw new IllegalStateException("Unable to release item",error);
        }
    }

    private void updateConsumeEffects(long now) {
        int duration=chargingItem?ConsumableUse.durationTicks(chargingMaterial):0;
        if(duration==0)return;
        int tick=(int)Math.min(duration,(now-itemUseStarted)/50_000_000L);
        if(tick==lastConsumeTick)return;
        lastConsumeTick=tick;
        int remaining=duration-tick;
        if(tick<7||remaining<=0||remaining%4!=0)return;
        boolean drink=ConsumableUse.drink(chargingMaterial);
        String sound=drink?("HONEY_BOTTLE".equals(com.newhorizon.thinclient.world.ProjectileKind.materialKey(chargingMaterial))
                ?"minecraft:item.honey_bottle.drink":"minecraft:entity.generic.drink"):"minecraft:entity.generic.eat";
        runtime.sounds.playRelative(sound,com.newhorizon.thinclient.audio.SoundEventQueue.PLAYERS,
                drink?.5f:1f,1f,now);
        if(!drink)consumeCrumbs(now,5);
    }
    private void consumeCrumbs(long now,int count) {
        if(consumeParticleItem>0)runtime.entities.worldParticles.eat(consumeParticleItem,
                renderCameraPosition[0],renderCameraPosition[1]+eyeHeight(),renderCameraPosition[2],
                cameraRotation[0],cameraRotation[1],count,now);
    }

    private boolean targetConsumesUse() {
        if(sneaking || vanillaHit.type!=VanillaHitResult.BLOCK) return false;
        String name=com.newhorizon.thinclient.audio.VanillaSoundCatalog.load().blockName(
                runtime.world.blockStateAt(vanillaHit.blockX,vanillaHit.blockY,vanillaHit.blockZ));
        if(name==null) return false;
        if(name.endsWith("_fence") && runtime.entities.leashes.hasHolder(connection.playerEntityId()))return true;
        return name.endsWith("_door") || name.endsWith("_trapdoor") || name.endsWith("_button")
                || name.endsWith("_fence_gate") || name.endsWith("_shulker_box")
                || name.endsWith("chest") || name.endsWith("furnace") || name.endsWith("_sign")
                || name.endsWith("crafting_table") || name.endsWith("enchanting_table")
                || name.endsWith("anvil") || name.endsWith("barrel") || name.endsWith("hopper")
                || name.endsWith("dispenser") || name.endsWith("dropper") || name.endsWith("lever")
                || name.endsWith("jukebox") || name.endsWith("brewing_stand")
                || name.endsWith("smoker") || name.endsWith("stonecutter") || name.endsWith("loom")
                || name.endsWith("cartography_table") || name.endsWith("smithing_table")
                || name.endsWith("grindstone") || name.endsWith("_bed");
    }

    private void findVanillaHit(VanillaHitResult output) {
        output.clear();
        double yaw = Math.toRadians(cameraRotation[0]);
        double pitch = Math.toRadians(cameraRotation[1]);
        double horizontal = Math.cos(pitch);
        double rayX = -Math.sin(yaw) * horizontal;
        double rayY = -Math.sin(pitch);
        double rayZ = Math.cos(yaw) * horizontal;
        double originX = renderCameraPosition[0];
        double originY = renderCameraPosition[1] + eyeHeight();
        double originZ = renderCameraPosition[2];

        raycastBlock(originX, originY, originZ, rayX, rayY, rayZ, output);
        double entityLimit = Math.min(runtime.inventory.isCreativeMode()?6.0:3.0,
                output.type == VanillaHitResult.BLOCK ? output.distance : 6.0);
        runtime.entities.findRayHit(originX, originY, originZ,
                rayX, rayY, rayZ, entityLimit,
                connection.playerEntityId(), entityHit);
        if (entityHit.entityId >= 0 && entityHit.distance < entityLimit) {
            output.type = VanillaHitResult.ENTITY;
            output.entityId = entityHit.entityId;
            output.distance = entityHit.distance;
        }
    }

    private void raycastBlock(double originX, double originY, double originZ,
                              double rayX, double rayY, double rayZ,
                              VanillaHitResult output) {
        int previousX = floor(originX);
        int previousY = floor(originY);
        int previousZ = floor(originZ);
        for (double distance = BLOCK_RAY_STEP;
             distance <= (runtime.inventory.isCreativeMode()?5.0:VANILLA_INTERACTION_RANGE);
             distance += BLOCK_RAY_STEP) {
            double hitX = originX + rayX * distance;
            double hitY = originY + rayY * distance;
            double hitZ = originZ + rayZ * distance;
            int blockX = floor(hitX);
            int blockY = floor(hitY);
            int blockZ = floor(hitZ);
            boolean enteredNewCell = blockX != previousX || blockY != previousY
                    || blockZ != previousZ;
            if (enteredNewCell
                    && runtime.world.isTargetableBlock(blockX, blockY, blockZ)) {
                int state=runtime.world.blockStateAt(blockX,blockY,blockZ);
                boolean rail=com.newhorizon.thinclient.world.RailState.isRail(state);
                double exact=rail ? com.newhorizon.thinclient.world.RailState.hit(state,originX,originY,originZ,rayX,rayY,rayZ,
                        blockX,blockY,blockZ,runtime.inventory.isCreativeMode()?5.0:VANILLA_INTERACTION_RANGE) : distance;
                if(exact<0) { previousX=blockX;previousY=blockY;previousZ=blockZ;continue; }
                if(rail) { hitX=originX+rayX*exact;hitY=originY+rayY*exact;hitZ=originZ+rayZ*exact; }
                output.type = VanillaHitResult.BLOCK;
                output.blockX = blockX;
                output.blockY = blockY;
                output.blockZ = blockZ;
                output.direction = enteredFace(previousX, previousY, previousZ,
                        blockX, blockY, blockZ, rayX, rayY, rayZ);
                output.hitX = (float) hitX;
                output.hitY = (float) hitY;
                output.hitZ = (float) hitZ;
                if(rail) {
                    if(Math.abs(hitY-blockY-com.newhorizon.thinclient.world.RailState.outlineHeight(state))<1e-6)output.direction=1;
                    else if(Math.abs(hitY-blockY)<1e-6)output.direction=0;
                }
                output.distance = exact;
                output.breakTicks = runtime.world.breakTicksAt(
                        blockX, blockY, blockZ);
                return;
            }
            previousX = blockX;
            previousY = blockY;
            previousZ = blockZ;
        }
    }

    private static int enteredFace(int previousX, int previousY, int previousZ,
                                   int blockX, int blockY, int blockZ,
                                   double rayX, double rayY, double rayZ) {
        if (blockY != previousY) return rayY > 0.0 ? 0 : 1; // DOWN / UP
        if (blockZ != previousZ) return rayZ > 0.0 ? 2 : 3; // NORTH / SOUTH
        if (blockX != previousX) return rayX > 0.0 ? 4 : 5; // WEST / EAST
        double localX = Math.abs((blockX + 0.5) - (previousX + 0.5));
        double localY = Math.abs((blockY + 0.5) - (previousY + 0.5));
        double localZ = Math.abs((blockZ + 0.5) - (previousZ + 0.5));
        if (localY >= localX && localY >= localZ) return rayY > 0.0 ? 0 : 1;
        if (localZ >= localX) return rayZ > 0.0 ? 2 : 3;
        return rayX > 0.0 ? 4 : 5;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private void logVanillaInteraction(String message) {
        if (mouseEventLogCount >= 24) return;
        mouseEventLogCount++;
        System.out.println("[NH-THIN] vanilla " + message);
    }

    private boolean findDisplayHit(HitResult output) {
        output.browserId = -1;
        output.distance = DISPLAY_INTERACTION_RANGE + 1.0;
        double yaw = Math.toRadians(cameraRotation[0]);
        double pitch = Math.toRadians(cameraRotation[1]);
        double horizontal = Math.cos(pitch);
        double rayX = -Math.sin(yaw) * horizontal;
        double rayY = -Math.sin(pitch);
        double rayZ = Math.cos(yaw) * horizontal;
        double originX = renderCameraPosition[0];
        double originY = renderCameraPosition[1] + eyeHeight();
        double originZ = renderCameraPosition[2];
        double nearest = DISPLAY_INTERACTION_RANGE + 1.0;
        for (int index = 0; index < displaySnapshotCount; index++) {
            DisplayController.Renderable renderable = displaySnapshots[index];
            VirtualDisplay display = renderable.display;
            float[] right = rightAxis(display.side);
            float[] up = upAxis(display.side);
            float[] forward = forwardAxis(display.side);
            double centerX = display.x + forward[0] * 0.008;
            double centerY = display.y + forward[1] * 0.008;
            double centerZ = display.z + forward[2] * 0.008;
            double denominator = rayX * forward[0] + rayY * forward[1]
                    + rayZ * forward[2];
            if (Math.abs(denominator) < 1.0e-6) continue;
            double distance = ((centerX - originX) * forward[0]
                    + (centerY - originY) * forward[1]
                    + (centerZ - originZ) * forward[2]) / denominator;
            if (distance < 0.05 || distance > DISPLAY_INTERACTION_RANGE
                    || distance >= nearest) continue;
            double hitX = originX + rayX * distance - centerX;
            double hitY = originY + rayY * distance - centerY;
            double hitZ = originZ + rayZ * distance - centerZ;
            double localRight = hitX * right[0] + hitY * right[1] + hitZ * right[2];
            double localUp = hitX * up[0] + hitY * up[1] + hitZ * up[2];
            if (Math.abs(localRight) > display.widthBlocks * 0.5
                    || Math.abs(localUp) > display.heightBlocks * 0.5) continue;
            nearest = distance;
            output.browserId = renderable.browserId;
            output.distance = distance;
            output.pixelX = clamp((int) ((localRight / display.widthBlocks + 0.5)
                    * display.pixelWidth), 0, display.pixelWidth - 1);
            output.pixelY = clamp((int) ((0.5 - localUp / display.heightBlocks)
                    * display.pixelHeight), 0, display.pixelHeight - 1);
        }
        return output.browserId >= 0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private float eyeHeight() {
        return runtime.riding.sleeping()?.5f:mountedId>=0?STANDING_EYE_HEIGHT:swimming.prone?.4f:sneaking ? SNEAKING_EYE_HEIGHT : STANDING_EYE_HEIGHT;
    }

    private float projectionScale() {
        double modifier=(runtime.effects.movementSpeed()/.1+1)/2;
        if(!Double.isFinite(modifier) || modifier<=0) modifier=1;
        modifier=Math.max(.5,Math.min(1.5,modifier));
        return (sprinting ? 1.55f : 1.7320508f)/(float)modifier;
    }

    /** fov:0.0 in the original profile maps to Minecraft's default 70 degrees. */
    private static float vanillaFirstPersonProjectionScale() {
        return 1.428148f; // 1 / tan(70 deg / 2)
    }

    private static float[] rightAxis(DisplaySide side) {
        switch (side) {
            case NORTH: return AXIS_NEGATIVE_X;
            case SOUTH: return AXIS_POSITIVE_X;
            case WEST: return AXIS_POSITIVE_Z;
            case EAST: return AXIS_NEGATIVE_Z;
            default: return AXIS_POSITIVE_X;
        }
    }

    private static float[] upAxis(DisplaySide side) {
        switch (side) {
            case BOTTOM:
            case TOP: return AXIS_NEGATIVE_Z;
            default: return AXIS_POSITIVE_Y;
        }
    }

    private static float[] forwardAxis(DisplaySide side) {
        switch (side) {
            case BOTTOM: return AXIS_NEGATIVE_Y;
            case TOP: return AXIS_POSITIVE_Y;
            case NORTH: return AXIS_NEGATIVE_Z;
            case SOUTH: return AXIS_POSITIVE_Z;
            case WEST: return AXIS_NEGATIVE_X;
            case EAST: return AXIS_POSITIVE_X;
            default: return AXIS_POSITIVE_Y;
        }
    }

    private static int linkProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GL33.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GL33.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GL33.glCreateProgram();
        GL33.glAttachShader(program, vertex);
        GL33.glAttachShader(program, fragment);
        GL33.glBindAttribLocation(program, 0, "aPosition");
        GL33.glBindAttribLocation(program, 1, "aColor");
        GL33.glBindAttribLocation(program, 2, "aUv");
        GL33.glBindAttribLocation(program, 2, "aLight");
        GL33.glLinkProgram(program);
        if (GL33.glGetProgrami(program, GL33.GL_LINK_STATUS) == GL33.GL_FALSE) {
            throw new IllegalStateException("Shader link failed: "
                    + GL33.glGetProgramInfoLog(program, 4096));
        }
        GL33.glDeleteShader(vertex);
        GL33.glDeleteShader(fragment);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GL33.glCreateShader(type);
        GL33.glShaderSource(shader, ScreenEffects.shader(type,source));
        GL33.glCompileShader(shader);
        if (GL33.glGetShaderi(shader, GL33.GL_COMPILE_STATUS) == GL33.GL_FALSE) {
            throw new IllegalStateException("Shader compile failed: "
                    + GL33.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }

    @Override
    public void close() {
        if (released) return;
        released = true;
        stopRequested = true;
        meshWorker.close();
        GeckoNativeBridge.hideInventory();
        GeckoNativeBridge.hideHotbar();
        GeckoNativeBridge.resetChat();
        minePads.close();
        if (glInitialized) {
            for (DisplayGpu gpu : displayGpu) {
                if (gpu != null) releaseDisplayGpu(gpu);
            }
            for (GpuChunk chunk : gpuChunks) {
                if (chunk != null) releaseGpuChunk(chunk);
            }
            if(uploadGpu!=null)releaseGpuChunk(uploadGpu);
            if (vao != 0) GL33.glDeleteVertexArrays(vao);
            railAtlas.close();blockAtlas.close();biomeTints.close();
            if(breakingVbo!=0)GL33.glDeleteBuffers(breakingVbo);
            if (shaderProgram != 0) ScreenEffects.deleteProgram(shaderProgram);
            if (displayVbo != 0) GL33.glDeleteBuffers(displayVbo);
            if (displayVao != 0) GL33.glDeleteVertexArrays(displayVao);
            if (displayShaderProgram != 0) ScreenEffects.deleteProgram(displayShaderProgram);
            if (firstPersonVbo != 0) GL33.glDeleteBuffers(firstPersonVbo);
            if (firstPersonVao != 0) GL33.glDeleteVertexArrays(firstPersonVao);
            if (firstPersonShaderProgram != 0) ScreenEffects.deleteProgram(firstPersonShaderProgram);
            if (selectionVbo != 0) GL33.glDeleteBuffers(selectionVbo);
            if (selectionVao != 0) GL33.glDeleteVertexArrays(selectionVao);


            if (selectionShaderProgram != 0) ScreenEffects.deleteProgram(selectionShaderProgram);
            entityRenderer.close();
            environmentRenderer.close();
        }
        if (window != 0) {
            GLFW.glfwDestroyWindow(window);
            window = 0;
        }
        GLFW.glfwTerminate();
        SceneAtmosphere.reset();
        // GPU/direct allocations are admitted only once and die with the JVM.
    }

    private static final class GpuChunk {
        final int vbo;
        final int lightVbo;
        MemoryBudget.Lease lease;
        int capacityBytes;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        int revision = Integer.MIN_VALUE;
        long epoch=Long.MIN_VALUE;
        int vertexCount;

        GpuChunk(int vbo,int lightVbo, MemoryBudget.Lease lease, int capacityBytes) {
            this.vbo = vbo;
            this.lightVbo=lightVbo;
            this.lease = lease;
            this.capacityBytes = capacityBytes;
        }
    }

    private static final class DisplayGpu {
        final int browserId;
        final int width;
        final int height;
        final int[] textureIds = new int[GPU_TEXTURES_PER_DISPLAY];
        boolean registered;
        boolean seen;
        boolean frameLogged;

        DisplayGpu(int browserId, int width, int height) {
            this.browserId = browserId;
            this.width = width;
            this.height = height;
        }

        boolean owns(int texture) {
            for (int candidate : textureIds) {
                if (candidate == texture) return true;
            }
            return false;
        }
    }

    private static final class HitResult {
        int browserId = -1;
        int pixelX;
        int pixelY;
        double distance;
    }

    private static final class VanillaHitResult {
        static final int NONE = 0;
        static final int BLOCK = 1;
        static final int ENTITY = 2;
        int type;
        int blockX;
        int blockY;
        int blockZ;
        int direction;
        int entityId = -1;
        int breakTicks;
        float hitX;
        float hitY;
        float hitZ;
        double distance;

        void clear() {
            type = NONE;
            entityId = -1;
            breakTicks = 0;
            distance = VANILLA_INTERACTION_RANGE + 1.0;
        }

        void copyBlockFrom(VanillaHitResult source) {
            type = BLOCK;
            blockX = source.blockX;
            blockY = source.blockY;
            blockZ = source.blockZ;
            direction = source.direction;
            hitX = source.hitX;
            hitY = source.hitY;
            hitZ = source.hitZ;
            distance = source.distance;
            breakTicks = source.breakTicks;
        }

        boolean sameBlock(VanillaHitResult other) {
            return type == BLOCK && other.type == BLOCK
                    && blockX == other.blockX && blockY == other.blockY
                    && blockZ == other.blockZ;
        }
    }

    private static final float[] AXIS_POSITIVE_X = {1f, 0f, 0f};
    private static final float[] AXIS_NEGATIVE_X = {-1f, 0f, 0f};
    private static final float[] AXIS_POSITIVE_Y = {0f, 1f, 0f};
    private static final float[] AXIS_NEGATIVE_Y = {0f, -1f, 0f};
    private static final float[] AXIS_POSITIVE_Z = {0f, 0f, 1f};
    private static final float[] AXIS_NEGATIVE_Z = {0f, 0f, -1f};
    private static final String VERTEX_SHADER = TerrainShaders.VERTEX;
    private static final String FRAGMENT_SHADER = TerrainShaders.FRAGMENT;

    private static final String DISPLAY_VERTEX_SHADER =
            "#version 150 core\n"
            + "in vec3 aPosition;\n"
            + "in vec2 aTexCoord;\n"
            + "out vec2 vTexCoord;\n"
            + "uniform vec3 uCamera;\n"
             + "uniform vec2 uRotation;\n"
             + "uniform float uAspect;\n"
             + "uniform float uProjection;\n"
             + "uniform int uHand;\n"
            + "void main() {\n"
            + "  if(uHand!=0){float n=0.05;float farZ=4096.0;vec3 p=aPosition;\n"
            + "    gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(farZ+n)/(farZ-n)*p.z-(2.0*farZ*n)/(farZ-n),-p.z);vTexCoord=aTexCoord;return;}\n"
            + "  vec3 p = aPosition; if(uHand==0){p-=uCamera;\n"
            + "  float y = radians(-uRotation.x);\n"
            + "  p = vec3(cos(y)*p.x - sin(y)*p.z, p.y, sin(y)*p.x + cos(y)*p.z);\n"
            + "  p.x = -p.x;\n"
            + "  p.z = -p.z;\n"
            + "  float x = radians(uRotation.y);\n"
            + "  p = vec3(p.x, cos(x)*p.y - sin(x)*p.z, sin(x)*p.y + cos(x)*p.z);}\n"
             + "  float f = uProjection;\n"
            + "  float n = 0.05; float farZ = uHand==0?192.0:4096.0;\n"
            + "  gl_Position = vec4(p.x*f/uAspect, p.y*f,"
            + " -(farZ+n)/(farZ-n)*p.z-(2.0*farZ*n)/(farZ-n), -p.z);\n"
            + "  vTexCoord = aTexCoord;\n"
            + "}\n";

    private static final String DISPLAY_FRAGMENT_SHADER =
            "#version 150 core\n"
            + "in vec2 vTexCoord;\n"
            + "out vec4 fragColor;\n"
            + "uniform sampler2D uTexture;\n"
            + "void main() { fragColor = texture(uTexture, vTexCoord); }\n";

    private static final String FIRST_PERSON_VERTEX_SHADER =
            "#version 150 core\n"
            + "in vec3 aPosition;\n"
            + "in vec4 aColor;in vec2 aUv;out vec2 vUv;\n"
            + "out vec4 vColor;\n"
            + "uniform float uAspect;\n"
            + "uniform float uProjection;\n"
            + "uniform float uSwing;\n"
            + "uniform vec3 uEat;\n"
            + "uniform int uHandKind;\n"
            + "vec3 rx(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(p.x,p.y*c-p.z*s,p.y*s+p.z*c);}\n"
            + "vec3 ry(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(p.x*c+p.z*s,p.y,-p.x*s+p.z*c);}\n"
            + "vec3 rz(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(p.x*c-p.y*s,p.x*s+p.y*c,p.z);}\n"
            + "void main() {\n"
            + "  float n = 0.05; float farZ = 4096.0;\n"
            + "  vec3 p = aPosition;\n"
            // Undo the cached idle pose, then apply ItemInHandRenderer's articulated swing.
            + "  float swing=clamp(uSwing,0.0,1.0),root=sqrt(swing);\n"
            + "  float wave=sin(root*3.14159265),squared=sin(swing*swing*3.14159265);\n"
            + "  if(uHandKind==1){vec3 pivot=vec3(.56,-.52,-.72);p=ry(p-pivot,radians(-45.0));\n"
            + "    p=rx(p,radians(-80.0*wave));p=rz(p,radians(-20.0*wave));p=ry(p,radians(45.0-20.0*squared));\n"
            + "    p+=pivot+vec3(-.4*wave,.2*sin(root*6.28318530),-.2*sin(swing*3.14159265));}\n"
            + "  else if(uHandKind==2){vec3 pivot=vec3(.64000005,-.6,-.71999997);p=ry(p-pivot,radians(-45.0));\n"
            + "    p=rz(p,radians(-20.0*squared));p=ry(p,radians(45.0+70.0*wave));\n"
            + "    p+=pivot+vec3(-.3*wave,.4*sin(root*6.28318530),-.4*sin(swing*3.14159265));}\n"
            // applyEatTransform precedes the ordinary hand/model pose. No per-frame mesh rebuild.
            + "  float f=uEat.x, side=uEat.z;float a=radians(side*f*30.0);\n"
            + "  p.xy=mat2(cos(a),sin(a),-sin(a),cos(a))*p.xy;\n"
            + "  a=radians(f*10.0);p.yz=mat2(cos(a),sin(a),-sin(a),cos(a))*p.yz;\n"
            + "  a=radians(side*f*90.0);p.xz=mat2(cos(a),-sin(a),sin(a),cos(a))*p.xz;\n"
            + "  p+=vec3(side*f*0.6,uEat.y-f*0.5,0.0);\n"
            + "  gl_Position = vec4(p.x*uProjection/uAspect, p.y*uProjection,"
            + " -(farZ+n)/(farZ-n)*p.z-(2.0*farZ*n)/(farZ-n), -p.z);\n"
            + "  vColor = aColor;vUv=aUv;\n"
            + "}\n";

    private static final String FIRST_PERSON_FRAGMENT_SHADER =
            "#version 150 core\n"
            + "in vec4 vColor;in vec2 vUv;uniform sampler2D uBlockAtlas;uniform int uGrassSide,uGrassOverlay;\n"
            + "out vec4 fragColor;\n"
            + "void main() { fragColor = vColor;if(vUv.x>=0.){vec4 texel=texture(uBlockAtlas,vUv);if(texel.a<.1)discard;vec2 cells=vUv*vec2(32.,64.);int tile=int(floor(cells.y))*32+int(floor(cells.x));if(tile==uGrassSide){vec2 uv=(vec2(float(uGrassOverlay%32),float(uGrassOverlay/32))+fract(cells))/vec2(32.,64.);vec4 overlay=texture(uBlockAtlas,uv);texel.rgb=mix(texel.rgb,overlay.rgb*vec3(.486275,.741176,.419608),overlay.a);}fragColor*=texel;}if(vUv.x> -1.5)fragColor.rgb*=nhLight(nhEyeLight); }\n";

    private static final String SELECTION_FRAGMENT_SHADER =
            "#version 150 core\n"
            + "in vec4 vColor;\n"
            + "out vec4 fragColor;\n"
            + "void main() { fragColor = vColor; }\n";
}
