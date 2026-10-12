package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;

/** Tests native menu packets, including the live SkyWars CHEST36 / Play Skywars menu. */
public final class GenericMenuSelfTest {
    public static void main(String[] args) throws Exception { WorkbenchSelfTest.run(); }

    public static void run() throws Exception {
        InventoryState inventory = new InventoryState();
        for (int rows = 1; rows <= 6; rows++) {
            WorkbenchState menu = new WorkbenchState();
            int top = rows * 9, count = top + 36, window = 10 + rows;
            menu.open(open(window, rows - 1, "{\"text\":\"Play Skywars\"}"));
            require(menu.window() == window && menu.rows() == rows && !menu.ready(), "supported generic menu");
            menu.readContent(content(window, 19, count), false);
            require(menu.ready() && menu.stateId() == 19, "server snapshot state");
            String[] lines = menu.encodeForUi(inventory).split("\n");
            require(lines.length == count + 1, "dynamic menu slot count");
            require(lines[0].equals("0\t2\tstone\t\t0\tchest\t00\t" + rows + "\t{\"text\":\"Play Skywars\"}"), "title and rows reach UI");
            boolean[] seen = new boolean[count];
            for (int ui = 0; ui < count; ui++) {
                int expected = ui < 9 ? top + 27 + ui : ui < 36 ? top + ui - 9 : ui - 36;
                require(menu.serverSlot(ui) == expected && !seen[expected], "complete unique native slot mapping");
                seen[expected] = true;
                require(lines[ui + 1].startsWith(ui + "\t" + (expected % 64 + 1) + "\tstone\t\t1\t00"), "server item in mapped UI slot");
                for (int button = 0; button < 2; button++) for (boolean shift : new boolean[]{false, true}) {
                    ByteBuffer click = ByteBuffer.allocate(64);
                    menu.writeMenuClick(click, window, ui, button, shift); click.flip();
                    require(VarInts.read(click) == 0x0b && (click.get() & 255) == window, "native container click");
                    require(VarInts.read(click) == -1 && click.getShort() == expected && click.get() == button, "authoritative slot click");
                    require(VarInts.read(click) == (shift ? 1 : 0) && VarInts.read(click) == 0 && click.get() == 0 && !click.hasRemaining(), "no client stacks supplied");
                }
            }
            require(menu.serverSlot(36) == 0 && menu.serverSlot(count - 1) == top - 1, "chest boundaries");
            ByteBuffer drop = ByteBuffer.allocate(64);
            menu.writeMenuClick(drop, window, 255, 1, true); drop.flip();
            VarInts.read(drop); drop.get(); VarInts.read(drop);
            require(drop.getShort() == -999 && drop.get() == 1 && VarInts.read(drop) == 0, "outside click does not shift");
            try { menu.writeMenuClick(ByteBuffer.allocate(64), window + 1, 36, 0, false); throw new AssertionError("stale click accepted"); }
            catch (IllegalArgumentException expected) { }
            try { menu.serverSlot(count); throw new AssertionError("out of bounds UI slot accepted"); }
            catch (IllegalArgumentException expected) { }
            long revision = menu.revision();
            menu.readContent(content(0, 20, 46), false);
            menu.readContent(content(window + 1, 20, count), false);
            require(menu.revision() == revision && menu.stateId() == 19, "foreign/player contents cannot replace chest");
            String before = menu.encodeForUi(inventory);
            ByteBuffer truncated = content(window, 20, count); truncated.limit(truncated.limit() - 1);
            try { menu.readContent(truncated, false); throw new AssertionError("truncated contents accepted"); }
            catch (ProtocolException expected) { require(before.equals(menu.encodeForUi(inventory)) && menu.stateId() == 19, "snapshot failure atomic"); }
            try { menu.readContent(content(window, 20, count - 1), false); throw new AssertionError("wrong slot count accepted"); }
            catch (ProtocolException expected) { require(before.equals(menu.encodeForUi(inventory)), "bad size atomic"); }
            menu.readContent(single(window, 20, 0, 5), true);
            require(menu.stateId() == 20 && menu.encodeForUi(inventory).split("\n")[37].startsWith("36\t5\tstone"), "server top slot update");
            menu.readContent(single(-1, 21, -1, 4), true);
            require(menu.encodeForUi(inventory).startsWith("0\t4\tstone"), "server cursor update");
            menu.serverClose(ByteBuffer.wrap(new byte[]{(byte)(window + 1)}));
            require(menu.window() == window, "foreign close ignored");
            menu.serverClose(ByteBuffer.wrap(new byte[]{(byte)window}));
            require(menu.window() == -1 && !menu.ready() && menu.stateId() == -1, "native close clears menu");
            revision = menu.revision();
            menu.readContent(content(window, 22, count), false);
            require(menu.revision() == revision, "late content cannot reopen menu");
            menu.open(open(window + 1, 11, "{\"translate\":\"container.crafting\"}"));
            menu.readContent(content(window + 1, 0, 46), false);
            require(menu.rows() == 0 && menu.ready() && menu.serverSlot(36) == 0 && menu.serverSlot(0) == 37, "crafting works after chest");
            require(menu.encodeForUi(inventory).split("\n")[0].equals("0\t2\tstone\t\t0\tcrafting\t00"), "crafting UI remains compatible");
        }
        System.out.println("GenericMenuSelfTest passed: six chest sizes, Play Skywars title, native clicks, server state, cursor, stale packets and crafting transition");
    }

    private static ByteBuffer open(int window, int type, String title) {
        ByteBuffer out = ByteBuffer.allocate(512);
        VarInts.write(out, window); VarInts.write(out, type); BinaryCodec.writeString(out, title, 512);
        return (ByteBuffer)out.flip();
    }
    private static ByteBuffer content(int window, int state, int count) {
        ByteBuffer out = ByteBuffer.allocate(2048);
        out.put((byte)window); VarInts.write(out, state); VarInts.write(out, count);
        for (int i = 0; i < count; i++) stack(out, i % 64 + 1);
        stack(out, 2); return (ByteBuffer)out.flip();
    }
    private static ByteBuffer single(int window, int state, int slot, int count) {
        ByteBuffer out = ByteBuffer.allocate(64);
        out.put((byte)window); VarInts.write(out, state); out.putShort((short)slot); stack(out, count);
        return (ByteBuffer)out.flip();
    }
    private static void stack(ByteBuffer out, int count) { out.put((byte)1); VarInts.write(out, 1); out.put((byte)count).put((byte)0); }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
