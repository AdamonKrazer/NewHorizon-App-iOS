package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;

import java.nio.ByteBuffer;

/** Minimal synchronized copy of the vanilla clock and weather level state. */
public final class EnvironmentState {
    private static final long DAY_TICKS = 24_000L;
    private static final long NANOS_PER_TICK = 50_000_000L;
    private long gameTime;
    private long dayTime = 6_000L;
    private long receivedNanos = System.nanoTime();
    private boolean daylightCycle;
    private volatile float rainLevel;
    private volatile float thunderLevel;
    private int weatherLogCount;
    private int timeLogCount;

    public synchronized void readTime(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, 16);
        gameTime = packet.getLong();
        long encodedDayTime = packet.getLong();
        if (packet.hasRemaining()) throw new ProtocolException("Trailing time packet bytes");
        daylightCycle = encodedDayTime >= 0L;
        // Vanilla encodes a stopped clock as a negative value. Long.MIN_VALUE
        // is not emitted by the server but is handled without overflow.
        dayTime = encodedDayTime == Long.MIN_VALUE
                ? 0L : Math.abs(encodedDayTime);
        receivedNanos = System.nanoTime();
        if (timeLogCount < 12) {
            timeLogCount++;
            System.out.println("[NH-THIN] vanilla clock dayTime="
                    + Math.floorMod(dayTime, DAY_TICKS)
                    + " running=" + daylightCycle);
        }
    }

    public void readGameEvent(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, 5);
        int event = packet.get() & 0xff;
        float value = packet.getFloat();
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing game-event packet bytes");
        }
        switch (event) {
            case 1: // START_RAINING
                rainLevel = Math.max(rainLevel, 1.0f);
                logWeather("rain start", rainLevel);
                break;
            case 2: // STOP_RAINING
                rainLevel = 0.0f;
                thunderLevel = 0.0f;
                logWeather("rain stop", 0.0f);
                break;
            case 7: // RAIN_LEVEL_CHANGE
                rainLevel = clamp(value);
                logWeather("rain level", rainLevel);
                break;
            case 8: // THUNDER_LEVEL_CHANGE
                thunderLevel = clamp(value);
                logWeather("thunder level", thunderLevel);
                break;
            default:
                break;
        }
    }

    public synchronized void sample(long now, Snapshot output) {
        long sampledDayTime = dayTime;
        long sampledGameTime = gameTime;
        if (daylightCycle) {
            long elapsedTicks = Math.max(0L, (now - receivedNanos) / NANOS_PER_TICK);
            sampledDayTime += elapsedTicks;
            sampledGameTime += elapsedTicks;
        }
        output.dayTime = Math.floorMod(sampledDayTime, DAY_TICKS);
        output.day=sampledDayTime/DAY_TICKS;
        output.moonPhase = (int)Math.floorMod(sampledDayTime / DAY_TICKS, 8L);
        output.gameTime = sampledGameTime;
        output.rain = rainLevel;
        output.thunder = thunderLevel;
    }

    private void logWeather(String label, float value) {
        if (weatherLogCount >= 24) return;
        weatherLogCount++;
        System.out.println("[NH-THIN] vanilla weather " + label + "=" + value);
    }

    private static float clamp(float value) {
        if (!Float.isFinite(value)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public static final class Snapshot {
        public long gameTime;
        public long dayTime;
        public long day;
        public int moonPhase;
        public float rain;
        public float thunder;
    }
}
