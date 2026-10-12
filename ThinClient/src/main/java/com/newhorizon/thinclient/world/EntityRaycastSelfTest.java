package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Uses actual spawn/metadata packets to check the entities selected for interaction. */
public final class EntityRaycastSelfTest {
    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        EntityTracker tracker=new EntityTracker(2);
        tracker.addEntity(spawn(1,2,2)); // Armor stand in front of a player NPC.
        tracker.addEntity(spawn(2,122,3));
        hit(tracker,0,65.5,1,"normal armor stand can be selected");
        tracker.setMetadata(byteMetadata(1,15,0x10));
        hit(tracker,0,65.5,2,"marker hologram cannot intercept the NPC");
        tracker.setMetadata(byteMetadata(1,15,0x11));
        hit(tracker,0,64.5,2,"small marker hologram cannot intercept the NPC");

        ByteBuffer health=ByteBuffer.allocate(12);VarInts.write(health,1);
        health.put((byte)9).put((byte)3).putFloat(20).put((byte)255).flip();
        tracker.setMetadata(health);
        hit(tracker,0,64.5,2,"partial metadata retains marker status");
        EntityTracker.Renderable[] visible={new EntityTracker.Renderable(),new EntityTracker.Renderable()};
        check(tracker.snapshotVisible(visible,System.nanoTime(),0,64,0,100,-1)==2,
                "marker remains available to rendering and name labels");

        tracker.setMetadata(byteMetadata(1,15,0x01));
        hit(tracker,0,65.5,2,"small armor stand is shorter than a player");
        hit(tracker,0,64.5,1,"small armor stand still has an interactive body");
        hit(tracker,.2,64.5,2,"small armor stand uses half the normal width");
        hit(tracker,.12,64.5,1,"small armor stand includes points within width 0.25");
        hit(tracker,0,64.98,1,"small armor stand includes points below height 0.9875");
        hit(tracker,0,65,2,"small armor stand excludes points above height 0.9875");

        tracker.setMetadata(byteMetadata(1,15,0));
        hit(tracker,.2,65.5,1,"clearing small/marker flags restores the normal hitbox");
        tracker.setMetadata(byteMetadata(1,0,0x20));
        hit(tracker,0,65.5,1,"invisibility alone does not disable interaction");
        tracker.setMetadata(byteMetadata(1,15,0x10));
        hit(tracker,0,65.5,2,"invisible marker cannot intercept the NPC");

        EntityTracker reused=new EntityTracker(1);
        reused.addEntity(spawn(3,2,2));
        reused.setMetadata(byteMetadata(3,15,0x10));
        hit(reused,0,65.5,-1,"a marker on its own is not selectable");
        reused.addEntity(spawn(4,2,2));
        hit(reused,0,65.5,4,"a replacement entity does not inherit marker flags");
        System.out.println("Entity raycast tests passed: marker holograms, small armor stands, partial metadata, invisibility and slot reuse");
    }

    private static void hit(EntityTracker tracker,double x,double y,int expected,String message) {
        EntityTracker.Hit hit=new EntityTracker.Hit();
        tracker.findRayHit(x,y,0,0,0,1,5,-1,hit);
        check(hit.entityId==expected,message+": selected "+hit.entityId+", expected "+expected);
    }

    private static ByteBuffer spawn(int id,int type,double z) {
        ByteBuffer packet=ByteBuffer.allocate(96);VarInts.write(packet,id);
        BinaryCodec.writeUuid(packet,new UUID(0,id));VarInts.write(packet,type);
        packet.putDouble(0).putDouble(64).putDouble(z).put((byte)0).put((byte)0)
                .put((byte)0).put((byte)0).putShort((short)0).putShort((short)0).putShort((short)0).flip();
        return packet;
    }

    private static ByteBuffer byteMetadata(int id,int index,int value) {
        ByteBuffer packet=ByteBuffer.allocate(8);VarInts.write(packet,id);
        packet.put((byte)index).put((byte)0).put((byte)value).put((byte)255).flip();
        return packet;
    }

    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
