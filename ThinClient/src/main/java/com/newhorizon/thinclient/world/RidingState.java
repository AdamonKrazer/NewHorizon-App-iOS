package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Fixed passenger graph. Enter/leave/sleep transitions are exclusively server-confirmed. */
public final class RidingState {
    private final int[] riders=new int[256],vehicles=new int[256],seats=new int[256],scratch=new int[256];
    private final EntityMetadata.Values metadata=new EntityMetadata.Values();
    private boolean sleeping,bedPresent;
    private long bed,revision;
    private int correctedVehicle=-1;
    private final double[] correction=new double[5];
    public RidingState(){clear();}
    public synchronized void readPassengers(ByteBuffer in) throws ProtocolException {
        int vehicle=VarInts.read(in),count=VarInts.read(in);
        if(vehicle<0||count<0||count>scratch.length)throw new ProtocolException("Passenger count outside budget");
        int available=0;
        for(int i=0;i<riders.length;i++)if(riders[i]<0||vehicles[i]==vehicle)available++;
        for(int i=0;i<count;i++) {
            int id=VarInts.read(in);scratch[i]=id;
            if(id<0||id==vehicle)throw new ProtocolException("Invalid passenger");
            for(int j=0;j<i;j++)if(scratch[j]==id)throw new ProtocolException("Duplicate passenger");
            if(vehicleOf(id)>=0&&vehicleOf(id)!=vehicle)available++;
            int ancestor=vehicle;
            for(int depth=0;depth<256&&ancestor>=0;depth++) {
                if(ancestor==id)throw new ProtocolException("Passenger cycle");ancestor=vehicleOf(ancestor);
            }
        }
        if(in.hasRemaining()||count>available)throw new ProtocolException("Passenger graph outside budget");
        for(int i=0;i<riders.length;i++)if(vehicles[i]==vehicle)riders[i]=-1;
        for(int seat=0;seat<count;seat++) {
            int slot=index(scratch[seat]);if(slot<0)for(int i=0;i<riders.length;i++)if(riders[i]<0){slot=i;break;}
            riders[slot]=scratch[seat];vehicles[slot]=vehicle;seats[slot]=seat;
        }
        revision++;
    }
    private int index(int id){if(id<0)return -1;for(int i=0;i<riders.length;i++)if(riders[i]==id)return i;return -1;}
    public synchronized int vehicleOf(int id){int i=index(id);return i<0?-1:vehicles[i];}
    public synchronized int seatOf(int id){int i=index(id);return i<0?-1:seats[i];}
    public synchronized int passengerCount(int vehicle){int n=0;for(int i=0;i<riders.length;i++)if(riders[i]>=0&&vehicles[i]==vehicle)n++;return n;}
    public synchronized int rootOf(int id){int parent=vehicleOf(id);for(int i=0;i<256&&parent>=0;i++){id=parent;parent=vehicleOf(id);}return id;}
    public synchronized boolean sameVehicle(int a,int b){return a>=0&&b>=0&&rootOf(a)==rootOf(b);}
    public synchronized long revision(){return revision;}
    public synchronized void removeEntities(ByteBuffer in) throws ProtocolException {
        int count=VarInts.read(in);if(count<0||count>1024)throw new ProtocolException("Invalid removal count");
        for(int j=0;j<count;j++){int id=VarInts.read(in);for(int i=0;i<riders.length;i++)if(riders[i]==id||vehicles[i]==id)riders[i]=-1;if(correctedVehicle==id)correctedVehicle=-1;}
        if(in.hasRemaining())throw new ProtocolException("Trailing removals");revision++;
    }
    public synchronized void readMetadata(ByteBuffer in,int localPlayer)throws ProtocolException {
        if(VarInts.read(in)!=localPlayer)return;EntityMetadata.read(in,metadata);
        if((metadata.present&32)!=0)sleeping=metadata.pose==2;
        if((metadata.present&64)!=0){bedPresent=metadata.bedPresent;bed=metadata.bed;}
    }
    public synchronized void wake(){sleeping=false;bedPresent=false;}
    public synchronized boolean sleeping(){return sleeping;}
    public synchronized boolean bedPresent(){return bedPresent;}
    public synchronized long bed(){return bed;}
    public synchronized void correctVehicle(int vehicle,double x,double y,double z,float yaw,float pitch){
        if(vehicle<0)return;correctedVehicle=vehicle;correction[0]=x;correction[1]=y;correction[2]=z;correction[3]=yaw;correction[4]=pitch;
    }
    public synchronized boolean drainCorrection(int vehicle,double[] out){
        if(correctedVehicle!=vehicle)return false;System.arraycopy(correction,0,out,0,5);correctedVehicle=-1;return true;
    }
    public synchronized void clear(){Arrays.fill(riders,-1);sleeping=bedPresent=false;correctedVehicle=-1;revision++;metadata.pose=0;}
    public static boolean boat(int type){return type==9||type==13;}
    public static boolean horse(int type){return type==21||type==49||type==66||type==87||type==119;}
    public static boolean mountable(int type){return boat(type)||horse(type)||minecart(type)||type==10||type==60||type==72||type==98||type==103;}
    public static boolean minecart(int type){return type==14||type==17||type==40||type==48||type==64||type==93||type==102;}
    public static double seatHeight(int type,int variant){
        if(boat(type))return (variant==8?.25:-.1)-.35;
        if(minecart(type))return -.35;
        if(horse(type))return CombatHitboxes.height(type)*.75-.35;
        return CombatHitboxes.height(type)*.75-.35;
    }
}
