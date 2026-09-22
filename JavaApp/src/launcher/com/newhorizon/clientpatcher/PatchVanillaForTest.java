package com.newhorizon.clientpatcher;

import java.io.File;

/** Applies the actual launcher profile to the pinned test fixture, never the original. */
public final class PatchVanillaForTest {
    public static void main(String[] args) throws Exception {
        PatchResult result = NhClientPatcher.patch(new File(args[0]), new File(args[1]), PatchProfile.minecraft1201Minimal());
        if (result.totalChangedClasses() != 19) throw new AssertionError("Expected 19 patched classes, got " + result.totalChangedClasses());
        System.out.println("[NH-PATCH-TEST] " + result.totalChangedClasses() + " classes SHA256=" + result.outputSha256);
    }
}
