package com.newhorizon.thinclient.render;

import java.util.*;

/** Small bounded chat-component decoder; no Android or new runtime dependency. */
final class LabelText {
    static final int[] COLORS={0x000000,0x0000aa,0x00aa00,0x00aaaa,0xaa0000,0xaa00aa,0xffaa00,0xaaaaaa,0x555555,0x5555ff,0x55ff55,0x55ffff,0xff5555,0xff55ff,0xffff55,0xffffff};
    static final String[] NAMES={"black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white"};
    final int[] code=new int[256],color=new int[256],style=new int[256];int size;
    static LabelText parse(String json){
        LabelText out=new LabelText();
        try{out.append(new Parser(json).value(0),0xffffff,0,0);}catch(RuntimeException ignored){}
        return out;
    }
    private void append(Object obj,int rgb,int flags,int depth){
        if(depth>16||size==code.length||obj==null)return;
        if(obj instanceof List){for(Object item:(List<?>)obj)append(item,rgb,flags,depth+1);return;}
        if(!(obj instanceof Map)){literal(String.valueOf(obj),rgb,flags);return;}
        Map<?,?> m=(Map<?,?>)obj;Object c=m.get("color");
        for(int i=0;i<NAMES.length;i++)if(NAMES[i].equals(c))rgb=COLORS[i];
        if(c instanceof String&&((String)c).matches("#[0-9a-fA-F]{6}"))rgb=Integer.parseInt(((String)c).substring(1),16);
        String[] keys={"bold","italic","underlined","strikethrough"};
        for(int i=0;i<keys.length;i++)if(m.containsKey(keys[i]))flags=Boolean.TRUE.equals(m.get(keys[i]))?flags|(1<<i):flags&~(1<<i);
        Object text=m.get("text");if(text==null)text=m.get("fallback");if(text==null)text=m.get("translate");if(text==null)text=m.get("keybind");
        if(text!=null)literal(String.valueOf(text),rgb,flags);
        append(m.get("extra"),rgb,flags,depth+1);
    }
    private void literal(String text,int rgb,int flags){
        for(int i=0;i<text.length()&&size<code.length;){
            int ch=text.codePointAt(i);i+=Character.charCount(ch);
            if(ch==167&&i<text.length()){
                char f=Character.toLowerCase(text.charAt(i++));int n="0123456789abcdef".indexOf(f);
                if(n>=0){rgb=COLORS[n];flags=0;}else if(f=='l')flags|=1;else if(f=='o')flags|=2;
                else if(f=='n')flags|=4;else if(f=='m')flags|=8;else if(f=='r'){rgb=0xffffff;flags=0;}
                continue;
            }
            code[size]=ch;color[size]=rgb;style[size++]=flags;
        }
    }
    private static final class Parser{
        final String s;int i,nodes;Parser(String s){if(s.length()>4096)throw new IllegalArgumentException();this.s=s;}
        void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        Object value(int depth){
            if(depth>20||++nodes>1024)throw new IllegalArgumentException();ws();char c=s.charAt(i);
            if(c=='"')return string();
            if(c=='{'||c=='['){boolean map=c=='{';i++;Map<String,Object> m=new LinkedHashMap<>();List<Object>a=new ArrayList<>();ws();
                if(s.charAt(i)==(map?'}':']')){i++;return map?m:a;}
                for(;;){if(map){ws();String key=string();ws();if(s.charAt(i++)!=':')throw new IllegalArgumentException();m.put(key,value(depth+1));}else a.add(value(depth+1));
                    ws();char sep=s.charAt(i++);if(sep==(map?'}':']'))return map?m:a;if(sep!=',')throw new IllegalArgumentException();}
            }
            int start=i;while(i<s.length()&&",]} \r\n\t".indexOf(s.charAt(i))<0)i++;String v=s.substring(start,i);
            if(v.equals("true"))return true;if(v.equals("false"))return false;if(v.equals("null"))return null;if(v.isEmpty())throw new IllegalArgumentException();return v;
        }
        String string(){if(s.charAt(i++)!='"')throw new IllegalArgumentException();StringBuilder out=new StringBuilder();
            while(i<s.length()){char c=s.charAt(i++);if(c=='"')return out.toString();if(c=='\\'){c=s.charAt(i++);switch(c){case 'u':c=(char)Integer.parseInt(s.substring(i,i+4),16);i+=4;break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'b':c='\b';break;case 'f':c='\f';break;default:break;}}out.append(c);}throw new IllegalArgumentException();
        }
    }
}
