package net.kdt.pojavlaunch.nhclient;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

/** Launches the shared Android core without adding Minecraft/Forge to the loader. */
public final class ThinClientLauncher {
    private ThinClientLauncher() {}

    public static void launch(MinecraftAccount account) throws Throwable {
        File component = new File(Tools.DIR_BUNDLE, "libs/nh-thin-client.jar");
        if (!component.isFile()) throw new IllegalStateException("Missing thin client: " + component);
        String server = System.getProperty("newhorizon.thin.server", "127.0.0.1:9055").trim();
        if (server.isEmpty()) throw new IllegalArgumentException("Informe o servidor do cliente leve nas configurações.");
        String[] arguments = {
                "--thin-client", "--server", server,
                "--username", account.username.replace("Demo.", ""),
                "--uuid", account.profileId,
                "--accessToken", account.accessToken == null ? "" : account.accessToken,
                "--gameDir", Tools.DIR_GAME_PROFILE,
                "--assetsDir", Tools.ASSETS_PATH
        };
        try {
            Class.forName("com.newhorizon.thinclient.ThinClientMain")
                    .getMethod("main", String[].class).invoke(null, (Object) arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        } finally {
            java.util.Arrays.fill(arguments, "");
        }
    }
}
