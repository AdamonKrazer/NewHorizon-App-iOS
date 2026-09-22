package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ItemInHandLayer and original thirdperson transforms, attached to the animated arm joint. */
final class EquipmentPose {
    private static final Map<String,float[]> TRANSFORMS=load();
    private final EntityModelAnimation animation=new EntityModelAnimation();
    final float[] matrix=new float[16];
    boolean prepare(EntityTracker.Renderable e,int slot,String material){
        VanillaEntityModels.Model model=VanillaEntityModels.model(e);if(model==null)return false;
        boolean leftMain=!e.player&&(e.appearance[15]&2)!=0;
        if(e.player)leftMain=e.appearance[18]==0;
        boolean left=slot==0?leftMain:!leftMain;
        animation.prepare(model,e);if(!animation.joint(left?"/left_arm":"/right_arm",matrix))return false;
        // SkeletonModel.translateToHand offsets its narrower two-pixel arms by one pixel.
        if(e.type==86||e.type==97||e.type==114)matrix[12]+=(left?1:-1)/16f;
        rotate(0,-90);rotate(1,180);translate((left?-1:1)/16f,.125f,-.625f);
        float[] t=TRANSFORMS.get(material);if(t!=null){int p=left?9:0;
            translate((left?-1:1)*t[p+3]/16,t[p+4]/16,t[p+5]/16);
            rotate(0,t[p]);rotate(1,t[p+1]*(left?-1:1));rotate(2,t[p+2]*(left?-1:1));
            for(int col=0;col<3;col++)for(int row=0;row<3;row++)matrix[col*4+row]*=t[p+6+col];
        }
        float scale=e.player?.9375f:e.type==114?1.2f:e.type==42?6:1;if(e.baby)scale*=.5f;
        matrix[13]+=1.5f;
        float yaw=(Float.isFinite(e.bodyYaw)?e.bodyYaw:e.yaw)+180;
        float c=(float)Math.cos(Math.toRadians(yaw)),s=(float)Math.sin(Math.toRadians(yaw));
        for(int col=0;col<4;col++){int p=col*4;float x=matrix[p]*scale,z=matrix[p+2]*scale;matrix[p]=c*x-s*z;matrix[p+1]*=scale;matrix[p+2]=s*x+c*z;}
        matrix[12]+=e.x;matrix[13]+=e.y;matrix[14]+=e.z;return true;
    }
    private void translate(float x,float y,float z){for(int r=0;r<3;r++)matrix[12+r]+=matrix[r]*x+matrix[4+r]*y+matrix[8+r]*z;}
    private void rotate(int axis,float degrees){
        int a=(axis+1)%3*4,b=(axis+2)%3*4;float c=(float)Math.cos(Math.toRadians(degrees)),s=(float)Math.sin(Math.toRadians(degrees));
        for(int r=0;r<3;r++){float x=matrix[a+r],y=matrix[b+r];matrix[a+r]=c*x+s*y;matrix[b+r]=-s*x+c*y;}
    }
    private static Map<String,float[]> load(){
        Map<String,float[]> result=new HashMap<String,float[]>();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(EquipmentPose.class.getResourceAsStream("/assets/newhorizon/vanilla_thirdperson_items.tsv"),StandardCharsets.UTF_8))){
            String line;while((line=in.readLine())!=null){String[] f=line.split("\t"),v=f[1].split(",");float[] t=new float[18];for(int i=0;i<18;i++)t[i]=Float.parseFloat(v[i]);result.put(f[0],t);}
        }catch(IOException e){throw new ExceptionInInitializerError(e);}return result;
    }
}
