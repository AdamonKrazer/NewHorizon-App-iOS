package com.newhorizon.thinclient.render;
/** Allocation-free timings; nested UI timings supplement the full-frame phases. */
final class FrameProfile {
 private static final String[] NAMES={"poll","input","audio","meshes","prepare","sky","solid","entities","liquidWeather","displays","maps","labels","hand","fence","swap","uiEvents","hudEncode","displayUpdate","ambientSounds","ambientParticles","localAudio"};
 private final long[] current=new long[NAMES.length],total=new long[NAMES.length],max=new long[NAMES.length];
 private long previous;private int frames;
 private volatile long lastCompletedFrame;
 private volatile String lastStage="startup";
 private volatile boolean watching;
 private Thread watcher;
 void watch(final Thread renderThread){
  lastCompletedFrame=System.nanoTime();watching=true;
  watcher=new Thread(()->{
   long lastReport=0;
   while(watching&&renderThread.isAlive()){
    try{Thread.sleep(1000);}catch(InterruptedException ignored){return;}
    long now=System.nanoTime(),idle=now-lastCompletedFrame;
    if(watching&&idle>=5_000_000_000L&&(lastReport==0||now-lastReport>=30_000_000_000L)){
     lastReport=now;
     System.out.println("[NH-FRAME-STALL] noCompletedFrameMs="+idle/1_000_000+" after="+lastStage+" renderState="+renderThread.getState());
     for(StackTraceElement frame:renderThread.getStackTrace())System.out.println("[NH-FRAME-STALL] at "+frame);
    }
   }
  },"NH-FrameWatch");watcher.setDaemon(true);watcher.start();
 }
 void stopWatching(){watching=false;if(watcher!=null)watcher.interrupt();}
 void stage(String value){lastStage=value;}
 void begin(long now){java.util.Arrays.fill(current,0);previous=now;}
 void mark(int phase){long now=System.nanoTime();current[phase]+=now-previous;previous=now;lastStage=NAMES[phase];}
 void add(int phase,long nanos){current[phase]+=nanos;}
 void end(){
  lastCompletedFrame=System.nanoTime();lastStage="completed";
  for(int i=0;i<current.length;i++){total[i]+=current[i];max[i]=Math.max(max[i],current[i]);}
  if(++frames%100!=0)return;
  StringBuilder out=new StringBuilder("[NH-FRAME] frames=").append(frames);
  for(int i=0;i<current.length;i++){out.append(' ').append(NAMES[i]).append("Ms=").append(Math.round(total[i]/1000.0)/100000.0).append('/').append(Math.round(max[i]/1000.0)/1000.0);total[i]=max[i]=0;}
  System.out.println(out);
 }
}
