package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;
import com.newhorizon.thinclient.world.EntityTracker;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTSourceDistanceModel;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Bounded, worker-owned vanilla sound playback. No audio file IO, Vorbis decode,
 * OpenAL calls or native allocation is performed by the render/network threads.
 * Every sound is streamed, including small effects: there is no decoded cache.
 */
public final class ThinSoundEngine implements AutoCloseable {
    public static final int MAX_SOURCES = 24;
    public static final int BUFFER_BYTES = 16 * 1024;
    public static final int BUFFERS_PER_SOURCE = 3;
    // OpenAL may internally store float PCM (twice our signed-short input).
    private static final long VOICE_BYTES = BoundedVorbisStream.ARENA_BYTES
            + 2L * BUFFER_BYTES * BUFFERS_PER_SOURCE + 16 * 1024;
    // Compact metadata object/string arrays, queues, shared PCM and native overhead.
    private static final long ENGINE_BYTES = 2 * 1024 * 1024;
    private final MemoryBudget budget;
    private final Path assetsDirectory;
    private final SoundEventQueue queue;
    private final EntityTracker entities;
    private final Voice[] voices = new Voice[MAX_SOURCES];
    private final String[] playingNames = new String[MAX_SOURCES];
    private final SoundEventQueue.Event event = new SoundEventQueue.Event();
    private final double[] entityPosition = new double[3];
    private final float[] orientation = new float[6];
    private final Thread worker;
    private volatile boolean closed;
    private volatile String status = "starting";
    private volatile float master = 1;
    private volatile float[] categoryVolumes = allVolumes();
    private volatile int activeSources;
    private volatile long played;
    private volatile long dropped;
    private volatile long missing;
    private double requestedX, requestedY, requestedZ;
    private float requestedYaw, requestedPitch;
    private int requestedEntityId = -1;
    private double requestedFeetY;
    private double listenerX, listenerY, listenerZ;
    private int playerEntityId = -1;
    private double playerFeetY;
    private long device;
    private long context;
    private boolean sourceDistanceModel;
    private ByteBuffer pcm;
    private VanillaSoundCatalog catalog;
    private MemoryBudget.Lease engineLease;
    private int diagnosticCount;

    public ThinSoundEngine(MemoryBudget budget, Path assetsDirectory,
                           SoundEventQueue queue, EntityTracker entities) {
        if (budget == null || assetsDirectory == null || queue == null || entities == null) {
            throw new NullPointerException("Sound engine dependencies");
        }
        this.budget = budget;
        this.assetsDirectory = assetsDirectory;
        this.queue = queue;
        this.entities = entities;
        worker = new Thread(this::run, "NH-thin-audio");
        worker.setDaemon(true);
    }

    public void start() { worker.start(); }

    public synchronized void updateListener(double x, double y, double z, float yaw, float pitch) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        requestedX = x;
        requestedY = y;
        requestedZ = z;
        requestedYaw = yaw;
        requestedPitch = pitch;
    }

    /** The local player is intentionally absent from EntityTracker's remote set. */
    public synchronized void setLocalPlayer(int entityId, double feetY) {
        requestedEntityId = entityId;
        requestedFeetY = feetY;
    }

    public void setVolumes(float masterVolume, float[] categories) {
        if (categories == null || categories.length != 10) throw new IllegalArgumentException("10 sound categories required");
        float[] copy = categories.clone();
        for (int i = 0; i < copy.length; i++) copy[i] = VanillaSoundMath.clamp(copy[i], 0, 1);
        // MASTER is the listener gain, not an additional per-source multiplier.
        copy[0] = 1;
        categoryVolumes = copy;
        master = VanillaSoundMath.clamp(masterVolume, 0, 1);
    }

    public boolean play(String name, int category, double x, double y, double z,
                        float volume, float pitch, long seed) {
        return !closed && queue.play(name, category, x, y, z, volume, pitch, seed);
    }

    public void stop(String name, int category) { queue.stop(name, category); }

    public synchronized boolean isPlaying(String name) {
        for (String playing : playingNames) if (name.equals(playing)) return true;
        return false;
    }

    public String status() { return status; }
    public int activeSources() { return activeSources; }
    public long playedCount() { return played; }
    public long droppedCount() { return dropped; }
    public long missingCount() { return missing; }

    private void run() {
        try {
            engineLease = budget.tryReserve(MemoryCategory.AUDIO, ENGINE_BYTES);
            if (engineLease == null) throw new IOException("Audio memory budget unavailable");
            catalog = VanillaSoundCatalog.load();
            initializeOpenAL();
            pcm = MemoryUtil.memAlloc(BUFFER_BYTES).order(ByteOrder.nativeOrder());
            long generation = queue.generation();
            status = "ready";
            System.out.println("[NH-THIN-AUDIO] ready: vanilla 1.20.1, voices=" + MAX_SOURCES
                    + ", streamed PCM, perVoice=" + VOICE_BYTES);
            while (!closed) {
                long currentGeneration = queue.generation();
                if (generation != currentGeneration) {
                    stopVoices(null, -1);
                    generation = currentGeneration;
                }
                applyListener();
                for (int index = 0; index < voices.length; index++) {
                    Voice voice = voices[index];
                    if (voice == null) continue;
                    try {
                        if (!voice.update()) removeVoice(index);
                    } catch (IOException | RuntimeException failure) {
                        diagnostic("stream " + voice.name, failure);
                        removeVoice(index);
                    }
                }
                // Limit command work between refills even if a server floods sounds.
                for (int count = 0; count < 32 && !closed && queue.poll(event); count++) {
                    if (event.generation != queue.generation()) continue;
                    if (event.generation != generation) {
                        stopVoices(null, -1);
                        generation = event.generation;
                    }
                    if (event.kind == SoundEventQueue.STOP) stopVoices(event.soundName, event.category);
                    else if (event.kind == SoundEventQueue.AMBIENT_LOOP) {
                        boolean exists = false;
                        for (Voice voice : voices) {
                            if (voice == null || !voice.loop) continue;
                            boolean match = voice.name.equals(event.soundName);
                            voice.fadeOut = !match;
                            exists |= match;
                        }
                        if (!exists && event.soundName != null) start(event);
                    }
                    else if (event.kind == SoundEventQueue.LEVEL) {
                        if (event.levelEvent == 1010 || event.levelEvent == 1011) {
                            for (int i = 0; i < voices.length; i++) {
                                Voice voice = voices[i];
                                if (voice != null && voice.category == SoundEventQueue.RECORDS
                                        && voice.x == event.x + 0.5 && voice.y == event.y + 0.5
                                        && voice.z == event.z + 0.5) removeVoice(i);
                            }
                        }
                        if (event.global) {
                            double dx = event.x - listenerX, dy = event.y - listenerY, dz = event.z - listenerZ;
                            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            event.x = listenerX - 0.5 + (length > 0 ? dx / length * 2 : 0);
                            event.y = listenerY - 0.5 + (length > 0 ? dy / length * 2 : 0);
                            event.z = listenerZ - 0.5 + (length > 0 ? dz / length * 2 : 0);
                        }
                        VanillaLevelSounds.play(event, catalog, queue);
                    }
                    else start(event);
                }
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { if (closed) break; }
            }
        } catch (Throwable failure) {
            if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
            status = "unavailable: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            System.err.println("[NH-THIN-AUDIO] " + status);
        } finally {
            cleanup();
            if (closed) status = "closed";
        }
    }

    private void initializeOpenAL() throws IOException {
        device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0) throw new IOException("OpenAL default device unavailable");
        ALCCapabilities deviceCaps = ALC.createCapabilities(device);
        context = ALC10.alcCreateContext(device, (IntBuffer) null);
        if (context == 0 || !ALC10.alcMakeContextCurrent(context)) {
            throw new IOException("OpenAL context unavailable");
        }
        ALCapabilities capabilities = AL.createCapabilities(deviceCaps);
        sourceDistanceModel = capabilities.AL_EXT_source_distance_model;
        if (sourceDistanceModel) AL10.alEnable(EXTSourceDistanceModel.AL_SOURCE_DISTANCE_MODEL);
        AL10.alDistanceModel(AL11.AL_LINEAR_DISTANCE);
        // Minecraft leaves source/listener velocities at zero: no Doppler shift.
        AL10.alListener3f(AL10.AL_VELOCITY, 0, 0, 0);
        checkAl("initialize");
    }

    private synchronized void applyListener() {
        listenerX = requestedX;
        listenerY = requestedY;
        listenerZ = requestedZ;
        playerEntityId = requestedEntityId;
        playerFeetY = requestedFeetY;
        VanillaSoundMath.orientation(requestedYaw, requestedPitch, orientation);
        AL10.alListener3f(AL10.AL_POSITION, (float) listenerX, (float) listenerY, (float) listenerZ);
        AL10.alListenerfv(AL10.AL_ORIENTATION, orientation);
        AL10.alListenerf(AL10.AL_GAIN, master);
    }

    private void start(SoundEventQueue.Event requested) {
        String name = requested.soundName;
        if (name == null) name = catalog.eventName(requested.soundId);
        if (name == null) { missing++; return; }
        if (name.indexOf(':') < 0) name = "minecraft:" + name;
        if (requested.category < 0 || requested.category >= 10 || master == 0) return;
        VanillaSoundCatalog.Sample sample = catalog.choose(name, requested.seed);
        if (sample == null) { missing++; return; }
        float volume = requested.volume * sample.volume;
        if (!(VanillaSoundMath.gain(volume, categoryVolumes[requested.category]) > 0)) return;
        int slot = -1;
        for (int i = 0; i < voices.length; i++) if (voices[i] == null) { slot = i; break; }
        if (slot == -1) { dropped++; return; }
        double x = requested.x, y = requested.y, z = requested.z;
        if (requested.kind == SoundEventQueue.ENTITY) {
            if (!position(requested.entityId)) return;
            x = entityPosition[0]; y = entityPosition[1]; z = entityPosition[2];
        }
        if (!requested.relative) {
            double dx = x - listenerX, dy = y - listenerY, dz = z - listenerZ;
            double distance = VanillaSoundMath.distance(volume, sample.attenuationDistance);
            if (dx * dx + dy * dy + dz * dz >= distance * distance) return;
        }
        MemoryBudget.Lease lease = budget.tryReserve(MemoryCategory.AUDIO, VOICE_BYTES);
        if (lease == null) { dropped++; return; }
        Voice voice = new Voice(name, requested.category, volume,
                requested.kind == SoundEventQueue.ENTITY ? requested.entityId : -1, lease);
        voice.loop = requested.kind == SoundEventQueue.AMBIENT_LOOP;
        voice.fade = voice.loop ? 0 : 1;
        voice.x = x; voice.y = y; voice.z = z;
        try {
            String sha1 = sample.sha1;
            if (sha1 == null || sha1.length() != 40) throw new IOException("Missing vanilla asset hash");
            Path path = assetsDirectory.resolve("objects").resolve(sha1.substring(0, 2)).resolve(sha1);
            if (!Files.isRegularFile(path)) throw new IOException("Missing sound asset " + sample.assetPath);
            voice.stream = new BoundedVorbisStream(path);
            voice.source = AL10.alGenSources();
            checkAl("allocate source");
            for (int i = 0; i < voice.buffers.length; i++) {
                voice.buffers[i] = AL10.alGenBuffers();
                checkAl("allocate buffer");
            }
            AL10.alSourcef(voice.source, AL10.AL_PITCH, VanillaSoundMath.pitch(requested.pitch * sample.pitch));
            AL10.alSourcef(voice.source, AL10.AL_GAIN, VanillaSoundMath.gain(volume * voice.fade, categoryVolumes[voice.category]));
            AL10.alSourcei(voice.source, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcei(voice.source, AL10.AL_SOURCE_RELATIVE, requested.relative ? AL10.AL_TRUE : AL10.AL_FALSE);
            AL10.alSource3f(voice.source, AL10.AL_POSITION,
                    requested.relative ? 0 : (float) x, requested.relative ? 0 : (float) y,
                    requested.relative ? 0 : (float) z);
            AL10.alSource3f(voice.source, AL10.AL_VELOCITY, 0, 0, 0);
            if (sourceDistanceModel) {
                AL10.alSourcei(voice.source, AL10.AL_DISTANCE_MODEL,
                        requested.relative ? AL10.AL_NONE : AL11.AL_LINEAR_DISTANCE);
            }
            AL10.alSourcef(voice.source, AL10.AL_ROLLOFF_FACTOR, requested.relative ? 0 : 1);
            AL10.alSourcef(voice.source, AL10.AL_REFERENCE_DISTANCE, 0);
            // SoundEvent.fixedRange controls the server's broadcast radius. The
            // vanilla client attenuation uses the chosen sample's distance.
            AL10.alSourcef(voice.source, AL10.AL_MAX_DISTANCE,
                    VanillaSoundMath.distance(volume, sample.attenuationDistance));
            for (int buffer : voice.buffers) if (!voice.fill(buffer)) break;
            if (voice.queued == 0) { voice.close(); return; }
            checkAl("configure source");
            AL10.alSourcePlay(voice.source);
            checkAl("play source");
            voices[slot] = voice;
            synchronized (this) { playingNames[slot] = name; }
            activeSources++;
            played++;
        } catch (IOException | RuntimeException | LinkageError failure) {
            missing++;
            diagnostic("play " + name, failure);
            voice.close();
        }
    }

    private boolean position(int entityId) {
        if (entityId == playerEntityId && entityId >= 0) {
            entityPosition[0] = listenerX;
            entityPosition[1] = playerFeetY;
            entityPosition[2] = listenerZ;
            return true;
        }
        return entities.position(entityId, System.nanoTime(), entityPosition);
    }

    private void stopVoices(String name, int category) {
        for (int i = 0; i < voices.length; i++) {
            Voice voice = voices[i];
            if (voice != null && (name == null || name.equals(voice.name))
                    && (category == -1 || category == voice.category)) removeVoice(i);
        }
    }

    private void removeVoice(int slot) {
        Voice voice = voices[slot];
        voices[slot] = null;
        synchronized (this) { playingNames[slot] = null; }
        if (voice != null) {
            activeSources--;
            voice.close();
        }
    }

    private void diagnostic(String operation, Throwable failure) {
        if (diagnosticCount++ < 8) System.err.println("[NH-THIN-AUDIO] " + operation + ": " + failure);
    }

    private void cleanup() {
        try { stopVoices(null, -1); }
        finally {
            if (pcm != null) { MemoryUtil.memFree(pcm); pcm = null; }
            if (context != 0) {
                ALC10.alcMakeContextCurrent(0);
                ALC10.alcDestroyContext(context);
                context = 0;
                AL.setCurrentThread(null);
            }
            if (device != 0) { ALC10.alcCloseDevice(device); device = 0; }
            if (engineLease != null) { engineLease.close(); engineLease = null; }
            catalog = null;
        }
    }

    @Override public void close() {
        closed = true;
        worker.interrupt();
        if (Thread.currentThread() != worker) {
            try { worker.join(2000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static float[] allVolumes() {
        float[] levels = new float[10];
        Arrays.fill(levels, 1);
        return levels;
    }

    private static void checkAl(String operation) throws IOException {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) throw new IOException("OpenAL " + operation + " error=" + error);
    }

    private final class Voice implements AutoCloseable {
        final String name;
        final int category;
        final float volume;
        final int entityId;
        final MemoryBudget.Lease lease;
        final int[] buffers = new int[BUFFERS_PER_SOURCE];
        int source;
        int queued;
        double x, y, z;
        boolean eof;
        boolean loop, fadeOut;
        float fade = 1;
        long lastFadeNanos = System.nanoTime();
        BoundedVorbisStream stream;

        Voice(String name, int category, float volume, int entityId, MemoryBudget.Lease lease) {
            this.name = name;
            this.category = category;
            this.volume = volume;
            this.entityId = entityId;
            this.lease = lease;
        }

        boolean fill(int buffer) throws IOException {
            if (eof) return false;
            if (stream.read(pcm) == 0) {
                if (!loop || !stream.rewind() || stream.read(pcm) == 0) { eof = true; return false; }
            }
            AL10.alBufferData(buffer, stream.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
                    pcm, stream.sampleRate());
            AL10.alSourceQueueBuffers(source, buffer);
            checkAl("queue PCM");
            queued++;
            return true;
        }

        boolean update() throws IOException {
            if (loop) {
                long now = System.nanoTime();
                float delta = (now - lastFadeNanos) / 2_000_000_000f;
                lastFadeNanos = now;
                fade = VanillaSoundMath.clamp(fade + (fadeOut ? -delta : delta), 0, 1);
                if (fadeOut && fade == 0) return false;
            }
            if (entityId >= 0) {
                if (!position(entityId)) return false;
                AL10.alSource3f(source, AL10.AL_POSITION,
                        (float) entityPosition[0], (float) entityPosition[1], (float) entityPosition[2]);
            }
            AL10.alSourcef(source, AL10.AL_GAIN, VanillaSoundMath.gain(volume * fade, categoryVolumes[category]));
            int processed = Math.min(BUFFERS_PER_SOURCE, AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED));
            while (processed-- > 0) {
                int buffer = AL10.alSourceUnqueueBuffers(source);
                queued--;
                fill(buffer);
            }
            if (queued == 0) return false;
            // Recover after a scheduling stall without replaying already consumed PCM.
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) AL10.alSourcePlay(source);
            checkAl("update stream");
            return true;
        }

        @Override public void close() {
            try {
                if (source != 0) {
                    AL10.alSourceStop(source);
                    AL10.alDeleteSources(source);
                    source = 0;
                }
                for (int i = 0; i < buffers.length; i++) {
                    if (buffers[i] != 0) { AL10.alDeleteBuffers(buffers[i]); buffers[i] = 0; }
                }
            } finally {
                try { if (stream != null) { stream.close(); stream = null; } }
                finally { lease.close(); }
            }
        }
    }
}
