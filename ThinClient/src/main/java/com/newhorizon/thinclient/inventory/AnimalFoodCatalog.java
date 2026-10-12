package com.newhorizon.thinclient.inventory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Food capabilities exported from vanilla Animal.isFood and official item tags. */
final class AnimalFoodCatalog {
    private static final int[] FOODS=load();
    private AnimalFoodCatalog() { }
    static boolean accepts(int entity,String material) {
        int item=ItemInteractionCatalog.protocolId(material);
        return entity>=0&&entity<128&&item>=0&&Arrays.binarySearch(FOODS,(entity<<12)|item)>=0;
    }
    private static int[] load() {
        InputStream stream=AnimalFoodCatalog.class.getResourceAsStream("/assets/newhorizon/vanilla_animal_foods_1_20_1.tsv");
        if(stream==null)throw new IllegalStateException("Missing animal food catalog");
        ArrayList<Integer> food=new ArrayList<>();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            for(String row;(row=in.readLine())!=null;) {
                if(row.isEmpty()||row.charAt(0)=='#')continue;String[] v=row.split("\\t");
                if(v.length!=3)throw new IOException("Invalid animal food row");
                int entity=Integer.parseInt(v[0]),item=ItemInteractionCatalog.protocolId(v[2]);
                if(entity<0||entity>=128||item<0||food.size()>=512)throw new IOException("Invalid animal food entry");
                food.add((entity<<12)|item);
            }
        }catch(IOException|NumberFormatException error){throw new IllegalStateException("Invalid animal foods",error);}
        int[] result=new int[food.size()];for(int i=0;i<result.length;i++)result[i]=food.get(i);Arrays.sort(result);return result;
    }
}