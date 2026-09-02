package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class NhClientPatcher {
    public static final String MARKER_ENTRY = "META-INF/newhorizon/client-patch.properties";
    private static final int BUFFER_SIZE = 32 * 1024;

    private NhClientPatcher() {
    }

    public static PatchResult patch(File inputJar, File outputJar, PatchProfile profile)
            throws IOException {
        validateFiles(inputJar, outputJar);
        String inputSha256 = sha256(inputJar);
        if (!profile.inputSha256.equals(inputSha256)) {
            throw new IOException("Input JAR hash mismatch for profile " + profile.id
                    + ": expected " + profile.inputSha256 + ", got " + inputSha256);
        }

        File parent = outputJar.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create output directory: " + parent);
        }
        File temporary = new File(parent, outputJar.getName() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Could not remove stale temporary output: " + temporary);
        }

        Map<String, Integer> changes = new LinkedHashMap<>();
        for (String transformer : profile.transformers) {
            changes.put(transformer, 0);
        }

        try {
            writePatchedJar(inputJar, temporary, profile, inputSha256, changes);
            requireExpectedChanges(changes);
            verify(temporary, profile, inputSha256);
            replaceOutput(temporary, outputJar);
        } catch (Throwable throwable) {
            if (temporary.exists()) {
                // This is an owned cache artifact and is never the source client JAR.
                temporary.delete();
            }
            if (throwable instanceof IOException) {
                throw (IOException) throwable;
            }
            throw new IOException("Failed to patch client JAR", throwable);
        }

        return new PatchResult(outputJar, inputSha256, sha256(outputJar), changes);
    }

    public static boolean isPatched(File jar, PatchProfile profile, String inputSha256) {
        try {
            verify(jar, profile, inputSha256);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void writePatchedJar(File inputJar, File outputJar, PatchProfile profile,
                                        String inputSha256, Map<String, Integer> changes)
            throws IOException {
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(inputJar));
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(outputJar))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (MARKER_ENTRY.equals(name) || isSignatureEntry(name)) {
                    continue;
                }

                byte[] bytes = readAll(input);
                for (String transformer : profile.transformers) {
                    if (PatchProfile.TELEMETRY_1201.equals(transformer)) {
                        byte[] transformed = Telemetry1201Transformer.transform(name, bytes);
                        if (transformed != null) {
                            bytes = transformed;
                            changes.put(transformer, changes.get(transformer) + 1);
                        }
                    } else if (PatchProfile.ANDROID_NARRATOR_1201.equals(transformer)) {
                        byte[] transformed = AndroidNarrator1201Transformer.transform(name, bytes);
                        if (transformed != null) {
                            bytes = transformed;
                            changes.put(transformer, changes.get(transformer) + 1);
                        }
                    } else if (PatchProfile.DEDICATED_DATA_FIXER_1201.equals(transformer)) {
                        byte[] transformed = DedicatedDataFixer1201Transformer.transform(
                                name, bytes);
                        if (transformed != null) {
                            bytes = transformed;
                            changes.put(transformer, changes.get(transformer) + 1);
                        }
                    } else if (PatchProfile.MINIMAL_SOUND_1201.equals(transformer)) {
                        byte[] transformed = MinimalSound1201Transformer.transform(name, bytes);
                        if (transformed != null) {
                            bytes = transformed;
                            changes.put(transformer, changes.get(transformer) + 1);
                        }
                    } else if (PatchProfile.FORGE_MINIMAL_SOUND_1201.equals(transformer)) {
                        byte[] transformed = ForgeSound1201Transformer.transform(name, bytes);
                        if (transformed != null) {
                            bytes = transformed;
                            changes.put(transformer, changes.get(transformer) + 1);
                        }
                    }
                }
                writeEntry(output, name, bytes, entry.isDirectory());
            }
            writeEntry(output, MARKER_ENTRY,
                    marker(profile, inputSha256).getBytes("UTF-8"), false);
        }
    }

    private static void verify(File jar, PatchProfile profile, String inputSha256)
            throws IOException {
        if (!jar.isFile() || jar.length() == 0) {
            throw new IOException("Patched JAR does not exist or is empty: " + jar);
        }
        boolean markerFound = false;
        boolean managerFound = false;
        boolean worldManagerFound = false;
        boolean narratorFound = false;
        boolean dataFixerFound = false;
        boolean productionIdeFlagFound = false;
        boolean soundManagerFound = false;
        boolean soundEngineFound = false;
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (MARKER_ENTRY.equals(name)) {
                    String actual = new String(readAll(input), "UTF-8");
                    String expected = marker(profile, inputSha256);
                    if (!expected.equals(actual)) {
                        throw new IOException("Patched JAR marker does not match the profile");
                    }
                    markerFound = true;
                } else if (Telemetry1201Transformer.MANAGER_ENTRY.equals(name)) {
                    ClassReader reader = new ClassReader(readAll(input));
                    managerFound = "net/minecraft/client/telemetry/ClientTelemetryManager"
                            .equals(reader.getClassName());
                } else if (Telemetry1201Transformer.WORLD_MANAGER_ENTRY.equals(name)) {
                    ClassReader reader = new ClassReader(readAll(input));
                    worldManagerFound =
                            "net/minecraft/client/telemetry/WorldSessionTelemetryManager"
                                    .equals(reader.getClassName());
                } else if (AndroidNarrator1201Transformer.ENTRY.equals(name)) {
                    narratorFound = AndroidNarrator1201Transformer.isPatched(readAll(input));
                } else if (DedicatedDataFixer1201Transformer.ENTRY.equals(name)) {
                    dataFixerFound = DedicatedDataFixer1201Transformer.isPatched(readAll(input));
                } else if (DedicatedDataFixer1201Transformer.SHARED_CONSTANTS_ENTRY.equals(name)) {
                    productionIdeFlagFound =
                            DedicatedDataFixer1201Transformer.isIdeFlagPatched(readAll(input));
                } else if (MinimalSound1201Transformer.MANAGER_ENTRY.equals(name)) {
                    soundManagerFound = MinimalSound1201Transformer.isManagerPatched(
                            readAll(input));
                } else if (MinimalSound1201Transformer.ENGINE_ENTRY.equals(name)) {
                    soundEngineFound = MinimalSound1201Transformer.isEnginePatched(
                            readAll(input));
                }
            }
        }
        boolean telemetryRequired = profile.transformers.contains(PatchProfile.TELEMETRY_1201);
        boolean narratorRequired =
                profile.transformers.contains(PatchProfile.ANDROID_NARRATOR_1201);
        boolean dataFixerRequired =
                profile.transformers.contains(PatchProfile.DEDICATED_DATA_FIXER_1201);
        boolean soundRequired =
                profile.transformers.contains(PatchProfile.MINIMAL_SOUND_1201);
        boolean forgeSoundRequired =
                profile.transformers.contains(PatchProfile.FORGE_MINIMAL_SOUND_1201);
        if (!markerFound
                || (telemetryRequired && (!managerFound || !worldManagerFound))
                || (narratorRequired && !narratorFound)
                || (dataFixerRequired && (!dataFixerFound || !productionIdeFlagFound))
                || (soundRequired && (!soundManagerFound || !soundEngineFound))
                || (forgeSoundRequired && !soundEngineFound)) {
            throw new IOException("Patched JAR verification failed: marker=" + markerFound
                    + " manager=" + managerFound + " worldManager=" + worldManagerFound
                    + " narrator=" + narratorFound + " dataFixer=" + dataFixerFound);
        }
    }

    private static void requireExpectedChanges(Map<String, Integer> changes) throws IOException {
        for (Map.Entry<String, Integer> change : changes.entrySet()) {
            int expected;
            if (PatchProfile.TELEMETRY_1201.equals(change.getKey())) {
                expected = 2;
            } else if (PatchProfile.ANDROID_NARRATOR_1201.equals(change.getKey())) {
                expected = 1;
            } else if (PatchProfile.DEDICATED_DATA_FIXER_1201.equals(change.getKey())) {
                expected = 2;
            } else if (PatchProfile.MINIMAL_SOUND_1201.equals(change.getKey())) {
                expected = 2;
            } else if (PatchProfile.FORGE_MINIMAL_SOUND_1201.equals(change.getKey())) {
                expected = 1;
            } else {
                expected = -1;
            }
            if (change.getValue() != expected) {
                throw new IOException("Transformer " + change.getKey() + " changed "
                        + change.getValue() + " classes; expected " + expected);
            }
        }
    }

    private static void validateFiles(File inputJar, File outputJar) throws IOException {
        if (!inputJar.isFile()) {
            throw new IOException("Input JAR does not exist: " + inputJar);
        }
        if (inputJar.getCanonicalFile().equals(outputJar.getCanonicalFile())) {
            throw new IOException("Refusing to patch the source JAR in place");
        }
    }

    private static void replaceOutput(File temporary, File output) throws IOException {
        if (output.exists() && !output.delete()) {
            throw new IOException("Could not replace existing patch output: " + output);
        }
        if (!temporary.renameTo(output)) {
            copy(temporary, output);
            if (!temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private static boolean isSignatureEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || upper.startsWith("META-INF/SIG-");
    }

    private static String marker(PatchProfile profile, String inputSha256) {
        return "format=1\n"
                + "profile.id=" + profile.id + "\n"
                + "game.version=" + profile.gameVersion + "\n"
                + "input.sha256=" + inputSha256 + "\n"
                + "transformers=" + join(profile.transformers) + "\n";
    }

    private static String join(Iterable<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes,
                                   boolean directory) throws IOException {
        ZipEntry replacement = new ZipEntry(name);
        replacement.setTime(0L);
        output.putNextEntry(replacement);
        if (!directory) {
            output.write(bytes);
        }
        output.closeEntry();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void copy(File source, File destination) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    static String sha256(byte[] bytes) {
        MessageDigest digest = newSha256();
        return hex(digest.digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return output.toString();
    }
}
