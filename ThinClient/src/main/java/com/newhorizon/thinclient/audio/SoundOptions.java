package com.newhorizon.thinclient.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Reads the existing vanilla sliders without keeping its options object graph. */
public final class SoundOptions {
    private static final String[] CATEGORIES = { "master", "music", "record", "weather",
            "block", "hostile", "neutral", "player", "ambient", "voice" };
    private final float[] volumes = new float[10];

    public SoundOptions() { Arrays.fill(volumes, 1.0f); }

    public static SoundOptions load(Path gameDirectory) {
        SoundOptions options = new SoundOptions();
        Path file = gameDirectory.resolve("options.txt");
        if (!Files.isRegularFile(file)) return options;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) options.readLine(line);
        } catch (IOException exception) {
            System.out.println("[NH-THIN-AUDIO] Could not read sound sliders: "
                    + exception.getClass().getSimpleName());
        }
        return options;
    }

    void readLine(String line) {
        if (!line.startsWith("soundCategory_")) return;
        int separator = line.indexOf(':');
        if (separator < 0) return;
        String name = line.substring(14, separator);
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (!CATEGORIES[i].equals(name)) continue;
            try {
                float value = Float.parseFloat(line.substring(separator + 1));
                if (Float.isFinite(value)) volumes[i] = Math.max(0, Math.min(1, value));
            } catch (NumberFormatException ignored) { }
            return;
        }
    }

    public float master() { return volumes[0]; }
    public float[] categories() {
        float[] categories = volumes.clone();
        // MASTER sounds receive only listener gain, not master squared.
        categories[0] = 1.0f;
        return categories;
    }
}
