package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.inventory.ItemUseState;
import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Protocol and timing regressions; intentionally independent of GL/Android and server damage rules. */
public final class CombatSelfTest {
    public static void run() throws Exception {
        CombatState c=new CombatState();long now=System.nanoTime();
        c.selectWeapon("minecraft:diamond_sword",now);
        near(c.attackStrength(now,0),0,"equip resets cooldown");
        near(c.attackStrength(now+300_000_000L,0),.48,"sword at six ticks");
        near(c.attackStrength(now+650_000_000L,0),1,"sword ready at thirteen ticks");
        c.selectWeapon("DIAMOND_SWORD",now+300_000_000L);
        near(c.attackStrength(now+300_000_000L,0),.48,"same weapon snapshot does not reset");
        check(c.mayAttack(now),"weak attacks must still be sent to server");
        c.attribute("minecraft:generic.attack_speed",8);near(c.attackStrength(now+100_000_000L,0),.8,"server attribute override");
        c.attacked(now,true,false);check(!c.mayAttack(now+400_000_000L),"miss delay");
        check(c.mayAttack(now+500_000_000L),"miss delay expires");c.releaseAttack();check(c.mayAttack(now),"button release clears miss delay");
        c.use(true,true);check(!c.mayAttack(now)&&!c.canSprint(),"shield use prevents attack/sprint");
        c.shieldStatus(30,now);check(!c.usingItem()&&c.shieldDisabled(now),"shield break stops blocking");
        check(!c.shieldDisabled(now+5_000_000_000L),"shield status expires");
        near(CombatState.weaponSpeed("wooden_axe"),.8,"wood axe speed");
        near(CombatState.weaponSpeed("iron_axe"),.9,"iron axe speed");
        near(CombatState.weaponSpeed("diamond_hoe"),4,"diamond hoe speed");
        near(CombatState.weaponSpeed("trident"),1.1,"trident speed");
        ItemUseState use=new ItemUseState();ByteBuffer cd=ByteBuffer.allocate(16);
        VarInts.write(cd,1116);VarInts.write(cd,100);cd.flip();use.readCooldown(cd);
        check(use.coolingDown("minecraft:shield",System.nanoTime()),"server shield cooldown ID");
        cd.clear();VarInts.write(cd,1116);VarInts.write(cd,0);cd.flip();use.readCooldown(cd);
        check(!use.coolingDown("shield",System.nanoTime()),"server can clear shield cooldown");

        double[] velocity=new double[3];
        c.readMotion(motion(42,8000,4000,-8000),42);
        c.readExplosionMotion(explosion(.25f,.5f,0));
        check(c.drainMotion(velocity)==1,"velocity then explosion replaces with combined value");
        near(velocity[0],1.25,"combined X");near(velocity[1],1,"combined Y");near(velocity[2],-1,"combined Z");
        check(c.drainMotion(velocity)==0,"impulse applied exactly once");
        c.readMotion(motion(99,8000,8000,8000),42);check(c.drainMotion(velocity)==0,"other entity cannot knock back player");
        c.readExplosionMotion(explosion(.5f,0,0));check(c.drainMotion(velocity)==2,"standalone explosion adds");
        c.readExplosionMotion(explosion(.5f,0,0));c.readMotion(motion(42,2000,0,0),42);
        check(c.drainMotion(velocity)==1,"later velocity supersedes pending explosion");near(velocity[0],.25,"server correction wins");
        try{c.readMotion(ByteBuffer.wrap(new byte[]{42,0}),42);throw new AssertionError("truncated velocity accepted");}
        catch(ProtocolException expected){}
        c.readHealth(health(7,6,0));check(!c.canSprint(),"low hunger prevents sprint");
        ByteBuffer xp=ByteBuffer.allocate(16);xp.putFloat(.5f);VarInts.write(xp,12);VarInts.write(xp,123);xp.flip();c.readExperience(xp);
        ByteBuffer meta=ByteBuffer.allocate(64);VarInts.write(meta,42);
        meta.put((byte)0).put((byte)0).put((byte)1); // burning
        meta.put((byte)1).put((byte)1);VarInts.write(meta,120);
        meta.put((byte)15).put((byte)3).putFloat(4).put((byte)255).flip();c.readMetadata(meta,42);
        String[] hud=c.encodeForUi(now,new PlayerEffects(),false,false).split(",");
        near(Float.parseFloat(hud[0]),7,"health HUD");near(Float.parseFloat(hud[4]),4,"absorption metadata");
        check(hud[6].equals("120")&&hud[7].equals("12")&&hud[13].equals("1"),"air XP and burning HUD");
        c.readHealth(health(0,20,5));check(c.dead()&&!c.mayAttack(now),"server death blocks combat");
        check(c.canRespawn(System.nanoTime()+1_000_000_001L),"respawn button delay");
        c.clear();check(!c.dead()&&c.canSprint()&&!c.usingItem(),"respawn clears combat state");
        ByteBuffer wire=ByteBuffer.allocate(32);MinecraftPackets.writeUseItem(wire,1,17);wire.flip();
        check(VarInts.read(wire)==0x32&&VarInts.read(wire)==1&&VarInts.read(wire)==17&&!wire.hasRemaining(),"offhand use packet");
        wire.clear();MinecraftPackets.writeRespawnRequest(wire);wire.flip();
        check(VarInts.read(wire)==7&&VarInts.read(wire)==0&&!wire.hasRemaining(),"respawn request packet");
        ByteBuffer respawn=ByteBuffer.allocate(256);
        BinaryCodec.writeString(respawn,"minecraft:overworld",32767);BinaryCodec.writeString(respawn,"minecraft:overworld",32767);
        respawn.putLong(0).put((byte)3).put((byte)1).put((byte)0).put((byte)0).put((byte)3).put((byte)1);
        BinaryCodec.writeString(respawn,"minecraft:the_nether",32767);respawn.putLong(123);VarInts.write(respawn,20);respawn.flip();
        check(MinecraftPackets.readRespawnFlags(respawn)==0x303,"respawn mode/keep mask and optional death location");
        c.selectWeapon("diamond_sword",now);c.attribute("generic.attack_speed",7);c.attribute("generic.max_health",40);c.readHealth(health(17,12,3));
        c.respawn(3);near(c.attackSpeed(),7,"dimension transfer retains server attributes");
        near(Float.parseFloat(c.encodeForUi(now,new PlayerEffects(),false,false).split(",")[0]),17,"dimension transfer retains entity health");
        c.respawn(0);near(c.attackSpeed(),4,"death respawn clears stale weapon attribute");

        EntityTracker tracker=new EntityTracker(4);EntityTracker.Hit hit=new EntityTracker.Hit();
        tracker.addEntity(spawn(9,18,0,64,3)); // cow
        tracker.findRayHit(0,65.3,0,0,0,1,3,-1,hit);check(hit.entityId==9,"cow vanilla dimensions");
        tracker.findRayHit(0,65.5,0,0,0,1,3,-1,hit);check(hit.entityId==-1,"ray above cow");
        meta.clear();VarInts.write(meta,9);meta.put((byte)16).put((byte)8).put((byte)1).put((byte)255).flip();tracker.setMetadata(meta);
        tracker.findRayHit(0,65.3,0,0,0,1,3,-1,hit);check(hit.entityId==-1,"baby cow hitbox halves");
        tracker.findRayHit(0,64.5,0,0,0,1,3,-1,hit);check(hit.entityId==9,"baby remains pickable");
        tracker.hurt(9,now);tracker.status(9,3,now);
        tracker.findRayHit(0,64.5,0,0,0,1,3,-1,hit);check(hit.entityId==-1&&!tracker.alive(9),"dead entity no longer attackable");
        tracker.clear();tracker.addEntity(spawn(10,122,0,64,3));
        meta.clear();VarInts.write(meta,10);meta.put((byte)6).put((byte)20).put((byte)5).put((byte)255).flip();tracker.setMetadata(meta);
        tracker.findRayHit(0,65.62,0,0,0,1,3,-1,hit);check(hit.entityId==-1,"crouching player hitbox");
        check(!CombatHitboxes.pickable(54)&&!CombatHitboxes.pickable(34),"item/orb do not intercept combat");
        near(CombatHitboxes.reach(false),3,"survival entity reach");near(CombatHitboxes.reach(true),6,"creative entity reach");
        near(CombatHitboxes.width(250),.6,"unknown mod type bounded fallback");
        CombatParticles particles=new CombatParticles(8);CombatParticles.Particle[] out=new CombatParticles.Particle[8];
        for(int i=0;i<8;i++)out[i]=new CombatParticles.Particle();
        for(int i=0;i<100;i++)particles.burst(0,64,0,0,now);
        check(particles.snapshot(out,now+100_000_000L)==8,"particle capacity remains bounded");
        check(particles.snapshot(out,now+400_000_000L)==0,"particles expire without server removal");
        particles.burst(0,64,0,2,now);particles.clear();check(particles.snapshot(out,now)==0,"world reset clears particles");
        System.out.println("Combat tests passed: cooldown, attributes, shield, motion ordering, vitals, death, wire packets, hitboxes and bounded particles");
    }
    private static ByteBuffer motion(int id,int x,int y,int z){ByteBuffer b=ByteBuffer.allocate(16);VarInts.write(b,id);b.putShort((short)x).putShort((short)y).putShort((short)z).flip();return b;}
    private static ByteBuffer explosion(float x,float y,float z){ByteBuffer b=ByteBuffer.allocate(48);b.putDouble(0).putDouble(64).putDouble(0).putFloat(4).put((byte)0).putFloat(x).putFloat(y).putFloat(z).flip();return b;}
    private static ByteBuffer health(float hp,int food,float saturation){ByteBuffer b=ByteBuffer.allocate(16);b.putFloat(hp);VarInts.write(b,food);b.putFloat(saturation).flip();return b;}
    private static ByteBuffer spawn(int id,int type,double x,double y,double z){ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,id);BinaryCodec.writeUuid(b,new UUID(0,id));VarInts.write(b,type);b.putDouble(x).putDouble(y).putDouble(z).put((byte)0).put((byte)0).put((byte)0).put((byte)0).putShort((short)0).putShort((short)0).putShort((short)0).flip();return b;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-6,message+": "+actual+" != "+expected);}
}
