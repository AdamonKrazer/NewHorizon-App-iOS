package com.newhorizon.thinclient.render;

/** Regression references transcribed from the official ChickenModel constructor/setupAnim. */
public final class ChickenPoseSelfTest {
    public static void run() {
        // Original pivot (0,15,-4), transformed only by the model-space X/Y flip.
        float[] pivot={0,-15,-4};
        float[][] centers={{0,-12,-4.5f},{0,-12,-7},{0,-14,-6}};
        String[] parts={"/head","/beak","/red_thing"};
        for(float yaw:new float[]{-180,-135,-90,-30,0,30,90,135,180})for(float pitch:new float[]{-90,-45,0,45,90}) {
            float[] fixed=pivot.clone();float[] fixedBasis=identity();
            ChickenModelPose.apply("/head",yaw,pitch,fixed,fixedBasis);
            vector(fixed,pivot,"neck pivot does not orbit the entity");
            float[][] moved=new float[3][],basis=new float[3][];
            for(int p=0;p<3;p++) {
                moved[p]=centers[p].clone();basis[p]=identity();
                ChickenModelPose.apply(parts[p],yaw,pitch,moved[p],basis[p]);
                vector(basis[p],fixedBasis,"head, beak and wattle share a rotation");
            }
            // The head front plane and beak back plane meet at native (0,-3,-2).
            vector(point(moved[0],basis[0],0,0,-1.5f),point(moved[1],basis[1],0,0,1),"beak remains attached to head");
            // Native (0,-2,-2.5) lies on both beak bottom and red_thing top.
            vector(point(moved[1],basis[1],0,-1,.5f),point(moved[2],basis[2],0,1,-.5f),"wattle remains attached to beak");
            near(distance(moved[0],moved[1]),2.5f,"head/beak spacing");
            near(distance(moved[0],moved[2]),2.5f,"head/wattle spacing");
            for(String path:new String[]{"/body","/right_leg","/left_leg","/right_wing","/left_wing"}) {
                float[] center={1.5f,-21.5f,-.5f},original=center.clone(),bodyBasis=identity();
                ChickenModelPose.apply(path,yaw,pitch,center,bodyBasis);
                vector(center,original,"body and legs do not inherit head look");
                vector(bodyBasis,identity(),"body and leg orientation remains unchanged");
            }
        }
        // Exact quarter turns independently evaluated in native model space:
        // Ry(+90): (x,y,z)->(z,y,-x); Rx(+90): (x,y,z)->(x,-z,y).
        pose("/head",centers[0],90,0,new float[]{.5f,-12,-4},new float[]{0,0,-1,0,1,0,1,0,0});
        pose("/beak",centers[1],90,0,new float[]{3,-12,-4},new float[]{0,0,-1,0,1,0,1,0,0});
        pose("/red_thing",centers[2],90,0,new float[]{2,-14,-4},new float[]{0,0,-1,0,1,0,1,0,0});
        pose("/head",centers[0],0,90,new float[]{0,-15.5f,-7},new float[]{1,0,0,0,0,1,0,-1,0});
        pose("/beak",centers[1],0,90,new float[]{0,-18,-7},new float[]{1,0,0,0,0,1,0,-1,0});
        pose("/red_thing",centers[2],0,90,new float[]{0,-17,-5},new float[]{1,0,0,0,0,1,0,-1,0});
        pose("/beak",centers[1],90,90,new float[]{3,-18,-4},new float[]{0,1,0,0,0,1,1,0,0});
        for(int i=0;i<3;i++) {
            float[] a=centers[i].clone(),b=centers[i].clone(),ma=identity(),mb=identity();
            ChickenModelPose.apply(parts[i],-180,35,a,ma);ChickenModelPose.apply(parts[i],180,35,b,mb);
            vector(a,b,"yaw wrap keeps center continuous");vector(ma,mb,"yaw wrap keeps orientation continuous");
        }
        System.out.println("Chicken pose tests passed: official pivot, rigid beak/wattle contacts, quarter turns, body/leg isolation and yaw wrap");
    }
    private static void pose(String path,float[] rest,float yaw,float pitch,float[] expected,float[] rotation) {
        float[] center=rest.clone(),basis=identity();ChickenModelPose.apply(path,yaw,pitch,center,basis);
        vector(center,expected,"official quarter-turn center "+path);vector(basis,rotation,"official quarter-turn basis "+path);
    }
    private static float[] identity(){return new float[]{1,0,0,0,1,0,0,0,1};}
    private static float[] point(float[] center,float[] basis,float x,float y,float z) {
        return new float[]{center[0]+basis[0]*x+basis[1]*y+basis[2]*z,
                center[1]+basis[3]*x+basis[4]*y+basis[5]*z,
                center[2]+basis[6]*x+basis[7]*y+basis[8]*z};
    }
    private static float distance(float[] a,float[] b){float x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2];return(float)Math.sqrt(x*x+y*y+z*z);}
    private static void vector(float[] actual,float[] expected,String label){for(int i=0;i<expected.length;i++)near(actual[i],expected[i],label+" component "+i);}
    private static void near(float actual,float expected,String label){if(!Float.isFinite(actual)||Math.abs(actual-expected)>.0001f)throw new AssertionError(label+": "+actual+" != "+expected);}
    private ChickenPoseSelfTest() { }
}
