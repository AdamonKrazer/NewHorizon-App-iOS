package com.newhorizon.clientpatcher;

import java.io.File;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !"patch".equals(args[0]) || !"--profile".equals(args[1])) {
            usage();
            System.exit(2);
            return;
        }

        PatchProfile profile = PatchProfile.load(new File(args[2]));
        String[] paths = args[3].split("::", -1);
        if (paths.length != 2 || paths[0].isEmpty() || paths[1].isEmpty()) {
            throw new IllegalArgumentException("Input and output must be INPUT::OUTPUT");
        }

        PatchResult result = NhClientPatcher.patch(
                new File(paths[0]), new File(paths[1]), profile);
        System.out.println("NH client patch complete");
        System.out.println("  profile: " + profile.id);
        System.out.println("  input SHA-256: " + result.inputSha256);
        System.out.println("  output SHA-256: " + result.outputSha256);
        System.out.println("  changed classes: " + result.totalChangedClasses());
        System.out.println("  output: " + result.output.getAbsolutePath());
    }

    private static void usage() {
        System.err.println("Usage: nh-client-patcher patch --profile PROFILE INPUT::OUTPUT");
    }
}
