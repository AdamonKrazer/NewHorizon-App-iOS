package com.newhorizon.thinclient;

import com.newhorizon.thinclient.display.BrowserPort;
import com.newhorizon.thinclient.display.DisplayController;
import com.newhorizon.thinclient.memory.BoundedBufferPool;
import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;
import com.newhorizon.thinclient.protocol.FramedPacketDecoder;
import com.newhorizon.thinclient.inventory.InventoryState;
import com.newhorizon.thinclient.world.WorldChunkStore;
import com.newhorizon.thinclient.world.EntityTracker;
import com.newhorizon.thinclient.world.EnvironmentState;
import com.newhorizon.thinclient.audio.SoundEventQueue;

/** Owns every long-lived allocation of the thin client core. */
public final class ThinClientRuntime implements AutoCloseable {
    public static final int PROTOCOL_1_20_1 = 763;
    public static final int MAX_PACKET_BYTES = 2 * 1024 * 1024;

    public final MemoryBudget budget;
    public final BoundedBufferPool packetBuffers;
    public final FramedPacketDecoder packetDecoder;
    public final DisplayController displays;
    public final WorldChunkStore world;
    public final EntityTracker entities;
    public final EnvironmentState environment;
    public final com.newhorizon.thinclient.world.RidingState riding=new com.newhorizon.thinclient.world.RidingState();
    public final com.newhorizon.thinclient.world.PlayerEffects effects =
            new com.newhorizon.thinclient.world.PlayerEffects();
    public final com.newhorizon.thinclient.world.CombatState combat =
            new com.newhorizon.thinclient.world.CombatState();
    public final InventoryState inventory;
    public final com.newhorizon.thinclient.world.PlayerListState players=new com.newhorizon.thinclient.world.PlayerListState();
    public final com.newhorizon.thinclient.world.PlayerSkins playerSkins=new com.newhorizon.thinclient.world.PlayerSkins();
    public final com.newhorizon.thinclient.world.ServerHudState hud=new com.newhorizon.thinclient.world.ServerHudState();
    public final com.newhorizon.thinclient.world.DebugNetworkState debug=new com.newhorizon.thinclient.world.DebugNetworkState();
    public final SoundEventQueue sounds;
    public final com.newhorizon.thinclient.inventory.ItemUseState itemUse =
            new com.newhorizon.thinclient.inventory.ItemUseState();
    public final com.newhorizon.thinclient.audio.BiomeSoundRegistry soundBiomes =
            new com.newhorizon.thinclient.audio.BiomeSoundRegistry();

    public ThinClientRuntime(BrowserPort browserPort) {
        budget = MemoryBudget.lowRamDefaults();
        packetBuffers = new BoundedBufferPool(
                budget, MemoryCategory.NETWORK, 256 * 1024, 8);
        packetDecoder = new FramedPacketDecoder(budget, MAX_PACKET_BYTES);
        displays = new DisplayController(budget, browserPort, 8, 32.0, 48.0);
        // Complete vanilla view-distance 4 footprint (9 x 9 chunks).
        world = new WorldChunkStore(budget, 9 * 9, 4_000, 4);
        entities = new EntityTracker(256);
        entities.setWorld(world);
        entities.setRiding(riding);
        environment = new EnvironmentState();
        inventory = new InventoryState();
        sounds = new SoundEventQueue(256);
        entities.fireworks.setSounds(sounds);
    }

    @Override
    public void close() {
        playerSkins.close();players.clear();
        sounds.clear();
        displays.close();
        world.close();
        entities.clear();
        packetDecoder.close();
        packetBuffers.close();
    }
}
