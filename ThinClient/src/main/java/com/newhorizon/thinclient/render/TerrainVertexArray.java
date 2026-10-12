package com.newhorizon.thinclient.render;

import org.lwjgl.opengl.GL33;

/** Immutable vertex bindings, retained alongside the chunk's two buffer names. */
final class TerrainVertexArray implements AutoCloseable {
    private final int id;

    TerrainVertexArray(int geometry, int light) {
        id = GL33.glGenVertexArrays();
        bind();
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, geometry);
        GL33.glEnableVertexAttribArray(0);
        GL33.glEnableVertexAttribArray(1);
        GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, SurfaceMesher.VERTEX_BYTES, 0L);
        GL33.glVertexAttribPointer(1, 4, GL33.GL_UNSIGNED_BYTE, true, SurfaceMesher.VERTEX_BYTES, 12L);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, light);
        GL33.glEnableVertexAttribArray(2);
        GL33.glVertexAttribPointer(2, 2, GL33.GL_UNSIGNED_BYTE, false, 2, 0L);
    }

    void bind() { GL33.glBindVertexArray(id); }
    public void close() { GL33.glDeleteVertexArrays(id); }
}
