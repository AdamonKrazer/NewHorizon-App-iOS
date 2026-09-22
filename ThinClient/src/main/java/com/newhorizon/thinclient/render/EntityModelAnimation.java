package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;

/** CPU joint matrices for the existing instanced cuboids; no per-frame allocations. */
final class EntityModelAnimation {
    private static final float PI=(float)Math.PI,DEG=PI/180;
    private final float[] matrices=new float[80*12],local=new float[12],pose=new float[6];
    private VanillaEntityRig rig;

    void prepare(VanillaEntityModels.Model model,EntityTracker.Renderable e) {
        rig=model.rig;
        for(int joint=0;joint<rig.paths.length;joint++) {
            System.arraycopy(rig.poses,joint*6,pose,0,6);
            if(e!=null)animate(rig.paths[joint],rig.names[joint],e,pose);
            if(e!=null&&(e.player||e.type==122)&&e.swimAmount>0)swimPart(rig.names[joint],e,pose);
            matrix(pose,local);
            int parent=rig.parents[joint],to=joint*12;
            if(parent<0)System.arraycopy(local,0,matrices,to,12);
            else for(int row=0;row<3;row++) {
                int p=parent*12+row*4;
                for(int col=0;col<4;col++)matrices[to+row*4+col]=
                    matrices[p]*local[col]+matrices[p+1]*local[4+col]+matrices[p+2]*local[8+col]+(col==3?matrices[p+3]:0);
            }
        }
    }
    void box(int part,float[] center,float[] basis) {
        int j=rig.boxJoints[part]*12,b=part*3;
        for(int row=0;row<3;row++) {
            int p=j+row*4;
            center[row]=matrices[p]*rig.centers[b]+matrices[p+1]*rig.centers[b+1]+matrices[p+2]*rig.centers[b+2]+matrices[p+3];
            for(int col=0;col<3;col++)basis[row*3+col]=matrices[p+col];
        }
    }
    boolean joint(String path,float[] out) {
        for(int j=0;j<rig.paths.length;j++)if(rig.paths[j].equals(path)) {
            for(int row=0;row<3;row++){for(int col=0;col<3;col++)out[col*4+row]=matrices[j*12+row*4+col]*(col==2?1:-1);out[12+row]=matrices[j*12+row*4+3]/16;}
            out[3]=out[7]=out[11]=0;out[15]=1;return true;
        }return false;
    }
    private static void matrix(float[] p,float[] m) {
        // S * Rz * Ry * Rx * S, S=(-1,-1,+1), matching the validated cube axes.
        float cx=cos(-p[3]),sx=sin(-p[3]),cy=cos(-p[4]),sy=sin(-p[4]),cz=cos(p[5]),sz=sin(p[5]);
        m[0]=cz*cy;m[1]=cz*sy*sx-sz*cx;m[2]=cz*sy*cx+sz*sx;m[3]=-p[0];
        m[4]=sz*cy;m[5]=sz*sy*sx+cz*cx;m[6]=sz*sy*cx-cz*sx;m[7]=-p[1];
        m[8]=-sy;m[9]=cy*sx;m[10]=cy*cx;m[11]=p[2];
    }
    static void squidBody(EntityTracker.Renderable e,float[] c,float[] b) {
        // SquidRenderer: T(0,.5,0), body yaw, Rx(xBodyRot), Ry(zBodyRot),
        // T(0,-1.2,0), then LivingEntityRenderer's model baseline and axis conversion.
        float cy=cos(-e.squidRoll*DEG),sy=sin(-e.squidRoll*DEG),cx=cos(e.squidPitch*DEG),sx=sin(e.squidPitch*DEG);
        float x=cy*c[0]+sy*c[2],y=c[1]+4.816f,z=-sy*c[0]+cy*c[2];
        c[0]=x;c[1]=cx*y-sx*z+8-24;c[2]=sx*y+cx*z;
        for(int col=0;col<3;col++) {
            x=cy*b[col]+sy*b[6+col];y=b[3+col];z=-sy*b[col]+cy*b[6+col];
            b[col]=x;b[3+col]=cx*y-sx*z;b[6+col]=sx*y+cx*z;
        }
    }
    private static void animate(String path,String part,EntityTracker.Renderable e,float[] p) {
        if(e.player||e.type==122){
            if(part.endsWith("sleeve"))part=part.replace("sleeve","arm");
            else if(part.endsWith("pants"))part=part.replace("pants","leg");
            else if(part.equals("jacket"))part="body";
            if(e.sneaking){
                if(part.equals("body"))p[3]=.5f;
                if(part.endsWith("arm")){p[1]=5.2f;p[3]+=.4f;}
                if(part.endsWith("leg")){p[1]=12.2f;p[2]=4;}
                if(part.equals("head")||part.equals("hat"))p[1]=4.2f;
            }
        }
        float age=e.age,walk=e.walkPosition*.6662f,amount=e.walkSpeed;
        float body=Float.isFinite(e.bodyYaw)?e.bodyYaw:e.yaw;
        float head=wrap(e.headYaw-body)*DEG,pitch=e.pitch*DEG;
        boolean right=part.startsWith("right"),left=part.startsWith("left");
        if(e.type==44||e.type==96) {if(part.startsWith("part"))p[3]=e.squidTentacle;return;}
        if(e.type==2)return; // Armor-stand poses are metadata, not a walking creature.
        boolean fish=e.type==16||e.type==20||e.type==78||e.type==81||e.type==99||e.type==105;
        boolean horse=e.type==49||e.type==21||e.type==66||e.type==87||e.type==119;
        boolean headPart=part.equals("head")||path.equals("/hat")||horse&&part.equals("head_parts");
        if(horse&&part.equals("head"))headPart=false;
        // Chicken's validated shared neck transform remains in ChickenModelPose.
        if(headPart&&!fish&&e.type!=15) {p[3]+=pitch;p[4]+=head;}
        if(e.type==79) {
            if(part.equals("nose")||part.endsWith("ear")){p[3]=pitch;p[4]+=head;}
            float jump=sin(e.jumpProgress*PI);
            if(part.endsWith("haunch"))p[3]=(jump*50-21)*DEG;
            if(part.endsWith("hind_foot"))p[3]=jump*50*DEG;
            if(part.endsWith("front_leg"))p[3]=(jump*-40-11)*DEG;
            return;
        }
        if(e.type==12||e.type==95) {spider(part,walk,amount,p);return;}
        if(e.type==5) {bat(path,e,p,head,pitch);return;}
        if(e.type==6) {
            if(part.endsWith("wing")){p[3]=p[4]=0;p[5]=(right?1:-1)*cos(age*2.1f)*PI*.15f;}
            if(part.endsWith("legs"))p[3]=PI/4;
            return;
        }
        if(e.type==71) {
            float flap=cos((e.entityId*3+age)*7.448451f*DEG)*16*DEG;
            if(part.contains("wing"))p[5]=(left?1:-1)*flap;
            if(part.startsWith("tail"))p[3]=-5*DEG+flap;
            return;
        }
        if(e.type==0||e.type==107) {
            if(part.endsWith("wing"))p[4]=(right?1:-1)*(.47f+cos(age*(e.type==107?1.22173f:.69813f))*PI*.25f);
            if(part.endsWith("arm"))p[3]+=-.2f+cos(walk+(right?PI:0))*amount*.5f;
            return;
        }
        if(e.type==70) {parrot(part,e,p);return;}
        if(e.type==41) {if(part.startsWith("part"))p[3]=.2f*sin(age*.3f+index(part,4))+.4f;return;}
        if(e.type==7) {
            if(part.startsWith("part")) {
                int i=index(part,4),ring=i/4;float a=age*PI*(ring==0?-.03f:ring==1?.03f:-.05f)+(i%4);
                p[0]=cos(a)*(ring==0?9:ring==1?7:5);p[2]=sin(a)*(ring==0?9:ring==1?7:5);
                p[1]=(ring==0?-2:ring==1?2:11)+cos((i*(ring==0?2:1)+age)*(ring==0?.25f:ring==1?.25f:.5f));
            }return;
        }
        if(e.type==30||e.type==85) {
            if(part.startsWith(e.type==30?"part":"segment")) {
                int i=index(part,e.type==30?4:7);p[4]=cos(age*.9f+i*.15f*PI)*PI*(e.type==30?.01f:.05f)*(1+Math.abs(i-2));
                p[0]=sin(age*.9f+i*.15f*PI)*PI*(e.type==30?.1f:.2f)*Math.abs(i-2);
            }return;
        }
        if(fish) {
            float swim=e.inWater?1:1.5f;
            if(e.type==20) {
                if(part.equals("body")){p[3]=pitch+(amount>1e-5?-.05f-.05f*cos(age*.3f):0);p[4]=0;}
                if(part.equals("tail"))p[3]=amount>1e-5?-.1f*cos(age*.3f):0;
                if(part.equals("tail_fin"))p[3]=amount>1e-5?-.2f*cos(age*.3f):0;
            } else if(e.type==78) {
                if(part.equals("right_fin")||part.equals("right_blue_fin"))p[5]=-.2f+.4f*sin(age*.2f);
                if(part.equals("left_fin")||part.equals("left_blue_fin"))p[5]=.2f-.4f*sin(age*.2f);
            } else if(part.equals("tail")||part.equals("tail_fin")||part.equals("body_back")) {
                p[4]=-(e.type==16?.45f:e.type==99?.25f:.45f)*swim*sin(age*(e.type==99?.3f:.6f)*(e.type==81&&!e.inWater?1.3f:1));
            }
            return;
        }
        if(e.type==4&&e.inWater) {
            float swim=sin(age*.33f);
            if(part.equals("body")){p[3]=pitch+.13f*swim;}
            if(part.equals("tail"))p[4]=.3f*swim;
            if(part.endsWith("leg")){p[3]=1.8849558f;p[4]=(left?-1:1)*PI/2;}
            return;
        }
        boolean leg=part.endsWith("leg")||part.endsWith("pants");
        boolean arm=part.endsWith("arm")||part.endsWith("sleeve");
        if((left||right)&&(leg||arm)) {
            boolean front=part.contains("front"),hind=part.contains("hind")||part.contains("mid");
            float offset=arm?(right?PI:0):front?(right?PI:0):hind?(right?0:PI):(right?0:PI);
            float amplitude=arm?1:1.4f;
            if(e.type==29)amplitude*=.5f;
            if(e.type==108||e.type==110||e.type==112)amplitude=.7f;
            p[3]+=cos(walk+offset)*amplitude*amount;
            if(e.type==53)p[3]=(right?1:-1)*(arm?1.5f:-1.5f)*triangle(e.walkPosition,13)*amount;
            if(e.type==106) {
                p[3]=e.inWater?cos(walk*.6662f+(right?0:PI))*.5f*amount:0;
                if(!e.inWater)p[4]=cos(walk*5+(right?0:PI))*(front?8:3)*amount;
                if(front&&e.inWater){p[3]=0;p[5]=cos(walk*.6662f+(right?0:PI))*.5f*amount;}
            }
            if(e.riding) {
                if(leg){p[3]=-1.4137167f;p[4]=(right?1:-1)*PI/10;p[5]=(right?1:-1)*.07853982f;}
                if(arm)p[3]-=PI/5;
            }
            if(arm) {
                if((e.type==86||e.type==97||e.type==114)&&(e.appearance[15]&4)!=0&&e.hasBow()) {
                    boolean bowLeft=e.equipment[0]==com.newhorizon.thinclient.world.EntityEquipment.BOW?(e.appearance[15]&2)!=0:(e.appearance[15]&2)==0;
                    p[3]=-PI/2+pitch;p[4]=head+(right?-.1f:.1f)+(left!=bowLeft?(right?-.4f:.4f):0);p[5]=0;
                }
                if(e.type==23||e.type==50||e.type==118||e.type==120) {
                    float swing=e.swingProgress,hit=sin(swing*PI),returning=sin((1-(1-swing)*(1-swing))*PI);
                    p[3]=-PI/((e.appearance[15]&4)!=0?1.5f:2.25f)+hit*1.2f-returning*.4f;
                    p[4]=(right?-1:1)*(.1f-hit*.6f);p[5]=0;
                }
                p[5]+=(right?1:-1)*(cos(age*.09f)*.05f+.05f);
                p[3]+=(right?1:-1)*sin(age*.067f)*.05f;
                if(e.type!=23&&e.type!=50&&e.type!=118&&e.type!=120&&e.swingProgress>0&&e.swingProgress<1&&right!=e.offhandSwing)p[3]-=sin((1-(float)Math.pow(1-e.swingProgress,4))*PI)*1.2f;
            }
        }
        if(e.type==15&&part.endsWith("wing"))p[5]=e.onGround?0:(right?1:-1)*(1+sin(age*2));
        if((e.type==11||e.type==67)&&part.equals("tail2"))p[3]=1.7278761f+PI/4*cos(e.walkPosition)*amount;
        if(e.type==116&&part.equals("tail"))p[4]=cos(walk)*1.4f*amount;
    }
    private static void parrot(String part,EntityTracker.Renderable e,float[] p) {
        // ParrotModel.prepare sets these rotations every frame, outside createBodyLayer.
        boolean sitting=(e.appearance[17]&1)!=0,flying=!e.onGround&&!sitting;
        float flap=flying?(1+sin(e.age*1.2f)):0,bob=flap*.3f;
        if(part.equals("feather"))p[3]=-.2214f;
        if(part.equals("body")){p[3]=.4937f;p[1]=sitting?18.4f:16.5f+bob;}
        if(part.equals("head"))p[1]=sitting?17.59f:15.69f+bob;
        if(part.equals("tail")){p[3]=sitting?1.5388988f:1.015f+cos(e.walkPosition*.6662f)*.3f*e.walkSpeed;p[1]=sitting?22.97f:21.07f+bob;}
        if(part.endsWith("wing")){p[3]=-.6981f;p[4]=-PI;p[5]=(part.startsWith("left")?-1:1)*(.0873f+flap);p[1]=sitting?18.84f:16.94f+bob;}
        if(part.endsWith("leg")){p[3]=-.0299f+(sitting?PI/2:flying?.6981317f:cos(e.walkPosition*.6662f+(part.startsWith("right")?PI:0))*1.4f*e.walkSpeed);p[1]=sitting?23.9f:22+bob;}
    }
    /** HumanoidModel's 26-stride swimming cycle, shared by skin overlay parts. */
    private static void swimPart(String part,EntityTracker.Renderable e,float[] p) {
        float blend=e.swimAmount;
        if(part.equals("head")||part.equals("hat")){
            p[3]+=wrapRadians((e.swimming?-PI/4:e.pitch*DEG)-p[3])*blend;return;
        }
        boolean right=part.startsWith("right"),left=part.startsWith("left");
        if(!right&&!left)return;
        if(part.endsWith("leg")||part.endsWith("pants")){
            p[3]+=(.3f*cos(e.walkPosition/3+(left?PI:0))-p[3])*blend;return;
        }
        if(!part.endsWith("arm")&&!part.endsWith("sleeve"))return;
        if(e.blocking || e.swingProgress>0&&e.swingProgress<1&&right!=e.offhandSwing)return;
        float phase=e.walkPosition%26,x,z;
        if(phase<14){x=0;float curve=(-65*phase+phase*phase)/(-65*14f+14*14f);z=PI+(left?1:-1)*1.8707964f*curve;}
        else if(phase<22){float t=(phase-14)/8;x=PI/2*t;z=left?5.012389f-1.8707964f*t:1.2707963f+1.8707964f*t;}
        else {x=PI/2*(1-(phase-22)/4);z=PI;}
        p[3]+=(left?wrapRadians(x-p[3]):x-p[3])*blend;
        p[4]+=(left?wrapRadians(PI-p[4]):PI-p[4])*blend;
        p[5]+=(left?wrapRadians(z-p[5]):z-p[5])*blend;
    }
    private static float wrapRadians(float a){a%=(2*PI);if(a>=PI)a-=2*PI;if(a< -PI)a+=2*PI;return a;}

    /** PlayerRenderer rotation followed by its (0,-1,.3) swim translation. */
    static void swimmingBody(EntityTracker.Renderable e,float[] c,float[] b,float unit) {
        float angle=(-90-(e.inWater?e.pitch:0))*DEG*e.swimAmount,cs=cos(angle),sn=sin(angle);
        float y=(24+c[1])*unit-(e.prone?1:0),z=c[2]*unit+(e.prone?.3f:0);
        c[1]=(cs*y-sn*z)/unit-24;c[2]=(sn*y+cs*z)/unit;
        for(int col=0;col<3;col++){y=b[3+col];z=b[6+col];b[3+col]=cs*y-sn*z;b[6+col]=sn*y+cs*z;}
    }
    private static void spider(String part,float walk,float amount,float[] p) {
        if(!part.endsWith("leg"))return;
        boolean right=part.startsWith("right");int pair=part.contains("middle_hind")?1:part.contains("middle_front")?2:part.contains("front")?3:0;
        float offset=pair==0?0:pair==1?PI:pair==2?PI/2:PI*1.5f;
        p[4]=(right?1:-1)*(pair==0?PI/4:pair==1?PI/8:pair==2?-PI/8:-PI/4)
                +(right?-1:1)*cos(walk*2+offset)*.4f*amount;
        p[5]=(right?-1:1)*(pair==0||pair==3?PI/4:.58119464f)
                +(right?1:-1)*Math.abs(sin(walk+offset)*.4f)*amount;
    }
    private static void bat(String path,EntityTracker.Renderable e,float[] p,float head,float pitch) {
        boolean resting=(e.appearance[16]&1)!=0,right=path.contains("right"),tip=path.endsWith("wing_tip");
        if(path.equals("/head")){p[3]=pitch;p[4]=resting?PI-head:head;p[5]=resting?PI:0;p[1]=resting?-2:0;}
        if(path.equals("/body"))p[3]=resting?PI:PI/4+cos(e.age*.1f)*.15f;
        if(path.contains("wing")) {
            p[4]=(right?1:-1)*(resting?(tip?-1.7278761f:-1.2566371f):cos(e.age*1.3f)*PI*.25f*(tip?.5f:1));
            if(!tip){p[0]=resting?(right?-3:3):0;p[2]=resting?3:0;p[3]=resting?-.15707964f:0;}
        }
    }
    private static int index(String name,int start){int value=0;for(int i=start;i<name.length();i++)value=value*10+name.charAt(i)-'0';return value;}
    private static float triangle(float value,float period){return (Math.abs(value%period-period*.5f)-period*.25f)/(period*.25f);}
    private static float sin(float x){return (float)Math.sin(x);}
    private static float cos(float x){return (float)Math.cos(x);}
    private static float wrap(float x){x%=360;if(x>=180)x-=360;if(x< -180)x+=360;return x;}
}
