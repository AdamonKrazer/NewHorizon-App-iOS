package com.newhorizon.thinclient.world;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Bed facing table extracted from the official 1.20.1 block-state report. */
public final class BedGeometry {
    private static final short[] IDS=new short[256],YAW=new short[256];
    static {try(BufferedReader r=new BufferedReader(new InputStreamReader(BedGeometry.class.getResourceAsStream("/assets/newhorizon/bed_directions.tsv"),StandardCharsets.UTF_8))){String row;int i=0;while((row=r.readLine())!=null){String[] f=row.split("\t");IDS[i]=(short)Integer.parseInt(f[0]);YAW[i++]=(short)(Integer.parseInt(f[1])-180);}}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    public static float yaw(int state){for(int i=0;i<IDS.length;i++)if((IDS[i]&65535)==state)return YAW[i];return 0;}
    public static int x(long pos){return (int)(pos>>38);}
    public static int y(long pos){return (int)(pos<<52>>52);}
    public static int z(long pos){return (int)(pos<<26>>38);}
}
