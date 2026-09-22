package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;
import java.nio.ByteBuffer;
import java.util.Random;

/** Tests actual wire layouts, control saturation and the generated official catalog. */
public final class AudioSelfTest {
    public static void run() throws Exception {
        testBiomeRegistry();
        VanillaSoundMath.selfTest();
        VanillaSoundCatalog catalog = VanillaSoundCatalog.load();
        require(catalog == VanillaSoundCatalog.load(), "shared metadata");
        require(catalog.registrySize() == 1474 && catalog.assetCount() == 3395
                && catalog.blockStateCount() == 24135, "official catalog counts");
        require("minecraft:entity.allay.ambient_with_item".equals(catalog.eventName(0)), "registry ID zero");
        require("minecraft:stone".equals(catalog.blockName(1)), "block identity");
        require(catalog.blockSound(1).stepEvent.equals("minecraft:block.stone.step"), "stone step");
        require(catalog.choose("minecraft:intentionally_empty", 1) == null, "intentional silence");
        for (int i = 0; i < catalog.registrySize(); i++) {
            String name = catalog.eventName(i);
            VanillaSoundCatalog.Sample a = catalog.choose(name, 73L);
            VanillaSoundCatalog.Sample b = catalog.choose(name, new Random(73L));
            require((a == null) == (b == null), "seeded event silence");
            if (a != null) require(a.sha1.equals(b.sha1) && a.volume == b.volume
                    && a.pitch == b.pitch && a.encodedSize > 0, "deterministic sample");
        }
        SoundEventQueue queue = new SoundEventQueue(2);
        SoundEventQueue.Event event = new SoundEventQueue.Event();
        ByteBuffer packet = ByteBuffer.allocate(2048);
        VarInts.write(packet, 1); VarInts.write(packet, 7);
        packet.putInt(-17).putInt(515).putInt(80).putFloat(3).putFloat(0.7f).putLong(123);
        packet.flip(); SoundPacketDecoder.readSound(packet, queue);
        require(queue.poll(event) && event.soundId == 0 && event.x == -2.125
                && event.y == 64.375 && event.volume == 3 && event.seed == 123, "position sound wire");
        packet.clear(); VarInts.write(packet, 0);
        BinaryCodec.writeString(packet, "minecraft:block.stone.break", 1024);
        packet.put((byte) 1).putFloat(32); VarInts.write(packet, 4); VarInts.write(packet, 19);
        packet.putFloat(1).putFloat(2).putLong(-900); packet.flip();
        SoundPacketDecoder.readEntitySound(packet, queue);
        require(queue.poll(event) && event.entityId == 19 && event.fixedRange == 32
                && event.soundName.equals("minecraft:block.stone.break"), "direct entity holder");
        for (int flags = 0; flags < 4; flags++) {
            packet.clear(); packet.put((byte) flags);
            if ((flags & 1) != 0) VarInts.write(packet, 4);
            if ((flags & 2) != 0) BinaryCodec.writeString(packet, "block.stone.break", 1024);
            packet.flip(); SoundPacketDecoder.readStopSound(packet, queue);
            require(queue.poll(event) && event.kind == SoundEventQueue.STOP
                    && event.category == ((flags & 1) != 0 ? 4 : -1)
                    && ((event.soundName != null) == ((flags & 2) != 0)), "stop flags");
        }
        queue.play("a", 4, 0, 0, 0, 1, 1, 0);
        queue.play("b", 4, 0, 0, 0, 1, 1, 0);
        require(!queue.play("c", 4, 0, 0, 0, 1, 1, 0), "play backpressure");
        queue.stop(null, -1);
        require(queue.size() == 2, "bounded stop insertion");
        require(queue.poll(event) && queue.poll(event) && event.kind == SoundEventQueue.STOP,
                "stop survives full queue");
        long generation = queue.generation(); queue.clear();
        require(queue.generation() > generation && queue.size() == 0, "world invalidation");
        packet.clear(); packet.put((byte) 4).flip();
        try { SoundPacketDecoder.readStopSound(packet, queue); throw new AssertionError("invalid flags accepted"); }
        catch (ProtocolException expected) { }
        SoundOptions options = new SoundOptions();
        options.readLine("soundCategory_master:0.25"); options.readLine("soundCategory_block:0.5");
        options.readLine("soundCategory_music:NaN");
        require(options.master() == 0.25f && options.categories()[0] == 1
                && options.categories()[4] == 0.5f && options.categories()[1] == 1, "vanilla sliders");
        System.out.println("NH audio self-test passed: catalog, seeds, PCM, spatial math, packets, queue, sliders");
    }
    private static void require(boolean test, String label) {
        if (!test) throw new AssertionError(label);
    }

    private static void testBiomeRegistry() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        tag(out, 10, "");
        tag(out, 10, "minecraft:worldgen/biome");
        tag(out, 9, "value"); out.writeByte(10); out.writeInt(1);
        tag(out, 3, "id"); out.writeInt(719); // Server IDs are not assumed to be vanilla order.
        tag(out, 8, "name"); out.writeUTF("test:biome");
        tag(out, 10, "element");
        tag(out, 1, "has_precipitation"); out.writeByte(1);
        tag(out, 5, "temperature"); out.writeFloat(0.7f);
        tag(out,5,"downfall");out.writeFloat(.6f);
        tag(out, 10, "effects");
        tag(out,3,"grass_color");out.writeInt(0x123456);
        tag(out,3,"foliage_color");out.writeInt(0x234567);
        tag(out,3,"water_color");out.writeInt(0x345678);
        tag(out,8,"grass_color_modifier");out.writeUTF("dark_forest");
        tag(out, 8, "ambient_sound"); out.writeUTF("minecraft:ambient.basalt_deltas.loop");
        tag(out, 10, "music");
        tag(out, 8, "sound"); out.writeUTF("minecraft:music.game");
        tag(out, 3, "min_delay"); out.writeInt(100);
        tag(out, 3, "max_delay"); out.writeInt(200);
        tag(out, 1, "replace_current_music"); out.writeByte(1);
        out.writeByte(0); // music
        tag(out, 10, "mood_sound");
        tag(out, 8, "sound"); out.writeUTF("minecraft:ambient.cave");
        tag(out, 3, "tick_delay"); out.writeInt(6000);
        tag(out, 6, "offset"); out.writeDouble(2);
        out.writeByte(0); // mood
        out.writeByte(0); // effects
        out.writeByte(0); // element
        out.writeByte(0); // biome entry
        out.writeByte(0); // biome registry
        tag(out, 10, "minecraft:damage_type");
        tag(out, 9, "value"); out.writeByte(10); out.writeInt(1);
        tag(out, 3, "id"); out.writeInt(5);
        tag(out, 10, "element");
        tag(out, 8, "effects"); out.writeUTF("burning");
        out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(0);
        out.writeInt(0x12345678); // Next Join Game field must not be consumed.
        ByteBuffer input = ByteBuffer.wrap(bytes.toByteArray());
        BiomeSoundRegistry registry = new BiomeSoundRegistry(); registry.read(input);
        BiomeSoundRegistry.Biome biome = registry.get(719);
        require(biome != null && biome.precipitation && biome.minDelay == 100
                && biome.maxDelay == 200 && biome.replaceMusic
                && "minecraft:ambient.cave".equals(biome.mood), "biome NBT audio fields");
        require(biome.grassColor==0x123456&&biome.foliageColor==0x234567&&biome.waterColor==0x345678
                && Math.abs(biome.downfall-.6f)<1e-6&&"dark_forest".equals(biome.grassModifier)&&registry.revision()==1,"biome texture colors use server registry scalars");
        require("burning".equals(registry.damageEffect(5)) && input.getInt() == 0x12345678
                && !input.hasRemaining(), "registry boundaries and damage effects");
        require(registry.get(-1) == null && registry.get(1024) == null, "bounded biome lookup");
    }

    private static void tag(java.io.DataOutputStream out, int type, String name) throws java.io.IOException {
        out.writeByte(type); out.writeUTF(name);
    }
}
