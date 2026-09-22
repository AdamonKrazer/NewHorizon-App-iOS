package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;

/** Only the vanilla base/living fields needed for combat presentation. */
public final class EntityMetadata {
    public static final class Values {
        public final int[] appearance=new int[32];
        public int blockState=-1;public int itemId=-1;public String itemTag="";
        public int present,flags,air=300,livingFlags,pose,size=1,vehicleVariant,horseFlags;
        public boolean saddled;
        public boolean baby;
        public boolean bedPresent;
        public long bed;
        public float health=20,absorption,interactionWidth=1,interactionHeight=1;
    }
    private EntityMetadata() { }
    public static void read(ByteBuffer in,Values out) throws ProtocolException {
        out.present=0;
        for(int entries=0;entries<256;entries++) {
            BinaryCodec.require(in,1);int index=in.get()&255;if(index==255)return;
            int type=VarInts.read(in);
            if(type==0) {
                BinaryCodec.require(in,1);int value=in.get()&255;if(index<32)out.appearance[index]=value;
                if(index==0) {out.flags=value;out.present|=1;}
                if(index==8) {out.livingFlags=value;out.present|=4;}
                if(index==17)out.horseFlags=value;
            } else if(type==1) {
                int value=VarInts.read(in);if(index<32)out.appearance[index]=value;if(index==1) {out.air=value;out.present|=2;}
                if(index==11)out.vehicleVariant=value;
                if(index==16)out.size=Math.max(1,Math.min(127,value));
            } else if(type==3) {
                BinaryCodec.require(in,4);float value=in.getFloat();
                if(!Float.isFinite(value))throw new ProtocolException("Invalid entity float");
                if(index==9) {out.health=value;out.present|=8;}
                if(index==15) {out.absorption=Math.max(0,value);out.present|=16;}
                if(index==8)out.interactionWidth=Math.max(0,value);
                if(index==9)out.interactionHeight=Math.max(0,value);
            } else switch(type) {
                case 2:VarInts.readLong(in);break;
                case 4:case 5:{String text=BinaryCodec.readString(in,262144);if(type==4&&index==17)out.appearance[index]="brown".equals(text)?1:0;break;}
                case 6:if(flag(in))BinaryCodec.readString(in,262144);break;
                case 7:{boolean present=flag(in);int item=-1;String tag="";
                    if(present){item=VarInts.read(in);skip(in,1);int start=in.position();NbtSkipper.skipRoot(in);int length=in.position()-start;
                        if((index==8||index==22)&&length>1&&length<=4096){char[] hex=new char[length*2];String digits="0123456789abcdef";
                            for(int n=0;n<length;n++){int value=in.get(start+n)&255;hex[n*2]=digits.charAt(value>>>4);hex[n*2+1]=digits.charAt(value&15);}tag=new String(hex);}}
                    if(index==8||index==22){out.itemId=item;out.itemTag=tag;}break;}
                case 8:{boolean value=flag(in);if(index<32)out.appearance[index]=value?1:0;if(index==16)out.baby=value;if(index==17||index==19)out.saddled=value;break;}
                case 9:case 26:skip(in,12);break;
                case 10:skip(in,8);break;
                case 11:{boolean present=flag(in);long pos=0;if(present){BinaryCodec.require(in,8);pos=in.getLong();}if(index==14){out.bedPresent=present;out.bed=pos;out.present|=64;}break;}
                case 20:{int value=VarInts.read(in);if(index==6){out.pose=value;out.present|=32;}break;}
                case 12:case 14:case 15:case 19:case 21:case 22:case 24:case 25:{int value=VarInts.read(in);if(index<32)out.appearance[index]=value;if(type==14&&index==23)out.blockState=value;break;}
                case 13:if(flag(in))skip(in,16);break;
                case 16:NbtSkipper.skipRoot(in);break;
                case 18:out.appearance[28]=VarInts.read(in);out.appearance[29]=VarInts.read(in);out.appearance[30]=VarInts.read(in);break;
                case 23:if(flag(in)){BinaryCodec.readString(in,32767);skip(in,8);}break;
                case 27:skip(in,16);break;
                default:in.position(in.limit());return; // A later mod/particle serializer is outside this projection.
            }
        }
        throw new ProtocolException("Too many metadata entries");
    }
    private static boolean flag(ByteBuffer in)throws ProtocolException {BinaryCodec.require(in,1);return in.get()!=0;}
    private static void skip(ByteBuffer in,int bytes)throws ProtocolException {BinaryCodec.require(in,bytes);in.position(in.position()+bytes);}
}
