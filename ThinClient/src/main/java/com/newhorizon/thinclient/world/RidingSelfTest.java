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
        state.clear();check(!state.sleeping()&&state.vehicleOf(42)==-1,"world reset clears sleep and mounts");
        System.out.println("Riding tests passed: graph, seats, authority, wake, input/vehicle packets, corrections, boat physics and horse jump charge");
    }
    private static ByteBuffer passengers(int vehicle,int...riders){ByteBuffer b=ByteBuffer.allocate(2048);VarInts.write(b,vehicle);VarInts.write(b,riders.length);for(int id:riders)VarInts.write(b,id);b.flip();return b;}
    private static ByteBuffer spawn(int id,int type,double x,double y,double z){ByteBuffer b=ByteBuffer.allocate(96);VarInts.write(b,id);BinaryCodec.writeUuid(b,new UUID(0,id));VarInts.write(b,type);b.putDouble(x).putDouble(y).putDouble(z).put((byte)0).put((byte)0).put((byte)0).put((byte)0).putShort((short)0).putShort((short)0).putShort((short)0).flip();return b;}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action,String message)throws Exception{try{action.run();throw new AssertionError(message);}catch(ProtocolException expected){}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-6,message+": "+actual+" != "+expected);}
}
