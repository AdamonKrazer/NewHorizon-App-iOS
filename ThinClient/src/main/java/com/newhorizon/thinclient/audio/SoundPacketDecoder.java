package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.ProtocolException;
import com.newhorizon.thinclient.protocol.VarInts;

import java.nio.ByteBuffer;

/** Wire layouts verified against the bundled Mojang 1.20.1 client bytecode. */
public final class SoundPacketDecoder {
    private static final int MAX_SOUND_NAME_BYTES = 1024;

    private SoundPacketDecoder() { }

    public static void readPlayerDamage(ByteBuffer packet, SoundEventQueue queue,
                                        BiomeSoundRegistry registry, int localPlayer)
            throws ProtocolException {
        int entity = VarInts.read(packet), damage = VarInts.read(packet);
        VarInts.read(packet); VarInts.read(packet);
        BinaryCodec.require(packet, 1);
        if (packet.get() != 0) { BinaryCodec.require(packet, 24); packet.position(packet.position() + 24); }
        requireConsumed(packet);
        if (entity != localPlayer) return;
        String effect = registry.damageEffect(damage);
        String sound = "minecraft:entity.player.hurt";
        if ("burning".equals(effect)) sound = "minecraft:entity.player.hurt_on_fire";
        else if ("drowning".equals(effect)) sound = "minecraft:entity.player.hurt_drown";
        else if ("freezing".equals(effect)) sound = "minecraft:entity.player.hurt_freeze";
        java.util.Random random = new java.util.Random();
        queue.sound(SoundEventQueue.ENTITY, -1, sound, SoundEventQueue.PLAYERS,
                entity, 0, 0, 0, 1, (random.nextFloat() - random.nextFloat()) * 0.2f + 1,
                random.nextLong(), -1, false);
    }

    public static void readPickup(ByteBuffer packet, SoundEventQueue queue,
                                  com.newhorizon.thinclient.world.EntityTracker entities)
            throws ProtocolException {
        int id = VarInts.read(packet);
        VarInts.read(packet); // collector
        VarInts.read(packet); // amount
        requireConsumed(packet);
        double[] position = new double[3];
        if (!entities.position(id, System.nanoTime(), position)) return;
        boolean experience = entities.typeOf(id) == 34;
        java.util.Random random = new java.util.Random();
        float difference = random.nextFloat() - random.nextFloat();
        queue.play(experience ? "minecraft:entity.experience_orb.pickup" : "minecraft:entity.item.pickup",
                SoundEventQueue.PLAYERS, position[0], position[1], position[2],
                experience ? 0.1f : 0.2f,
                experience ? difference * 0.35f + 0.9f : difference * 1.4f + 2,
                random.nextLong());
    }

    public static void readExplosion(ByteBuffer packet, SoundEventQueue queue) throws ProtocolException {
        BinaryCodec.require(packet, 28);
        double x = packet.getDouble(), y = packet.getDouble(), z = packet.getDouble();
        packet.getFloat(); // power
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new ProtocolException("Invalid explosion position");
        int count = VarInts.read(packet);
        if (count < 0 || count > (packet.remaining() - 12) / 3)
            throw new ProtocolException("Explosion block count outside packet");
        BinaryCodec.require(packet, count * 3 + 12);
        packet.position(packet.position() + count * 3 + 12);
        requireConsumed(packet);
        java.util.Random random = new java.util.Random();
        queue.play("minecraft:entity.generic.explode", SoundEventQueue.BLOCKS, x, y, z, 4,
                (1 + (random.nextFloat() - random.nextFloat()) * 0.2f) * 0.7f, random.nextLong());
    }

    public static void readSound(ByteBuffer packet, SoundEventQueue queue)
            throws ProtocolException {
        readSound(packet, queue, false);
    }

    public static void readEntitySound(ByteBuffer packet, SoundEventQueue queue)
            throws ProtocolException {
        readSound(packet, queue, true);
    }

    private static void readSound(ByteBuffer packet, SoundEventQueue queue, boolean entity)
            throws ProtocolException {
        // FriendlyByteBuf.readById: 0 is a direct Holder, otherwise registry id + 1.
        int holder = VarInts.read(packet);
        if (holder < 0) throw new ProtocolException("Negative sound holder");
        int soundId = holder - 1;
        String name = null;
        float fixedRange = -1.0f;
        if (holder == 0) {
            name = readName(packet);
            BinaryCodec.require(packet, 1);
            if (packet.get() != 0) {
                BinaryCodec.require(packet, 4);
                fixedRange = packet.getFloat();
                nonnegativeFinite(fixedRange, "range");
            }
        }
        int category = readCategory(packet);
        int entityId = -1;
        double x = 0.0, y = 0.0, z = 0.0;
        if (entity) {
            entityId = VarInts.read(packet);
            if (entityId < 0) throw new ProtocolException("Negative sound entity");
        } else {
            BinaryCodec.require(packet, 12);
            x = packet.getInt() / 8.0;
            y = packet.getInt() / 8.0;
            z = packet.getInt() / 8.0;
        }
        BinaryCodec.require(packet, 16);
        float volume = packet.getFloat();
        float pitch = packet.getFloat();
        long seed = packet.getLong();
        nonnegativeFinite(volume, "volume");
        nonnegativeFinite(pitch, "pitch");
        requireConsumed(packet);
        queue.sound(entity ? SoundEventQueue.ENTITY : SoundEventQueue.POSITIONAL,
                soundId, name, category, entityId, x, y, z, volume, pitch, seed,
                fixedRange, false);
    }

    public static void readStopSound(ByteBuffer packet, SoundEventQueue queue)
            throws ProtocolException {
        BinaryCodec.require(packet, 1);
        int flags = packet.get() & 0xff;
        if ((flags & ~3) != 0) throw new ProtocolException("Invalid stop sound flags");
        int category = (flags & 1) != 0 ? readCategory(packet) : -1;
        String name = (flags & 2) != 0 ? readName(packet) : null;
        requireConsumed(packet);
        queue.stop(name, category);
    }

    public static void readLevelEvent(ByteBuffer packet, SoundEventQueue queue)
            throws ProtocolException {
        BinaryCodec.require(packet, 17);
        int type = packet.getInt();
        long position = packet.getLong();
        int x = (int) (position >> 38);
        int y = (int) (position << 52 >> 52);
        int z = (int) (position << 26 >> 38);
        int data = packet.getInt();
        boolean global = packet.get() != 0;
        requireConsumed(packet);
        queue.level(type, data, x, y, z, global);
    }

    private static int readCategory(ByteBuffer packet) throws ProtocolException {
        int category = VarInts.read(packet);
        if (category < 0 || category > SoundEventQueue.VOICE) {
            throw new ProtocolException("Invalid sound category: " + category);
        }
        return category;
    }

    private static String readName(ByteBuffer packet) throws ProtocolException {
        String name = BinaryCodec.readString(packet, MAX_SOUND_NAME_BYTES);
        if (name.isEmpty()) throw new ProtocolException("Empty sound identifier");
        int separator = name.indexOf(':');
        for (int index = 0; index < name.length(); index++) {
            char ch = name.charAt(index);
            boolean common = ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9'
                    || ch == '_' || ch == '.' || ch == '-';
            if (!common && !(ch == ':' && index == separator)
                    && !(ch == '/' && (separator < 0 || index > separator))) {
                throw new ProtocolException("Invalid sound identifier");
            }
        }
        if (separator == name.length() - 1) throw new ProtocolException("Empty sound path");
        if (separator < 0) return "minecraft:" + name;
        return separator == 0 ? "minecraft" + name : name;
    }

    private static void nonnegativeFinite(float value, String field)
            throws ProtocolException {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new ProtocolException("Invalid sound " + field);
        }
    }

    private static void requireConsumed(ByteBuffer packet) throws ProtocolException {
        if (packet.hasRemaining()) throw new ProtocolException("Trailing sound packet bytes");
    }
}
