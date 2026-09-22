package com.newhorizon.thinclient.audio;

import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisAlloc;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;

/** Incremental Vorbis reader with a hard native allocation ceiling per voice. */
final class BoundedVorbisStream implements AutoCloseable {
    static final int ARENA_BYTES = 512 * 1024;
    private ByteBuffer arena;
    private long decoder;
    private long channel0;
    private long channel1;
    private int framePosition;
    private int frameLength;
    private int channels;
    private int sampleRate;
    private boolean eof;

    BoundedVorbisStream(Path path) throws IOException {
        arena = MemoryUtil.memAlloc(ARENA_BYTES);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.callocInt(1);
            STBVorbisAlloc allocator = STBVorbisAlloc.malloc(stack).alloc_buffer(arena);
            // The stdio API reads compressed bytes incrementally; it never copies
            // a complete song into either Java or direct memory.
            decoder = STBVorbis.stb_vorbis_open_filename(path.toString(), error, allocator);
            if (decoder == 0) throw new IOException("Vorbis open failed, code=" + error.get(0));
            STBVorbisInfo info = STBVorbis.stb_vorbis_get_info(decoder, STBVorbisInfo.malloc(stack));
            channels = info.channels();
            sampleRate = info.sample_rate();
            if ((channels != 1 && channels != 2) || sampleRate < 8000 || sampleRate > 192000) {
                throw new IOException("Unsupported Vorbis format: " + channels + "ch at " + sampleRate);
            }
        } catch (IOException | RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    int channels() { return channels; }
    int sampleRate() { return sampleRate; }

    boolean rewind() {
        if (decoder == 0 || !STBVorbis.stb_vorbis_seek_start(decoder)) return false;
        framePosition = frameLength = 0;
        eof = false;
        return true;
    }

    /** Fills at most target.capacity() bytes, with exactly vanilla PCM rounding. */
    int read(ByteBuffer target) throws IOException {
        target.clear();
        while (target.remaining() >= channels * 2) {
            if (framePosition >= frameLength && !nextFrame()) break;
            int count = Math.min(frameLength - framePosition, target.remaining() / (channels * 2));
            for (int sample = 0; sample < count; sample++) {
                long offset = (long) framePosition++ * 4;
                target.putShort(VanillaSoundMath.pcm16(MemoryUtil.memGetFloat(channel0 + offset)));
                if (channels == 2) {
                    target.putShort(VanillaSoundMath.pcm16(MemoryUtil.memGetFloat(channel1 + offset)));
                }
            }
        }
        target.flip();
        return target.remaining();
    }

    private boolean nextFrame() throws IOException {
        if (eof || decoder == 0) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channelCount = stack.callocInt(1);
            PointerBuffer output = stack.callocPointer(1);
            frameLength = STBVorbis.stb_vorbis_get_frame_float(decoder, channelCount, output);
            framePosition = 0;
            if (frameLength == 0) {
                int error = STBVorbis.stb_vorbis_get_error(decoder);
                if (error != STBVorbis.VORBIS__no_error) {
                    throw new IOException("Vorbis decode failed, code=" + error);
                }
                eof = true;
                return false;
            }
            if (channelCount.get(0) != channels || output.get(0) == 0) {
                throw new IOException("Vorbis channel layout changed");
            }
            channel0 = MemoryUtil.memGetAddress(output.get(0));
            if (channels == 2) channel1 = MemoryUtil.memGetAddress(output.get(0) + Pointer.POINTER_SIZE);
            return true;
        }
    }

    @Override public void close() {
        if (decoder != 0) {
            STBVorbis.stb_vorbis_close(decoder);
            decoder = 0;
        }
        if (arena != null) {
            MemoryUtil.memFree(arena);
            arena = null;
        }
        eof = true;
    }
}
