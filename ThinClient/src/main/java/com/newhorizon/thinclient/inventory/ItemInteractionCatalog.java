package com.newhorizon.thinclient.inventory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Small capability table exported from the official 1.20.1 Item registry.
 * This selects a server action; it never predicts that the target accepts it. */
public final class ItemInteractionCatalog {
    private static final String RESOURCE="/assets/newhorizon/vanilla_item_interactions_1_20_1.tsv";
    private static final int ITEM_COUNT=1255;
    private static final ItemInteractionCatalog INSTANCE=new ItemInteractionCatalog();
    private final String[] names=new String[ITEM_COUNT];
    private final short[] ids=new short[ITEM_COUNT];
    private final byte[] flags=new byte[ITEM_COUNT];

    private ItemInteractionCatalog() {
        InputStream stream=ItemInteractionCatalog.class.getResourceAsStream(RESOURCE);
        if(stream==null)throw new IllegalStateException("Missing "+RESOURCE);
        String[] rows=new String[ITEM_COUNT];int count=0;
        boolean[] seen=new boolean[ITEM_COUNT];
        try(BufferedReader in=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            for(String row;(row=in.readLine())!=null;) {
                if(row.isEmpty()||row.charAt(0)=='#')continue;
                String[] fields=row.split("\\t");
                if(fields.length!=3)throw new IOException("Invalid item interaction row");
                int id=Integer.parseInt(fields[0]),capability=Integer.parseInt(fields[2]);
                if(id<0||id>=ITEM_COUNT||seen[id]||capability<0||capability>7
                        ||!fields[1].matches("[a-z0-9_]+"))throw new IOException("Invalid item interaction entry");
                seen[id]=true;
                rows[count++]=fields[1]+"\t"+id+"\t"+capability;
            }
            if(count!=ITEM_COUNT)throw new IOException("Incomplete item interaction registry: "+count);
            Arrays.sort(rows);
            for(int i=0;i<count;i++) {
                String[] fields=rows[i].split("\\t");
                names[i]=fields[0];ids[i]=(short)Integer.parseInt(fields[1]);flags[i]=(byte)Integer.parseInt(fields[2]);
                if(i>0&&names[i].equals(names[i-1]))throw new IOException("Duplicate item name");
            }
        } catch(IOException|NumberFormatException error) {throw new IllegalStateException("Invalid vanilla item interactions",error);}
    }
    private static int index(String material) {
        if(material==null)return -1;
        String key=material.toLowerCase(Locale.ROOT);
        if(key.startsWith("minecraft:"))key=key.substring(10);
        if(key.indexOf(':')>=0)return -1;
        return Arrays.binarySearch(INSTANCE.names,key);
    }
    public static int protocolId(String material) {int index=index(material);return index<0?-1:INSTANCE.ids[index]&65535;}
    public static boolean blockItem(String material) {return has(material,1);}
    public static boolean blockAction(String material) {return has(material,2);}
    /** BucketItem.use performs its own fluid ray cast; useOn alone does not place/fill it. */
    public static boolean bucket(String material) {return has(material,4);}
    private static boolean has(String material,int flag) {int index=index(material);return index>=0&&(INSTANCE.flags[index]&flag)!=0;}
}