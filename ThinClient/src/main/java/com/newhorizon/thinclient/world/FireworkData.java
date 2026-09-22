package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Bounded projection of item NBT, never an executable client-side item. */
final class FireworkData {
    static final int MAX_EXPLOSIONS=8, MAX_COLORS=16;
    int flight=1,count;
    final Explosion[] explosions=new Explosion[MAX_EXPLOSIONS];
    static final class Explosion {
        int type,colorCount,fadeCount;
        boolean trail,flicker;
        final int[] colors=new int[MAX_COLORS],fade=new int[MAX_COLORS];
        int color(java.util.Random random) { return colorCount==0?0x1e1b1b:colors[random.nextInt(colorCount)]; }
    }
    static FireworkData read(ByteBuffer source) throws ProtocolException {
        ByteBuffer in=source.duplicate(); NbtSkipper.skipRoot(in); // Validate lengths and depth before projection.
        in=source.duplicate();BinaryCodec.require(in,1);
        FireworkData result=new FireworkData();
        if((in.get()&255)!=10)return result;
        string(in);
        while(in.hasRemaining()) {
            int type=in.get()&255;if(type==0)break;String name=string(in);
            if(type==10&&name.equals("Fireworks"))result.fireworks(in);
            else NbtSkipper.skipUnnamed(in,type);
        }
        return result;
    }
    private void fireworks(ByteBuffer in) throws ProtocolException {
        while(in.hasRemaining()) {
            int type=in.get()&255;if(type==0)return;String name=string(in);
            if(type==1&&name.equals("Flight")) { BinaryCodec.require(in,1);flight=in.get(); }
            else if(type==9&&name.equals("Explosions")) {
                BinaryCodec.require(in,5);int element=in.get()&255,n=in.getInt();count=0;
                for(int i=0;i<n;i++) {
                    if(element==10&&i<MAX_EXPLOSIONS)explosions[count++]=explosion(in);
                    else NbtSkipper.skipUnnamed(in,element);
                }
            } else NbtSkipper.skipUnnamed(in,type);
        }
    }
    private static Explosion explosion(ByteBuffer in) throws ProtocolException {
        Explosion e=new Explosion();
        while(in.hasRemaining()) {
            int type=in.get()&255;if(type==0)break;String key=string(in);
            if(type==1&&(key.equals("Type")||key.equals("Trail")||key.equals("Flicker"))) {
                BinaryCodec.require(in,1);int v=in.get()&255;
                if(key.equals("Type"))e.type=v<=4?v:0;
                else if(key.equals("Trail"))e.trail=v!=0;else e.flicker=v!=0;
            } else if(type==11&&(key.equals("Colors")||key.equals("FadeColors"))) {
                BinaryCodec.require(in,4);int n=in.getInt();int[] target=key.equals("Colors")?e.colors:e.fade;
                for(int i=0;i<n;i++) { BinaryCodec.require(in,4);int v=in.getInt();if(i<MAX_COLORS)target[i]=v&0xffffff; }
                if(key.equals("Colors"))e.colorCount=Math.min(n,MAX_COLORS);else e.fadeCount=Math.min(n,MAX_COLORS);
            } else NbtSkipper.skipUnnamed(in,type);
        }
        return e;
    }
    private static String string(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in,2);int n=in.getShort()&65535;BinaryCodec.require(in,n);
        if(n>64) { in.position(in.position()+n);return ""; }
        byte[] bytes=new byte[n];in.get(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
}
