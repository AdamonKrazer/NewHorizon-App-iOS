package com.newhorizon.thinclient.world;

public final class SprintInputSelfTest {
    public static void run(){
        SprintInput s=new SprintInput();long t=1_000_000_000L;
        check(!s.forward(true,t,true),"first forward does not sprint");
        check(!s.forward(true,t+10_000_000L,true),"duplicate down is not a second press");
        s.forward(false,t+50_000_000L,true);
        check(s.forward(true,t+150_000_000L,true),"two genuine forward edges sprint");
        s.reset();s.forward(true,t,true);s.forward(false,t+50_000_000L,true);
        check(!s.forward(true,t+400_000_000L,true),"expired seven-tick window");
        s.reset();s.forward(true,t,true);s.cancel();s.forward(false,t+50_000_000L,true);
        check(!s.forward(true,t+100_000_000L,true),"collision clears pending double tap");
        s.reset();s.forward(true,t,false);s.forward(false,t+50_000_000L,false);
        check(!s.forward(true,t+100_000_000L,false),"double sprint disabled");
        check(!SprintInput.minorCollision(0,1,0,0),"wall stopping all movement is not minor");
        check(!SprintInput.minorCollision(1,1,0,.2),"45 degree deflection stops sprint");
        check(SprintInput.minorCollision(.05,1,0,.2),"grazing wall preserves sprint");
        check(!SprintInput.minorCollision(.2,1,0,.2),"over eight degrees stops sprint");
        check(SprintInput.blocked(true,false,false,false),"land collision cancels before next jump");
        check(!SprintInput.blocked(true,true,false,false),"minor collision exempt");
        check(!SprintInput.blocked(true,false,true,false),"swimming keeps its separate sprint rules");
        check(!SprintInput.blocked(true,false,false,true),"creative flight exempt");
        check(!SprintInput.blocked(false,false,false,false),"no collision allows sprint");
        System.out.println("Sprint tests passed: genuine edges, timeout, cancellation, settings, frontal/grazing collisions, swimming and flying");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private SprintInputSelfTest(){}
}
