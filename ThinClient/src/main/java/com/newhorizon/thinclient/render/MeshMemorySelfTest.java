package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.memory.*;

public final class MeshMemorySelfTest {
    public static void run(){
        MemoryBudget budget=MemoryBudget.lowRamDefaults();
        MemoryBudget.Lease fixed=budget.reserve(MemoryCategory.MESH,12*1024*1024);
        MemoryBudget.Lease[] slots=new MemoryBudget.Lease[82];
        for(int i=0;i<slots.length;i++)slots[i]=budget.reserve(MemoryCategory.MESH,0);
        long peak=0;
        // Follow a dense patch around every reusable GPU slot, then return to
        // small ocean chunks. Grow-only buffers fail after enough exploration.
        for(int pass=0;pass<12;pass++)for(int slot=0;slot<slots.length;slot++){
            for(int i=0;i<slots.length;i++){
                int size=MeshBufferSizing.capacity(i==slot?2*1024*1024:180000);
                MemoryBudget.Lease old=slots[i],next=budget.tryResize(old,size+size/8);
                check(next!=null,"exploration must not retain past peaks");slots[i]=next;
                old.close(); // Replacement transfers ownership; closing old is harmless.
            }
            peak=Math.max(peak,budget.used(MemoryCategory.MESH));
        }
        check(peak<34*1024*1024,"resident cost follows current scene");
        long before=budget.used(MemoryCategory.MESH);
        check(budget.tryResize(slots[0],budget.limit(MemoryCategory.MESH))==null,"over-budget resize refused");
        check(budget.used(MemoryCategory.MESH)==before,"failed admission retains original accounting");
        for(MemoryBudget.Lease slot:slots)slot.close();fixed.close();
        check(budget.used(MemoryCategory.MESH)==0,"all GPU leases reclaimed");
        System.out.println("Mesh memory tests passed: 984 dense-patch moves, shrink/reuse, failed admission and release; peak="+peak);
    }
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}
