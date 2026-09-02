package com.newhorizon.clientpatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class PatcherSelfTest {
    private PatcherSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "nh-client-patcher-selftest-" + System.nanoTime());
        if (!root.mkdirs()) {
            throw new IOException("Could not create self-test directory");
        }
        try {
            File profileFile = new File(root, "valid.properties");
            write(profileFile,
                    "profile.id=test\n"
                            + "game.version=1.20.1\n"
                            + "input.sha256=0000000000000000000000000000000000000000000000000000000000000000\n"
                            + "transformers=disable-client-telemetry-1.20.1,"
                            + "disable-android-narrator-1.20.1,"
                            + "dedicated-client-data-fixer-1.20.1,"
                            + "minimal-vanilla-sound-1.20.1\n");
            PatchProfile profile = PatchProfile.load(profileFile);
            require("test".equals(profile.id), "profile id");
            require(profile.transformers.size() == 4, "transformer count");
            require(PatchProfile.forge1201MinimalSound().transformers.size() == 1,
                    "Forge transformer count");

            File unknownFile = new File(root, "unknown.properties");
            write(unknownFile,
                    "profile.id=test\n"
                            + "game.version=1.20.1\n"
                            + "input.sha256=0000000000000000000000000000000000000000000000000000000000000000\n"
                            + "transformers=unknown\n");
            boolean rejected = false;
            try {
                PatchProfile.load(unknownFile);
            } catch (IOException expected) {
                rejected = true;
            }
            require(rejected, "unknown transformer rejection");
            require("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                    .equals(NhClientPatcher.sha256("abc".getBytes("UTF-8"))), "SHA-256");
            System.out.println("NH client patcher self-test passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void write(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes("UTF-8"));
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError("Self-test failed: " + name);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
