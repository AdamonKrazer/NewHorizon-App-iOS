package net.kdt.pojavlaunch.gecko;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Bounded display.Name/Lore projection. The original server NBT stays untouched. */
final class ThinContainerTag {
    String name;
    final List<String> lore=new ArrayList<>();
    static ThinContainerTag read(String hex){
        ThinContainerTag result=new ThinContainerTag();
        if(hex==null || hex.length()>131072 || (hex.length()&1)!=0)return result;
        try {
            byte[] bytes=new byte[hex.length()/2];
            for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
            ByteBuffer input=ByteBuffer.wrap(bytes);
            if(!input.hasRemaining() || input.get()!=10)return result;
            ThinItemTag.string(input);compound(input,result,"",0);
        }catch(RuntimeException ignored){return new ThinContainerTag();}
        return result;
    }
    private static void compound(ByteBuffer input,ThinContainerTag result,String context,int depth){
        if(depth>16)throw new IllegalArgumentException("NBT depth");
        for(int fields=0;fields<1024;fields++){
            int type=input.get()&255;if(type==0)return;
            String key=ThinItemTag.string(input);
            if(type==10){compound(input,result,context+"/"+key,depth+1);continue;}
            if(type==8 && context.equals("/display") && key.equals("Name")){
                result.name=ThinItemTag.string(input);continue;
            }
            if(type==9 && context.equals("/display") && key.equals("Lore")){
                int child=input.get()&255,count=input.getInt();
                if(count<0 || count>1024 || (child==0 && count>0))throw new IllegalArgumentException("NBT lore count");
                for(int i=0;i<count;i++){
                    if(child==8){String line=ThinItemTag.string(input);if(i<64)result.lore.add(line);}
                    else skip(input,child,depth+1);
                }
                continue;
            }
            skip(input,type,depth+1);
        }
        throw new IllegalArgumentException("NBT fields");
    }
    private static void skip(ByteBuffer input,int type,int depth){
        if(depth>16)throw new IllegalArgumentException("NBT depth");
        int size=0;
        switch(type){
            case 0:return;case 1:size=1;break;case 2:size=2;break;
            case 3:case 5:size=4;break;case 4:case 6:size=8;break;
            case 7:case 11:case 12:{int count=input.getInt(),scale=type==7?1:type==11?4:8;
                if(count<0 || count>input.remaining()/scale)throw new IllegalArgumentException("NBT array");size=count*scale;break;}
            case 8:ThinItemTag.string(input);return;
            case 9:{int child=input.get()&255,count=input.getInt();if(count<0 || count>4096 || (child==0 && count>0))throw new IllegalArgumentException("NBT list");
                for(int i=0;i<count;i++)skip(input,child,depth+1);return;}
            case 10:for(int i=0;i<1024;i++){int child=input.get()&255;if(child==0)return;ThinItemTag.string(input);skip(input,child,depth+1);}throw new IllegalArgumentException("NBT fields");
            default:throw new IllegalArgumentException("NBT type");
        }
        if(size>input.remaining())throw new IllegalArgumentException("NBT size");
        input.position(input.position()+size);
    }
}
