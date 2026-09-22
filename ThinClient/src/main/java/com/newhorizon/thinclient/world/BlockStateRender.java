package com.newhorizon.thinclient.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Compact render classification generated from Mojang's 1.20.1 block report. */
public final class BlockStateRender {
    public static final int CUBE = 0;
    public static final int FOLIAGE = 1;
    public static final int FLOWER = 2;
    public static final int CROP = 3;
    public static final int PALE = 4;
    private static final int MAGIC = 0x4e485244; // NHRD
    private static final int VERSION = 1;
    private static final int PROTOCOL = 763;
    private static final byte[] STYLES = load();

    private BlockStateRender() {
    }

    public static int style(int stateId) {
        return stateId >= 0 && stateId < STYLES.length
                ? STYLES[stateId] & 0xff : CUBE;
    }

    public static boolean isPlant(int stateId) {
        return style(stateId) != CUBE;
    }

    private static byte[] load() {
        try (InputStream resource = BlockStateRender.class.getResourceAsStream(
                "/assets/newhorizon/block_render_1_20_1.bin")) {
            if (resource == null) throw new IOException("missing render table");
            DataInputStream input = new DataInputStream(resource);
            if (input.readInt() != MAGIC || input.readInt() != VERSION
                    || input.readInt() != PROTOCOL) {
                throw new IOException("render table version");
            }
            int count = input.readInt();
            if (count <= 0 || count > 65_535) {
                throw new IOException("invalid render state count");
            }
            byte[] styles = new byte[count];
            input.readFully(styles);
            if (input.read() != -1) throw new IOException("render table trailing bytes");
            int plants = 0;
            for (byte style : styles) if (style != CUBE) plants++;
            System.out.println("[NH-THIN] plant render states=" + plants
                    + "/" + count);
            return styles;
        } catch (IOException exception) {
            System.out.println("[NH-THIN] plant render fallback: "
                    + exception.getMessage());
            return new byte[24_162];
        }
    }
}
