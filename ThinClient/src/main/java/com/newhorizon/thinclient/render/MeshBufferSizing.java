package com.newhorizon.thinclient.render;

/** Spare buffers shrink as well as grow; never retain a past chunk's high-water mark. */
public final class MeshBufferSizing {
    private static final int PAGE=64*1024;
    public static int capacity(int bytes){
        if(bytes<0 || bytes>ChunkMeshWorker.BYTES)throw new IllegalArgumentException("Mesh size "+bytes);
        return (bytes+PAGE-1)/PAGE*PAGE;
    }
}
