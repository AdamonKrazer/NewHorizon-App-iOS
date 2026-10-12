package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.MinecraftPackets;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;

public final class DimensionSelfTest {
    public static void run() throws Exception {
        DimensionState dims = new DimensionState();
        ByteBuffer registry = registry(256); dims.readRegistry(registry);
        check(registry.getInt() == 0x12345678 && !registry.hasRemaining(), "registry boundary preserved");
        WorldChunkStore world = new WorldChunkStore(MemoryBudget.lowRamDefaults(), 9, 16, 1);
        EnvironmentState env = new EnvironmentState(); EnvironmentState.Snapshot snapshot = new EnvironmentState.Snapshot();
        long[] versions = new long[28];
        try {
            dims.enter("minecraft:overworld", "minecraft:overworld", world, env, true);
            world.applyServerTeleport(8, 64, 8, 0, 0, 0); dims.teleported();
            check(!dims.ready(world,8,64,8), "no gravity before destination data");
            world.applyVanillaChunkData(0,0,sections(24,1));
            check(world.minY()==-64 && world.height()==384 && world.blockStateAt(8,-64,8)==1, "24 Overworld sections at -64");
            check(dims.ready(world,8,64,8), "destination releases after native teleport and chunk");
            world.applyVanillaChunkWithLight(10,0,sections(24,1),noLight());
            world.applyVanillaBlockUpdate(168,65,8,2); // A cached chunk and pending update in the old world.
            world.copyMeshVersions(versions); long epoch=versions[27];
            env.readGameEvent((ByteBuffer)ByteBuffer.allocate(5).put((byte)7).putFloat(1).flip());
            dims.enter("minecraft:the_nether","minecraft:the_nether",world,env,false);
            world.copyMeshVersions(versions);
            check(versions[27]!=epoch && world.size()==0 && !world.hasPlayerPosition(), "dimension invalidates meshes and old position");
            check(world.minY()==0 && world.height()==256 && dims.effects().equals("minecraft:the_nether"), "Nether geometry/effects");
            check(dims.awaitingTeleport() && !dims.ready(world,8,64,8), "wait for destination teleport");
            world.applyServerTeleport(168,65,8,0,0,0);dims.teleported();world.serviceColdPromotion();
            check(world.size()==0, "old dimension disk cache cannot be promoted");
            world.applyVanillaChunkData(10,0,sections(16,3));
            check(world.blockStateAt(168,65,8)==3, "old block journal cannot overwrite new dimension");
            check(dims.ready(world,168,65,8), "Nether native chunk decoded with 16 sections");
            env.readTime((ByteBuffer)ByteBuffer.allocate(16).putLong(100).putLong(6000).flip());env.sample(System.nanoTime(),snapshot);
            check(snapshot.dayTime==18000 && snapshot.rain==0 && snapshot.thunder==0, "Nether fixed time and weather reset");
            dims.enter("minecraft:the_end","minecraft:the_end",world,env,false);
            world.applyServerTeleport(100.5,49,0.5,90,0,0);dims.teleported();world.applyVanillaChunkData(6,0,sections(16,4));
            check(dims.ready(world,100.5,49,.5) && world.blockStateAt(100,49,0)==4, "End platform destination");
            env.sample(System.nanoTime(),snapshot);check(snapshot.dayTime==6000 && dims.effects().equals("minecraft:the_end"), "End fixed time/effects");
            dims.enter("minecraft:overworld","minecraft:overworld",world,env,false);
            check(world.size()==0 && world.minY()==-64 && world.height()==384, "return resets End chunks and restores Overworld origin");
            env.readTime((ByteBuffer)ByteBuffer.allocate(16).putLong(200).putLong(-18000).flip());env.sample(System.nanoTime(),snapshot);
            check(snapshot.dayTime==18000, "Overworld clock is no longer fixed");
            world.applyServerTeleport(8,64,8,0,0,0);dims.teleported();world.applyVanillaChunkData(0,0,sections(24,1));
            dims.ready(world,8,64,8);world.copyMeshVersions(versions);epoch=versions[27];
            dims.enter("minecraft:overworld","minecraft:overworld",world,env,false);
            world.copyMeshVersions(versions);check(versions[27]==epoch && world.size()==1 && dims.awaitingTeleport(), "same-world respawn retains world but awaits teleport");
            dims.enter("minecraft:the_nether","custom:inferno",world,env,false);
            check(world.dimension().equals("custom:inferno") && dims.effects().equals("minecraft:the_nether"), "world key independent from effects type");
            ByteBuffer bad=registry(1024);
            try {dims.readRegistry(bad);throw new AssertionError("unbounded dimension accepted");}catch(ProtocolException expected){}
            dims.enter("minecraft:the_end","minecraft:the_end",world,env,false); // previous valid registry survives.
            ByteBuffer respawn=ByteBuffer.allocate(200);
            BinaryCodec.writeString(respawn,"minecraft:the_end",32767);BinaryCodec.writeString(respawn,"minecraft:the_end",32767);
            respawn.putLong(12).put((byte)1).put((byte)1).put((byte)0).put((byte)0).put((byte)3).put((byte)0);VarInts.write(respawn,20);respawn.flip();
            MinecraftPackets.Respawn parsed=MinecraftPackets.readRespawn(respawn);
            check(parsed.type.equals("minecraft:the_end") && parsed.dimension.equals("minecraft:the_end") && parsed.flags==0x301 && parsed.portalCooldown==20, "respawn retains destination alongside keep mask");
        } finally {world.close();}
        System.out.println("DimensionSelfTest passed: registry, Overworld/Nether/End round trip, 24/16 sections, cache/mesh isolation, spawn readiness, fixed time, custom keys, respawn");
    }
    private static ByteBuffer sections(int count,int state) {
        ByteBuffer out=ByteBuffer.allocate(1024);
        for(int i=0;i<count;i++){out.putShort((short)4096).put((byte)0);VarInts.write(out,state);VarInts.write(out,0);out.put((byte)0);VarInts.write(out,0);VarInts.write(out,0);}
        return (ByteBuffer)out.flip();
    }
    private static ByteBuffer noLight() {return ByteBuffer.wrap(new byte[]{0,0,0,0,0,0,0});}
    private static ByteBuffer registry(int netherHeight)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        tag(out,10,"");tag(out,10,"minecraft:dimension_type");tag(out,9,"value");out.writeByte(10);out.writeInt(3);
        entry(out,"minecraft:overworld",-64,384,null);entry(out,"minecraft:the_nether",0,netherHeight,18000L);entry(out,"minecraft:the_end",0,256,6000L);
        out.writeByte(0);out.writeByte(0);out.writeInt(0x12345678);return ByteBuffer.wrap(bytes.toByteArray());
    }
    private static void entry(DataOutputStream out,String key,int minY,int height,Long time)throws Exception {
        tag(out,8,"name");out.writeUTF(key);tag(out,10,"element");
        tag(out,3,"min_y");out.writeInt(minY);tag(out,3,"height");out.writeInt(height);
        tag(out,8,"effects");out.writeUTF(key);if(time!=null){tag(out,4,"fixed_time");out.writeLong(time);}
        out.writeByte(0);out.writeByte(0);
    }
    private static void tag(DataOutputStream out,int type,String name)throws Exception{out.writeByte(type);out.writeUTF(name);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
