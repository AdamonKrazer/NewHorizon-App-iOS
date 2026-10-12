package com.newhorizon.thinbridge;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Main-thread, bounded discovery of existing WebDisplays block screens. No chunk loads. */
final class BlockScreenBridge {
    static final String CHANNEL="newhorizon:display_v1";
    private final JavaPlugin plugin;
    private final Map<UUID,State> clients=new HashMap<>();
    private boolean warned;
    private long lastWork;
    private int cursor;
    private static final class State {
        UUID world;int scan;
        final Map<UUID,Screen> sent=new LinkedHashMap<>();
    }
    private static final class Screen {
        UUID id;int bx,by,bz,side,w,h,pw,ph,rotation;double x,y,z;String dimension,url;
        String signature(){return x+":"+y+":"+z+":"+side+":"+w+":"+h+":"+pw+":"+ph+":"+rotation+":"+url;}
    }
    BlockScreenBridge(JavaPlugin plugin){this.plugin=plugin;plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin,CHANNEL);}
    void join(Player player){clients.put(player.getUniqueId(),new State());}
    void quit(UUID player){clients.remove(player);}
    void update(){
        long now=System.nanoTime();if(now-lastWork<100_000_000L||clients.isEmpty())return;lastWork=now;
        List<UUID> ids=new ArrayList<>(clients.keySet());
        // Four chunks total per pass, independent of the number of connected players.
        for(int n=0;n<4&&!ids.isEmpty();n++){
            UUID id=ids.get(Math.floorMod(cursor++,ids.size()));Player p=plugin.getServer().getPlayer(id);State s=clients.get(id);
            if(p==null||!p.isOnline()){clients.remove(id);continue;}
            try{scan(p,s);}catch(ReflectiveOperationException|RuntimeException error){if(!warned){warned=true;plugin.getLogger().warning("Block screen bridge: "+error);}}
        }
    }
    private void scan(Player p,State s)throws ReflectiveOperationException{
        Location at=p.getLocation();UUID world=p.getWorld().getUID();
        if(!world.equals(s.world)){for(UUID id:s.sent.keySet())remove(p,id);s.sent.clear();s.world=world;s.scan=0;}
        Iterator<Screen> old=s.sent.values().iterator();
        while(old.hasNext()){Screen screen=old.next();double dx=screen.x-at.getX(),dy=screen.y-at.getY(),dz=screen.z-at.getZ();
            if(dx*dx+dy*dy+dz*dz>64*64){remove(p,screen.id);old.remove();}}
        // Center-first rings keep the local screens responsive without generating chunks.
        int index=s.scan++%49,dx=0,dz=0;
        if(index>0){int radius=(int)Math.ceil((Math.sqrt(index+1)-1)/2),start=(2*radius-1)*(2*radius-1),offset=index-start,edge=2*radius;
            if(offset<edge){dx=-radius+offset;dz=-radius;}else if(offset<edge*2){dx=radius;dz=-radius+offset-edge;}
            else if(offset<edge*3){dx=radius-(offset-edge*2);dz=radius;}else{dx=-radius;dz=radius-(offset-edge*3);}}
        int cx=(at.getBlockX()>>4)+dx,cz=(at.getBlockZ()>>4)+dz;
        Set<UUID> seen=new HashSet<>();
        if(p.getWorld().isChunkLoaded(cx,cz)){
            Chunk chunk=p.getWorld().getChunkAt(cx,cz);
            Object handle=chunkHandle(chunk);
            Map<?,?> tiles=(Map<?,?>)method(handle.getClass(),"getBlockEntities","m_62954_").invoke(handle);
            int examined=0;
            for(Map.Entry<?,?> entry:tiles.entrySet()){
                if(++examined>256)break;
                Object tile=entry.getValue();if(!tile.getClass().getName().equals("net.montoyo.wd.entity.ScreenBlockEntity"))continue;
                Object pos=entry.getKey();int bx=coordinate(pos,"getX","m_123341_"),by=coordinate(pos,"getY","m_123342_"),bz=coordinate(pos,"getZ","m_123343_");
                int count=((Number)method(tile.getClass(),"screenCount").invoke(tile)).intValue();
                for(int i=0;i<Math.min(6,count);i++){
                    Object data=tile.getClass().getMethod("getScreen",int.class).invoke(tile,i);
                    Screen screen=decode(p,data,bx,by,bz);if(screen==null)continue;seen.add(screen.id);
                    Screen previous=s.sent.get(screen.id);
                    if(previous==null&&s.sent.size()>=24)continue;
                    if(previous==null||!previous.signature().equals(screen.signature())){spawn(p,screen);s.sent.put(screen.id,screen);
                        if(previous==null)plugin.getLogger().info("Block screen synchronized: "+p.getName()+" at "+bx+","+by+","+bz+" side="+screen.side);}
                }
            }
        }
        Iterator<Screen> it=s.sent.values().iterator();while(it.hasNext()){Screen screen=it.next();if((screen.bx>>4)==cx&&(screen.bz>>4)==cz&&!seen.contains(screen.id)){remove(p,screen.id);it.remove();}}
    }
    private static Screen decode(Player p,Object data,int bx,int by,int bz)throws ReflectiveOperationException{
        Object side=field(data,"side"),size=field(data,"size"),res=field(data,"resolution");
        if(side==null||size==null||res==null)return null;Screen s=new Screen();s.bx=bx;s.by=by;s.bz=bz;
        s.side=((Enum<?>)side).ordinal();s.w=number(size,"x");s.h=number(size,"y");if(s.w<1||s.w>16||s.h<1||s.h>16)return null;
        s.pw=Math.max(16,Math.min(1024,number(res,"x")));s.ph=Math.max(16,Math.min(1024,number(res,"y")));
        s.rotation=((Enum<?>)field(data,"rotation")).ordinal();
        s.dimension=p.getWorld().getKey().toString();s.id=UUID.nameUUIDFromBytes((s.dimension+":"+bx+":"+by+":"+bz+":"+s.side).getBytes(StandardCharsets.UTF_8));
        s.url=String.valueOf(field(data,"url"));
        if(Boolean.TRUE.equals(field(data,"useRegisteredLinks"))){Object links=field(data,"registeredLinks");if(links instanceof List&&!((List<?>)links).isEmpty())s.url=String.valueOf(((List<?>)links).get(Math.floorMod(s.id.hashCode(),((List<?>)links).size())));}
        if(s.url.getBytes(StandardCharsets.UTF_8).length>4096)return null;
        Object right=field(side,"right"),up=field(side,"up"),forward=field(side,"forward");
        s.x=bx+.5+number(right,"x")*(s.w-1)*.5+number(up,"x")*(s.h-1)*.5+number(forward,"x")*.5;
        s.y=by+.5+number(right,"y")*(s.w-1)*.5+number(up,"y")*(s.h-1)*.5+number(forward,"y")*.5;
        s.z=bz+.5+number(right,"z")*(s.w-1)*.5+number(up,"z")*(s.h-1)*.5+number(forward,"z")*.5;
        return s;
    }
    private static Object chunkHandle(Chunk chunk)throws ReflectiveOperationException{
        try{return chunk.getClass().getMethod("getHandle").invoke(chunk);}catch(NoSuchMethodException ignored){}
        for(Method method:chunk.getClass().getMethods())if(method.getName().equals("getHandle")&&method.getParameterCount()==1){
            Class<?> status=method.getParameterTypes()[0];
            for(Field field:status.getFields())if(Modifier.isStatic(field.getModifiers())&&field.getType()==status){
                Object value=field.get(null);String name=String.valueOf(value);
                if(name.equals("minecraft:full")||name.equals("full")||field.getName().equals("FULL"))return method.invoke(chunk,value);
            }
        }
        throw new NoSuchMethodException("CraftChunk full handle");
    }
    private static Method method(Class<?> type,String...names)throws NoSuchMethodException{for(String name:names)try{return type.getMethod(name);}catch(NoSuchMethodException ignored){}throw new NoSuchMethodException(type.getName()+Arrays.toString(names));}
    private static int coordinate(Object o,String...names)throws ReflectiveOperationException{return ((Number)method(o.getClass(),names).invoke(o)).intValue();}
    private static Object field(Object o,String name)throws ReflectiveOperationException{return o.getClass().getField(name).get(o);}
    private static int number(Object o,String name)throws ReflectiveOperationException{return ((Number)field(o,name)).intValue();}
    private static void varint(ByteBuffer b,int n){do{int next=n&127;n>>>=7;b.put((byte)(next|(n!=0?128:0)));}while(n!=0);}
    private static void string(ByteBuffer b,String s){byte[] utf=s.getBytes(StandardCharsets.UTF_8);varint(b,utf.length);b.put(utf);}
    private static ByteBuffer packet(int type){return ByteBuffer.allocate(4608).putInt(0x4e484431).put((byte)2).put((byte)type);}
    private void send(Player p,ByteBuffer b){p.sendPluginMessage(plugin,CHANNEL,Arrays.copyOf(b.array(),b.position()));}
    private void remove(Player p,UUID id){ByteBuffer b=packet(2);b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());send(p,b);}
    private void spawn(Player p,Screen s){ByteBuffer b=packet(1);b.putLong(s.id.getMostSignificantBits()).putLong(s.id.getLeastSignificantBits());string(b,s.dimension);
        b.putDouble(s.x).putDouble(s.y).putDouble(s.z).putFloat(0).putFloat(s.w).putFloat(s.h).put((byte)s.side);
        varint(b,0);varint(b,s.pw);varint(b,s.ph);string(b,s.url);b.put((byte)s.rotation);send(p,b);}
}
