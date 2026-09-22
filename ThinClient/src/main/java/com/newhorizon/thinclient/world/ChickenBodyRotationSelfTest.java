package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.VarInts;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Headless checks for independent packet interpolation and vanilla chicken body control. */
public final class ChickenBodyRotationSelfTest {
    private static final long TICK=ChickenBodyRotation.TICK_NANOS;

    public static void run() throws Exception {
        movingAndIdle();
        independentPacketClocks();
        trackerPresentation();
        System.out.println("Chicken body rotation tests passed: independent head/move clocks, 75-degree clamp, idle delay, angle wrap, passenger rule and visual-only yaw");
    }

    private static void movingAndIdle() {
        ChickenBodyRotation body=new ChickenBodyRotation();
        body.reset(0,0,0,0,0);
        body.tick(1,0,30,150,false);
        angle(body.bodyYaw(TICK),30,"moving body follows entity yaw");
        angle(body.headYaw(TICK),105,"moving head stays within 75 degrees of body");
        body.reset(0,0,0,0,0);
        body.tick(.0004,0,90,0,false);
        angle(body.bodyYaw(TICK),0,"sub-threshold motion leaves body stationary");
        body.tick(.001,0,90,0,false);
        angle(body.bodyYaw(TICK),90,"motion above vanilla squared threshold turns body");

        body.reset(0,0,0,0,0);
        body.tick(0,0,0,90,false);
        angle(body.bodyYaw(TICK),15,"head turn initially leaves 75-degree body gap");
        for(int i=0;i<10;i++)body.tick(0,0,0,90,false);
        angle(body.bodyYaw(TICK),15,"stable head waits ten complete ticks");
        body.tick(0,0,0,90,false);
        angle(body.bodyYaw(TICK),22.5f,"eleventh stable tick begins linear alignment");
        for(int i=0;i<9;i++)body.tick(0,0,0,90,false);
        angle(body.bodyYaw(TICK),90,"twentieth stable tick completes alignment");
        body.tick(0,0,0,-90,false);
        angle(body.bodyYaw(TICK),-15,"new head direction resets idle delay and clamps body");

        body.reset(0,0,0,0,0);
        for(int i=0;i<25;i++)body.tick(0,0,0,120,true);
        angle(body.bodyYaw(TICK),0,"stationary body does not follow while carrying a mob passenger");
        body.tick(1,0,45,120,true);
        angle(body.bodyYaw(TICK),45,"mob passenger does not disable moving branch");
        angle(ChickenBodyRotation.limit(179,-179,75),179,"head/body clamp wraps across 180 degrees");
        angle(ChickenBodyRotation.interpolate(179,-179,.5f),180,"interpolation takes shortest wrap path");
        check(Float.isNaN(new EntityTracker.Renderable().bodyYaw),"direct snapshot callers retain yaw fallback");
    }

    private static void independentPacketClocks() throws Exception {
        EntityTracker tracker=new EntityTracker(2);
        // Players retain raw packet head interpolation; mobs now additionally use body control.
        tracker.addEntity(spawn(1,122,0,0));
        tracker.moveEntity(move(1,4096,0,0),false);
        long[] movementClock=longs(tracker,"updateNanos");
        long[] headClock=longs(tracker,"headUpdateNanos");
        long movementStart=movementClock[0];
        double movementFrom=doubles(tracker,"previousXs")[0];
        tracker.rotateHead(head(1,64));
        check(movementClock[0]==movementStart,"head packet must not restart movement clock");
        near(doubles(tracker,"previousXs")[0],movementFrom,"head packet must not recapture movement origin");
        long headStart=headClock[0];
        float headFrom=floats(tracker,"previousHeadYaws")[0];
        tracker.rotateEntity(rotation(1,32));
        check(headClock[0]==headStart,"body rotation packet must not restart head clock");
        near(floats(tracker,"previousHeadYaws")[0],headFrom,"body packet must not recapture head origin");
        tracker.moveEntity(move(1,1024,0,0),false);
        check(headClock[0]==headStart,"movement packet must not restart head clock");

        // Fixed synthetic times avoid sleeps and expose distinct interpolation fractions.
        long now=2_000_000_000L;
        movementClock[0]=now-25_000_000L;
        headClock[0]=now-75_000_000L;
        doubles(tracker,"previousXs")[0]=0; doubles(tracker,"xs")[0]=4;
        floats(tracker,"previousYaws")[0]=0; floats(tracker,"yaws")[0]=80;
        floats(tracker,"previousHeadYaws")[0]=0; floats(tracker,"headYaws")[0]=120;
        EntityTracker.Renderable[] out={new EntityTracker.Renderable()};
        check(tracker.snapshotVisible(out,now,0,64,0,100,-1)==1,"snapshot found player");
        near(out[0].x,1,"position uses movement clock quarter-progress");
        angle(out[0].yaw,20,"entity yaw uses movement clock");
        angle(out[0].headYaw,90,"head uses separate three-quarter progress");
        angle(out[0].bodyYaw,20,"player retains network body yaw presentation");
    }

    private static void trackerPresentation() throws Exception {
        EntityTracker tracker=new EntityTracker(1);
        tracker.addEntity(spawn(1,15,0,64));
        ChickenBodyRotation body=((ChickenBodyRotation[])field(tracker,"chickenBodyRotations"))[0];
        long initial=body.tickNanos;
        EntityTracker.Renderable[] out={new EntityTracker.Renderable()};
        tracker.snapshotVisible(out,initial+25*TICK,0,64,0,100,-1);
        // The catch-up bound is twenty ticks: one initial head change plus nineteen stable ticks.
        tracker.snapshotVisible(out,initial+27*TICK,0,64,0,100,-1);
        angle(out[0].yaw,0,"stationary body control preserves packet/entity yaw");
        angle(out[0].bodyYaw,90,"chicken render body follows stable head after bounded catch-up");
        angle(floats(tracker,"yaws")[0],0,"visual controller cannot mutate network body yaw");
        angle(floats(tracker,"headYaws")[0],90,"visual controller cannot mutate network head target");
        near(doubles(tracker,"xs")[0],0,"visual controller cannot mutate position");

        tracker.addEntity(spawn(2,15,32,32));
        ChickenBodyRotation reused=((ChickenBodyRotation[])field(tracker,"chickenBodyRotations"))[0];
        check(reused==body,"bounded tracker slot reuses its controller");
        tracker.snapshotVisible(out,reused.tickNanos,0,64,0,100,-1);
        angle(out[0].bodyYaw,45,"slot replacement resets old visual body state");
    }

    private static Object field(EntityTracker tracker,String name)throws Exception {
        Field field=EntityTracker.class.getDeclaredField(name);field.setAccessible(true);return field.get(tracker);
    }
    private static long[] longs(EntityTracker t,String n)throws Exception{return(long[])field(t,n);}
    private static float[] floats(EntityTracker t,String n)throws Exception{return(float[])field(t,n);}
    private static double[] doubles(EntityTracker t,String n)throws Exception{return(double[])field(t,n);}
    private static ByteBuffer spawn(int id,int type,int yaw,int head) {
        ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,id);BinaryCodec.writeUuid(b,new UUID(0,id));
        VarInts.write(b,type);b.putDouble(0).putDouble(64).putDouble(0).put((byte)0).put((byte)yaw)
                .put((byte)head).put((byte)0).putShort((short)0).putShort((short)0).putShort((short)0).flip();return b;
    }
    private static ByteBuffer move(int id,int x,int y,int z) {
        ByteBuffer b=ByteBuffer.allocate(16);VarInts.write(b,id);b.putShort((short)x).putShort((short)y).putShort((short)z).put((byte)1).flip();return b;
    }
    private static ByteBuffer head(int id,int yaw) {
        ByteBuffer b=ByteBuffer.allocate(8);VarInts.write(b,id);b.put((byte)yaw).flip();return b;
    }
    private static ByteBuffer rotation(int id,int yaw) {
        ByteBuffer b=ByteBuffer.allocate(8);VarInts.write(b,id);b.put((byte)yaw).put((byte)0).put((byte)1).flip();return b;
    }
    private static void angle(float actual,float expected,String message) {
        near(ChickenBodyRotation.wrap(actual-expected),0,message);
    }
    private static void near(double actual,double expected,String message) {
        check(Math.abs(actual-expected)<1e-5,message+": "+actual+" != "+expected);
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
