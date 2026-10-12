package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;
import com.newhorizon.thinclient.world.LocalPlayerVisual;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;

public final class PlayerAttackSelfTest {
    public static void run() throws Exception {
        EntityTracker tracker=new EntityTracker(1);
        ByteBuffer spawn=ByteBuffer.allocate(64);VarInts.write(spawn,17);
        spawn.putLong(1).putLong(2).putDouble(0).putDouble(0).putDouble(0).put((byte)0).put((byte)0).flip();
        tracker.addPlayer(spawn);
        EntityTracker.Renderable[] out={new EntityTracker.Renderable()};long start=System.nanoTime();
        tracker.animate(17,0,start);
        tracker.animate(17,0,start+50_000_000L);
        tracker.snapshotVisible(out,start+100_000_000L,0,0,0,100,-1);
        near(out[0].swingProgress,1/3f,"duplicate attack does not restart first half");
        check(PlayerAttackPose.rightArm(out[0]),"default main hand is right");
        checkPose(out[0]);
        tracker.snapshotVisible(out,start+300_000_000L,0,0,0,100,-1);
        near(out[0].swingProgress,0,"completed stroke returns to rest");
        tracker.animate(17,3,start+400_000_000L);
        tracker.snapshotVisible(out,start+500_000_000L,0,0,0,100,-1);
        check(!PlayerAttackPose.rightArm(out[0]),"offhand event selects left arm");checkPose(out[0]);
        out[0].appearance[18]=0;
        check(PlayerAttackPose.rightArm(out[0]),"left-handed offhand selects right arm");
        out[0].offhandSwing=false;check(!PlayerAttackPose.rightArm(out[0]),"left-handed main hand");checkPose(out[0]);
        LocalPlayerVisual local=new LocalPlayerVisual();local.model.swingProgress=.12f;
        local.update(start,0,0,0,0,0);near(local.model.swingProgress,.12f,"local third person retains attack");
        check(PlayerAttackPose.rightArm(local.model),"local main hand");checkPose(local.model);
        local.model.swimAmount=1;local.model.swimming=true;checkPose(local.model);
        System.out.println("Player attack tests passed: network/local poses, repeated packets, completion, main/off hand, left-handed skins, sleeves and swimming");
    }
    private static void checkPose(EntityTracker.Renderable e) {
        e.swingProgress=.12f;
        EntityModelAnimation a=new EntityModelAnimation();VanillaEntityModels.Model model=VanillaEntityModels.model(e);
        a.prepare(model,e);float[] arm=new float[16],sleeve=new float[16],body=new float[16];
        String side=PlayerAttackPose.rightArm(e)?"right":"left";
        check(a.joint("/"+side+"_arm",arm),"arm joint");check(a.joint("/"+side+"_sleeve",sleeve),"sleeve joint");
        for(int i=0;i<16;i++)near(arm[i],sleeve[i],"clothing follows arm");
        check(a.joint("/body",body),"body joint");check(Math.abs(body[2])>.1f,"vanilla torso twist");
        check(Math.abs(arm[6])>.65f,"attack arm reaches forward even without walking");
        float[] attacking=arm.clone();e.swingProgress=0;a.prepare(model,e);a.joint("/"+side+"_arm",arm);
        float delta=0;for(int i=0;i<16;i++)delta+=Math.abs(arm[i]-attacking[i]);check(delta>1,"attack differs visibly from idle");
    }
    private static void near(float a,float b,String msg){check(Math.abs(a-b)<.0002f,msg+": "+a+" != "+b);}
    private static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
}