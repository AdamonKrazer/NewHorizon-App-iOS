package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;

public final class EntityModelAnimationSelfTest {
    public static void run() {
        EntityModelAnimation animator=new EntityModelAnimation();float[] c=new float[3],b=new float[9];int boxes=0;
        // Independently baked flat transforms must match reconstruction of every original joint.
        for(VanillaEntityModels.Model model:VanillaEntityModels.MODELS) {
            animator.prepare(model,null);
            for(int i=0;i<model.paths.length;i++) {
                animator.box(i,c,b);int p=i*VanillaEntityModels.STRIDE;
                for(int k=0;k<3;k++)near(c[k],model.boxes[p+k],"rest joint center "+model.name+model.paths[i]);
                for(int k=0;k<9;k++)near(b[k],model.boxes[p+6+k],"rest joint orientation");boxes++;
            }
        }
        EntityTracker.Renderable e=new EntityTracker.Renderable();e.type=18;e.bodyYaw=0;e.headYaw=65;e.pitch=30;
        VanillaEntityModels.Model cow=VanillaEntityModels.model(e);animator.prepare(cow,e);
        int head=part(cow,"/head");animator.box(head,c,b);
        // Head cube origin must still map to its fixed neck joint after both rotations.
        int bone=cow.rig.boxJoints[head],offset=head*3;
        for(int row=0;row<3;row++) {
            float pivot=c[row];for(int col=0;col<3;col++)pivot-=b[row*3+col]*cow.rig.centers[offset+col];
            near(pivot,cow.rig.poses[bone*6+row]*(row==2?1:-1),"cow neck pivot stays attached");
        }
        e.headYaw=e.pitch=0;e.walkSpeed=.8f;
        animator.prepare(cow,e);float[] legA=new float[9],legB=new float[9],legC=new float[9];
        animator.box(part(cow,"/right_hind_leg"),c,legA);animator.box(part(cow,"/left_front_leg"),c,legB);animator.box(part(cow,"/right_front_leg"),c,legC);
        for(int k=0;k<9;k++)near(legA[k],legB[k],"diagonal legs move together");
        near(legA[5],-legC[5],"opposite legs alternate");
        e.type=96;e.squidTentacle=.6f;e.squidPitch=-90;e.squidRoll=25;
        VanillaEntityModels.Model squid=VanillaEntityModels.model(e);animator.prepare(squid,e);
        for(int i=0;i<squid.paths.length;i++) {
            animator.box(i,c,b);EntityModelAnimation.squidBody(e,c,b);
            for(int row=0;row<3;row++){float length=0;for(int col=0;col<3;col++)length+=b[row*3+col]*b[row*3+col];near(length,1,"squid rotation cannot stretch tentacles");}
        }
        // Animated child cubes and overlays must never introduce NaNs at extreme headings.
        for(int type=0;type<124;type++) {
            e.type=type;e.age=33.25f;e.walkPosition=6;e.walkSpeed=.65f;e.headYaw=359;e.bodyYaw=-179;e.pitch=70;e.inWater=true;
            VanillaEntityModels.Model model=VanillaEntityModels.model(e);if(model==null)continue;
            animator.prepare(model,e);
            for(int i=0;i<model.paths.length;i++){animator.box(i,c,b);for(float v:c)check(Float.isFinite(v),"finite animated center");for(float v:b)check(Float.isFinite(v),"finite animated basis");}
        }
        System.out.println("Entity rig tests passed: "+VanillaEntityModels.MODELS.length+" native hierarchies, "+boxes+" unchanged rest cuboids, neck/diagonal gait/squid rigid transforms and all registry poses");
    }
    private static int part(VanillaEntityModels.Model m,String path){for(int i=0;i<m.paths.length;i++)if(m.paths[i].equals(path))return i;throw new AssertionError(path);}
    private static void near(float a,float b,String label){check(Math.abs(a-b)<.0002f,label+": "+a+" != "+b);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
