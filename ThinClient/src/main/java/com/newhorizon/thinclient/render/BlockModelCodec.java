package com.newhorizon.thinclient.render;

import java.nio.ByteBuffer;

/** Lossless model lookup encoded in normalized nibbles; survives LTW's RGBA4/lowp path. */
final class BlockModelCodec {
    static final int TEXELS=3,BYTES=12,UV_SCALE=1024;
    static void put(ByteBuffer out,int descriptor,float u,float v,float shade) {
        if((descriptor&~0xfffff)!=0)throw new IllegalArgumentException("Model material bits");
        int a=uv(u),b=uv(v),s=Math.round(shade*10);
        if(s<0||s>15||Math.abs(s/10f-shade)>.00001f)throw new IllegalArgumentException("Model shade precision");
        for(int shift=0;shift<20;shift+=4)out.put((byte)(((descriptor>>>shift)&15)*17));
        for(int shift=0;shift<12;shift+=4)out.put((byte)(((a>>>shift)&15)*17));
        for(int shift=0;shift<12;shift+=4)out.put((byte)(((b>>>shift)&15)*17));
        out.put((byte)(s*17));
    }
    private static int uv(float value){int fixed=Math.round(value*UV_SCALE);if(fixed<0||fixed>4095||Math.abs(value-fixed/(float)UV_SCALE)>1e-6f)throw new IllegalArgumentException("Model UV precision");return fixed;}
    private BlockModelCodec() { }
}
