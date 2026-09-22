package com.newhorizon.thinclient.world;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** One bounded downloader; no render/network thread waits for a player's skin. */
public final class PlayerSkins implements AutoCloseable {
    public static final int CAPACITY=64;
    private static final String[] NAMES={"alex","ari","efe","kai","makena","noor","steve","sunny","zuri"};
    private static final Pattern SKIN=Pattern.compile("\"SKIN\"\\s*:\\s*\\{([^}]{0,8192})");
    private static final Pattern URL=Pattern.compile("\"url\"\\s*:\\s*\"(https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{32,64})\"");
    private static final Pattern SLIM=Pattern.compile("\"model\"\\s*:\\s*\"slim\"");
    private final LinkedHashMap<UUID,Slot> slots=new LinkedHashMap<>(64,.75f,true);
    private final Skin[] defaults=new Skin[18];
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"NH-player-skins");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private long revision;
    private boolean closed;
    public static final class Skin {
        public final int[] pixels;
        public final boolean slim;
        public final String resource;
        Skin(int[] pixels,boolean slim,String resource){this.pixels=pixels;this.slim=slim;this.resource=resource;}
        public String encoded(){ByteBuffer b=ByteBuffer.allocate(64*64*4);for(int c:pixels)b.putInt(c);return Base64.getEncoder().encodeToString(b.array());}
    }
    public static final class Slot {
        public final UUID id;
        public final int index;
        public volatile Skin skin;
        public volatile long revision;
        private String property="";
        private long used,retry;
        private boolean pending;
        Slot(UUID id,int index,Skin skin){this.id=id;this.index=index;this.skin=skin;}
    }
    public static int defaultIndex(UUID id){return id==null?15:Math.floorMod(id.hashCode(),18);}
    public static String defaultResource(UUID id){int i=defaultIndex(id);return "player/"+(i<9?"slim/":"wide/")+NAMES[i%9];}
    private Skin fallback(UUID id){int i=defaultIndex(id);if(defaults[i]==null){String resource=defaultResource(id);
        try(InputStream in=PlayerSkins.class.getResourceAsStream("/assets/newhorizon/entities/"+resource+".png")){if(in==null)throw new IOException("Missing "+resource);defaults[i]=new Skin(normalize(ImageIO.read(in)),i<9,resource);}catch(IOException e){throw new IllegalStateException("Default player skin",e);}}
        return defaults[i];
    }
    public synchronized Slot request(UUID id,String property){
        if(id==null||closed)return null;long now=System.nanoTime();Slot slot=slots.get(id);
        if(slot==null){int index=slots.size();if(index>=CAPACITY){Slot victim=null;for(Slot s:slots.values())if(!s.pending&&now-s.used>1_000_000_000L){victim=s;break;}if(victim==null)return null;slots.remove(victim.id);index=victim.index;}
            slot=new Slot(id,index,fallback(id));slot.revision=++revision;slots.put(id,slot);}
        slot.used=now;String value=property==null?"":property;
        if(!slot.property.equals(value)){slot.property=value;slot.retry=0;slot.skin=fallback(id);slot.revision=++revision;}
        if(!slot.pending&&!value.isEmpty()&&now>=slot.retry){String[] decoded=texture(value);slot.retry=Long.MAX_VALUE;
            if(decoded!=null){final Slot target=slot;final String token=value;target.pending=true;
                try{executor.execute(()->load(target,token,decoded[0],"slim".equals(decoded[1])));}catch(RejectedExecutionException ignored){slot.pending=false;slot.retry=now+1_000_000_000L;}}}
        return slot;
    }
    public synchronized Slot[] snapshot(){return slots.values().toArray(new Slot[0]);}
    private void load(Slot slot,String property,String url,boolean slim){Skin result=null;
        try{HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(4000);connection.setReadTimeout(4000);connection.setInstanceFollowRedirects(false);
            try{if(connection.getResponseCode()!=200)throw new IOException("Skin HTTP");try(InputStream in=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1){if(bytes.size()+n>262144)throw new IOException("Skin size");bytes.write(block,0,n);}
                byte[] png=bytes.toByteArray();if(png.length<24||ByteBuffer.wrap(png).getLong()!=0x89504e470d0a1a0aL)throw new IOException("Skin PNG");
                ByteBuffer header=ByteBuffer.wrap(png);int w=header.getInt(16),h=header.getInt(20);if(w!=64||(h!=32&&h!=64))throw new IOException("Skin dimensions");
                result=new Skin(normalize(ImageIO.read(new ByteArrayInputStream(png))),slim,"");
            }}finally{connection.disconnect();}
        }catch(Exception ignored){};
        synchronized(this){slot.pending=false;if(closed||slots.get(slot.id)!=slot||!slot.property.equals(property)){slot.retry=0;return;}
            if(result!=null){slot.skin=result;slot.revision=++revision;}else slot.retry=System.nanoTime()+60_000_000_000L;}
    }
    /** Only the texture host used by vanilla is fetched; properties cannot request local resources. */
    public static String[] texture(String property){
        try{if(property.length()>32768)return null;String json=new String(Base64.getDecoder().decode(property),StandardCharsets.UTF_8);Matcher skin=SKIN.matcher(json);if(!skin.find())return null;
            Matcher url=URL.matcher(skin.group(1));if(!url.find())return null;return new String[]{url.group(1).replace("http:","https:"),SLIM.matcher(skin.group(1)).find()?"slim":"wide"};
        }catch(IllegalArgumentException e){return null;}
    }
    public static int[] normalize(BufferedImage image)throws IOException{
        if(image==null||image.getWidth()!=64||(image.getHeight()!=32&&image.getHeight()!=64))throw new IOException("Invalid player skin");
        int[] pixels=new int[4096];image.getRGB(0,0,64,image.getHeight(),pixels,0,64);
        if(image.getHeight()==32){
            copy(pixels,24,48,20,52,4,16,8,20);copy(pixels,28,48,24,52,8,16,12,20);
            copy(pixels,20,52,16,64,8,20,12,32);copy(pixels,24,52,20,64,4,20,8,32);
            copy(pixels,28,52,24,64,0,20,4,32);copy(pixels,32,52,28,64,12,20,16,32);
            copy(pixels,40,48,36,52,44,16,48,20);copy(pixels,44,48,40,52,48,16,52,20);
            copy(pixels,36,52,32,64,48,20,52,32);copy(pixels,40,52,36,64,44,20,48,32);
            copy(pixels,44,52,40,64,40,20,44,32);copy(pixels,48,52,44,64,52,20,56,32);
            boolean opaque=true;for(int y=0;y<32;y++)for(int x=32;x<64;x++)if((pixels[y*64+x]>>>24)<128)opaque=false;
            if(opaque)for(int y=0;y<32;y++)for(int x=32;x<64;x++)pixels[y*64+x]&=0xffffff;
        }
        opaque(pixels,0,0,32,16);opaque(pixels,0,16,64,32);opaque(pixels,16,48,48,64);return pixels;
    }
    private static void opaque(int[] p,int x0,int y0,int x1,int y1){for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++)p[y*64+x]|=0xff000000;}
    private static void copy(int[] p,int dx0,int dy0,int dx1,int dy1,int sx0,int sy0,int sx1,int sy1){for(int y=0;y<dy1-dy0;y++)for(int x=0;x<Math.abs(dx1-dx0);x++)p[(dy0+y)*64+(dx1<dx0?dx0-1-x:dx0+x)]=p[(sy0+y)*64+sx0+x];}
    public synchronized void close(){closed=true;executor.shutdownNow();slots.clear();}
}
