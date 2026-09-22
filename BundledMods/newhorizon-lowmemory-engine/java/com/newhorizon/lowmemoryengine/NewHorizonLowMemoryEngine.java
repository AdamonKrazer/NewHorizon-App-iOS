package com.newhorizon.lowmemoryengine;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.embeddedt.embeddium.api.render.chunk.RenderSectionDistanceFilter;
import org.embeddedt.embeddium.api.render.chunk.RenderSectionDistanceFilterEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Mod(NewHorizonLowMemoryEngine.MOD_ID)
public final class NewHorizonLowMemoryEngine {
    public static final String MOD_ID = "newhorizon_lowmemory_engine";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double ITEM_RENDER_RADIUS_SQR = 8.0D * 8.0D;
    private static final double DECORATIVE_RENDER_RADIUS_SQR = 10.0D * 10.0D;
    private static final double MOB_RENDER_RADIUS_SQR = 16.0D * 16.0D;
    private static final double PROJECTILE_RENDER_RADIUS_SQR = 18.0D * 18.0D;
    private static final double PLAYER_RENDER_RADIUS_SQR = 32.0D * 32.0D;
    private static final double DEFAULT_ENTITY_RENDER_RADIUS_SQR = 20.0D * 20.0D;
    private static final double BLOCK_ENTITY_RENDER_RADIUS_SQR = 12.0D * 12.0D;
    private static final int CLIENT_CHUNK_CACHE_RADIUS_CAP = 2;
    // These are visual limits only. ClientLevel and ClientChunkCache keep the
    // complete server-provided world used by collision, fluids, entities and
    // menus; only LTW geometry outside this radius is not built.
    private static final int CLIENT_RENDER_DISTANCE_CAP = 2;
    private static final double CHUNK_REBUILD_RADIUS_SQR = 24.0D * 24.0D;
    private static final int STORAGE_TRIM_RADIUS_CHUNKS = 2;
    private static final int STORAGE_TRIM_INTERVAL_TICKS = 10;
    private static final int STORAGE_TRIM_MAX_CHUNKS_PER_PASS = 48;
    private static final float EMBEDDIUM_RENDER_DISTANCE_CAP_BLOCKS = 64.0F;

    private static long sLastLogMs;
    private static int sEntitySkips;
    private static int sBlockEntitySkips;
    private static int sChunkCacheClamps;
    private static int sViewAreaClamps;
    private static int sChunkRebuildSkips;
    private static int sChunkStorageDrops;
    private static int sChunkStorageSeen;
    private static int sEmbeddiumFilterRejects;
    private static Field sChunkCacheStorageField;
    private static Field sStorageChunksField;
    private static Field sStorageLoadedCountField;
    private static boolean sReflectionFailed;

    public NewHorizonLowMemoryEngine() {
        tryRegisterEmbeddiumDistanceFilter();
        LOGGER.info("[NHLowMemoryEngine] active version=0.5-dynamic-chunk-storage cacheRadius={} renderDistance={} trimRadiusChunks={} rebuildRadiusBlocks={} embeddiumCapBlocks={}",
                CLIENT_CHUNK_CACHE_RADIUS_CAP, CLIENT_RENDER_DISTANCE_CAP, STORAGE_TRIM_RADIUS_CHUNKS,
                Math.sqrt(CHUNK_REBUILD_RADIUS_SQR), EMBEDDIUM_RENDER_DISTANCE_CAP_BLOCKS);
        System.err.println("[NHLowMemoryEngine] active version=0.5-dynamic-chunk-storage cacheRadius="
                + CLIENT_CHUNK_CACHE_RADIUS_CAP + " renderDistance=" + CLIENT_RENDER_DISTANCE_CAP
                + " trimRadiusChunks=" + STORAGE_TRIM_RADIUS_CHUNKS
                + " embeddiumCapBlocks=" + EMBEDDIUM_RENDER_DISTANCE_CAP_BLOCKS);
    }

    private static void tryRegisterEmbeddiumDistanceFilter() {
        try {
            RenderSectionDistanceFilterEvent.BUS.addListener(event -> {
                RenderSectionDistanceFilter previous = event.getFilter();
                event.setFilter((x, y, z, distance) -> {
                    float cappedDistance = Math.min(distance, EMBEDDIUM_RENDER_DISTANCE_CAP_BLOCKS);
                    boolean allowed = previous.isWithinDistance(x, y, z, cappedDistance);
                    if (!allowed) {
                        sEmbeddiumFilterRejects++;
                        if ((sEmbeddiumFilterRejects & 4095) == 0) {
                            logSummary();
                        }
                    }
                    return allowed;
                });
            });
            LOGGER.info("[NHLowMemoryEngine] Embeddium section distance filter registered capBlocks={}",
                    EMBEDDIUM_RENDER_DISTANCE_CAP_BLOCKS);
            System.err.println("[NHLowMemoryEngine] Embeddium section distance filter registered capBlocks="
                    + EMBEDDIUM_RENDER_DISTANCE_CAP_BLOCKS);
        } catch (Throwable throwable) {
            LOGGER.warn("[NHLowMemoryEngine] Embeddium distance filter unavailable {}", throwable.toString());
            System.err.println("[NHLowMemoryEngine] Embeddium distance filter unavailable " + throwable);
        }
    }

    public static int clampClientChunkCacheRadius(int requested) {
        int clamped = Math.max(2, Math.min(requested, CLIENT_CHUNK_CACHE_RADIUS_CAP));
        if (clamped != requested) {
            sChunkCacheClamps++;
            logSummary();
        }
        return clamped;
    }

    public static int clampClientRenderDistance(int requested) {
        int clamped = Math.max(2, Math.min(requested, CLIENT_RENDER_DISTANCE_CAP));
        if (clamped != requested) {
            sViewAreaClamps++;
            logSummary();
        }
        return clamped;
    }

    public static boolean shouldSkipChunkRebuildByDistance(double distanceSqr) {
        boolean skip = distanceSqr > CHUNK_REBUILD_RADIUS_SQR;
        if (skip) {
            sChunkRebuildSkips++;
            logSummary();
        }
        return skip;
    }

    public static boolean shouldRunStorageTrimThisTick() {
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91073_ == null || minecraft.f_91074_ == null) {
            return false;
        }
        return (minecraft.f_91073_.m_46467_() % STORAGE_TRIM_INTERVAL_TICKS) == 0L;
    }

    public static int trimClientChunkStorage(AtomicReferenceArray<LevelChunk> chunks, int loadedChunkCount) {
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91073_ == null || minecraft.f_91074_ == null || chunks == null) {
            return loadedChunkCount;
        }

        int playerChunkX = minecraft.f_91074_.m_146903_() >> 4;
        int playerChunkZ = minecraft.f_91074_.m_146907_() >> 4;
        int dropped = 0;
        int seen = 0;

        for (int index = 0; index < chunks.length() && dropped < STORAGE_TRIM_MAX_CHUNKS_PER_PASS; index++) {
            LevelChunk chunk = chunks.get(index);
            if (chunk == null) {
                continue;
            }
            seen++;
            ChunkPos pos = chunk.m_7697_();
            int dx = Math.abs(pos.f_45578_ - playerChunkX);
            int dz = Math.abs(pos.f_45579_ - playerChunkZ);
            if (dx <= STORAGE_TRIM_RADIUS_CHUNKS && dz <= STORAGE_TRIM_RADIUS_CHUNKS) {
                continue;
            }

            LevelChunk removed = chunks.getAndSet(index, null);
            if (removed == null) {
                continue;
            }
            dropped++;
            try {
                minecraft.f_91073_.m_104665_(removed);
            } catch (Throwable throwable) {
                LOGGER.warn("[NHLowMemoryEngine] chunk unload callback failed {}", throwable.toString());
            }
        }

        if (dropped > 0) {
            sChunkStorageDrops += dropped;
            sChunkStorageSeen = seen;
            logSummary();
            return Math.max(0, loadedChunkCount - dropped);
        }
        return loadedChunkCount;
    }

    public static void trimClientChunkCacheObject(Object chunkCache) {
        if (chunkCache == null || sReflectionFailed || !shouldRunStorageTrimThisTick()) {
            return;
        }
        try {
            if (sChunkCacheStorageField == null) {
                sChunkCacheStorageField = findField(chunkCache.getClass(), "f_104410_");
            }
            Object storage = sChunkCacheStorageField.get(chunkCache);
            if (storage == null) {
                return;
            }
            if (sStorageChunksField == null || sStorageLoadedCountField == null) {
                Class<?> storageClass = storage.getClass();
                sStorageChunksField = findField(storageClass, "f_104466_");
                sStorageLoadedCountField = findField(storageClass, "f_104471_");
            }
            @SuppressWarnings("unchecked")
            AtomicReferenceArray<LevelChunk> chunks =
                    (AtomicReferenceArray<LevelChunk>) sStorageChunksField.get(storage);
            int currentCount = sStorageLoadedCountField.getInt(storage);
            int newCount = trimClientChunkStorage(chunks, currentCount);
            if (newCount != currentCount) {
                sStorageLoadedCountField.setInt(storage, newCount);
            }
        } catch (Throwable throwable) {
            sReflectionFailed = true;
            LOGGER.warn("[NHLowMemoryEngine] storage reflection disabled {}", throwable.toString());
            System.err.println("[NHLowMemoryEngine] storage reflection disabled " + throwable);
        }
    }

    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        Class<?> cursor = owner;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    public static boolean shouldSkipEntityRender(Entity entity) {
        if (entity == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91074_ == null || entity == minecraft.f_91074_) {
            return false;
        }

        double distanceSqr = minecraft.f_91074_.m_20280_(entity);
        double capSqr = entityCapSqr(entity);
        boolean skip = distanceSqr > capSqr;
        if (skip) {
            logSkip(false);
        }
        return skip;
    }

    public static boolean shouldSkipBlockEntityRender(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91074_ == null) {
            return false;
        }
        BlockPos pos = blockEntity.m_58899_();
        if (pos == null) {
            return false;
        }

        double x = (double) pos.m_123341_() + 0.5D;
        double y = (double) pos.m_123342_() + 0.5D;
        double z = (double) pos.m_123343_() + 0.5D;
        boolean skip = minecraft.f_91074_.m_20275_(x, y, z) > BLOCK_ENTITY_RENDER_RADIUS_SQR;
        if (skip) {
            logSkip(true);
        }
        return skip;
    }

    private static double entityCapSqr(Entity entity) {
        if (entity instanceof Player) {
            return PLAYER_RENDER_RADIUS_SQR;
        }
        if (entity instanceof ArmorStand || entity instanceof ItemFrame) {
            return DECORATIVE_RENDER_RADIUS_SQR;
        }
        if (entity instanceof ItemEntity) {
            return ITEM_RENDER_RADIUS_SQR;
        }
        if (entity instanceof Projectile) {
            return PROJECTILE_RENDER_RADIUS_SQR;
        }
        if (entity instanceof Mob) {
            return MOB_RENDER_RADIUS_SQR;
        }
        return DEFAULT_ENTITY_RENDER_RADIUS_SQR;
    }

    private static void logSkip(boolean blockEntity) {
        if (blockEntity) {
            sBlockEntitySkips++;
        } else {
            sEntitySkips++;
        }
        long now = System.currentTimeMillis();
        logSummary();
    }

    private static void logSummary() {
        long now = System.currentTimeMillis();
        if (now - sLastLogMs < 15000L) {
            return;
        }
        sLastLogMs = now;
        LOGGER.info("[NHLowMemoryEngine] summary15s entitySkip={} blockEntitySkip={} chunkRebuildSkip={} cacheClamp={} viewClamp={} storageDrop={} storageSeen={} embeddiumReject={}",
                sEntitySkips, sBlockEntitySkips, sChunkRebuildSkips, sChunkCacheClamps, sViewAreaClamps,
                sChunkStorageDrops, sChunkStorageSeen, sEmbeddiumFilterRejects);
        System.err.println("[NHLowMemoryEngine] skips15s entity=" + sEntitySkips
                + " blockEntity=" + sBlockEntitySkips
                + " chunkRebuild=" + sChunkRebuildSkips
                + " cacheClamp=" + sChunkCacheClamps
                + " viewClamp=" + sViewAreaClamps
                + " storageDrop=" + sChunkStorageDrops
                + " storageSeen=" + sChunkStorageSeen
                + " embeddiumReject=" + sEmbeddiumFilterRejects);
        sEntitySkips = 0;
        sBlockEntitySkips = 0;
        sChunkRebuildSkips = 0;
        sChunkCacheClamps = 0;
        sViewAreaClamps = 0;
        sChunkStorageDrops = 0;
        sChunkStorageSeen = 0;
        sEmbeddiumFilterRejects = 0;
    }
}
