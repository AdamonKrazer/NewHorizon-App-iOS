package com.newhorizon.thinclient.render;
import java.nio.ByteBuffer;
/** Conservative clip-plane test. Bounds are prepared once by the mesh worker. */
final class ViewFrustum {
 private final double[][] planes=new double[6][4];
 void update(double x,double y,double z,double yaw,double pitch,double aspect,double projection){
  double cy=Math.cos(Math.toRadians(-yaw)),sy=Math.sin(Math.toRadians(-yaw)),cp=Math.cos(Math.toRadians(pitch)),sp=Math.sin(Math.toRadians(pitch));
  double rx=-cy,ry=0,rz=sy,ux=sp*sy,uy=cp,uz=sp*cy,dx=cp*sy,dy=-sp,dz=cp*cy;
  plane(0,dx*aspect+rx*projection,dy*aspect+ry*projection,dz*aspect+rz*projection,0,x,y,z);
  plane(1,dx*aspect-rx*projection,dy*aspect-ry*projection,dz*aspect-rz*projection,0,x,y,z);
  plane(2,dx+ux*projection,dy+uy*projection,dz+uz*projection,0,x,y,z);
  plane(3,dx-ux*projection,dy-uy*projection,dz-uz*projection,0,x,y,z);
  plane(4,dx,dy,dz,-.05,x,y,z);plane(5,-dx,-dy,-dz,192,x,y,z);
 }
 private void plane(int i,double a,double b,double c,double d,double x,double y,double z){double[] p=planes[i];p[0]=a;p[1]=b;p[2]=c;p[3]=d-a*x-b*y-c*z;}
 boolean visible(float[] b){return visible(b,0);}
 boolean visible(float[] b,int at){for(double[] p:planes){double x=b[at+(p[0]>=0?3:0)],y=b[at+(p[1]>=0?4:1)],z=b[at+(p[2]>=0?5:2)];if(p[0]*x+p[1]*y+p[2]*z+p[3]<-.01)return false;}return true;}
 boolean contains(float[] b){for(double[] p:planes){double x=b[p[0]>=0?0:3],y=b[p[1]>=0?1:4],z=b[p[2]>=0?2:5];if(p[0]*x+p[1]*y+p[2]*z+p[3]<.01)return false;}return true;}
 static void bounds(ByteBuffer geometry,int vertices,float[] out){
  bounds(geometry,0,vertices,out,0);
 }
 static void bounds(ByteBuffer geometry,int first,int vertices,float[] out,int at){
  out[at]=out[at+1]=out[at+2]=Float.POSITIVE_INFINITY;out[at+3]=out[at+4]=out[at+5]=Float.NEGATIVE_INFINITY;
  for(int i=first;i<first+vertices;i++)for(int axis=0;axis<3;axis++){float v=geometry.getFloat(i*SurfaceMesher.VERTEX_BYTES+axis*4);out[at+axis]=Math.min(out[at+axis],v);out[at+axis+3]=Math.max(out[at+axis+3],v);}
 }
}
