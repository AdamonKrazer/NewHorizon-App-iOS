package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.render.FluidSurfaceMesher;
import java.nio.ByteBuffer;

/** Cross-chunk fluid regressions: hidden internal walls, shoreline depth and shared corners. */
public final class FluidSurfaceSelfTest {
    private static final int WATER=80, SAND=112;
    private FluidSurfaceSelfTest() {}
    public static void run() {
        ByteBuffer mesh=ByteBuffer.allocate(1024*1024);
        SurfaceChunk center=chunk(0,0),east=chunk(1,0);
        center.putFullBlockState(15,1,8,WATER);east.putFullBlockState(0,1,8,WATER);
        SurfaceChunk[] neighbors=new SurfaceChunk[9];neighbors[5]=east;
        int count=FluidSurfaceMesher.append(center,0,neighbors,mesh);
        require(count==30,"one connected water block has five external faces");
        require(faceCount(mesh,5)==0,"no east water curtain at loaded chunk boundary");
        require(FluidSurfaceMesher.sampleState(center,neighbors,16,1,8)==WATER,"east chunk sample");
        require(FluidSurfaceMesher.sampleState(center,neighbors,15,1,8)==WATER,"center sample without neighbors[4]");
        mesh.clear();FluidSurfaceMesher.append(center,0,null,mesh);
        require(faceCount(mesh,5)==0,"unknown outside boundary cannot create an ocean curtain");
        east.putFullBlockState(0,1,8,0);
        mesh.clear();FluidSurfaceMesher.append(center,0,neighbors,mesh);
        require(faceCount(mesh,5)==6,"known air across the chunk border reveals a real fluid edge");
        east.putFullBlockState(0,1,8,SAND);
        mesh.clear();FluidSurfaceMesher.append(center,0,neighbors,mesh);
        require(faceCount(mesh,5)==0,"sand across boundary hides coplanar fluid face");
        require(!FluidSurfaceMesher.wasTruncated(),"bounded ordinary mesh completed");

        SurfaceChunk flat=chunk(0,0);SurfaceChunk[] ocean=new SurfaceChunk[9];
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
            SurfaceChunk part=dx==0&&dz==0?flat:chunk(dx,dz);ocean[(dz+1)*3+dx+1]=part;
            for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                part.putFullBlockState(x,0,z,SAND);part.putFullBlockState(x,1,z,WATER);
            }
        }
        mesh.clear();count=FluidSurfaceMesher.append(flat,0,ocean,mesh);
        require(count==6,"uniform ocean remains one greedy surface, not 256 quads");
        for(int at=0;at<mesh.position();at+=16) {
            require(face(mesh,at)==1,"solid sand floor has no overlapping water bottom");
            near(mesh.getFloat(at+4),1+8/9f-FluidSurfaceMesher.EPSILON,"vanilla source height and inset");
        }
        mesh.clear();count=FluidSurfaceMesher.append(flat,0,null,mesh);
        require(count==6,"streaming frontier stays a single surface without skirts or sloping edges");
        for(int at=0;at<mesh.position();at+=16) {
            require(face(mesh,at)==1,"no artificial vertical ocean walls with unloaded neighbors");
            near(mesh.getFloat(at+4),1+8/9f-FluidSurfaceMesher.EPSILON,"unloaded border preserves the source level");
        }

        SurfaceChunk slope=chunk(0,0);
        slope.putFullBlockState(8,1,8,WATER);slope.putFullBlockState(9,1,8,84);
        mesh.clear();FluidSurfaceMesher.append(slope,0,null,mesh);
        assertSharedCorner(mesh,9,8,2);
        assertSharedCorner(mesh,9,9,2);
        float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
        for(int at=0;at<mesh.position();at+=16)if(face(mesh,at)==1) {
            low=Math.min(low,mesh.getFloat(at+4));high=Math.max(high,mesh.getFloat(at+4));
            require(((mesh.get(at+13)&255)>>>4&3)==1,"flow texture points downhill east consistently in both triangles");
        }
        require(high-low>.01f,"source and flowing level form a sloped surface");

        SurfaceChunk corner=chunk(0,0),right=chunk(1,0),south=chunk(0,1),diagonal=chunk(1,1);
        corner.putFullBlockState(15,1,15,WATER);right.putFullBlockState(0,1,15,WATER);
        south.putFullBlockState(15,1,0,WATER);diagonal.putFullBlockState(0,1,0,WATER);
        diagonal.putFullBlockState(0,2,0,WATER);
        SurfaceChunk[] around=new SurfaceChunk[9];around[5]=right;around[7]=south;around[8]=diagonal;
        mesh.clear();FluidSurfaceMesher.append(corner,0,around,mesh);
        boolean found=false;
        for(int at=0;at<mesh.position();at+=16)if(face(mesh,at)==1
                &&mesh.getFloat(at)==16&&mesh.getFloat(at+8)==16) {
            near(mesh.getFloat(at+4),2-FluidSurfaceMesher.EPSILON,"diagonal water above raises shared corner");found=true;
        }
        require(found,"diagonal chunk contributes corner geometry");

        SurfaceChunk waterfall=chunk(0,0);waterfall.putFullBlockState(8,1,8,WATER);waterfall.putFullBlockState(8,2,8,WATER);
        mesh.clear();FluidSurfaceMesher.append(waterfall,0,null,mesh);
        int joined=0;
        for(int at=0;at<mesh.position();at+=16)if(face(mesh,at)>=2) {
            float y=mesh.getFloat(at+4);
            require(Math.abs(y-(2+FluidSurfaceMesher.EPSILON))>.00001f,"no epsilon slit between stacked fluid side faces");
            if(y==2)joined++;
        }
        require(joined>0,"stacked fluid walls meet at the exact shared level");

        SurfaceChunk slab=chunk(0,0);slab.putFullBlockState(8,1,8,11023);
        mesh.clear();FluidSurfaceMesher.append(slab,0,null,mesh);
        require(faceCount(mesh,1)==6&&faceCount(mesh,0)==0,"bottom waterlogged slab keeps top fluid and hides its solid bottom");
        slab.putFullBlockState(8,1,8,11021);
        mesh.clear();FluidSurfaceMesher.append(slab,0,null,mesh);
        require(faceCount(mesh,1)==0&&faceCount(mesh,0)==6,"top waterlogged slab closes the top face");

        SurfaceChunk isolated=chunk(0,0);isolated.putFullBlockState(8,1,8,WATER);
        mesh.clear();FluidSurfaceMesher.append(isolated,0,null,mesh);
        for(int at=0;at<mesh.position();at+=16) {
            int face=face(mesh,at);float x=mesh.getFloat(at),y=mesh.getFloat(at+4),z=mesh.getFloat(at+8);
            require(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z),"finite fluid mesh");
            if(face==0)near(y,1+FluidSurfaceMesher.EPSILON,"bottom depth inset");
            if(face==2)near(z,8+FluidSurfaceMesher.EPSILON,"north inset");
            if(face==3)near(z,9-FluidSurfaceMesher.EPSILON,"south inset");
            if(face==4)near(x,8+FluidSurfaceMesher.EPSILON,"west inset");
            if(face==5)near(x,9-FluidSurfaceMesher.EPSILON,"east inset");
        }
        ByteBuffer limited=ByteBuffer.allocate(32+6*16);limited.putLong(0x1122334455667788L);limited.position(32);
        count=FluidSurfaceMesher.append(isolated,0,null,limited);
        require(count==6&&limited.position()==128&&FluidSurfaceMesher.wasTruncated(),"atomic complete quad at VBO cap");
        require(limited.getLong(0)==0x1122334455667788L,"append preserves previous terrain vertices");
        System.out.println("Fluid surface tests passed: chunk seams, sand shores, shared weighted corners, greedy ocean, slabs, epsilon and bounded output.");
    }
    private static SurfaceChunk chunk(int x,int z) {
        SurfaceChunk chunk=new SurfaceChunk(ByteBuffer.allocate(0),ByteBuffer.allocate(16*16*16*2),
                ByteBuffer.allocate(SurfaceChunk.BIOME_BYTES),ByteBuffer.allocate(0),ByteBuffer.allocate(0));
        chunk.reset(x,z);chunk.finishFullBlockUpdate(16);return chunk;
    }
    private static int face(ByteBuffer mesh,int at){return ((mesh.get(at+14)&255)>>>4)&7;}
    private static int faceCount(ByteBuffer mesh,int target) {
        int count=0;for(int at=0;at<mesh.position();at+=16)if(face(mesh,at)==target)count++;return count;
    }
    private static void assertSharedCorner(ByteBuffer mesh,float x,float z,int minimum) {
        int samples=0;float value=0;
        for(int at=0;at<mesh.position();at+=16)if(face(mesh,at)==1&&mesh.getFloat(at)==x&&mesh.getFloat(at+8)==z) {
            if(samples++>0)near(mesh.getFloat(at+4),value,"shared flowing corner continuity");
            value=mesh.getFloat(at+4);
        }
        require(samples>=minimum,"shared corner appears in both neighbor faces");
    }
    private static void near(float actual,float expected,String what){require(Math.abs(actual-expected)<.00001f,what+": "+actual+" != "+expected);}
    private static void require(boolean value,String what){if(!value)throw new AssertionError(what);}
}
