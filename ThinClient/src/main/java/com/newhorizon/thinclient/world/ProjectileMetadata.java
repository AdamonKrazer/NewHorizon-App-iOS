package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads the base Entity and projectile metadata without retaining NBT trees. */
final class ProjectileMetadata {
    static void read(ByteBuffer in, ProjectileMotion p) throws ProtocolException {
        for(int count=0;count<256;count++) {
            BinaryCodec.require(in,1); int index=in.get()&255;
            if(index==255) {
                if(in.hasRemaining()) throw new ProtocolException("Trailing projectile metadata");
                return;
            }
            int serializer=VarInts.read(in);
            switch(serializer) {
                case 0: case 8: {
                    BinaryCodec.require(in,1); int value=in.get()&255;
                    if(index==10 && serializer==8 && p.type==ProjectileKind.FIREWORK) p.shotAtAngle=value!=0;
                    if(index==9 && serializer==8 && p.type==ProjectileKind.FISHING) p.biting(value!=0);
                    if(index==5 && serializer==8) p.noGravity=value!=0;
                    if(index==8 && serializer==0 && ProjectileKind.arrow(p.type)) p.flags=value;
                    if(index==8 && serializer==8 && p.type==ProjectileKind.WITHER_SKULL) p.dangerous=value!=0;
                    if(index==10 && serializer==0 && p.type==ProjectileKind.TRIDENT) p.loyalty=value;
                    break;
                }
                case 1: case 14: case 15: case 19: case 20:
                case 21: case 22: case 24: case 25: {
                    int value=VarInts.read(in);
                    if(index==8 && serializer==1 && p.type==ProjectileKind.FISHING) p.hooked=value>0?value-1:-1;
                    if(index==9 && serializer==19 && p.type==ProjectileKind.FIREWORK) p.attachedEntity=value-1;
                    if(index==10 && p.type==ProjectileKind.ARROW && serializer==1) p.color=value;
                    break;
                }
                case 2:
                    for(int n=0;n<10;n++) {
                        BinaryCodec.require(in,1); if((in.get()&128)==0) break;
                        if(n==9) throw new ProtocolException("Metadata VarLong overflow");
                    }
                    break;
                case 3: skip(in,4); break;
                case 4: case 5: BinaryCodec.readString(in,32767); break;
                case 6:
                    BinaryCodec.require(in,1);
                    if(in.get()!=0) BinaryCodec.readString(in,32767); break;
                case 7:
                    BinaryCodec.require(in,1);
                    if(in.get()!=0) {
                        int item=VarInts.read(in); skip(in,1);
                        int start=in.position(); NbtSkipper.skipRoot(in);
                        if(index==8) {
                            p.itemId=item;
                            ByteBuffer tag=in.duplicate(); tag.position(start); tag.limit(in.position());
                            if(p.type==ProjectileKind.FIREWORK) p.firework=FireworkData.read(tag);
                            else p.color=potionColor(tag);
                        }
                    } else if(index==8) { p.itemId=0; p.color=-1; p.firework=null; }
                    break;
                case 9: case 26: skip(in,12); break;
                case 10: skip(in,8); break;
                case 11: BinaryCodec.require(in,1); if(in.get()!=0) skip(in,8); break;
                case 12: VarInts.read(in); break;
                case 13: BinaryCodec.require(in,1); if(in.get()!=0) skip(in,16); break;
                case 16: NbtSkipper.skipRoot(in); break;
                case 18: VarInts.read(in); VarInts.read(in); VarInts.read(in); break;
                case 23:
                    BinaryCodec.require(in,1);
                    if(in.get()!=0) { BinaryCodec.readString(in,32767); skip(in,8); } break;
                case 27: skip(in,16); break;
                default:
                    // Forge may add serializers. Framing lets us ignore this projection
                    // without interpreting unknown data as another metadata field.
                    in.position(in.limit()); return;
            }
        }
        throw new ProtocolException("Projectile metadata exceeds entry limit");
    }
    private static void skip(ByteBuffer in,int bytes) throws ProtocolException {
        BinaryCodec.require(in,bytes); in.position(in.position()+bytes);
    }
    private static String string(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in,2); int length=in.getShort()&65535;
        if(length>4096) throw new ProtocolException("Projectile NBT string too large");
        BinaryCodec.require(in,length); byte[] text=new byte[length]; in.get(text);
        return new String(text,StandardCharsets.UTF_8);
    }
    private static int potionColor(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in,1); if((in.get()&255)!=10) return -1;
        string(in); int color=0x385dc6, custom=-1;
        while(in.hasRemaining()) {
            int type=in.get()&255; if(type==0) break;
            String key=string(in);
            if(type==3 && key.equals("CustomPotionColor")) { BinaryCodec.require(in,4); custom=in.getInt()&0xffffff; }
            else if(type==8 && key.equals("Potion")) color=baseColor(string(in));
            else NbtSkipper.skipUnnamed(in,type);
        }
        return custom>=0 ? custom : color;
    }
    private static int baseColor(String potion) {
        String name=potion.replace("minecraft:","").replace("long_","").replace("strong_","");
        switch(name) {
            case "healing": case "harming": return name.equals("healing")?0xf82423:0x430a09;
            case "poison": return 0x4e9331;
            case "regeneration": return 0xcd5cab;
            case "swiftness": return 0x7cafc6;
            case "slowness": return 0x5a6c81;
            case "strength": return 0x932423;
            case "weakness": return 0x484d48;
            case "leaping": return 0x22ff4c;
            case "fire_resistance": return 0xe49a3a;
            case "water_breathing": return 0x2e5299;
            case "invisibility": return 0x7f8392;
            case "night_vision": return 0x1f1fa1;
            case "slow_falling": return 0xf3cfb9;
            default: return 0x385dc6;
        }
    }
}
