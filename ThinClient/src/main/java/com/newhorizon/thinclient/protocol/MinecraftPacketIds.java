package com.newhorizon.thinclient.protocol;

/** Packet IDs derived from the bundled 1.20.1 (protocol 763) client classes. */
public final class MinecraftPacketIds {
    public static final int HANDSHAKE_INTENTION = 0x00;

    public static final int LOGIN_SERVERBOUND_HELLO = 0x00;
    public static final int LOGIN_SERVERBOUND_KEY = 0x01;
    public static final int LOGIN_SERVERBOUND_CUSTOM_QUERY = 0x02;
    public static final int LOGIN_CLIENTBOUND_DISCONNECT = 0x00;
    public static final int LOGIN_CLIENTBOUND_HELLO = 0x01;
    public static final int LOGIN_CLIENTBOUND_SUCCESS = 0x02;
    public static final int LOGIN_CLIENTBOUND_COMPRESSION = 0x03;
    public static final int LOGIN_CLIENTBOUND_CUSTOM_QUERY = 0x04;

    public static final int PLAY_CLIENTBOUND_CUSTOM_PAYLOAD = 0x17;
    public static final int PLAY_CLIENTBOUND_COOLDOWN = 0x15;
    public static final int PLAY_CLIENTBOUND_SET_EQUIPMENT = 0x55;
    public static final int PLAY_CLIENTBOUND_SET_ENTITY_DATA = 0x52;
    public static final int PLAY_CLIENTBOUND_SET_ENTITY_MOTION = 0x54;
    public static final int PLAY_CLIENTBOUND_UPDATE_EFFECT = 0x6c;
    public static final int PLAY_CLIENTBOUND_REMOVE_EFFECT = 0x3f;
    public static final int PLAY_CLIENTBOUND_UPDATE_ATTRIBUTES = 0x6a;
    public static final int PLAY_CLIENTBOUND_EXPLOSION = 0x1d;
    public static final int PLAY_CLIENTBOUND_DAMAGE_EVENT = 0x18;
    public static final int PLAY_CLIENTBOUND_ANIMATE = 0x04;
    public static final int PLAY_CLIENTBOUND_ENTITY_LINK = 0x53;
    public static final int PLAY_CLIENTBOUND_ENTITY_EVENT = 0x1c;
    public static final int PLAY_CLIENTBOUND_HURT_ANIMATION = 0x21;
    public static final int PLAY_CLIENTBOUND_COMBAT_KILL = 0x38;
    public static final int PLAY_CLIENTBOUND_SET_HEALTH = 0x57;
    public static final int PLAY_CLIENTBOUND_SET_EXPERIENCE = 0x56;
    public static final int PLAY_SERVERBOUND_CLIENT_COMMAND = 0x07;
    public static final int PLAY_CLIENTBOUND_ADD_EXPERIENCE_ORB = 0x02;
    public static final int PLAY_CLIENTBOUND_TAKE_ITEM = 0x67;
    public static final int PLAY_CLIENTBOUND_ADD_ENTITY = 0x01;
    public static final int PLAY_CLIENTBOUND_ADD_PLAYER = 0x03;
    public static final int PLAY_CLIENTBOUND_BLOCK_CHANGED_ACK = 0x06;
    public static final int PLAY_CLIENTBOUND_BLOCK_DESTRUCTION = 0x07;
    public static final int PLAY_CLIENTBOUND_BLOCK_ENTITY_DATA = 0x08;
    public static final int PLAY_CLIENTBOUND_BLOCK_EVENT = 0x09;
    public static final int PLAY_CLIENTBOUND_BLOCK_UPDATE = 0x0a;
    public static final int PLAY_CLIENTBOUND_CHUNKS_BIOMES = 0x0d;
    public static final int PLAY_CLIENTBOUND_KEEP_ALIVE = 0x23;
    public static final int PLAY_CLIENTBOUND_FORGET_LEVEL_CHUNK = 0x1e;
    public static final int PLAY_CLIENTBOUND_GAME_EVENT = 0x1f;
    public static final int PLAY_CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT = 0x24;
    public static final int PLAY_CLIENTBOUND_LEVEL_EVENT = 0x25;
    public static final int PLAY_CLIENTBOUND_LIGHT_UPDATE = 0x27;
    public static final int PLAY_CLIENTBOUND_LOGIN = 0x28;
    public static final int PLAY_CLIENTBOUND_MOVE_ENTITY_POSITION = 0x2b;
    public static final int PLAY_CLIENTBOUND_MOVE_ENTITY_POSITION_ROTATION = 0x2c;
    public static final int PLAY_CLIENTBOUND_MOVE_ENTITY_ROTATION = 0x2d;
    public static final int PLAY_CLIENTBOUND_PLAYER_ABILITIES = 0x34;
    public static final int PLAY_CLIENTBOUND_PLAYER_POSITION = 0x3c;
    public static final int PLAY_CLIENTBOUND_REMOVE_ENTITIES = 0x3e;
    public static final int PLAY_CLIENTBOUND_RESPAWN = 0x41;
    public static final int PLAY_CLIENTBOUND_ROTATE_HEAD = 0x42;
    public static final int PLAY_CLIENTBOUND_SECTION_BLOCKS_UPDATE = 0x43;
    public static final int PLAY_CLIENTBOUND_SET_CHUNK_CACHE_CENTER = 0x4e;
    public static final int PLAY_CLIENTBOUND_SET_CHUNK_CACHE_RADIUS = 0x4f;
    public static final int PLAY_CLIENTBOUND_SET_TIME = 0x5e;
    public static final int PLAY_CLIENTBOUND_SOUND_ENTITY = 0x61;
    public static final int PLAY_CLIENTBOUND_SOUND = 0x62;
    public static final int PLAY_CLIENTBOUND_STOP_SOUND = 0x63;
    public static final int PLAY_CLIENTBOUND_TELEPORT_ENTITY = 0x68;
    public static final int PLAY_SERVERBOUND_ACCEPT_TELEPORTATION = 0x00;
    public static final int PLAY_SERVERBOUND_CLIENT_INFORMATION = 0x08;
    public static final int PLAY_SERVERBOUND_CUSTOM_PAYLOAD = 0x0d;
    public static final int PLAY_SERVERBOUND_INTERACT = 0x10;
    public static final int PLAY_SERVERBOUND_KEEP_ALIVE = 0x12;
    public static final int PLAY_SERVERBOUND_MOVE_POSITION_ROTATION = 0x15;
    public static final int PLAY_SERVERBOUND_PLAYER_ABILITIES = 0x1c;
    public static final int PLAY_SERVERBOUND_PLAYER_ACTION = 0x1d;
    public static final int PLAY_SERVERBOUND_PLAYER_COMMAND = 0x1e;
    public static final int PLAY_SERVERBOUND_SET_CREATIVE_MODE_SLOT = 0x2b;
    public static final int PLAY_SERVERBOUND_SWING = 0x2f;
    public static final int PLAY_SERVERBOUND_USE_ITEM_ON = 0x31;
    public static final int PLAY_SERVERBOUND_USE_ITEM = 0x32;
    public static final int PLAY_CLIENTBOUND_SET_PASSENGERS=0x59;
    public static final int PLAY_CLIENTBOUND_MOVE_VEHICLE=0x2e;
    public static final int PLAY_SERVERBOUND_MOVE_ROTATION=0x16;
    public static final int PLAY_SERVERBOUND_MOVE_VEHICLE=0x18;
    public static final int PLAY_SERVERBOUND_PADDLE_BOAT=0x19;
    public static final int PLAY_SERVERBOUND_PLAYER_INPUT=0x1f;

    private MinecraftPacketIds() {
    }
}
