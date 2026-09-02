package com.newhorizon.clientpatcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class PatchProfile {
    public static final String TELEMETRY_1201 = "disable-client-telemetry-1.20.1";
    public static final String ANDROID_NARRATOR_1201 =
            "disable-android-narrator-1.20.1";
    public static final String DEDICATED_DATA_FIXER_1201 =
            "dedicated-client-data-fixer-1.20.1";
    public static final String MINIMAL_SOUND_1201 =
            "minimal-vanilla-sound-1.20.1";
    public static final String FORGE_MINIMAL_SOUND_1201 =
            "minimal-forge-sound-1.20.1-47.4.0";
    public static final String MINECRAFT_1201_MINIMAL_PROFILE_ID =
            "minecraft-1.20.1-forge-srg-newhorizon-minimal-v7";
    public static final String MINECRAFT_1201_CLIENT_SHA256 =
            "e2e940fe7ca0a9fba5a5c11ad2cf3e2f7df91bbf51d12af22cdde7af18adc344";
    public static final String FORGE_1201_CLIENT_SHA256 =
            "010adc332f19b05fb24383954ec7a88694666216e480a6824ff0cbc4740c3c66";

    public final String id;
    public final String gameVersion;
    public final String inputSha256;
    public final List<String> transformers;

    private PatchProfile(String id, String gameVersion, String inputSha256,
                         List<String> transformers) {
        this.id = id;
        this.gameVersion = gameVersion;
        this.inputSha256 = inputSha256;
        this.transformers = Collections.unmodifiableList(new ArrayList<>(transformers));
    }

    public static PatchProfile load(File file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(file)) {
            properties.load(input);
        }

        String id = require(properties, "profile.id");
        String gameVersion = require(properties, "game.version");
        String inputSha256 = require(properties, "input.sha256").toLowerCase(Locale.ROOT);
        if (!inputSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("input.sha256 must contain exactly 64 hexadecimal characters");
        }

        String rawTransformers = require(properties, "transformers");
        List<String> transformers = new ArrayList<>();
        for (String transformer : rawTransformers.split(",")) {
            String normalized = transformer.trim();
            if (!normalized.isEmpty()) {
                transformers.add(normalized);
            }
        }
        if (transformers.isEmpty()) {
            throw new IOException("The patch profile has no transformers");
        }
        for (String transformer : transformers) {
            if (!TELEMETRY_1201.equals(transformer)
                    && !ANDROID_NARRATOR_1201.equals(transformer)
                    && !DEDICATED_DATA_FIXER_1201.equals(transformer)
                    && !MINIMAL_SOUND_1201.equals(transformer)
                    && !FORGE_MINIMAL_SOUND_1201.equals(transformer)) {
                throw new IOException("Unknown transformer: " + transformer);
            }
        }
        return new PatchProfile(id, gameVersion, inputSha256, transformers);
    }

    public static PatchProfile minecraft1201Minimal() {
        return new PatchProfile(
                MINECRAFT_1201_MINIMAL_PROFILE_ID,
                "1.20.1",
                MINECRAFT_1201_CLIENT_SHA256,
                Arrays.asList(TELEMETRY_1201, ANDROID_NARRATOR_1201,
                        DEDICATED_DATA_FIXER_1201, MINIMAL_SOUND_1201));
    }

    public static PatchProfile forge1201MinimalSound() {
        return new PatchProfile(
                "forge-1.20.1-47.4.0-newhorizon-minimal-sound-v1",
                "1.20.1-forge-47.4.0",
                FORGE_1201_CLIENT_SHA256,
                Arrays.asList(FORGE_MINIMAL_SOUND_1201));
    }

    private static String require(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Missing required profile property: " + key);
        }
        return value.trim();
    }
}
