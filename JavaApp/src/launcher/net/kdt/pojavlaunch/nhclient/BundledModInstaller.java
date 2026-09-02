package net.kdt.pojavlaunch.nhclient;

import com.newhorizon.clientpatcher.NhClientPatcher;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Synchronizes the cross-platform mods owned by the New Horizon launcher. */
public final class BundledModInstaller {
    private static final String SESSION_TARGET = "newhorizon_session_flow-1.0.3.jar";
    private static final String LOW_MEMORY_ENGINE = "newhorizon_lowmemory_engine-0.1.0.jar";
    private static final int COPY_BUFFER_SIZE = 32 * 1024;

    private BundledModInstaller() {}

    public static synchronized void synchronize() throws IOException {
        File sourceDirectory = new File(Tools.DIR_BUNDLE, "newhorizon/bundled-mods");
        File modsDirectory = new File(Tools.DIR_GAME_NEW, "mods");
        if (!sourceDirectory.isDirectory()) {
            throw new IOException("Bundled New Horizon mods are missing: " + sourceDirectory);
        }
        if (!modsDirectory.isDirectory() && !modsDirectory.mkdirs()) {
            throw new IOException("Could not create mods directory: " + modsDirectory);
        }

        boolean lowMemory = Boolean.parseBoolean(
                System.getProperty("newhorizon.lowPressure", "false"));
        List<String> expected = new ArrayList<String>();
        install(sourceDirectory, modsDirectory,
                "mcef-forge-2.1.6-1.20.1-geckoview.jar",
                "mcef-forge-2.1.6-1.20.1-geckoview.jar", expected);
        install(sourceDirectory, modsDirectory,
                "webdisplays-2.0.2-1.20.1.jar",
                "webdisplays-2.0.2-1.20.1.jar", expected);
        install(sourceDirectory, modsDirectory,
                lowMemory
                        ? "newhorizon_session_flow-1.0.3-low-memory.jar"
                        : "newhorizon_session_flow-1.0.3-base.jar",
                SESSION_TARGET, expected);
        if (lowMemory) {
            install(sourceDirectory, modsDirectory,
                    LOW_MEMORY_ENGINE, LOW_MEMORY_ENGINE, expected);
        }

        removeConflictingOwnedMods(modsDirectory, expected);
        System.out.println("[BundledMods] synchronized profile="
                + (lowMemory ? "LOW_MEMORY" : "BASE") + " count=" + expected.size());
    }

    private static void install(File sourceDirectory, File modsDirectory,
                                String sourceName, String destinationName,
                                List<String> expected) throws IOException {
        File source = new File(sourceDirectory, sourceName);
        File destination = new File(modsDirectory, destinationName);
        if (!source.isFile()) {
            throw new IOException("Bundled mod is missing: " + source);
        }
        expected.add(destinationName.toLowerCase(Locale.ROOT));
        String sourceHash = NhClientPatcher.sha256(source);
        if (destination.isFile() && sourceHash.equals(NhClientPatcher.sha256(destination))) {
            return;
        }

        File temporary = new File(destination.getAbsolutePath() + ".nh.tmp");
        copy(source, temporary);
        if (!sourceHash.equals(NhClientPatcher.sha256(temporary))) {
            temporary.delete();
            throw new IOException("Bundled mod verification failed: " + destinationName);
        }
        replace(temporary, destination);
        System.out.println("[BundledMods] installed " + destinationName
                + " bytes=" + destination.length() + " sha256=" + sourceHash);
    }

    private static void removeConflictingOwnedMods(File modsDirectory, List<String> expected) {
        File[] files = modsDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.isFile() || !name.endsWith(".jar") || expected.contains(name)) continue;
            if (name.startsWith("mcef-forge-")
                    || name.startsWith("newhorizon_session_flow")
                    || name.startsWith("newhorizon-session-flow")
                    || name.equals("device-newhorizon-session-flow.jar")
                    || name.startsWith("newhorizon_lowmemory_engine")) {
                if (!file.delete()) {
                    System.err.println("[BundledMods][WARN] could not remove " + file);
                } else {
                    System.out.println("[BundledMods] removed conflicting " + file.getName());
                }
            }
        }
    }

    private static void copy(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create directory: " + parent);
        }
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static void replace(File temporary, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace " + destination);
        }
        if (!temporary.renameTo(destination)) {
            copy(temporary, destination);
            if (!temporary.delete()) {
                System.err.println("[BundledMods][WARN] could not remove " + temporary);
            }
        }
    }
}
