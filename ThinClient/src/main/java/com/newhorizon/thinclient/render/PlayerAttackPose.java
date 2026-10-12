package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;

/** HumanoidModel.setupAttackAnimation (1.20.1), also copied to PlayerModel's clothing. */
final class PlayerAttackPose {
    static boolean rightArm(EntityTracker.Renderable e) {
        boolean leftMain=e.appearance[18]==0;
        return leftMain==e.offhandSwing;
    }
    static void apply(String part,EntityTracker.Renderable e,float[] pose) {
        float progress=e.swingProgress;
        if(!(progress>0 && progress<1))return;
        boolean attackRight=rightArm(e);
        float twist=(float)Math.sin(Math.sqrt(progress)*Math.PI*2)*.2f*(attackRight?1:-1);
        if(part.equals("body")||part.equals("jacket")){pose[4]=twist;return;}
        if(!part.endsWith("arm")&&!part.endsWith("sleeve"))return;
        boolean right=part.startsWith("right");
        pose[0]=(float)Math.cos(twist)*(right?-5:5);
        pose[2]=(float)Math.sin(twist)*(right?5:-5);
        pose[4]+=twist;
        if(!right)pose[3]+=twist;
        if(right!=attackRight)return;
        float eased=1-progress;eased=1-eased*eased*eased*eased;
        float hit=(float)Math.sin(eased*Math.PI);
        float follow=(float)Math.sin(progress*Math.PI)*-(e.pitch*(float)Math.PI/180-.7f)*.75f;
        pose[3]-=hit*1.2f+follow;
        pose[4]+=twist*2;
        pose[5]+=(float)Math.sin(progress*Math.PI)*-.4f;
    }
    private PlayerAttackPose() { }
}