package com.newhorizon.thinclient;

import com.newhorizon.thinclient.display.BrowserPort;
import com.newhorizon.thinclient.display.DisplayController;
import com.newhorizon.thinclient.display.DisplayMessage;
import com.newhorizon.thinclient.display.DisplayProtocol;
import com.newhorizon.thinclient.display.DisplaySide;
import com.newhorizon.thinclient.display.VirtualDisplay;
import com.newhorizon.thinclient.display.WebDisplaysPacketDecoder;
import com.newhorizon.thinclient.memory.BoundedBufferPool;
import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;
import com.newhorizon.thinclient.protocol.FramedPacketDecoder;
import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.MinecraftFrameCodec;
import com.newhorizon.thinclient.protocol.MinecraftPackets;
import com.newhorizon.thinclient.protocol.VarInts;
import com.newhorizon.thinclient.world.BlockStatePhysics;
import com.newhorizon.thinclient.world.BlockStateRender;
import com.newhorizon.thinclient.world.SurfaceChunk;
import com.newhorizon.thinclient.world.WorldChunkStore;
import com.newhorizon.thinclient.world.WorldMessage;
import com.newhorizon.thinclient.world.WorldProtocol;
import com.newhorizon.thinclient.world.EntityTracker;
import com.newhorizon.thinclient.world.EnvironmentState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ThinClientSelfTest {
    private ThinClientSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        com.newhorizon.thinclient.audio.AudioSelfTest.run();
        testBudgetAndPool();
        testDisplayProtocolAndLifecycle();
        testWebDisplaysPacketDecoder();
        testFragmentedPacketFrames();
        testMinecraftPackets();
        com.newhorizon.thinclient.protocol.ChatProtocolSelfTest.run();
        com.newhorizon.thinclient.display.MinePadSelfTest.run();
        com.newhorizon.thinclient.display.MinePadResourcesSelfTest.run();
        com.newhorizon.thinclient.world.HudCameraSelfTest.run();
        com.newhorizon.thinclient.render.DebugOverlaySelfTest.run();
        testEntityTracker();
        com.newhorizon.thinclient.world.ProjectileSelfTest.run();
        com.newhorizon.thinclient.world.EffectsSelfTest.run();
        com.newhorizon.thinclient.world.CombatSelfTest.run();
        com.newhorizon.thinclient.inventory.ConsumableUseSelfTest.run();
        com.newhorizon.thinclient.world.RidingSelfTest.run();
        com.newhorizon.thinclient.world.RailsFireworksSelfTest.run();
        com.newhorizon.thinclient.world.FishingLeadsSelfTest.run();
        com.newhorizon.thinclient.world.TerrainTextureSelfTest.run();
        com.newhorizon.thinclient.world.WorldChunkNeighborsSelfTest.run();
        com.newhorizon.thinclient.world.ChunkStreamingSelfTest.run();
        com.newhorizon.thinclient.render.MeshMemorySelfTest.run();
        com.newhorizon.thinclient.world.FluidSurfaceSelfTest.run();
        com.newhorizon.thinclient.world.VanillaBlockTexturesSelfTest.run();
        com.newhorizon.thinclient.render.VanillaItemTexturesSelfTest.run();
        com.newhorizon.thinclient.render.EntityTextureSelfTest.run();
        com.newhorizon.thinclient.render.MobModelSelfTest.run();
        com.newhorizon.thinclient.render.ClimbingPlantSelfTest.run();
        com.newhorizon.thinclient.render.ChickenPoseSelfTest.run();
        com.newhorizon.thinclient.world.ChickenBodyRotationSelfTest.run();
        com.newhorizon.thinclient.world.EntityAnimationSelfTest.run();
        com.newhorizon.thinclient.world.SwimmingSelfTest.run();
        com.newhorizon.thinclient.render.EntityModelAnimationSelfTest.run();
        com.newhorizon.thinclient.world.WorldParticlesSelfTest.run();
        com.newhorizon.thinclient.world.AtmosphereSelfTest.run();
        testEnvironmentState();
        testMinecraftCompression();
        testBoundedWorldStream();
        System.out.println("NH thin-client self-test passed");
    }

    private static void testBudgetAndPool() {
        MemoryBudget budget = MemoryBudget.lowRamDefaults();
        MemoryBudget.Lease first = budget.reserve(MemoryCategory.TRANSIENT, 1024);
        require(budget.used(MemoryCategory.TRANSIENT) == 1024, "budget reserve");
        first.close();
        require(budget.used(MemoryCategory.TRANSIENT) == 0, "budget release");

        BoundedBufferPool pool = new BoundedBufferPool(
                budget, MemoryCategory.NETWORK, 4096, 2);
        ByteBuffer a = pool.acquire();
        ByteBuffer b = pool.acquire();
        require(a != null && b != null, "pool allocation");
        require(pool.acquire() == null, "pool backpressure");
        pool.release(a);
        require(pool.acquire() == a, "pool reuse");
        pool.release(a);
        pool.release(b);
        pool.close();
    }

    private static void testDisplayProtocolAndLifecycle() throws Exception {
        UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        VirtualDisplay source = new VirtualDisplay(id, "minecraft:overworld",
                12.5, 64.0, -3.25, 90f, 2f, 1.5f, DisplaySide.NORTH,
                0, 352, 224, "https://example.invalid/video");
        ByteBuffer encoded = ByteBuffer.allocate(8192);
        DisplayProtocol.encode(encoded, new DisplayMessage.Spawn(source));
        encoded.flip();
        DisplayMessage decoded = DisplayProtocol.decode(encoded);
        require(decoded instanceof DisplayMessage.Spawn, "display spawn type");
        VirtualDisplay target = ((DisplayMessage.Spawn) decoded).display;
        require(source.id.equals(target.id), "display id");
        require(source.url.equals(target.url), "display url");
        require(source.pixelWidth == target.pixelWidth
                && source.pixelHeight == target.pixelHeight, "display resolution");

        FakeBrowserPort browser = new FakeBrowserPort();
        MemoryBudget budget = MemoryBudget.lowRamDefaults();
        DisplayController controller = new DisplayController(budget, browser, 4, 32, 48);
        require(controller.handle(decoded), "display admitted");
        require(controller.size() == 1 && browser.created == 1, "browser created");
        controller.updatePlayer("minecraft:overworld", 12.5, 64, -3.25);
        require(browser.destroyed == 0, "browser retained in radius");
        controller.updatePlayer("minecraft:overworld", 200, 64, 200);
        require(browser.destroyed == 1, "browser released outside radius");
        controller.updatePlayer("minecraft:overworld", 12.5, 64, -3.25);
        require(browser.created == 2, "browser recreated on demand");
        controller.close();
    }

    private static void testFragmentedPacketFrames() throws Exception {
        MemoryBudget budget = MemoryBudget.lowRamDefaults();
        FramedPacketDecoder decoder = new FramedPacketDecoder(budget, 1024);
        ByteBuffer frame = ByteBuffer.allocate(32);
        VarInts.write(frame, 5);
        frame.put(new byte[]{1, 2, 3, 4, 5});
        frame.flip();
        final int[] packets = {0};
        while (frame.hasRemaining()) {
            ByteBuffer one = frame.slice();
            one.limit(1);
            decoder.feed(one, packet -> {
                require(packet.remaining() == 5, "frame length");
                require(packet.get() == 1 && packet.get() == 2 && packet.get() == 3
                        && packet.get() == 4 && packet.get() == 5, "frame payload");
                packets[0]++;
            });
            frame.position(frame.position() + 1);
        }
        require(packets[0] == 1, "fragmented frame count");
        decoder.close();
    }

    private static void testWebDisplaysPacketDecoder() throws Exception {
        UUID id = UUID.fromString("6aa03ef1-e68a-4d56-9c44-ff1c448c65f1");
        ByteBuffer packet = ByteBuffer.allocate(1024);
        VarInts.write(packet, WebDisplaysPacketDecoder.SPAWN_DISCRIMINATOR);
        BinaryCodec.writeUuid(packet, id);
        BinaryCodec.writeString(packet, "minecraft:overworld", 128);
        packet.putDouble(3.5).putDouble(65.025).putDouble(-8.5);
        packet.putFloat(0f).putFloat(3f).putFloat(2.5f);
        packet.put((byte) DisplaySide.TOP.ordinal());
        VarInts.write(packet, 900);
        packet.put((byte) 10).putShort((short) 0); // unnamed compound root
        writeNbtInt(packet, "ResolutionX", 300);
        writeNbtInt(packet, "ResolutionY", 250);
        packet.put((byte) 1);
        writeNbtString(packet, "UseRegLinks");
        packet.put((byte) 1);
        packet.put((byte) 9);
        writeNbtString(packet, "RegLinks");
        packet.put((byte) 8).putInt(1);
        writeNbtString(packet, "https://example.invalid/registered");
        packet.put((byte) 8);
        writeNbtString(packet, "URL");
        writeNbtString(packet, "https://example.invalid/video");
        packet.put((byte) 0);
        packet.flip();

        DisplayMessage message = WebDisplaysPacketDecoder.decode(packet);
        require(message instanceof DisplayMessage.Spawn, "WebDisplays spawn type");
        VirtualDisplay display = ((DisplayMessage.Spawn) message).display;
        require(id.equals(display.id), "WebDisplays display id");
        require(display.pixelWidth == 300 && display.pixelHeight == 250,
                "WebDisplays display resolution");
        require("https://example.invalid/registered".equals(display.url),
                "WebDisplays registered display URL");

        packet.clear();
        VarInts.write(packet, WebDisplaysPacketDecoder.REMOVE_DISCRIMINATOR);
        BinaryCodec.writeUuid(packet, id);
        packet.flip();
        message = WebDisplaysPacketDecoder.decode(packet);
        require(message instanceof DisplayMessage.Remove
                && id.equals(((DisplayMessage.Remove) message).id),
                "WebDisplays remove");
    }

    private static void writeNbtInt(ByteBuffer output, String name, int value) {
        output.put((byte) 3);
        writeNbtString(output, name);
        output.putInt(value);
    }

    private static void writeNbtString(ByteBuffer output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.putShort((short) bytes.length).put(bytes);
    }

    private static void testMinecraftPackets() throws Exception {
        ByteBuffer handshake = ByteBuffer.allocate(512);
        MinecraftPackets.writeHandshake(handshake, "127.0.0.1", 25565);
        handshake.flip();
        require(VarInts.read(handshake) == 0, "handshake id");
        require(VarInts.read(handshake) == 763, "protocol version");
        require("127.0.0.1".equals(BinaryCodec.readString(handshake, 255)),
                "handshake host");
        require((handshake.getShort() & 0xffff) == 25565, "handshake port");
        require(VarInts.read(handshake) == 2 && !handshake.hasRemaining(),
                "handshake login intention");

        UUID profile = UUID.fromString("de305d54-75b4-431b-adb2-eb6b9e546014");
        ByteBuffer login = ByteBuffer.allocate(128);
        MinecraftPackets.writeLoginStart(login, "MinePad", profile);
        login.flip();
        require(VarInts.read(login) == 0, "login start id");
        require("MinePad".equals(BinaryCodec.readString(login, 16)), "login username");
        require(login.get() == 1 && profile.equals(BinaryCodec.readUuid(login))
                && !login.hasRemaining(), "login profile id");

        ByteBuffer command = ByteBuffer.allocate(16);
        MinecraftPackets.writePlayerCommand(command, 267, 3);
        command.flip();
        require(VarInts.read(command) == 0x1e
                        && VarInts.read(command) == 267
                        && VarInts.read(command) == 3
                        && VarInts.read(command) == 0
                        && !command.hasRemaining(),
                "vanilla sprint command");

        ByteBuffer abilities = ByteBuffer.allocate(8);
        MinecraftPackets.writePlayerAbilities(abilities, true);
        abilities.flip();
        require(VarInts.read(abilities) == 0x1c
                        && (abilities.get() & 0xff) == 0x02
                        && !abilities.hasRemaining(),
                "vanilla flying abilities");

        ByteBuffer attack = ByteBuffer.allocate(16);
        MinecraftPackets.writeAttackEntity(attack, 321, true);
        attack.flip();
        require(VarInts.read(attack) == 0x10
                        && VarInts.read(attack) == 321
                        && VarInts.read(attack) == 1
                        && attack.get() == 1 && !attack.hasRemaining(),
                "vanilla entity attack");

        ByteBuffer interact = ByteBuffer.allocate(16);
        MinecraftPackets.writeInteractEntity(interact, 322, false);
        interact.flip();
        require(VarInts.read(interact) == 0x10
                        && VarInts.read(interact) == 322
                        && VarInts.read(interact) == 0
                        && VarInts.read(interact) == 0
                        && interact.get() == 0 && !interact.hasRemaining(),
                "vanilla entity interaction");

        ByteBuffer action = ByteBuffer.allocate(32);
        MinecraftPackets.writePlayerAction(action, 0, -12, 70, 31, 2, 7);
        action.flip();
        require(VarInts.read(action) == 0x1d
                        && VarInts.read(action) == 0
                        && action.getLong() == (((long) -12 & 0x3ffffffL) << 38
                        | (31L << 12) | 70L)
                        && (action.get() & 0xff) == 2
                        && VarInts.read(action) == 7 && !action.hasRemaining(),
                "vanilla block action");

        ByteBuffer useOn = ByteBuffer.allocate(64);
        MinecraftPackets.writeUseItemOn(useOn, 4, 65, -9, 1,
                0.25f, 1.0f, 0.75f, false, 8);
        useOn.flip();
        require(VarInts.read(useOn) == 0x31
                        && VarInts.read(useOn) == 0
                        && useOn.getLong() == ((4L << 38)
                        | ((long) -9 & 0x3ffffffL) << 12 | 65L)
                        && VarInts.read(useOn) == 1
                        && useOn.getFloat() == 0.25f
                        && useOn.getFloat() == 1.0f
                        && useOn.getFloat() == 0.75f
                        && useOn.get() == 0
                        && VarInts.read(useOn) == 8 && !useOn.hasRemaining(),
                "vanilla block use");

        ByteBuffer use = ByteBuffer.allocate(8);
        MinecraftPackets.writeUseItem(use, 9);
        use.flip();
        require(VarInts.read(use) == 0x32 && VarInts.read(use) == 0
                        && VarInts.read(use) == 9 && !use.hasRemaining(),
                "vanilla item use");

        ByteBuffer swing = ByteBuffer.allocate(8);
        MinecraftPackets.writeSwing(swing);
        swing.flip();
        require(VarInts.read(swing) == 0x2f && VarInts.read(swing) == 0
                        && !swing.hasRemaining(), "vanilla hand swing");

        ByteBuffer creative = ByteBuffer.allocate(16);
        MinecraftPackets.writeSetCreativeModeSlot(creative, 38, 1062, 1);
        creative.flip();
        require(VarInts.read(creative) == 0x2b
                        && (creative.getShort() & 0xffff) == 38
                        && creative.get() == 1
                        && VarInts.read(creative) == 1062
                        && creative.get() == 1
                        && creative.get() == 0
                        && !creative.hasRemaining(),
                "vanilla creative slot");

        byte[] nbt = {10, 0, 0, 0}; // empty named CompoundTag encoded by NbtIo
        ByteBuffer creativeVariant = ByteBuffer.allocate(24);
        MinecraftPackets.writeSetCreativeModeSlot(
                creativeVariant, -1, 1066, 3, nbt);
        creativeVariant.flip();
        require(VarInts.read(creativeVariant) == 0x2b
                        && creativeVariant.getShort() == -1
                        && creativeVariant.get() == 1
                        && VarInts.read(creativeVariant) == 1066
                        && creativeVariant.get() == 3
                        && creativeVariant.get() == 10
                        && creativeVariant.get() == 0
                        && creativeVariant.get() == 0
                        && creativeVariant.get() == 0
                        && !creativeVariant.hasRemaining(),
                "vanilla tagged creative slot/drop");
    }

    private static void testEntityTracker() throws Exception {
        EntityTracker tracker = new EntityTracker(4);
        ByteBuffer addPlayer = ByteBuffer.allocate(64);
        VarInts.write(addPlayer, 44);
        BinaryCodec.writeUuid(addPlayer,
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
        addPlayer.putDouble(0.0).putDouble(64.0).putDouble(3.0);
        addPlayer.put((byte) 0).put((byte) 0).flip();
        tracker.addPlayer(addPlayer);

        EntityTracker.Hit hit = new EntityTracker.Hit();
        tracker.findRayHit(0.0, 65.62, 0.0,
                0.0, 0.0, 1.0, 4.5, -1, hit);
        require(hit.entityId == 44 && hit.distance > 2.0 && hit.distance < 3.0,
                "vanilla entity ray pick");

        ByteBuffer move = ByteBuffer.allocate(16);
        VarInts.write(move, 44);
        move.putShort((short) 4096).putShort((short) 0).putShort((short) 0);
        move.put((byte) 1).flip();
        tracker.moveEntity(move, false);
        tracker.findRayHit(0.0, 65.62, 0.0,
                0.0, 0.0, 1.0, 4.5, -1, hit, System.nanoTime()+100_000_000L);
        require(hit.entityId == -1, "moved entity leaves ray");
    }

    private static void testEnvironmentState() throws Exception {
        EnvironmentState environment = new EnvironmentState();
        ByteBuffer time = ByteBuffer.allocate(16)
                .putLong(100L).putLong(-18_000L);
        time.flip();
        environment.readTime(time);
        ByteBuffer rain = ByteBuffer.allocate(5)
                .put((byte) 7).putFloat(0.75f);
        rain.flip();
        environment.readGameEvent(rain);
        ByteBuffer thunder = ByteBuffer.allocate(5)
                .put((byte) 8).putFloat(0.40f);
        thunder.flip();
        environment.readGameEvent(thunder);
        EnvironmentState.Snapshot snapshot = new EnvironmentState.Snapshot();
        environment.sample(System.nanoTime(), snapshot);
        require(snapshot.dayTime == 18_000L, "frozen vanilla day time");
        require(Math.abs(snapshot.rain - 0.75f) < 0.001f
                        && Math.abs(snapshot.thunder - 0.40f) < 0.001f,
                "vanilla weather levels");
    }

    private static void testMinecraftCompression() throws Exception {
        MemoryBudget budget = MemoryBudget.lowRamDefaults();
        MinecraftFrameCodec codec = new MinecraftFrameCodec(budget, 1024);
        codec.enableCompression(64);

        ByteBuffer source = ByteBuffer.allocate(300);
        for (int index = 0; index < source.capacity(); index++) {
            source.put((byte) (index % 7));
        }
        source.flip();
        ByteBuffer wire = ByteBuffer.allocate(2048);
        codec.encode(wire, source);
        wire.flip();
        int frameBytes = VarInts.read(wire);
        ByteBuffer frame = wire.slice();
        frame.limit(frameBytes);
        ByteBuffer decoded = codec.decode(frame);
        require(decoded.remaining() == source.remaining(), "compressed packet size");
        while (source.hasRemaining()) {
            require(source.get() == decoded.get(), "compressed packet contents");
        }

        ByteBuffer small = ByteBuffer.wrap(new byte[]{9, 8, 7});
        wire.clear();
        codec.encode(wire, small);
        wire.flip();
        frameBytes = VarInts.read(wire);
        frame = wire.slice();
        frame.limit(frameBytes);
        decoded = codec.decode(frame);
        require(decoded.remaining() == 3 && decoded.get() == 9 && decoded.get() == 8
                && decoded.get() == 7, "uncompressed packet below threshold");
        codec.close();
    }

    private static void testBoundedWorldStream() throws Exception {
        require(BlockStateRender.style(2005) == BlockStateRender.FOLIAGE
                        && BlockStateRender.style(25) == BlockStateRender.FOLIAGE,
                "official grass and sapling render classification");
        require(BlockStateRender.style(1) == BlockStateRender.CUBE,
                "ordinary solid block remains cube-rendered");
        require((BlockStatePhysics.flags(80) & BlockStatePhysics.WATER) != 0,
                "vanilla water state traits");
        require(!BlockStatePhysics.blocksMovement(80),
                "water does not block player movement");
        require(BlockStatePhysics.fullyOccludesUnitCube(1),
                "full vanilla block occludes neighbouring faces");
        require(!BlockStatePhysics.fullyOccludesUnitCube(80)
                        && !BlockStatePhysics.fullyOccludesUnitCube(11023),
                "water and half slab cannot erase neighbouring terrain");
        require((BlockStatePhysics.flags(2004) & BlockStatePhysics.COBWEB) != 0,
                "vanilla cobweb state traits");
        require(!BlockStatePhysics.blocksMovement(2004),
                "cobweb permits slow passage");
        require(BlockStatePhysics.fluidAmount(80) == 8
                        && BlockStatePhysics.fluidAmount(87) == 1,
                "vanilla flowing-fluid levels");
        require(BlockStatePhysics.intersectsCollision(11023, 0, 0, 0,
                        0.2, 0.2, 0.2, 0.8, 0.3, 0.8),
                "bottom slab lower collision shape");
        require(!BlockStatePhysics.intersectsCollision(11023, 0, 0, 0,
                        0.2, 0.7, 0.2, 0.8, 0.8, 0.8),
                "bottom slab leaves upper half open");
        require(BlockStatePhysics.intersectsCollision(11021, 0, 0, 0,
                        0.2, 0.7, 0.2, 0.8, 0.8, 0.8),
                "top slab upper collision shape");
        require(BlockStatePhysics.intersectsCollision(4655, 0, 0, 0,
                        0.2, 0.2, 0.90, 0.8, 0.8, 0.95)
                        && !BlockStatePhysics.intersectsCollision(4655, 0, 0, 0,
                        0.2, 0.2, 0.40, 0.8, 0.8, 0.60),
                "north ladder uses its thin vanilla collision plane");
        MemoryBudget budget = MemoryBudget.lowRamDefaults();
        WorldChunkStore store = new WorldChunkStore(budget, 2, 4);
        store.handle(new WorldMessage.Reset("minecraft:overworld", -64, 384));
        store.handle(new WorldMessage.PlayerPosition(1, 64, 1, 0, 0));
        require(store.teleportRevision() == 0,
                "bridge position is not an authoritative teleport");

        putTestChunk(store, 0, 0, 1, 0xff334455);
        store.applyVanillaChunkData(0, 0, fullDepthTestChunk());
        require(store.hasFullBlockData(3, 15, 5), "vanilla full chunk retained");
        store.applyVanillaChunkAuxData(0, 0, zeroSkyLightChunkAux());
        require(store.chunkAt(0).combinedLight(0, 0, 0) == 0,
                "vanilla light payload retained");
        int fullChunkCount = store.size();
        store.handle(new WorldMessage.Reset("minecraft:overworld", -64, 384));
        require(store.size() == fullChunkCount,
                "same-world bridge reset preserves vanilla chunks");
        require(store.isSolidBlock(3, 16, 5), "solid collision index");
        com.newhorizon.thinclient.world.BoatMotion boatCollision=new com.newhorizon.thinclient.world.BoatMotion();
        boatCollision.reset(1,16.1,5.5,0,0);boatCollision.vx=4;boatCollision.move(store,.6875,.5625,0);
        require(Math.abs(boatCollision.x-2.3125)<1e-6 && boatCollision.vx==0,"boat hull clips vanilla wall shape");
        boatCollision.reset(3.5,17.1,5.5,0,0);boatCollision.vy=-.2;boatCollision.move(store,.6875,.5625,0);
        require(Math.abs(boatCollision.y-17)<1e-6 && boatCollision.onGround,"boat lands on vanilla collision shape");
        require(Math.abs(store.projectileClip(1,16.5,5.5,8,0,0)-.25)<1e-6,
                "fast projectile clips exact vanilla block shape without tunnelling");
        require(store.isTargetableBlock(3, 16, 5), "targetable block index");
        require(!store.isSolidBlock(4, 16, 5), "empty collision index");
        require(store.applyVanillaBlockUpdate(3, 16, 5, 0),
                "vanilla air update removes surface cell");
        require(!store.isSolidBlock(3, 16, 5),
                "vanilla air update removes collision");
        require(!store.isTargetableBlock(3, 16, 5),
                "vanilla air update removes target");
        require(store.isTargetableBlock(3, 15, 5),
                "vanilla air update reveals retained block below");
        putTestChunk(store, 0, 0, 2, 0xff334455);
        putTestChunk(store, 10, 0, 1, 0xff667788);
        require(store.size() == 2, "world slots filled");
        putTestChunk(store, 1, 0, 1, 0xff99aabb);
        require(store.size() == 2, "world slots bounded");
        boolean originPresent = false;
        boolean nearPresent = false;
        for (int index = 0; index < store.size(); index++) {
            SurfaceChunk chunk = store.chunkAt(index);
            originPresent |= chunk.chunkX == 0;
            nearPresent |= chunk.chunkX == 1;
        }
        require(originPresent && nearPresent, "farthest chunk evicted");
        store.handle(new WorldMessage.Reset("minecraft:the_nether", 0, 256));
        store.handle(new WorldMessage.Reset("minecraft:overworld", -64, 384));
        putTestChunk(store, 0, 0, 2, 0x3f76e4ff);
        require(!store.isSolidBlock(3, 16, 5), "water is not solid");
        require(!store.isTargetableBlock(3, 16, 5),
                "ordinary vanilla targeting ignores fluids");
        require(budget.used(MemoryCategory.WORLD) == 2L
                        * (4 * SurfaceChunk.RECORD_BYTES + SurfaceChunk.FULL_BLOCK_BYTES
                        + SurfaceChunk.BIOME_BYTES + SurfaceChunk.LIGHT_BYTES
                        + SurfaceChunk.BLOCK_ENTITY_BYTES),
                "world allocation ceiling");
        store.applyServerTeleport(2, 65, 2, 10, 5, 0);
        require(store.teleportRevision() == 1,
                "Minecraft teleport revision");
        store.close();

        MemoryBudget coldBudget = MemoryBudget.lowRamDefaults();
        WorldChunkStore coldStore = new WorldChunkStore(coldBudget, 1, 4, 0);
        coldStore.handle(new WorldMessage.Reset("minecraft:overworld", -64, 384));
        coldStore.applyVanillaChunkData(2, -3, fullDepthTestChunk());
        coldStore.applyVanillaChunkAuxData(2, -3, zeroSkyLightChunkAux());
        require(!coldStore.hasFullBlockData(35, 15, -43),
                "cold chunk stays off heap before player position");
        coldStore.applyVanillaBlockUpdate(35, 16, -43, 0);
        coldStore.handle(new WorldMessage.PlayerPosition(40, 64, -40, 0, 0));
        require(coldStore.hasFullBlockData(35, 15, -43),
                "cold chunk sections promoted around player");
        require(!coldStore.isTargetableBlock(35, 16, -43),
                "cold chunk replays server block updates");
        require(coldStore.chunkAt(0).combinedLight(0, 0, 0) == 0,
                "cold chunk promotes light payload");
        coldStore.close();
    }

    private static void putTestChunk(WorldChunkStore store, int x, int z,
                                     int revision, int color) throws Exception {
        ByteBuffer records = ByteBuffer.allocate(SurfaceChunk.RECORD_BYTES);
        int packed = 3 | (5 << 4) | (80 << 8) | (63 << 17) | (18 << 24);
        if (color != 0x3f76e4ff && color != 0xff6b16ff) {
            packed |= SurfaceChunk.COLLIDABLE_FLAG;
        }
        records.putInt(packed).putInt(color).flip();
        ByteBuffer payload = ByteBuffer.allocate(128);
        WorldProtocol.encodeChunk(payload, x, z, revision, records);
        payload.flip();
        store.handle(WorldProtocol.decode(payload));
    }

    private static ByteBuffer fullDepthTestChunk() {
        ByteBuffer sections = ByteBuffer.allocate(8192);
        for (int section = 0; section < 24; section++) {
            boolean containsTestBlock = section == 4 || section == 5;
            sections.putShort((short) (containsTestBlock ? 1 : 0));
            if (containsTestBlock) {
                sections.put((byte) 4);
                VarInts.write(sections, 2);
                VarInts.write(sections, 0);
                VarInts.write(sections, 1);
                VarInts.write(sections, 256);
                int packedIndex = section == 4 ? (15 << 8) | (5 << 4) | 3
                        : (5 << 4) | 3;
                int selectedWord = packedIndex / 16;
                int selectedShift = (packedIndex % 16) * 4;
                for (int word = 0; word < 256; word++) {
                    sections.putLong(word == selectedWord ? 1L << selectedShift : 0L);
                }
            } else {
                sections.put((byte) 0);
                VarInts.write(sections, 0);
                VarInts.write(sections, 0);
            }
            sections.put((byte) 0);
            VarInts.write(sections, 0);
            VarInts.write(sections, 0);
        }
        sections.flip();
        return sections;
    }

    private static ByteBuffer zeroSkyLightChunkAux() {
        ByteBuffer packet = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        VarInts.write(packet, 0); // block entities
        VarInts.write(packet, 1);
        packet.putLong(1L << 1); // sky mask, first real world section
        VarInts.write(packet, 0); // block mask
        VarInts.write(packet, 0); // empty sky mask
        VarInts.write(packet, 0); // empty block mask
        VarInts.write(packet, 1); // sky arrays
        VarInts.write(packet, SurfaceChunk.LIGHT_SECTION_BYTES);
        for (int index = 0; index < SurfaceChunk.LIGHT_SECTION_BYTES; index++) {
            packet.put((byte) 0);
        }
        VarInts.write(packet, 0); // block arrays
        packet.flip();
        return packet;
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError("Self-test failed: " + label);
    }

    private static final class FakeBrowserPort implements BrowserPort {
        int created;
        int destroyed;

        @Override
        public void create(int browserId, String url, boolean transparent) {
            created++;
        }

        @Override
        public void resize(int browserId, int width, int height) {
        }

        @Override
        public void navigate(int browserId, String url) {
        }

        @Override
        public void setVisible(int browserId, boolean visible) {
        }

        @Override
        public void setFocused(int browserId, boolean focused) {
        }

        @Override
        public void destroy(int browserId) {
            destroyed++;
        }
    }
}
