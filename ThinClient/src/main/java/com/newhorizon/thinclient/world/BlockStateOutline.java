package com.newhorizon.thinclient.world;

import java.io.*;

/** Official 1.20.1 outline shapes (different from collision and from render geometry). */
public final class BlockStateOutline {
    private static final Data DATA=load();
    private BlockStateOutline() { }
    public static boolean replaceable(int state){return state>=0&&state<DATA.states.length&&(DATA.flags[state]&1)!=0;}
    public static boolean supportsFromBelow(int state){return state>=0&&state<DATA.states.length&&(DATA.flags[state]&2)!=0;}
    public static boolean extendsCell(int state){return state>=0&&state<DATA.states.length&&DATA.protruding[DATA.states[state]&65535];}
    public static int stateCount(){return DATA.states.length;}
    public static int maxBoxCount(){int max=0;for(int i=1;i<DATA.starts.length;i++)max=Math.max(max,DATA.starts[i]-DATA.starts[i-1]);return max;}
    public static int boxCount(int state) {
        if(state<0||state>=DATA.states.length)return BlockStatePhysics.isAir(state)?0:1;
        int shape=DATA.states[state]&65535;return DATA.starts[shape+1]-DATA.starts[shape];
    }
    /** Local coordinates without the block's position-dependent plant offset. */
    public static float coordinate(int state,int box,int component) {
        if(component<0||component>5||box<0||box>=boxCount(state))throw new IndexOutOfBoundsException();
        if(state<0||state>=DATA.states.length)return component<3?0:1;
        return DATA.boxes[(DATA.starts[DATA.states[state]&65535]+box)*6+component];
    }
    public static double coordinateAt(int state,int box,int component,int worldX,int worldZ) {
        return coordinate(state,box,component)+offset(state,worldX,worldZ,component%3);
    }
    /** Same hash and float division as BlockBehaviour.Properties.offsetType. */
    public static double offset(int state,int worldX,int worldZ,int axis) {
        return profileOffset(state,worldX,worldZ,axis,DATA.profileIds);
    }
    /** Visual offset may differ: grass models move while their outline remains fixed. */
    public static double renderOffset(int state,int worldX,int worldZ,int axis) {
        return profileOffset(state,worldX,worldZ,axis,DATA.renderProfileIds);
    }
    private static double profileOffset(int state,int worldX,int worldZ,int axis,byte[] profileIds) {
        if(axis<0||axis>2)throw new IllegalArgumentException("axis");
        if(state<0||state>=DATA.states.length)return 0;
        int p=(profileIds[state]&255)*2;double horizontal=DATA.profiles[p],vertical=DATA.profiles[p+1];
        if(horizontal==0&&vertical==0)return 0;
        long hash=(long)(worldX*3129871)^(long)worldZ*116129781L;
        hash=(hash*hash*42317861L+hash*11L)>>16;
        if(axis==1)return ((double)((float)((hash>>4)&15L)/15.0f)-1)*vertical;
        double value=((double)((float)((hash>>(axis==0?0:8))&15L)/15.0f)-.5)*.5;
        return Math.max(-horizontal,Math.min(horizontal,value));
    }
    public static final class Hit {
        public double distance;
        public int face;
        public boolean inside;
    }
    /** Allocation-free ray/outline intersection; ray direction must be normalized. */
    public static boolean hit(int state,double originX,double originY,double originZ,
                              double directionX,double directionY,double directionZ,
                              int blockX,int blockY,int blockZ,double maximum,Hit out) {
        out.distance=maximum+1;out.face=-1;out.inside=false;
        if(maximum<0||!Double.isFinite(maximum))return false;
        double x=originX-blockX-offset(state,blockX,blockZ,0),
                y=originY-blockY-offset(state,blockX,blockZ,1),
                z=originZ-blockZ-offset(state,blockX,blockZ,2);
        for(int box=0,count=boxCount(state);box<count;box++) {
            double enter=0,leave=maximum;int face=-1;boolean valid=true,inside=true;
            for(int axis=0;axis<3;axis++) {
                double origin=axis==0?x:axis==1?y:z,dir=axis==0?directionX:axis==1?directionY:directionZ;
                double lo=coordinate(state,box,axis),hi=coordinate(state,box,axis+3);
                if(origin<=lo||origin>=hi)inside=false;
                if(Math.abs(dir)<1.0e-12) {if(origin<lo||origin>hi){valid=false;break;}continue;}
                double near=(lo-origin)/dir,far=(hi-origin)/dir;
                int nearFace=axis==0?4:axis==1?0:2;
                if(near>far){double swap=near;near=far;far=swap;nearFace++;}
                if(near>=enter){enter=near;face=nearFace;}
                leave=Math.min(leave,far);
                if(enter>leave){valid=false;break;}
            }
            if(!valid||leave<0||enter>maximum||enter>=out.distance)continue;
            if(inside) {
                double ax=Math.abs(directionX),ay=Math.abs(directionY),az=Math.abs(directionZ);
                face=ax>=ay&&ax>=az?(directionX>0?4:5):ay>=az?(directionY>0?0:1):(directionZ>0?2:3);
            }
            if(face<0)continue;
            out.distance=enter;out.face=face;out.inside=inside;
        }
        return out.face>=0;
    }
    private static Data load() {
        try(InputStream stream=BlockStateOutline.class.getResourceAsStream("/assets/newhorizon/block_outlines_1_20_1.bin")) {
            if(stream==null)throw new IOException("Missing outline catalog");
            DataInputStream in=new DataInputStream(new BufferedInputStream(stream));
            if(in.readInt()!=0x4e484f4c||in.readInt()!=3||in.readInt()!=763)throw new IOException("Outline catalog version");
            int states=in.readInt(),shapes=in.readInt(),boxes=in.readInt(),profiles=in.readInt();
            if(states<1||states>65535||shapes<1||shapes>65535||boxes<0||boxes>65535||profiles<1||profiles>255)
                throw new IOException("Invalid outline dimensions");
            Data data=new Data(states,shapes,boxes,profiles);
            for(int i=0;i<states;i++){int id=in.readUnsignedShort();if(id>=shapes)throw new IOException("Invalid outline shape");data.states[i]=(short)id;}
            in.readFully(data.profileIds);in.readFully(data.renderProfileIds);in.readFully(data.flags);
            for(byte p:data.profileIds)if((p&255)>=profiles)throw new IOException("Invalid outline offset profile");
            for(byte p:data.renderProfileIds)if((p&255)>=profiles)throw new IOException("Invalid render offset profile");
            for(int i=0;i<=shapes;i++){int start=in.readInt();if(start<0||start>boxes||i>0&&start<data.starts[i-1])throw new IOException("Invalid outline offset");data.starts[i]=start;}
            if(data.starts[0]!=0||data.starts[shapes]!=boxes)throw new IOException("Invalid outline box count");
            for(int i=0;i<data.boxes.length;i++){float v=in.readFloat();if(!Float.isFinite(v)||v< -4||v>4)throw new IOException("Invalid outline coordinate");data.boxes[i]=v;}
            for(int i=0;i<data.profiles.length;i++){float v=in.readFloat();if(!Float.isFinite(v)||v<0||v>1)throw new IOException("Invalid outline offset");data.profiles[i]=v;}
            for(int shape=0;shape<shapes;shape++)for(int b=data.starts[shape];b<data.starts[shape+1];b++)for(int axis=0;axis<3;axis++)
                if(data.boxes[b*6+axis]<0||data.boxes[b*6+axis+3]>1)data.protruding[shape]=true;
            // A position-dependent shape can cross the cell even if its base boxes do not.
            for(int state=0;state<states;state++) {
                int shape=data.states[state]&65535,p=(data.profileIds[state]&255)*2;
                for(int b=data.starts[shape];b<data.starts[shape+1];b++)for(int axis=0;axis<3;axis++) {
                    float amount=data.profiles[p+(axis==1?1:0)];
                    if(data.boxes[b*6+axis]-amount<0||data.boxes[b*6+axis+3]+(axis==1?0:amount)>1)data.protruding[shape]=true;
                }
            }
            if(in.read()!=-1)throw new IOException("Trailing outline bytes");
            System.out.println("[NH-THIN] vanilla outlines states="+states+" shapes="+shapes+" boxes="+boxes);
            return data;
        }catch(IOException error){throw new IllegalStateException("Invalid vanilla outline catalog",error);}
    }
    private static final class Data {
        final short[] states;final byte[] profileIds,renderProfileIds,flags;final boolean[] protruding;final int[] starts;final float[] boxes,profiles;
        Data(int states,int shapes,int boxes,int profiles){this.states=new short[states];profileIds=new byte[states];renderProfileIds=new byte[states];flags=new byte[states];protruding=new boolean[shapes];starts=new int[shapes+1];this.boxes=new float[boxes*6];this.profiles=new float[profiles*2];}
    }
}