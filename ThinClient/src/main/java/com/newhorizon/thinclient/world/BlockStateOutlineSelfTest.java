package com.newhorizon.thinclient.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class BlockStateOutlineSelfTest {
    public static void main(String[] args)throws Exception {run();if(args.length>0)compareOfficial(Paths.get(args[0]));}
    public static void run() {
        check(BlockStateOutline.stateCount()==24135,"all protocol-763 outline states");
        check(BlockStateOutline.maxBoxCount()==15,"bounded most complex outline");
        check(BlockStateOutline.offset(2005,0,0,0)==0&&BlockStateOutline.renderOffset(2005,0,0,0)==-.25,
                "grass visual offset does not move the vanilla selection outline");
        int empty=0,nonCube=0;
        for(int state=0;state<BlockStateOutline.stateCount();state++) {
            int count=BlockStateOutline.boxCount(state);if(count==0)empty++;if(count!=1)nonCube++;
            for(int box=0;box<count;box++)for(int axis=0;axis<3;axis++) {
                float lo=BlockStateOutline.coordinate(state,box,axis),hi=BlockStateOutline.coordinate(state,box,axis+3);
                check(Float.isFinite(lo)&&Float.isFinite(hi)&&lo<hi,"finite positive outline volume "+state);
            }
        }
        check(empty>0&&nonCube>0,"empty and compound outlines");
        check(BlockStateOutline.boxCount(0)==0&&BlockStateOutline.boxCount(80)==0&&BlockStateOutline.boxCount(96)==0,"air and fluid excluded");
        check(BlockStateOutline.boxCount(2355)==1&&BlockStateOutline.coordinate(2355,0,0)==.375f
                &&BlockStateOutline.coordinate(2355,0,4)==.625f,"torch narrow outline, not a unit cube");
        BlockStateOutline.Hit out=new BlockStateOutline.Hit();
        check(BlockStateOutline.hit(2355,.5,2,.5,0,-1,0,0,0,0,5,out)&&out.face==1&&Math.abs(out.distance-1.375)<1e-9,"torch exact top and UP face");
        check(BlockStateOutline.hit(2355,.5,.5,-1,0,0,1,0,0,0,5,out)&&out.face==2&&Math.abs(out.distance-1.375)<1e-9,"torch exact north side");
        check(!BlockStateOutline.hit(2355,.1,.5,-1,0,0,1,0,0,0,5,out),"ray through empty space beside torch misses");
        check(!BlockStateOutline.hit(2355,.5,2,.5,0,-1,0,0,0,0,1,out),"reach limit excludes distant torch");
        check(BlockStateOutline.hit(2355,-1037.5,74,-1549.5,0,-1,0,-1038,72,-1550,5,out)
                &&out.face==1&&Math.abs(out.distance-1.375)<1e-9,"negative world positions retain exact hit");
        check(BlockStateOutline.hit(1,.5,.5,.5,1,0,0,0,0,0,5,out)&&out.inside&&out.face==4&&out.distance==0,"inside solid outline");
        for(int face=0;face<6;face++) {
            int axis=face<2?1:face<4?2:0;double[] o={.5,.5,.5},d={0,0,0};
            o[axis]=(face&1)==0?-1:2;d[axis]=(face&1)==0?1:-1;
            check(BlockStateOutline.hit(1,o[0],o[1],o[2],d[0],d[1],d[2],0,0,0,5,out)
                    &&out.face==face&&out.distance==1,"all six placement faces: "+face);
        }
        int waterlogged=0;
        for(int state=0;state<BlockStateOutline.stateCount();state++)if((BlockStatePhysics.flags(state)&BlockStatePhysics.WATER)!=0
                &&BlockStateOutline.boxCount(state)>0)waterlogged++;
        check(waterlogged>100,"waterlogged solid states remain targetable");
        System.out.println("Outline tests passed: 24135 states, torch/wall/compound shapes, fluid exclusion, "+waterlogged+" waterlogged outlines, exact faces and reach");
    }
    /** Fixtures were emitted separately by official BlockState.getShape at each position. */
    private static void compareOfficial(Path file)throws IOException {
        int previousState=-1,previousX=0,previousY=0,previousZ=0,box=0,checked=0;
        try(BufferedReader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            for(String row;(row=in.readLine())!=null;) {
                if(row.isEmpty()||row.charAt(0)=='#')continue;String[] v=row.split(" ");
                int state=Integer.parseInt(v[0]),x=Integer.parseInt(v[1]),y=Integer.parseInt(v[2]),z=Integer.parseInt(v[3]);
                if(state!=previousState||x!=previousX||y!=previousY||z!=previousZ)box=0;
                for(int c=0;c<6;c++) {
                    double expected=Double.parseDouble(v[4+c]),actual=BlockStateOutline.coordinateAt(state,box,c,x,z);
                    check(Math.abs(actual-expected)<2e-7,"official coordinate state="+state+" at="+x+","+y+","+z+" box="+box+" c="+c+" expected="+expected+" got="+actual);
                }
                previousState=state;previousX=x;previousY=y;previousZ=z;box++;checked++;
            }
        }
        check(checked>100000,"broad independent shape comparison");
        System.out.println("Compared "+checked+" official AABBs at zero, positive and negative world coordinates");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}