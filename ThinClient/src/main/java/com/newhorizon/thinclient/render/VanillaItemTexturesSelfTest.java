package com.newhorizon.thinclient.render;
import com.newhorizon.thinclient.world.EnvironmentState;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Source-model coverage, tint isolation, original pixels, and server moon-phase checks. */
public final class VanillaItemTexturesSelfTest {
    private VanillaItemTexturesSelfTest() { }
    public static void run()throws Exception {
        check(VanillaItemSprites.modelNames().size()==983,"all generated models");
        check(VanillaItemSprites.eggCount()==77,"all spawn egg colors");
        check(CombatItemSprites.load("shield")!=null,"dedicated dropped shield skin");
        check(DroppedItemRenderer.name(0).equals("air")&&DroppedItemRenderer.name(1255)==null,"bounded item protocol registry");
        int generated=0,blocks=0,shield=0;
        for(int id=0;id<1255;id++) {
            String item=DroppedItemRenderer.name(id);int route=HeldItemTextureRoute.select(item);
            check(route!=HeldItemTextureRoute.NONE,"registered item has texture route "+item);
            if(route==HeldItemTextureRoute.GENERATED)generated++;
            else if(route==HeldItemTextureRoute.BLOCK)blocks++;
            else if(route==HeldItemTextureRoute.SHIELD)shield++;
        }
        check(generated==576&&blocks==678&&shield==1,"all 1255 actual texture routes");
        for(String item:new String[]{"oak_door","oak_sign","rail","torch","lantern","chain","glass_pane"})
            check(HeldItemTextureRoute.select(item)==HeldItemTextureRoute.GENERATED,"item model takes precedence over block "+item);
        for(String item:new String[]{"big_dripleaf","chorus_flower","small_dripleaf","spore_blossom"})
            check(HeldItemTextureRoute.select(item)==HeldItemTextureRoute.BLOCK,"non-generated plant retains original block texture "+item);
        check(HeldItemTextureRoute.select("other:stone")==HeldItemTextureRoute.NONE,"route namespace isolation");
        for(String model:VanillaItemSprites.modelNames()) {
            int[] pixels=CombatItemSprites.load(model,null);check(pixels!=null&&pixels.length==256,"sprite "+model);
            check(VanillaItemSprites.transform(model).length==9,"pose "+model);
        }
        int[] sword=CombatItemSprites.load("minecraft:diamond_sword",null);
        try(InputStream in=VanillaItemTexturesSelfTest.class.getResourceAsStream("/assets/minecraft/textures/item/diamond_sword.png")) {
            BufferedImage image=ImageIO.read(in);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                int original=image.getRGB(x,y);check(sword[y*16+x]==((original>>>24)<128?0:original),"original sword pixels");
            }
        }
        check(!Arrays.equals(CombatItemSprites.load("chicken_spawn_egg"),CombatItemSprites.load("horse_spawn_egg")),"egg color projection");
        check(CombatItemSprites.load("other:diamond_sword")==null,"unknown namespace");
        check(CombatItemSprites.load("../diamond_sword")==null,"path rejection");
        String red=customColor("CustomPotionColor",0xff0000),blue=customColor("CustomPotionColor",0x0000ff);
        check(!Arrays.equals(CombatItemSprites.load("splash_potion",red),CombatItemSprites.load("splash_potion",blue)),"potion layer tint");
        check(Arrays.equals(CombatItemSprites.load("diamond_sword",red),sword),"tint cannot recolor uncolored items");
        check(VanillaItemTag.read("0100broken").potionColor==0x385dc6,"malformed NBT fallback");
        String display=compoundColor("display","color",0x123456);
        check(VanillaItemTag.read(display).leatherColor==0x123456,"nested leather dye");
        check(!Arrays.equals(CombatItemSprites.load("leather_chestplate",display),CombatItemSprites.load("leather_chestplate",null)),"leather tint differs");
        check(CombatItemSprites.load("leather_helmet_amethyst_trim")!=null,"palette permutation");
        EnvironmentState state=new EnvironmentState();EnvironmentState.Snapshot snapshot=new EnvironmentState.Snapshot();
        for(int phase=0;phase<16;phase++) {
            ByteBuffer packet=ByteBuffer.allocate(16).putLong(123).putLong(-(phase*24000L+6000));packet.flip();
            state.readTime(packet);state.sample(System.nanoTime(),snapshot);
            check(snapshot.moonPhase==phase%8&&snapshot.dayTime==6000,"moon phase preserved "+phase);
        }
        check(Math.abs(EnvironmentRenderer.celestialAngle(6000)-Math.PI*.5)<1e-6,"sun at noon");
        for(String asset:new String[]{"sun","moon_phases","rain","snow","end_sky","clouds"}) {
            try(InputStream in=VanillaItemTexturesSelfTest.class.getResourceAsStream("/assets/minecraft/textures/environment/"+asset+".png")) {
                check(in!=null&&ImageIO.read(in)!=null,"original environment "+asset);
            }
        }
        System.out.println("Vanilla item/environment textures passed: 983 models, 77 spawn eggs, original pixels, layers/NBT, palettes, 8 moon phases.");
    }
    private static String customColor(String key,int color)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(10);out.writeUTF("");out.writeByte(3);out.writeUTF(key);out.writeInt(color);out.writeByte(0);return hex(bytes.toByteArray());
    }
    private static String compoundColor(String compound,String key,int color)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(10);out.writeUTF("");out.writeByte(10);out.writeUTF(compound);
        out.writeByte(3);out.writeUTF(key);out.writeInt(color);out.writeByte(0);out.writeByte(0);return hex(bytes.toByteArray());
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
