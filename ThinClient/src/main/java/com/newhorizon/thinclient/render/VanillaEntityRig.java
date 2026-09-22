package com.newhorizon.thinclient.render;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Original PartPose hierarchy; immutable, shared by every instance of a model. */
final class VanillaEntityRig {
    final String name;
    final String[] paths,names;
    final int[] parents,boxJoints;
    final float[] poses,centers;
    private VanillaEntityRig(DataInputStream in)throws java.io.IOException {
        name=in.readUTF();int joints=in.readInt(),boxes=in.readInt();
        if(joints<1||joints>80||boxes<1||boxes>40)throw new IllegalStateException("Rig bounds");
        paths=new String[joints];names=new String[joints];parents=new int[joints];poses=new float[joints*6];
        boxJoints=new int[boxes];centers=new float[boxes*3];
        for(int j=0;j<joints;j++) {
            paths[j]=in.readUTF();parents[j]=in.readInt();
            names[j]=paths[j].substring(paths[j].lastIndexOf('/')+1);
            if(parents[j]<-1||parents[j]>=j)throw new IllegalStateException("Rig parent order");
            for(int k=0;k<6;k++)poses[j*6+k]=number(in);
        }
        for(int b=0;b<boxes;b++) {
            boxJoints[b]=in.readInt();
            if(boxJoints[b]<0||boxJoints[b]>=joints)throw new IllegalStateException("Rig box joint");
            for(int k=0;k<3;k++)centers[b*3+k]=number(in);
        }
    }
    private static float number(DataInputStream in)throws java.io.IOException {
        float value=in.readFloat();if(!Float.isFinite(value))throw new IllegalStateException("Rig float");return value;
    }
    static final VanillaEntityRig[] ALL=load();
    private static VanillaEntityRig[] load() {
        try(InputStream resource=VanillaEntityRig.class.getResourceAsStream("/assets/newhorizon/entities/rigs.bin.gz")) {
            if(resource==null)throw new IllegalStateException("Missing vanilla rigs");
            try(DataInputStream in=new DataInputStream(new GZIPInputStream(resource))) {
                if(in.readInt()!=0x4e485231)throw new IllegalStateException("Rig magic");
                int count=in.readInt();if(count<1||count>128)throw new IllegalStateException("Rig count");
                VanillaEntityRig[] result=new VanillaEntityRig[count];
                for(int i=0;i<count;i++)result[i]=new VanillaEntityRig(in);
                if(in.read()!=-1)throw new IllegalStateException("Trailing rig data");return result;
            }
        }catch(java.io.IOException ex){throw new IllegalStateException("Vanilla entity rigs",ex);}
    }
    static VanillaEntityRig find(String name) {
        for(VanillaEntityRig rig:ALL)if(rig.name.equals(name))return rig;
        throw new IllegalStateException("Missing rig "+name);
    }
}
