package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;

import java.nio.ByteBuffer;

/**
 * Fixed-capacity protocol-763 entity state. It keeps only the fields required
 * for vanilla movement interpolation, rendering and picking; Minecraft's large
 * entity class hierarchy is deliberately not retained.
 */
public final class EntityTracker {
    private static final double PLAYER_HALF_WIDTH = 0.30;
    private static final double DEFAULT_HALF_WIDTH = 0.35;
    private static final double PLAYER_HEIGHT = 1.80;
    private static final double DEFAULT_HEIGHT = 1.40;
    private static final double PICK_MARGIN = 0.0;
    private static final long INTERPOLATION_NANOS = 100_000_000L;
    private final int[] ids;
    private final int[] types;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final double[] previousXs;
    private final double[] previousYs;
    private final double[] previousZs;
    private final float[] yaws;
    private final float[] pitches;
    private final float[] headYaws;
    private final float[] previousYaws;
    private final float[] previousPitches;
    private final float[] previousHeadYaws;
    private final long[] updateNanos;
    private final long[] headUpdateNanos;
    private final ChickenBodyRotation[] chickenBodyRotations;
    private final EntityAnimation[] visualAnimations;
    private final boolean[] onGround;
    private final boolean[] players;
    private final java.util.UUID[] profileIds;
    private int replacementCursor;
    private int spawnLogCount;
    private final ProjectileMotion[] projectiles;
    private final long[] hurtUntil,deathAt,swingAt;
    private final int[] animations;
    private final EntityMetadata.Values[] living;
    private final EntityEquipment[] equipment;
    public final FishingParticles fishingParticles=new FishingParticles();
    public final LeashState leashes=new LeashState();
    private float localYaw,localPitch;
    private boolean fishingOffhand,hasFishingTip;
    private int fishingLogCount;
    private final double[] fishingTip=new double[3];
    private final double[] ropeStart=new double[3],ropeEnd=new double[3];
    public synchronized void localRopeView(float yaw,float pitch,boolean offhand) {localYaw=yaw;localPitch=pitch;fishingOffhand=offhand;}
    public synchronized void clearLocalFishingTip(){hasFishingTip=false;}
    /** Inverse of the world camera rotation; callers already compensate for hand/world FOV. */
    public synchronized void localFishingTip(float x,float y,float z,float eyeHeight) {
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||!Float.isFinite(eyeHeight))return;
        double yaw=Math.toRadians(localYaw),pitch=Math.toRadians(localPitch);
        double vertical=Math.cos(pitch)*y+Math.sin(pitch)*z;
        double forward=Math.sin(pitch)*y-Math.cos(pitch)*z;
        fishingTip[0]=localPlayerPosition[0]-Math.cos(yaw)*x-Math.sin(yaw)*forward;
        fishingTip[1]=localPlayerPosition[1]+eyeHeight+vertical;
        fishingTip[2]=localPlayerPosition[2]-Math.sin(yaw)*x+Math.cos(yaw)*forward;
        hasFishingTip=true;
    }

    public synchronized boolean fishing(int owner) {for(int i=0;i<ids.length;i++)if(ids[i]>=0&&types[i]==ProjectileKind.FISHING&&projectiles[i].owner==owner)return true;return false;}
    public synchronized int count(){int count=0;for(int id:ids)if(id>=0)count++;return count;}
    public final FireworkEffects fireworks = new FireworkEffects();
    private int localPlayer=-1;
    private final double[] localPlayerPosition=new double[3];
    public synchronized void localPlayer(int id,double x,double y,double z) {localPlayer=id;localPlayerPosition[0]=x;localPlayerPosition[1]=y;localPlayerPosition[2]=z;}
    public final CombatParticles combatParticles = new CombatParticles(96);
    public final WorldParticles worldParticles=new WorldParticles();
    private final double[] railCenter=new double[3],railFront=new double[3],railBack=new double[3];
    private WorldChunkStore world;
    private RidingState riding;
    private int localVehicle=-1;
    private final double[] localVehiclePose=new double[5],passengerPosition=new double[3];
    private double[] mountSpeed,mountJump;
    public synchronized void setRiding(RidingState state){riding=state;}
    public synchronized void localVehicle(int id,double x,double y,double z,float yaw,float pitch){localVehicle=id;localVehiclePose[0]=x;localVehiclePose[1]=y;localVehiclePose[2]=z;localVehiclePose[3]=yaw;localVehiclePose[4]=pitch;}

    public synchronized void setWorld(WorldChunkStore world) { this.world=world; }
    public synchronized int worldParticles(WorldParticles.Particle[] out,long now,double x,double y,double z){return worldParticles.snapshot(out,now,world,x,y,z);}

    public EntityTracker(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        ids = new int[capacity];mountSpeed=new double[capacity];mountJump=new double[capacity];
        types = new int[capacity];
        xs = new double[capacity];
        ys = new double[capacity];
        zs = new double[capacity];
        previousXs = new double[capacity];
        previousYs = new double[capacity];
        previousZs = new double[capacity];
        yaws = new float[capacity];
        pitches = new float[capacity];
        headYaws = new float[capacity];
        previousYaws = new float[capacity];
        previousPitches = new float[capacity];
        previousHeadYaws = new float[capacity];
        updateNanos = new long[capacity];
        headUpdateNanos = new long[capacity];
        chickenBodyRotations = new ChickenBodyRotation[capacity];
        visualAnimations = new EntityAnimation[capacity];onGround=new boolean[capacity];
        for(int i=0;i<capacity;i++)visualAnimations[i]=new EntityAnimation();
        players = new boolean[capacity];profileIds=new java.util.UUID[capacity];
        projectiles = new ProjectileMotion[capacity];
        hurtUntil=new long[capacity];deathAt=new long[capacity];swingAt=new long[capacity];animations=new int[capacity];
        living=new EntityMetadata.Values[capacity];equipment=new EntityEquipment[capacity];for(int i=0;i<capacity;i++)equipment[i]=new EntityEquipment();
        for(int i=0;i<capacity;i++)living[i]=new EntityMetadata.Values();
        for (int i=0;i<capacity;i++) projectiles[i]=new ProjectileMotion();
        clear();
    }

    public synchronized void addEntity(ByteBuffer packet) throws ProtocolException {
        int id = VarInts.read(packet);
        java.util.UUID profile=BinaryCodec.readUuid(packet);
        int type = VarInts.read(packet);
        BinaryCodec.require(packet, 27);
        double x = packet.getDouble();
        double y = packet.getDouble();
        double z = packet.getDouble();
        float pitch = unpackAngle(packet.get());
        float yaw = unpackAngle(packet.get());
        float headYaw = unpackAngle(packet.get());
        int spawnData=VarInts.read(packet); // FishingHook stores its owner entity ID here.
        BinaryCodec.require(packet, 6);
        double vx=packet.getShort()/8000.0, vy=packet.getShort()/8000.0,
                vz=packet.getShort()/8000.0;
        requireConsumed(packet);
        if (id<0 || type<0 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) throw new ProtocolException("Invalid entity spawn");
        put(id, type, x, y, z, yaw, pitch, headYaw, false);
        profileIds[find(id)]=profile;
        if(type==36)living[find(id)].blockState=spawnData;
        projectiles[find(id)].reset(type,x,y,z,yaw,pitch,vx,vy,vz,System.nanoTime());
        if(type==ProjectileKind.FISHING) {
            projectiles[find(id)].owner=spawnData;
            if(fishingLogCount++<48)System.out.println("[NH-FISHING] spawn id="+id+" owner="+spawnData+" local="+localPlayer+" pos="+x+","+y+","+z+" velocity="+vx+","+vy+","+vz+" timeMs="+System.currentTimeMillis());
        }
    }

    public synchronized void addPlayer(ByteBuffer packet) throws ProtocolException {
        int id = VarInts.read(packet);
        java.util.UUID profile=BinaryCodec.readUuid(packet);
        BinaryCodec.require(packet, 26);
        double x = packet.getDouble();
        double y = packet.getDouble();
        double z = packet.getDouble();
        float yaw = unpackAngle(packet.get());
        float pitch = unpackAngle(packet.get());
        requireConsumed(packet);
        put(id, 122, x, y, z, yaw, pitch, yaw, true);
        profileIds[find(id)]=profile;
    }

    public synchronized void addExperienceOrb(ByteBuffer packet) throws ProtocolException {
        int id = VarInts.read(packet);
        BinaryCodec.require(packet, 26);
        double x = packet.getDouble(), y = packet.getDouble(), z = packet.getDouble();
        packet.getShort();
        requireConsumed(packet);
        put(id, 34, x, y, z, 0, 0, 0, false);
    }

    public synchronized int typeOf(int id) {
        int slot = find(id);
        return slot < 0 ? -1 : types[slot];
    }

    public synchronized void moveEntity(ByteBuffer packet, boolean includesRotation)
            throws ProtocolException {
        int id = VarInts.read(packet);
        BinaryCodec.require(packet, includesRotation ? 9 : 7);
        short dx = packet.getShort();
        short dy = packet.getShort();
        short dz = packet.getShort();
        float yaw = 0.0f;
        float pitch = 0.0f;
        if (includesRotation) {
            yaw = unpackAngle(packet.get());
            pitch = unpackAngle(packet.get());
        }
        boolean grounded=packet.get()!=0;
        requireConsumed(packet);
        int slot = find(id);
        if (slot >= 0) {
            beginInterpolation(slot);
            onGround[slot]=grounded;
            xs[slot] += dx / 4096.0;
            ys[slot] += dy / 4096.0;
            zs[slot] += dz / 4096.0;
            if (includesRotation) {
                yaws[slot] = yaw;
                pitches[slot] = pitch;
            }
            correctProjectile(slot);
        }
    }

    /** Rotation-only ClientboundMoveEntityPacket.Rot. */
    public synchronized void rotateEntity(ByteBuffer packet) throws ProtocolException {
        int id = VarInts.read(packet);
        BinaryCodec.require(packet, 3);
        float yaw = unpackAngle(packet.get());
        float pitch = unpackAngle(packet.get());
        boolean grounded=packet.get()!=0;
        requireConsumed(packet);
        int slot = find(id);
        if (slot >= 0) {
            beginInterpolation(slot);
            onGround[slot]=grounded;
            yaws[slot] = yaw;
            pitches[slot] = pitch;
            if(projectiles[slot].active) {
                projectiles[slot].yaw=yaw; projectiles[slot].pitch=pitch;
            }
        }
    }

    public synchronized void rotateHead(ByteBuffer packet) throws ProtocolException {
        int id = VarInts.read(packet);
        BinaryCodec.require(packet, 1);
        float headYaw = unpackAngle(packet.get());
        requireConsumed(packet);
        int slot = find(id);
        if (slot >= 0) {
            long now = System.nanoTime();
            advanceChickenBody(slot,now);
            double progress = headInterpolation(slot, now);
            previousHeadYaws[slot] = interpolatedAngle(
                    previousHeadYaws[slot], headYaws[slot], progress);
            headYaws[slot] = headYaw;
            headUpdateNanos[slot] = now;
        }
    }

    public synchronized void teleportEntity(ByteBuffer packet) throws ProtocolException {
        int id = VarInts.read(packet);
        BinaryCodec.require(packet, 27);
        double x = packet.getDouble();
        double y = packet.getDouble();
        double z = packet.getDouble();
        float yaw = unpackAngle(packet.get());
        float pitch = unpackAngle(packet.get());
        boolean grounded=packet.get()!=0;
        requireConsumed(packet);
        int slot = find(id);
        if (slot >= 0) {
            beginInterpolation(slot);
            onGround[slot]=grounded;
            boolean discontinuity=(xs[slot]-x)*(xs[slot]-x)+(ys[slot]-y)*(ys[slot]-y)+(zs[slot]-z)*(zs[slot]-z)>64;
            xs[slot] = x;
            ys[slot] = y;
            zs[slot] = z;
            yaws[slot] = yaw;
            pitches[slot] = pitch;
            if(discontinuity){previousXs[slot]=x;previousYs[slot]=y;previousZs[slot]=z;visualAnimations[slot].relocate(x,y,z);}
            correctProjectile(slot);
        }
    }

    public synchronized void removeEntities(ByteBuffer packet) throws ProtocolException {
        int count = VarInts.read(packet);
        if (count < 0 || count > ids.length * 4) {
            throw new ProtocolException("Entity removal count outside limit");
        }
        for (int index = 0; index < count; index++) {
            int id=VarInts.read(packet);int slot=find(id);leashes.remove(id);
            if (slot >= 0) {
                if(types[slot]==ProjectileKind.FISHING && fishingLogCount++<48)
                    System.out.println("[NH-FISHING] server remove id="+id+" timeMs="+System.currentTimeMillis());
                ids[slot]=-1;projectiles[slot].active=false;
            }
        }
        requireConsumed(packet);
    }

    /** Allocation-free snapshot for the bounded GPU entity pass. */
    public synchronized int snapshotVisible(Renderable[] output, long now,
                                            double centerX, double centerY,
                                            double centerZ, double maximumDistance,
                                            int ignoredId) {
        double maximumDistanceSquared = maximumDistance * maximumDistance;
        int count = 0;
        for (int slot = 0; slot < ids.length && count < output.length; slot++) {
            if (ids[slot] < 0 || ids[slot] == ignoredId || projectiles[slot].exploded) continue;
            double progress = interpolation(slot, now);
            double x = lerp(previousXs[slot], xs[slot], progress);
            double y = lerp(previousYs[slot], ys[slot], progress);
            double z = lerp(previousZs[slot], zs[slot], progress);
            ProjectileMotion projectile=projectiles[slot];
            if(projectile.active) {
                advanceProjectile(projectile,now);
                x=projectile.renderX(now); y=projectile.renderY(now); z=projectile.renderZ(now);
            }
            if(ids[slot]==localVehicle){x=localVehiclePose[0];y=localVehiclePose[1];z=localVehiclePose[2];}
            else if(riding!=null && riding.vehicleOf(ids[slot])>=0 && position(ids[slot],now,passengerPosition)){x=passengerPosition[0];y=passengerPosition[1];z=passengerPosition[2];}
            double dx = x - centerX;
            double dy = y - centerY;
            double dz = z - centerZ;
            if (dx * dx + dy * dy + dz * dz > maximumDistanceSquared) continue;
            Renderable item = output[count++];
            item.entityId = ids[slot];
            item.type = types[slot];
            item.x = (float) x;
            item.y = (float) y;
            item.z = (float) z;
            item.yaw = interpolatedAngle(previousYaws[slot], yaws[slot], progress);
            item.pitch = interpolatedAngle(previousPitches[slot], pitches[slot], progress);
            item.headYaw = interpolatedAngle(
                    previousHeadYaws[slot], headYaws[slot], headInterpolation(slot,now));
            if(ids[slot]==localVehicle){item.yaw=(float)localVehiclePose[3];item.pitch=(float)localVehiclePose[4];}
            if(RidingState.minecart(item.type))RailPath.pose(world,item,railCenter,railFront,railBack);
            System.arraycopy(equipment[slot].items,0,item.equipment,0,6);System.arraycopy(equipment[slot].tags,0,item.equipmentTags,0,6);
            item.player = players[slot];item.profileId=profileIds[slot];item.sneaking=living[slot].pose==5;
            item.prone=living[slot].pose==3;item.swimming=(living[slot].flags&16)!=0;
            System.arraycopy(living[slot].appearance,0,item.appearance,0,32);item.baby=types[slot]==73?living[slot].appearance[17]!=0:CombatHitboxes.hasBabySize(types[slot])&&living[slot].baby;item.size=living[slot].size;
            item.riding=riding!=null && riding.vehicleOf(ids[slot])>=0;item.sleeping=living[slot].pose==2;
            item.hurt=now<hurtUntil[slot];
            item.deathProgress=deathAt[slot]==0?0:Math.min(1,(now-deathAt[slot])/1_000_000_000f);
            item.swingProgress=swingAt[slot]==0?0:Math.max(0,Math.min(1,(now-swingAt[slot])/300_000_000f));
            item.offhandSwing=animations[slot]==3;
            item.blocking=(living[slot].livingFlags&1)!=0;
            item.invisible=(living[slot].flags&32)!=0;
            item.burning=(living[slot].flags&1)!=0;
            item.blockState=living[slot].blockState;item.itemId=(item.type==54||item.type==55)?living[slot].itemId:projectile.itemId;item.itemTag=living[slot].itemTag; item.color=projectile.color;
            if(projectile.active) { item.yaw=projectile.yaw; item.pitch=projectile.pitch; }
            item.bodyYaw=item.yaw;
            if(bodyRotationType(item.type)) {
                advanceChickenBody(slot,now);
                item.bodyYaw=chickenBodyRotations[slot].bodyYaw(now);
                item.headYaw=chickenBodyRotations[slot].headYaw(now);
            }
            advanceVisualAnimation(slot,now);
            item.onGround=onGround[slot];item.inWater=inWater(slot,x,y,z);
            visualAnimations[slot].snapshot(item,now);
        }
        return count;
    }

    /** Allocation-free position for an entity-bound sound, including interpolation. */
    public synchronized boolean position(int entityId, long now, double[] output) {
        return position(entityId,now,output,0);
    }
    private boolean position(int entityId,long now,double[] output,int depth) {
        if(entityId==localVehicle&&entityId>=0){System.arraycopy(localVehiclePose,0,output,0,3);return true;}
        int parent=riding==null?-1:riding.vehicleOf(entityId);
        if(parent>=0 && depth<8 && position(parent,now,output,depth+1)){
            int p=find(parent);if(p>=0){
                output[1]+=RidingState.seatHeight(types[p],living[p].vehicleVariant);
                if(RidingState.boat(types[p])){double offset=riding.passengerCount(parent)>1?(riding.seatOf(entityId)==0?.2:-.6):types[p]==13?.15:0;
                    double yaw=Math.toRadians(parent==localVehicle?localVehiclePose[3]:yaws[p]);output[0]-=Math.sin(yaw)*offset;output[2]+=Math.cos(yaw)*offset;}
                return true;
            }
        }
        int slot = find(entityId);
        if (slot < 0) return false;
        ProjectileMotion projectile=projectiles[slot];
        if(projectile.active) {
            advanceProjectile(projectile,now);
            output[0]=projectile.renderX(now); output[1]=projectile.renderY(now);
            output[2]=projectile.renderZ(now); return true;
        }
        double progress = interpolation(slot, now);
        output[0] = lerp(previousXs[slot], xs[slot], progress);
        output[1] = lerp(previousYs[slot], ys[slot], progress);
        output[2] = lerp(previousZs[slot], zs[slot], progress);
        if(RidingState.minecart(types[slot]))RailPath.project(world,output[0],output[1],output[2],output);
        return true;
    }

    public synchronized void findRayHit(double originX, double originY, double originZ,
                                        double rayX, double rayY, double rayZ,
                                        double maximumDistance, int ignoredId,
                                        Hit output) {
        findRayHit(originX,originY,originZ,rayX,rayY,rayZ,maximumDistance,ignoredId,output,System.nanoTime());
    }
    public synchronized void findRayHit(double originX,double originY,double originZ,
                                        double rayX,double rayY,double rayZ,double maximumDistance,
                                        int ignoredId,Hit output,long now) {
        output.entityId = -1;
        output.distance = maximumDistance + 1.0;
        for (int slot = 0; slot < ids.length; slot++) {
            int id = ids[slot];
            if (id < 0 || id == ignoredId || deathAt[slot]!=0) continue;
            if (!CombatHitboxes.pickable(types[slot])) continue;
            if(riding!=null&&riding.sameVehicle(id,ignoredId))continue;
            double halfWidth=CombatHitboxes.width(types[slot])/2;
            double height=CombatHitboxes.height(types[slot]);
            EntityMetadata.Values meta=living[slot];
            if(types[slot]==122) {
                if(meta.pose==5)height=1.5;
                else if(meta.pose==1||meta.pose==3||meta.pose==4)height=.6;
                else if(meta.pose==2||meta.pose==7){halfWidth=.1;height=.2;}
            } else if(types[slot]==62||types[slot]==88) {halfWidth*=.255*meta.size;height*=.255*meta.size;}
            else if(types[slot]==52) {halfWidth=meta.interactionWidth/2;height=meta.interactionHeight;}
            else if(meta.baby && CombatHitboxes.hasBabySize(types[slot])) {halfWidth*=.5;height*=.5;}
            double progress=interpolation(slot,now);
            double x=lerp(previousXs[slot],xs[slot],progress),y=lerp(previousYs[slot],ys[slot],progress),z=lerp(previousZs[slot],zs[slot],progress);
            if(riding!=null&&riding.vehicleOf(id)>=0&&position(id,now,passengerPosition)){x=passengerPosition[0];y=passengerPosition[1];z=passengerPosition[2];}
            if(projectiles[slot].active) {
                ProjectileMotion p=projectiles[slot]; p.advance(now,world);
                x=p.renderX(now); y=p.renderY(now); z=p.renderZ(now);
            }
            double distance = rayAabb(originX, originY, originZ, rayX, rayY, rayZ,
                    x - halfWidth, y - PICK_MARGIN,
                    z - halfWidth, x + halfWidth,
                    y + height, z + halfWidth,
                    maximumDistance);
            if (distance >= 0.0 && distance < output.distance) {
                output.entityId = id;
                output.distance = distance;
            }
        }
    }

    public synchronized void clear() {
        java.util.Arrays.fill(ids, -1);
        combatParticles.clear();worldParticles.clear();fireworks.clear();leashes.clear();fishingParticles.clear();hasFishingTip=false;fishingLogCount=0;localVehicle=localPlayer=-1;
        for(ProjectileMotion p:projectiles) {p.active=false;p.firework=null;}
        replacementCursor = 0;
        spawnLogCount = 0;
    }

    private void put(int id, int type, double x, double y, double z,
                     float yaw, float pitch, float headYaw, boolean player) {
        int slot = find(id);
        if (slot < 0) {
            slot = findFree();
            if (slot < 0) slot = replacementCursor++ % ids.length;
        }
        if(ids[slot]>=0&&ids[slot]!=id)leashes.remove(ids[slot]);
        ids[slot] = id;
        hurtUntil[slot]=deathAt[slot]=swingAt[slot]=0;
        equipment[slot].clear();living[slot].blockState=-1;living[slot].itemId=-1;living[slot].itemTag="";java.util.Arrays.fill(living[slot].appearance,0);if(type==83)living[slot].appearance[18]=16;if(type==60)living[slot].appearance[20]=-1;if(type==11)living[slot].appearance[22]=14;if(type==116)living[slot].appearance[20]=14;if(type==108||type==120)living[slot].appearance[28]=2;
        living[slot].flags=living[slot].livingFlags=0;living[slot].health=20;
        living[slot].pose=0;living[slot].baby=false;living[slot].size=1;living[slot].horseFlags=0;living[slot].saddled=false;living[slot].vehicleVariant=0;
        mountSpeed[slot]=type==21||type==66?.175:type==10?.09:.225;mountJump[slot]=.7;
        living[slot].interactionWidth=living[slot].interactionHeight=1;
        types[slot] = type;
        xs[slot] = previousXs[slot] = x;
        ys[slot] = previousYs[slot] = y;
        zs[slot] = previousZs[slot] = z;
        yaws[slot] = previousYaws[slot] = yaw;
        pitches[slot] = previousPitches[slot] = pitch;
        headYaws[slot] = previousHeadYaws[slot] = headYaw;
        long now=System.nanoTime();
        updateNanos[slot] = headUpdateNanos[slot] = now - INTERPOLATION_NANOS;
        visualAnimations[slot].reset(id,x,y,z,yaw,now);onGround[slot]=true;
        if(bodyRotationType(type)) {
            if(chickenBodyRotations[slot]==null)chickenBodyRotations[slot]=new ChickenBodyRotation();
            chickenBodyRotations[slot].reset(x,z,yaw,headYaw,now);
        }
        players[slot] = player;profileIds[slot]=null;
        projectiles[slot].reset(type,x,y,z,yaw,pitch,0,0,0,System.nanoTime());
        if (spawnLogCount < 16) {
            spawnLogCount++;
            System.out.println("[NH-THIN] entity spawn id=" + id + " type=" + type
                    + " at=" + Math.round(x) + "," + Math.round(y) + "," + Math.round(z));
        }
    }

    private void beginInterpolation(int slot) {
        long now = System.nanoTime();
        advanceChickenBody(slot,now);
        advanceVisualAnimation(slot,now);
        double progress = interpolation(slot, now);
        captureInterpolated(slot, progress);
        updateNanos[slot] = now;
    }

    private void advanceChickenBody(int slot,long now) {
        if(!bodyRotationType(types[slot]))return;
        ChickenBodyRotation body=chickenBodyRotations[slot];
        long elapsed=now-body.tickNanos;
        if(elapsed<ChickenBodyRotation.TICK_NANOS)return;
        // A background/resume gap must not create an unbounded render-thread loop.
        if(elapsed>20*ChickenBodyRotation.TICK_NANOS)
            body.tickNanos=now-20*ChickenBodyRotation.TICK_NANOS;
        boolean carryingMob=false;
        if(riding!=null && riding.passengerCount(ids[slot])>0) {
            for(int passenger=0;passenger<ids.length;passenger++) {
                if(ids[passenger]>=0 && riding.vehicleOf(ids[passenger])==ids[slot]
                        && riding.seatOf(ids[passenger])==0) {
                    carryingMob=mobType(types[passenger]);
                    break;
                }
            }
        }
        while(now-body.tickNanos>=ChickenBodyRotation.TICK_NANOS) {
            body.tickNanos+=ChickenBodyRotation.TICK_NANOS;
            double progress=interpolation(slot,body.tickNanos);
            body.tick(lerp(previousXs[slot],xs[slot],progress),
                    lerp(previousZs[slot],zs[slot],progress),
                    interpolatedAngle(previousYaws[slot],yaws[slot],progress),
                    interpolatedAngle(previousHeadYaws[slot],headYaws[slot],
                            headInterpolation(slot,body.tickNanos)),carryingMob);
        }
    }

    private static boolean bodyRotationType(int type) {
        switch(type){case 0:case 5:case 6:case 16:case 20:case 27:case 41:case 44:case 71:case 78:case 81:case 96:case 99:case 105:case 107:return false;default:return mobType(type);}
    }
    private boolean inWater(int slot,double x,double y,double z) {
        if(world==null)return types[slot]==44||types[slot]==96;
        int by=(int)Math.floor(y+.1);
        return world.waterHeightAt((int)Math.floor(x),by,(int)Math.floor(z))>y+.1-by;
    }
    private void advanceVisualAnimation(int slot,long now) {
        EntityAnimation animation=visualAnimations[slot];
        if(now-animation.tickTime>20*EntityAnimation.TICK)animation.tickTime=now-20*EntityAnimation.TICK;
        while(now-animation.tickTime>=EntityAnimation.TICK) {
            animation.swimming(living[slot].pose==3);
            animation.tickTime+=EntityAnimation.TICK;
            double t=interpolation(slot,animation.tickTime);
            double x=lerp(previousXs[slot],xs[slot],t),y=lerp(previousYs[slot],ys[slot],t),z=lerp(previousZs[slot],zs[slot],t);
            ProjectileMotion motion=projectiles[slot];
            if(onGround[slot]&&(living[slot].flags&8)!=0&&world!=null) {
                double previous=interpolation(slot,animation.tickTime-EntityAnimation.TICK);
                double dx=x-lerp(previousXs[slot],xs[slot],previous),dz=z-lerp(previousZs[slot],zs[slot],previous);
                if(dx*dx+dz*dz>1e-7)worldParticles.running(world,x,y,z,dx,dz,CombatHitboxes.width(types[slot]),animation.tickTime);
            }
            animation.tick(x,y,z,types[slot],living[slot].pose==2||deathAt[slot]!=0||riding!=null&&riding.vehicleOf(ids[slot])>=0,
                    (types[slot]==44||types[slot]==96)&&inWater(slot,x,y,z),motion.vx,motion.vy,motion.vz);
        }
    }

    /** Vanilla registry entries inheriting Mob; players and armor stands are LivingEntity only. */
    private static boolean mobType(int type) {
        switch(type) {
            case 0:case 4:case 5:case 6:case 7:case 10:case 11:case 12:case 15:case 16:
            case 18:case 19:case 20:case 21:case 23:case 25:case 27:case 29:case 30:case 31:
            case 38:case 39:case 41:case 42:case 44:case 45:case 46:case 47:case 49:case 50:
            case 51:case 53:case 60:case 62:case 65:case 66:case 67:case 69:case 70:case 71:
            case 72:case 73:case 74:case 75:case 76:case 78:case 79:case 80:case 81:case 82:
            case 83:case 85:case 86:case 87:case 88:case 90:case 91:case 95:case 96:case 97:
            case 98:case 99:case 103:case 105:case 106:case 107:case 108:case 109:case 110:
            case 111:case 112:case 113:case 114:case 116:case 117:case 118:case 119:case 120:
            case 121:return true;
            default:return false;
        }
    }

    private void correctProjectile(int slot) {
        ProjectileMotion p=projectiles[slot];
        if(p.active) p.correct(xs[slot],ys[slot],zs[slot],yaws[slot],pitches[slot],System.nanoTime());
    }

    public synchronized void setMotion(ByteBuffer packet) throws ProtocolException {
        int id=VarInts.read(packet); BinaryCodec.require(packet,6);
        double vx=packet.getShort()/8000.0,vy=packet.getShort()/8000.0,vz=packet.getShort()/8000.0;
        requireConsumed(packet); int slot=find(id);
        if(slot>=0) {
            advanceVisualAnimation(slot,System.nanoTime());
            if(projectiles[slot].active)projectiles[slot].advance(System.nanoTime(),world);
            projectiles[slot].velocity(vx,vy,vz);
        }
    }

    public synchronized void setMetadata(ByteBuffer packet) throws ProtocolException {
        int id=VarInts.read(packet),slot=find(id);
        if(slot<0) { packet.position(packet.limit()); return; }
        if(projectiles[slot].active) ProjectileMetadata.read(packet,projectiles[slot]);
        else EntityMetadata.read(packet,living[slot]);
    }

    private void advanceProjectile(ProjectileMotion p,long now) {
        p.advance(now,world);
        if(p.type==ProjectileKind.FISHING) {
            if(p.hooked==localPlayer&&localPlayer>=0) {p.x=p.px=localPlayerPosition[0];p.y=p.py=localPlayerPosition[1]+1.8*.8;p.z=p.pz=localPlayerPosition[2];}
            else if(p.hooked>=0) {int target=find(p.hooked);if(target>=0&&!projectiles[target].active&&position(p.hooked,now,ropeStart)) {
                p.x=p.px=ropeStart[0];p.y=p.py=ropeStart[1]+entityHeight(target)*.8;p.z=p.pz=ropeStart[2];
            }}
            return;
        }
        if(p.type!=ProjectileKind.FIREWORK || p.exploded)return;
        if(p.attachedEntity>=0) {
            if(p.attachedEntity==localPlayer) {p.x=p.px=localPlayerPosition[0];p.y=p.py=localPlayerPosition[1];p.z=p.pz=localPlayerPosition[2];}
            else { int target=find(p.attachedEntity);
                if(target>=0&&!projectiles[target].active) {
                    double t=interpolation(target,now);p.x=p.px=lerp(previousXs[target],xs[target],t);
                    p.y=p.py=lerp(previousYs[target],ys[target],t);p.z=p.pz=lerp(previousZs[target],zs[target],t);
                }
            }
        }
        fireworks.trail(p,now);
    }

    public synchronized void pullHookedPlayer(int hook,int player,CombatState combat) {
        int slot=find(hook);if(slot<0||types[slot]!=ProjectileKind.FISHING||projectiles[slot].hooked!=player||player!=localPlayer)return;
        if(ropePosition(projectiles[slot].owner,System.nanoTime(),ropeEnd))
            combat.addImpulse((ropeEnd[0]-localPlayerPosition[0])*.1,(ropeEnd[1]-localPlayerPosition[1])*.1,(ropeEnd[2]-localPlayerPosition[2])*.1);
    }
    public synchronized int fishingParticles(FishingParticles.Particle[] out,long now) {return fishingParticles.snapshot(out,now,world);}
    private double entityHeight(int slot) {return CombatHitboxes.height(types[slot])*(living[slot].baby&&CombatHitboxes.hasBabySize(types[slot])?.5:1);}
    private boolean ropePosition(int id,long now,double[] out) {
        if(id==localPlayer&&localPlayer>=0) {System.arraycopy(localPlayerPosition,0,out,0,3);return true;}
        return position(id,now,out);
    }
    public synchronized int ropes(Rope[] out,long now,double cameraX,double cameraY,double cameraZ) {
        int count=0;
        for(int slot=0;slot<ids.length&&count<out.length;slot++) {
            int id=ids[slot];if(id<0)continue;
            boolean fishing=types[slot]==ProjectileKind.FISHING;
            int holder=fishing?projectiles[slot].owner:leashes.holder(id);
            if(holder<0||!ropePosition(id,now,ropeStart)||!ropePosition(holder,now,ropeEnd))continue;
            double dx=ropeStart[0]-cameraX,dy=ropeStart[1]-cameraY,dz=ropeStart[2]-cameraZ;
            double ex=ropeEnd[0]-cameraX,ey=ropeEnd[1]-cameraY,ez=ropeEnd[2]-cameraZ;
            if(dx*dx+dy*dy+dz*dz>96*96&&ex*ex+ey*ey+ez*ez>96*96)continue;
            if(fishing)ropeStart[1]+=.25;
            else {double yaw=Math.toRadians(yaws[slot]);double width=CombatHitboxes.width(types[slot])*(living[slot].baby?.5:1);
                ropeStart[0]-=Math.sin(yaw)*width*.4;ropeStart[2]+=Math.cos(yaw)*width*.4;ropeStart[1]+=entityHeight(slot)*.7;}
            if(holder==localPlayer && fishing && hasFishingTip) {
                System.arraycopy(fishingTip,0,ropeEnd,0,3);
            } else if(holder==localPlayer) {
                double yaw=Math.toRadians(localYaw),pitch=Math.toRadians(localPitch),side=fishing&&fishingOffhand?-1:1;
                double forward=fishing?.55:.35,vertical=fishing?-.22:-.3;
                double fy=Math.cos(pitch)*vertical-Math.sin(pitch)*forward,fz=Math.sin(pitch)*vertical+Math.cos(pitch)*forward;
                ropeEnd[0]+=-Math.cos(yaw)*.32*side-Math.sin(yaw)*fz;
                ropeEnd[1]+=1.62+fy;ropeEnd[2]+=-Math.sin(yaw)*.32*side+Math.cos(yaw)*fz;
            } else {int ownerSlot=find(holder);if(ownerSlot<0)continue;
                if(types[ownerSlot]==58)ropeEnd[1]+=.2;
                else {double yaw=Math.toRadians(yaws[ownerSlot]);ropeEnd[0]-=Math.cos(yaw)*.35;ropeEnd[2]-=Math.sin(yaw)*.35;ropeEnd[1]+=entityHeight(ownerSlot)*.8;}}
            double lengthX=ropeStart[0]-ropeEnd[0],lengthY=ropeStart[1]-ropeEnd[1],lengthZ=ropeStart[2]-ropeEnd[2];
            if(lengthX*lengthX+lengthY*lengthY+lengthZ*lengthZ>128*128)continue;
            Rope r=out[count++];r.x=ropeStart[0];r.y=ropeStart[1];r.z=ropeStart[2];r.endX=ropeEnd[0];r.endY=ropeEnd[1];r.endZ=ropeEnd[2];r.fishing=fishing;
        }
        return count;
    }

    public synchronized void hurt(int entity,long now) { int slot=find(entity);if(slot>=0)hurtUntil[slot]=now+500_000_000L; }
    public synchronized void status(int entity,int status,long now) {
        int slot=find(entity);if(slot<0)return;
        if(status==17 && types[slot]==ProjectileKind.FIREWORK) {
            advanceProjectile(projectiles[slot],now);fireworks.explode(projectiles[slot],now);
        }
        if(status==19&&(types[slot]==44||types[slot]==96)){advanceVisualAnimation(slot,now);visualAnimations[slot].resetSquidCycle();}
        if(status==1&&types[slot]==79){advanceVisualAnimation(slot,now);visualAnimations[slot].jump();}
        if(status==3) {
            ProjectileMotion p=projectiles[slot];
            if(p.active) {
                advanceProjectile(p,now);
                if(types[slot]==ProjectileKind.EGG||types[slot]==ProjectileKind.SNOWBALL)
                    worldParticles.burst(WorldParticles.ITEM,types[slot]==ProjectileKind.EGG?887:872,p.renderX(now),p.renderY(now),p.renderZ(now),8,0,.1,0xffffff,now);
                else if(types[slot]==ProjectileKind.PEARL)worldParticles.burst(49,0,p.renderX(now),p.renderY(now),p.renderZ(now),32,2,.2,0x8050d0,now);
            } else if(mobType(types[slot])||players[slot]||types[slot]==122)
                worldParticles.burst(48,0,xs[slot],ys[slot]+.7,zs[slot],20,1,.02,0xffffff,now);
        }
        if(status==6||status==7||status==18)worldParticles.burst(status==6?51:38,0,xs[slot],ys[slot]+1,zs[slot],7,1,.02,0xffffff,now);
        if(status==2)hurtUntil[slot]=now+500_000_000L;
        if(status==3)deathAt[slot]=now;
    }
    public synchronized void animate(int entity,int animation,long now) {
        int slot=find(entity);if(slot<0)return;
        if(animation==0||animation==3) {swingAt[slot]=now;animations[slot]=animation;}
        if(animation==4||animation==5)combatParticles.burst(xs[slot],ys[slot]+.9,zs[slot],animation==5?1:0,now);
    }
    public synchronized boolean alive(int entity) {int slot=find(entity);return slot>=0 && deathAt[slot]==0 && living[slot].health>0;}

    public synchronized void correctVehicle(int id,double x,double y,double z,float yaw,float pitch){
        int slot=find(id);if(slot<0)return;xs[slot]=previousXs[slot]=x;ys[slot]=previousYs[slot]=y;zs[slot]=previousZs[slot]=z;
        yaws[slot]=previousYaws[slot]=yaw;pitches[slot]=previousPitches[slot]=pitch;updateNanos[slot]=System.nanoTime();
        if(id==localVehicle)localVehicle(id,x,y,z,yaw,pitch);
    }
    public static final class Vehicle {
        public int type,variant,horseFlags;public boolean saddled;
        public double x,y,z,speed,jump;public float yaw,pitch;
    }
    public synchronized boolean vehicle(int id,long now,Vehicle out){
        int slot=find(id);if(slot<0)return false;
        if(!position(id,now,passengerPosition))return false;
        out.x=passengerPosition[0];out.y=passengerPosition[1];out.z=passengerPosition[2];
        double progress=interpolation(slot,now);out.yaw=interpolatedAngle(previousYaws[slot],yaws[slot],progress);out.pitch=pitches[slot];
        if(id==localVehicle){out.yaw=(float)localVehiclePose[3];out.pitch=(float)localVehiclePose[4];}
        out.type=types[slot];out.variant=living[slot].vehicleVariant;out.horseFlags=living[slot].horseFlags;out.saddled=living[slot].saddled;
        out.speed=mountSpeed[slot];out.jump=mountJump[slot];return true;
    }
    public synchronized void attribute(int id,String key,double value){
        int slot=find(id);if(slot<0||!Double.isFinite(value))return;
        if(key.endsWith("generic.movement_speed"))mountSpeed[slot]=Math.max(0,Math.min(1024,value));
        if(key.endsWith("horse.jump_strength"))mountJump[slot]=Math.max(0,Math.min(2,value));
    }

    private void captureInterpolated(int slot, double progress) {
        previousXs[slot] = lerp(previousXs[slot], xs[slot], progress);
        previousYs[slot] = lerp(previousYs[slot], ys[slot], progress);
        previousZs[slot] = lerp(previousZs[slot], zs[slot], progress);
        previousYaws[slot] = interpolatedAngle(
                previousYaws[slot], yaws[slot], progress);
        previousPitches[slot] = interpolatedAngle(
                previousPitches[slot], pitches[slot], progress);
    }

    private double interpolation(int slot, long now) {
        return Math.max(0.0, Math.min(1.0,
                (now - updateNanos[slot]) / (double) INTERPOLATION_NANOS));
    }

    private double headInterpolation(int slot,long now) {
        return Math.max(0.0,Math.min(1.0,
                (now-headUpdateNanos[slot])/(double)INTERPOLATION_NANOS));
    }

    private int find(int id) {
        // -1 is the empty-slot sentinel, never an entity that callers may follow.
        if(id<0)return -1;
        for (int slot = 0; slot < ids.length; slot++) {
            if (ids[slot] == id) return slot;
        }
        return -1;
    }

    private int findFree() {
        for (int slot = 0; slot < ids.length; slot++) {
            if (ids[slot] < 0) return slot;
        }
        return -1;
    }

    private static float unpackAngle(byte packed) {
        return packed * (360.0f / 256.0f);
    }

    private static double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static float interpolatedAngle(float start, float end, double progress) {
        float delta = (end - start) % 360.0f;
        if (delta >= 180.0f) delta -= 360.0f;
        if (delta < -180.0f) delta += 360.0f;
        return start + delta * (float) progress;
    }

    static double rayAabb(double ox, double oy, double oz,
                                  double dx, double dy, double dz,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ,
                                  double limit) {
        double near = 0.0;
        double far = limit;
        if (Math.abs(dx) < 1.0e-9) {
            if (ox < minX || ox > maxX) return -1.0;
        } else {
            double a = (minX - ox) / dx;
            double b = (maxX - ox) / dx;
            if (a > b) { double swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return -1.0;
        }
        if (Math.abs(dy) < 1.0e-9) {
            if (oy < minY || oy > maxY) return -1.0;
        } else {
            double a = (minY - oy) / dy;
            double b = (maxY - oy) / dy;
            if (a > b) { double swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return -1.0;
        }
        if (Math.abs(dz) < 1.0e-9) {
            if (oz < minZ || oz > maxZ) return -1.0;
        } else {
            double a = (minZ - oz) / dz;
            double b = (maxZ - oz) / dz;
            if (a > b) { double swap = a; a = b; b = swap; }
            near = Math.max(near, a);
            far = Math.min(far, b);
            if (near > far) return -1.0;
        }
        return near <= limit ? near : -1.0;
    }

    private static void requireConsumed(ByteBuffer packet) throws ProtocolException {
        if (packet.hasRemaining()) throw new ProtocolException("Trailing entity packet bytes");
    }

    public synchronized void setEquipment(ByteBuffer packet)throws ProtocolException {int slot=find(VarInts.read(packet));if(slot<0){new EntityEquipment().read(packet);return;}equipment[slot].read(packet);}

    public static final class Renderable {
        public final int[] equipment={-1,-1,-1,-1,-1,-1};
        public final String[] equipmentTags={"","","","","",""};
        public boolean hasBow(){return equipment[0]==EntityEquipment.BOW||equipment[1]==EntityEquipment.BOW;}
        public int entityId;
        public int type;
        public float x;
        public float y;
        public float z;
        public float yaw;
        public float bodyYaw=Float.NaN;
        public float pitch;
        public float headYaw;
        public boolean player;
        public java.util.UUID profileId;
        public boolean slim,sneaking,swimming,prone;
        public float swimAmount;
        public final int[] appearance=new int[32];
        public boolean baby;public int size=1;
        public int blockState=-1;public int itemId;
        public String itemTag="";
        public int color;
        public boolean hurt,invisible,blocking,offhandSwing,riding,sleeping;
        public float deathProgress,swingProgress;
        public boolean onGround=true,inWater,burning;
        public float age,walkPosition,walkSpeed,squidPitch,squidRoll,squidTentacle,jumpProgress=1;
    }

    public static final class Hit {
        public int entityId = -1;
        public double distance;
    }
}
