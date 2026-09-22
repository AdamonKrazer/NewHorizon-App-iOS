package com.newhorizon.thinclient.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/** Exact opaque pixels from WebDisplays 2.0.2 item textures. */
final class ModItemSprites {
    static final int SIZE = 16;
    static final int MINEPAD_MODEL_SIZE = 32;
    static final int[] MINEPAD_MODEL = loadArgb(
            "/assets/webdisplays/textures/item/model/minepad_item.png",
            MINEPAD_MODEL_SIZE, MINEPAD_MODEL_SIZE);

    static final int[] LASER_POINTER = {
            0xff0000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0xff0000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0xff0000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0xff0000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0xff0000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0xff0000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0xff0000, 0, 0, 0x494949, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0xff0000, 0x494949, 0x848484, 0x494949, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0x494949, 0x848484, 0x848484, 0x848484, 0x494949, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0x494949, 0x848484, 0x848484, 0x848484, 0x848484, 0x848484, 0x494949, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0x494949, 0x848484, 0x848484, 0x848484, 0x848484, 0x848484, 0x494949, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0x494949, 0x848484, 0x848484, 0x848484, 0x848484, 0x848484, 0x494949, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0x494949, 0x848484, 0x848484, 0x848484, 0x848484, 0x848484, 0x494949,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x494949, 0x848484, 0x848484, 0x848484, 0x848484, 0x494949,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x494949, 0x848484, 0x848484, 0x494949, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x494949, 0x494949, 0, 0
    };

    static final int[] MINEPAD = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0x979797, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x979797, 0,
            0x979797, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x979797,
            0x949494, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xe5e5e5, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x949494,
            0x949494, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xc6c6c6, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x949494,
            0x949494, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xc3c3c3, 0xffffff, 0xf9f9f9, 0xdbdbdb, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x949494,
            0x949494, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xefefef, 0xffffff, 0xdddddd, 0xbebebe, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x949494,
            0x949494, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xefefef, 0xffffff, 0xe1e1e1, 0xbbbbbb, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x949494,
            0x949494, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xc3c3c3, 0xffffff, 0xf9f9f9, 0xd5d5d5, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x949494,
            0x979797, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0xbababa, 0x979797,
            0, 0x979797, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x949494, 0x979797, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private ModItemSprites() {
    }

    private static int[] loadArgb(String path, int expectedWidth,
                                  int expectedHeight) {
        try (InputStream stream = ModItemSprites.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("missing resource " + path);
            }
            BufferedImage image = ImageIO.read(stream);
            if (image == null || image.getWidth() != expectedWidth
                    || image.getHeight() != expectedHeight) {
                throw new IOException("invalid texture dimensions for " + path);
            }
            int[] pixels = new int[expectedWidth * expectedHeight];
            image.getRGB(0, 0, expectedWidth, expectedHeight, pixels, 0,
                    expectedWidth);
            return pixels;
        } catch (IOException error) {
            System.err.println("[NH-THIN] unable to load item texture: "
                    + error.getMessage());
            return new int[expectedWidth * expectedHeight];
        }
    }
}
