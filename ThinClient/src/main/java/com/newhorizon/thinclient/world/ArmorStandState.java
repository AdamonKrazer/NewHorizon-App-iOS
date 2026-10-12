package com.newhorizon.thinclient.world;

/** Protocol 763 rotations in degrees, retained across partial metadata updates. */
public final class ArmorStandState {
    private static final float[] DEFAULT={0,0,0, 0,0,0, -10,0,-10, -15,0,10, -1,0,-1, 1,0,1};
    public static float[] create(){return DEFAULT.clone();}
    public static void reset(float[] pose){System.arraycopy(DEFAULT,0,pose,0,18);}
    public static boolean small(EntityTracker.Renderable e){return e.type==2&&(e.appearance[15]&1)!=0;}
    public static boolean visiblePart(EntityTracker.Renderable e,String path){
        if(e.type!=2)return true;
        if(path.equals("/hat"))return false;
        if(path.endsWith("_arm"))return (e.appearance[15]&4)!=0;
        return !path.equals("/base_plate")||(e.appearance[15]&8)==0;
    }
    public static int part(String name){
        switch(name){case "head":case "hat":return 0;
            case "body":case "right_body_stick":case "left_body_stick":case "shoulder_stick":return 3;
            case "left_arm":return 6;case "right_arm":return 9;case "left_leg":return 12;case "right_leg":return 15;
            default:return -1;}
    }
    private ArmorStandState(){}
}
