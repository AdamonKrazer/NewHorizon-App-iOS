package com.newhorizon.thinclient.world;

/** Replays local strafing with tiny depth noise at the old 90-degree flip boundary. */
public final class LocalPlayerVisualSelfTest {
    private static final long TICK=50_000_000L;
    public static void run() {
        strafe(1);strafe(-1);reverse();angles();stationary();attack();
        System.out.println("Local player body tests passed: noisy left/right strafe, reversals, 180 wrap, idle, attack and camera independence");
    }
    public static void main(String[] args){run();}
    private static LocalPlayerVisual player(float yaw) {
        LocalPlayerVisual p=new LocalPlayerVisual();p.update(TICK,0,0,0,yaw,0);return p;
    }
    private static float frame(LocalPlayerVisual p,long tick,double x,double z,float yaw) {
        p.update(tick,x,0,z,yaw,0);p.update(tick+TICK-1,x,0,z,yaw,0);return p.model.bodyYaw;
    }
    private static void strafe(int direction) {
        LocalPlayerVisual p=player(0);float previous=0;double x=0,z=0;
        for(int i=2;i<70;i++) {
            x+=direction*.13;z+=(i%2==0?1:-1)*.000001;
            float body=frame(p,i*TICK,x,z,0);
            check(Math.abs(ChickenBodyRotation.wrap(body-previous))<=30,"strafe cannot flip torso between frames");
            check(Math.abs(ChickenBodyRotation.wrap(body))<=50.01,"head and torso stay within vanilla limit");
            if(i>12)check(direction*body< -40,"stable side chosen despite tiny forward/backward noise");
            previous=body;
        }
        check(p.model.headYaw==0&&p.model.yaw==0,"presentation does not rotate gameplay aim");
        PerspectiveCamera camera=new PerspectiveCamera();camera.mode=2;
        try(WorldChunkStore world=new WorldChunkStore(com.newhorizon.thinclient.memory.MemoryBudget.lowRamDefaults(),1,16)) {
            camera.update(world,x,1.6,z,0,0);
            check(camera.yaw==180&&p.model.headYaw==0,"front camera adds 180 only to view");
        }
    }
    private static void reverse() {
        LocalPlayerVisual p=player(0);double x=0;float previous=0;
        for(int i=2;i<30;i++) {
            x+=i<15?.15:-.15;float body=frame(p,i*TICK,x,0,0);
            check(Math.abs(ChickenBodyRotation.wrap(body-previous))<45,"changing strafe side blends instead of snapping");previous=body;
        }
        check(previous>40,"left strafe settles on opposite side");
        p=player(0);for(int i=2;i<15;i++)check(Math.abs(frame(p,i*TICK,0,-i*.15,0))<.01,"backward walking keeps body facing head");
    }
    private static void angles() {
        LocalPlayerVisual p=player(179);float body=frame(p,2*TICK,0,0,-179);
        check(Math.abs(ChickenBodyRotation.wrap(body-179))<3,"crossing yaw wrap uses shortest arc");
        check(Math.abs(ChickenBodyRotation.wrap(p.model.headYaw-body))<3,"head does not twist at wrap");
    }
    private static void stationary() {
        LocalPlayerVisual p=player(0);float previous=0;
        for(int i=2;i<24;i++) {
            float body=frame(p,i*TICK,(i%2)*.00001,0,0);
            check(Math.abs(body-previous)<.01,"sub-threshold drift does not turn stationary torso");previous=body;
        }
    }
    private static void attack() {
        LocalPlayerVisual p=player(0);double x=0;float previous=0;
        for(int i=2;i<12;i++){x+=.15;previous=frame(p,i*TICK,x,0,0);}
        p.model.swingProgress=.3f;x+=.15;float body=frame(p,12*TICK,x,0,0);
        check(Math.abs(body)<Math.abs(previous),"attack turns torso toward aim gradually");
        check(p.model.swingProgress==.3f,"body controller preserves arm animation");
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private LocalPlayerVisualSelfTest(){}
}
