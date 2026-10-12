package com.newhorizon.thinclient.world;
import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Checks authority transitions and packets without GL, Android or wall-clock waits. */
public final class RidingSelfTest {
    public static void run()throws Exception{
        RidingState state=new RidingState();state.readPassengers(passengers(10,42,43));
        check(state.vehicleOf(42)==10&&state.seatOf(42)==0&&state.seatOf(43)==1,"ordered boat seats");
        check(state.passengerCount(10)==2&&state.sameVehicle(42,43),"passenger siblings share root");
        state.readPassengers(passengers(11,10));check(state.rootOf(42)==11,"nested mount root");
        reject(()->state.readPassengers(passengers(42,11)),"mount cycles");
        reject(()->state.readPassengers(passengers(10,42,42)),"duplicate seats");
        check(state.vehicleOf(42)==10&&state.vehicleOf(11)==-1,"invalid graph leaves old attachment intact");
        state.readPassengers(passengers(12,42));check(state.vehicleOf(42)==12&&state.passengerCount(10)==1,"transfer removes old seat");
        state.readPassengers(passengers(12));check(state.vehicleOf(42)==-1,"empty passenger list dismounts");
        state.readPassengers(passengers(10,42));ByteBuffer remove=ByteBuffer.wrap(new byte[]{1,10});state.removeEntities(remove);
        check(state.vehicleOf(42)==-1&&state.vehicleOf(10)==-1,"destroying vehicle clears parent and children");
        check(state.vehicleOf(-1)==-1,"uninitialized local player never aliases empty graph slot");
        state.clear();reject(()->state.readPassengers(ByteBuffer.wrap(new byte[]{10,2,42})),"truncated passenger list");
        check(state.vehicleOf(42)==-1,"truncated packet cannot partially mount player");

        ByteBuffer wire=ByteBuffer.allocate(256);MinecraftPackets.writePlayerInput(wire,-1,.5f,true,true);wire.flip();
        check(VarInts.read(wire)==0x1f&&wire.getFloat()==-1&&wire.getFloat()==.5f&&wire.get()==3&&!wire.hasRemaining(),"rider input layout/flags");
        wire.clear();MinecraftPackets.writePaddles(wire,true,false);wire.flip();check(VarInts.read(wire)==0x19&&wire.get()==1&&wire.get()==0&&!wire.hasRemaining(),"boat paddle packet");
        wire.clear();MinecraftPackets.writeVehicleMovement(wire,1,64,-3,90,0);wire.flip();check(VarInts.read(wire)==0x18&&wire.getDouble()==1&&wire.getDouble()==64&&wire.getDouble()==-3&&wire.getFloat()==90&&wire.getFloat()==0&&!wire.hasRemaining(),"vehicle movement layout");
        wire.clear();MinecraftPackets.writePlayerCommand(wire,42,2);wire.flip();check(VarInts.read(wire)==0x1e&&VarInts.read(wire)==42&&VarInts.read(wire)==2&&VarInts.read(wire)==0,"wake command");
        wire.clear();MinecraftPackets.writePlayerCommand(wire,42,5,90);wire.flip();VarInts.read(wire);VarInts.read(wire);check(VarInts.read(wire)==5&&VarInts.read(wire)==90,"mount jump strength");
        long bed=((long)-4&0x3ffffff)<<38|((long)9&0x3ffffff)<<12|((long)-12&0xfff);
        wire.clear();VarInts.write(wire,42);wire.put((byte)6).put((byte)20).put((byte)2).put((byte)14).put((byte)11).put((byte)1).putLong(bed).put((byte)255).flip();
        state.readMetadata(wire,42);check(state.sleeping()&&state.bedPresent()&&state.bed()==bed,"server pose and sleeping block");
        check(BedGeometry.x(bed)==-4&&BedGeometry.y(bed)==-12&&BedGeometry.z(bed)==9,"signed bed position unpacking");
        wire.clear();VarInts.write(wire,99);wire.put((byte)6).put((byte)20).put((byte)0).put((byte)255).flip();state.readMetadata(wire,42);check(state.sleeping(),"other player's wake ignored");
        wire.clear();VarInts.write(wire,42);wire.put((byte)6).put((byte)20).put((byte)0).put((byte)255).flip();state.readMetadata(wire,42);check(!state.sleeping(),"server wake ends sleeping pose");
        state.correctVehicle(10,5,64,6,45,0);double[] correction=new double[5];
        check(!state.drainCorrection(11,correction)&&state.drainCorrection(10,correction)&&!state.drainCorrection(10,correction),"vehicle correction consumed exactly once for matching root");
        near(correction[0],5,"correction position");

        EntityTracker entities=new EntityTracker(4);entities.setRiding(state);state.clear();
        entities.addEntity(spawn(10,9,0,64,0));state.readPassengers(passengers(10,42,43));double[] pos=new double[3];
        check(entities.position(42,System.nanoTime(),pos),"local player seat resolves without own spawn packet");
        near(pos[1],63.55,"boat riding height plus player offset");near(pos[2],.2,"front seat offset");
        entities.position(43,System.nanoTime(),pos);near(pos[2],-.6,"rear seat offset");
        entities.localVehicle(10,3,65,4,90,0);entities.position(42,System.nanoTime(),pos);near(pos[0],2.8,"seat follows controlled boat yaw");near(pos[2],4,"seat follows predicted boat position");
        EntityTracker.Hit hit=new EntityTracker.Hit();entities.findRayHit(0,64.1,-3,0,0,1,6,42,hit);check(hit.entityId==-1,"own vehicle does not intercept interaction ray");
        entities.correctVehicle(10,6,66,7,0,0);entities.position(42,System.nanoTime(),pos);near(pos[0],6,"server correction replaces local prediction");
        check(RidingState.horse(49)&&RidingState.horse(66)&&!RidingState.horse(47)&&!RidingState.horse(65),"official horse/mule types exclude hoglin and mooshroom");
        check(ProjectileKind.airUseItem("minecraft:oak_boat")&&ProjectileKind.airUseItem("minecraft:bamboo_raft")&&!ProjectileKind.airUseItem("mod:oak_boat"),"boat placement uses vanilla air-use packet");

        BoatMotion boat=new BoatMotion();boat.reset(0,64,0,0,0);
        boat.integrate(BoatMotion.AIR,0,.6f,false,false,true,false);near(boat.vz,.04,"forward paddle acceleration");near(boat.vy,-.04,"boat gravity");
        boat.integrate(BoatMotion.AIR,0,.6f,false,false,true,false);near(boat.vz,.076,"boat drag before thrust");
        boat.reset(0,64,0,0,0);boat.integrate(BoatMotion.AIR,0,.6f,true,false,false,false);
        near(boat.yaw,-1,"boat left turn");check(!boat.leftPaddle&&boat.rightPaddle,"opposite paddle turns boat");
        boat.reset(0,64,0,0,0);boat.integrate(BoatMotion.AIR,0,.6f,false,false,false,true);near(boat.vz,-.005,"backwards thrust");
        boat.reset(0,64,0,0,0);boat.integrate(BoatMotion.WATER,.4,.6f,false,false,false,false);near(boat.y,63.9385,"entering water aligns boat surface");near(boat.vy,0,"water entry cancels falling velocity");
        near(MountMotion.jumpPower(0),0,"uncharged jump");near(MountMotion.jumpPower(5),.5,"half charge");near(MountMotion.jumpPower(10),1,"peak charge");near(MountMotion.jumpPower(11),.9,"overheld charge decays");
        BoatCamera camera=new BoatCamera();float view=camera.update(0,0);
        float[] expected={.25f,.5f,.75f,1f};
        for(int n=0;n<4;n++){view=camera.update(view,BoatCamera.interpolate(0,1,(n+1)/4.0));near(view,expected[n],"camera turns on each rendered frame");}
        // The next physics tick shares the previous endpoint: no step at the tick boundary.
        view=camera.update(view,BoatCamera.interpolate(1,3,0));near(view,1,"boat tick boundary continuity");
        view=camera.update(view+20,BoatCamera.interpolate(1,3,.5));near(view,22,"mouse look preserved while turning");
        camera.reset();view=camera.update(179,179);view=camera.update(view,-179);near(view,-179,"wrapped boat yaw has no full rotation");
        near(camera.update(400,-179),-284,"vanilla passenger look clamp");
        camera.reset();near(camera.update(15,10),15,"mount reset does not carry old boat rotation");
        testDismountAndFire();
        testBoatHull();
        state.clear();check(!state.sleeping()&&state.vehicleOf(42)==-1,"world reset clears sleep and mounts");
        System.out.println("Riding tests passed: graph, seats, authority, wake, input/vehicle packets, corrections, boat physics and horse jump charge");
    }
    private static void testBoatHull()throws Exception {
        RidingState graph=new RidingState();EntityTracker entities=new EntityTracker(8);entities.setRiding(graph);
        entities.addEntity(spawn(10,9,4,4,4));graph.readPassengers(passengers(10,42,43));
        entities.localVehicle(10,12,6,12,90,0);long now=System.nanoTime();
        near(entities.clipBoatMovement(42,now,1,11.7,6.5625,11.7,12.3,8.3625,12.3,-.08),-.08,"rider ignores own hull");
        check(!entities.collidesBoatBox(42,now,11.7,6,11.7,12.3,7.8,12.3),"attached rider may overlap hull");
        graph.readPassengers(passengers(10,43));
        entities.finishLocalVehicle(10,12,6,12,90,0);double[] pose=new double[3];
        entities.position(10,now,pose);near(pose[0],12,"boat retains simulated X on exit");near(pose[1],6,"boat retains simulated Y on exit");
        near(entities.clipBoatMovement(43,now,1,11.7,6.5625,11.7,12.3,8.3625,12.3,-.08),-.08,"remaining passenger still ignores hull");
        // Reproduce the old failure: gravity immediately put the detached player into the hull.
        double x=12,y=6.5625,z=12,velocity=0;
        for(int tick=0;tick<20;tick++) {
            velocity=(velocity-.08)*.98;
            double dy=entities.clipBoatMovement(42,now,1,x-.3,y,z-.3,x+.3,y+1.8,z+.3,velocity);
            y+=dy;if(dy!=velocity)velocity=0;
            near(y,6.5625,"detached player stands on boat under repeated gravity");
            check(!entities.collidesBoatBox(42,now,x-.3,y+1e-7,z-.3,x+.3,y+1.8,z+.3),"player does not penetrate hull");
        }
        for(int tick=0;tick<20;tick++) {
            double dx=entities.clipBoatMovement(42,now,0,x-.3,y,z-.3,x+.3,y+1.8,z+.3,.12);
            near(dx,.12,"walking off deck is never blocked horizontally");x+=dx;
            velocity=(velocity-.08)*.98;
            double dy=entities.clipBoatMovement(42,now,1,x-.3,y,z-.3,x+.3,y+1.8,z+.3,velocity);
            y+=dy;if(dy!=velocity)velocity=0;
        }
        check(x>14&&y<6,"player leaves deck and falls into water without flight");
        near(entities.clipBoatMovement(42,now,0,9.7,6,11.7,10.3,7.8,12.3,5),1.0125,"boat side clips X movement");
        near(entities.clipBoatMovement(42,now,2,11.7,6,13.7,12.3,7.8,14.3,-5),-1.0125,"boat side clips negative Z movement");
        near(entities.clipBoatMovement(42,now,1,11.7,4,11.7,12.3,5.8,12.3,.5),.2,"boat underside clips upward motion");
        check(entities.collidesBoatBox(42,now,11.7,6,11.7,12.3,7.8,12.3),"hull overlap detected for recovery");
        entities.addEntity(spawn(11,13,20,6,20));
        near(entities.clipBoatMovement(42,now,1,19.7,6.5625,19.7,20.3,8.3625,20.3,-.08),0,"chest boat deck is solid");
        entities.removeEntities(ByteBuffer.wrap(new byte[]{1,10}));
        near(entities.clipBoatMovement(42,now,1,11.7,6.5625,11.7,12.3,8.3625,12.3,-.08),-.08,"removed boat no longer collides");
        System.out.println("Boat hull tests passed: detach after movement, retained passenger, 20 gravity ticks, walk off, sides, underside, chest boat and removal");
    }
    private static void testDismountAndFire()throws Exception {
        try(WorldChunkStore world=new WorldChunkStore(com.newhorizon.thinclient.memory.MemoryBudget.lowRamDefaults(),9,16)) {
            world.handle(new WorldMessage.Reset("minecraft:overworld",0,64));
            ByteBuffer air=ByteBuffer.allocate(48);
            for(int i=0;i<4;i++){air.putShort((short)0).put((byte)0);VarInts.write(air,0);VarInts.write(air,0);air.put((byte)0);VarInts.write(air,0);VarInts.write(air,0);}air.flip();
            ByteBuffer light=ByteBuffer.allocate(32);VarInts.write(light,0);VarInts.write(light,0);VarInts.write(light,0);VarInts.write(light,1);light.putLong(63);VarInts.write(light,1);light.putLong(63);VarInts.write(light,0);VarInts.write(light,0);light.flip();
            world.applyVanillaChunkWithLight(0,0,air,light);
            double[] out=new double[3];
            for(int x=2;x<=7;x++)for(int z=2;z<=7;z++)world.applyVanillaBlockUpdate(x,3,z,1);
            BoatDismount.place(world,4,4,4,0,out);
            near(out[1],4,"shore dismount uses solid floor");check(out[2]>5,"shore dismount clears boat hull");
            check(!world.collidesBox(out[0]-.3,out[1]+1e-7,out[2]-.3,out[0]+.3,out[1]+1.8,out[2]+.3),"dismount body fits");
            // A slab is a half-block floor; a low ceiling requires the crouching pose.
            int slab=findBlock("minecraft:stone_slab","type: bottom");
            world.applyVanillaBlockUpdate(4,4,5,slab);
            BoatDismount.place(world,4,4,4,0,out);near(out[1],4.5,"slab dismount height");
            for(int x=3;x<=4;x++)for(int z=4;z<=5;z++)world.applyVanillaBlockUpdate(x,6,z,1);
            near(BoatDismount.place(world,4,4,4,0,out),1.5,"low ceiling selects crouching dismount");
            world.applyVanillaBlockUpdate(4,3,5,80);
            BoatDismount.place(world,4,4,4,0,out);
            near(out[0],4,"water exit uses boat center");near(out[1],4.5625,"water exit starts above hull instead of seated below it");near(out[2],4,"water exit center z");
        }
        int fireCount=0;
        for(int state=0;state<24135;state++) {
            String name=DebugCatalog.get().block(state);
            boolean fire=name.equals("minecraft:fire")||name.equals("minecraft:soul_fire");
            check(BlockAttack.isFire(state)==fire,"fire attack classification "+state);
            if(fire){fireCount++;check(!BlockStatePhysics.blocksMovement(state),"fire never has carpet collision");}
        }
        check(fireCount==513,"all fire age/direction variants and soul fire included");
        check(!BlockAttack.isFire(-1)&&!BlockAttack.isFire(30000),"unknown states not extinguishable");
        System.out.println("Boat regressions passed: frame rotation, tick continuity, wrapped yaw, shore/slab/crouch/water exits; all fire states");
    }
    private static int findBlock(String name,String property) {
        for(int i=0;i<24135;i++)if(DebugCatalog.get().block(i).equals(name)&&DebugCatalog.get().properties(i).contains(property))return i;
        throw new AssertionError(name+property);
    }
    private static ByteBuffer passengers(int vehicle,int...riders){ByteBuffer b=ByteBuffer.allocate(2048);VarInts.write(b,vehicle);VarInts.write(b,riders.length);for(int id:riders)VarInts.write(b,id);b.flip();return b;}
    private static ByteBuffer spawn(int id,int type,double x,double y,double z){ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,id);BinaryCodec.writeUuid(b,new UUID(0,id));VarInts.write(b,type);b.putDouble(x).putDouble(y).putDouble(z).put((byte)0).put((byte)0).put((byte)0).put((byte)0).putShort((short)0).putShort((short)0).putShort((short)0).flip();return b;}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action,String message)throws Exception{try{action.run();throw new AssertionError(message);}catch(ProtocolException expected){}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-6,message+": "+actual+" != "+expected);}
}
