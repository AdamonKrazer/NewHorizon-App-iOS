package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.NbtSkipper;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Server-owned 1.20.1 crafting and generic chest menus. Never recreates server stacks. */
public final class WorkbenchState {
    public static final int OPEN_SCREEN = 0x30, CLOSE_SCREEN = 0x11;
    public static final int CLICK = 0x0b, CLOSE = 0x0c, CRAFTING = 11;
    private static final String[] ITEMS = itemNames();
    private static final Stack EMPTY = new Stack(0, 0, "");
    private Stack[] slots = new Stack[46];
    private Stack cursor = EMPTY;
    private int window = -1;
    private int rows;
    private int stateId = -1;
    private String title = "";
    private long revision;
    private boolean ready;

    public WorkbenchState() { Arrays.fill(slots, EMPTY); }

    public synchronized void open(ByteBuffer packet) throws ProtocolException {
        int id = VarInts.read(packet), type = VarInts.read(packet);
        String nextTitle = BinaryCodec.readString(packet, 262144);
        if (id <= 0 || id > 127 || packet.hasRemaining()) throw new ProtocolException("Invalid menu open");
        clear();
        if (type == CRAFTING || (type >= 0 && type <= 5)) {
            rows = type == CRAFTING ? 0 : type + 1;
            slots = new Stack[rows == 0 ? 46 : rows * 9 + 36];
            Arrays.fill(slots, EMPTY);
            title = nextTitle.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
            window = id;
        }
    }

    public synchronized int window() { return window; }
    public synchronized long revision() { return revision; }
    public synchronized boolean ready() { return ready && window > 0; }
    public synchronized int rows() { return rows; }
    public synchronized int stateId() { return stateId; }
    public synchronized String title() { return title; }

    public synchronized void serverClose(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, 1);
        int id = packet.get() & 255;
        if (packet.hasRemaining()) throw new ProtocolException("Invalid menu close");
        if (id == window) clear();
    }

    public synchronized void clear() {
        window = -1; ready = false; cursor = EMPTY; rows = 0; title = ""; stateId = -1;
        if (slots.length != 46) slots = new Stack[46];
        Arrays.fill(slots, EMPTY); revision++;
    }

    /** Inventory window 0 and delayed packets for closed windows cannot replace this menu. */
    public synchronized void readContent(ByteBuffer packet, boolean single) throws ProtocolException {
        BinaryCodec.require(packet, 1);
        int id = packet.get();
        if (window < 0 || (id != window && !(single && id == -1))) return;
        int nextState = VarInts.read(packet);
        if (nextState < 0 || nextState > 32767) throw new ProtocolException("Invalid menu state id");
        if (single) {
            BinaryCodec.require(packet, 2);
            int slot = packet.getShort();
            Stack stack = readStack(packet);
            if (packet.hasRemaining()) throw new ProtocolException("Trailing menu slot bytes");
            if (id == -1 && slot == -1) cursor = stack;
            else if (id == window && slot >= 0 && slot < slots.length) slots[slot] = stack;
        } else {
            int count = VarInts.read(packet);
            if (count != slots.length) throw new ProtocolException("Invalid menu size");
            Stack[] next = new Stack[count];
            for (int slot = 0; slot < count; slot++) next[slot] = readStack(packet);
            Stack carried = readStack(packet);
            if (packet.hasRemaining()) throw new ProtocolException("Trailing menu contents");
            System.arraycopy(next, 0, slots, 0, count);
            cursor = carried; ready = true;
        }
        stateId = nextState;
        revision++;
    }

    public static int menuSlot(int uiSlot) {
        if (uiSlot == InventoryProtocol.OUTSIDE_SLOT) return -999;
        if (uiSlot >= 0 && uiSlot < 9) return uiSlot + 37;
        if (uiSlot >= 9 && uiSlot < 36) return uiSlot + 1;
        if (uiSlot == 36) return 0;
        if (uiSlot >= 37 && uiSlot < 46) return uiSlot - 36;
        throw new IllegalArgumentException("Crafting slot");
    }

    public static void writeClick(ByteBuffer out, int window, int slot, int button, boolean shift) {
        writeMappedClick(out, window, menuSlot(slot), button, shift);
    }

    public synchronized int serverSlot(int uiSlot) {
        if (rows == 0) return menuSlot(uiSlot);
        if (uiSlot == InventoryProtocol.OUTSIDE_SLOT) return -999;
        int top = rows * 9;
        if (uiSlot >= 0 && uiSlot < 9) return top + 27 + uiSlot;
        if (uiSlot >= 9 && uiSlot < 36) return top + uiSlot - 9;
        if (uiSlot >= 36 && uiSlot < 36 + top) return uiSlot - 36;
        throw new IllegalArgumentException("Chest slot");
    }

    public synchronized void writeMenuClick(ByteBuffer out, int expectedWindow, int slot, int button, boolean shift) {
        if (expectedWindow != window || !ready()) throw new IllegalArgumentException("Stale menu click");
        writeMappedClick(out, window, serverSlot(slot), button, shift);
    }

    private static void writeMappedClick(ByteBuffer out, int window, int menu, int button, boolean shift) {
        if (window <= 0 || window > 127 || button < 0 || button > 1) throw new IllegalArgumentException("Menu click");
        VarInts.write(out, CLICK); out.put((byte)window);
        // No local recipe/stack prediction. Vanilla accepts a stale state id, applies the
        // click to its real menu and broadcasts the complete resulting slots and cursor.
        // -1 cannot equal the server's 15-bit state id, including when it wraps to zero.
        VarInts.write(out, -1); out.putShort((short)menu).put((byte)button);
        VarInts.write(out, shift && menu >= 0 ? 1 : 0); // QUICK_MOVE / PICKUP
        VarInts.write(out, 0); // No predicted slot changes.
        out.put((byte)0); // Predicted carried item only, not the authoritative server cursor.
    }

    public static void writeClose(ByteBuffer out, int window) {
        if (window <= 0 || window > 127) throw new IllegalArgumentException("Crafting window");
        VarInts.write(out, CLOSE); out.put((byte)window);
    }

    public synchronized String encodeForUi(InventoryState inventory) {
        StringBuilder out = new StringBuilder(2048);
        out.append("0\t").append(cursor.count).append('\t').append(material(cursor, inventory))
                .append("\t\t0\t").append(rows == 0 ? "crafting" : "chest").append('\t')
                .append(cursor.tag == null ? "?" : cursor.tag);
        if (rows > 0) out.append('\t').append(rows).append('\t').append(title);
        out.append('\n');
        for (int ui = 0; ui < slots.length; ui++) {
            Stack stack = slots[serverSlot(ui)];
            out.append(ui).append('\t').append(stack.count).append('\t').append(material(stack, inventory))
                    .append("\t\t").append(stack.id).append('\t')
                    .append(stack.tag == null ? "?" : stack.tag).append('\n');
        }
        return out.toString();
    }

    private static String material(Stack stack, InventoryState inventory) {
        if (stack.count == 0) return "";
        String vanilla = vanillaMaterial(stack.id);
        if (!vanilla.isEmpty()) return vanilla;
        return inventory.materialForProtocolId(stack.id);
    }

    static String vanillaMaterial(int id) {
        return id > 0 && id < ITEMS.length && ITEMS[id] != null ? ITEMS[id] : "";
    }

    private static Stack readStack(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in, 1);
        if (in.get() == 0) return EMPTY;
        int id = VarInts.read(in);
        BinaryCodec.require(in, 1); int count = in.get() & 255;
        int start = in.position(); NbtSkipper.skipRoot(in);
        int end = in.position(); String tag = null;
        // Tags only describe icons here; clicks never send them back or lose server NBT.
        if (end - start <= 4096) {
            StringBuilder hex = new StringBuilder((end - start) * 2);
            String digits = "0123456789abcdef";
            for (int i = start; i < end; i++) { int b = in.get(i) & 255; hex.append(digits.charAt(b >> 4)).append(digits.charAt(b & 15)); }
            tag = hex.toString();
        }
        if (id < 0) throw new ProtocolException("Invalid crafting item id");
        return new Stack(id, count, tag);
    }

    private static String[] itemNames() {
        String[] names = new String[1255];
        try (InputStream stream = WorkbenchState.class.getResourceAsStream("/assets/newhorizon/vanilla_item_ids_1_20_1.tsv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t"); names[Integer.parseInt(fields[0])] = fields[1];
            }
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
        return names;
    }

    private static final class Stack {
        final int id, count; final String tag;
        Stack(int id, int count, String tag) { this.id = id; this.count = count; this.tag = tag; }
    }
}
