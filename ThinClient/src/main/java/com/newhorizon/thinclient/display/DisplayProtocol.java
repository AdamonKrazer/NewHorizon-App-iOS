package com.newhorizon.thinclient.display;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Compact vanilla custom-payload protocol used instead of Forge SimpleChannel. */
public final class DisplayProtocol {
    public static final String CHANNEL = "newhorizon:display_v1";
    public static final int MAGIC = 0x4e484431; // NHD1
    public static final int VERSION = 1;
    public static final int MAX_DIMENSION_BYTES = 128;
    public static final int MAX_URL_BYTES = 4096;

    private DisplayProtocol() {
    }

    public static void encode(ByteBuffer output, DisplayMessage message) {
        output.putInt(MAGIC);
        output.put((byte) VERSION);
        output.put((byte) message.type);
        if (message instanceof DisplayMessage.Spawn) {
            writeSpawn(output, ((DisplayMessage.Spawn) message).display);
        } else if (message instanceof DisplayMessage.Remove) {
            BinaryCodec.writeUuid(output, ((DisplayMessage.Remove) message).id);
        } else if (message instanceof DisplayMessage.Navigate) {
            DisplayMessage.Navigate navigate = (DisplayMessage.Navigate) message;
            BinaryCodec.writeUuid(output, navigate.id);
            BinaryCodec.writeString(output, navigate.url, MAX_URL_BYTES);
        } else if (!(message instanceof DisplayMessage.Clear)) {
            throw new IllegalArgumentException("Unknown display message");
        }
    }

    public static DisplayMessage decode(ByteBuffer input) throws ProtocolException {
        BinaryCodec.require(input, 6);
        if (input.getInt() != MAGIC) throw new ProtocolException("Invalid display magic");
        int version = input.get() & 0xff;
        if (version != VERSION) throw new ProtocolException("Unsupported display version " + version);
        int type = input.get() & 0xff;
        DisplayMessage result;
        switch (type) {
            case DisplayMessage.SPAWN:
                result = new DisplayMessage.Spawn(readSpawn(input));
                break;
            case DisplayMessage.REMOVE:
                result = new DisplayMessage.Remove(BinaryCodec.readUuid(input));
                break;
            case DisplayMessage.CLEAR:
                result = new DisplayMessage.Clear();
                break;
            case DisplayMessage.NAVIGATE:
                result = new DisplayMessage.Navigate(BinaryCodec.readUuid(input),
                        BinaryCodec.readString(input, MAX_URL_BYTES));
                break;
            default:
                throw new ProtocolException("Unknown display message type " + type);
        }
        if (input.hasRemaining()) throw new ProtocolException("Trailing display payload bytes");
        return result;
    }

    private static void writeSpawn(ByteBuffer output, VirtualDisplay display) {
        BinaryCodec.writeUuid(output, display.id);
        BinaryCodec.writeString(output, display.dimension, MAX_DIMENSION_BYTES);
        output.putDouble(display.x).putDouble(display.y).putDouble(display.z);
        output.putFloat(display.yaw).putFloat(display.widthBlocks).putFloat(display.heightBlocks);
        output.put((byte) display.side.ordinal());
        VarInts.write(output, display.lifetimeTicks);
        VarInts.write(output, display.pixelWidth);
        VarInts.write(output, display.pixelHeight);
        BinaryCodec.writeString(output, display.url, MAX_URL_BYTES);
    }

    private static VirtualDisplay readSpawn(ByteBuffer input) throws ProtocolException {
        UUID id = BinaryCodec.readUuid(input);
        String dimension = BinaryCodec.readString(input, MAX_DIMENSION_BYTES);
        BinaryCodec.require(input, 24 + 12 + 1);
        double x = input.getDouble();
        double y = input.getDouble();
        double z = input.getDouble();
        float yaw = input.getFloat();
        float width = input.getFloat();
        float height = input.getFloat();
        DisplaySide side = DisplaySide.fromOrdinal(input.get() & 0xff);
        int lifetime = VarInts.read(input);
        int pixelWidth = VarInts.read(input);
        int pixelHeight = VarInts.read(input);
        String url = BinaryCodec.readString(input, MAX_URL_BYTES);
        try {
            return new VirtualDisplay(id, dimension, x, y, z, yaw, width, height,
                    side, lifetime, pixelWidth, pixelHeight, url);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid display dimensions");
        }
    }
}


