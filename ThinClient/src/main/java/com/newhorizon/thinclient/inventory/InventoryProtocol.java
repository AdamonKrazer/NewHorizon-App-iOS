package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Small server-authoritative inventory protocol for the independent client. */
public final class InventoryProtocol {
    public static final String CHANNEL = "newhorizon:inventory_v1";
    /** Player inventory, armour, off-hand, the 2x2 crafting grid and its result. */
    public static final int SLOT_COUNT = 46;
    public static final int OUTSIDE_SLOT = 0xff;
    public static final int BUTTON_LEFT = 0;
    public static final int BUTTON_RIGHT = 1;
    private static final int MAGIC = 0x4e484931; // NHI1
    private static final int VERSION = 2;
    private static final int OPEN = 1;
    private static final int CLICK = 2;
    private static final int CLOSE = 3;
    private static final int SELECT = 4;
    private static final int CREATIVE_CURSOR = 5;
    public static final int CURSOR_CLEAR = 0;
    public static final int CURSOR_SHRINK = 1;
    public static final int CURSOR_GROW = 2;

    private InventoryProtocol() {
    }

    public static ByteBuffer openRequest() {
        return request(OPEN, -1);
    }

    public static ByteBuffer clickRequest(int slot) {
        return clickRequest(slot, BUTTON_LEFT, false);
    }

    public static ByteBuffer clickRequest(int slot, int button, boolean shift) {
        if ((slot < 0 || slot >= SLOT_COUNT) && slot != OUTSIDE_SLOT) {
            throw new IllegalArgumentException("slot");
        }
        if (button != BUTTON_LEFT && button != BUTTON_RIGHT) {
            throw new IllegalArgumentException("button");
        }
        ByteBuffer result = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        result.putInt(MAGIC).put((byte) VERSION).put((byte) CLICK)
                .put((byte) slot).put((byte) button).put((byte) (shift ? 1 : 0));
        result.flip();
        return result;
    }

    public static ByteBuffer closeRequest() {
        return request(CLOSE, -1);
    }

    public static ByteBuffer selectRequest(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            throw new IllegalArgumentException("hotbarSlot");
        }
        return request(SELECT, hotbarSlot);
    }

    /** Mirrors CreativeModeInventoryScreen's client-side carried-stack mutation. */
    public static ByteBuffer creativeCursorRequest(int action) {
        if (action < CURSOR_CLEAR || action > CURSOR_GROW) {
            throw new IllegalArgumentException("creative cursor action");
        }
        return request(CREATIVE_CURSOR, action);
    }

    private static ByteBuffer request(int type, int slot) {
        ByteBuffer result = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        result.putInt(MAGIC).put((byte) VERSION).put((byte) type);
        if (slot >= 0) result.put((byte) slot);
        result.flip();
        return result;
    }

    static void decodeSnapshot(ByteBuffer payload, InventoryState target)
            throws ProtocolException {
        ByteBuffer input = payload.slice().order(ByteOrder.BIG_ENDIAN);
        BinaryCodec.require(input, 9);
        if (input.getInt() != MAGIC || (input.get() & 0xff) != VERSION
                || (input.get() & 0xff) != OPEN) {
            throw new ProtocolException("Invalid inventory snapshot header");
        }
        int selected = input.get() & 0xff;
        int cursorAmount = input.get() & 0xff;
        String cursorMaterial = BinaryCodec.readString(input, 96);
        String cursorName = BinaryCodec.readString(input, 160);
        BinaryCodec.require(input, 1);
        int count = input.get() & 0xff;
        if (selected > 8 || count > SLOT_COUNT) {
            throw new ProtocolException("Invalid inventory snapshot metadata");
        }
        target.beginSnapshot(selected, cursorAmount, cursorMaterial, cursorName);
        for (int index = 0; index < count; index++) {
            BinaryCodec.require(input, 2);
            int slot = input.get() & 0xff;
            int amount = input.get() & 0xff;
            String material = BinaryCodec.readString(input, 96);
            String name = BinaryCodec.readString(input, 160);
            if (slot >= SLOT_COUNT) throw new ProtocolException("Invalid inventory slot");
            target.setSlot(slot, amount, material, name);
        }
        if (input.hasRemaining()) throw new ProtocolException("Trailing inventory bytes");
        target.endSnapshot();
    }
}
