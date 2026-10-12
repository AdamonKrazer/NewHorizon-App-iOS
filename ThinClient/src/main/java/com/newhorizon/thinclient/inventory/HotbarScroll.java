package com.newhorizon.thinclient.inventory;

/** Accumulates high-resolution wheel events; one positive notch selects the previous slot. */
public final class HotbarScroll {
    private double remainder;
    public int move(int selected, double delta) {
        if (!Double.isFinite(delta)) return selected;
        if (remainder * delta < 0) remainder = 0;
        remainder += Math.max(-100, Math.min(100, delta));
        int steps = (int) remainder;
        remainder -= steps;
        return Math.floorMod(selected - steps, 9);
    }
    public void reset() { remainder = 0; }
}
