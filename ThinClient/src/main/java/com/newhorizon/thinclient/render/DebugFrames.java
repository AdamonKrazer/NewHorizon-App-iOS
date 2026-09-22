package com.newhorizon.thinclient.render;

/** Fixed-size frame history and CPU phase timings; never captures browser contents. */
public final class DebugFrames {
    private final float[] frames=new float[120];
    private final double[] phases=new double[4];
    private int count,cursor;
    public void add(long elapsed,long input,long mesh,long render,long swap){
        frames[cursor]=(float)Math.max(0,Math.min(10000,elapsed/1e6));cursor=(cursor+1)%frames.length;count=Math.min(count+1,frames.length);
        phase(0,input);phase(1,mesh);phase(2,render);phase(3,swap);
    }
    private void phase(int i,long nanos){phases[i]=phases[i]*.9+Math.max(0,nanos/1e6)*.1;}
    public String json(boolean chart,boolean pie){StringBuilder b=new StringBuilder("{\"chart\":").append(chart).append(",\"pie\":").append(pie).append(",\"frames\":[");
        if(chart)for(int i=0;i<count;i++){if(i>0)b.append(',');b.append(frames[Math.floorMod(cursor-count+i,frames.length)]);}b.append("],\"phases\":[");
        for(int i=0;i<4;i++){if(i>0)b.append(',');b.append((float)phases[i]);}return b.append("]}").toString();}
    public String summary(){if(count==0)return "Frame time: waiting";float sum=0,min=Float.MAX_VALUE,max=0;for(int i=0;i<count;i++){sum+=frames[i];min=Math.min(min,frames[i]);max=Math.max(max,frames[i]);}return String.format(java.util.Locale.ROOT,"Frame: %.1f min / %.1f avg / %.1f max ms",min,sum/count,max);}
}
