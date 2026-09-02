package com.newhorizon.sessionflow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.CRC32;

/**
 * One-shot recorder for Minecraft's already-baked 1.20.1 client data.
 *
 * <p>The thin client must not approximate vanilla's model baker. This exporter
 * asks the live, fully loaded client for the exact state-to-model mapping and
 * stores its final quads, local sprite UVs, render flags and collision boxes.
 * It runs only when the completed capture is absent and never participates in
 * normal frame rendering.</p>
 */
final class VanillaRuntimeCapture {
    private static final int MAGIC = 0x4e485643; // NHVC
    private static final int SPRITE_MAGIC = 0x4e485350; // NHSP
    private static final int TEXTURE_MAGIC = 0x4e485654; // NHVT
    private static final int VERSION = 3;
    private static final int MAX_RESOURCE_BYTES = 16 * 1024 * 1024;
    private static final int STATES_PER_TICK = 4;
    private static final int TEXTURES_PER_TICK = 1;
    private static final int SYNC_INTERVAL = 64;
    private static CaptureSession session;
    private static volatile java.lang.reflect.Method dynamicModelSetter;

    private VanillaRuntimeCapture() {
    }

    static void start(Minecraft minecraft) {
        if (minecraft == null || minecraft.f_91073_ == null) return;
        try {
            if (session == null) session = new CaptureSession(minecraft);
            session.tick(minecraft);
        } catch (Throwable throwable) {
            NewHorizonSessionFlow.captureWarning("incremental capture tick failed", throwable);
        }
    }

    private static long writeState(DataOutputStream data, BlockState state,
                                   BakedModel model,
                                   LinkedHashMap<String, SpriteRecord> sprites,
                                   List<SpriteRecord> newSprites) throws Exception {
        long writtenQuads = 0L;
        data.writeUTF(canonicalState(state));
        data.writeByte(state.m_60799_().ordinal());
        data.writeUTF(String.valueOf(ItemBlockRenderTypes.m_109293_(state)));
        data.writeBoolean(model.m_7541_());
        data.writeBoolean(model.m_7539_());
        data.writeBoolean(model.m_7547_());
        data.writeBoolean(model.m_7521_());

        Block block = state.m_60734_();
        data.writeFloat(block.m_49958_());
        data.writeFloat(block.m_49961_());
        data.writeFloat(block.m_49964_());
        writeShape(data, collisionShape(state));
        writeShape(data, visualShape(state));
        Vec3 offset = state.m_60824_(EmptyBlockGetter.INSTANCE, BlockPos.f_121853_);
        data.writeDouble(offset.f_82479_);
        data.writeDouble(offset.f_82480_);
        data.writeDouble(offset.f_82481_);

        List<ModelPart> parts = decompose(model, state);
        data.writeShort(parts.size());
        for (ModelPart part : parts) {
            data.writeShort(part.choices.size());
            for (ModelChoice choice : part.choices) {
                List<CulledQuad> quads = collectQuads(choice.model, state);
                writtenQuads += quads.size();
                data.writeInt(choice.weight);
                data.writeInt(quads.size());
                for (CulledQuad quad : quads) {
                    writeQuad(data, quad, sprites, newSprites);
                }
            }
        }
        return writtenQuads;
    }

    private static VoxelShape collisionShape(BlockState state) {
        try {
            return state.m_60651_(EmptyBlockGetter.INSTANCE, BlockPos.f_121853_,
                    CollisionContext.m_82749_());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static VoxelShape visualShape(BlockState state) {
        try {
            return state.m_60742_(EmptyBlockGetter.INSTANCE, BlockPos.f_121853_,
                    CollisionContext.m_82749_());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeShape(DataOutputStream data, VoxelShape shape) throws Exception {
        List<AABB> boxes = shape == null ? java.util.Collections.emptyList() : shape.m_83299_();
        data.writeShort(boxes.size());
        for (AABB box : boxes) {
            data.writeFloat((float) box.f_82288_);
            data.writeFloat((float) box.f_82289_);
            data.writeFloat((float) box.f_82290_);
            data.writeFloat((float) box.f_82291_);
            data.writeFloat((float) box.f_82292_);
            data.writeFloat((float) box.f_82293_);
        }
    }

    private static List<ModelPart> decompose(BakedModel model, BlockState state) {
        if (model == null) return java.util.Collections.emptyList();
        String className = model.getClass().getName();
        if (className.endsWith("MultiPartBakedModel")) {
            Object selectorsObject = readField(model, "f_119459_");
            if (selectorsObject instanceof Collection<?>) {
                ArrayList<ModelPart> parts = new ArrayList<>();
                for (Object selector : (Collection<?>) selectorsObject) {
                    Object predicateObject = invoke(selector, "getLeft");
                    Object childObject = invoke(selector, "getRight");
                    if (!(predicateObject instanceof Predicate<?>)
                            || !(childObject instanceof BakedModel)) continue;
                    @SuppressWarnings("unchecked")
                    Predicate<BlockState> predicate = (Predicate<BlockState>) predicateObject;
                    if (!predicate.test(state)) continue;
                    parts.add(weightedPart((BakedModel) childObject));
                }
                if (!parts.isEmpty()) return parts;
            }
        }
        return java.util.Collections.singletonList(weightedPart(model));
    }

    private static ModelPart weightedPart(BakedModel model) {
        if (model != null && model.getClass().getName().endsWith("WeightedBakedModel")) {
            Object wrappersObject = readField(model, "f_119541_");
            if (wrappersObject instanceof Collection<?>) {
                ArrayList<ModelChoice> choices = new ArrayList<>();
                for (Object wrapper : (Collection<?>) wrappersObject) {
                    Object child = invoke(wrapper, "m_146310_");
                    Object weight = invoke(wrapper, "m_142631_");
                    Object value = invoke(weight, "m_146281_");
                    if (child instanceof BakedModel && value instanceof Number) {
                        choices.add(new ModelChoice(Math.max(1,
                                ((Number) value).intValue()), (BakedModel) child));
                    }
                }
                if (!choices.isEmpty()) return new ModelPart(choices);
            }
        }
        return new ModelPart(java.util.Collections.singletonList(new ModelChoice(1, model)));
    }

    private static List<CulledQuad> collectQuads(BakedModel model, BlockState state) {
        ArrayList<CulledQuad> result = new ArrayList<>();
        RandomSource random = RandomSource.m_216335_(0L);
        random.m_188584_(0L);
        addQuads(result, model.m_213637_(state, null, random), -1);
        Direction[] directions = Direction.values();
        for (int index = 0; index < directions.length; index++) {
            random.m_188584_(0L);
            addQuads(result, model.m_213637_(state, directions[index], random), index);
        }
        return result;
    }

    private static void addQuads(List<CulledQuad> result, List<BakedQuad> quads,
                                 int cullFace) {
        if (quads == null) return;
        for (BakedQuad quad : quads) result.add(new CulledQuad(cullFace, quad));
    }

    private static void writeQuad(DataOutputStream data, CulledQuad record,
                                  LinkedHashMap<String, SpriteRecord> sprites,
                                  List<SpriteRecord> newSprites) throws Exception {
        BakedQuad quad = record.quad;
        TextureAtlasSprite sprite = quad.m_173410_();
        // TextureAtlasSprite#atlasLocation identifies the shared blocks atlas.
        // SpriteContents#name is the actual material (grass, sand, gravel, ...).
        String spriteName = sprite.m_245424_().m_246162_().toString();
        if (!sprites.containsKey(spriteName)) {
            SpriteRecord added = new SpriteRecord(spriteName,
                    sprite.m_245424_().m_246492_(), sprite.m_245424_().m_245330_());
            sprites.put(spriteName, added);
            newSprites.add(added);
        }
        data.writeByte(record.cullFace);
        data.writeByte(quad.m_111306_().ordinal());
        data.writeShort(quad.m_111305_());
        data.writeBoolean(quad.m_111304_());
        data.writeBoolean(quad.m_111307_());
        data.writeUTF(spriteName);
        data.writeFloat(sprite.m_118409_());
        data.writeFloat(sprite.m_118410_());
        data.writeFloat(sprite.m_118411_());
        data.writeFloat(sprite.m_118412_());
        int[] vertices = quad.m_111303_();
        data.writeShort(vertices.length);
        for (int value : vertices) data.writeInt(value);
        int stride = vertices.length / 4;
        data.writeByte(stride);
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            float atlasU = Float.intBitsToFloat(vertices[base + 4]);
            float atlasV = Float.intBitsToFloat(vertices[base + 5]);
            data.writeFloat(sprite.m_174727_(atlasU));
            data.writeFloat(sprite.m_174741_(atlasV));
        }
    }

    private static ResourceLocation textureLocation(String spriteName, String suffix) {
        ResourceLocation sprite = new ResourceLocation(spriteName);
        return new ResourceLocation(sprite.m_135827_(),
                "textures/" + sprite.m_135815_() + suffix);
    }

    private static byte[] readResource(ResourceManager resources, ResourceLocation location) {
        try (InputStream input = resources.m_215595_(location);
             ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESOURCE_BYTES) return new byte[0];
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    private static String canonicalState(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.f_256975_.m_7981_(state.m_60734_());
        TreeMap<String, String> properties = new TreeMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.m_61148_().entrySet()) {
            properties.put(entry.getKey().m_61708_(), propertyValue(entry.getKey(), entry.getValue()));
        }
        if (properties.isEmpty()) return blockId.toString();
        StringBuilder result = new StringBuilder(blockId.toString()).append('[');
        boolean comma = false;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            if (comma) result.append(',');
            comma = true;
            result.append(property.getKey()).append('=').append(property.getValue());
        }
        return result.append(']').toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(Property property, Comparable value) {
        return property.m_6940_(value);
    }

    private static Object readField(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invoke(Object owner, String name) {
        if (owner == null) return null;
        try {
            java.lang.reflect.Method method = owner.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The recorder deliberately advances from the render tick.  Dynamic model
     * providers are not safe to materialize from a background thread and their
     * LRU can only release a baked model after we drop the current reference.
     */
    private static final class CaptureSession {
        final File directory;
        final File completionMarker;
        final List<BlockState> states;
        final LinkedHashMap<String, SpriteRecord> sprites = new LinkedHashMap<>();
        final FramedFile stateFile;
        final FramedFile spriteFile;
        FramedFile textureFile;
        int stateIndex;
        int textureIndex;
        long successfulStates;
        long failedStates;
        long quadCount;
        boolean stateFilesSynced;
        boolean complete;

        CaptureSession(Minecraft minecraft) throws Exception {
            directory = new File(minecraft.f_91069_, "newhorizon-capture/runtime-v3");
            Files.createDirectories(directory.toPath());
            completionMarker = new File(directory, "complete.nhvc");
            states = new ArrayList<>();
            for (Block block : BuiltInRegistries.f_256975_) {
                states.addAll(block.m_49965_().m_61056_());
            }
            states.sort(Comparator.comparing(VanillaRuntimeCapture::canonicalState));

            if (completionMarker.isFile() && completionMarker.length() > 16) {
                complete = true;
                stateFile = null;
                spriteFile = null;
                NewHorizonSessionFlow.captureInfo("incremental capture already complete states="
                        + states.size() + " path=" + directory.getAbsolutePath());
                return;
            }

            spriteFile = new FramedFile(new File(directory, "sprites.nhsp"),
                    SPRITE_MAGIC, 0, (payload, ignored) -> readSpriteRecord(payload, sprites));
            stateFile = new FramedFile(new File(directory, "states.nhvs"),
                    MAGIC, states.size(), null);
            stateIndex = Math.min(stateFile.recordCount, states.size());
            NewHorizonSessionFlow.captureInfo("incremental capture ready states="
                    + states.size() + " resume=" + stateIndex + " sprites="
                    + sprites.size() + " batch=" + STATES_PER_TICK
                    + " path=" + directory.getAbsolutePath());
        }

        void tick(Minecraft minecraft) throws Exception {
            if (complete) return;
            if (stateIndex < states.size()) {
                BlockModelShaper shaper = minecraft.m_91289_().m_110907_();
                int stop = Math.min(states.size(), stateIndex + STATES_PER_TICK);
                while (stateIndex < stop) captureState(shaper, states.get(stateIndex));
                if ((stateIndex & 255) == 0 || stateIndex == states.size()) {
                    stateFile.sync();
                    spriteFile.sync();
                    NewHorizonSessionFlow.captureInfo("states " + stateIndex + "/"
                            + states.size() + " sprites=" + sprites.size()
                            + " ok=" + successfulStates + " failed=" + failedStates
                            + " quads=" + quadCount);
                }
                return;
            }

            if (!stateFilesSynced) {
                stateFile.sync();
                spriteFile.sync();
                stateFilesSynced = true;
                NewHorizonSessionFlow.captureInfo("state pass complete states="
                        + stateIndex + " sprites=" + sprites.size());
            }

            ArrayList<SpriteRecord> orderedSprites = new ArrayList<>(sprites.values());
            orderedSprites.sort(Comparator.comparing(sprite -> sprite.name));
            if (textureFile == null) {
                textureFile = new FramedFile(new File(directory, "textures.nhvt"),
                        TEXTURE_MAGIC, orderedSprites.size(), null);
                textureIndex = Math.min(textureFile.recordCount, orderedSprites.size());
                NewHorizonSessionFlow.captureInfo("texture pass ready textures="
                        + orderedSprites.size() + " resume=" + textureIndex);
            }
            int stop = Math.min(orderedSprites.size(), textureIndex + TEXTURES_PER_TICK);
            ResourceManager resources = minecraft.m_91098_();
            while (textureIndex < stop) {
                SpriteRecord sprite = orderedSprites.get(textureIndex);
                textureFile.append(textureRecord(resources, sprite),
                        (textureIndex & (SYNC_INTERVAL - 1)) == SYNC_INTERVAL - 1);
                textureIndex++;
            }
            if ((textureIndex & 31) == 0 || textureIndex == orderedSprites.size()) {
                textureFile.sync();
                NewHorizonSessionFlow.captureInfo("textures " + textureIndex + "/"
                        + orderedSprites.size());
            }
            if (textureIndex == orderedSprites.size()) finish(orderedSprites.size());
        }

        private void captureState(BlockModelShaper shaper, BlockState state) throws Exception {
            ArrayList<SpriteRecord> newSprites = new ArrayList<>();
            byte[] payload;
            long beforeQuads = quadCount;
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
                long stateQuads;
                try (DataOutputStream data = new DataOutputStream(bytes)) {
                    data.writeBoolean(true);
                    BakedModel model = shaper.m_110893_(state);
                    stateQuads = writeState(data, state, model, sprites, newSprites);
                }
                payload = bytes.toByteArray();
                successfulStates++;
                quadCount += stateQuads;
            } catch (Throwable throwable) {
                quadCount = beforeQuads;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
                try (DataOutputStream data = new DataOutputStream(bytes)) {
                    data.writeBoolean(false);
                    data.writeUTF(canonicalState(state));
                    data.writeUTF(throwable.getClass().getName());
                    data.writeUTF(String.valueOf(throwable.getMessage()));
                }
                payload = bytes.toByteArray();
                failedStates++;
                NewHorizonSessionFlow.captureWarning("state failed "
                        + canonicalState(state), throwable);
            }

            for (int index = 0; index < newSprites.size(); index++) {
                SpriteRecord sprite = newSprites.get(index);
                spriteFile.append(spriteRecord(sprite), index == newSprites.size() - 1);
            }
            stateFile.append(payload,
                    (stateIndex & (SYNC_INTERVAL - 1)) == SYNC_INTERVAL - 1);
            stateIndex++;
            clearDynamicModelReference(state);
        }

        private void finish(int textureCount) throws Exception {
            stateFile.sync();
            spriteFile.sync();
            textureFile.sync();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeInt(MAGIC);
                data.writeInt(VERSION);
                data.writeUTF("minecraft-1.20.1-live-incremental");
                data.writeInt(states.size());
                data.writeInt(sprites.size());
                data.writeInt(textureCount);
                data.writeLong(System.currentTimeMillis());
            }
            File temporary = new File(directory, "complete.nhvc.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes.toByteArray());
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), completionMarker.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            complete = true;
            NewHorizonSessionFlow.captureInfo("complete states=" + states.size()
                    + " sprites=" + sprites.size() + " textures=" + textureCount
                    + " bytes=" + directorySize(directory)
                    + " path=" + directory.getAbsolutePath());
        }
    }

    private static byte[] spriteRecord(SpriteRecord sprite) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF(sprite.name);
            data.writeInt(sprite.width);
            data.writeInt(sprite.height);
        }
        return bytes.toByteArray();
    }

    private static void readSpriteRecord(byte[] payload,
                                         LinkedHashMap<String, SpriteRecord> sprites)
            throws Exception {
        try (java.io.DataInputStream data = new java.io.DataInputStream(
                new ByteArrayInputStream(payload))) {
            SpriteRecord sprite = new SpriteRecord(data.readUTF(), data.readInt(), data.readInt());
            sprites.putIfAbsent(sprite.name, sprite);
        }
    }

    private static byte[] textureRecord(ResourceManager resources, SpriteRecord sprite)
            throws Exception {
        byte[] png = readResource(resources, textureLocation(sprite.name, ".png"));
        byte[] metadata = readResource(resources, textureLocation(sprite.name, ".png.mcmeta"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(png.length + metadata.length + 64);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF(sprite.name);
            data.writeInt(sprite.width);
            data.writeInt(sprite.height);
            data.writeInt(png.length);
            data.write(png);
            data.writeInt(metadata.length);
            data.write(metadata);
        }
        return bytes.toByteArray();
    }

    private static long directorySize(File directory) {
        long result = 0L;
        File[] files = directory.listFiles();
        if (files == null) return 0L;
        for (File file : files) result += file.isFile() ? file.length() : 0L;
        return result;
    }

    /**
     * ModernFix attaches the lazily baked model directly to every BlockState.
     * That is ideal during play, but a full registry walk would otherwise turn
     * its bounded cache into 24k permanent strong references.  Clearing only
     * the state we just serialized preserves normal lazy rebaking and keeps the
     * capture live set flat.
     */
    private static void clearDynamicModelReference(BlockState state) {
        try {
            java.lang.reflect.Method setter = dynamicModelSetter;
            if (setter == null) {
                setter = state.getClass().getMethod("mfix$setModel", BakedModel.class);
                setter.setAccessible(true);
                dynamicModelSetter = setter;
            }
            setter.invoke(state, new Object[]{null});
        } catch (NoSuchMethodException ignored) {
            // ModernFix dynamic resources are optional; vanilla uses its own map.
        } catch (Throwable throwable) {
            NewHorizonSessionFlow.captureWarning("could not release dynamic model", throwable);
        }
    }

    private interface RecordConsumer {
        void accept(byte[] payload, int index) throws Exception;
    }

    /** Length + CRC framing lets a new process truncate and resume a torn tail. */
    private static final class FramedFile {
        private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
        final RandomAccessFile file;
        int recordCount;

        FramedFile(File path, int magic, int expected, RecordConsumer consumer)
                throws Exception {
            file = new RandomAccessFile(path, "rw");
            if (file.length() < 12L) {
                file.setLength(0L);
                file.writeInt(magic);
                file.writeInt(VERSION);
                file.writeInt(expected);
                file.getFD().sync();
            } else {
                file.seek(0L);
                if (file.readInt() != magic || file.readInt() != VERSION
                        || file.readInt() != expected) {
                    file.setLength(0L);
                    file.writeInt(magic);
                    file.writeInt(VERSION);
                    file.writeInt(expected);
                    file.getFD().sync();
                }
            }
            scan(consumer);
        }

        private void scan(RecordConsumer consumer) throws Exception {
            long good = 12L;
            file.seek(good);
            int index = 0;
            while (file.getFilePointer() + 8L <= file.length()) {
                int length = file.readInt();
                if (length < 0 || length > MAX_RECORD_BYTES
                        || file.getFilePointer() + length + 4L > file.length()) break;
                byte[] payload = new byte[length];
                file.readFully(payload);
                int expectedCrc = file.readInt();
                CRC32 crc = new CRC32();
                crc.update(payload);
                if ((int) crc.getValue() != expectedCrc) break;
                if (consumer != null) consumer.accept(payload, index);
                index++;
                good = file.getFilePointer();
            }
            if (file.length() != good) file.setLength(good);
            file.seek(good);
            recordCount = index;
        }

        void append(byte[] payload, boolean sync) throws Exception {
            CRC32 crc = new CRC32();
            crc.update(payload);
            file.writeInt(payload.length);
            file.write(payload);
            file.writeInt((int) crc.getValue());
            recordCount++;
            if (sync) sync();
        }

        void sync() throws Exception {
            file.getFD().sync();
        }
    }

    private static final class ModelPart {
        final List<ModelChoice> choices;

        ModelPart(List<ModelChoice> choices) {
            this.choices = choices;
        }
    }

    private static final class ModelChoice {
        final int weight;
        final BakedModel model;

        ModelChoice(int weight, BakedModel model) {
            this.weight = weight;
            this.model = model;
        }
    }

    private static final class CulledQuad {
        final int cullFace;
        final BakedQuad quad;

        CulledQuad(int cullFace, BakedQuad quad) {
            this.cullFace = cullFace;
            this.quad = quad;
        }
    }

    private static final class SpriteRecord {
        final String name;
        final int width;
        final int height;

        SpriteRecord(String name, int width, int height) {
            this.name = name;
            this.width = width;
            this.height = height;
        }
    }
}
