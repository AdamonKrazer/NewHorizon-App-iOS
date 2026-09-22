package com.newhorizon.thinclient.render;

/** Box UVs after the vanilla model-space X/Y flip, plus one shared yaw convention. */
final class EntityBoxGeometry {
    private EntityBoxGeometry() { }

    // Native model fronts point toward -Z. Vanilla living rendering rotates by 180-yaw
    // around Y, whose equivalent in the world's X/Z plane is yaw+180.
    static float yaw(float entityYaw,boolean minecart) {return minecart?entityYaw:entityYaw+180f;}

    static void uv(int face,float x,float y,float z,float u,float v,float w,float h,float d,
                   boolean mirror,float[] out) {
        if(mirror){x=1-x;if(face==4)face=5;else if(face==5)face=4;}
        switch(face) {
            case 0:out[0]=u+d+(2-x)*w;out[1]=v+(1-z)*d;break;
            case 1:out[0]=u+d+(1-x)*w;out[1]=v+(1-z)*d;break;
            case 2:out[0]=u+d+(1-x)*w;out[1]=v+d+(1-y)*h;break;
            case 3:out[0]=u+2*d+w+x*w;out[1]=v+d+(1-y)*h;break;
            case 4:out[0]=u+d+w+z*d;out[1]=v+d+(1-y)*h;break;
            case 5:out[0]=u+(1-z)*d;out[1]=v+d+(1-y)*h;break;
            default:throw new IllegalArgumentException("Cube face "+face);
        }
    }

    static final String UV_SHADER=
            " float w=iPixels.x;float h=iPixels.y;float d=iPixels.z;int face=gl_VertexID/6;vec2 uv;vec3 t=aPosition;if(iMirror>0.5){t.x=1.0-t.x;if(face==4)face=5;else if(face==5)face=4;}\n"
            +" if(face==0)uv=vec2(d+(2.0-t.x)*w,(1.0-t.z)*d);\n"
            +" else if(face==1)uv=vec2(d+(1.0-t.x)*w,(1.0-t.z)*d);\n"
            +" else if(face==2)uv=vec2(d+(1.0-t.x)*w,d+(1.0-t.y)*h);\n"
            +" else if(face==3)uv=vec2(2.0*d+w+t.x*w,d+(1.0-t.y)*h);\n"
            +" else if(face==4)uv=vec2(d+w+t.z*d,d+(1.0-t.y)*h);\n"
            +" else uv=vec2((1.0-t.z)*d,d+(1.0-t.y)*h);\n";
}
