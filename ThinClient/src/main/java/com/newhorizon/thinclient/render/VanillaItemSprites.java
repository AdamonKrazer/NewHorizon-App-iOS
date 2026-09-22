package com.newhorizon.thinclient.render;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original generated-item layers. Eight retained 16x16 sprites, no runtime model graph. */
final class VanillaItemSprites {
    private static final Map<String,Model> MODELS=readModels();
    private static final Map<String,int[]> EGGS=readEggs();
    private static final String[] keys=new String[8];
    private static final int[][] pixels=new int[8][];
    private static int next;
    private static int[] shield;
    static synchronized int[] load(String material,String hex) {
        if(material==null)return null;
        String id=material.toLowerCase(Locale.ROOT);
        if(id.startsWith("minecraft:"))id=id.substring(10);
        if(!id.matches("[a-z0-9_]+"))return null;
        if(id.equals("shield"))return shield();
        VanillaItemTag tag=VanillaItemTag.read(hex);
        if(tag.trim!=null&&MODELS.containsKey(id+"_"+tag.trim+"_trim"))id+="_"+tag.trim+"_trim";
        if(id.equals("crossbow")&&tag.charged)id=tag.firework?"crossbow_firework":"crossbow_arrow";
        if(id.equals("elytra")&&tag.damage>=431)id="broken_elytra";
        Model model=MODELS.get(id);if(model==null)return CombatItemSprites.legacy(id);
        int[] egg=EGGS.get(id);int color0=0xffffff,color1=0xffffff;
        if(id.contains("potion")||id.equals("tipped_arrow"))color0=tag.potionColor;
        else if(id.startsWith("leather_"))color0=tag.leatherColor;
        else if(id.equals("firework_star"))color1=tag.fireworkColor;
        else if(id.equals("filled_map"))color1=tag.mapColor;
        else if(id.equals("grass")||id.equals("fern")||id.equals("tall_grass")||id.equals("large_fern"))color0=0x7cbd6b;
        else if(id.equals("vine"))color0=0x48b518;
        else if(id.equals("lily_pad"))color0=0x208030;
        else if(egg!=null){color0=egg[0];color1=egg[1];}
        String key=id+":"+color0+":"+color1;
        for(int i=0;i<keys.length;i++)if(key.equals(keys[i]))return pixels[i];
        int slot=next;next=(next+1)%keys.length;keys[slot]=key;pixels[slot]=null;
        int[] data=new int[256];
        try {
            for(int layer=0;layer<model.layers.length;layer++) {
                BufferedImage image=readImage(model.layers[layer]);
                int tint=layer==0?color0:layer==1?color1:0xffffff;
                int frameHeight=Math.min(image.getWidth(),image.getHeight());
                for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                    int argb=image.getRGB(x*image.getWidth()/16,y*frameHeight/16);
                    if((argb>>>24)<128)continue;
                    int r=((argb>>>16)&255)*((tint>>>16)&255)/255;
                    int g=((argb>>>8)&255)*((tint>>>8)&255)/255;
                    int b=(argb&255)*(tint&255)/255;
                    data[y*16+x]=0xff000000|(r<<16)|(g<<8)|b;
                }
            }
            return pixels[slot]=data;
        }catch(IOException error){throw new IllegalStateException("Missing original item layer: "+id,error);}
    }
    static float[] transform(String material) {
        if(material==null)return null;Model model=MODELS.get(material.toLowerCase(Locale.ROOT).replace("minecraft:",""));
        return model==null?null:model.transform;
    }
    static Set<String> modelNames(){return MODELS.keySet();}
    static int eggCount(){return EGGS.size();}
    /** Dropped shield front; first-person uses the full original model/skin instead. */
    private static int[] shield() {
        if(shield!=null)return shield;
        try {
            BufferedImage source=original("entity/shield_base_nopattern");int[] result=new int[256];
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)result[y*16+x]=source.getRGB(1+x*12/16,1+y*22/16);
            return shield=result;
        }catch(IOException error){throw new IllegalStateException("Original shield skin missing",error);}
    }
    private static BufferedImage readImage(String layer)throws IOException {
        int split=layer.indexOf('@');BufferedImage image=original(split<0?layer:layer.substring(0,split));
        if(split<0)return image;
        BufferedImage key=original("trims/color_palettes/trim_palette");
        BufferedImage palette=original("trims/color_palettes/"+layer.substring(split+1));
        int count=key.getWidth()*key.getHeight();
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int value=image.getRGB(x,y);if((value>>>24)==0)continue;
            for(int i=0;i<count;i++)if((key.getRGB(i%key.getWidth(),i/key.getWidth())&0xffffff)==(value&0xffffff)) {
                int replacement=palette.getRGB(i%palette.getWidth(),i/palette.getWidth());
                image.setRGB(x,y,(((value>>>24)*(replacement>>>24)/255)<<24)|(replacement&0xffffff));break;
            }
        }
        return image;
    }
    private static BufferedImage original(String path)throws IOException {
        try(InputStream in=VanillaItemSprites.class.getResourceAsStream("/assets/minecraft/textures/"+path+".png")) {
            if(in==null)throw new IOException(path);BufferedImage image=ImageIO.read(in);
            if(image==null||image.getWidth()>256||image.getHeight()>4096)throw new IOException("Invalid "+path);
            return image;
        }
    }
    private static Map<String,Model> readModels() {
        Map<String,Model> result=new HashMap<String,Model>();
        try(BufferedReader in=reader("vanilla_item_layers_1_20_1.tsv")) {
            String line;while((line=in.readLine())!=null) {
                if(line.startsWith("#")||line.isEmpty())continue;String[] fields=line.split("\t");
                Model model=new Model();model.layers=fields[1].split(",");model.transform=new float[9];
                String[] numbers=fields[2].split(",");for(int i=0;i<9;i++)model.transform[i]=Float.parseFloat(numbers[i]);result.put(fields[0],model);
            }
        }catch(IOException error){throw new ExceptionInInitializerError(error);}
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,int[]> readEggs() {
        Map<String,int[]> result=new HashMap<String,int[]>();
        try(BufferedReader in=reader("vanilla_spawn_egg_colors_1_20_1.tsv")) {
            String line;while((line=in.readLine())!=null){if(line.isEmpty())continue;String[] f=line.split("\t");result.put(f[0],new int[]{Integer.parseInt(f[1],16),Integer.parseInt(f[2],16)});}
        }catch(IOException error){throw new ExceptionInInitializerError(error);}
        return Collections.unmodifiableMap(result);
    }
    private static BufferedReader reader(String path)throws IOException {
        InputStream in=VanillaItemSprites.class.getResourceAsStream("/assets/newhorizon/"+path);
        if(in==null)throw new IOException(path);return new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
    }
    private static final class Model {String[] layers;float[] transform;}
}
