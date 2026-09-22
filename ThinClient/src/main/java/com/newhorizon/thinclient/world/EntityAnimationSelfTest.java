package com.newhorizon.thinclient.world;

import java.nio.ByteBuffer;
import com.newhorizon.thinclient.protocol.VarInts;

public final class EntityAnimationSelfTest {
    public static void run()throws Exception {
        EntityAnimation state=new EntityAnimation();EntityTracker.Renderable e=new EntityTracker.Renderable();
        state.reset(1,0,0,0,0,0);
        state.tick(.1,0,0,18,false,false,0,0,0);state.snapshot(e,EntityAnimation.TICK);
        near(e.walkSpeed,.16f,"vanilla walk smoothing");near(e.walkPosition,.16f,"walk phase integrates filtered speed");
        for(int i=0;i<30;i++)state.tick(.1,0,0,18,false,false,0,0,0);
        state.snapshot(e,EntityAnimation.TICK);check(e.walkSpeed<1e-6,"standing stops footsteps");
        state.tick(100,0,0,18,false,false,0,0,0);state.snapshot(e,EntityAnimation.TICK);check(e.walkSpeed<1e-6,"teleport does not stride");
        state.reset(7,0,0,0,0,0);e.type=96;
        float rate=.2f/(new java.util.Random(7).nextFloat()+1),phase=0,tilt=0,spin=0,roll=0;
        for(int i=0;i<60;i++) {
            state.tick(0,0,0,96,false,true,0,0,.1);state.snapshot(e,EntityAnimation.TICK);
            phase=Math.min((float)(Math.PI*2),phase+rate);
            float f=phase/(float)Math.PI,angle=phase<Math.PI?(float)Math.sin(f*f*Math.PI)*(float)Math.PI*.25f:0;
            if(phase<Math.PI){if(f>.75f)spin=1;else spin*=.8f;}else spin*=.99f;
            roll+=(float)Math.PI*spin*1.5f;tilt+=(-90-tilt)*.1f;
            near(e.squidTentacle,angle,"native squid pulse tick "+i);near(e.squidPitch,tilt,"squid horizontal swim tilt");near(e.squidRoll,roll,"squid axial roll");
        }
        near(e.squidTentacle,0,"client waits for server cycle event");
        state.resetSquidCycle();state.tick(0,0,0,96,false,true,0,0,.1);state.snapshot(e,EntityAnimation.TICK);
        check(e.squidTentacle>0,"cycle event restarts stroke");
        state.reset(7,0,0,0,0,0);state.snapshot(e,0);near(e.squidPitch,0,"slot reuse resets tilt");near(e.squidRoll,0,"slot reuse resets spin");
        for(int i=1;i<=20;i++)state.tick(i*.1,0,0,96,false,true,0,0,0);
        state.snapshot(e,EntityAnimation.TICK);check(e.squidPitch< -70,"relative movement supplies omitted squid velocity");
        state.jump();for(int i=0;i<4;i++)state.tick(0,0,0,79,false,false,0,0,0);state.snapshot(e,EntityAnimation.TICK);near(e.jumpProgress,.5f,"rabbit half jump");
        packetIntegration();
        System.out.println("Entity animation tests passed: walk/stop/teleport, squid 60 native ticks and cycle sync, slot reuse, rabbit and network velocity");
    }
    private static void packetIntegration()throws Exception {
        EntityTracker tracker=new EntityTracker(1);
        java.lang.reflect.Method put=EntityTracker.class.getDeclaredMethod("put",int.class,int.class,double.class,double.class,double.class,float.class,float.class,float.class,boolean.class);put.setAccessible(true);
        put.invoke(tracker,17,96,0d,0d,0d,0f,0f,0f,false);
        java.lang.reflect.Field field=EntityTracker.class.getDeclaredField("visualAnimations");field.setAccessible(true);
        EntityAnimation state=((EntityAnimation[])field.get(tracker))[0];long start=state.tickTime;
        ByteBuffer b=ByteBuffer.allocate(10);VarInts.write(b,17);b.putShort((short)800).putShort((short)0).putShort((short)0).flip();tracker.setMotion(b);
        EntityTracker.Renderable[] out={new EntityTracker.Renderable()};
        tracker.snapshotVisible(out,start+20*EntityAnimation.TICK,0,0,0,100,-1);
        check(out[0].squidPitch< -70,"non-projectile velocity drives swimming orientation");check(out[0].bodyYaw< -70,"velocity drives squid heading");
        near(out[0].x,0,"visual swim cannot move network position");
        tracker.status(17,19,start+20*EntityAnimation.TICK);
        tracker.snapshotVisible(out,start+22*EntityAnimation.TICK,0,0,0,100,-1);check(out[0].squidTentacle>0,"protocol status restarts squid stroke");
        put.invoke(tracker,18,18,0d,0d,0d,0f,0f,90f,false);
        java.lang.reflect.Field bodyField=EntityTracker.class.getDeclaredField("chickenBodyRotations");bodyField.setAccessible(true);
        ChickenBodyRotation body=((ChickenBodyRotation[])bodyField.get(tracker))[0];long bodyStart=body.tickNanos;
        tracker.snapshotVisible(out,bodyStart+20*EntityAnimation.TICK,0,0,0,100,-1);
        tracker.snapshotVisible(out,bodyStart+23*EntityAnimation.TICK,0,0,0,100,-1);
        near(out[0].bodyYaw,90,"cow body follows stable head");near(out[0].yaw,0,"body animation preserves server yaw");
    }
    private static void near(float a,float b,String label){check(Math.abs(a-b)<.0001f,label+": "+a+" != "+b);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
