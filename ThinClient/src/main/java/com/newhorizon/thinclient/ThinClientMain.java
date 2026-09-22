package com.newhorizon.thinclient;

import com.newhorizon.thinclient.display.DisplayMessage;
import com.newhorizon.thinclient.display.GeckoNativeBridge;
import com.newhorizon.thinclient.protocol.MinecraftConnection;
import com.newhorizon.thinclient.render.LtwScene;
import com.newhorizon.thinclient.audio.ThinSoundEngine;
import com.newhorizon.thinclient.audio.SoundOptions;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ThinClientMain {
    private ThinClientMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            ThinClientSelfTest.main(new String[0]);
            return;
        }
        System.out.println("New Horizon Thin Client core 0.2.1");
        System.out.println("Protocol target: Minecraft Java 1.20.1 (763)");
        if (!ThinClientOptions.enabled(args)) {
            System.out.println("Runtime activation requires --thin-client.");
            return;
        }

        ThinClientOptions options = ThinClientOptions.parse(args);
        System.out.println("[NH-THIN] target=" + options.host + ":" + options.port);
        // Pojav's OpenJDK-side JNI_OnLoad resolves and initializes the patched
        // GLFW class. Loading pojavexec from inside GLFW's own static initializer
        // creates a class-initialization cycle and leaves the surface black.
        // Preload it while no LWJGL class is being initialized.
        String iosBundle = System.getenv("BUNDLE_PATH");
        if (iosBundle != null && !iosBundle.isEmpty()) {
            System.load(iosBundle + "/AngelAuraAmethyst");
        } else {
            System.loadLibrary("pojavexec");
        }
        System.out.println("[NH-THIN] Pojav/LTW runtime bridge ready");
        AtomicReference<Throwable> connectionFailure = new AtomicReference<>();
        AtomicBoolean stopping = new AtomicBoolean();
        System.out.println("[NH-THIN] allocating bounded runtime");
        try (ThinClientRuntime runtime = new ThinClientRuntime(new GeckoNativeBridge());
             MinecraftConnection connection = new MinecraftConnection(runtime, new LogListener(runtime));
             ThinSoundEngine audio = new ThinSoundEngine(runtime.budget,
                     options.assetsDirectory, runtime.sounds, runtime.entities);
             LtwScene scene = new LtwScene(runtime, connection, audio)) {
            SoundOptions soundOptions = SoundOptions.load(options.gameDirectory);
            audio.setVolumes(soundOptions.master(), soundOptions.categories());
            audio.start();
            System.out.println("[NH-THIN] bounded runtime ready");
            Thread network = new Thread(() -> {
                try {
                    System.out.println("[NH-THIN] network connect begin");
                    connection.run(options.host, options.port, options.credentials);
                } catch (Throwable throwable) {
                    if (!stopping.get()) {
                        throwable.printStackTrace(System.out);
                        connectionFailure.set(throwable);
                        scene.requestStop();
                    }
                }
            }, "NH-Thin-Network");
            network.setDaemon(true);
            network.start();
            try {
                System.out.println("[NH-THIN] LTW scene init begin");
                scene.run();
            } finally {
                stopping.set(true);
                connection.close();
                network.join(2_000L);
            }
        }
        Throwable failure = connectionFailure.get();
        if (failure != null) throw new IllegalStateException("Thin connection failed", failure);
    }

    private static final class LogListener implements MinecraftConnection.Listener {
        private final ThinClientRuntime runtime;

        LogListener(ThinClientRuntime runtime) {
            this.runtime = runtime;
        }
        @Override
        public void onConnected() {
            System.out.println("[NH-THIN] socket connected");
        }

        @Override
        public void onLoginSuccess(UUID profileId, String username) {
            System.out.println("[NH-THIN] login complete user=" + username);
        }

        @Override
        public void onLoginQueryRejected(String channel) {
            System.out.println("[NH-THIN] vanilla-compatible login query rejected channel="
                    + channel);
        }

        @Override
        public void onCustomPayload(String channel, ByteBuffer payload) {
            if (!com.newhorizon.thinclient.inventory.InventoryProtocol.CHANNEL.equals(channel)) {
                return;
            }
            try {
                runtime.inventory.handle(payload);
            } catch (com.newhorizon.thinclient.protocol.ProtocolException exception) {
                System.out.println("[NH-THIN] invalid inventory snapshot: "
                        + exception.getMessage());
            }
        }

        @Override
        public void onDisplayRejected(DisplayMessage message) {
            System.out.println("[NH-THIN] display rejected by fixed budget");
        }

        @Override
        public void onDisconnected(String reason) {
            System.out.println("[NH-THIN] server disconnected: " + reason);
        }
    }
}
