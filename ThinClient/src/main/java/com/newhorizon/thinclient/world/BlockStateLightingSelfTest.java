package com.newhorizon.thinclient.world;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Original-state assertions; optional exhaustive independent vanilla fixture comparison. */
public final class BlockStateLightingSelfTest {
    public static void main(String[] args)throws Exception {run();if(args.length>0)compareProperties(Paths.get(args[0]));if(args.length>1)compareFaces(Paths.get(args[1]));}
    public static void run(){
        check(BlockStateLighting.stateCount()==24135,"complete protocol763 registry");
        check(BlockStateLighting.retainedBytes()<80*1024,"light catalog stays below80KiB");
        int emitters=0;
        for(int state=0;state<BlockStateLighting.stateCount();state++){
            int e=BlockStateLighting.emission(state),o=BlockStateLighting.opacity(state);
            check(e>=0&&e<=15&&o>=0&&o<=15,"nibble light range");if(e>0)emitters++;
            check(BlockStateLighting.sameLighting(state,state),"stable identical state");
        }
        check(emitters==1532,"all original emitting states");
        expect(0,0,0);expect(1,0,15);expect(519,0,0);expect(20942,0,15);
        for(int state=80;state<96;state++)expect(state,0,1);
        for(int state=96;state<112;state++)expect(state,15,1);
        expect(2355,14,0);expect(5859,10,0);expect(5738,7,0);expect(5739,0,0);
        expect(5864,15,15);expect(10583,15,15);expect(7417,15,15);expect(7418,0,15);
        check(BlockStateLighting.emission(-1)==0&&BlockStateLighting.opacity(-1)==15,"unknown states conservatively absorb");
        for(int face=0;face<6;face++){
            check(!BlockStateLighting.occludes(519,0,face),"glass has no light occlusion shape");
            check(!BlockStateLighting.occludes(2355,0,face),"torch sends light all directions");
            check(!BlockStateLighting.occludes(5864,0,face),"opaque glowstone still emits through its own face");
            check(BlockStateLighting.occludes(-1,0,face),"unknown shape conservatively closes");
        }
        int top=11022,bottom=11024;
        check(BlockStateLighting.useShapeForLightOcclusion(top)&&BlockStateLighting.canOcclude(top),"slabs use actual light shape");
        check(BlockStateLighting.occludes(bottom,0,0)&&!BlockStateLighting.occludes(bottom,0,1),"bottom slab closes below but opens above");
        check(BlockStateLighting.occludes(top,0,1)&&!BlockStateLighting.occludes(top,0,0),"top slab closes above but opens below");
        for(int face=2;face<6;face++){
            check(BlockStateLighting.occludes(bottom,top,face),"complementary slab faces fully occlude");
            check(!BlockStateLighting.occludes(bottom,bottom,face),"matching half slabs leave opening");
        }
        check(BlockStateLighting.sameLighting(0,519),"air and glass have identical light behavior");
        check(BlockStateLighting.sameLighting(80,95),"flowing water changes do not need relight");
        check(!BlockStateLighting.sameLighting(top,bottom),"slab orientation changes require relight");
        check(!BlockStateLighting.sameLighting(11023,11024),"waterlogging absorption changes require relight");
        check(!BlockStateLighting.sameLighting(5738,5739),"redstone torch toggles emission");
        check(!BlockStateLighting.sameLighting(0,1),"opaque placement changes propagation");
        System.out.println("Block state light catalog passed: 24135 states,1532 emitters, exact slab face unions, glass/water/tintedglass, normal/soul/redstone torches, sameLighting; retainedBytes="+BlockStateLighting.retainedBytes());
    }
    private static void expect(int state,int emission,int opacity){check(BlockStateLighting.emission(state)==emission&&BlockStateLighting.opacity(state)==opacity,"original light values state="+state);}
    private static void compareProperties(Path path)throws IOException{
        int count=0;
        try(BufferedReader in=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            for(String row;(row=in.readLine())!=null;){if(row.isEmpty()||row.charAt(0)=='#')continue;String[] v=row.split("\\t",6);int state=Integer.parseInt(v[0]);expect(state,Integer.parseInt(v[1]),Integer.parseInt(v[2]));int flag=Integer.parseInt(v[3]);check(BlockStateLighting.useShapeForLightOcclusion(state)==((flag&1)!=0)&&BlockStateLighting.canOcclude(state)==((flag&2)!=0),"original shape flags "+state);count++;}
        }
        check(count==24135,"every original property fixture");System.out.println("Compared all"+count+" original state light properties");
    }
    private static void compareFaces(Path path)throws IOException{
        int count=0,closed=0;
        try(BufferedReader in=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            for(String row;(row=in.readLine())!=null;){if(row.isEmpty()||row.charAt(0)=='#')continue;String[] v=row.split(" ");int a=Integer.parseInt(v[0]),b=Integer.parseInt(v[1]),face=Integer.parseInt(v[2]);boolean expected=v[3].equals("1");check(BlockStateLighting.occludes(a,b,face)==expected,"vanilla directional union "+row);check(BlockStateLighting.occludes(a,b,face)==BlockStateLighting.occludes(b,a,face^1),"reversing edge preserves union");count++;if(expected)closed++;}
        }
        check(count>1000&&closed>0&&closed<count,"broad directed shape union comparison");System.out.println("Compared"+count+" directed face pairs to original Shapes.faceShapeOccludes (closed="+closed+")");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}