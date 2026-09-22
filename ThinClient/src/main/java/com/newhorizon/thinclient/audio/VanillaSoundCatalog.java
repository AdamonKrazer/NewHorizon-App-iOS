package com.newhorizon.thinclient.audio;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.InflaterInputStream;

/**
 * Compact official 1.20.1 registry, sounds.json and per-state SoundType metadata.
 * No Minecraft bootstrap, JSON tree or decoded audio is retained in memory.
 */
public final class VanillaSoundCatalog {
    private static final String RESOURCE = "/assets/newhorizon/sound_catalog_1_20_1.bin";
    public static final String OFFICIAL_SOUNDS_SHA1 = "40a4222b7ada165fa98cb9215129aeb8d9b6b379";
    private final Sample[] files;
    private final Event[] events;
    private final String[] eventNames;
    private final short[] registry;
    private final BlockSound[] blockTypes;
    private final byte[] blockStates;
    private final String[] blockNames;
    private final byte[] blockFlags;
    private final short[] stateBlocks;
    private final int[] recordItems;
    private final short[] recordEvents;
    private static volatile VanillaSoundCatalog shared;

    private VanillaSoundCatalog(DataInputStream input) throws IOException {
        if (input.readInt() != 0x4e48534e || input.readInt() != 2) {
            throw new IOException("Invalid 1.20.1 sound catalog header");
        }
        if (!OFFICIAL_SOUNDS_SHA1.equals(input.readUTF())) {
            throw new IOException("Unexpected vanilla sounds.json identity");
        }
        input.readUTF(); // Original index SHA-1, retained in the resource for auditing.
        files = new Sample[count(input, 16384)];
        for (int i = 0; i < files.length; i++) {
            String path = input.readUTF();
            String hash = input.readUTF();
            int size = input.readInt();
            if (!path.startsWith("minecraft/sounds/") || path.contains("..")
                    || !path.endsWith(".ogg") || !hash.matches("[0-9a-f]{40}") || size <= 0) {
                throw new IOException("Invalid sound asset metadata");
            }
            files[i] = new Sample(path, hash, size, 1, 1, 16, false);
        }
        events = new Event[count(input, 4096)];
        eventNames = new String[events.length];
        int choiceCount = 0;
        for (int i = 0; i < events.length; i++) {
            eventNames[i] = input.readUTF();
            if (i > 0 && eventNames[i - 1].compareTo(eventNames[i]) >= 0) {
                throw new IOException("Unsorted sound event catalog");
            }
            int total = input.readInt();
            int length = input.readUnsignedShort();
            choiceCount += length;
            if (total < 0 || choiceCount > 65536) throw new IOException("Oversized sound catalog");
            Choice[] choices = new Choice[length];
            int sum = 0;
            for (int j = 0; j < length; j++) {
                boolean event = input.readUnsignedByte() != 0;
                int target = input.readUnsignedShort();
                int weight = input.readUnsignedShort();
                float volume = input.readFloat();
                float pitch = input.readFloat();
                int attenuation = input.readUnsignedShort();
                boolean stream = input.readUnsignedByte() != 0;
                if (target >= (event ? events.length : files.length)
                        || !positive(volume) || !positive(pitch)) {
                    throw new IOException("Invalid sound choice");
                }
                sum += weight;
                choices[j] = new Choice(event, target, weight, volume, pitch, attenuation, stream);
            }
            if (sum != total) throw new IOException("Sound weight mismatch");
            events[i] = new Event(total, choices);
        }
        registry = new short[count(input, 4096)];
        for (int i = 0; i < registry.length; i++) registry[i] = (short) eventIndex(input);
        blockTypes = new BlockSound[count(input, 256)];
        for (int i = 0; i < blockTypes.length; i++) {
            float volume = input.readFloat();
            float pitch = input.readFloat();
            blockTypes[i] = new BlockSound(volume, pitch, eventNames[eventIndex(input)],
                    eventNames[eventIndex(input)], eventNames[eventIndex(input)],
                    eventNames[eventIndex(input)], eventNames[eventIndex(input)]);
        }
        blockStates = new byte[count(input, 65536)];
        input.readFully(blockStates);
        for (byte type : blockStates) {
            if ((type & 255) >= blockTypes.length) throw new IOException("Invalid block sound type");
        }
        blockNames = new String[count(input, 4096)];
        blockFlags = new byte[blockNames.length];
        for (int i = 0; i < blockNames.length; i++) {
            blockNames[i] = input.readUTF();
            blockFlags[i] = input.readByte();
        }
        stateBlocks = new short[blockStates.length];
        for (int i = 0; i < stateBlocks.length; i++) {
            int block = input.readUnsignedShort();
            if (block >= blockNames.length) throw new IOException("Invalid sound block ID");
            stateBlocks[i] = (short) block;
        }
        recordItems = new int[count(input, 256)];
        recordEvents = new short[recordItems.length];
        for (int i = 0; i < recordItems.length; i++) {
            recordItems[i] = input.readInt();
            recordEvents[i] = (short) eventIndex(input);
        }
        if (input.read() != -1) throw new IOException("Trailing sound metadata");
    }

    /** Loads only the small bundled metadata table, never the game registries. */
    public static synchronized VanillaSoundCatalog load() {
        if (shared != null) return shared;
        InputStream resource = VanillaSoundCatalog.class.getResourceAsStream(RESOURCE);
        if (resource == null) throw new IllegalStateException("Missing " + RESOURCE);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new InflaterInputStream(resource)))) {
            shared = new VanillaSoundCatalog(input);
            return shared;
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid vanilla sound catalog", failure);
        }
    }

    public String blockName(int state) {
        return state < 0 || state >= stateBlocks.length ? null : blockNames[stateBlocks[state] & 65535];
    }

    public int blockFlags(int state) {
        return state < 0 || state >= stateBlocks.length ? 0 : blockFlags[stateBlocks[state] & 65535];
    }

    public String recordEvent(int item) {
        int index = Arrays.binarySearch(recordItems, item);
        return index < 0 ? null : eventNames[recordEvents[index] & 65535];
    }

    /** Registry IDs are zero-based; subtract the holder's network +1 before this call. */
    public String eventName(int registryId) {
        return registryId < 0 || registryId >= registry.length ? null
                : eventNames[registry[registryId] & 65535];
    }

    public int registrySize() { return registry.length; }
    public int eventCount() { return events.length; }
    public int assetCount() { return files.length; }
    public Sample asset(int index) { return files[index]; }
    public int blockStateCount() { return blockStates.length; }

    /**
     * ClientLevel.playSeededSound uses RandomSource.create(seed), which is the
     * same 48-bit LCG and bounded nextInt algorithm as java.util.Random in 1.20.1.
     */
    public Sample choose(String name, long seed) { return choose(name, new Random(seed)); }

    public Sample choose(String name, Random random) {
        if (name == null || random == null) return null;
        if (name.indexOf(':') < 0) name = "minecraft:" + name;
        int index = Arrays.binarySearch(eventNames, name);
        return index < 0 ? null : choose(index, random, 0);
    }

    private Sample choose(int index, Random random, int depth) {
        Event event = events[index];
        if (depth >= 32 || event.total == 0) return null;
        int chosen = random.nextInt(event.total);
        for (Choice choice : event.choices) {
            chosen -= choice.weight;
            if (chosen >= 0) continue;
            Sample sample = choice.event ? choose(choice.target, random, depth + 1)
                    : files[choice.target];
            if (sample == null) return null;
            // Nested references retain the leaf's attenuation and OR streaming,
            // as SoundManager.Preparations does; reference weight is precomputed.
            return new Sample(sample.assetPath, sample.sha1, sample.encodedSize,
                    sample.volume * choice.volume, sample.pitch * choice.pitch,
                    choice.event ? sample.attenuationDistance : choice.attenuation,
                    sample.stream || choice.stream);
        }
        return null;
    }

    /** Exact SoundType for each global block state, including state-dependent types. */
    public BlockSound blockSound(int stateId) {
        return stateId < 0 || stateId >= blockStates.length ? null
                : blockTypes[blockStates[stateId] & 255];
    }

    private int eventIndex(DataInputStream input) throws IOException {
        int index = input.readUnsignedShort();
        if (index >= events.length) throw new IOException("Invalid sound event index");
        return index;
    }

    private static int count(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 1 || count > maximum) throw new IOException("Invalid sound metadata count");
        return count;
    }

    private static boolean positive(float value) {
        return value > 0 && !Float.isInfinite(value) && !Float.isNaN(value);
    }

    public static final class Sample {
        public final String assetPath;
        public final String sha1;
        public final int encodedSize;
        public final float volume;
        public final float pitch;
        public final int attenuationDistance;
        public final boolean stream;

        private Sample(String assetPath, String sha1, int encodedSize, float volume,
                       float pitch, int attenuationDistance, boolean stream) {
            this.assetPath = assetPath;
            this.sha1 = sha1;
            this.encodedSize = encodedSize;
            this.volume = volume;
            this.pitch = pitch;
            this.attenuationDistance = attenuationDistance;
            this.stream = stream;
        }
    }

    public static final class BlockSound {
        public final float volume;
        public final float pitch;
        public final String breakEvent;
        public final String stepEvent;
        public final String placeEvent;
        public final String hitEvent;
        public final String fallEvent;

        private BlockSound(float volume, float pitch, String breakEvent, String stepEvent,
                           String placeEvent, String hitEvent, String fallEvent) {
            this.volume = volume;
            this.pitch = pitch;
            this.breakEvent = breakEvent;
            this.stepEvent = stepEvent;
            this.placeEvent = placeEvent;
            this.hitEvent = hitEvent;
            this.fallEvent = fallEvent;
        }
    }

    private static final class Event {
        final int total;
        final Choice[] choices;
        Event(int total, Choice[] choices) { this.total = total; this.choices = choices; }
    }

    private static final class Choice {
        final boolean event;
        final int target;
        final int weight;
        final float volume;
        final float pitch;
        final int attenuation;
        final boolean stream;
        Choice(boolean event, int target, int weight, float volume, float pitch,
               int attenuation, boolean stream) {
            this.event = event;
            this.target = target;
            this.weight = weight;
            this.volume = volume;
            this.pitch = pitch;
            this.attenuation = attenuation;
            this.stream = stream;
        }
    }
}
