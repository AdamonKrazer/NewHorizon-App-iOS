package com.newhorizon.thinclient.audio;

import com.newhorizon.thinclient.protocol.BinaryCodec;
import com.newhorizon.thinclient.protocol.NbtSkipper;
import com.newhorizon.thinclient.protocol.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Keeps only sound, climate and color scalars from the server's biome registry NBT. */
public final class BiomeSoundRegistry {
    private volatile Biome[] biomes = new Biome[0];
    private volatile long revision;
    public long revision() {return revision;}
    private volatile Biome[] damageTypes = new Biome[0];

    public String damageEffect(int id) {
        Biome[] current = damageTypes;
        return id < 0 || id >= current.length || current[id] == null ? null : current[id].damageEffect;
    }

    public Biome get(int id) {
        Biome[] current = biomes;
        return id < 0 || id >= current.length ? null : current[id];
    }

    public void read(ByteBuffer input) throws ProtocolException {
        BinaryCodec.require(input, 1);
        if (input.get() != 10) throw new ProtocolException("Expected registry compound");
        string(input);
        Biome[] result = new Biome[1024];
        Biome[] damage = new Biome[1024];
        while (true) {
            int type = type(input);
            if (type == 0) break;
            String name = string(input);
            if (type == 10 && name.equals("minecraft:worldgen/biome")) registry(input, result);
            else if (type == 10 && name.equals("minecraft:damage_type")) registry(input, damage);
            else NbtSkipper.skipUnnamed(input, type);
        }
        biomes = result;
        damageTypes = damage;revision++;
    }

    private static void registry(ByteBuffer input, Biome[] result) throws ProtocolException {
        while (true) {
            int type = type(input);
            if (type == 0) return;
            String name = string(input);
            if (type == 9 && name.equals("value")) {
                BinaryCodec.require(input, 5);
                int child = input.get() & 255, count = input.getInt();
                if (child != 10 || count < 0 || count > result.length)
                    throw new ProtocolException("Biome registry outside limit");
                for (int i = 0; i < count; i++) {
                    Biome biome = new Biome();
                    compound(input, "", biome, 0);
                    if (biome.id < 0 || biome.id >= result.length)
                        throw new ProtocolException("Biome ID outside limit");
                    result[biome.id] = biome;
                }
            } else NbtSkipper.skipUnnamed(input, type);
        }
    }

    private static void compound(ByteBuffer input, String prefix, Biome biome, int depth)
            throws ProtocolException {
        if (depth > 12) throw new ProtocolException("Biome sound nesting outside limit");
        while (true) {
            int type = type(input);
            if (type == 0) return;
            String field = prefix + string(input);
            if (type == 10) { compound(input, field + ".", biome, depth + 1); continue; }
            if (type == 8) { biome.text(field, string(input)); continue; }
            double number;
            switch (type) {
                case 1: BinaryCodec.require(input, 1); number = input.get(); break;
                case 3: BinaryCodec.require(input, 4); number = input.getInt(); break;
                case 5: BinaryCodec.require(input, 4); number = input.getFloat(); break;
                case 6: BinaryCodec.require(input, 8); number = input.getDouble(); break;
                default: NbtSkipper.skipUnnamed(input, type); continue;
            }
            if (Double.isFinite(number)) biome.number(field, number);
        }
    }

    private static int type(ByteBuffer input) throws ProtocolException {
        BinaryCodec.require(input, 1);
        int type = input.get() & 255;
        if (type > 12) throw new ProtocolException("Invalid biome NBT type");
        return type;
    }

    private static String string(ByteBuffer input) throws ProtocolException {
        BinaryCodec.require(input, 2);
        int length = input.getShort() & 65535;
        if (length > 1024) throw new ProtocolException("Biome string outside limit");
        BinaryCodec.require(input, length);
        byte[] bytes = new byte[length]; input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static final class Biome {
        public int id = -1;
        public String name, loop, mood, additions, music;
        String damageEffect;
        public int moodDelay = 6000, moodExtent = 8, minDelay = 12000, maxDelay = 24000;
        public double moodOffset = 2, additionsChance;
        public float temperature = 0.8f,downfall=.4f;
        public int grassColor=-1,foliageColor=-1,waterColor=0x3f76e4;
        public int skyColor=0x78a7ff,fogColor=0xc0d8ff,waterFogColor=0x050533;
        public String grassModifier="none";
        public boolean precipitation, replaceMusic;

        void text(String key, String value) {
            switch (key) {
                case "name": name = value; break;
                case "element.effects": damageEffect = value; break;
                case "element.effects.grass_color_modifier": grassModifier=value;break;
                case "element.effects.ambient_sound": loop = value; break;
                case "element.effects.mood_sound.sound": mood = value; break;
                case "element.effects.additions_sound.sound": additions = value; break;
                case "element.effects.music.sound": music = value; break;
            }
        }
        void number(String key, double value) {
            switch (key) {
                case "id": id = (int) value; break;
                case "element.temperature": temperature = (float) value; break;
                case "element.downfall": downfall=(float)value;break;
                case "element.effects.grass_color": grassColor=((int)value)&0xffffff;break;
                case "element.effects.foliage_color": foliageColor=((int)value)&0xffffff;break;
                case "element.effects.water_color": waterColor=((int)value)&0xffffff;break;
                case "element.effects.sky_color": skyColor=((int)value)&0xffffff;break;
                case "element.effects.fog_color": fogColor=((int)value)&0xffffff;break;
                case "element.effects.water_fog_color": waterFogColor=((int)value)&0xffffff;break;
                case "element.has_precipitation": precipitation = value != 0; break;
                case "element.effects.music.min_delay": minDelay = bound(value, 0, 1000000); break;
                case "element.effects.music.max_delay": maxDelay = bound(value, 0, 1000000); break;
                case "element.effects.music.replace_current_music": replaceMusic = value != 0; break;
                case "element.effects.mood_sound.tick_delay": moodDelay = bound(value, 1, 1000000); break;
                case "element.effects.mood_sound.block_search_extent": moodExtent = bound(value, 0, 32); break;
                case "element.effects.mood_sound.offset": moodOffset = Math.max(0, Math.min(64, value)); break;
                case "element.effects.additions_sound.tick_chance": additionsChance = Math.max(0, Math.min(1, value)); break;
            }
        }
        private static int bound(double value, int min, int max) { return (int) Math.max(min, Math.min(max, value)); }
    }
}
