package com.newhorizon.thinclient.audio;

/** Fixed-capacity handoff from the network/game threads to the audio owner. */
public final class SoundEventQueue {
    public static final int POSITIONAL = 1;
    public static final int ENTITY = 2;
    public static final int STOP = 3;
    public static final int LEVEL = 4;
    public static final int AMBIENT_LOOP = 5;
    public static final int MASTER = 0, MUSIC = 1, RECORDS = 2, WEATHER = 3,
            BLOCKS = 4, HOSTILE = 5, NEUTRAL = 6, PLAYERS = 7, AMBIENT = 8, VOICE = 9;

    private final Event[] events;
    private int head;
    private int size;
    private long generation;
    private long dropped;

    public SoundEventQueue(int capacity) {
        if (capacity < 1 || capacity > 4096) throw new IllegalArgumentException("capacity");
        events = new Event[capacity];
        for (int index = 0; index < capacity; index++) events[index] = new Event();
    }

    public boolean play(String name, int category, double x, double y, double z,
                        float volume, float pitch, long seed) {
        return sound(POSITIONAL, -1, name, category, -1, x, y, z,
                volume, pitch, seed, -1.0f, false);
    }

    public boolean playRelative(String name, int category, float volume, float pitch,
                                long seed) {
        return sound(POSITIONAL, -1, name, category, -1, 0.0, 0.0, 0.0,
                volume, pitch, seed, -1.0f, true);
    }
    public boolean playEntity(String name,int category,int entity,float volume,float pitch,long seed) {
        return sound(ENTITY,-1,name,category,entity,0,0,0,volume,pitch,seed,-1,false);
    }

    synchronized boolean sound(int kind, int soundId, String name, int category,
                               int entityId, double x, double y, double z,
                               float volume, float pitch, long seed, float fixedRange,
                               boolean relative) {
        Event event = next(false);
        if (event == null) return false;
        event.kind = kind;
        event.soundId = soundId;
        event.soundName = name;
        event.category = category;
        event.entityId = entityId;
        event.x = x;
        event.y = y;
        event.z = z;
        event.volume = volume;
        event.pitch = pitch;
        event.seed = seed;
        event.fixedRange = fixedRange;
        event.relative = relative;
        return true;
    }

    /** A null name and category -1 correspond to vanilla /stopsound all. */
    public synchronized void stop(String name, int category) {
        Event event = next(true);
        event.kind = STOP;
        event.soundName = name;
        event.category = category;
    }

    public synchronized void ambientLoop(String name) {
        Event event = next(true);
        event.kind = AMBIENT_LOOP;
        event.soundName = name;
        event.category = AMBIENT;
        event.relative = true;
        event.volume = event.pitch = 1;
        event.seed = System.nanoTime();
    }

    synchronized void level(int type, int data, int x, int y, int z, boolean global) {
        Event event = next(false);
        if (event == null) return;
        event.kind = LEVEL;
        event.levelEvent = type;
        event.data = data;
        event.x = x;
        event.y = y;
        event.z = z;
        event.global = global;
    }

    /** Copies into caller-owned storage and releases any queued resource name. */
    public synchronized boolean poll(Event output) {
        if (size == 0) return false;
        Event event = events[head];
        output.copy(event);
        event.soundName = null;
        head = (head + 1) % events.length;
        size--;
        return true;
    }

    public synchronized int size() { return size; }
    public int capacity() { return events.length; }
    public synchronized long generation() { return generation; }
    public synchronized long dropped() { return dropped; }

    /** Also invalidates voices already playing in the previous world/session. */
    public synchronized void clear() {
        for (Event event : events) event.soundName = null;
        head = 0;
        size = 0;
        generation++;
    }

    private Event next(boolean control) {
        if (size == events.length) {
            if (!control) {
                dropped++;
                return null;
            }
            // A stop must never be lost behind a burst of ordinary effects.
            int expendable = 0;
            while (expendable < size
                    && events[(head + expendable) % events.length].kind == STOP) {
                expendable++;
            }
            if (expendable == size) {
                // More distinct stop requests than the fixed control budget:
                // invalidating the generation safely collapses them to stop all.
                dropped += size;
                clear();
            } else {
                for (int index = expendable; index + 1 < size; index++) {
                    events[(head + index) % events.length].copy(
                            events[(head + index + 1) % events.length]);
                }
                size--;
                dropped++;
            }
        }
        Event result = events[(head + size++) % events.length];
        result.reset(generation);
        return result;
    }

    /** Mutable only so the consumer can reuse one instance without allocations. */
    public static final class Event {
        public int kind;
        public int soundId = -1;
        public String soundName;
        public int category = -1;
        public int entityId = -1;
        public double x, y, z;
        public float volume, pitch;
        public long seed;
        public float fixedRange = -1.0f;
        public boolean relative;
        public int levelEvent, data;
        public boolean global;
        public long generation;

        private void reset(long currentGeneration) {
            kind = 0;
            soundId = -1;
            soundName = null;
            category = -1;
            entityId = -1;
            x = y = z = 0.0;
            volume = pitch = 0.0f;
            seed = 0L;
            fixedRange = -1.0f;
            relative = global = false;
            levelEvent = data = 0;
            generation = currentGeneration;
        }

        private void copy(Event source) {
            kind = source.kind;
            soundId = source.soundId;
            soundName = source.soundName;
            category = source.category;
            entityId = source.entityId;
            x = source.x;
            y = source.y;
            z = source.z;
            volume = source.volume;
            pitch = source.pitch;
            seed = source.seed;
            fixedRange = source.fixedRange;
            relative = source.relative;
            levelEvent = source.levelEvent;
            data = source.data;
            global = source.global;
            generation = source.generation;
        }
    }
}
