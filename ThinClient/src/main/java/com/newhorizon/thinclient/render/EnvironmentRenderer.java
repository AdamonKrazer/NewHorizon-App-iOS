package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EnvironmentState;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** Bounded LTW sky, cloud and precipitation pass driven by vanilla packets. */
final class EnvironmentRenderer implements AutoCloseable {
    /*
     * This is deliberately a fixed upper bound.  Even with the complete sky
     * dome and the vanilla cloud plane the native upload buffer stays below
     * 72 KiB and can never grow with play time.
     */
    private static final int MAX_VERTICES = 2_048;
    private static final int VERTEX_FLOATS = 9;
    private static final int STAR_COUNT = 96;
    private static final int SKY_SEGMENTS = 20;
    private static final int SKY_BANDS = 6;
    private static final float CLOUD_BASE_Y = 192.0f;
    private static final float CLOUD_SCALE = 12.0f;
    private static final float CLOUD_UV_SCALE = 1.0f / 256.0f;
    private static final float[] STAR_DIRECTIONS = new float[STAR_COUNT * 3];
    private static final int[][] END_FACES={{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7},{4,5,1,0}};
    private static final float[][] END_CORNERS={{-100,-100,-100},{100,-100,-100},{100,100,-100},{-100,100,-100},
            {-100,-100,100},{100,-100,100},{100,100,100},{-100,100,100}};

    static {
        // Stable Fibonacci sphere: no random state and no per-frame allocation.
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int index = 0; index < STAR_COUNT; index++) {
            double y = 1.0 - 2.0 * (index + 0.5) / STAR_COUNT;
            double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double angle = index * goldenAngle;
            STAR_DIRECTIONS[index * 3] = (float) (Math.cos(angle) * radius);
            STAR_DIRECTIONS[index * 3 + 1] = (float) y;
            STAR_DIRECTIONS[index * 3 + 2] = (float) (Math.sin(angle) * radius);
        }
    }

    private final EnvironmentState state;
    private final EnvironmentState.Snapshot snapshot = new EnvironmentState.Snapshot();
    private final FloatBuffer vertices = BufferUtils.createFloatBuffer(
            MAX_VERTICES * VERTEX_FLOATS);
    private int program;
    private int vao;
    private int vbo;
    private int cameraUniform;
    private int rotationUniform;
    private int aspectUniform;
    private int projectionUniform;
    private int texturedUniform;
    private int textureUniform;
    private int cloudTexture;
    private int sunTexture, moonTexture, rainTexture, snowTexture, endTexture, renderTexture;
    private boolean endDimension, snowing;
    private long frameNanos;
    private long previousFrameNanos;
    private float displayedRain;
    private float displayedThunder;
    private float daylight = 1.0f;
    private float clearRed = 0.42f;
    private float clearGreen = 0.65f;
    private float clearBlue = 0.88f;
    private boolean overworld = true;
    final com.newhorizon.thinclient.world.AtmosphereState atmosphere=new com.newhorizon.thinclient.world.AtmosphereState();
    private float skyCameraX;

    EnvironmentRenderer(EnvironmentState state) {
        this.state = state;
    }

    void initializeGl() {
        program = linkProgram();
        cameraUniform = GL33.glGetUniformLocation(program, "uCamera");
        rotationUniform = GL33.glGetUniformLocation(program, "uRotation");
        aspectUniform = GL33.glGetUniformLocation(program, "uAspect");
        projectionUniform = GL33.glGetUniformLocation(program, "uProjection");
        texturedUniform = GL33.glGetUniformLocation(program, "uTextured");
        textureUniform = GL33.glGetUniformLocation(program, "uTexture");
        vao = GL33.glGenVertexArrays();
        vbo = GL33.glGenBuffers();
        GL33.glBindVertexArray(vao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,
                (long) vertices.capacity() * Float.BYTES, GL33.GL_STREAM_DRAW);
        GL33.glEnableVertexAttribArray(0);
        GL33.glEnableVertexAttribArray(1);
        GL33.glEnableVertexAttribArray(2);
        GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false,
                VERTEX_FLOATS * Float.BYTES, 0L);
        GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false,
                VERTEX_FLOATS * Float.BYTES, 3L * Float.BYTES);
        GL33.glVertexAttribPointer(2, 2, GL33.GL_FLOAT, false,
                VERTEX_FLOATS * Float.BYTES, 7L * Float.BYTES);
        GL33.glBindVertexArray(0);
        cloudTexture = loadCloudTexture();
        sunTexture=loadTexture("sun");moonTexture=loadTexture("moon_phases");
        rainTexture=loadTexture("rain");snowTexture=loadTexture("snow");endTexture=loadTexture("end_sky");
        System.out.println("[NH-THIN] bounded environment renderer vertexKiB="
                + vertices.capacity() * Float.BYTES / 1024);
    }

    void prepare(long now, String dimension) {
        prepare(now,dimension,null,0,4,0);
    }

    void prepare(long now,String dimension,com.newhorizon.thinclient.audio.BiomeSoundRegistry.Biome biome,int medium,int viewChunks,float yaw) {
        frameNanos = now;
        state.sample(now, snapshot);
        double seconds = previousFrameNanos == 0L ? 0.0
                : Math.min(0.25, (now - previousFrameNanos) / 1_000_000_000.0);
        previousFrameNanos = now;
        float blend = (float) Math.min(1.0, seconds * 1.8);
        displayedRain += (snapshot.rain - displayedRain) * blend;
        displayedThunder += (snapshot.thunder - displayedThunder) * blend;
        overworld = dimension == null || "minecraft:overworld".equals(dimension);
        endDimension="minecraft:the_end".equals(dimension);
        snapshot.rain=displayedRain;snapshot.thunder=displayedThunder;
        atmosphere.sample(snapshot,dimension,biome,medium,viewChunks,yaw);
        daylight=atmosphere.daylight;
        clearRed=atmosphere.fog[0];clearGreen=atmosphere.fog[1];clearBlue=atmosphere.fog[2];
    }

    float clearRed() { return clearRed; }
    float clearGreen() { return clearGreen; }
    float clearBlue() { return clearBlue; }
    void snowing(boolean value) { snowing=value; }

    void drawSky(float cameraX, float cameraY, float cameraZ,
                 float yaw, float pitch, float aspect, float projection) {
        if(endDimension) {
            vertices.clear();renderTexture=endTexture;addEndSky(cameraX,cameraY,cameraZ);
            uploadAndDraw(cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,false,true);return;
        }
        if (!overworld) return;
        vertices.clear();
        addSkyDome(cameraX, cameraY, cameraZ);
        addStars(cameraX, cameraY, cameraZ);
        uploadAndDraw(cameraX, cameraY, cameraZ, yaw, pitch, aspect, projection,
                false, false);
        double angle=celestialAngle(snapshot.dayTime);
        vertices.clear();renderTexture=sunTexture;
        addOrbitalTexture(cameraX,cameraY,cameraZ,angle,30f,0,0,1,1);
        uploadAndDraw(cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,false,true);
        vertices.clear();renderTexture=moonTexture;
        float moonU=(snapshot.moonPhase%4)*.25f,moonV=(snapshot.moonPhase/4)*.5f;
        addOrbitalTexture(cameraX,cameraY,cameraZ,angle+Math.PI,20f,moonU,moonV,moonU+.25f,moonV+.5f);
        uploadAndDraw(cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,false,true);
        vertices.clear();
        renderTexture=cloudTexture;
        addVanillaClouds(cameraX, cameraY, cameraZ);
        uploadAndDraw(cameraX, cameraY, cameraZ, yaw, pitch, aspect, projection,
                true, true);
    }

    void drawWeather(float cameraX, float cameraY, float cameraZ,
                     float yaw, float pitch, float aspect, float projection) {
        if (!overworld || displayedRain < 0.015f) return;
        vertices.clear();
        int drops = 24 + Math.round(displayedRain * 96.0f);
        double tick = snapshot.gameTime
                + (frameNanos % 50_000_000L) / 50_000_000.0;
        float rightX = (float) Math.cos(Math.toRadians(yaw)) * 0.5f;
        float rightZ = (float) -Math.sin(Math.toRadians(yaw)) * 0.5f;
        renderTexture=snowing?snowTexture:rainTexture;
        for (int index = 0; index < drops; index++) {
            int hash = index * 1_103_515_245 + 12_345;
            float x = cameraX + (((hash >>> 8) & 0xff) / 255.0f - 0.5f) * 25.0f;
            float z = cameraZ + (((hash >>> 16) & 0xff) / 255.0f - 0.5f) * 25.0f;
            double phase = (tick * (0.73 + (index & 7) * 0.035)
                    + (hash & 0xffff) * 0.001) % 1.0;
            float top = cameraY + 13.0f - (float) phase * 26.0f;
            float bottom = top - 3.0f - (index & 3) * 0.55f;
            float alpha = 0.20f + displayedRain * 0.42f;
            float scroll=(float)(tick*(snowing?.01:.08));
            addTexturedQuad(x-rightX,bottom,z-rightZ,0,bottom*.25f+scroll,
                    x+rightX,bottom,z+rightZ,1,bottom*.25f+scroll,
                    x+rightX,top,z+rightZ,1,top*.25f+scroll,
                    x-rightX,top,z-rightZ,0,top*.25f+scroll,
                    1,1,1,alpha);
        }
        uploadAndDraw(cameraX, cameraY, cameraZ, yaw, pitch, aspect, projection,
                true, true);
    }

    private void addCelestialBodies(float cameraX, float cameraY, float cameraZ) {
        double angle = snapshot.dayTime * Math.PI * 2.0 / 24_000.0;
        addOrbitalQuad(cameraX, cameraY, cameraZ, angle, 5.8f,
                1.00f, 0.88f, 0.45f,
                Math.max(0.12f, 1.0f - displayedRain * 0.88f));
        addOrbitalQuad(cameraX, cameraY, cameraZ, angle + Math.PI, 4.6f,
                0.72f, 0.80f, 0.96f,
                Math.max(0.10f, 1.0f - displayedRain * 0.90f));
    }

    static double celestialAngle(long dayTime) {
        double fraction=dayTime/24000.0-.25;fraction-=Math.floor(fraction);
        double eased=.5-Math.cos(fraction*Math.PI)*.5;
        return ((fraction*2+eased)/3)*Math.PI*2+Math.PI*.5;
    }
    private void addOrbitalTexture(float x,float y,float z,double angle,float size,float u0,float v0,float u1,float v1) {
        float rx=(float)Math.cos(angle),ry=(float)Math.sin(angle),cx=x+rx*100,cy=y+ry*100;
        float tx=-ry*size,ty=rx*size;
        addTexturedQuad(cx-tx,cy-ty,z-size,u0,v1,cx+tx,cy+ty,z-size,u0,v0,
                cx+tx,cy+ty,z+size,u1,v0,cx-tx,cy-ty,z+size,u1,v1,
                1,1,1,1-displayedRain);
    }
    private void addEndSky(float x,float y,float z) {
        float shade=40f/255f;
        for(int[] face:END_FACES){float[] a=END_CORNERS[face[0]],b=END_CORNERS[face[1]],c=END_CORNERS[face[2]],d=END_CORNERS[face[3]];
            addTexturedQuad(x+a[0],y+a[1],z+a[2],0,0,x+b[0],y+b[1],z+b[2],16,0,
                    x+c[0],y+c[1],z+c[2],16,16,x+d[0],y+d[1],z+d[2],0,16,shade,shade,shade,1);
        }
    }

    /**
     * A small closed sphere replaces the old single clear colour.  It gives
     * the upper sky, horizon and lower haze the same continuous visual field
     * that the vanilla sky pass provides, without a texture or framebuffer.
     */
    private void addSkyDome(float cameraX, float cameraY, float cameraZ) {
        skyCameraX=cameraX;
        final float radius = 252.0f;
        for (int band = 0; band < SKY_BANDS; band++) {
            double latitude0 = -Math.PI * 0.5 + Math.PI * band / SKY_BANDS;
            double latitude1 = -Math.PI * 0.5
                    + Math.PI * (band + 1) / SKY_BANDS;
            float y0 = (float) Math.sin(latitude0);
            float y1 = (float) Math.sin(latitude1);
            float ring0 = (float) Math.cos(latitude0);
            float ring1 = (float) Math.cos(latitude1);
            for (int segment = 0; segment < SKY_SEGMENTS; segment++) {
                double angle0 = Math.PI * 2.0 * segment / SKY_SEGMENTS;
                double angle1 = Math.PI * 2.0 * (segment + 1) / SKY_SEGMENTS;
                float x00 = cameraX + radius * ring0 * (float) Math.cos(angle0);
                float z00 = cameraZ + radius * ring0 * (float) Math.sin(angle0);
                float x01 = cameraX + radius * ring0 * (float) Math.cos(angle1);
                float z01 = cameraZ + radius * ring0 * (float) Math.sin(angle1);
                float x10 = cameraX + radius * ring1 * (float) Math.cos(angle0);
                float z10 = cameraZ + radius * ring1 * (float) Math.sin(angle0);
                float x11 = cameraX + radius * ring1 * (float) Math.cos(angle1);
                float z11 = cameraZ + radius * ring1 * (float) Math.sin(angle1);
                addSkyQuad(x00, cameraY + radius * y0, z00, y0,
                        x01, cameraY + radius * y0, z01, y0,
                        x11, cameraY + radius * y1, z11, y1,
                        x10, cameraY + radius * y1, z10, y1);
            }
        }
    }

    private void addSkyQuad(float ax, float ay, float az, float ah,
                            float bx, float by, float bz, float bh,
                            float cx, float cy, float cz, float ch,
                            float dx, float dy, float dz, float dh) {
        if (vertices.remaining() < 6 * VERTEX_FLOATS) return;
        skyVertex(ax, ay, az, ah);
        skyVertex(bx, by, bz, bh);
        skyVertex(cx, cy, cz, ch);
        skyVertex(ax, ay, az, ah);
        skyVertex(cx, cy, cz, ch);
        skyVertex(dx, dy, dz, dh);
    }

    private void skyVertex(float x, float y, float z, float height) {
        // Bright, slightly washed horizon and deeper zenith, with a bounded
        // lower-horizon haze so looking down never exposes an empty void.
        float upper = smoothStep(-0.05f, 0.82f, height);
        float red=mix(clearRed,atmosphere.sky[0],upper);
        float green=mix(clearGreen,atmosphere.sky[1],upper);
        float blue=mix(clearBlue,atmosphere.sky[2],upper);
        float towardSun=Math.max(0,(x-skyCameraX)/252*atmosphere.sunDirection);
        float sunset=atmosphere.sunset[3]*towardSun*towardSun*(1-upper);
        red=mix(red,atmosphere.sunset[0],sunset);
        green=mix(green,atmosphere.sunset[1],sunset);
        blue=mix(blue,atmosphere.sunset[2],sunset);
        vertex(x, y, z, red, green, blue, 1.0f);
    }

    private void addOrbitalQuad(float cameraX, float cameraY, float cameraZ,
                                double angle, float size, float red,
                                float green, float blue, float alpha) {
        float radialX = (float) Math.cos(angle);
        float radialY = (float) Math.sin(angle);
        float centerX = cameraX + radialX * 150.0f;
        float centerY = cameraY + radialY * 150.0f;
        float centerZ = cameraZ;
        float tangentX = -radialY * size;
        float tangentY = radialX * size;
        addQuad(centerX - tangentX, centerY - tangentY, centerZ - size,
                centerX + tangentX, centerY + tangentY, centerZ - size,
                centerX + tangentX, centerY + tangentY, centerZ + size,
                centerX - tangentX, centerY - tangentY, centerZ + size,
                red, green, blue, alpha);
    }

    private void addStars(float cameraX, float cameraY, float cameraZ) {
        float alpha = (1.0f - daylight) * (1.0f - displayedRain * 0.92f);
        if (alpha < 0.015f) return;
        double rotation = snapshot.dayTime * Math.PI * 2.0 / 24_000.0;
        float sin = (float) Math.sin(rotation);
        float cos = (float) Math.cos(rotation);
        for (int index = 0; index < STAR_COUNT; index++) {
            float sourceX = STAR_DIRECTIONS[index * 3];
            float sourceY = STAR_DIRECTIONS[index * 3 + 1];
            float directionZ = STAR_DIRECTIONS[index * 3 + 2];
            float directionX = cos * sourceX - sin * sourceY;
            float directionY = sin * sourceX + cos * sourceY;
            float centerX = cameraX + directionX * 158.0f;
            float centerY = cameraY + directionY * 158.0f;
            float centerZ = cameraZ + directionZ * 158.0f;
            float size = 0.30f + (index & 3) * 0.08f;
            // Tiny crossed sky cards remain visible from every yaw.
            addQuad(centerX - size, centerY - size, centerZ,
                    centerX + size, centerY - size, centerZ,
                    centerX + size, centerY + size, centerZ,
                    centerX - size, centerY + size, centerZ,
                    0.90f, 0.93f, 1.0f, alpha);
        }
    }

    /** Exact 1.20.1 FAST cloud geometry, scale, UV range and scroll speed. */
    private void addVanillaClouds(float cameraX, float cameraY, float cameraZ) {
        float partialTick = (frameNanos % 50_000_000L) / 50_000_000.0f;
        double travel = (snapshot.gameTime + partialTick) * 0.03;
        double cloudX = (cameraX + travel) / CLOUD_SCALE;
        double cloudZ = cameraZ / CLOUD_SCALE + 0.33000001311302185;
        cloudX -= Math.floor(cloudX / 2048.0) * 2048.0;
        cloudZ -= Math.floor(cloudZ / 2048.0) * 2048.0;

        int wholeX = (int) Math.floor(cloudX);
        int wholeZ = (int) Math.floor(cloudZ);
        float fractionalX = (float) (cloudX - wholeX);
        float fractionalZ = (float) (cloudZ - wholeZ);
        float x0 = cameraX + (-32.0f - fractionalX) * CLOUD_SCALE;
        float x1 = cameraX + (32.0f - fractionalX) * CLOUD_SCALE;
        float z0 = cameraZ + (-32.0f - fractionalZ) * CLOUD_SCALE;
        float z1 = cameraZ + (32.0f - fractionalZ) * CLOUD_SCALE;
        float cloudY = CLOUD_BASE_Y + 0.33f;
        float u0 = (wholeX - 32.0f) * CLOUD_UV_SCALE;
        float u1 = (wholeX + 32.0f) * CLOUD_UV_SCALE;
        float v0 = (wholeZ - 32.0f) * CLOUD_UV_SCALE;
        float v1 = (wholeZ + 32.0f) * CLOUD_UV_SCALE;

        float brightness = vanillaCloudBrightness(snapshot.dayTime);
        float red = brightness * 0.9f + 0.1f;
        float green = red;
        float blue = brightness * 0.85f + 0.15f;
        if (displayedRain > 0.0f) {
            float grey = (red * 0.30f + green * 0.59f + blue * 0.11f) * 0.6f;
            float keep = 1.0f - displayedRain * 0.95f;
            red = red * keep + grey * (1.0f - keep);
            green = green * keep + grey * (1.0f - keep);
            blue = blue * keep + grey * (1.0f - keep);
        }
        if (displayedThunder > 0.0f) {
            float grey = (red * 0.30f + green * 0.59f + blue * 0.11f) * 0.2f;
            float keep = 1.0f - displayedThunder * 0.95f;
            red = red * keep + grey * (1.0f - keep);
            green = green * keep + grey * (1.0f - keep);
            blue = blue * keep + grey * (1.0f - keep);
        }
        addTexturedQuad(x0, cloudY, z1, u0, v1,
                x1, cloudY, z1, u1, v1,
                x1, cloudY, z0, u1, v0,
                x0, cloudY, z0, u0, v0,
                red, green, blue, 0.8f);
    }

    private static float vanillaCloudBrightness(long dayTime) {
        double fraction = dayTime / 24000.0 - 0.25;
        fraction -= Math.floor(fraction);
        double eased = 0.5 - Math.cos(fraction * Math.PI) * 0.5;
        float celestial = (float) ((fraction * 2.0 + eased) / 3.0);
        return Math.max(0.0f, Math.min(1.0f,
                (float) Math.cos(celestial * Math.PI * 2.0) * 2.0f + 0.5f));
    }

    private void addQuad(float ax, float ay, float az,
                         float bx, float by, float bz,
                         float cx, float cy, float cz,
                         float dx, float dy, float dz,
                         float red, float green, float blue, float alpha) {
        if (vertices.remaining() < 6 * VERTEX_FLOATS) return;
        vertex(ax, ay, az, red, green, blue, alpha);
        vertex(bx, by, bz, red, green, blue, alpha);
        vertex(cx, cy, cz, red, green, blue, alpha);
        vertex(ax, ay, az, red, green, blue, alpha);
        vertex(cx, cy, cz, red, green, blue, alpha);
        vertex(dx, dy, dz, red, green, blue, alpha);
    }

    private void addTexturedQuad(float ax, float ay, float az, float au, float av,
                                 float bx, float by, float bz, float bu, float bv,
                                 float cx, float cy, float cz, float cu, float cv,
                                 float dx, float dy, float dz, float du, float dv,
                                 float red, float green, float blue, float alpha) {
        if (vertices.remaining() < 6 * VERTEX_FLOATS) return;
        texturedVertex(ax, ay, az, red, green, blue, alpha, au, av);
        texturedVertex(bx, by, bz, red, green, blue, alpha, bu, bv);
        texturedVertex(cx, cy, cz, red, green, blue, alpha, cu, cv);
        texturedVertex(ax, ay, az, red, green, blue, alpha, au, av);
        texturedVertex(cx, cy, cz, red, green, blue, alpha, cu, cv);
        texturedVertex(dx, dy, dz, red, green, blue, alpha, du, dv);
    }

    private void vertex(float x, float y, float z, float red, float green,
                        float blue, float alpha) {
        texturedVertex(x, y, z, red, green, blue, alpha, 0.0f, 0.0f);
    }

    private void texturedVertex(float x, float y, float z, float red,
                                float green, float blue, float alpha,
                                float u, float v) {
        vertices.put(x).put(y).put(z).put(red).put(green).put(blue).put(alpha)
                .put(u).put(v);
    }

    private void uploadAndDraw(float cameraX, float cameraY, float cameraZ,
                               float yaw, float pitch, float aspect,
                               float projection, boolean depthTest,
                               boolean textured) {
        int count = vertices.position() / VERTEX_FLOATS;
        if (count == 0) return;
        vertices.flip();
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, vertices);
        ScreenEffects.bind(program);
        GL33.glUniform3f(cameraUniform, cameraX, cameraY, cameraZ);
        GL33.glUniform2f(rotationUniform, yaw, pitch);
        GL33.glUniform1f(aspectUniform, aspect);
        GL33.glUniform1f(projectionUniform, projection);
        GL33.glUniform1i(texturedUniform, textured ? 1 : 0);
        GL33.glUniform1i(textureUniform, 0);
        GL33.glActiveTexture(GL33.GL_TEXTURE0);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, textured ? renderTexture : 0);
        GL33.glBindVertexArray(vao);
        GL33.glEnable(GL33.GL_BLEND);
        // Vanilla sun/moon PNGs are RGB with black backgrounds and require additive blending.
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA,textured&&(renderTexture==sunTexture||renderTexture==moonTexture)
                ?GL33.GL_ONE:GL33.GL_ONE_MINUS_SRC_ALPHA);
        GL33.glDepthMask(false);
        GL33.glDisable(GL33.GL_CULL_FACE);
        if (depthTest) GL33.glEnable(GL33.GL_DEPTH_TEST);
        else GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, count);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        GL33.glEnable(GL33.GL_CULL_FACE);
        GL33.glDepthMask(true);
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, 0);
    }

    @Override
    public void close() {
        if (cloudTexture != 0) GL33.glDeleteTextures(cloudTexture);
        if(sunTexture!=0)GL33.glDeleteTextures(sunTexture);
        if(moonTexture!=0)GL33.glDeleteTextures(moonTexture);
        if(rainTexture!=0)GL33.glDeleteTextures(rainTexture);
        if(snowTexture!=0)GL33.glDeleteTextures(snowTexture);
        if(endTexture!=0)GL33.glDeleteTextures(endTexture);
        sunTexture=moonTexture=rainTexture=snowTexture=endTexture=renderTexture=0;
        if (vbo != 0) GL33.glDeleteBuffers(vbo);
        if (vao != 0) GL33.glDeleteVertexArrays(vao);
        if (program != 0) ScreenEffects.deleteProgram(program);
        cloudTexture = vbo = vao = program = 0;
    }

    private static float mix(float start, float end, float value) {
        return start + (end - start) * value;
    }

    private static float smoothStep(float minimum, float maximum, float value) {
        float x = Math.max(0.0f, Math.min(1.0f,
                (value - minimum) / (maximum - minimum)));
        return x * x * (3.0f - 2.0f * x);
    }

    private static int linkProgram() {
        int vertex = compileShader(GL33.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GL33.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int result = GL33.glCreateProgram();
        GL33.glAttachShader(result, vertex);
        GL33.glAttachShader(result, fragment);
        GL33.glBindAttribLocation(result, 0, "aPosition");
        GL33.glBindAttribLocation(result, 1, "aColor");
        GL33.glBindAttribLocation(result, 2, "aTexCoord");
        GL33.glLinkProgram(result);
        if (GL33.glGetProgrami(result, GL33.GL_LINK_STATUS) == GL33.GL_FALSE) {
            throw new IllegalStateException("Environment shader link failed: "
                    + GL33.glGetProgramInfoLog(result, 4096));
        }
        GL33.glDeleteShader(vertex);
        GL33.glDeleteShader(fragment);
        return result;
    }

    private static int compileShader(int type, String source) {
        int shader = GL33.glCreateShader(type);
        GL33.glShaderSource(shader, ScreenEffects.shader(type,source));
        GL33.glCompileShader(shader);
        if (GL33.glGetShaderi(shader, GL33.GL_COMPILE_STATUS) == GL33.GL_FALSE) {
            throw new IllegalStateException("Environment shader compile failed: "
                    + GL33.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }

    private static int loadCloudTexture() {
        return loadTexture("clouds");
    }
    private static int loadTexture(String name) {
        final String path = "/assets/minecraft/textures/environment/"+name+".png";
        ByteBuffer pixels = null;
        try (InputStream stream = EnvironmentRenderer.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("missing " + path);
            BufferedImage image = ImageIO.read(stream);
            if (image == null || image.getWidth() > 256 || image.getHeight() > 256) {
                throw new IOException("invalid vanilla environment texture");
            }
            pixels = MemoryUtil.memAlloc(image.getWidth()*image.getHeight()*4);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) (argb >>> 16));
                    pixels.put((byte) (argb >>> 8));
                    pixels.put((byte) argb);
                    pixels.put((byte) (argb >>> 24));
                }
            }
            pixels.flip();
            int texture = GL33.glGenTextures();
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER,
                    GL33.GL_NEAREST);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER,
                    GL33.GL_NEAREST);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_S,
                    GL33.GL_REPEAT);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_T,
                    GL33.GL_REPEAT);
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA8,
                    image.getWidth(),image.getHeight(),0,GL33.GL_RGBA,GL33.GL_UNSIGNED_BYTE,pixels);
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, 0);
            System.out.println("[NH-THIN] vanilla environment "+name+" "+image.getWidth()+"x"+image.getHeight());
            return texture;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load vanilla clouds", error);
        } finally {
            if (pixels != null) MemoryUtil.memFree(pixels);
        }
    }

    private static final String VERTEX_SHADER =
            "#version 150 core\n"
            + "in vec3 aPosition; in vec4 aColor; in vec2 aTexCoord; out vec4 vColor; out vec2 vTexCoord;\n"
            + "uniform vec3 uCamera; uniform vec2 uRotation; uniform float uAspect; uniform float uProjection;\n"
            + "void main(){ vec3 p=aPosition-uCamera; float y=radians(-uRotation.x);\n"
            + "p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z); p.x=-p.x; p.z=-p.z;\n"
            + "float x=radians(uRotation.y); p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);\n"
            + "float n=0.05; float f=768.0; gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-(2.0*f*n)/(f-n),-p.z); vColor=aColor; vTexCoord=aTexCoord;}\n";
    private static final String FRAGMENT_SHADER =
            "#version 150 core\n"
            + "in vec4 vColor; in vec2 vTexCoord; uniform sampler2D uTexture; uniform int uTextured; out vec4 fragColor;\n"
            + "void main(){fragColor=uTextured==1?vColor*texture(uTexture,vTexCoord):vColor;}\n";
}
