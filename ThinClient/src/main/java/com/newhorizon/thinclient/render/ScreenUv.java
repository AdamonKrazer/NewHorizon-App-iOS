package com.newhorizon.thinclient.render;
/** Shared texture/picking transform for the four WebDisplays rotations. */
public final class ScreenUv {
    public static float u(float u,float v,int rotation){switch(rotation&3){case 1:return v;case 2:return 1-u;case 3:return 1-v;default:return u;}}
    public static float v(float u,float v,int rotation){switch(rotation&3){case 1:return 1-u;case 2:return 1-v;case 3:return u;default:return v;}}
}
