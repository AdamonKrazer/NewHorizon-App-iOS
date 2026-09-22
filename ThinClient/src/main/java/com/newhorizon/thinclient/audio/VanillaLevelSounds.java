package com.newhorizon.thinclient.audio;

import java.util.Random;

/** Sound branches of the official protocol-763 LevelRenderer.levelEvent switch. */
public final class VanillaLevelSounds {
    private static final Random RANDOM = new Random();
    private VanillaLevelSounds() { }

    public static void play(SoundEventQueue.Event event, VanillaSoundCatalog catalog,
                            SoundEventQueue queue) {
        int id = event.levelEvent;
        String name;
        int category = SoundEventQueue.BLOCKS;
        float volume = 1, pitch = 1;
        switch (id) {
            case 1000: name = "block.dispenser.dispense"; break;
            case 1001: name = "block.dispenser.fail"; pitch = 1.2f; break;
            case 1002: name = "block.dispenser.launch"; pitch = 1.2f; break;
            case 1003: name = "entity.ender_eye.launch"; category = 6; pitch = 1.2f; break;
            case 1004: name = "entity.firework_rocket.shoot"; category = 6; pitch = 1.2f; break;
            case 1009:
                if (event.data == 0) {
                    name = "block.fire.extinguish"; volume = 0.5f; pitch = 2.6f + difference() * 0.8f;
                } else if (event.data == 1) {
                    name = "entity.generic.extinguish_fire"; volume = 0.7f; pitch = 1.6f + difference() * 0.4f;
                } else return;
                break;
            case 1010:
                name = catalog.recordEvent(event.data);
                if (name == null) return;
                volume = 4; category = SoundEventQueue.RECORDS; break;
            case 1011: return; // Engine stops only the jukebox at this position.
            case 1015: name = "entity.ghast.warn"; volume = 10; category = 5; pitch = mobPitch(); break;
            case 1016: name = "entity.ghast.shoot"; volume = 10; category = 5; pitch = mobPitch(); break;
            case 1017: name = "entity.ender_dragon.shoot"; volume = 10; category = 5; pitch = mobPitch(); break;
            case 1018: name = "entity.blaze.shoot"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1019: name = "entity.zombie.attack_wooden_door"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1020: name = "entity.zombie.attack_iron_door"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1021: name = "entity.zombie.break_wooden_door"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1022: name = "entity.wither.break_block"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1023: name = "entity.wither.spawn"; category = 5; break;
            case 1024: name = "entity.wither.shoot"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1025: name = "entity.bat.takeoff"; volume = 0.05f; category = 6; pitch = mobPitch(); break;
            case 1026: name = "entity.zombie.infect"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1027: name = "entity.zombie_villager.converted"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1028: name = "entity.ender_dragon.death"; volume = 5; category = 5; break;
            case 1029: name = "block.anvil.destroy"; pitch = toolPitch(); break;
            case 1030: name = "block.anvil.use"; pitch = toolPitch(); break;
            case 1031: name = "block.anvil.land"; volume = 0.3f; pitch = toolPitch(); break;
            case 1032:
                queue.playRelative("minecraft:block.portal.travel", SoundEventQueue.MASTER,
                        0.25f, 0.8f + RANDOM.nextFloat() * 0.4f, RANDOM.nextLong()); return;
            case 1033: name = "block.chorus_flower.grow"; break;
            case 1034: name = "block.chorus_flower.death"; break;
            case 1035: name = "block.brewing_stand.brew"; break;
            case 1038: name = "block.end_portal.spawn"; category = 5; break;
            case 1039: name = "entity.phantom.bite"; volume = 0.3f; category = 5; pitch = toolPitch(); break;
            case 1040: name = "entity.zombie.converted_to_drowned"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1041: name = "entity.husk.converted_to_zombie"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1042: name = "block.grindstone.use"; pitch = toolPitch(); break;
            case 1043: name = "item.book.page_turn"; pitch = toolPitch(); break;
            case 1044: name = "block.smithing_table.use"; pitch = toolPitch(); break;
            case 1045: name = "block.pointed_dripstone.land"; volume = 2; pitch = toolPitch(); break;
            case 1046: name = "block.pointed_dripstone.drip_lava_into_cauldron"; volume = 2; pitch = toolPitch(); break;
            case 1047: name = "block.pointed_dripstone.drip_water_into_cauldron"; volume = 2; pitch = toolPitch(); break;
            case 1048: name = "entity.skeleton.converted_to_stray"; volume = 2; category = 5; pitch = mobPitch(); break;
            case 1501: name = "block.lava.extinguish"; volume = 0.5f; pitch = 2.6f + difference() * 0.8f; break;
            case 1502: name = "block.redstone_torch.burnout"; volume = 0.5f; pitch = 2.6f + difference() * 0.8f; break;
            case 1503: name = "block.end_portal_frame.fill"; break;
            case 1505: name = "item.bone_meal.use"; break;
            case 2001:
                VanillaSoundCatalog.BlockSound sound = catalog.blockSound(event.data);
                if (sound == null) return;
                name = sound.breakEvent; volume = (sound.volume + 1) / 2; pitch = sound.pitch * 0.8f; break;
            case 2002: case 2007: name = "entity.splash_potion.break"; category = 6; pitch = toolPitch(); break;
            case 2006:
                if (event.data != 1) return;
                name = "entity.dragon_fireball.explode"; category = 5; pitch = toolPitch(); break;
            case 3000: name = "block.end_gateway.spawn"; volume = 10; pitch = mobPitch() * 0.7f; break;
            case 3001: name = "entity.ender_dragon.growl"; volume = 64; category = 5; pitch = 0.8f + RANDOM.nextFloat() * 0.3f; break;
            case 3003: name = "item.honeycomb.wax_on"; break;
            case 3006:
                name = "block.sculk.charge";
                int charge = event.data >> 6;
                if (charge > 0) {
                    if (RANDOM.nextFloat() >= 0.3f + charge * 0.1f) return;
                    volume = 0.15f + 0.02f * charge * charge * RANDOM.nextFloat();
                    pitch = 0.4f + 0.3f * charge * RANDOM.nextFloat();
                }
                break;
            case 3007: name = "block.sculk_shrieker.shriek"; volume = 2; pitch = 0.6f + RANDOM.nextFloat() * 0.4f; break;
            default: return; // Particle-only events have no sound branch.
        }
        queue.play(name, category, event.x + 0.5, event.y + 0.5, event.z + 0.5,
                volume, pitch, RANDOM.nextLong());
    }

    private static float difference() { return RANDOM.nextFloat() - RANDOM.nextFloat(); }
    private static float mobPitch() { return 1 + difference() * 0.2f; }
    private static float toolPitch() { return 0.9f + RANDOM.nextFloat() * 0.1f; }
}
