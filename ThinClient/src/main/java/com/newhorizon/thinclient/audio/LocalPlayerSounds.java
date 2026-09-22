package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.world.BlockStatePhysics;
import com.newhorizon.thinclient.world.WorldChunkStore;
import java.util.Random;

/** Vanilla player movement and block interaction sounds, independent of render FPS. */
public final class LocalPlayerSounds {
    private static final int PENDING_LIMIT = 32;
    private final WorldChunkStore world;
    private final SoundEventQueue queue;
    private final VanillaSoundCatalog catalog;
    private final Random random = new Random();
    private final Pending[] pending = new Pending[PENDING_LIMIT];
    private int pendingCursor;
    private long generation = -1;
    private float moveDistance;
    private float nextStep = 1.0f;
    private boolean inWater;
    private boolean initialized;
    private long lastCrystalTick = Long.MIN_VALUE;
    private float crystalIntensity;
    private long ticks;

    public LocalPlayerSounds(WorldChunkStore world, SoundEventQueue queue) {
        this.world = world;
        this.queue = queue;
        catalog = VanillaSoundCatalog.load();
        for (int i = 0; i < pending.length; i++) pending[i] = new Pending();
    }

    private void checkGeneration() {
        long current = queue.generation();
        if (generation == current) return;
        generation = current;
        moveDistance = 0;
        nextStep = 1;
        initialized = false;
        lastCrystalTick = Long.MIN_VALUE;
        crystalIntensity = 0;
        for (Pending action : pending) action.active = false;
    }

    /** Called after each actual 20Hz movement tick, before server reconciliation. */
    public void movement(double x, double y, double z, double dx, double dy, double dz,
                         double vx, double vy, double vz, boolean grounded,
                         boolean sneaking, boolean flying, int physicsFlags) {
        checkGeneration();
        ticks++;
        boolean water = (physicsFlags & BlockStatePhysics.WATER) != 0;
        if (initialized && water && !inWater) {
            float gain = waterGain(vx, vy, vz, 0.2f);
            play(gain < 0.25f ? "entity.player.splash" : "entity.player.splash.high_speed",
                    SoundEventQueue.PLAYERS, x, y, z, gain, waterPitch());
        }
        initialized = true;
        inWater = water;
        if (flying || sneaking) return;
        boolean climbable = (physicsFlags & (BlockStatePhysics.CLIMBABLE
                | BlockStatePhysics.POWDER_SNOW)) != 0;
        double verticalDistance = climbable ? dy : 0;
        moveDistance += (float) Math.sqrt(dx * dx + verticalDistance * verticalDistance
                + dz * dz) * 0.6f;
        if (moveDistance <= nextStep) return;
        int bx = floor(x), by = floor(y - 0.2), bz = floor(z);
        int state = world.blockStateAt(bx, by, bz);
        if (state < 0) return;
        boolean stepping = !BlockStatePhysics.isAir(state) && (grounded || climbable);
        if (!stepping && !water) return;
        nextStep = (int) moveDistance + 1;
        if (water) {
            play("entity.player.swim", SoundEventQueue.PLAYERS, x, y, z,
                    waterGain(vx, vy, vz, 0.35f), waterPitch());
            if (stepping) step(state, x, y, z, 0.05f, 0.8f);
        } else {
            int above = world.blockStateAt(bx, by + 1, bz);
            int aboveFlags = catalog.blockFlags(above);
            if ((aboveFlags & 2) != 0) {
                step(above, x, y, z, 0.15f, 1);
                step(state, x, y, z, 0.05f, 0.8f);
            } else {
                step((aboveFlags & 1) != 0 ? above : state, x, y, z, 0.15f, 1);
            }
            if ((catalog.blockFlags(state) & 4) != 0
                    && (lastCrystalTick == Long.MIN_VALUE || ticks - lastCrystalTick >= 20)) {
                crystalIntensity *= lastCrystalTick == Long.MIN_VALUE
                        ? 0 : (float) Math.pow(0.997, ticks - lastCrystalTick);
                crystalIntensity = Math.min(1, crystalIntensity + 0.07f);
                play("block.amethyst_block.chime", SoundEventQueue.PLAYERS, x, y, z,
                        0.1f + crystalIntensity * 1.2f,
                        0.5f + crystalIntensity * random.nextFloat() * 1.2f);
                lastCrystalTick = ticks;
            }
        }
    }

    private void step(int state, double x, double y, double z, float gain, float pitch) {
        VanillaSoundCatalog.BlockSound sound = catalog.blockSound(state);
        if (sound != null) play(sound.stepEvent, SoundEventQueue.PLAYERS,
                x, y, z, sound.volume * gain, sound.pitch * pitch);
    }

    /** MultiPlayerGameMode uses one hit sample per four client ticks. */
    public void hitBlock(int x, int y, int z) {
        VanillaSoundCatalog.BlockSound sound = catalog.blockSound(world.blockStateAt(x, y, z));
        if (sound != null) play(sound.hitEvent, SoundEventQueue.BLOCKS,
                x + 0.5, y + 0.5, z + 0.5, (sound.volume + 1) / 8, sound.pitch * 0.5f);
    }

    /** The server excludes the acting player from its break/place sound broadcast. */
    public void expectBreak(int x, int y, int z, long now) {
        expect(1, x, y, z, null, now);
    }

    public void expectPlace(int x, int y, int z, int direction, String material, long now) {
        if (material == null || material.isEmpty()) return;
        String block = material.toLowerCase(java.util.Locale.ROOT);
        if (!block.startsWith("minecraft:")) block = "minecraft:" + block;
        // Include both replaceable clicked cells and the outward face candidate.
        expect(2, x, y, z, block, now);
        int dx = direction == 4 ? -1 : direction == 5 ? 1 : 0;
        int dy = direction == 0 ? -1 : direction == 1 ? 1 : 0;
        int dz = direction == 2 ? -1 : direction == 3 ? 1 : 0;
        expect(2, x + dx, y + dy, z + dz, block, now);
    }

    private void expect(int kind, int x, int y, int z, String block, long now) {
        checkGeneration();
        int state = world.blockStateAt(x, y, z);
        if (state < 0) return;
        for (Pending action : pending) {
            if (action.active && action.x == x && action.y == y && action.z == z) {
                // Holding the same target must not replace its original state.
                if (action.kind == kind) return;
                action.active = false;
            }
        }
        Pending action = pending[pendingCursor++ % pending.length];
        action.active = true;
        action.kind = kind;
        action.x = x; action.y = y; action.z = z;
        action.state = state;
        action.block = block;
        action.deadline = now + 2_000_000_000L;
    }

    /** Emit only after the authoritative block state changed successfully. */
    public void update(long now) {
        checkGeneration();
        for (Pending action : pending) {
            if (!action.active) continue;
            if (now > action.deadline) { action.active = false; continue; }
            int state = world.blockStateAt(action.x, action.y, action.z);
            if (state < 0 || state == action.state) continue;
            action.active = false;
            if (action.kind == 1) {
                if (!BlockStatePhysics.isAir(state)
                        && (BlockStatePhysics.flags(state) & (BlockStatePhysics.WATER
                        | BlockStatePhysics.LAVA)) == 0) continue;
                VanillaSoundCatalog.BlockSound sound = catalog.blockSound(action.state);
                if (sound != null) play(sound.breakEvent, SoundEventQueue.BLOCKS,
                        action.x + 0.5, action.y + 0.5, action.z + 0.5,
                        (sound.volume + 1) / 2, sound.pitch * 0.8f);
            } else if (action.block.equals(catalog.blockName(state))) {
                VanillaSoundCatalog.BlockSound sound = catalog.blockSound(state);
                if (sound != null) play(sound.placeEvent, SoundEventQueue.BLOCKS,
                        action.x + 0.5, action.y + 0.5, action.z + 0.5,
                        (sound.volume + 1) / 2, sound.pitch * 0.8f);
            }
        }
    }

    private void play(String name, int category, double x, double y, double z,
                      float volume, float pitch) {
        if (name != null) queue.play(name, category, x, y, z, volume, pitch, random.nextLong());
    }

    static float waterGain(double vx, double vy, double vz, float scale) {
        return Math.min(1, (float) Math.sqrt(vx * vx * 0.2 + vy * vy + vz * vz * 0.2) * scale);
    }

    private float waterPitch() { return 1 + (random.nextFloat() - random.nextFloat()) * 0.4f; }
    private static int floor(double value) { return (int) Math.floor(value); }

    private static final class Pending {
        boolean active;
        int kind, x, y, z, state;
        long deadline;
        String block;
    }
}
