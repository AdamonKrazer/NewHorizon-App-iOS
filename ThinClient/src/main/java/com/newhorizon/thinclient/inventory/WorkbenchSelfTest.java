package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;

public final class WorkbenchSelfTest {
    public static void run() throws Exception {
        WorkbenchState menu = new WorkbenchState(); InventoryState inventory = new InventoryState();
        inventory.setCreativeMode(true);
        menu.open(open(7, 11));
        require(menu.window() == 7 && !menu.ready(), "wait for server contents");
        menu.readContent(contents(7), false);
        require(menu.ready(), "initial contents ready");
        String[] lines = menu.encodeForUi(inventory).split("\n");
        require(lines[0].equals("0\t3\toak_planks\t\t0\tcrafting\t00"), "native cursor, not creative catalog");
        boolean[] visited = new boolean[46];
        for (int ui = 0; ui < 46; ui++) {
            int slot = WorkbenchState.menuSlot(ui);
            require(!visited[slot], "unique mapping"); visited[slot] = true;
            require(lines[ui + 1].startsWith(ui + "\t" + (slot + 1) + "\tstone\t\t1\t00"), "all native slots mapped");
            for (int button = 0; button <= 1; button++) for (boolean shift : new boolean[]{false,true}) {
                ByteBuffer out = ByteBuffer.allocate(64);
                WorkbenchState.writeClick(out, 7, ui, button, shift); out.flip();
                require(VarInts.read(out) == 0x0b && out.get() == 7, "native click framing");
                require(VarInts.read(out) == -1 && out.getShort() == slot && out.get() == button, "slot and button");
                require(VarInts.read(out) == (shift ? 1 : 0), "pickup/shift craft");
                require(VarInts.read(out) == 0 && out.get() == 0 && !out.hasRemaining(), "no client stack replacement");
            }
        }
        require(WorkbenchState.menuSlot(36) == 0 && WorkbenchState.menuSlot(45) == 9, "output and ninth ingredient");
        require(WorkbenchState.menuSlot(0) == 37 && WorkbenchState.menuSlot(9) == 10, "player inventory positions");
        ByteBuffer outside = ByteBuffer.allocate(32);
        WorkbenchState.writeClick(outside, 7, 255, 1, true); outside.flip();
        VarInts.read(outside); outside.get(); VarInts.read(outside);
        require(outside.getShort() == -999 && outside.get() == 1 && VarInts.read(outside) == 0, "drop one outside");
        long revision = menu.revision();
        menu.readContent(contents(0), false); menu.readContent(contents(8), false);
        require(menu.revision() == revision, "player and foreign contents ignored");
        menu.readContent(single(7, 0, 280, 4), true);
        require(menu.encodeForUi(inventory).split("\n")[37].contains("\t4\t"), "server recipe result");
        menu.readContent(single(-1, -1, 1, 2), true);
        require(menu.encodeForUi(inventory).startsWith("0\t2\tstone\t"), "native cursor update");
        menu.readContent(single(7, 9, 0, 0), true);
        require(menu.encodeForUi(inventory).split("\n")[46].equals("45\t0\t\t\t0\t"), "consumed ingredient cleared");
        String before = menu.encodeForUi(inventory);
        ByteBuffer truncated = contents(7); truncated.limit(truncated.limit() - 1);
        try { menu.readContent(truncated, false); throw new AssertionError("truncated content accepted"); }
        catch (ProtocolException expected) { require(menu.encodeForUi(inventory).equals(before), "invalid snapshot is atomic"); }
        menu.serverClose(ByteBuffer.wrap(new byte[]{8})); require(menu.window() == 7, "stale close ignored");
        ByteBuffer close = ByteBuffer.allocate(8); WorkbenchState.writeClose(close, 7); close.flip();
        require(VarInts.read(close) == 0x0c && close.get() == 7 && !close.hasRemaining(), "close packet returns server ingredients/cursor");
        menu.serverClose(ByteBuffer.wrap(new byte[]{7}));
        require(menu.window() == -1 && !menu.ready(), "server close");
        revision = menu.revision(); menu.readContent(contents(7), false); menu.readContent(single(-1, -1, 1, 2), true);
        require(menu.revision() == revision, "late data cannot reopen screen");
        menu.open(open(8, 11)); require(!menu.ready(), "reopen waits for fresh slots");
        require(!menu.encodeForUi(inventory).contains("\tstone\t"), "reopen does not reuse previous stacks");
        menu.open(open(9, 6)); require(menu.window() == -1, "unsupported menu is never interpreted as crafting");
        try { WorkbenchState.menuSlot(46); throw new AssertionError("invalid slot accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("WorkbenchSelfTest passed: native 3x3 slots, clicks, cursor, server result, close and stale packets");
        GenericMenuSelfTest.run();
    }
    private static ByteBuffer open(int id, int type) {
        ByteBuffer out = ByteBuffer.allocate(128); VarInts.write(out, id); VarInts.write(out, type);
        BinaryCodec.writeString(out, "{\"translate\":\"container.crafting\"}", 128); return (ByteBuffer)out.flip();
    }
    private static ByteBuffer contents(int window) {
        ByteBuffer out = ByteBuffer.allocate(1024); out.put((byte)window); VarInts.write(out, 3); VarInts.write(out, 46);
        for (int i = 0; i < 46; i++) stack(out, 1, i + 1);
        stack(out, 23, 3); return (ByteBuffer)out.flip();
    }
    private static ByteBuffer single(int window, int slot, int item, int count) {
        ByteBuffer out = ByteBuffer.allocate(64); out.put((byte)window); VarInts.write(out, 4); out.putShort((short)slot);
        stack(out, item, count); return (ByteBuffer)out.flip();
    }
    private static void stack(ByteBuffer out, int id, int count) {
        out.put((byte)(count == 0 ? 0 : 1));
        if (count > 0) { VarInts.write(out, id); out.put((byte)count).put((byte)0); }
    }
    private static void require(boolean valid, String label) { if (!valid) throw new AssertionError(label); }
}
