package com.newhorizon.thinclient.protocol;

import com.newhorizon.thinclient.ThinClientRuntime;
import com.newhorizon.thinclient.audio.SoundPacketDecoder;
import com.newhorizon.thinclient.display.DisplayMessage;
import com.newhorizon.thinclient.display.DisplayProtocol;
import com.newhorizon.thinclient.display.WebDisplaysPacketDecoder;
import com.newhorizon.thinclient.world.WorldMessage;
import com.newhorizon.thinclient.world.WorldProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import com.newhorizon.thinclient.inventory.InventoryProtocol;
import java.security.GeneralSecurityException;

/**
 * Blocking, single-owner Minecraft connection. Every I/O buffer comes from the
 * runtime's fixed pool, so a slow server or a burst cannot create a queue.
 */
public final class MinecraftConnection implements AutoCloseable {
    private static final String MCEF_GATE_CHANNEL = "webdisplays:mcef_gate";
    private static final String MCEF_BUILD_MARKER = "NH-MCEF-2026-02-21";
    private static final String WEBDISPLAYS_CHANNEL = "webdisplays:packetsystem";
    private static final String WEBDISPLAYS_CLIENT_VERSION = "1.3";
    private static final int COMPATIBILITY_HELLO_ATTEMPTS = 6;
    private static final long COMPATIBILITY_HELLO_INTERVAL_NANOS = 1_000_000_000L;
    private final ThinClientRuntime runtime;
    private final MinecraftSessionAuthenticator authenticator;
    private final Listener listener;
    private final MinecraftFrameCodec frameCodec;
    private SocketChannel channel;
    private int playerEntityId = -1;
    private volatile int gameMode;
    public boolean isSpectator() {return gameMode==3;}
    private volatile boolean mayFly;
    private volatile boolean flying;
    private volatile float flyingSpeed = 0.05f;
    private MinecraftStreamCipher streamCipher;
    private State state = State.DISCONNECTED;
    private boolean closed;
    private int compatibilityHelloAttempts;
    private long nextCompatibilityHelloNanos;
    private boolean playHellosSent;
    private int interactionSequence;
    private int vanillaChunkLogCount;
    private final ChatProtocol chat=new ChatProtocol();

    public synchronized boolean sendChat(String text) throws IOException,ProtocolException {
        if(state!=State.PLAY||closed)return false;
        if(ChatProtocol.normalize(text).isEmpty())return false;
        sendPacket(packet->chat.write(packet,text,System.currentTimeMillis(),java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        return true;
    }

    private synchronized void receiveChat(ByteBuffer packet,boolean player) throws IOException,ProtocolException {
        ChatProtocol.Message message=ChatProtocol.read(packet,player);
        chat.accept(message);
        if(message.displayed)com.newhorizon.thinclient.display.GeckoNativeBridge.chatMessage(message.json);
        if(chat.needsAck())sendPacket(chat::writeAck);
    }

    public MinecraftConnection(ThinClientRuntime runtime, Listener listener) {
        this(runtime, listener, new MinecraftSessionAuthenticator());
    }

    MinecraftConnection(ThinClientRuntime runtime, Listener listener,
                        MinecraftSessionAuthenticator authenticator) {
        if (runtime == null || listener == null || authenticator == null) {
            throw new NullPointerException();
        }
        this.runtime = runtime;
        this.listener = listener;
        this.authenticator = authenticator;
        frameCodec = new MinecraftFrameCodec(runtime.budget,
                ThinClientRuntime.MAX_PACKET_BYTES);
    }

    /** Connects and owns the calling thread until disconnect or failure. */
    public void run(String host, int port, SessionCredentials credentials)
            throws IOException, ProtocolException {
        if (closed || state != State.DISCONNECTED) throw new IllegalStateException();
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port");
        channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.socket().setTcpNoDelay(true);
        channel.socket().setKeepAlive(true);
        channel.socket().connect(new InetSocketAddress(host, port), 10_000);

        state = State.LOGIN;
        sendPacket(packet -> MinecraftPackets.writeHandshake(packet, host, port));
        sendPacket(packet -> MinecraftPackets.writeLoginStart(
                packet, credentials.username, credentials.profileId));
        listener.onConnected();

        ByteBuffer network = acquireBuffer();
        try {
            while (!closed) {
                network.clear();
                int count = channel.read(network);
                if (count < 0) throw new EOFException("Server closed the connection");
                if (count == 0) continue;
                network.flip();
                if (streamCipher == null) {
                    runtime.packetDecoder.feed(network,
                            frame -> receiveFrame(frame, credentials));
                } else {
                    ByteBuffer plaintext = acquireBuffer();
                    try {
                        streamCipher.decrypt(network, plaintext);
                        plaintext.flip();
                        runtime.packetDecoder.feed(plaintext,
                                frame -> receiveFrame(frame, credentials));
                    } catch (GeneralSecurityException exception) {
                        throw new ProtocolException("Unable to decrypt network stream", exception);
                    } finally {
                        runtime.packetBuffers.release(plaintext);
                    }
                }
            }
        } finally {
            runtime.packetBuffers.release(network);
            close();
        }
    }

    public synchronized void sendCustomPayload(String channelName, ByteBuffer payload)
            throws IOException, ProtocolException {
        if (state != State.PLAY) throw new IllegalStateException("Not in play state");
        sendPacket(packet -> MinecraftPackets.writeCustomPayload(packet, channelName, payload));
    }

    public synchronized boolean sendMovement(double x, double y, double z,
                                             float yaw, float pitch,
                                             boolean onGround)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed) return false;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Non-finite movement");
        }
        sendPacket(packet -> MinecraftPackets.writeMovePositionRotation(
                packet, x, y, z, yaw, pitch, onGround));
        return true;
    }

    public synchronized boolean sendPlayerCommand(int action)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed || playerEntityId < 0) return false;
        sendPacket(packet -> MinecraftPackets.writePlayerCommand(
                packet, playerEntityId, action));
        return true;
    }

    public synchronized boolean attackEntity(int entityId, boolean secondaryAction)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed || entityId < 0 || isSpectator()) return false;
        sendPacket(packet -> MinecraftPackets.writeAttackEntity(
                packet, entityId, secondaryAction));
        return true;
    }

    public synchronized boolean interactEntity(int entityId,boolean secondaryAction,int hand) throws IOException,ProtocolException {
        if(state!=State.PLAY||closed||entityId<0)return false;
        sendPacket(packet->MinecraftPackets.writeInteractEntity(packet,entityId,secondaryAction,hand));return true;
    }

    public synchronized boolean interactEntity(int entityId, boolean secondaryAction)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed || entityId < 0) return false;
        sendPacket(packet -> MinecraftPackets.writeInteractEntity(
                packet, entityId, secondaryAction));
        return true;
    }

    public synchronized boolean sendPlayerAction(int action,
                                                 int x, int y, int z,
                                                 int direction)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed) return false;
        int sequence = nextInteractionSequence();
        sendPacket(packet -> MinecraftPackets.writePlayerAction(
                packet, action, x, y, z, direction, sequence));
        return true;
    }

    public synchronized boolean swingMainHand() throws IOException, ProtocolException {
        if (state != State.PLAY || closed) return false;
        sendPacket(MinecraftPackets::writeSwing);
        return true;
    }

    public synchronized boolean setCreativeHotbarSlot(int hotbarSlot,
                                                       int protocolItemId, int count)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed || hotbarSlot < 0 || hotbarSlot >= 9) return false;
        return setCreativeSlot(36 + hotbarSlot, protocolItemId, count, "");
    }

    public synchronized boolean setCreativeSlot(int menuSlot, int protocolItemId,
                                                 int count, String nbtHex)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed || menuSlot < -1 || menuSlot > 45) return false;
        byte[] nbt = decodeHex(nbtHex);
        sendPacket(packet -> MinecraftPackets.writeSetCreativeModeSlot(
                packet, menuSlot, protocolItemId, count, nbt));
        return true;
    }

    private static byte[] decodeHex(String value) {
        if (value == null || value.isEmpty()) return null;
        if ((value.length() & 1) != 0 || value.length() > 8192) {
            throw new IllegalArgumentException("creative NBT");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("creative NBT");
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    public synchronized boolean useItemOn(int x, int y, int z, int direction,
                                          float hitX, float hitY, float hitZ,
                                          boolean insideBlock)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed) return false;
        int sequence = nextInteractionSequence();
        sendPacket(packet -> MinecraftPackets.writeUseItemOn(packet,
                x, y, z, direction, hitX, hitY, hitZ, insideBlock, sequence));
        return true;
    }

    public synchronized boolean useMainHandItem() throws IOException, ProtocolException {
        return useHandItem(0);
    }
    public synchronized boolean useHandItem(int hand) throws IOException, ProtocolException {
        if (state != State.PLAY || closed) return false;
        int sequence = nextInteractionSequence();
        sendPacket(packet -> MinecraftPackets.writeUseItem(packet, hand,sequence));
        return true;
    }
    public synchronized boolean sendRiderInput(float sideways,float forward,boolean jump,boolean dismount,float yaw,float pitch) throws IOException,ProtocolException {
        if(state!=State.PLAY||closed||runtime.riding.vehicleOf(playerEntityId)<0)return false;
        sendPacket(p->MinecraftPackets.writeRiderRotation(p,yaw,pitch,false));
        sendPacket(p->MinecraftPackets.writePlayerInput(p,sideways,forward,jump,dismount));return true;
    }
    public synchronized boolean sendVehicleMovement(double x,double y,double z,float yaw,float pitch) throws IOException,ProtocolException {
        if(state!=State.PLAY||closed||runtime.riding.vehicleOf(playerEntityId)<0)return false;
        sendPacket(p->MinecraftPackets.writeVehicleMovement(p,x,y,z,yaw,pitch));return true;
    }
    public synchronized void sendPaddles(boolean left,boolean right) throws IOException,ProtocolException {
        if(state==State.PLAY&&!closed&&runtime.riding.vehicleOf(playerEntityId)>=0)sendPacket(p->MinecraftPackets.writePaddles(p,left,right));
    }
    public synchronized void ridingCommand(int action,int data) throws IOException,ProtocolException {
        if(state==State.PLAY&&!closed&&playerEntityId>=0)sendPacket(p->MinecraftPackets.writePlayerCommand(p,playerEntityId,action,data));
    }

    public synchronized boolean requestRespawn() throws IOException,ProtocolException {
        if(state!=State.PLAY||closed||!runtime.combat.canRespawn(System.nanoTime()))return false;
        sendPacket(MinecraftPackets::writeRespawnRequest);return true;
    }

    private int nextInteractionSequence() {
        int result = interactionSequence;
        interactionSequence = interactionSequence == Integer.MAX_VALUE
                ? 0 : interactionSequence + 1;
        return result;
    }

    public synchronized boolean setFlying(boolean requested)
            throws IOException, ProtocolException {
        if (state != State.PLAY || closed || (requested && !mayFly)) return false;
        sendPacket(packet -> MinecraftPackets.writePlayerAbilities(packet, requested));
        flying = requested;
        return true;
    }

    public boolean canFly() {
        return mayFly;
    }

    public boolean isFlying() {
        return flying;
    }

    public float flyingSpeed() {
        return flyingSpeed;
    }

    public int playerEntityId() {
        return playerEntityId;
    }

    private void receiveFrame(ByteBuffer frame, SessionCredentials credentials)
            throws ProtocolException {
        ByteBuffer packet = frameCodec.decode(frame);
        int packetId = VarInts.read(packet);
        runtime.debug.receivedPackets++;
        if (state == State.LOGIN) {
            receiveLogin(packetId, packet, credentials);
        } else if (state == State.PLAY) {
            receivePlay(packetId, packet);
        }
    }

    private void receiveLogin(int packetId, ByteBuffer packet,
                              SessionCredentials credentials) throws ProtocolException {
        try {
            switch (packetId) {
                case MinecraftPacketIds.LOGIN_CLIENTBOUND_DISCONNECT:
                    String reason = BinaryCodec.readString(packet, 262_144);
                    listener.onDisconnected(reason);
                    closed = true;
                    break;
                case MinecraftPacketIds.LOGIN_CLIENTBOUND_HELLO:
                    MinecraftPackets.EncryptionRequest request =
                            MinecraftPackets.readEncryptionRequest(packet);
                    MinecraftSessionAuthenticator.LoginEncryption encryption =
                            authenticator.authenticate(request, credentials);
                    sendPacket(output -> MinecraftPackets.writeLoginKey(output,
                            encryption.encryptedSecret, encryption.encryptedChallenge));
                    streamCipher = new MinecraftStreamCipher(encryption.secret);
                    break;
                case MinecraftPacketIds.LOGIN_CLIENTBOUND_SUCCESS:
                    MinecraftPackets.LoginSuccess success =
                            MinecraftPackets.readLoginSuccess(packet);
                    state = State.PLAY;
                    runtime.players.identity(success.profileId,success.username);
                    listener.onLoginSuccess(success.profileId, success.username);
                    break;
                case MinecraftPacketIds.LOGIN_CLIENTBOUND_COMPRESSION:
                    frameCodec.enableCompression(
                            MinecraftPackets.readCompressionThreshold(packet));
                    break;
                case MinecraftPacketIds.LOGIN_CLIENTBOUND_CUSTOM_QUERY:
                    MinecraftPackets.LoginQuery query = MinecraftPackets.readLoginQuery(packet);
                    // Rejecting Forge's query declares this as a vanilla-compatible client.
                    // A server bridge supplies the thin display protocol after login.
                    sendPacket(output -> MinecraftPackets.writeRejectedLoginQuery(
                            output, query.transactionId));
                    listener.onLoginQueryRejected(query.channel);
                    break;
                default:
                    throw new ProtocolException("Unexpected login packet 0x"
                            + Integer.toHexString(packetId));
            }
        } catch (IOException exception) {
            throw new ProtocolException("Unable to send login response", exception);
        } catch (GeneralSecurityException exception) {
            throw new ProtocolException("Unable to initialize encrypted login", exception);
        }
    }

    private void receivePlay(int packetId, ByteBuffer packet) throws ProtocolException {
        try {
            if(runtime.debug.handles(packetId)){runtime.debug.read(packetId,packet);completePlayHandshake();return;}
            if(packetId==ChatProtocol.PLAYER||packetId==ChatProtocol.DISGUISED){
                receiveChat(packet,packetId==ChatProtocol.PLAYER);completePlayHandshake();return;
            }
            if(packetId==com.newhorizon.thinclient.world.PlayerListState.UPDATE||packetId==com.newhorizon.thinclient.world.PlayerListState.REMOVE||packetId==com.newhorizon.thinclient.world.PlayerListState.HEADER){
                runtime.players.read(packetId,packet);completePlayHandshake();return;
            }
            if(com.newhorizon.thinclient.world.ServerHudState.handles(packetId)){
                runtime.hud.read(packetId,packet);completePlayHandshake();return;
            }
            if(packetId==0x12 || packetId==0x14) {
                runtime.inventory.readVanillaContent(packet,packetId==0x14); completePlayHandshake(); return;
            }
            if(packetId==0x46||packetId==0x64){
                String message=BinaryCodec.readString(packet,262144);boolean overlay=true;
                if(packetId==0x64){BinaryCodec.require(packet,1);overlay=packet.get()!=0;}
                if(packet.hasRemaining())throw new ProtocolException("Trailing action bar bytes");
                if(overlay)com.newhorizon.thinclient.display.GeckoNativeBridge.actionBar(message);
                else com.newhorizon.thinclient.display.GeckoNativeBridge.chatMessage(message);
                completePlayHandshake();return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_SET_PASSENGERS){
                runtime.riding.readPassengers(packet);completePlayHandshake();return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_MOVE_VEHICLE){
                BinaryCodec.require(packet,32);double x=packet.getDouble(),y=packet.getDouble(),z=packet.getDouble();float yaw=packet.getFloat(),pitch=packet.getFloat();
                if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch)||packet.hasRemaining())throw new ProtocolException("Invalid vehicle correction");
                if(runtime.riding.vehicleOf(playerEntityId)>=0){
                    int root=runtime.riding.rootOf(playerEntityId);runtime.riding.correctVehicle(root,x,y,z,yaw,pitch);
                    runtime.entities.correctVehicle(root,x,y,z,yaw,pitch);
                    sendVehicleMovement(x,y,z,yaw,pitch);
                }
                completePlayHandshake();return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_ENTITY_LINK) {
                runtime.entities.leashes.read(packet);completePlayHandshake();return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_SET_ENTITY_MOTION) {
                runtime.combat.readMotion(packet.duplicate(),playerEntityId);
                runtime.entities.setMotion(packet); completePlayHandshake(); return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_SET_EQUIPMENT){runtime.entities.setEquipment(packet);completePlayHandshake();return;}
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_SET_ENTITY_DATA) {
                runtime.combat.readMetadata(packet.duplicate(),playerEntityId);
                runtime.riding.readMetadata(packet.duplicate(),playerEntityId);
                runtime.entities.setMetadata(packet); completePlayHandshake(); return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_COOLDOWN) {
                runtime.itemUse.readCooldown(packet); completePlayHandshake(); return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_UPDATE_EFFECT) {
                runtime.effects.update(packet,playerEntityId); completePlayHandshake(); return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_REMOVE_EFFECT) {
                runtime.effects.remove(packet,playerEntityId); completePlayHandshake(); return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_UPDATE_ATTRIBUTES) {
                runtime.effects.attributes(packet,playerEntityId,runtime.combat,runtime.entities); completePlayHandshake(); return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_SET_HEALTH) {
                runtime.combat.readHealth(packet);completePlayHandshake();return;
            }
            if(packetId==0x26) {
                runtime.entities.worldParticles.read(packet.duplicate(),System.nanoTime());
                runtime.entities.fishingParticles.read(packet.duplicate(),System.nanoTime());
                runtime.entities.combatParticles.readLevelParticles(packet,6,18,55);completePlayHandshake();return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_SET_EXPERIENCE) {
                runtime.combat.readExperience(packet);completePlayHandshake();return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_HURT_ANIMATION) {
                int entity=VarInts.read(packet);BinaryCodec.require(packet,4);float direction=packet.getFloat();
                if(!Float.isFinite(direction)||packet.hasRemaining())throw new ProtocolException("Invalid hurt animation");
                runtime.entities.hurt(entity,System.nanoTime());
                if(entity==playerEntityId)runtime.combat.hurt(direction,System.nanoTime());
                completePlayHandshake();return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_ANIMATE) {
                int entity=VarInts.read(packet);BinaryCodec.require(packet,1);int animation=packet.get()&255;
                if(packet.hasRemaining())throw new ProtocolException("Trailing entity animation");
                runtime.entities.animate(entity,animation,System.nanoTime());
                if(entity==playerEntityId&&animation==2)runtime.riding.wake();completePlayHandshake();return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_ENTITY_EVENT) {
                BinaryCodec.require(packet,5);int entity=packet.getInt(),status=packet.get()&255;
                if(packet.hasRemaining())throw new ProtocolException("Trailing entity event");
                if(status==31)runtime.entities.pullHookedPlayer(entity,playerEntityId,runtime.combat);
                runtime.entities.status(entity,status,System.nanoTime());
                if(entity==playerEntityId) {
                    runtime.combat.shieldStatus(status,System.nanoTime());
                    if(status==9)runtime.combat.completeUse();
                    if(status==3)runtime.combat.markDead("");
                }
                if(status==35)runtime.sounds.playEntity("minecraft:item.totem.use",
                        com.newhorizon.thinclient.audio.SoundEventQueue.PLAYERS,entity,1,1,System.nanoTime());
                if(status==29||status==30)runtime.sounds.playEntity(
                        status==29?"minecraft:item.shield.block":"minecraft:item.shield.break",
                        com.newhorizon.thinclient.audio.SoundEventQueue.PLAYERS,entity,1,1,System.nanoTime());
                completePlayHandshake();return;
            }
            if(packetId==MinecraftPacketIds.PLAY_CLIENTBOUND_COMBAT_KILL) {
                int entity=VarInts.read(packet);String message=BinaryCodec.readString(packet,262144);
                if(packet.hasRemaining())throw new ProtocolException("Trailing combat death");
                if(entity==playerEntityId)runtime.combat.markDead(message);
                completePlayHandshake();return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_SOUND) {
                SoundPacketDecoder.readSound(packet, runtime.sounds);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_EXPLOSION) {
                ByteBuffer effect=packet.duplicate();BinaryCodec.require(effect,24);
                runtime.entities.worldParticles.burst(23,0,effect.getDouble(),effect.getDouble(),effect.getDouble(),1,0,0,0xffffff,System.nanoTime());
                runtime.combat.readExplosionMotion(packet.duplicate());
                SoundPacketDecoder.readExplosion(packet, runtime.sounds);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_DAMAGE_EVENT) {
                ByteBuffer damageCopy=packet.duplicate();int damagedEntity=VarInts.read(damageCopy);
                runtime.entities.hurt(damagedEntity,System.nanoTime());
                SoundPacketDecoder.readPlayerDamage(packet, runtime.sounds, runtime.soundBiomes, playerEntityId);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_ADD_EXPERIENCE_ORB) {
                runtime.entities.addExperienceOrb(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_TAKE_ITEM) {
                SoundPacketDecoder.readPickup(packet, runtime.sounds, runtime.entities);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_SOUND_ENTITY) {
                SoundPacketDecoder.readEntitySound(packet, runtime.sounds);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_STOP_SOUND) {
                SoundPacketDecoder.readStopSound(packet, runtime.sounds);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_LEVEL_EVENT) {
                runtime.entities.worldParticles.levelEvent(packet.duplicate(),System.nanoTime());
                SoundPacketDecoder.readLevelEvent(packet, runtime.sounds);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_KEEP_ALIVE) {
                BinaryCodec.require(packet, 8);
                long id = packet.getLong();
                if (packet.hasRemaining()) throw new ProtocolException("Trailing keepalive bytes");
                sendPacket(output -> MinecraftPackets.writeKeepAlive(output, id));
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_GAME_EVENT) {
                if(packet.remaining()==5 && (packet.get(packet.position())&255)==3) {
                    gameMode=(int)packet.getFloat(packet.position()+1);
                    runtime.inventory.setCreativeMode(gameMode==1);
                }
                runtime.environment.readGameEvent(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_SET_TIME) {
                runtime.environment.readTime(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_LOGIN) {
                runtime.debug.login(packet.duplicate());
                runtime.effects.clear();
                runtime.combat.clear();
                runtime.riding.clear();
                runtime.inventory.clear();
                runtime.sounds.clear();
                runtime.itemUse.clear();
                BinaryCodec.require(packet, Integer.BYTES + 2);
                playerEntityId = packet.getInt();
                packet.get(); // hardcore
                gameMode = packet.get() & 0xff;
                runtime.inventory.setCreativeMode(gameMode == 1);
                BinaryCodec.require(packet, 1);
                packet.get(); // previous game mode
                int dimensionCount = VarInts.read(packet);
                if (dimensionCount < 0 || dimensionCount > 1024)
                    throw new ProtocolException("Dimension count outside limit");
                for (int i = 0; i < dimensionCount; i++) BinaryCodec.readString(packet, 1024);
                runtime.soundBiomes.read(packet);
                System.out.println("[NH-THIN] vanilla game mode=" + gameMode
                        + " creativeInventory=" + (gameMode == 1));
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_FORGET_LEVEL_CHUNK) {
                receiveForgetLevelChunk(packet);
                completePlayHandshake();
                return;
            }
            if (packetId
                    == MinecraftPacketIds.PLAY_CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT) {
                receiveLevelChunkWithLight(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_LIGHT_UPDATE) {
                receiveLightUpdate(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_CHUNKS_BIOMES) {
                receiveChunksBiomes(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_BLOCK_UPDATE) {
                receiveBlockUpdate(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_BLOCK_ENTITY_DATA) {
                receiveBlockEntityData(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_BLOCK_CHANGED_ACK) {
                VarInts.read(packet);
                if (packet.hasRemaining()) {
                    throw new ProtocolException("Trailing block acknowledgement bytes");
                }
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_BLOCK_DESTRUCTION) {
                receiveBlockDestruction(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_BLOCK_EVENT) {
                receiveBlockEvent(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_SET_CHUNK_CACHE_CENTER) {
                receiveChunkCacheCenter(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_SET_CHUNK_CACHE_RADIUS) {
                int radius = VarInts.read(packet);
                if (packet.hasRemaining()) {
                    throw new ProtocolException("Trailing chunk cache radius bytes");
                }
                System.out.println("[NH-THIN] server chunk cache radius=" + radius);
                completePlayHandshake();
                return;
            }
            if (packetId
                    == MinecraftPacketIds.PLAY_CLIENTBOUND_SECTION_BLOCKS_UPDATE) {
                receiveSectionBlocksUpdate(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_PLAYER_ABILITIES) {
                BinaryCodec.require(packet, 9);
                int flags = packet.get() & 0xff;
                flying = (flags & 0x02) != 0;
                mayFly = (flags & 0x04) != 0;
                if (!mayFly) flying = false;
                float speed = packet.getFloat();
                if (Float.isFinite(speed) && speed > 0.0f) flyingSpeed = speed;
                packet.getFloat(); // walking FOV modifier
                System.out.println("[NH-THIN] vanilla abilities mayFly=" + mayFly
                        + " flying=" + flying + " speed=" + flyingSpeed);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_PLAYER_POSITION) {
                BinaryCodec.require(packet, 33);
                double x = packet.getDouble();
                double y = packet.getDouble();
                double z = packet.getDouble();
                float yaw = packet.getFloat();
                float pitch = packet.getFloat();
                int relativeFlags = packet.get() & 0xff;
                int teleportId = VarInts.read(packet);
                if (packet.hasRemaining()) {
                    throw new ProtocolException("Trailing player-position bytes");
                }
                runtime.world.applyServerTeleport(
                        x, y, z, yaw, pitch, relativeFlags);
                sendPacket(output -> MinecraftPackets.writeAcceptTeleportation(
                        output, teleportId));
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_ADD_ENTITY) {
                runtime.entities.addEntity(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_ADD_PLAYER) {
                runtime.entities.addPlayer(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_MOVE_ENTITY_POSITION) {
                runtime.entities.moveEntity(packet, false);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_MOVE_ENTITY_POSITION_ROTATION) {
                runtime.entities.moveEntity(packet, true);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_MOVE_ENTITY_ROTATION) {
                runtime.entities.rotateEntity(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_ROTATE_HEAD) {
                runtime.entities.rotateHead(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_REMOVE_ENTITIES) {
                runtime.riding.removeEntities(packet.duplicate());
                runtime.entities.removeEntities(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_TELEPORT_ENTITY) {
                runtime.entities.teleportEntity(packet);
                completePlayHandshake();
                return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_RESPAWN) {
                int respawnFlags=MinecraftPackets.readRespawnFlags(packet),keep=respawnFlags>>>8;
                gameMode=respawnFlags&255;runtime.inventory.setCreativeMode(gameMode==1);
                if((keep&2)==0)runtime.effects.clear();
                runtime.combat.respawn(keep);runtime.riding.clear();
                if((keep&2)==0)runtime.inventory.clear();
                runtime.entities.clear();
                runtime.sounds.clear();
                runtime.itemUse.clear();
                completePlayHandshake();return;
            }
            if (packetId == MinecraftPacketIds.PLAY_CLIENTBOUND_CUSTOM_PAYLOAD) {
                MinecraftPackets.CustomPayload custom =
                        MinecraftPackets.readCustomPayload(packet);
                if ("minecraft:brand".equals(custom.channel)) {
                    runtime.debug.brand=BinaryCodec.readString(custom.payload,1024);
                } else if (DisplayProtocol.CHANNEL.equals(custom.channel)) {
                    DisplayMessage message = DisplayProtocol.decode(custom.payload);
                    if (!runtime.displays.handle(message)) {
                        listener.onDisplayRejected(message);
                    }
                } else if (WorldProtocol.CHANNEL.equals(custom.channel)) {
                    WorldMessage message = WorldProtocol.decode(custom.payload);
                    if (message instanceof WorldMessage.Reset) runtime.sounds.clear();
                    runtime.world.handle(message);
                    if (message instanceof WorldMessage.PlayerPosition) {
                        WorldMessage.PlayerPosition position =
                                (WorldMessage.PlayerPosition) message;
                        runtime.displays.updatePlayer(runtime.world.dimension(),
                                position.x, position.y, position.z);
                    }
                } else if (InventoryProtocol.CHANNEL.equals(custom.channel)) {
                    runtime.inventory.handle(custom.payload);
                } else if (WEBDISPLAYS_CHANNEL.equals(custom.channel)) {
                    DisplayMessage message = WebDisplaysPacketDecoder.decode(custom.payload);
                    if (message != null) {
                        if (!runtime.displays.handle(message)) {
                            listener.onDisplayRejected(message);
                        }
                    } else {
                        listener.onCustomPayload(custom.channel, custom.payload);
                    }
                } else {
                    listener.onCustomPayload(custom.channel, custom.payload);
                }
            }
            completePlayHandshake();
            // World packet decoders are added independently. Unknown packets are
            // discarded immediately; they can never accumulate in a queue.
        } catch (IOException exception) {
            throw new ProtocolException("Unable to send play response", exception);
        }
    }

    private void receiveBlockUpdate(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, Long.BYTES);
        long packedPosition = packet.getLong();
        int stateId = VarInts.read(packet);
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing block-update bytes");
        }
        runtime.world.applyVanillaBlockUpdate(
                unpackBlockX(packedPosition), unpackBlockY(packedPosition),
                unpackBlockZ(packedPosition), stateId);
    }

    private void receiveLevelChunkWithLight(ByteBuffer packet)
            throws ProtocolException {
        BinaryCodec.require(packet, Integer.BYTES * 2);
        int chunkX = packet.getInt();
        int chunkZ = packet.getInt();
        NbtSkipper.skipRoot(packet); // heightmaps
        int sectionBytes = VarInts.read(packet);
        if (sectionBytes < 0 || sectionBytes > ThinClientRuntime.MAX_PACKET_BYTES) {
            throw new ProtocolException("Vanilla chunk data outside limit " + sectionBytes);
        }
        BinaryCodec.require(packet, sectionBytes);
        ByteBuffer sections = packet.slice();
        sections.limit(sectionBytes);
        packet.position(packet.position() + sectionBytes);
        ByteBuffer auxiliary = packet.slice();
        int auxiliaryBytes = auxiliary.remaining();
        runtime.world.applyVanillaChunkWithLight(chunkX, chunkZ, sections, auxiliary);
        packet.position(packet.limit());
        if (vanillaChunkLogCount < 8) {
            vanillaChunkLogCount++;
            System.out.println("[NH-THIN] vanilla full chunk=" + chunkX + ","
                    + chunkZ + " sectionBytes=" + sectionBytes
                    + " auxiliaryBytes=" + auxiliaryBytes);
        }
    }

    private void receiveForgetLevelChunk(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, Integer.BYTES * 2);
        int chunkX = packet.getInt();
        int chunkZ = packet.getInt();
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing forgotten chunk bytes");
        }
        runtime.world.forgetVanillaChunk(chunkX, chunkZ);
    }

    private void receiveLightUpdate(ByteBuffer packet) throws ProtocolException {
        int chunkX = VarInts.read(packet);
        int chunkZ = VarInts.read(packet);
        runtime.world.applyVanillaLightData(chunkX, chunkZ, packet.slice());
        packet.position(packet.limit());
    }

    private void receiveChunksBiomes(ByteBuffer packet) throws ProtocolException {
        int count = VarInts.read(packet);
        if (count < 0 || count > 4096) {
            throw new ProtocolException("Invalid chunk biome count " + count);
        }
        for (int index = 0; index < count; index++) {
            BinaryCodec.require(packet, Long.BYTES);
            long packedChunk = packet.getLong();
            int chunkX = (int) packedChunk;
            int chunkZ = (int) (packedChunk >>> 32);
            int length = VarInts.read(packet);
            if (length < 0 || length > ThinClientRuntime.MAX_PACKET_BYTES) {
                throw new ProtocolException("Chunk biome data outside limit " + length);
            }
            BinaryCodec.require(packet, length);
            ByteBuffer biomes = packet.slice();
            biomes.limit(length);
            runtime.world.applyVanillaBiomeData(chunkX, chunkZ, biomes);
            packet.position(packet.position() + length);
        }
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing chunks biome bytes");
        }
    }

    private void receiveBlockEntityData(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, Long.BYTES);
        long packedPosition = packet.getLong();
        int typeId = VarInts.read(packet);
        NbtSkipper.skipRoot(packet);
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing block entity bytes");
        }
        runtime.world.applyVanillaBlockEntity(unpackBlockX(packedPosition),
                unpackBlockY(packedPosition), unpackBlockZ(packedPosition), typeId);
    }

    private void receiveBlockDestruction(ByteBuffer packet) throws ProtocolException {
        VarInts.read(packet); // breaker entity id
        BinaryCodec.require(packet, Long.BYTES + 1);
        packet.getLong(); // block position
        packet.get(); // destruction stage
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing block destruction bytes");
        }
    }

    private void receiveBlockEvent(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, Long.BYTES + 2);
        packet.getLong(); // block position
        packet.get(); // event action
        packet.get(); // event parameter
        VarInts.read(packet); // block registry id
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing block event bytes");
        }
    }

    private void receiveChunkCacheCenter(ByteBuffer packet) throws ProtocolException {
        int chunkX = VarInts.read(packet);
        int chunkZ = VarInts.read(packet);
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing chunk cache center bytes");
        }
        System.out.println("[NH-THIN] server chunk cache center="
                + chunkX + "," + chunkZ);
    }

    private void receiveSectionBlocksUpdate(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.require(packet, Long.BYTES);
        long packedSection = packet.getLong();
        int count = VarInts.read(packet);
        if (count < 0 || count > 4096) {
            throw new ProtocolException("Invalid section block-update count " + count);
        }
        int sectionX = (int) (packedSection >> 42);
        int sectionY = (int) (packedSection << 44 >> 44);
        int sectionZ = (int) (packedSection << 22 >> 42);
        for (int index = 0; index < count; index++) {
            long entry = VarInts.readLong(packet);
            int localPosition = (int) entry & 0xfff;
            int stateId = (int) (entry >>> 12);
            int x = (sectionX << 4) + ((localPosition >>> 8) & 15);
            int y = (sectionY << 4) + (localPosition & 15);
            int z = (sectionZ << 4) + ((localPosition >>> 4) & 15);
            runtime.world.applyVanillaBlockUpdate(x, y, z, stateId);
        }
        if (packet.hasRemaining()) {
            throw new ProtocolException("Trailing section block-update bytes");
        }
    }

    private static int unpackBlockX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackBlockY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackBlockZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    /**
     * Mohist can emit LoginSuccess a few milliseconds before its inbound
     * decoder leaves LOGIN. Waiting for one clientbound PLAY packet proves the
     * protocol transition is complete before sending packet id 0x0d.
     */
    private void completePlayHandshake() throws IOException, ProtocolException {
        if (!playHellosSent) {
            // Keep the server's authoritative loaded set equal to the bounded
            // 9x9 cache. Without this vanilla packet Mohist used its much
            // larger default view and the thin client eventually discarded
            // chunks that the server still considered loaded.
            sendPacket(packet -> MinecraftPackets.writeClientInformation(packet, 4));
            sendBrand();
            sendChannelRegistration();
            sendThinHello();
            playHellosSent = true;
            nextCompatibilityHelloNanos = System.nanoTime()
                    + COMPATIBILITY_HELLO_INTERVAL_NANOS;
            System.out.println("[NH-THIN] PLAY decoder confirmed; payloads enabled");
        }
        maybeSendWebDisplaysCompatibilityHellos();
    }

    private void sendBrand() throws IOException, ProtocolException {
        ByteBuffer brand = ByteBuffer.allocate(64);
        BinaryCodec.writeString(brand, "newhorizon:thin", 64);
        brand.flip();
        sendPacket(packet -> MinecraftPackets.writeCustomPayload(
                packet, "minecraft:brand", brand));
    }

    private void sendThinHello() throws IOException, ProtocolException {
        ByteBuffer hello = ByteBuffer.allocate(8);
        WorldProtocol.encodeHello(hello);
        hello.flip();
        sendPacket(packet -> MinecraftPackets.writeCustomPayload(
                packet, WorldProtocol.HELLO_CHANNEL, hello));
    }

    private void sendChannelRegistration() throws IOException, ProtocolException {
        String channels = WorldProtocol.CHANNEL + '\0' + DisplayProtocol.CHANNEL
                + '\0' + MCEF_GATE_CHANNEL + '\0' + WEBDISPLAYS_CHANNEL
                + '\0' + InventoryProtocol.CHANNEL;
        ByteBuffer registration = ByteBuffer.wrap(
                channels.getBytes(StandardCharsets.UTF_8));
        sendPacket(packet -> MinecraftPackets.writeCustomPayload(
                packet, "minecraft:register", registration));
    }

    /**
     * Satisfies WebDisplays' MCEF build gate without loading its Forge client.
     * The thin profile owns the same validated Gecko/native bridge and therefore
     * advertises the same build marker through the mod's SimpleChannel frame.
     */
    private void sendWebDisplaysCompatibilityHellos() throws IOException, ProtocolException {
        ByteBuffer mcefHello = ByteBuffer.allocate(128);
        VarInts.write(mcefHello, 0); // C2SMcefGateHello discriminator
        BinaryCodec.writeString(mcefHello, MCEF_BUILD_MARKER, 128);
        mcefHello.flip();
        sendPacket(packet -> MinecraftPackets.writeCustomPayload(
                packet, MCEF_GATE_CHANNEL, mcefHello));

        ByteBuffer versionHello = ByteBuffer.allocate(16);
        versionHello.put((byte) 16); // C2SMessageClientVersion discriminator
        BinaryCodec.writeString(versionHello, WEBDISPLAYS_CLIENT_VERSION, 16);
        versionHello.flip();
        sendPacket(packet -> MinecraftPackets.writeCustomPayload(
                packet, WEBDISPLAYS_CHANNEL, versionHello));
    }

    private void maybeSendWebDisplaysCompatibilityHellos()
            throws IOException, ProtocolException {
        if (compatibilityHelloAttempts >= COMPATIBILITY_HELLO_ATTEMPTS) return;
        long now = System.nanoTime();
        if (now < nextCompatibilityHelloNanos) return;
        sendWebDisplaysCompatibilityHellos();
        compatibilityHelloAttempts++;
        nextCompatibilityHelloNanos = now + COMPATIBILITY_HELLO_INTERVAL_NANOS;
        System.out.println("[NH-THIN] WebDisplays compatibility hello attempt="
                + compatibilityHelloAttempts);
    }

    private synchronized void sendPacket(PacketWriter writer)
            throws IOException, ProtocolException {
        if (channel == null || !channel.isOpen()) throw new IOException("Connection is closed");
        ByteBuffer packet = acquireBuffer();
        ByteBuffer wire = null;
        ByteBuffer ciphertext = null;
        try {
            writer.write(packet);
            packet.flip();
            wire = acquireBuffer();
            frameCodec.encode(wire, packet);
            wire.flip();
            ByteBuffer outgoing = wire;
            if (streamCipher != null) {
                ciphertext = acquireBuffer();
                try {
                    streamCipher.encrypt(wire, ciphertext);
                } catch (GeneralSecurityException exception) {
                    throw new ProtocolException("Unable to encrypt network stream", exception);
                }
                ciphertext.flip();
                outgoing = ciphertext;
            }
            while (outgoing.hasRemaining()) channel.write(outgoing);
            runtime.debug.sentPackets++;
        } catch (BufferOverflowException exception) {
            throw new ProtocolException("Outgoing packet exceeded fixed buffer", exception);
        } finally {
            if (ciphertext != null) runtime.packetBuffers.release(ciphertext);
            if (wire != null) runtime.packetBuffers.release(wire);
            runtime.packetBuffers.release(packet);
        }
    }

    private ByteBuffer acquireBuffer() throws ProtocolException {
        ByteBuffer buffer = runtime.packetBuffers.acquire();
        if (buffer == null) {
            throw new ProtocolException("Network buffer budget exhausted");
        }
        return buffer;
    }

    @Override
    public synchronized void close() {
        if (closed && state == State.DISCONNECTED) return;
        closed = true;
        state = State.DISCONNECTED;
        runtime.effects.clear();
                runtime.combat.clear();
                runtime.riding.clear();
                runtime.inventory.clear();
        runtime.sounds.clear();
        runtime.itemUse.clear();
        frameCodec.close();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
    }

    private interface PacketWriter {
        void write(ByteBuffer packet);
    }

    private enum State {
        DISCONNECTED, LOGIN, PLAY
    }

    public interface Listener {
        void onConnected();

        void onLoginSuccess(java.util.UUID profileId, String username);

        void onLoginQueryRejected(String channel);

        void onCustomPayload(String channel, ByteBuffer payload);

        void onDisplayRejected(DisplayMessage message);

        void onDisconnected(String reason);
    }
}
