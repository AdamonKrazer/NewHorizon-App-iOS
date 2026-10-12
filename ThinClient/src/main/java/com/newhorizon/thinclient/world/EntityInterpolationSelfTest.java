package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Replays server movement cadence without sleeps or a running renderer. */
public final class EntityInterpolationSelfTest {
    private static final long MILLIS=1_000_000L;

    public static void run() throws Exception {
        for(int type:new int[]{60,103,110,122,15}) steadyMovement(type);
        interruptedMovement();
        traderVisibility();
        EntityRaycastSelfTest.run();
        System.out.println("Entity interpolation tests passed: three-tick cadence, continuous movement, interrupted targets, stop, teleport and server invisibility");
    }

    private static void steadyMovement(int type) throws Exception {
        EntityTracker tracker=new EntityTracker(2);
        tracker.addEntity(spawn(1,type));
        long start=System.nanoTime();
        double[] position=new double[3];
        EntityTracker.Renderable[] out={new EntityTracker.Renderable()};
        // 0.375 blocks per server update: six equal 25 ms render steps, including
        // the final 50 ms that used to freeze while awaiting the next packet.
        double previous=0;
        for(int packet=0;packet<20;packet++) {
            long receipt=start+packet*150*MILLIS;
            tracker.moveEntity(move(1,1536,0,0),false,receipt);
            for(int frame=1;frame<=6;frame++) {
                long now=receipt+frame*25*MILLIS;
                check(tracker.position(1,now,position),"entity remains tracked");
                near(position[0]-previous,.0625,"steady frame displacement, type="+type);
                previous=position[0];
                check(tracker.snapshotVisible(out,now,0,64,0,100,-1)==1,"visible entity");
                near(out[0].x,position[0],"model and attachment coordinates agree");
                if(packet>5) near(out[0].walkSpeed,.5,"steady gait follows distance per tick");
            }
        }
        tracker.position(1,start+4000*MILLIS,position);
        near(position[0],7.5,"stopped server does not extrapolate beyond target");
        tracker.snapshotVisible(out,start+5000*MILLIS,0,64,0,100,-1);
        check(out[0].walkSpeed<.001,"gait settles after stopping");
    }

    private static void interruptedMovement() throws Exception {
        EntityTracker tracker=new EntityTracker(1);
        tracker.addEntity(spawn(1,103));
        long start=System.nanoTime();
        double[] position=new double[3];
        tracker.moveEntity(move(1,1536,0,0),false,start);
        tracker.moveEntity(move(1,1536,0,0),false,start+75*MILLIS);
        tracker.position(1,start+75*MILLIS,position);
        near(position[0],.1875,"early update preserves current visual position");
        tracker.position(1,start+225*MILLIS,position);
        near(position[0],.75,"relative deltas accumulate from server position, not visual position");
        tracker.moveEntity(move(1,-1536,4096,0),false,start+225*MILLIS);
        tracker.position(1,start+300*MILLIS,position);
        near(position[0],.5625,"direction reversal interpolates continuously");
        near(position[1],64.5,"vertical motion uses same clock");
        ByteBuffer teleport=ByteBuffer.allocate(40);
        VarInts.write(teleport,1);
        teleport.putDouble(50).putDouble(64).putDouble(0)
                .put((byte)0).put((byte)0).put((byte)1).flip();
        tracker.teleportEntity(teleport);
        tracker.position(1,System.nanoTime(),position);
        near(position[0],50,"large teleport snaps without streaking across terrain");
    }

    private static void traderVisibility() throws Exception {
        EntityTracker tracker=new EntityTracker(2);
        tracker.addEntity(spawn(1,110));tracker.addEntity(spawn(2,103));
        EntityTracker.Renderable[] out={new EntityTracker.Renderable(),new EntityTracker.Renderable()};
        tracker.setMetadata(flags(1,32));
        check(tracker.snapshotVisible(out,System.nanoTime(),0,64,0,100,-1)==2,"invisibility retains tracked entities");
        check(out[0].invisible&&!out[1].invisible,"trader invisibility does not hide its llama");
        ByteBuffer health=ByteBuffer.allocate(12);VarInts.write(health,1);
        health.put((byte)9).put((byte)3).putFloat(20).put((byte)255).flip();
        tracker.setMetadata(health);
        tracker.snapshotVisible(out,System.nanoTime(),0,64,0,100,-1);
        check(out[0].invisible,"unrelated metadata cannot cancel invisibility");
        tracker.setMetadata(flags(1,0));
        tracker.snapshotVisible(out,System.nanoTime(),0,64,0,100,-1);
        check(!out[0].invisible&&tracker.count()==2,"server makes same trader visible again without respawn");
    }

    private static ByteBuffer spawn(int id,int type) {
        ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,id);
        BinaryCodec.writeUuid(b,new UUID(0,id));VarInts.write(b,type);
        b.putDouble(0).putDouble(64).putDouble(0).put((byte)0).put((byte)0)
                .put((byte)0).put((byte)0).putShort((short)0).putShort((short)0).putShort((short)0).flip();
        return b;
    }
    private static ByteBuffer move(int id,int dx,int dy,int dz) {
        ByteBuffer b=ByteBuffer.allocate(16);VarInts.write(b,id);
        b.putShort((short)dx).putShort((short)dy).putShort((short)dz).put((byte)1).flip();return b;
    }
    private static ByteBuffer flags(int id,int flags) {
        ByteBuffer b=ByteBuffer.allocate(8);VarInts.write(b,id);
        b.put((byte)0).put((byte)0).put((byte)flags).put((byte)255).flip();return b;
    }
    private static void near(double actual,double expected,String message) {
        check(Math.abs(actual-expected)<.0001,message+": "+actual+" != "+expected);
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
