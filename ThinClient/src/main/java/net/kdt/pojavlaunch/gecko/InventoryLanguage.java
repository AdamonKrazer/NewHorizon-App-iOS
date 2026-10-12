package net.kdt.pojavlaunch.gecko;

import java.io.*;
import java.util.*;

/** Official language table and contextual item-name keys, independent of Minecraft classes. */
final class InventoryLanguage {
    private final Map<String,String> translations=new HashMap<>();
    private final Map<String,String> names=new HashMap<>(),baseKeys=new HashMap<>();
    static InventoryLanguage read(InputStream language,InputStream itemKeys)throws IOException {
        InventoryLanguage result=new InventoryLanguage();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(language,"UTF-8"))) {
            String line;while((line=reader.readLine())!=null) {
                if(line.startsWith("#"))continue;int tab=line.indexOf('\t');
                if(tab>0)result.translations.put(line.substring(0,tab),unescape(line.substring(tab+1)));
            }
        }
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(itemKeys,"UTF-8"))) {
            String line;while((line=reader.readLine())!=null) {
                String[] values=line.split("\t",-1);
                if(values.length==4&&values[0].equals("n"))result.names.put(values[1]+"\t"+unescape(values[2]),values[3]);
                else if(values.length==3&&values[0].equals("b"))result.baseKeys.put(values[1],values[2]);
            }
        }
        return result;
    }
    String translation(String key,String fallback){return translations.getOrDefault(key,fallback);}
    String itemName(String material,String suppliedName,String potion) {
        if(material==null||material.isEmpty())return suppliedName==null?"":suppliedName;
        String id=material.toLowerCase(Locale.ROOT);if(id.indexOf(':')<0)id="minecraft:"+id;
        String supplied=suppliedName==null?"":suppliedName.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "").trim();
        String key=names.get(id+"\t"+supplied);
        // Unknown server/plugin names are literal. Explicit NBT names are handled before this method.
        if(!supplied.isEmpty()&&key==null&&!supplied.equals(pretty(id)))return supplied;
        if(id.equals("minecraft:potion")||id.equals("minecraft:splash_potion")
                ||id.equals("minecraft:lingering_potion")||id.equals("minecraft:tipped_arrow")) {
            if(potion!=null) {
                String effect=potion.replace("minecraft:","").replaceFirst("^(long_|strong_)","");
                return translation("item."+id.replace(':','.')+".effect."+effect,supplied.isEmpty()?pretty(id):supplied);
            }
            if(key==null)key="item."+id.replace(':','.')+".effect.empty";
        }
        if(key==null)key=baseKeys.get(id);
        if(key==null) {
            String item="item."+id.replace(':','.');
            key=translations.containsKey(item)?item:"block."+id.replace(':','.');
        }
        return translation(key,supplied.isEmpty()?pretty(id):supplied);
    }
    static String pretty(String material) {
        String value=material.substring(material.indexOf(':')+1).toLowerCase(Locale.ROOT);
        StringBuilder name=new StringBuilder();
        for(String part:value.split("_")){if(part.isEmpty())continue;if(name.length()>0)name.append(' ');name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));}
        return name.toString();
    }
    private static String unescape(String value) {
        StringBuilder out=new StringBuilder(value.length());
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='\\'&&i+1<value.length()) {
                c=value.charAt(++i);if(c=='n')c='\n';else if(c=='r')c='\r';else if(c=='t')c='\t';
            }
            out.append(c);
        }
        return out.toString();
    }
}
