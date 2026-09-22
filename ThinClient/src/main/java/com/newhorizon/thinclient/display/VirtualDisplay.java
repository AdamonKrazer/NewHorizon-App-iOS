package com.newhorizon.thinclient.display;

import java.util.UUID;

/** Compact state required to render and interact with a virtual display. */
public final class VirtualDisplay {
    public final UUID id;
    public final String dimension;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float widthBlocks;
    public final float heightBlocks;
    public final DisplaySide side;
    public final int lifetimeTicks;
    public final int pixelWidth;
    public final int pixelHeight;
    public final String url;

    public VirtualDisplay(UUID id, String dimension, double x, double y, double z,
                          float yaw, float widthBlocks, float heightBlocks,
                          DisplaySide side, int lifetimeTicks, int pixelWidth,
                          int pixelHeight, String url) {
        if (id == null || dimension == null || side == null || url == null) {
            throw new NullPointerException();
        }
        if (widthBlocks <= 0f || heightBlocks <= 0f
                || pixelWidth <= 0 || pixelWidth > 4096
                || pixelHeight <= 0 || pixelHeight > 4096) {
            throw new IllegalArgumentException("Invalid display dimensions");
        }
        this.id = id;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.widthBlocks = widthBlocks;
        this.heightBlocks = heightBlocks;
        this.side = side;
        this.lifetimeTicks = lifetimeTicks;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.url = url;
    }

    public long estimatedStateBytes() {
        return 256L + 2L * dimension.length() + 2L * url.length();
    }
}


