package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import com.newhorizon.thinclient.inventory.ItemUseState;
import java.nio.ByteBuffer;
import java.util.UUID;

public final class ProjectileSelfTest {
    public static void run() throws Exception {
        check(MinecraftPacketIds.PLAY_CLIENTBOUND_COOLDOWN==0x15
                && MinecraftPacketIds.PLAY_CLIENTBOUND_SET_ENTITY_DATA==0x52
                && MinecraftPacketIds.PLAY_CLIENTBOUND_SET_ENTITY_MOTION==0x54,
                "IDs audited against official ConnectionProtocol PLAY registration");
        ProjectileMotion p=new ProjectileMotion();
        check(ProjectileKind.airUseItem("minecraft:egg") && ProjectileKind.chargedItem("bow")
                && ProjectileKind.chargedItem("BOW") && !ProjectileKind.airUseItem("mod:egg"),
                "real bridge/creative material names are normalized without aliasing mod items");
        p.reset(ProjectileKind.EGG,0,64,0,0,0,0,0,1,0);
        p.advance(100_000_000L,null);
        check(Math.abs(p.z-1.99)<1e-6 && Math.abs(p.y-63.97)<1e-6,"egg 20Hz drag/gravity");
        check(Math.abs(p.renderZ(125_000_000L)-1.495)<1e-6,"partial tick interpolation");
        p.reset(ProjectileKind.FIREBALL,0,64,0,0,0,0,0,1,0);
        p.advance(100_000_000L,null);
        check(Math.abs(p.z-.095)<1e-6 && p.y==64,"fireball acceleration, not spawn velocity");
        p.reset(ProjectileKind.POTION,0,64,0,0,0,0,0,1,0);
        p.advance(100_000_000L,null);
        check(Math.abs(p.y-63.95)<1e-6,"potion gravity");
        p.reset(ProjectileKind.ARROW,0,64,0,0,0,0,0,1,0); p.noGravity=true;
        p.advance(100_000_000L,null); check(p.y==64,"no gravity metadata behavior");

        EntityTracker tracker=new EntityTracker(2);
        tracker.addEntity(spawn(17,ProjectileKind.EGG,0,64,0,0,0,8000));
        EntityTracker.Renderable[] output={new EntityTracker.Renderable(),new EntityTracker.Renderable()};
        long now=System.nanoTime();
        tracker.snapshotVisible(output,now+150_000_000L,0,64,0,72,-1);
        check(output[0].z>1,"spawn velocity is rendered between server packets");
        ByteBuffer move=ByteBuffer.allocate(16); VarInts.write(move,17);
        move.putShort((short)0).putShort((short)0).putShort((short)4096).put((byte)0).flip();
        tracker.moveEntity(move,false);
        tracker.snapshotVisible(output,System.nanoTime(),0,64,0,72,-1);
        check(Math.abs(output[0].z-1)<.01,"relative movement uses server baseline, not prediction");
        EntityTracker.Hit hit=new EntityTracker.Hit();
        tracker.findRayHit(0,64,0,0,0,1,5,-1,hit);
        check(hit.entityId==-1,"non-pickable egg does not swallow use/attack ray");
        tracker.addEntity(spawn(18,ProjectileKind.FIREBALL,0,64,3,0,0,0));
        tracker.findRayHit(0,64.3,0,0,0,1,5,-1,hit);
        check(hit.entityId==18,"large fireball can be deflected with attack");

        ByteBuffer metadata=ByteBuffer.allocate(128);
        metadata.put((byte)5); VarInts.write(metadata,8); metadata.put((byte)1);
        metadata.put((byte)8); VarInts.write(metadata,7); metadata.put((byte)1);
        VarInts.write(metadata,1115); metadata.put((byte)1);
        metadata.put((byte)10).putShort((short)0).put((byte)3).putShort((short)17)
                .put("CustomPotionColor".getBytes("UTF-8")).putInt(0x123456).put((byte)0).put((byte)255).flip();
        ProjectileMetadata.read(metadata,p);
        check(p.itemId==1115 && p.color==0x123456 && p.noGravity,"potion item NBT and base fields");
        boolean truncated=false;
        try { ProjectileMetadata.read(ByteBuffer.wrap(new byte[]{8,7,1}),p); }
        catch(ProtocolException expected) { truncated=true; }
        check(truncated,"truncated metadata rejected");

        ByteBuffer motion=ByteBuffer.allocate(16); VarInts.write(motion,17);
        motion.putShort((short)0).putShort((short)0).putShort((short)-8000).flip();
        tracker.setMotion(motion);
        tracker.snapshotVisible(output,System.nanoTime()+150_000_000L,0,64,0,72,-1);
        check(output[0].z<1,"server motion reverses visual flight");
        ByteBuffer remove=ByteBuffer.allocate(8); VarInts.write(remove,1); VarInts.write(remove,17); remove.flip();
        tracker.removeEntities(remove);
        check(tracker.typeOf(17)==-1,"server removal ends projectile lifetime");
        tracker.clear(); check(tracker.snapshotVisible(output,System.nanoTime(),0,64,0,72,-1)==0,"world reset clears projectiles");

        ItemUseState uses=new ItemUseState(); ByteBuffer cooldown=ByteBuffer.allocate(8);
        VarInts.write(cooldown,952); VarInts.write(cooldown,20); cooldown.flip(); uses.readCooldown(cooldown);
        check(uses.coolingDown("ENDER_PEARL",System.nanoTime()),"server pearl cooldown");
        check(uses.coolingDown("minecraft:ender_pearl",System.nanoTime()),"bridge material cooldown");
        cooldown.clear(); VarInts.write(cooldown,952); VarInts.write(cooldown,0); cooldown.flip(); uses.readCooldown(cooldown);
        check(!uses.coolingDown("ENDER_PEARL",System.nanoTime()),"server can cancel cooldown");

        ByteBuffer release=ByteBuffer.allocate(32);
        MinecraftPackets.writePlayerAction(release,5,0,0,0,0,9); release.flip();
        check(VarInts.read(release)==0x1d && VarInts.read(release)==5 && release.getLong()==0
                && release.get()==0 && VarInts.read(release)==9 && !release.hasRemaining(),"release-use wire packet");
        System.out.println("Projectile tests passed: flight, corrections, metadata, picking, cooldowns, release");
    }
    private static ByteBuffer spawn(int id,int type,double x,double y,double z,int vx,int vy,int vz) {
        ByteBuffer b=ByteBuffer.allocate(96); VarInts.write(b,id); BinaryCodec.writeUuid(b,new UUID(0,id));
        VarInts.write(b,type); b.putDouble(x).putDouble(y).putDouble(z).put((byte)0).put((byte)0).put((byte)0);
        VarInts.write(b,0); b.putShort((short)vx).putShort((short)vy).putShort((short)vz); b.flip(); return b;
    }
    private static void check(boolean value,String text) { if(!value)throw new AssertionError(text); }
}
