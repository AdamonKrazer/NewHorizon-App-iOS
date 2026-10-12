package com.newhorizon.thinclient.world;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Actual vanilla 1.20.1 emission, absorption and LightEngine face occlusion. */
public final class BlockStateLighting {
    private static final Data DATA=load();
    private BlockStateLighting() { }
    public static int stateCount(){return DATA.levels.length;}
    public static int emission(int state){return valid(state)?DATA.levels[state]&15:0;}
    /** Original getLightBlock, before the light engine's mandatory minimum attenuation of one. */
    public static int opacity(int state){return valid(state)?(DATA.levels[state]&255)>>>4:15;}
    public static boolean useShapeForLightOcclusion(int state){return valid(state)&&(DATA.flags[state]&1)!=0;}
    public static boolean canOcclude(int state){return valid(state)&&(DATA.flags[state]&2)!=0;}
    public static boolean sameLighting(int before,int after){
        if(before==after)return true;
        if(!valid(before)||!valid(after))return !valid(before)&&!valid(after);
        return DATA.levels[before]==DATA.levels[after]&&DATA.profiles[before]==DATA.profiles[after];
    }
    /** Faces: down, up, north, south, west, east; source face unions with target's opposite face. */
    public static boolean occludes(int fromState,int toState,int face){
        if(face<0||face>5)throw new IllegalArgumentException("light face");
        int left=faceMask(fromState,face),right=faceMask(toState,face^1);
        if(left==1||right==1)return true;
        if(left==0||right==0)return false;
        return DATA.unions[left*DATA.maskCount+right];
    }
    public static int retainedBytes(){return DATA.levels.length*3+DATA.faces.length+DATA.unions.length;}
    private static boolean valid(int state){return state>=0&&state<DATA.levels.length;}
    private static int faceMask(int state,int face){return valid(state)?DATA.faces[(DATA.profiles[state]&255)*6+face]&255:1;}
    private static Data load(){
        try(InputStream stream=BlockStateLighting.class.getResourceAsStream("/assets/newhorizon/block_lighting_1_20_1.bin")){
            if(stream==null)throw new IOException("Missing original light catalog");
            DataInputStream in=new DataInputStream(new BufferedInputStream(stream));
            if(in.readInt()!=0x4e484c54||in.readInt()!=1||in.readInt()!=763)throw new IOException("Light catalog version");
            int states=in.readInt(),profiles=in.readInt(),masks=in.readInt();
            if(states!=24135||profiles<1||profiles>255||masks<2||masks>255)throw new IOException("Light catalog dimensions");
            Data data=new Data(states,profiles,masks);in.readFully(data.levels);in.readFully(data.flags);
            for(byte flag:data.flags)if((flag&~3)!=0)throw new IOException("Light catalog flags");
            for(int state=0;state<states;state++){int profile=in.readUnsignedShort();if(profile>=profiles)throw new IOException("Light profile index");data.profiles[state]=(byte)profile;}
            for(int face=0;face<data.faces.length;face++){int mask=in.readUnsignedShort();if(mask>=masks)throw new IOException("Light face index");data.faces[face]=(byte)mask;}
            long[] bits=new long[masks*4];for(int word=0;word<bits.length;word++)bits[word]=in.readLong();
            for(int word=0;word<4;word++)if(bits[word]!=0||bits[word+4]!=-1L)throw new IOException("Light empty/full masks");
            for(int face=0;face<6;face++)if(data.faces[face]!=0)throw new IOException("Light empty profile");
            for(int left=0;left<masks;left++)for(int right=0;right<masks;right++){
                boolean full=true;for(int word=0;word<4;word++)full&=(bits[left*4+word]|bits[right*4+word])==-1L;
                data.unions[left*masks+right]=full;
            }
            if(in.read()!=-1)throw new IOException("Trailing light catalog bytes");
            System.out.println("[NH-LIGHT] vanilla states="+states+" faceProfiles="+profiles+" masks="+masks+" retainedBytes="+(states*3+data.faces.length+data.unions.length));
            return data;
        }catch(IOException failure){throw new IllegalStateException("Invalid original light catalog",failure);}
    }
    private static final class Data {
        final byte[] levels,flags,profiles,faces;final boolean[] unions;final int maskCount;
        Data(int states,int profileCount,int masks){levels=new byte[states];flags=new byte[states];profiles=new byte[states];faces=new byte[profileCount*6];unions=new boolean[masks*masks];maskCount=masks;}
    }
}