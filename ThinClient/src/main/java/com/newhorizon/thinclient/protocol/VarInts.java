package com.newhorizon.thinclient.protocol;

import java.nio.ByteBuffer;

public final class VarInts {
    private VarInts() {
    }

    public static int read(ByteBuffer input) throws ProtocolException {
        int result = 0;
        for (int index = 0; index < 5; index++) {
            if (!input.hasRemaining()) throw new ProtocolException("Incomplete VarInt");
            int value = input.get() & 0xff;
            result |= (value & 0x7f) << (index * 7);
            if ((value & 0x80) == 0) return result;
        }
        throw new ProtocolException("VarInt exceeds five bytes");
    }

    public static long readLong(ByteBuffer input) throws ProtocolException {
        long result = 0L;
        for (int index = 0; index < 10; index++) {
            if (!input.hasRemaining()) throw new ProtocolException("Incomplete VarLong");
            int value = input.get() & 0xff;
            result |= (long) (value & 0x7f) << (index * 7);
            if ((value & 0x80) == 0) return result;
        }
        throw new ProtocolException("VarLong exceeds ten bytes");
    }

    /** Returns -1 without advancing when the input does not contain a full value. */
    public static int tryRead(ByteBuffer input) throws ProtocolException {
        int start = input.position();
        int result = 0;
        for (int index = 0; index < 5; index++) {
            if (!input.hasRemaining()) {
                input.position(start);
                return -1;
            }
            int value = input.get() & 0xff;
            result |= (value & 0x7f) << (index * 7);
            if ((value & 0x80) == 0) return result;
        }
        input.position(start);
        throw new ProtocolException("VarInt exceeds five bytes");
    }

    public static void write(ByteBuffer output, int value) {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            if (value != 0) current |= 0x80;
            output.put((byte) current);
        } while (value != 0);
    }

    public static int encodedSize(int value) {
        int size = 1;
        while ((value & ~0x7f) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}

