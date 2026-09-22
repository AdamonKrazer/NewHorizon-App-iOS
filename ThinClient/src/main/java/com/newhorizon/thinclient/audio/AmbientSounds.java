package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.ThinClientRuntime;
import com.newhorizon.thinclient.world.EnvironmentState;
import com.newhorizon.thinclient.world.BlockStatePhysics;
import java.util.Objects;
import java.util.Random;

/** Small 20Hz music/biome controller driven by the server's actual biome registry. */
public final class AmbientSounds {
    private final ThinClientRuntime runtime;
    private final ThinSoundEngine engine;
    private final Random random = new Random();
    private final VanillaSoundCatalog catalog = VanillaSoundCatalog.load();
    private final EnvironmentState.Snapshot environment = new EnvironmentState.Snapshot();
    private long nextTick, generation = -1;
    private String loop, music;
    private int nextMusic = 100, musicPending, rainSoundTime;
    private float mood;
    public float mood(){return mood;}

    public AmbientSounds(ThinClientRuntime runtime, ThinSoundEngine engine) {
        this.runtime = runtime; this.engine = engine;
    }

    public void update(long now, double x, double feetY, double eyeY, double z, boolean canFly) {
        if (generation != runtime.sounds.generation()) {
            generation = runtime.sounds.generation(); loop = music = null;
            nextMusic = 100; mood = 0; nextTick = now; musicPending = 0;
        }
        if (now < nextTick) return;
        nextTick = now + 50_000_000L;
        for (int i = 0; i < 667; i++) {
            animateBlock(floor(x), floor(feetY), floor(z), 16);
            animateBlock(floor(x), floor(feetY), floor(z), 32);
        }
        BiomeSoundRegistry.Biome biome = runtime.soundBiomes.get(runtime.world.biomeAt(
                floor(x), floor(feetY), floor(z)));
        if (biome == null) return;
        music(biome, canFly);
        if (!Objects.equals(loop, biome.loop)) {
            loop = biome.loop; runtime.sounds.ambientLoop(loop);
        }
        if (biome.additions != null && random.nextDouble() < biome.additionsChance)
            runtime.sounds.playRelative(biome.additions, SoundEventQueue.AMBIENT, 1, 1, random.nextLong());
        if (biome.mood != null) mood(biome, x, eyeY, z);
        runtime.environment.sample(now, environment);
        if (environment.rain > 0 && runtime.world.dimension().equals("minecraft:overworld"))
            rain(x, eyeY, z, environment.rain);
    }

    private void music(BiomeSoundRegistry.Biome biome, boolean canFly) {
        String wanted = biome.music == null ? "minecraft:music.game" : biome.music;
        int min = biome.music == null ? 12000 : biome.minDelay;
        int max = biome.music == null ? 24000 : Math.max(min, biome.maxDelay);
        boolean replace = biome.replaceMusic;
        String dimension = runtime.world.dimension();
        if (dimension.equals("minecraft:the_end")) {
            wanted = "minecraft:music.end"; min = 6000; max = 24000; replace = true;
        } else if (!dimension.equals("minecraft:the_nether")
                && canFly && runtime.inventory.isCreativeMode()) {
            wanted = "minecraft:music.creative"; min = 12000; max = 24000; replace = false;
        }
        if (music != null && !music.equals(wanted) && replace) {
            runtime.sounds.stop(music, SoundEventQueue.MUSIC);
            music = null; nextMusic = random.nextInt(min / 2 + 1); musicPending = 0;
        }
        if (music != null && musicPending-- <= 0 && !engine.isPlaying(music)) {
            music = null; nextMusic = Math.min(nextMusic, min + random.nextInt(max - min + 1));
        }
        nextMusic = Math.min(nextMusic, max);
        if (music == null && nextMusic-- <= 0 && engine.status().equals("ready")) {
            music = wanted; musicPending = 20;
            runtime.sounds.playRelative(wanted, SoundEventQueue.MUSIC, 1, 1, random.nextLong());
            nextMusic = Integer.MAX_VALUE;
        }
    }

    private void animateBlock(int x, int y, int z, int range) {
        x += random.nextInt(range) - random.nextInt(range);
        y += random.nextInt(range) - random.nextInt(range);
        z += random.nextInt(range) - random.nextInt(range);
        int state = runtime.world.blockStateAt(x, y, z);
        if (state < 0 || BlockStatePhysics.isAir(state)) return;
        int flags = BlockStatePhysics.flags(state);
        if ((flags & BlockStatePhysics.WATER) != 0 && BlockStatePhysics.fluidAmount(state) < 8
                && (flags & BlockStatePhysics.FLUID_FALLING) == 0 && random.nextInt(64) == 0) {
            runtime.sounds.play("minecraft:block.water.ambient", SoundEventQueue.BLOCKS,
                    x + 0.5, y + 0.5, z + 0.5, random.nextFloat() * 0.25f + 0.75f,
                    random.nextFloat() + 0.5f, random.nextLong());
        }
        if ((flags & BlockStatePhysics.LAVA) != 0
                && BlockStatePhysics.isAir(runtime.world.blockStateAt(x, y + 1, z))) {
            if (random.nextInt(100) == 0) runtime.sounds.play("minecraft:block.lava.pop", 4,
                    x + random.nextDouble(), y + 1, z + random.nextDouble(),
                    0.2f + random.nextFloat() * 0.2f, 0.9f + random.nextFloat() * 0.15f, random.nextLong());
            if (random.nextInt(200) == 0) runtime.sounds.play("minecraft:block.lava.ambient", 4,
                    x, y, z, 0.2f + random.nextFloat() * 0.2f,
                    0.9f + random.nextFloat() * 0.15f, random.nextLong());
        }
        String block = catalog.blockName(state);
        if (("minecraft:fire".equals(block) || "minecraft:soul_fire".equals(block))
                && random.nextInt(24) == 0) runtime.sounds.play("minecraft:block.fire.ambient", 4,
                x + 0.5, y + 0.5, z + 0.5, 1 + random.nextFloat(),
                random.nextFloat() * 0.7f + 0.3f, random.nextLong());
    }

    private void mood(BiomeSoundRegistry.Biome biome, double x, double y, double z) {
        int extent = biome.moodExtent, bound = extent * 2 + 1;
        int bx = floor(x + random.nextInt(bound) - extent);
        int by = floor(y + random.nextInt(bound) - extent);
        int bz = floor(z + random.nextInt(bound) - extent);
        int sky = runtime.world.lightAt(true, bx, by, bz);
        if (sky > 0) mood -= sky / 15f * 0.001f;
        else mood -= (runtime.world.lightAt(false, bx, by, bz) - 1f) / biome.moodDelay;
        if (mood >= 1) {
            double dx = bx + 0.5 - x, dy = by + 0.5 - y, dz = bz + 0.5 - z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length > 0) {
                double distance = length + biome.moodOffset;
                runtime.sounds.play(biome.mood, SoundEventQueue.AMBIENT,
                        x + dx / length * distance, y + dy / length * distance,
                        z + dz / length * distance, 1, 1, random.nextLong());
            }
            mood = 0;
        } else mood = Math.max(0, mood);
    }

    private void rain(double x, double y, double z, float amount) {
        int chosenX = 0, chosenY = Integer.MIN_VALUE, chosenZ = 0;
        int tries = (int) (100 * amount * amount);
        for (int i = 0; i < tries; i++) {
            int bx = floor(x) + random.nextInt(21) - 10;
            int bz = floor(z) + random.nextInt(21) - 10;
            int top = runtime.world.rainSurface(bx, bz);
            if (top == Integer.MIN_VALUE || Math.abs(top - floor(y)) > 10) continue;
            BiomeSoundRegistry.Biome biome = runtime.soundBiomes.get(runtime.world.biomeAt(bx, top, bz));
            if (biome == null || !biome.precipitation || biome.temperature < 0.15f) continue;
            chosenX = bx; chosenY = top; chosenZ = bz;
        }
        if (chosenY != Integer.MIN_VALUE && random.nextInt(3) < rainSoundTime++) {
            rainSoundTime = 0;
            boolean above = chosenY > y + 1
                    && runtime.world.rainSurface(floor(x), floor(z)) > floor(y);
            runtime.sounds.play(above ? "minecraft:weather.rain.above" : "minecraft:weather.rain",
                    SoundEventQueue.WEATHER, chosenX, chosenY, chosenZ,
                    above ? 0.1f : 0.2f, above ? 0.5f : 1, random.nextLong());
        }
    }

    private static int floor(double value) { return (int) Math.floor(value); }
}
