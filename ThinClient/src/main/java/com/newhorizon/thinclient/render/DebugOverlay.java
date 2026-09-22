package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.ThinClientRuntime;
import com.newhorizon.thinclient.world.*;
import com.newhorizon.thinclient.protocol.ChatProtocol;
import java.util.Locale;

/** Multiplayer F3 layout and real counters for this renderer, sampled only while visible. */
public final class DebugOverlay {
    public final DebugFrames frames=new DebugFrames();
    public boolean chart,pie,help;
    public int fps,meshes,pending,vertices,entities,particles,width,height,sounds;
    public int targetEntity=-1;
    public float mood;
    public String vendor="",renderer="",glVersion="";
    private long previousNanos,previousRx,previousTx,previousHeap;
    private float rx,tx,allocation;
    private final EnvironmentState.Snapshot environment=new EnvironmentState.Snapshot();
    private final int[] blockTarget=new int[4],fluidTarget=new int[4];
    public String encode(ThinClientRuntime runtime,long now,double px,double py,double pz,float eyeHeight,float yaw,float pitch){
        Runtime vm=Runtime.getRuntime();long heap=vm.totalMemory()-vm.freeMemory(),max=vm.maxMemory(),allocated=vm.totalMemory();
        if(previousNanos!=0){double seconds=Math.max(.001,(now-previousNanos)/1e9);rx=(float)((runtime.debug.receivedPackets-previousRx)/seconds);tx=(float)((runtime.debug.sentPackets-previousTx)/seconds);allocation=(float)(Math.max(0,heap-previousHeap)/seconds/1048576);}
        previousNanos=now;previousRx=runtime.debug.receivedPackets;previousTx=runtime.debug.sentPackets;previousHeap=heap;
        int x=floor(px),y=floor(py),z=floor(pz),cx=x>>4,cz=z>>4;
        StringBuilder left=new StringBuilder(1700),right=new StringBuilder(1800);
        line(left,"Minecraft 1.20.1 (New Horizon Thin/LTW)");line(left,f("%d fps T: vsync",fps));
        line(left,f("C: %d/%d chunks D: %d, pC: %d",meshes,runtime.world.size(),SceneAtmosphere.VIEW_CHUNKS,pending));
        line(left,f("E: %d/%d, B: 0",entities,runtime.entities.count()));line(left,f("P: %d. T: %d",particles,runtime.entities.count()));
        line(left,f("\"%s\" server, %.0f tx, %.0f rx",runtime.debug.brand,tx,rx));
        line(left,f("Server view: %s, Simulation: %s",number(runtime.debug.viewDistance),number(runtime.debug.simulationDistance)));
        line(left,runtime.world.dimension()+" FC: N/A");line(left,"");
        if(runtime.debug.reduced){line(left,f("Chunk-relative: %d %d %d",x&15,y&15,z&15));line(left,"Reduced debug info: server enabled");}
        else {
            line(left,f("XYZ: %.3f / %.5f / %.3f",px,py,pz));line(left,f("Block: %d %d %d [%d %d %d]",x,y,z,x&15,y&15,z&15));
            line(left,f("Chunk: %d %d %d [%d %d in r.%d.%d.mca]",cx,y>>4,cz,cx&31,cz&31,cx>>5,cz>>5));
            int facing=Math.floorMod(Math.round(yaw/90),4);line(left,f("Facing: %s (%s) (%.1f / %.1f)",new String[]{"south","west","north","east"}[facing],new String[]{"Towards positive Z","Towards negative X","Towards negative Z","Towards positive X"}[facing],wrap(yaw),wrap(pitch)));
            if(!runtime.world.hasFullBlockData(x,y,z))line(left,"Waiting for chunk...");
            else {
                int sky=runtime.world.lightAt(true,x,y,z),block=runtime.world.lightAt(false,x,y,z);line(left,f("Client Light: %d (%d sky, %d block)",Math.max(sky,block),sky,block));
                line(left,"CH S: "+surface(runtime.world,x,z)+" M: "+runtime.world.rainSurface(x,z));line(left,"SH S: ?? O: ?? M: ?? ML: ??");
                com.newhorizon.thinclient.audio.BiomeSoundRegistry.Biome biome=runtime.soundBiomes.get(runtime.world.biomeAt(x,y,z));line(left,"Biome: "+(biome==null?"unknown":biome.name));
                runtime.environment.sample(now,environment);float difficulty=localDifficulty(runtime.debug.difficulty,environment.day*24000+environment.dayTime);
                line(left,difficulty<0?"Local Difficulty: ?? // ?? (Day "+environment.day+")":f("Local Difficulty: %.2f // %.2f (Day %d)",difficulty,Math.max(0,Math.min(1,(difficulty-2)/2)),environment.day));
                line(left,"Difficulty: "+(runtime.debug.difficulty<0?"unknown":new String[]{"peaceful","easy","normal","hard"}[runtime.debug.difficulty]));
            }
        }
        line(left,"SC: N/A (remote server)");line(left,f("Sounds: %d/24 (Mood %d%%)",sounds,(int)(mood*100)));line(left,"Shader: LTW terrain/entities + Gecko");
        line(left,f("Mesh: %,d vertices",vertices));line(left,frames.summary());line(left,"");
        line(left,"Debug: Pie [shift]: "+(pie?"visible":"hidden")+" FPS [alt]: "+(chart?"visible":"hidden"));line(left,"For help: press F3 + Q");
        if(help){line(left,"F3 / DEBUG: debug | F5 / 3RD: camera");line(left,"F1 / HUD: interface | TAB: player list");line(left,"Shift+F3: CPU phases | Alt+F3: frame time");}
        line(right,"Java: "+System.getProperty("java.version")+" "+System.getProperty("sun.arch.data.model","64")+"bit");
        line(right,f("Mem: %2d%% %03d/%03dMB",heap*100/Math.max(1,max),heap/1048576,max/1048576));
        line(right,f("Allocation rate: ~%.0fMB /s",allocation));line(right,f("Allocated: %2d%% %03dMB",allocated*100/Math.max(1,max),allocated/1048576));line(right,"");
        line(right,"CPU: "+vm.availableProcessors()+"x "+System.getProperty("os.arch"));line(right,"");
        line(right,f("Display: %dx%d (%s)",width,height,vendor));line(right,renderer);line(right,glVersion);
        if(!runtime.debug.reduced){
            targets(runtime.world,px,py+eyeHeight,pz,yaw,pitch,blockTarget,fluidTarget);
            DebugCatalog catalog=DebugCatalog.get();
            if(blockTarget[3]>=0){line(right,"");line(right,position("Targeted Block",blockTarget));line(right,catalog.block(blockTarget[3]));String properties=catalog.properties(blockTarget[3]);if(!properties.isEmpty())line(right,properties);
                appendTags(right,runtime.debug.tags("minecraft:block",catalog.blockId(blockTarget[3])));}
            if(fluidTarget[3]>=0){line(right,"");line(right,position("Targeted Fluid",fluidTarget));int state=fluidTarget[3],flags=BlockStatePhysics.flags(state),amount=BlockStatePhysics.fluidAmount(state);boolean lava=(flags&BlockStatePhysics.LAVA)!=0,wet=(flags&(BlockStatePhysics.LAVA|BlockStatePhysics.WATER))!=0,falling=(flags&BlockStatePhysics.FLUID_FALLING)!=0;
                String fluid=wet?"minecraft:"+(amount<8||falling?"flowing_":"")+(lava?"lava":"water"):"minecraft:empty";line(right,fluid);
                if(wet){line(right,"falling: "+falling);if(amount<8||falling)line(right,"level: "+amount);}appendTags(right,runtime.debug.tags("minecraft:fluid",catalog.fluidId(fluid)));}
            if(targetEntity>=0){int type=runtime.entities.typeOf(targetEntity);line(right,"");line(right,"Targeted Entity");line(right,catalog.entity(type));appendTags(right,runtime.debug.tags("minecraft:entity_type",type));}
        }
        return "{\"left\":"+ChatProtocol.quote(left.toString())+",\"right\":"+ChatProtocol.quote(right.toString())+",\"performance\":"+frames.json(chart,pie)+"}";
    }
    private static int surface(WorldChunkStore world,int x,int z){for(int y=world.minY()+Math.min(512,world.height())-1;y>=world.minY();y--){int s=world.blockStateAt(x,y,z);if(s>=0&&!BlockStatePhysics.isAir(s))return y+1;}return world.minY();}
    /** Debug has its own twenty-block ray and does not alter interaction reach. */
    static void targets(WorldChunkStore world,double ox,double oy,double oz,float yaw,float pitch,int[] block,int[] fluid){
        block[3]=fluid[3]=-1;double yr=Math.toRadians(yaw),pr=Math.toRadians(pitch),dx=-Math.sin(yr)*Math.cos(pr),dy=-Math.sin(pr),dz=Math.cos(yr)*Math.cos(pr);
        int lastX=Integer.MIN_VALUE,lastY=0,lastZ=0;
        for(double d=0;d<=20;d+=.025){int x=floor(ox+dx*d),y=floor(oy+dy*d),z=floor(oz+dz*d);if(x==lastX&&y==lastY&&z==lastZ)continue;lastX=x;lastY=y;lastZ=z;
            int state=world.blockStateAt(x,y,z);if(state<0)continue;int flags=BlockStatePhysics.flags(state);boolean wet=(flags&(BlockStatePhysics.WATER|BlockStatePhysics.LAVA))!=0;
            boolean solid=world.isTargetableBlock(x,y,z);
            if(fluid[3]<0&&(wet||solid))set(fluid,x,y,z,state);if(solid){set(block,x,y,z,state);return;}
        }
    }
    private static void set(int[] a,int x,int y,int z,int state){a[0]=x;a[1]=y;a[2]=z;a[3]=state;}
    private static String position(String label,int[] a){return label+": "+a[0]+", "+a[1]+", "+a[2];}
    private static void appendTags(StringBuilder out,String tags){if(!tags.isEmpty())line(out,tags.substring(1));}
    private static void line(StringBuilder out,String s){out.append(s).append('\n');}
    private static String number(int n){return n<0?"?":Integer.toString(n);}
    /** Vanilla multiplayer F3 uses zero inhabited time and zero moon influence without an integrated server. */
    public static float localDifficulty(int difficulty,long dayTime){return difficulty<0?-1:difficulty*(.75f+Math.max(0,Math.min(1,(dayTime-72000f)/1440000f))*.25f);}
    private static int floor(double n){return (int)Math.floor(n);}
    private static float wrap(float n){n%=360;return n>=180?n-360:n< -180?n+360:n;}
    private static String f(String format,Object...args){return String.format(Locale.ROOT,format,args);}
}
