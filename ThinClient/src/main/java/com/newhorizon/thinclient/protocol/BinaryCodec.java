package com.newhorizon.thinclient.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class BinaryCodec {
    private BinaryCodec() {
    }

    public static void writeUuid(ByteBuffer output, UUID value) {
        output.putLong(value.getMostSignificantBits());
        output.putLong(value.getLeastSignificantBits());
    }

    public static UUID readUuid(ByteBuffer input) throws ProtocolException {
        require(input, 16);
        return new UUID(input.getLong(), input.getLong());
    }

    public static void writeByteArray(ByteBuffer output, byte[] value, int maxBytes) {
        if (value.length > maxBytes) throw new IllegalArgumentException("Byte array is too long");
        VarInts.write(output, value.length);
        output.put(value);
    }

    public static byte[] readByteArray(ByteBuffer input, int maxBytes) throws ProtocolException {
        int length = VarInts.read(input);
        if (length < 0 || length > maxBytes) {
            throw new ProtocolException("Byte array length outside limit: " + length);
        }
        require(input, length);
        byte[] result = new byte[length];
        input.get(result);
        return result;
    }

    public static void writeString(ByteBuffer output, String value, int maxBytes) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxBytes) throw new IllegalArgumentException("String is too long");
        VarInts.write(output, encoded.length);
        output.put(encoded);
    }

    public static String readString(ByteBuffer input, int maxBytes) throws ProtocolException {
        int length = VarInts.read(input);
        if (length < 0 || length > maxBytes) {
            throw new ProtocolException("String byte length outside limit: " + length);
        }
        require(input, length);
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("Invalid UTF-8 string");
        }
    }

    public static void require(ByteBuffer input, int count) throws ProtocolException {
        if (count < 0 || input.remaining() < count) {
            throw new ProtocolException("Truncated packet: need=" + count
                    + " remaining=" + input.remaining());
        }
    }
}


