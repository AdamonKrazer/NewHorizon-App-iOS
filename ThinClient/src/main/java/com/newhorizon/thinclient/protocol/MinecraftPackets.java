package com.newhorizon.thinclient.protocol;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Dependency-free encoders/decoders for the packets needed by the thin core. */
public final class MinecraftPackets {
    private MinecraftPackets() {
    }

    public static void writeHandshake(ByteBuffer packet, String host, int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port");
        VarInts.write(packet, MinecraftPacketIds.HANDSHAKE_INTENTION);
        VarInts.write(packet, 763);
        BinaryCodec.writeString(packet, host, 255);
        packet.putShort((short) port);
        VarInts.write(packet, 2); // LOGIN
    }

    public static void writeLoginStart(ByteBuffer packet, String username, UUID profileId) {
        VarInts.write(packet, MinecraftPacketIds.LOGIN_SERVERBOUND_HELLO);
        BinaryCodec.writeString(packet, username, 16);
        packet.put((byte) (profileId == null ? 0 : 1));
        if (profileId != null) BinaryCodec.writeUuid(packet, profileId);
    }

    public static void writeLoginKey(ByteBuffer packet, byte[] encryptedSecret,
                                     byte[] encryptedChallenge) {
        VarInts.write(packet, MinecraftPacketIds.LOGIN_SERVERBOUND_KEY);
        BinaryCodec.writeByteArray(packet, encryptedSecret, 512);
        BinaryCodec.writeByteArray(packet, encryptedChallenge, 512);
    }

    public static void writeRejectedLoginQuery(ByteBuffer packet, int transactionId) {
        VarInts.write(packet, MinecraftPacketIds.LOGIN_SERVERBOUND_CUSTOM_QUERY);
        VarInts.write(packet, transactionId);
        packet.put((byte) 0); // nullable payload is absent
    }

    public static void writeKeepAlive(ByteBuffer packet, long id) {
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_KEEP_ALIVE);
        packet.putLong(id);
    }

    /** Vanilla 1.20.1 ServerboundClientInformationPacket. */
    public static void writeClientInformation(ByteBuffer packet, int viewDistance) {
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("view distance");
        }
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_CLIENT_INFORMATION);
        BinaryCodec.writeString(packet, "pt_br", 16);
        packet.put((byte) viewDistance);
        VarInts.write(packet, 0); // ChatVisiblity.FULL
        packet.put((byte) 1); // chat colors
        packet.put((byte) 0x7f); // all vanilla skin parts
        VarInts.write(packet, 1); // HumanoidArm.RIGHT
        packet.put((byte) 0); // text filtering disabled
        packet.put((byte) 1); // allow server-list visibility
    }

    public static void writeAcceptTeleportation(ByteBuffer packet, int teleportId) {
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_ACCEPT_TELEPORTATION);
        VarInts.write(packet, teleportId);
    }

    public static void writeMovePositionRotation(ByteBuffer packet,
                                                 double x, double y, double z,
                                                 float yaw, float pitch,
                                                 boolean onGround) {
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_MOVE_POSITION_ROTATION);
        packet.putDouble(x).putDouble(y).putDouble(z);
        packet.putFloat(yaw).putFloat(pitch);
        packet.put((byte) (onGround ? 1 : 0));
    }

    public static void writePlayerCommand(ByteBuffer packet, int entityId,
                                          int action) {
        writePlayerCommand(packet,entityId,action,0);
    }
    public static void writePlayerCommand(ByteBuffer packet,int entityId,int action,int data){
        if (entityId < 0 || action < 0 || action > 8) {
            throw new IllegalArgumentException("player command");
        }
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_PLAYER_COMMAND);
        VarInts.write(packet, entityId);
        VarInts.write(packet, action);
        if(data<0||data>100)throw new IllegalArgumentException("jump strength");
        VarInts.write(packet, data);
    }
    public static void writePlayerInput(ByteBuffer out,float sideways,float forward,boolean jump,boolean dismount){
        if(!Float.isFinite(sideways)||!Float.isFinite(forward)||Math.abs(sideways)>1||Math.abs(forward)>1)throw new IllegalArgumentException("rider input");
        VarInts.write(out,MinecraftPacketIds.PLAY_SERVERBOUND_PLAYER_INPUT);out.putFloat(sideways).putFloat(forward).put((byte)((jump?1:0)|(dismount?2:0)));
    }
    public static void writePaddles(ByteBuffer out,boolean left,boolean right){VarInts.write(out,MinecraftPacketIds.PLAY_SERVERBOUND_PADDLE_BOAT);out.put((byte)(left?1:0)).put((byte)(right?1:0));}
    public static void writeVehicleMovement(ByteBuffer out,double x,double y,double z,float yaw,float pitch){
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("vehicle position");
        VarInts.write(out,MinecraftPacketIds.PLAY_SERVERBOUND_MOVE_VEHICLE);out.putDouble(x).putDouble(y).putDouble(z).putFloat(yaw).putFloat(pitch);
    }
    public static void writeRiderRotation(ByteBuffer out,float yaw,float pitch,boolean ground){
        VarInts.write(out,MinecraftPacketIds.PLAY_SERVERBOUND_MOVE_ROTATION);out.putFloat(yaw).putFloat(pitch).put((byte)(ground?1:0));
    }

    public static void writePlayerAbilities(ByteBuffer packet, boolean flying) {
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_PLAYER_ABILITIES);
        packet.put((byte) (flying ? 0x02 : 0x00));
    }

    public static void writeAttackEntity(ByteBuffer packet, int entityId,
                                         boolean secondaryAction) {
        if (entityId < 0) throw new IllegalArgumentException("entity id");
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_INTERACT);
        VarInts.write(packet, entityId);
        VarInts.write(packet, 1); // ServerboundInteractPacket.ActionType.ATTACK
        packet.put((byte) (secondaryAction ? 1 : 0));
    }

    public static void writeInteractEntity(ByteBuffer packet,int entityId,boolean secondaryAction) {
        writeInteractEntity(packet,entityId,secondaryAction,0);
    }
    public static void writeInteractEntity(ByteBuffer packet,int entityId,boolean secondaryAction,int hand) {
        if(entityId<0 || hand<0 || hand>1)throw new IllegalArgumentException("entity interaction");
        VarInts.write(packet,MinecraftPacketIds.PLAY_SERVERBOUND_INTERACT);VarInts.write(packet,entityId);
        VarInts.write(packet,0);VarInts.write(packet,hand);packet.put((byte)(secondaryAction?1:0));
    }

    public static void writePlayerAction(ByteBuffer packet, int action,
                                         int x, int y, int z, int direction,
                                         int sequence) {
        if (action < 0 || action > 6 || direction < 0 || direction > 5
                || sequence < 0) {
            throw new IllegalArgumentException("player action");
        }
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_PLAYER_ACTION);
        VarInts.write(packet, action);
        packet.putLong(packBlockPosition(x, y, z));
        packet.put((byte) direction);
        VarInts.write(packet, sequence);
    }

    public static void writeSwing(ByteBuffer packet) {
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_SWING);
        VarInts.write(packet, 0); // InteractionHand.MAIN_HAND
    }

    /** Vanilla 1.20.1 ServerboundSetCreativeModeSlotPacket with an untagged stack. */
    public static void writeSetCreativeModeSlot(ByteBuffer packet, int slot,
                                                int protocolItemId, int count) {
        writeSetCreativeModeSlot(packet, slot, protocolItemId, count, null);
    }

    /** Writes the exact ItemStack NBT bytes produced by NbtIo for creative variants. */
    public static void writeSetCreativeModeSlot(ByteBuffer packet, int slot,
                                                int protocolItemId, int count,
                                                byte[] encodedNbt) {
        if (slot < -1 || slot > 45 || protocolItemId < 0
                || count < 0 || count > 64
                || (encodedNbt != null && encodedNbt.length > 4096)) {
            throw new IllegalArgumentException("creative slot");
        }
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_SET_CREATIVE_MODE_SLOT);
        packet.putShort((short) slot);
        if (protocolItemId == 0 || count == 0) {
            packet.put((byte) 0); // ItemStack.EMPTY
            return;
        }
        packet.put((byte) 1); // ItemStack is present
        VarInts.write(packet, protocolItemId);
        packet.put((byte) count);
        if (encodedNbt == null || encodedNbt.length == 0) {
            packet.put((byte) 0); // null NBT tag
        } else {
            packet.put(encodedNbt);
        }
    }

    public static void writeUseItemOn(ByteBuffer packet,
                                      int x, int y, int z, int direction,
                                      float hitX, float hitY, float hitZ,
                                      boolean insideBlock, int sequence) {
        if (direction < 0 || direction > 5 || sequence < 0
                || !Float.isFinite(hitX) || !Float.isFinite(hitY)
                || !Float.isFinite(hitZ)) {
            throw new IllegalArgumentException("use item on");
        }
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_USE_ITEM_ON);
        VarInts.write(packet, 0); // InteractionHand.MAIN_HAND
        packet.putLong(packBlockPosition(x, y, z));
        VarInts.write(packet, direction);
        packet.putFloat(hitX).putFloat(hitY).putFloat(hitZ);
        packet.put((byte) (insideBlock ? 1 : 0));
        VarInts.write(packet, sequence);
    }

    public static void writeUseItem(ByteBuffer packet, int sequence) {
        writeUseItem(packet,0,sequence);
    }
    public static void writeUseItem(ByteBuffer packet,int hand,int sequence) {
        if (sequence < 0) throw new IllegalArgumentException("sequence");
        if(hand<0||hand>1)throw new IllegalArgumentException("hand");
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_USE_ITEM);
        VarInts.write(packet, hand);
        VarInts.write(packet, sequence);
    }
    public static void writeRespawnRequest(ByteBuffer packet) {
        VarInts.write(packet,MinecraftPacketIds.PLAY_SERVERBOUND_CLIENT_COMMAND);
        VarInts.write(packet,0); // PERFORM_RESPAWN
    }
    /** Low byte is game mode, high byte is the vanilla data-to-keep mask. */
    public static int readRespawnFlags(ByteBuffer packet) throws ProtocolException {
        BinaryCodec.readString(packet,32767);BinaryCodec.readString(packet,32767);
        BinaryCodec.require(packet,14);packet.getLong();int mode=packet.get()&255;
        packet.get();packet.get();packet.get();int keep=packet.get()&255;
        if(packet.get()!=0) {BinaryCodec.readString(packet,32767);BinaryCodec.require(packet,8);packet.getLong();}
        int cooldown=VarInts.read(packet);
        if(mode>3||cooldown<0||packet.hasRemaining())throw new ProtocolException("Invalid respawn packet");
        return mode|(keep<<8);
    }

    static long packBlockPosition(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38
                | ((long) z & 0x3ffffffL) << 12
                | ((long) y & 0xfffL);
    }

    public static void writeCustomPayload(ByteBuffer packet, String channel,
                                          ByteBuffer payload) {
        if (payload.remaining() > 32767) throw new IllegalArgumentException("payload too large");
        VarInts.write(packet, MinecraftPacketIds.PLAY_SERVERBOUND_CUSTOM_PAYLOAD);
        BinaryCodec.writeString(packet, channel, 32767);
        packet.put(payload.slice());
    }

    public static EncryptionRequest readEncryptionRequest(ByteBuffer packet)
            throws ProtocolException {
        String serverId = BinaryCodec.readString(packet, 20);
        byte[] publicKey = BinaryCodec.readByteArray(packet, 1024);
        byte[] challenge = BinaryCodec.readByteArray(packet, 1024);
        requireConsumed(packet);
        return new EncryptionRequest(serverId, publicKey, challenge);
    }

    public static int readCompressionThreshold(ByteBuffer packet) throws ProtocolException {
        int threshold = VarInts.read(packet);
        requireConsumed(packet);
        if (threshold < 0) throw new ProtocolException("Negative compression threshold");
        return threshold;
    }

    public static LoginSuccess readLoginSuccess(ByteBuffer packet) throws ProtocolException {
        UUID id = BinaryCodec.readUuid(packet);
        String name = BinaryCodec.readString(packet, 16);
        int propertyCount = VarInts.read(packet);
        if (propertyCount < 0 || propertyCount > 64) {
            throw new ProtocolException("Invalid profile property count " + propertyCount);
        }
        for (int index = 0; index < propertyCount; index++) {
            BinaryCodec.readString(packet, 32767);
            BinaryCodec.readString(packet, 32767);
            BinaryCodec.require(packet, 1);
            if (packet.get() != 0) BinaryCodec.readString(packet, 32767);
        }
        requireConsumed(packet);
        return new LoginSuccess(id, name);
    }

    public static LoginQuery readLoginQuery(ByteBuffer packet) throws ProtocolException {
        int transactionId = VarInts.read(packet);
        String channel = BinaryCodec.readString(packet, 32767);
        ByteBuffer payload = packet.slice().asReadOnlyBuffer();
        packet.position(packet.limit());
        return new LoginQuery(transactionId, channel, payload);
    }

    public static CustomPayload readCustomPayload(ByteBuffer packet) throws ProtocolException {
        String channel = BinaryCodec.readString(packet, 32767);
        ByteBuffer payload = packet.slice().asReadOnlyBuffer();
        packet.position(packet.limit());
        return new CustomPayload(channel, payload);
    }

    private static void requireConsumed(ByteBuffer packet) throws ProtocolException {
        if (packet.hasRemaining()) throw new ProtocolException("Trailing packet bytes");
    }

    public static final class EncryptionRequest {
        public final String serverId;
        public final byte[] publicKey;
        public final byte[] challenge;

        EncryptionRequest(String serverId, byte[] publicKey, byte[] challenge) {
            this.serverId = serverId;
            this.publicKey = publicKey;
            this.challenge = challenge;
        }
    }

    public static final class LoginSuccess {
        public final UUID profileId;
        public final String username;

        LoginSuccess(UUID profileId, String username) {
            this.profileId = profileId;
            this.username = username;
        }
    }

    public static final class LoginQuery {
        public final int transactionId;
        public final String channel;
        public final ByteBuffer payload;

        LoginQuery(int transactionId, String channel, ByteBuffer payload) {
            this.transactionId = transactionId;
            this.channel = channel;
            this.payload = payload;
        }
    }

    public static final class CustomPayload {
        public final String channel;
        public final ByteBuffer payload;

        CustomPayload(String channel, ByteBuffer payload) {
            this.channel = channel;
            this.payload = payload;
        }
    }
}
