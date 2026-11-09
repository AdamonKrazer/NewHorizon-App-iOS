package com.newhorizon.thinclient.protocol;

import com.newhorizon.thinclient.memory.MemoryBudget;
import java.nio.ByteBuffer;

/** Bounded FML3 login for the native WebDisplays protocol already implemented by the thin client. */
public final class ForgeLogin implements AutoCloseable {
    public static final int REJECT=-1,NO_RESPONSE=-2;
    private static final String HANDSHAKE="fml:handshake";
    private static final String[] CHANNELS={"fml:handshake","fml:loginwrapper","minecraft:register",
        "minecraft:unregister","webdisplays:packetsystem","webdisplays:mcef_gate","forge:tier_sorting","newhorizon:tablet_chat"};
    private static final String[] VERSIONS={"FML3","FML3","FML3","FML3","2","1","1.0","1"};
    public final ForgeItemRegistry items;
    private boolean listed;
    public boolean tabletSocial;
    private int registryCount;
    private boolean expectsItems;
    public ForgeLogin(MemoryBudget budget){items=new ForgeItemRegistry(budget);}

    public int receive(String channel,ByteBuffer payload)throws ProtocolException {
        if(!"fml:loginwrapper".equals(channel))return REJECT;
        ByteBuffer in=payload.duplicate();
        String target=BinaryCodec.readString(in,256);
        int length=count(in,2*1024*1024);
        if(length!=in.remaining())throw new ProtocolException("Invalid Forge wrapper length");
        if(!HANDSHAKE.equals(target))return REJECT;
        BinaryCodec.require(in,1);int type=in.get()&255,reply;
        switch(type) {
        case 5:
            for(int n=count(in,4096);n>0;n--){skipString(in,1024);skipString(in,1024);skipString(in,1024);}
            reply=NO_RESPONSE;break;
        case 1:
            if(listed)throw new ProtocolException("Duplicate Forge mod list");
            for(int n=count(in,4096);n>0;n--)skipString(in,1024);
            for(int n=count(in,4096);n>0;n--){
                String key=BinaryCodec.readString(in,256),version=BinaryCodec.readString(in,1024);
                if(key.equals("newhorizon:tablet_chat")&&version.equals("1"))tabletSocial=true;
                for(int i=0;i<CHANNELS.length;i++)if(CHANNELS[i].equals(key)&&!VERSIONS[i].equals(version))
                    throw new ProtocolException("Unsupported Forge channel version: "+key+"="+version);
            }
            registryCount=count(in,4096);
            for(int n=registryCount;n>0;n--)if("minecraft:item".equals(BinaryCodec.readString(in,256)))expectsItems=true;
            if(count(in,4096)!=0)throw new ProtocolException("Custom datapack registries are not supported by this thin client");
            listed=true;reply=2;break;
        case 3:
            if(!listed||registryCount<=0)throw new ProtocolException("Unexpected Forge registry");
            String registry=BinaryCodec.readString(in,256);
            BinaryCodec.require(in,1);int snapshot=in.get()&255;
            if(snapshot>1)throw new ProtocolException("Invalid registry snapshot flag");
            if(snapshot==1){
                if("minecraft:item".equals(registry))items.read(in);
                else for(int n=count(in,65536);n>0;n--){skipString(in,256);if(VarInts.read(in)<0)throw new ProtocolException("Negative registry id");}
                for(int n=count(in,65536);n>0;n--){skipString(in,256);skipString(in,256);}
                for(int n=count(in,65536);n>0;n--){skipString(in,256);skipString(in,1024);}
                for(int n=count(in,65536);n>0;n--)VarInts.read(in);
            }
            registryCount--;
            if(registryCount==0&&expectsItems&&!items.ready())throw new ProtocolException("Missing Forge item snapshot");
            reply=99;break;
        case 4:
            if(!listed)throw new ProtocolException("Config before mod list");
            skipString(in,32767*4);int bytes=count(in,2*1024*1024);BinaryCodec.require(in,bytes);in.position(in.position()+bytes);
            reply=99;break;
        case 6:throw new ProtocolException("Server rejected thin client Forge channels");
        default:throw new ProtocolException("Unsupported Forge login message "+type);
        }
        if(in.hasRemaining())throw new ProtocolException("Trailing Forge handshake bytes");
        return reply;
    }

    public void writeResponse(ByteBuffer out,int transaction,int reply) {
        VarInts.write(out,MinecraftPacketIds.LOGIN_SERVERBOUND_CUSTOM_QUERY);VarInts.write(out,transaction);out.put((byte)1);
        BinaryCodec.writeString(out,HANDSHAKE,256);
        int length=1;
        if(reply==2){
            length+=1+1+1; // mod count (zero), channel count, registry-hash count
            for(int i=0;i<CHANNELS.length;i++)length+=1+CHANNELS[i].length()+1+VERSIONS[i].length();
        }
        VarInts.write(out,length);out.put((byte)reply);
        if(reply==2){
            VarInts.write(out,0); // A protocol implementation does not claim installed Forge mods.
            VarInts.write(out,CHANNELS.length);
            for(int i=0;i<CHANNELS.length;i++){BinaryCodec.writeString(out,CHANNELS[i],256);BinaryCodec.writeString(out,VERSIONS[i],256);}
            VarInts.write(out,0);
        }
    }
    public void verifyComplete()throws ProtocolException {
        if(listed&&(registryCount!=0||(expectsItems&&!items.ready())))throw new ProtocolException("Incomplete Forge registries");
    }
    public static void readTiers(ByteBuffer in)throws ProtocolException {
        BinaryCodec.require(in,1);
        if(in.get()!=0)throw new ProtocolException("Unsupported tier message");
        // Mining/drop decisions stay server-owned. Accept the supported vanilla tier names.
        for(int n=count(in,64);n>0;n--){
            String key=BinaryCodec.readString(in,256);
            if(!key.matches("minecraft:(wood|gold|stone|iron|diamond|netherite)"))
                throw new ProtocolException("Unsupported custom tool tier: "+key);
        }
        if(in.hasRemaining())throw new ProtocolException("Trailing tier data");
    }
    static int count(ByteBuffer in,int max)throws ProtocolException {
        int n=VarInts.read(in);if(n<0||n>max)throw new ProtocolException("Forge length outside limit: "+n);return n;
    }
    static void skipString(ByteBuffer in,int max)throws ProtocolException {
        int n=count(in,max);BinaryCodec.require(in,n);in.position(in.position()+n);
    }
    @Override public void close(){items.close();}
}
