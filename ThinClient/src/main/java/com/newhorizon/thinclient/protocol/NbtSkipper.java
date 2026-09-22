package com.newhorizon.thinclient.protocol;

import java.nio.ByteBuffer;

/** Bounded NBT cursor used when a packet field is not needed by the thin client. */
public final class NbtSkipper {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ELEMENTS = 1_000_000;

    private NbtSkipper() {
    }

    public static void skipUnnamed(ByteBuffer input, int type) throws ProtocolException {
        skipPayload(input, type, 0, MAX_ELEMENTS);
    }

    public static void skipRoot(ByteBuffer input) throws ProtocolException {
        BinaryCodec.require(input, 1);
        int type = input.get() & 0xff;
        if (type == 0) return;
        if (type > 12) throw new ProtocolException("Unknown NBT root type " + type);
        skipString(input);
        skipPayload(input, type, 0, MAX_ELEMENTS);
    }

    private static int skipPayload(ByteBuffer input, int type, int depth, int budget)
            throws ProtocolException {
        if (depth > MAX_DEPTH) throw new ProtocolException("NBT nesting outside limit");
        if (--budget < 0) throw new ProtocolException("NBT element count outside limit");
        switch (type) {
            case 1:
                skipBytes(input, 1);
                return budget;
            case 2:
                skipBytes(input, 2);
                return budget;
            case 3:
            case 5:
                skipBytes(input, 4);
                return budget;
            case 4:
            case 6:
                skipBytes(input, 8);
                return budget;
            case 7:
                return skipArray(input, 1, budget);
            case 8:
                skipString(input);
                return budget;
            case 9: {
                BinaryCodec.require(input, 5);
                int elementType = input.get() & 0xff;
                int count = input.getInt();
                if (elementType > 12 || count < 0 || count > budget
                        || (elementType == 0 && count != 0)) {
                    throw new ProtocolException("NBT list outside limit");
                }
                for (int index = 0; index < count; index++) {
                    budget = skipPayload(input, elementType, depth + 1, budget);
                }
                return budget;
            }
            case 10:
                while (true) {
                    BinaryCodec.require(input, 1);
                    int childType = input.get() & 0xff;
                    if (childType == 0) return budget;
                    if (childType > 12) {
                        throw new ProtocolException("Unknown NBT child type " + childType);
                    }
                    skipString(input);
                    budget = skipPayload(input, childType, depth + 1, budget);
                }
            case 11:
                return skipArray(input, Integer.BYTES, budget);
            case 12:
                return skipArray(input, Long.BYTES, budget);
            default:
                throw new ProtocolException("Unknown NBT payload type " + type);
        }
    }

    private static int skipArray(ByteBuffer input, int elementBytes, int budget)
            throws ProtocolException {
        BinaryCodec.require(input, Integer.BYTES);
        int count = input.getInt();
        if (count < 0 || count > budget) {
            throw new ProtocolException("NBT array outside limit");
        }
        long bytes = (long) count * elementBytes;
        if (bytes > Integer.MAX_VALUE) throw new ProtocolException("NBT array too large");
        skipBytes(input, (int) bytes);
        return budget - count;
    }

    private static void skipString(ByteBuffer input) throws ProtocolException {
        BinaryCodec.require(input, Short.BYTES);
        int length = input.getShort() & 0xffff;
        skipBytes(input, length);
    }

    private static void skipBytes(ByteBuffer input, int count) throws ProtocolException {
        BinaryCodec.require(input, count);
        input.position(input.position() + count);
    }
}
