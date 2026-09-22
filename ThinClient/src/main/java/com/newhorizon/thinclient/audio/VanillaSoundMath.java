package com.newhorizon.thinclient.audio;

/** Dependency-free numeric rules verified against the official 1.20.1 client. */
public final class VanillaSoundMath {
    private VanillaSoundMath() { }

    public static float gain(float volume, float categoryVolume) {
        return clamp(volume * categoryVolume, 0.0f, 1.0f);
    }

    public static float pitch(float pitch) {
        return clamp(pitch, 0.5f, 2.0f);
    }

    public static float distance(float volume, int attenuationDistance) {
        return Math.max(volume, 1.0f) * attenuationDistance;
    }

    /** OggAudioStream.OutputConcat conversion, including its asymmetric rounding. */
    public static short pcm16(float sample) {
        int value = (int) (sample * 32767.5f - 0.5f);
        return (short) Math.max(-32768, Math.min(32767, value));
    }

    /** Minecraft yaw/pitch in degrees, returning forward XYZ followed by up XYZ. */
    public static void orientation(float yaw, float pitch, float[] output) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double sinY = Math.sin(y), cosY = Math.cos(y);
        double sinP = Math.sin(p), cosP = Math.cos(p);
        output[0] = (float) (-sinY * cosP);
        output[1] = (float) -sinP;
        output[2] = (float) (cosY * cosP);
        output[3] = (float) (-sinY * sinP);
        output[4] = (float) cosP;
        output[5] = (float) (cosY * sinP);
    }

    public static float clamp(float value, float min, float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    public static void selfTest() {
        require(gain(4, 0.5f) == 1 && gain(-1, 1) == 0, "gain clamp");
        require(gain(0.8f, 0.5f) == 0.4f, "category gain");
        require(pitch(0.1f) == 0.5f && pitch(10) == 2, "pitch clamp");
        require(distance(0.2f, 16) == 16 && distance(4, 16) == 64,
                "distance uses unclamped event volume");
        require(pcm16(-1) == -32768 && pcm16(1) == 32767 && pcm16(0) == 0,
                "PCM endpoints");
        require(pcm16(0.5f) == 16383 && pcm16(-0.5f) == -16384,
                "vanilla PCM rounding");
        require(pcm16(100) == 32767 && pcm16(-100) == -32768, "PCM clipping");
        float[] vector = new float[6];
        orientation(0, 0, vector);
        require(Math.abs(vector[2] - 1) < 1e-5 && Math.abs(vector[4] - 1) < 1e-5,
                "south-facing listener");
        orientation(90, 0, vector);
        require(Math.abs(vector[0] + 1) < 1e-5, "west-facing listener");
        orientation(23, 61, vector);
        float dot = vector[0] * vector[3] + vector[1] * vector[4] + vector[2] * vector[5];
        require(Math.abs(dot) < 1e-5, "listener forward/up orthogonal");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
