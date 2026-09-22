package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;
import com.newhorizon.thinclient.world.*;
import com.newhorizon.thinclient.protocol.*;

public final class MobModelSelfTest {
    public static void run()throws Exception {
        EntityTracker tracker=new EntityTracker(1);tracker.addEntity(FishingLeadsSelfTest.spawn(1,86,0,0,0,0));
        ByteBuffer packet=ByteBuffer.allocate(64);VarInts.write(packet,1);packet.put((byte)128).put((byte)1);VarInts.write(packet,760);packet.put((byte)1).put((byte)0);packet.put((byte)1).put((byte)0);packet.flip();tracker.setEquipment(packet);
        EntityTracker.Renderable[] out={new EntityTracker.Renderable()};tracker.snapshotVisible(out,System.nanoTime(),0,0,0,20,-1);
        check(out[0].hasBow()&&out[0].equipment[1]==-1,"equipment continuation/empty offhand");
        tracker.setEquipment(ByteBuffer.wrap(new byte[]{1,1,1,5,1,0}));tracker.snapshotVisible(out,System.nanoTime(),0,0,0,20,-1);
        check(out[0].hasBow()&&out[0].equipment[1]==5,"partial equipment retains mainhand");
        tracker.setEquipment(ByteBuffer.wrap(new byte[]{1,0,0}));tracker.snapshotVisible(out,System.nanoTime(),0,0,0,20,-1);check(!out[0].hasBow(),"empty hand removes bow");
        tracker.clear();tracker.addEntity(FishingLeadsSelfTest.spawn(2,70,0,0,0,0));tracker.snapshotVisible(out,System.nanoTime(),0,0,0,20,-1);check(out[0].equipment[1]==-1,"reused entity slot resets equipment");
        EntityTracker.Renderable e=out[0];EntityModelAnimation a=new EntityModelAnimation();float[] c=new float[3],b=new float[9];
        VanillaEntityModels.Model parrot=VanillaEntityModels.model(e);a.prepare(parrot,e);
        a.box(part(parrot,"/body"),c,b);near(b[5],(float)Math.sin(.4937),"parrot body pitch from original prepare");
        a.box(part(parrot,"/left_wing"),c,b);check(b[0]<-.99,"parrot wing is turned to show the correct skin face");
        for(int variant=0;variant<5;variant++){e.appearance[19]=variant;check(EntitySkins.find(EntityAppearance.name(e))>=0,"all parrot variants");}
        e.appearance[17]=1;a.prepare(parrot,e);a.box(part(parrot,"/body"),c,b);check(c[1]<-20,"sitting parrot body lowered");
        e.type=86;e.equipment[0]=760;e.appearance[15]=4;e.appearance[17]=0;EquipmentPose pose=new EquipmentPose();check(pose.prepare(e,0,"bow"),"skeleton hand joint");
        for(float f:pose.matrix)check(Float.isFinite(f),"finite original bow transform");
        a.prepare(VanillaEntityModels.model(e),e);a.box(part(VanillaEntityModels.model(e),"/right_arm"),c,b);check(b[5]<-.9,"skeleton aims bow with raised arm");
        ByteBuffer mesh=ByteBuffer.allocate(48*16);
        for(int state=7419;state<=7430;state++){
            mesh.clear();check(CocoaModel.append(mesh,state,0,0,0)==48,"cocoa body and two stem faces");
            float minY=2,maxY=-1;
            for(int p=0;p<mesh.position();p+=16){float y=mesh.getFloat(p+4);minY=Math.min(y,minY);maxY=Math.max(y,maxY);
                int token=(mesh.get(p+12)&255)|((mesh.get(p+13)&255)<<8)|((mesh.get(p+14)&255)<<16)|((mesh.get(p+15)&255)<<24);
                check(((token>>>20)&7)==7&&(token&262144)==0,"explicit model UV cannot become water");check(((token>>>12)&31)<=16&&((token>>>23)&31)<=16,"cocoa atlas UV bounds");
            }
            near(minY,(7-(state-7419)/4*2)/16f,"cocoa age geometry");near(maxY,1,"cocoa attached stem reaches trunk");
        }
        System.out.println("Mob model tests passed: partial equipment/removal/reuse, five parrots, vanilla resting/sitting/aiming poses and 12 cocoa states");
    }
    private static int part(VanillaEntityModels.Model m,String path){for(int i=0;i<m.paths.length;i++)if(m.paths[i].equals(path))return i;throw new AssertionError(path);}
    private static void near(float a,float b,String m){check(Math.abs(a-b)<.001,m+": "+a+" != "+b);}
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
