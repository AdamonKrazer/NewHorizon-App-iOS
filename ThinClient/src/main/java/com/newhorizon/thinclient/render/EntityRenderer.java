package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;

import java.nio.FloatBuffer;

/**
 * Bounded entity renderer using original Minecraft skins and model box UV nets.
 * One shared cube, texture atlas and fixed instance buffer replace per-mob GPU objects.
 */
final class EntityRenderer implements AutoCloseable {
    private static final int MAX_ENTITIES = 128;
    private static final int MAX_PARTS_PER_ENTITY = 40;
    private static final int INSTANCE_FLOATS = 40;
    private static final int INSTANCE_STRIDE = INSTANCE_FLOATS * Float.BYTES;
    private static final double RENDER_DISTANCE = 72.0;
    private static final int HUMANOID = 1;
    private static final int QUADRUPED = 2;
    private static final int FISH = 3;
    private static final int ARTHROPOD = 4;
    private static final int FLYING = 5;
    private static final int SLIME = 6;
    private static final int VEHICLE = 7;
    private static final int ITEM = 8;

    private final EntityTracker tracker;
    private final DroppedItemRenderer droppedItems=new DroppedItemRenderer();
    private final float[] basis={1,0,0,0,1,0,0,0,1};
    private final float[] partCenter = new float[3];
    private final EntityModelAnimation modelAnimation=new EntityModelAnimation();
    private final WorldEffectsRenderer worldEffects=new WorldEffectsRenderer();
    private final float[] entityLight=new float[]{1,1,1};
    private float overlay0=-1,overlay1=-1,overlayTint0=0xffffff,overlayTint1=0xffffff;
    private boolean centered;private float mirror;private int modelTint=0xffffffff;
    private final EntitySkinAtlas skins=new EntitySkinAtlas();
    private int currentSkin,currentPart;
    private float texU,texV,texW,texH,texD;
    private boolean explicitUv;
    private final RopeRenderer ropes = new RopeRenderer();
    private final ProjectileRenderer projectiles = new ProjectileRenderer();
    private final EntityTracker.Renderable[] snapshots =
            new EntityTracker.Renderable[MAX_ENTITIES];
    private final FloatBuffer instances = BufferUtils.createFloatBuffer(
            MAX_ENTITIES * MAX_PARTS_PER_ENTITY * INSTANCE_FLOATS);
    private int program;
    private int vao;
    private int cubeVbo;
    private int instanceVbo;
    private int cameraUniform;
    private int rotationUniform;
    private int aspectUniform;
    private int projectionUniform;
    private int mediumUniform;
    private int instanceCount;
    private int lastEntityCount = -1;
    int visibleEntities(){return Math.max(0,lastEntityCount);}
    int visibleParticles(){return worldEffects.visibleParticles+projectiles.visibleParticles;}
    private com.newhorizon.thinclient.world.PlayerListState playerList;
    private com.newhorizon.thinclient.world.PlayerSkins playerSkins;
    private EntityTracker.Renderable localModel;
    private int playerOrigin=-1;
    void players(com.newhorizon.thinclient.world.PlayerListState list,com.newhorizon.thinclient.world.PlayerSkins skins){playerList=list;playerSkins=skins;}
    void localPlayer(EntityTracker.Renderable model){localModel=model;}

    EntityRenderer(EntityTracker tracker) {
        this.tracker = tracker;
        for (int index = 0; index < snapshots.length; index++) {
            snapshots[index] = new EntityTracker.Renderable();
        }
    }

    void initializeGl() {
        projectiles.initializeGl();ropes.initializeGl();skins.initializeGl();droppedItems.initializeGl();worldEffects.initializeGl();
        program = linkProgram();
        cameraUniform = GL33.glGetUniformLocation(program, "uCamera");
        rotationUniform = GL33.glGetUniformLocation(program, "uRotation");
        aspectUniform = GL33.glGetUniformLocation(program, "uAspect");
        projectionUniform = GL33.glGetUniformLocation(program, "uProjection");
        mediumUniform = GL33.glGetUniformLocation(program, "uMedium");

        vao = GL33.glGenVertexArrays();
        cubeVbo = GL33.glGenBuffers();
        instanceVbo = GL33.glGenBuffers();
        GL33.glBindVertexArray(vao);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, cubeVbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER, CUBE_VERTICES, GL33.GL_STATIC_DRAW);
        GL33.glEnableVertexAttribArray(0);
        GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false,
                3 * Float.BYTES, 0L);

        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, instanceVbo);
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER,
                (long) instances.capacity() * Float.BYTES, GL33.GL_STREAM_DRAW);
        configureInstanceAttribute(1, 3, 0);
        configureInstanceAttribute(2, 3, 3);
        configureInstanceAttribute(3, 4, 6);
        configureInstanceAttribute(4, 1, 10);
        configureInstanceAttribute(5, 4, 11);
        configureInstanceAttribute(6, 1, 15);
        configureInstanceAttribute(7,4,16);configureInstanceAttribute(8,2,20);configureInstanceAttribute(9,3,22);configureInstanceAttribute(10,1,25);
        configureInstanceAttribute(11,3,26);configureInstanceAttribute(12,3,29);configureInstanceAttribute(13,3,32);configureInstanceAttribute(14,1,35);configureInstanceAttribute(15,4,36);
        GL33.glBindVertexArray(0);
        System.out.println("[NH-THIN] bounded entity renderer entities="
                + MAX_ENTITIES + " instanceKiB="
                + (instances.capacity() * Float.BYTES / 1024));
    }

    private static void configureInstanceAttribute(int index, int components,
                                                   int floatOffset) {
        GL33.glEnableVertexAttribArray(index);
        GL33.glVertexAttribPointer(index, components, GL33.GL_FLOAT, false,
                INSTANCE_STRIDE, (long) floatOffset * Float.BYTES);
        GL33.glVertexAttribDivisor(index, 1);
    }

    void draw(float cameraX, float cameraY, float cameraZ,
              float yaw, float pitch, float aspect, float projection,
              float medium, int ignoredEntityId) {
        int count = tracker.snapshotVisible(snapshots, System.nanoTime(),
                cameraX, cameraY, cameraZ, RENDER_DISTANCE, ignoredEntityId);
        drawSnapshots(count,cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,medium);
    }

    private void drawSnapshots(int count,float cameraX,float cameraY,float cameraZ,
                               float yaw,float pitch,float aspect,float projection,float medium) {
        instances.clear();
        instanceCount = 0;
        for (int index = 0; index < count; index++) addModel(snapshots[index]);
        if(localModel!=null)addModel(localModel);
        if (count != lastEntityCount) {
            lastEntityCount = count;
            System.out.println("[NH-THIN] visible entities=" + count
                    + " cuboids=" + instanceCount);
        }
        if (instanceCount == 0) { drawExtras(count,cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,medium); return; }
        instances.flip();
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, instanceVbo);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, instances);
        ScreenEffects.bind(program);skins.bind();
        GL33.glUniform3f(cameraUniform, cameraX, cameraY, cameraZ);
        GL33.glUniform2f(rotationUniform, yaw, pitch);
        GL33.glUniform1f(aspectUniform, aspect);
        GL33.glUniform1f(projectionUniform, projection);
        GL33.glUniform1f(mediumUniform, medium);
        GL33.glBindVertexArray(vao);
        // Vanilla EntityModel uses entityCutoutNoCull: alpha-cutout feet, ears and fins
        // need their reverse face too. Do not inherit terrain culling.
        boolean cull=GL33.glIsEnabled(GL33.GL_CULL_FACE);GL33.glDisable(GL33.GL_CULL_FACE);
        GL33.glDrawArraysInstanced(GL33.GL_TRIANGLES, 0, 36, instanceCount);
        if(cull)GL33.glEnable(GL33.GL_CULL_FACE);
        drawExtras(count,cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,medium);
    }
    void drawFireOverlay(boolean burning,float aspect){worldEffects.overlay(burning,aspect);}
    private void drawExtras(int count,float cameraX,float cameraY,float cameraZ,float yaw,float pitch,float aspect,float projection,float medium) {
        droppedItems.draw(snapshots,count,cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,medium);
        ropes.draw(tracker,cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,medium);projectiles.draw(snapshots,count,cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection,medium,tracker.combatParticles,tracker.fireworks,tracker);
        worldEffects.draw(tracker,snapshots,count,cameraX,cameraY,cameraZ,yaw,pitch,aspect,projection);
    }

    private int litOverlay(int rgb) {
        return (Math.round((rgb>>16&255)*entityLight[0])<<16)
                |(Math.round((rgb>>8&255)*entityLight[1])<<8)|Math.round((rgb&255)*entityLight[2]);
    }

    private void addModel(EntityTracker.Renderable entity) {
        if(entity.invisible || entity.type==58 || entity.type==101 || com.newhorizon.thinclient.world.ProjectileKind.isProjectile(entity.type)) return;
        String skin=EntityAppearance.name(entity);if(skin==null)return;
        SceneAtmosphere.light(entity.x,entity.y+.5f,entity.z,entityLight);
        currentSkin=EntitySkins.find(skin);currentPart=0;modelTint=entity.type==105?(EntityAppearance.dye((entity.appearance[17]>>>16)&255)<<8)|255:0xffffffff;
        playerOrigin=-1;entity.slim=false;
        if((entity.player||entity.type==122)&&entity.profileId!=null){
            if(playerSkins!=null){com.newhorizon.thinclient.world.PlayerSkins.Slot slot=playerSkins.request(entity.profileId,playerList.textures(entity.profileId));
                if(slot!=null){playerOrigin=skins.player(slot);entity.slim=slot.skin.slim;}}
        }
        overlay0=overlayOrigin(EntityAppearance.overlay(entity,0));overlay1=overlayOrigin(EntityAppearance.overlay(entity,1));
        overlayTint0=litOverlay(EntityAppearance.overlayColor(entity,0));overlayTint1=litOverlay(EntityAppearance.overlayColor(entity,1));
        VanillaEntityModels.Model baked=VanillaEntityModels.model(entity);
        if(baked!=null){bakedModel(entity,baked);
            if(entity.type==82&&(entity.appearance[17]&16)==0){currentSkin=EntitySkins.find("sheep/sheep_fur");modelTint=(EntityAppearance.dye(entity.appearance[17]&15)<<8)|255;bakedModel(entity,VanillaEntityModels.find("SheepFurModel."));}
            return;}
        int category = category(entity.type, entity.player);
        int color = 0xffffffff;
        switch (category) {
            case HUMANOID:
                humanoid(entity, color);
                break;
            case QUADRUPED:
                quadruped(entity, color);
                break;
            case FISH:
                fish(entity, color);
                break;
            case ARTHROPOD:
                arthropod(entity, color);
                break;
            case FLYING:
                flying(entity, color);
                break;
            case SLIME:
                slime(entity, color);
                break;
            case VEHICLE:
                vehicle(entity, color);
                break;
            case ITEM:
                item(entity, color);
                break;
            default:
                addBox(entity, 0f, 0f, 0f, 0.62f, 1.25f, 0.62f,
                        color, entity.yaw);
        }
    }

    private void humanoid(EntityTracker.Renderable e, int color) {
        addBox(e, 0f, 0.65f, 0f, 0.50f, 0.65f, 0.26f, color, e.yaw);
        addBox(e, 0f, 1.30f, 0f, 0.48f, 0.48f, 0.48f,
                lighten(color), e.headYaw);
        addBox(e, -0.15f, e.riding?.52f:0f, e.riding?-.22f:0f, .21f,e.riding?.22f:.68f,e.riding?.62f:.22f,darken(color),e.yaw);
        addBox(e, .15f, e.riding?.52f:0f, e.riding?-.22f:0f, .21f,e.riding?.22f:.68f,e.riding?.62f:.22f,darken(color),e.yaw);
        addBox(e, -0.36f, 0.62f, 0f, 0.18f, 0.69f, 0.20f, color, e.yaw);
        addBox(e, 0.36f, 0.62f, 0f, 0.18f, 0.69f, 0.20f, color, e.yaw);
    }

    private void quadruped(EntityTracker.Renderable e, int color) {
        float scale = e.type == 15 || e.type == 79 ? 0.62f : 1.0f;
        addBox(e, 0f, 0.42f * scale, 0f,
                0.90f * scale, 0.62f * scale, 0.42f * scale, color, e.yaw);
        addBox(e, 0f, 0.63f * scale, -0.50f * scale,
                0.48f * scale, 0.48f * scale, 0.48f * scale,
                lighten(color), e.headYaw);
        addBox(e, -0.28f * scale, 0f, -0.22f * scale,
                0.17f * scale, 0.48f * scale, 0.17f * scale, darken(color), e.yaw);
        addBox(e, 0.28f * scale, 0f, -0.22f * scale,
                0.17f * scale, 0.48f * scale, 0.17f * scale, darken(color), e.yaw);
        addBox(e, -0.28f * scale, 0f, 0.22f * scale,
                0.17f * scale, 0.48f * scale, 0.17f * scale, darken(color), e.yaw);
        addBox(e, 0.28f * scale, 0f, 0.22f * scale,
                0.17f * scale, 0.48f * scale, 0.17f * scale, darken(color), e.yaw);
    }

    private void fish(EntityTracker.Renderable e, int color) {
        addBox(e, 0f, 0.18f, 0f, 0.34f, 0.30f, 0.78f, color, e.yaw);
        addBox(e, 0f, 0.23f, 0.48f, 0.08f, 0.38f, 0.28f,
                lighten(color), e.yaw);
    }

    private void arthropod(EntityTracker.Renderable e, int color) {
        addBox(e, 0f, 0.18f, 0.10f, 0.75f, 0.34f, 0.68f, color, e.yaw);
        addBox(e, 0f, 0.19f, -0.38f, 0.48f, 0.38f, 0.42f,
                lighten(color), e.headYaw);
        for (int side = -1; side <= 1; side += 2) {
            addBox(e, side * 0.52f, 0.13f, -0.22f,
                    0.45f, 0.10f, 0.10f, darken(color), e.yaw);
            addBox(e, side * 0.56f, 0.13f, 0.08f,
                    0.52f, 0.10f, 0.10f, darken(color), e.yaw);
        }
    }

    private void flying(EntityTracker.Renderable e, int color) {
        float scale = e.type == 41 ? 2.3f : e.type == 27 ? 4.0f : 0.65f;
        addBox(e, 0f, 0.15f * scale, 0f,
                0.65f * scale, 0.65f * scale, 0.65f * scale, color, e.yaw);
        addBox(e, -0.48f * scale, 0.25f * scale, 0f,
                0.45f * scale, 0.08f * scale, 0.55f * scale,
                lighten(color), e.yaw);
        addBox(e, 0.48f * scale, 0.25f * scale, 0f,
                0.45f * scale, 0.08f * scale, 0.55f * scale,
                lighten(color), e.yaw);
    }

    private void slime(EntityTracker.Renderable e, int color) {
        addBox(e, 0f, 0f, 0f, 0.95f, 0.92f, 0.95f, color, e.yaw);
        addBox(e, 0f, 0.74f, -0.46f, 0.48f, 0.12f, 0.04f,
                darken(color), e.yaw);
    }

    private void vehicle(EntityTracker.Renderable e, int color) {
        if(com.newhorizon.thinclient.world.RidingState.minecart(e.type)) {
            addBox(e,0,0,0,1.25f,.125f,.875f,color,e.yaw);
            addBox(e,0,.125f,-.4375f,1.25f,.5f,.125f,color,e.yaw);
            addBox(e,0,.125f,.4375f,1.25f,.5f,.125f,color,e.yaw);
            addBox(e,-.625f,.125f,0,.125f,.5f,.875f,color,e.yaw);
            addBox(e,.625f,.125f,0,.125f,.5f,.875f,color,e.yaw);
            return;
        }
        addBox(e, 0f, 0f, 0f, 1.35f, 0.32f, 0.90f, color, e.yaw);
        addBox(e, 0f, 0.29f, 0f, 0.95f, 0.18f, 0.58f,
                darken(color), e.yaw);
    }

    private void item(EntityTracker.Renderable e, int color) {
        float size = e.type == 101 || e.type == 36 ? 0.92f : 0.28f;
        addBox(e, 0f, 0.08f, 0f, size, size, size, color, e.yaw);
    }

    private void addBox(EntityTracker.Renderable entity, float localX,
                        float localY, float localZ, float sizeX, float sizeY,
                        float sizeZ, int rgba, float yaw) {
        if (instanceCount >= MAX_ENTITIES * MAX_PARTS_PER_ENTITY) return;
        if(!explicitUv)defaultUv(entity,currentPart,sizeX,sizeY,sizeZ);currentPart++;
        if(entity.baby){localX*=.5f;localY*=.5f;localZ*=.5f;sizeX*=.5f;sizeY*=.5f;sizeZ*=.5f;}
        boolean cart=com.newhorizon.thinclient.world.RidingState.minecart(entity.type);
        if(cart) {
            double pitch=Math.toRadians(-entity.pitch);float ox=localX;
            localX=(float)(Math.cos(pitch)*localX-Math.sin(pitch)*localY);
            localY=(float)(Math.sin(pitch)*ox+Math.cos(pitch)*localY);
        }
        float modelYaw=EntityBoxGeometry.yaw(yaw,cart);
        double angle = Math.toRadians(modelYaw);
        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);
        float worldX = entity.x + cos * localX - sin * localZ;
        float worldZ = entity.z + sin * localX + cos * localZ;
        instances.put(worldX).put(entity.y + localY
                + (entity.type==15?ChickenModelPose.GROUND_OFFSET:0)).put(worldZ);
        instances.put(sizeX).put(sizeY).put(sizeZ);
        rgba=(modelTint&0xffffff00)|(entity.hurt?128:255);
        instances.put(((rgba >>> 24) & 0xff) / 255f*entityLight[0]);
        instances.put(((rgba >>> 16) & 0xff) / 255f*entityLight[1]);
        instances.put(((rgba >>> 8) & 0xff) / 255f*entityLight[2]);
        instances.put((rgba & 0xff) / 255f);
        instances.put(modelYaw);
        instances.put(entity.x).put(entity.y).put(entity.z).put(entity.sleeping?90:(float)Math.sqrt(entity.deathProgress)*90);
        instances.put(cart?entity.pitch:0);
        int at=currentSkin*4;
        if(playerOrigin>=0)instances.put(playerOrigin%EntitySkins.WIDTH).put(playerOrigin/EntitySkins.WIDTH).put(64).put(64);
        else instances.put(EntitySkins.RECTS[at]).put(EntitySkins.RECTS[at+1]).put(EntitySkins.RECTS[at+2]).put(EntitySkins.RECTS[at+3]);
        instances.put(texU).put(texV).put(texW).put(texH).put(texD).put(mirror);
        for(float v:basis)instances.put(v);instances.put(centered?1:0);instances.put(overlay0).put(overlay1).put(overlayTint0).put(overlayTint1);
        instanceCount++;
    }

    private static int overlayOrigin(String name){if(name==null)return -1;int a=EntitySkins.find(name)*4;return EntitySkins.RECTS[a]+EntitySkins.RECTS[a+1]*EntitySkins.WIDTH;}

    private void bakedModel(EntityTracker.Renderable e,VanillaEntityModels.Model model) {
        float scale=e.player||e.type==122?.9375f:e.type==42?6f:e.type==12?.7f:e.type==114?1.2f:e.type==5?.35f:e.type==41?4f:e.type==25?2.35f:1f;
        if(e.type==88||e.type==62)scale=Math.max(1,e.size);
        boolean boat=com.newhorizon.thinclient.world.RidingState.boat(e.type),cart=com.newhorizon.thinclient.world.RidingState.minecart(e.type);
        float unit=scale/16f,baseline=boat||cart?6:24;
        centered=true;explicitUv=true;
        modelAnimation.prepare(model,e);
        for(int part=0;part<model.paths.length;part++) {
            String path=model.paths[part];
            if(path.contains("baby_leg"))continue;
            if(e.player||e.type==122){
                int mask=e.appearance[17];int layer=path.endsWith("hat")?64:path.endsWith("jacket")?2:path.endsWith("left_sleeve")?4:path.endsWith("right_sleeve")?8:path.endsWith("left_pants")?16:path.endsWith("right_pants")?32:0;
                if(layer!=0&&(mask&layer)==0)continue;
            }
            if((path.contains("saddle")||path.contains("reins")||path.contains("saddle_mouth"))&&(e.appearance[17]&4)==0)continue;
            if(path.contains("chest")&&(e.type==21||e.type==66||e.type==60||e.type==103)&&e.appearance[18]==0)continue;
            int p=part*VanillaEntityModels.STRIDE;float[] b=model.boxes;
            texU=b[p+15];texV=b[p+16];texW=b[p+17];texH=b[p+18];texD=b[p+19];mirror=b[p+20];
            modelAnimation.box(part,partCenter,basis);
            boolean slimArm=e.slim&&(path.endsWith("arm")||path.endsWith("sleeve"));
            if(slimArm){float shift=path.contains("right")?-.5f:.5f;partCenter[0]+=basis[0]*shift;partCenter[1]+=basis[3]*shift-.5f;partCenter[2]+=basis[6]*shift;texW=3;}
            float yaw=Float.isFinite(e.bodyYaw)?e.bodyYaw:e.yaw;
            if(e.type==15) {
                // Keep the neck attached to the body; rotate every head part together.
                yaw=Float.isFinite(e.bodyYaw)?e.bodyYaw:e.yaw;
                ChickenModelPose.apply(path,e.headYaw-yaw,e.pitch,partCenter,basis);
            }
            if(e.type==44||e.type==96)EntityModelAnimation.squidBody(e,partCenter,basis);
            if((e.player||e.type==122)&&e.swimAmount>0)EntityModelAnimation.swimmingBody(e,partCenter,basis,unit);
            if(boat)yaw+=90;
            addBox(e,partCenter[0]*unit,(baseline+partCenter[1])*unit,partCenter[2]*unit,(b[p+3]-(slimArm?1:0))*unit,b[p+4]*unit,b[p+5]*unit,modelTint,yaw);
        }
        centered=false;explicitUv=false;mirror=0;java.util.Arrays.fill(basis,0);basis[0]=basis[4]=basis[8]=1;
    }

    private void texturedBox(EntityTracker.Renderable e,float x,float y,float z,float sx,float sy,float sz,
                             int u,int v,int width,int height,int depth,float yaw) {
        texU=u;texV=v;texW=width;texH=height;texD=depth;explicitUv=true;
        addBox(e,x,y,z,sx,sy,sz,0xffffffff,yaw);explicitUv=false;
    }
    private void defaultUv(EntityTracker.Renderable e,int part,float sx,float sy,float sz) {
        texU=0;texV=0;texW=Math.max(1,Math.round(sx*16));texH=Math.max(1,Math.round(sy*16));texD=Math.max(1,Math.round(sz*16));
        int category=category(e.type,e.player);
        if(category==HUMANOID){texU=part==0?16:part>=4?40:0;texV=part==1?0:16;texW=part==1?8:part==0?8:4;texH=part==1?8:12;texD=part==1?8:4;}
        else if(category==QUADRUPED){texU=part==0?28:0;texV=part>=2?16:part==0?8:0;texW=part==0?10:part==1?8:4;texH=part==0?16:part==1?8:6;texD=part==0?8:part==1?8:4;}
        else if(category==VEHICLE){texU=part==0?0:0;texV=part==0?10:0;texW=part==0?20:20;texH=part==0?16:8;texD=2;}
        else if(category==SLIME){texW=8;texH=8;texD=8;}
        int a=currentSkin*4,w=EntitySkins.RECTS[a+2],h=EntitySkins.RECTS[a+3];
        if(texU+2*(texW+texD)>w){texU=0;float shrink=w/(2*(texW+texD));texW*=shrink;texD*=shrink;}
        if(texV+texD+texH>h)texV=0;
    }

    private static int category(int type, boolean player) {
        if (player) return HUMANOID;
        switch (type) {
            case 2: case 7: case 23: case 29: case 31: case 42: case 50:
            case 51: case 53: case 73: case 74: case 75: case 86: case 91:
            case 97: case 108: case 109: case 110: case 111: case 112:
            case 113: case 114: case 118: case 120: case 121: case 122:
                return HUMANOID;
            case 4: case 10: case 11: case 15: case 18: case 21: case 38:
            case 39: case 45: case 47: case 49: case 60: case 65: case 66:
            case 67: case 69: case 72: case 76: case 79: case 80: case 82:
            case 87: case 90: case 98: case 103: case 106: case 116:
            case 117: case 119:
                return QUADRUPED;
            case 16: case 20: case 44: case 78: case 81: case 96: case 99:
            case 105:
                return FISH;
            case 12: case 30: case 85: case 95:
                return ARTHROPOD;
            case 0: case 5: case 6: case 27: case 41: case 70: case 71:
            case 107:
                return FLYING;
            case 19: case 62: case 88:
                return SLIME;
            case 9: case 13: case 14: case 17: case 40: case 48: case 64:
            case 93: case 102:
                return VEHICLE;
            case 3: case 22: case 24: case 28: case 33: case 34: case 35:
            case 36: case 37: case 54: case 57: case 61: case 77: case 84:
            case 89: case 92: case 94: case 101: case 104: case 115:
            case 123:
                return ITEM;
            default:
                return 0;
        }
    }

    private static int color(int type, boolean player) {
        if (player) return 0x4f86caff;
        switch (type) {
            case 18: case 21: case 49: case 65: case 66: case 72: case 82:
            case 87: case 119: return 0x9b7653ff;
            case 11: case 38: case 67: case 69: case 76: case 79: case 116:
                return 0xd6c4a5ff;
            case 15: case 53: case 91: return 0xe8e8dcff;
            case 16: case 20: case 44: case 78: case 81: case 96: case 99:
            case 105: return 0x4e9bb8ff;
            case 19: case 62: case 88: return 0x59a653ff;
            case 7: case 22: case 41: case 57: case 89: case 101:
                return 0xe47b31ff;
            case 12: case 30: case 85: case 95: return 0x493f42ff;
            case 23: case 29: case 47: case 50: case 73: case 74: case 98:
            case 117: case 118: case 121: return 0x5d8b62ff;
            case 86: case 97: case 114: return 0xb8b8aeff;
            case 108: case 110: case 112: case 120: return 0x9b6b50ff;
            case 9: case 13: case 14: case 17: case 40: case 48: case 64:
            case 93: case 102: return 0x775c3fff;
            default: return 0x9b78a8ff;
        }
    }

    private static int lighten(int rgba) {
        return shade(rgba, 1.16f);
    }

    private static int darken(int rgba) {
        return shade(rgba, 0.72f);
    }

    private static int shade(int rgba, float factor) {
        int red = Math.min(255, Math.round(((rgba >>> 24) & 0xff) * factor));
        int green = Math.min(255, Math.round(((rgba >>> 16) & 0xff) * factor));
        int blue = Math.min(255, Math.round(((rgba >>> 8) & 0xff) * factor));
        return red << 24 | green << 16 | blue << 8 | rgba & 0xff;
    }

    @Override
    public void close() {
        projectiles.close();ropes.close();skins.close();droppedItems.close();worldEffects.close();
        if (instanceVbo != 0) GL33.glDeleteBuffers(instanceVbo);
        if (cubeVbo != 0) GL33.glDeleteBuffers(cubeVbo);
        if (vao != 0) GL33.glDeleteVertexArrays(vao);
        if (program != 0) ScreenEffects.deleteProgram(program);
        instanceVbo = cubeVbo = vao = program = 0;
    }

    private static int linkProgram() {
        int vertex = compileShader(GL33.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GL33.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int result = GL33.glCreateProgram();
        GL33.glAttachShader(result, vertex);
        GL33.glAttachShader(result, fragment);
        GL33.glBindAttribLocation(result, 0, "aPosition");
        GL33.glBindAttribLocation(result, 1, "iOffset");
        GL33.glBindAttribLocation(result, 2, "iSize");
        GL33.glBindAttribLocation(result, 3, "iColor");
        GL33.glBindAttribLocation(result, 4, "iYaw");
        GL33.glBindAttribLocation(result, 5, "iDeath");
        GL33.glBindAttribLocation(result, 6, "iPitch");
        GL33.glBindAttribLocation(result,7,"iSkin");GL33.glBindAttribLocation(result,8,"iUv");GL33.glBindAttribLocation(result,9,"iPixels");GL33.glBindAttribLocation(result,10,"iMirror");
        GL33.glBindAttribLocation(result,11,"iBasisX");GL33.glBindAttribLocation(result,12,"iBasisY");GL33.glBindAttribLocation(result,13,"iBasisZ");GL33.glBindAttribLocation(result,14,"iCentered");GL33.glBindAttribLocation(result,15,"iOverlay");
        GL33.glLinkProgram(result);
        if (GL33.glGetProgrami(result, GL33.GL_LINK_STATUS) == GL33.GL_FALSE) {
            throw new IllegalStateException("Entity shader link failed: "
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
            throw new IllegalStateException("Entity shader compile failed: "
                    + GL33.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }

    private static final float[] CUBE_VERTICES = {
            0,0,0, 1,0,1, 1,0,0, 0,0,0, 0,0,1, 1,0,1,
            0,1,0, 1,1,0, 1,1,1, 0,1,0, 1,1,1, 0,1,1,
            0,0,0, 1,1,0, 0,1,0, 0,0,0, 1,0,0, 1,1,0,
            0,0,1, 0,1,1, 1,1,1, 0,0,1, 1,1,1, 1,0,1,
            0,0,0, 0,1,0, 0,1,1, 0,0,0, 0,1,1, 0,0,1,
            1,0,0, 1,0,1, 1,1,1, 1,0,0, 1,1,1, 1,1,0
    };

    private static final String VERTEX_SHADER =
            "#version 150 core\n"
            + "in vec3 aPosition; in vec3 iOffset; in vec3 iSize;\n"
            + "in vec4 iColor; in float iYaw; in vec4 iDeath; in float iPitch; out vec4 vColor; out vec3 vWorld;\n"
            + "in vec4 iSkin;in vec2 iUv;in vec3 iPixels;in float iMirror;in vec3 iBasisX;in vec3 iBasisY;in vec3 iBasisZ;in float iCentered;out vec2 vUv;in vec4 iOverlay;out vec4 vOverlayUv;out vec3 vOverlayColor0;out vec3 vOverlayColor1;\n"
            + "uniform vec3 uCamera; uniform vec2 uRotation;\n"
            + "uniform float uAspect; uniform float uProjection;\n"
            + "void main(){\n"
            + " vec3 local=vec3((aPosition.x-0.5)*iSize.x,(aPosition.y-iCentered*0.5)*iSize.y,(aPosition.z-0.5)*iSize.z);local=vec3(dot(iBasisX,local),dot(iBasisY,local),dot(iBasisZ,local));\n"
            + " float pitch=radians(iPitch); local.xy=mat2(cos(pitch),-sin(pitch),sin(pitch),cos(pitch))*local.xy;"
            + " float e=radians(-iYaw); local.xz=mat2(cos(e),-sin(e),sin(e),cos(e))*local.xz;\n"
            + " vec3 world=iOffset+local; float death=radians(iDeath.w); vec3 rel=world-iDeath.xyz; rel.xy=mat2(cos(death),-sin(death),sin(death),cos(death))*rel.xy; world=iDeath.xyz+rel; vec3 p=world-uCamera;\n"
            + " float y=radians(-uRotation.x); p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z);\n"
            + " p.x=-p.x; p.z=-p.z; float x=radians(uRotation.y);\n"
            + " p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);\n"
            + " float n=0.05; float f=192.0; gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-(2.0*f*n)/(f-n),-p.z);\n"
            + EntityBoxGeometry.UV_SHADER
            + " vec2 pixel=clamp(iUv+uv,vec2(0.01),iSkin.zw-vec2(0.01));vUv=(iSkin.xy+pixel)/vec2("+EntitySkins.WIDTH+".0,"+EntitySkinAtlas.HEIGHT+".0);\n"
            + " vec2 o0=vec2(mod(iOverlay.x,"+EntitySkins.WIDTH+".0),floor(iOverlay.x/"+EntitySkins.WIDTH+".0));vec2 o1=vec2(mod(iOverlay.y,"+EntitySkins.WIDTH+".0),floor(iOverlay.y/"+EntitySkins.WIDTH+".0));\n"
            + " vOverlayUv=vec4((o0+pixel)/vec2("+EntitySkins.WIDTH+".0,"+EntitySkinAtlas.HEIGHT+".0),(o1+pixel)/vec2("+EntitySkins.WIDTH+".0,"+EntitySkinAtlas.HEIGHT+".0));if(iOverlay.x<0.0)vOverlayUv.x=-1.0;if(iOverlay.y<0.0)vOverlayUv.z=-1.0;\n"
            + " vOverlayColor0=vec3(floor(iOverlay.z/65536.0),mod(floor(iOverlay.z/256.0),256.0),mod(iOverlay.z,256.0))/255.0;vOverlayColor1=vec3(floor(iOverlay.w/65536.0),mod(floor(iOverlay.w/256.0),256.0),mod(iOverlay.w,256.0))/255.0;\n"
            + " vColor=iColor; vWorld=world; }\n";

    private static final String FRAGMENT_SHADER =
            "#version 150 core\n"
            + "in vec4 vColor; in vec3 vWorld;in vec2 vUv;in vec4 vOverlayUv;in vec3 vOverlayColor0;in vec3 vOverlayColor1;uniform sampler2D uSkin;out vec4 fragColor;\n"
            + "uniform vec3 uCamera; uniform float uMedium;\n"
            + "void main(){ vec3 n=abs(normalize(cross(dFdx(vWorld),dFdy(vWorld))));\n"
            + " vec4 tex=texture(uSkin,vUv);tex.rgb*=vColor.rgb;if(vOverlayUv.x>=0.0){vec4 o=texture(uSkin,vOverlayUv.xy);tex.rgb=mix(tex.rgb,o.rgb*vOverlayColor0,o.a);tex.a=max(tex.a,o.a);}if(vOverlayUv.z>=0.0){vec4 o=texture(uSkin,vOverlayUv.zw);tex.rgb=mix(tex.rgb,o.rgb*vOverlayColor1,o.a);tex.a=max(tex.a,o.a);}if(tex.a<0.1)discard;float light=0.56+n.y*0.38+n.z*0.08; vec3 c=tex.rgb*light;if(vColor.a<.75)c*=vec3(1.0,.47,.47);\n"
            + " fragColor=vec4(nhFog(c,vWorld,uCamera),1.0); }\n";
}
