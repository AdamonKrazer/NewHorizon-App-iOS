package com.newhorizon.thinclient.world;
import java.io.*;
/** Lit/facing variants resolved offline from the official block-state report. */
public final class AmbientParticleSources {
    private static final byte[] DATA=load();
    private static byte[] load(){byte[] data=new byte[24135];try(DataInputStream in=new DataInputStream(AmbientParticleSources.class.getResourceAsStream("/assets/newhorizon/particles/ambient.bin"))){in.readFully(data);if(in.read()!=-1)throw new IOException("Ambient states length");return data;}catch(IOException ex){throw new IllegalStateException(ex);}}
    public static int source(int state){return state>=0&&state<DATA.length?DATA[state]&255:0;}
    private AmbientParticleSources(){}
}
