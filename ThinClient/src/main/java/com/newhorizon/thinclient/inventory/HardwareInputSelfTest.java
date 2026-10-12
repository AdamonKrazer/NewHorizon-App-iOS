package com.newhorizon.thinclient.inventory;

public final class HardwareInputSelfTest {
    public static void run() {
        ClientUiOptions options=new ClientUiOptions();
        check(options.apply("150,1,0,35")&&options.sensitivity==1.5f&&options.invertY&&!options.doubleSprint&&options.volume==.35f,"live UI settings");
        for(String bad:new String[]{"","NaN,0,0,0","200,2,1,50","24,0,1,50","100,0,1,101","100,0,1"}) {
            check(!options.apply(bad)&&options.sensitivity==1.5f&&options.volume==.35f,"invalid settings stay atomic");
        }
        HotbarScroll wheel=new HotbarScroll();
        check(wheel.move(0,1)==8,"wrap previous");
        check(wheel.move(8,-1)==0,"wrap next");
        check(wheel.move(2,.25)==2,"fraction 1");
        check(wheel.move(2,.25)==2,"fraction 2");
        check(wheel.move(2,.5)==1,"fraction whole");
        wheel.move(2,.75);
        check(wheel.move(2,-.5)==2,"direction reversal");
        check(wheel.move(2,-.5)==3,"opposite whole");
        wheel.move(2,.75);wheel.reset();
        check(wheel.move(2,.25)==2,"menu reset");
        check(wheel.move(2,Double.NaN)==2,"invalid event");
        wheel.reset();
        InventoryState inventory=new InventoryState();
        long revision=inventory.revision();
        for(int i=0;i<12;i++)inventory.selectHotbar(wheel.move(inventory.selectedHotbar(),-1));
        check(inventory.selectedHotbar()==3,"rapid scroll before server reply");
        check(inventory.revision()==revision+12,"selection refresh");
        inventory.selectHotbar(7);check(inventory.selectedHotbar()==7,"numeric selection");
        try {inventory.selectHotbar(9);throw new AssertionError("invalid slot accepted");}
        catch(IllegalArgumentException expected) { }
        System.out.println("Hardware input tests passed: wheel wrap/fractions/reversal, menu reset and rapid hotbar selection");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
