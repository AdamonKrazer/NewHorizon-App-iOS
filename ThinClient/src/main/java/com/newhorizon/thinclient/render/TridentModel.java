package com.newhorizon.thinclient.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** Original TridentModel: five cuboids, 32x32 texture, model Y points down. */
final class TridentModel {
    static final int FACES=30;
    // Each face has four xyz/uv vertices; UVs are original texture pixels.
    static final float[] MESH=mesh();
    private static final int[] PIXELS=load();
    private static float[] mesh() {
        List<Float> v=new ArrayList<>();
        box(v,0,6,-.5f,2,-.5f,1,25,1,false);
        box(v,4,0,-1.5f,0,-.5f,3,2,1,false);
        box(v,4,3,-2.5f,-3,-.5f,1,4,1,false);
        box(v,0,0,-.5f,-4,-.5f,1,4,1,false);
        box(v,4,3,1.5f,-3,-.5f,1,4,1,true);
        float[] out=new float[v.size()];for(int i=0;i<out.length;i++)out[i]=v.get(i);return out;
    }
    private static void box(List<Float> v,int u,int t,float x,float y,float z,int w,int h,int d,boolean mirror) {
        float a=x/16,b=y/16,c=z/16,xx=(x+w)/16,yy=(y+h)/16,zz=(z+d)/16,q=1f/16;
        int start=v.size();
        face(v,u+d,t,w,d,a,b,zz,q,0,0,0,0,-q);
        face(v,u+d+w,t,w,d,a,yy,c,q,0,0,0,0,q);
        face(v,u,t+d,d,h,a,b,c,0,0,q,0,q,0);
        face(v,u+d,t+d,w,h,xx,b,c,-q,0,0,0,q,0);
        face(v,u+d+w,t+d,d,h,xx,b,zz,0,0,-q,0,q,0);
        face(v,u+2*d+w,t+d,w,h,a,b,zz,q,0,0,0,q,0);
        if(mirror)for(int i=start;i<v.size();i+=5)v.set(i,a+xx-v.get(i));
    }
    private static void face(List<Float> v,int u,int t,int w,int h,float x,float y,float z,
                             float ax,float ay,float az,float bx,float by,float bz) {
        point(v,x,y,z,u,t);point(v,x+ax*w,y+ay*w,z+az*w,u+w,t);
        point(v,x+ax*w+bx*h,y+ay*w+by*h,z+az*w+bz*h,u+w,t+h);point(v,x+bx*h,y+by*h,z+bz*h,u,t+h);
    }
    private static void point(List<Float> v,float x,float y,float z,float u,float t){v.add(x);v.add(y);v.add(z);v.add(u);v.add(t);}
    static int pixel(int u,int v){return PIXELS[v*32+u];}
    private static int[] load() {
        try(InputStream in=TridentModel.class.getResourceAsStream("/assets/minecraft/textures/entity/trident.png")) {
            if(in==null)throw new IOException("Missing original trident texture");
            BufferedImage image=ImageIO.read(in);if(image==null||image.getWidth()!=32||image.getHeight()!=32)throw new IOException("Trident skin size");
            return image.getRGB(0,0,32,32,null,0,32);
        }catch(IOException e){throw new IllegalStateException("Trident model",e);}
    }
}
