package com.newhorizon.thinclient.render;
import org.lwjgl.opengl.GL33;

/** Keep at most two submitted frames, without blocking input polling on a GL wait. */
final class FramePacer implements AutoCloseable {
    private final long[] fences=new long[2];
    private int head,pending;
    long waitStarted,waitNanos,maxWaitNanos,waits;
    boolean ready(){
        while(pending>0){
            int status=GL33.glClientWaitSync(fences[head],0,0L);
            if(status==GL33.GL_TIMEOUT_EXPIRED)break;
            if(status==GL33.GL_WAIT_FAILED)throw new IllegalStateException("Frame fence failed");
            GL33.glDeleteSync(fences[head]);fences[head]=0;head=(head+1)%fences.length;pending--;
        }
        if(pending==fences.length){if(waitStarted==0){waitStarted=System.nanoTime();waits++;}return false;}
        if(waitStarted!=0){long duration=System.nanoTime()-waitStarted;waitNanos+=duration;maxWaitNanos=Math.max(maxWaitNanos,duration);waitStarted=0;}
        return true;
    }
    void submitted(){
        if(pending==fences.length)throw new IllegalStateException("Frame queue exceeded");
        int slot=(head+pending)%fences.length;
        fences[slot]=GL33.glFenceSync(GL33.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(fences[slot]==0)throw new IllegalStateException("Frame fence unavailable");
        pending++;GL33.glFlush();
    }
    public void close(){for(long f:fences)if(f!=0)GL33.glDeleteSync(f);java.util.Arrays.fill(fences,0);pending=head=0;waitStarted=0;}
}
