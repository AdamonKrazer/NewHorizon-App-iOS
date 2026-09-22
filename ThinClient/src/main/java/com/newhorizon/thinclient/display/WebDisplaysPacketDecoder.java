package com.newhorizon.thinclient.display;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Dependency-free decoder for the three WebDisplays auto-display packets.
 * It reads only the small subset of ScreenData NBT needed by the thin client
 * and skips every other tag without constructing a CompoundTag object graph.
 */
public final class WebDisplaysPacketDecoder {
    public static final int SPAWN_DISCRIMINATOR = 6;
    public static final int REMOVE_DISCRIMINATOR = 7;
    public static final int CLEAR_DISCRIMINATOR = 8;
    private static final int MAX_NBT_DEPTH = 16;
    private static final int MAX_NBT_ELEMENTS = 16_384;
    private static final int MAX_NBT_STRING_BYTES = 8_192;
    private static final String LIGHTWEIGHT_HOME_URL =
            "data:text/html;charset=utf-8,%3C!doctype%20html%3E%3Cmeta%20charset=utf-8%3E"
            + "%3Cstyle%3Ehtml,body%7Bwidth:100%25;height:100%25;margin:0;overflow:hidden;"
            + "background:%23101822;color:%23ff8a00;font-family:sans-serif%7Dmain%7Bheight:100%25;"
            + "display:flex;align-items:center;justify-content:center;flex-direction:column%7D"
            + "b%7Bfont-size:46px%7Dsmall%7Bmargin-top:16px;color:%23ddd%7D%3C/style%3E"
            + "%3Cmain%3E%3Cb%3ENew%20Horizon%3C/b%3E"
            + "%3Csmall%3EPRI:%20abrir%20%7C%20SEC:%20opcoes%3C/small%3E%3C/main%3E";

    private WebDisplaysPacketDecoder() {
    }

    /** Returns null for WebDisplays packets that do not concern auto displays. */
    public static DisplayMessage decode(ByteBuffer input) throws ProtocolException {
        int discriminator = VarInts.read(input);
        switch (discriminator) {
            case SPAWN_DISCRIMINATOR:
                return new DisplayMessage.Spawn(readSpawn(input));
            case REMOVE_DISCRIMINATOR:
                UUID id = BinaryCodec.readUuid(input);
                requireFinished(input);
                return new DisplayMessage.Remove(id);
            case CLEAR_DISCRIMINATOR:
                requireFinished(input);
                return new DisplayMessage.Clear();
            default:
                return null;
        }
    }

    private static VirtualDisplay readSpawn(ByteBuffer input) throws ProtocolException {
        UUID id = BinaryCodec.readUuid(input);
        String dimension = BinaryCodec.readString(input,
                DisplayProtocol.MAX_DIMENSION_BYTES);
        BinaryCodec.require(input, 24 + 12 + 1);
        double x = input.getDouble();
        double y = input.getDouble();
        double z = input.getDouble();
        float yaw = input.getFloat();
        float widthBlocks = input.getFloat();
        float heightBlocks = input.getFloat();
        DisplaySide side = DisplaySide.fromOrdinal(input.get() & 0xff);
        int lifetimeTicks = VarInts.read(input);
        ScreenFields screen = readScreenNbt(input, id.hashCode());
        requireFinished(input);

        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(widthBlocks)
                || !Float.isFinite(heightBlocks)) {
            throw new ProtocolException("Non-finite WebDisplays transform");
        }
        int pixelWidth = screen.resolutionX > 0
                ? screen.resolutionX : fallbackResolution(widthBlocks);
        int pixelHeight = screen.resolutionY > 0
                ? screen.resolutionY : fallbackResolution(heightBlocks);
        pixelWidth = clamp(pixelWidth, 16, 1024);
        pixelHeight = clamp(pixelHeight, 16, 1024);
        String initialUrl = screen.useRegisteredLinks && screen.registeredUrl != null
                ? screen.registeredUrl : screen.url;
        String url = sanitizeUrl(initialUrl);
        try {
            return new VirtualDisplay(id, dimension, x, y, z, yaw,
                    widthBlocks, heightBlocks, side, lifetimeTicks,
                    pixelWidth, pixelHeight, url);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid WebDisplays dimensions");
        }
    }

    private static ScreenFields readScreenNbt(ByteBuffer input, int linkSeed)
            throws ProtocolException {
        BinaryCodec.require(input, 1);
        int rootType = input.get() & 0xff;
        ScreenFields fields = new ScreenFields();
        if (rootType == 0) return fields;
        if (rootType != 10) throw new ProtocolException("WebDisplays NBT root is not a compound");
        readNbtString(input, 0); // unnamed root
        int[] elementBudget = {MAX_NBT_ELEMENTS};
        readCompound(input, fields, 0, elementBudget, true, linkSeed);
        return fields;
    }

    private static void readCompound(ByteBuffer input, ScreenFields fields, int depth,
                                     int[] elementBudget, boolean screenRoot, int linkSeed)
            throws ProtocolException {
        requireDepth(depth);
        while (true) {
            BinaryCodec.require(input, 1);
            int type = input.get() & 0xff;
            if (type == 0) return;
            consumeElement(elementBudget);
            String name = readNbtString(input, MAX_NBT_STRING_BYTES);
            if (screenRoot && type == 3 && "ResolutionX".equals(name)) {
                BinaryCodec.require(input, 4);
                fields.resolutionX = input.getInt();
            } else if (screenRoot && type == 3 && "ResolutionY".equals(name)) {
                BinaryCodec.require(input, 4);
                fields.resolutionY = input.getInt();
            } else if (screenRoot && type == 8 && "URL".equals(name)) {
                fields.url = readNbtString(input, DisplayProtocol.MAX_URL_BYTES);
            } else if (screenRoot && type == 1 && "UseRegLinks".equals(name)) {
                BinaryCodec.require(input, 1);
                fields.useRegisteredLinks = input.get() != 0;
            } else if (screenRoot && type == 9 && "RegLinks".equals(name)) {
                readRegisteredLinks(input, fields, depth + 1, elementBudget, linkSeed);
            } else {
                skipPayload(input, type, fields, depth + 1, elementBudget, linkSeed);
            }
        }
    }

    /** Selects one registered URL directly from the NBT list without retaining the list. */
    private static void readRegisteredLinks(ByteBuffer input, ScreenFields fields,
                                            int depth, int[] elementBudget, int linkSeed)
            throws ProtocolException {
        requireDepth(depth);
        BinaryCodec.require(input, 5);
        int elementType = input.get() & 0xff;
        int count = input.getInt();
        if (count < 0 || count > MAX_NBT_ELEMENTS || (elementType == 0 && count != 0)) {
            throw new ProtocolException("NBT list outside limit");
        }
        if (elementType != 8) {
            for (int index = 0; index < count; index++) {
                consumeElement(elementBudget);
                skipPayload(input, elementType, fields, depth + 1,
                        elementBudget, linkSeed);
            }
            return;
        }
        int selected = count == 0 ? -1 : Math.floorMod(linkSeed, count);
        String firstUsable = null;
        for (int index = 0; index < count; index++) {
            consumeElement(elementBudget);
            String candidate = readNbtString(input, DisplayProtocol.MAX_URL_BYTES);
            if (candidate != null && !candidate.trim().isEmpty()) {
                if (firstUsable == null) firstUsable = candidate;
                if (index == selected) fields.registeredUrl = candidate;
            }
        }
        if (fields.registeredUrl == null) fields.registeredUrl = firstUsable;
    }

    private static void skipPayload(ByteBuffer input, int type, ScreenFields fields,
                                    int depth, int[] elementBudget, int linkSeed)
            throws ProtocolException {
        requireDepth(depth);
        switch (type) {
            case 1:
                skipBytes(input, 1);
                return;
            case 2:
                skipBytes(input, 2);
                return;
            case 3:
            case 5:
                skipBytes(input, 4);
                return;
            case 4:
            case 6:
                skipBytes(input, 8);
                return;
            case 7:
                skipArray(input, 1);
                return;
            case 8:
                readNbtString(input, MAX_NBT_STRING_BYTES);
                return;
            case 9:
                BinaryCodec.require(input, 5);
                int elementType = input.get() & 0xff;
                int count = input.getInt();
                if (count < 0 || count > MAX_NBT_ELEMENTS || (elementType == 0 && count != 0)) {
                    throw new ProtocolException("NBT list outside limit");
                }
                for (int index = 0; index < count; index++) {
                    consumeElement(elementBudget);
                    skipPayload(input, elementType, fields, depth + 1,
                            elementBudget, linkSeed);
                }
                return;
            case 10:
                readCompound(input, fields, depth + 1, elementBudget, false, linkSeed);
                return;
            case 11:
                skipArray(input, 4);
                return;
            case 12:
                skipArray(input, 8);
                return;
            default:
                throw new ProtocolException("Unknown NBT tag type " + type);
        }
    }

    private static void skipArray(ByteBuffer input, int elementBytes)
            throws ProtocolException {
        BinaryCodec.require(input, 4);
        int count = input.getInt();
        if (count < 0 || count > MAX_NBT_ELEMENTS) {
            throw new ProtocolException("NBT array outside limit");
        }
        long bytes = (long) count * elementBytes;
        if (bytes > Integer.MAX_VALUE) throw new ProtocolException("NBT array is too large");
        skipBytes(input, (int) bytes);
    }

    private static String readNbtString(ByteBuffer input, int maxBytes)
            throws ProtocolException {
        BinaryCodec.require(input, 2);
        int length = input.getShort() & 0xffff;
        if (maxBytes > 0 && length > maxBytes) {
            throw new ProtocolException("NBT string outside limit: " + length);
        }
        BinaryCodec.require(input, length);
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("Invalid UTF-8 in NBT string");
        }
    }

    private static void skipBytes(ByteBuffer input, int count) throws ProtocolException {
        BinaryCodec.require(input, count);
        input.position(input.position() + count);
    }

    private static void requireFinished(ByteBuffer input) throws ProtocolException {
        if (input.hasRemaining()) {
            throw new ProtocolException("Trailing WebDisplays packet bytes: " + input.remaining());
        }
    }

    private static void requireDepth(int depth) throws ProtocolException {
        if (depth > MAX_NBT_DEPTH) throw new ProtocolException("NBT nesting outside limit");
    }

    private static void consumeElement(int[] budget) throws ProtocolException {
        if (--budget[0] < 0) throw new ProtocolException("NBT element count outside limit");
    }

    private static int fallbackResolution(float blocks) {
        if (!Float.isFinite(blocks) || blocks <= 0f) return 256;
        return Math.round(blocks * 128f);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String sanitizeUrl(String value) {
        if (value == null) return "about:blank";
        String url = value.trim();
        if (url.length() == 0) return "about:blank";
        if ("mod://webdisplays/main.html".equalsIgnoreCase(url)
                || "webdisplays://main.html".equalsIgnoreCase(url)) {
            return LIGHTWEIGHT_HOME_URL;
        }
        if (url.indexOf(':') < 0) return "https://" + url;
        return url;
    }

    private static final class ScreenFields {
        int resolutionX;
        int resolutionY;
        String url;
        String registeredUrl;
        boolean useRegisteredLinks;
    }
}


