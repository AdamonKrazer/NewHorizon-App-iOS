package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityMetadata;
import com.newhorizon.thinclient.world.EntityTracker;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Original-image, atlas bounds and protocol appearance regression checks; no GL needed. */
public final class EntityTextureSelfTest {
    public static void run() throws Exception {
        boolean[] occupied=new boolean[EntitySkins.WIDTH*EntitySkins.HEIGHT];
        for(int i=0;i<EntitySkins.NAMES.length;i++) {
            if(i>0)check(EntitySkins.NAMES[i-1].compareTo(EntitySkins.NAMES[i])<0,"sorted names");
            int a=i*4,x=EntitySkins.RECTS[a],y=EntitySkins.RECTS[a+1],w=EntitySkins.RECTS[a+2],h=EntitySkins.RECTS[a+3];
            check(x>0&&y>0&&x+w<EntitySkins.WIDTH&&y+h<EntitySkins.HEIGHT,"padded atlas bounds");
            try(InputStream in=EntityTextureSelfTest.class.getResourceAsStream("/assets/newhorizon/entities/"+EntitySkins.NAMES[i]+".png")) {
                check(in!=null,"original PNG exists");BufferedImage image=ImageIO.read(in);
                check(image!=null&&image.getWidth()==w&&image.getHeight()==h,"native resolution");
            }
            for(int py=y-1;py<=y+h;py++)for(int px=x-1;px<=x+w;px++) {
                int at=py*EntitySkins.WIDTH+px;check(!occupied[at],"atlas overlap");occupied[at]=true;
            }
        }
        EntityTracker.Renderable e=new EntityTracker.Renderable();
        int covered=0;
        for(int type=0;type<124;type++){e.type=type;String name=EntityAppearance.name(e);if(name!=null){check(EntitySkins.find(name)>=0,"entity skin");covered++;}}
        check(covered>=85,"living/vehicle texture coverage");
        e.type=9;for(int v=0;v<9;v++){e.appearance[11]=v;check(EntitySkins.find(EntityAppearance.name(e))>=0,"boat variants");}
        e.type=49;e.appearance[18]=4;check(EntityAppearance.name(e).equals("horse/horse_black"),"horse variant");
        e.type=11;e.appearance[19]=9;check(EntityAppearance.name(e).equals("cat/jellie"),"cat variant");
        int baked=0;
        for(int type=0;type<124;type++){e.type=type;VanillaEntityModels.Model model=VanillaEntityModels.model(e);if(model!=null){check(model.paths.length<=40,"bounded vanilla parts");baked++;}}
        check(baked>=75,"baked vanilla entity coverage");
        for(VanillaEntityModels.Model model:VanillaEntityModels.MODELS)for(int p=0;p<model.paths.length;p++) {
            int at=p*VanillaEntityModels.STRIDE;float[] b=model.boxes;
            for(int row=0;row<3;row++){float length=0;for(int col=0;col<3;col++){float v=b[at+6+row*3+col];length+=v*v;}check(Math.abs(length-1)<.001f,"vanilla part rotation");}
            check(b[at+3]>=0&&b[at+4]>=0&&b[at+5]>=0,"cuboid dimensions");
        }
        e.type=49;e.appearance[18]=0x301;check(EntityAppearance.overlay(e,0).equals("horse/horse_markings_whitedots"),"horse markings");
        e.type=108;e.appearance[28]=4;e.appearance[29]=5;check(EntityAppearance.overlay(e,0).equals("villager/type/snow"),"villager biome");check(EntityAppearance.overlay(e,1).equals("villager/profession/farmer"),"villager profession");
        EntityMetadata.Values values=new EntityMetadata.Values();
        EntityMetadata.read(ByteBuffer.wrap(new byte[]{19,21,9,(byte)255}),values);
        check(values.appearance[19]==9,"variant serializer");
        EntityMetadata.read(ByteBuffer.wrap(new byte[]{17,0,20,(byte)255}),values);
        check(values.appearance[17]==20&&values.appearance[19]==9,"partial metadata preserves appearance");
        EntityMetadata.read(ByteBuffer.wrap(new byte[]{8,7,1,5,1,0,(byte)255}),values);
        check(values.itemId==5&&values.itemTag.isEmpty(),"dropped item stack metadata");
        babyMetadata();vanillaCubeUvs();chickenLegAlpha();
        System.out.println("Entity texture tests passed: "+EntitySkins.NAMES.length+" original PNGs; "+covered+" entity type skins; atlas bounds, variants and metadata");
    }
    private static void vanillaCubeUvs() {
        // ModelPart.Cube/Polygon in the official JAR: original corners, polygon order,
        // and rectangle endpoints. This reference is deliberately independent of the
        // shader's face formulas; model-space X/Y are flipped only after selecting a corner.
        int[][] corners={{0,0,0},{1,0,0},{1,1,0},{0,1,0},{0,0,1},{1,0,1},{1,1,1},{0,1,1}};
        int[][] polys={{5,4,0,1},{2,3,7,6},{0,4,7,3},{1,0,3,2},{5,1,2,6},{4,5,6,7}};
        int[] faces={1,0,5,2,4,3};
        float u=7,v=11,w=4,h=6,d=3;
        float[][] rects={{u+d,v,u+d+w,v+d},{u+d+w,v+d,u+d+2*w,v},{u,v+d,u+d,v+d+h},
                {u+d,v+d,u+d+w,v+d+h},{u+d+w,v+d,u+2*d+w,v+d+h},{u+2*d+w,v+d,u+2*d+2*w,v+d+h}};
        float[] actual=new float[2];
        for(boolean mirror:new boolean[]{false,true})for(int f=0;f<6;f++)for(int n=0;n<4;n++) {
            int[] c=corners[polys[f][n]];float x=mirror?c[0]:1-c[0],y=1-c[1],z=c[2];int face=faces[f];
            if(mirror){if(face==4)face=5;else if(face==5)face=4;}
            EntityBoxGeometry.uv(face,x,y,z,u,v,w,h,d,mirror,actual);
            float expectedU=rects[f][n==0||n==3?2:0],expectedV=rects[f][n<2?1:3];
            check(Math.abs(actual[0]-expectedU)<.0001f&&Math.abs(actual[1]-expectedV)<.0001f,"original cube UV face="+face+" corner="+n+" mirror="+mirror);
        }
    }
    private static void chickenLegAlpha() throws Exception {
        // The original leg (UV 26,0; 3x5x3) contains a one-pixel-wide shaft
        // only on its back face. Its side/front faces are transparent: at a
        // perfectly side-on angle the shaft is edge-on, including in vanilla.
        try(InputStream in=EntityTextureSelfTest.class.getResourceAsStream("/assets/newhorizon/entities/chicken.png")) {
            BufferedImage skin=ImageIO.read(in);float[] uv=new float[2];
            for(int y=3;y<8;y++)for(int x=26;x<38;x++)
                check((skin.getRGB(x,y)>>>24)==(x==36?255:0),"original chicken shaft alpha footprint");
            for(int row=0;row<5;row++) {
                float height=1-(row+.5f)/5;
                for(int face:new int[]{2,3,4,5}) {
                    EntityBoxGeometry.uv(face,.5f,height,.5f,26,0,3,5,3,false,uv);
                    int alpha=skin.getRGB((int)uv[0],(int)uv[1])>>>24;
                    check(alpha==(face==3?255:0),"chicken shaft face sampling matches original alpha");
                }
            }
        }
    }
    private static void babyMetadata() throws Exception {
        EntityTracker tracker=new EntityTracker(1);
        EntityTracker.Renderable[] one={new EntityTracker.Renderable()};
        for(int type:new int[]{0,25,41,46,74,75}) {
            tracker.clear();tracker.addEntity(com.newhorizon.thinclient.world.FishingLeadsSelfTest.spawn(1,type,0,0,0,0));
            tracker.setMetadata(ByteBuffer.wrap(new byte[]{1,16,8,1,(byte)255}));
            check(tracker.snapshotVisible(one,System.nanoTime(),0,0,0,10,-1)==1&&!one[0].baby,"non-ageable flag cannot shrink type "+type);
        }
        tracker.clear();tracker.addEntity(com.newhorizon.thinclient.world.FishingLeadsSelfTest.spawn(1,73,0,0,0,0));
        tracker.setMetadata(ByteBuffer.wrap(new byte[]{1,16,8,1,(byte)255}));
        tracker.snapshotVisible(one,System.nanoTime(),0,0,0,10,-1);check(!one[0].baby,"piglin immunity is not age");
        tracker.setMetadata(ByteBuffer.wrap(new byte[]{1,17,8,1,(byte)255}));
        tracker.snapshotVisible(one,System.nanoTime(),0,0,0,10,-1);check(one[0].baby,"piglin uses baby metadata 17");
        tracker.setMetadata(ByteBuffer.wrap(new byte[]{1,17,8,0,(byte)255}));
        tracker.snapshotVisible(one,System.nanoTime(),0,0,0,10,-1);check(!one[0].baby,"piglin grows without replacing entity");
        for(int type:new int[]{15,49,118}) {
            tracker.clear();tracker.addEntity(com.newhorizon.thinclient.world.FishingLeadsSelfTest.spawn(1,type,0,0,0,0));
            tracker.setMetadata(ByteBuffer.wrap(new byte[]{1,16,8,1,(byte)255}));
            tracker.snapshotVisible(one,System.nanoTime(),0,0,0,10,-1);check(one[0].baby,"normal juvenile metadata retained");
        }
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private EntityTextureSelfTest() { }
}
