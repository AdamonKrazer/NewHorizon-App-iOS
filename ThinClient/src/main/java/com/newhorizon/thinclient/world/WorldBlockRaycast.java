package com.newhorizon.thinclient.world;

/** Bounded voxel DDA for player selection, including outlines that cross cell edges. */
public final class WorldBlockRaycast {
    private WorldBlockRaycast() { }
    public interface Blocks {int blockStateAt(int x,int y,int z);}
    public static final class Hit {
        public int blockX,blockY,blockZ,face;
        public double distance;
        public boolean inside;
        private final BlockStateOutline.Hit shape=new BlockStateOutline.Hit();
    }
    public static boolean trace(Blocks world,double ox,double oy,double oz,double dx,double dy,double dz,double maximum,Hit out) {
        out.face=-1;out.inside=false;out.distance=maximum+1;
        if(maximum<0||maximum>64||!Double.isFinite(maximum)||!Double.isFinite(ox)||!Double.isFinite(oy)||!Double.isFinite(oz)
                ||!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(dz))return false;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);if(length<1e-12)return false;dx/=length;dy/=length;dz/=length;
        int x=(int)Math.floor(ox),y=(int)Math.floor(oy),z=(int)Math.floor(oz);
        int sx=dx>0?1:dx<0?-1:0,sy=dy>0?1:dy<0?-1:0,sz=dz>0?1:dz<0?-1:0;
        double ax=sx==0?Double.POSITIVE_INFINITY:Math.abs(1/dx),ay=sy==0?Double.POSITIVE_INFINITY:Math.abs(1/dy),az=sz==0?Double.POSITIVE_INFINITY:Math.abs(1/dz);
        double tx=sx==0?Double.POSITIVE_INFINITY:(sx>0?x+1-ox:ox-x)*ax,
                ty=sy==0?Double.POSITIVE_INFINITY:(sy>0?y+1-oy:oy-y)*ay,
                tz=sz==0?Double.POSITIVE_INFINITY:(sz>0?z+1-oz:oz-z)*az;
        double travelled=0;
        for(int step=0;step<512&&travelled<=maximum;step++) {
            visit(world,x,y,z,false,ox,oy,oz,dx,dy,dz,maximum,out);
            // Only non-unit outlines in neighbouring cells can intersect this cell.
            for(int ny=-1;ny<=1;ny++)for(int nz=-1;nz<=1;nz++)for(int nx=-1;nx<=1;nx++) {
                if(nx==0&&ny==0&&nz==0)continue;
                visit(world,x+nx,y+ny,z+nz,true,ox,oy,oz,dx,dy,dz,maximum,out);
            }
            double next=Math.min(tx,Math.min(ty,tz));
            if(out.face>=0&&out.distance<=next+1e-10)break;
            if(next>maximum)break;
            if(tx<=ty&&tx<=tz){x+=sx;travelled=tx;tx+=ax;}
            else if(ty<=tz){y+=sy;travelled=ty;ty+=ay;}
            else {z+=sz;travelled=tz;tz+=az;}
        }
        return out.face>=0;
    }
    private static void visit(Blocks world,int x,int y,int z,boolean neighbour,double ox,double oy,double oz,
                              double dx,double dy,double dz,double maximum,Hit out) {
        int state=world.blockStateAt(x,y,z);
        if(state<0||BlockStateOutline.boxCount(state)==0||neighbour&&!BlockStateOutline.extendsCell(state))return;
        if(!BlockStateOutline.hit(state,ox,oy,oz,dx,dy,dz,x,y,z,Math.min(maximum,out.distance),out.shape)
                ||out.shape.distance>=out.distance)return;
        out.blockX=x;out.blockY=y;out.blockZ=z;out.distance=out.shape.distance;out.face=out.shape.face;out.inside=out.shape.inside;
    }
}