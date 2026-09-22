package com.newhorizon.thinclient.display;

/** Ordinals intentionally match WebDisplays BlockSide. */
public enum DisplaySide {
    BOTTOM,
    TOP,
    NORTH,
    SOUTH,
    WEST,
    EAST;

    public static DisplaySide fromOrdinal(int ordinal) {
        DisplaySide[] values = values();
        if (ordinal < 0 || ordinal >= values.length) return TOP;
        return values[ordinal];
    }
}


