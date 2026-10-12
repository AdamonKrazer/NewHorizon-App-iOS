package com.newhorizon.thinclient.render;
import com.newhorizon.thinclient.world.EntityMetadata;
import com.newhorizon.thinclient.protocol.BinaryCodec;
import java.nio.ByteBuffer;
public final class EntityLabelsSelfTest {
    public static void run() throws Exception {
        EntityMetadata.Values v=new EntityMetadata.Values();ByteBuffer b=ByteBuffer.allocate(256);
        b.put((byte)2).put((byte)6).put((byte)1);BinaryCodec.writeString(b,"{\"text\":\"NPC\"}",256);
        b.put((byte)3).put((byte)8).put((byte)1).put((byte)255).flip();EntityMetadata.read(b,v);
        check(v.customName.contains("NPC")&&v.customNameVisible,"custom name/visibility");
        b.clear();b.put((byte)0).put((byte)0).put((byte)32).put((byte)255).flip();EntityMetadata.read(b,v);
        check(v.customName.contains("NPC")&&v.customNameVisible,"partial metadata retains name");
        b.clear();b.put((byte)2).put((byte)6).put((byte)0).put((byte)255).flip();EntityMetadata.read(b,v);check(v.customName.isEmpty(),"removed name");
        b.clear();b.put((byte)22).put((byte)5);BinaryCodec.writeString(b,"{\"text\":\"Hologram\"}",256);b.put((byte)255).flip();EntityMetadata.read(b,v);check(v.displayText.contains("Hologram"),"text display");
        double[] p=new double[3];check(EntityLabels.project(0,0,5,0,0,2,1,p)&&Math.abs(p[0]-.5)<1e-6&&Math.abs(p[1]-.5)<1e-6,"front projection");
        check(!EntityLabels.project(0,0,-5,0,0,2,1,p),"behind camera");
        check(EntityLabels.project(-5,0,0,90,0,2,1,p)&&Math.abs(p[0]-.5)<1e-6,"rotated camera");
        check(EntityLabels.project(0,0,10,0,0,2,1,p)&&Math.abs(p[2]-.00125)<1e-7,"distance scaling");
        System.out.println("Entity label metadata/projection PASS");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
