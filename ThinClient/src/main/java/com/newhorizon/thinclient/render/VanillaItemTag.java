package com.newhorizon.thinclient.render;
import com.newhorizon.thinclient.protocol.NbtSkipper;
import com.newhorizon.thinclient.protocol.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Bounded visual projection of a stack tag, preserving the original server tag. */
final class VanillaItemTag {
    int potionColor=0x385dc6,leatherColor=0xa06540,mapColor=0xffffff,fireworkColor=0x8a8a8a,damage;
    boolean charged,firework;String trim;private int customPotion=-1;
    static VanillaItemTag read(String hex) {
        VanillaItemTag result=new VanillaItemTag();if(hex==null||hex.length()>8192||(hex.length()&1)!=0)return result;
        try {
            byte[] bytes=new byte[hex.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
            ByteBuffer in=ByteBuffer.wrap(bytes);if(!in.hasRemaining()||in.get()!=10)return result;
            string(in);compound(in,result,"",0);
            if(result.customPotion>=0)result.potionColor=result.customPotion;
        }catch(RuntimeException|ProtocolException ignored){return new VanillaItemTag();}
        return result;
    }
    static void compound(ByteBuffer in,VanillaItemTag tag,String context,int depth)throws ProtocolException {
        if(depth>8)throw new ProtocolException("Item visual tag too deep");
        for(int n=0;n<512;n++) {
            int type=in.get()&255;if(type==0)return;String key=string(in);
            if(type==10){compound(in,tag,context+"/"+key,depth+1);continue;}
            if(type==3){int value=in.getInt();
                if(key.equals("CustomPotionColor")&&context.isEmpty())tag.customPotion=value&0xffffff;
                else if(key.equals("color")&&context.equals("/display"))tag.leatherColor=value&0xffffff;
                else if(key.equals("MapColor")&&context.equals("/display"))tag.mapColor=value&0xffffff;
                else if(key.equals("Damage")&&context.isEmpty())tag.damage=value;
            }else if(type==8){String value=string(in);
                if(key.equals("Potion")&&context.isEmpty())tag.potionColor=potion(value);
                else if(key.equals("material")&&context.equals("/Trim"))tag.trim=value.replace("minecraft:","");
                else if(key.equals("id")&&context.equals("/ChargedProjectiles")&&value.equals("minecraft:firework_rocket"))tag.firework=true;
            }else if(type==1){boolean value=in.get()!=0;if(key.equals("Charged")&&context.isEmpty())tag.charged=value;
            }else if(type==9&&key.equals("ChargedProjectiles")&&context.isEmpty()) {
                int child=in.get()&255,count=in.getInt();if(count<0||count>16)throw new ProtocolException("Too many charged projectiles");
                for(int i=0;i<count;i++)if(child==10)compound(in,tag,"/ChargedProjectiles",depth+1);else NbtSkipper.skipUnnamed(in,child);
            }else if(type==11&&key.equals("Colors")&&context.equals("/Explosion")) {
                int count=in.getInt();if(count<0||count>256)throw new ProtocolException("Too many firework colors");
                long r=0,g=0,b=0;for(int i=0;i<count;i++){int color=in.getInt();r+=(color>>>16)&255;g+=(color>>>8)&255;b+=color&255;}
                if(count>0)tag.fireworkColor=(int)(r/count)<<16|(int)(g/count)<<8|(int)(b/count);
            }else NbtSkipper.skipUnnamed(in,type);
        }
        throw new ProtocolException("Too many item visual tags");
    }
    static String string(ByteBuffer in)throws ProtocolException {
        int length=in.getShort()&65535;if(length>4096||length>in.remaining())throw new ProtocolException("Bad item visual string");
        byte[] value=new byte[length];in.get(value);return new String(value,StandardCharsets.UTF_8);
    }
    static int potion(String id) {
        switch(id.replace("minecraft:","").replace("long_","").replace("strong_","")) {
            case "healing":return 0xf82423;case "harming":return 0x430a09;case "poison":return 0x4e9331;
            case "regeneration":return 0xcd5cab;case "swiftness":return 0x7cafc6;case "slowness":return 0x5a6c81;
            case "strength":return 0x932423;case "weakness":return 0x484d48;case "leaping":return 0x22ff4c;
            case "fire_resistance":return 0xe49a3a;case "water_breathing":return 0x2e5299;case "invisibility":return 0x7f8392;
            case "night_vision":return 0x1f1fa1;case "slow_falling":return 0xf3cfb9;case "turtle_master":return 0x756a61;
            case "luck":return 0x339900;case "empty":return 0xf800f8;default:return 0x385dc6;
        }
    }
}
