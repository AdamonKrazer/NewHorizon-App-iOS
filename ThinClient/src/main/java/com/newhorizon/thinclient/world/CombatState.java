package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;

/** Client presentation and input timing. Damage, armor, enchants and critical hits remain server-authoritative. */
public final class CombatState {
    private float health=20,saturation=5,absorption,xpProgress,hurtDirection;
    private int food=20,air=300,xpLevel,xpTotal;
    private double attackSpeed=Double.NaN,maxHealth=20,armor;
    private long attackReset,missUntil,hurtUntil,deathAt,shieldBreakUntil;
    private String weapon="",deathMessage="";
    private boolean dead,usingItem,offhandUse,burning;
    private double motionX,motionY,motionZ;
    private int pendingMotion;
    private long completedUses;
    private final EntityMetadata.Values metadata=new EntityMetadata.Values();

    public synchronized void readHealth(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in,4); float hp=in.getFloat(); int hunger=VarInts.read(in);
        BinaryCodec.require(in,4); float sat=in.getFloat();
        if(!Float.isFinite(hp)||hp<0||hunger<0||hunger>20||!Float.isFinite(sat)||sat<0||in.hasRemaining())
            throw new ProtocolException("Invalid player health");
        if(hp<health) hurtUntil=System.nanoTime()+500_000_000L;
        health=hp;food=hunger;saturation=sat;
        if(hp<=0) markDead("");
    }
    public synchronized void readExperience(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in,4);float progress=in.getFloat();int level=VarInts.read(in),total=VarInts.read(in);
        if(!Float.isFinite(progress)||progress<0||progress>1||level<0||total<0||in.hasRemaining())
            throw new ProtocolException("Invalid experience");
        xpProgress=progress;xpLevel=level;xpTotal=total;
    }
    public synchronized void readMotion(ByteBuffer in,int localPlayer) throws ProtocolException {
        int id=VarInts.read(in); BinaryCodec.require(in,6);
        double x=in.getShort()/8000.0,y=in.getShort()/8000.0,z=in.getShort()/8000.0;
        if(in.hasRemaining()) throw new ProtocolException("Trailing motion bytes");
        if(id==localPlayer) { motionX=x;motionY=y;motionZ=z;pendingMotion=1; }
    }
    public synchronized void readExplosionMotion(ByteBuffer in) throws ProtocolException {
        BinaryCodec.require(in,28);in.position(in.position()+28);
        int blocks=VarInts.read(in);
        if(blocks<0 || blocks>(in.remaining()-12)/3) throw new ProtocolException("Explosion block count");
        in.position(in.position()+blocks*3); BinaryCodec.require(in,12);
        float x=in.getFloat(),y=in.getFloat(),z=in.getFloat();
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)||in.hasRemaining()) throw new ProtocolException("Invalid explosion impulse");
        if(pendingMotion==0) { motionX=motionY=motionZ=0;pendingMotion=2; }
        motionX+=x;motionY+=y;motionZ+=z;
    }
    public synchronized void addImpulse(double x,double y,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return;
        if(pendingMotion==0){motionX=motionY=motionZ=0;pendingMotion=2;}
        motionX+=x;motionY+=y;motionZ+=z;
    }
    /** 1 replaces vanilla deltaMovement; 2 adds explosion impulse. Units are blocks/tick. */
    public synchronized int drainMotion(double[] out) {
        int mode=pendingMotion;if(mode==0)return 0;
        out[0]=motionX;out[1]=motionY;out[2]=motionZ;pendingMotion=0;return mode;
    }
    public synchronized void readMetadata(ByteBuffer in,int localPlayer) throws ProtocolException {
        int id=VarInts.read(in);if(id!=localPlayer)return;
        EntityMetadata.read(in,metadata);
        if((metadata.present&1)!=0) burning=(metadata.flags&1)!=0;
        if((metadata.present&2)!=0) air=metadata.air;
        if((metadata.present&4)!=0) { usingItem=(metadata.livingFlags&1)!=0;offhandUse=(metadata.livingFlags&2)!=0; }
        if((metadata.present&16)!=0) absorption=metadata.absorption;
    }
    public synchronized void attribute(String key,double value) {
        if(!Double.isFinite(value))return;
        if(key.startsWith("minecraft:"))key=key.substring(10);
        switch(key) {
            case "generic.attack_speed":attackSpeed=Math.max(0,Math.min(1024,value));break;
            case "generic.max_health":maxHealth=Math.max(1,Math.min(1024,value));break;
            case "generic.armor":armor=Math.max(0,Math.min(30,value));break;
            default:break;
        }
    }
    public synchronized void selectWeapon(String material,long now) {
        String next=ProjectileKind.materialKey(material);
        if(!next.equals(weapon)) { weapon=next;attackReset=now;attackSpeed=Double.NaN; }
    }
    public synchronized double attackSpeed() { return Double.isNaN(attackSpeed)?weaponSpeed(weapon):attackSpeed; }
    public synchronized float attackStrength(long now,float partialTick) {
        long ticks=attackReset==0?1000:Math.max(0,(now-attackReset)/50_000_000L);
        return (float)Math.max(0,Math.min(1,(ticks+partialTick)*attackSpeed()/20.0));
    }
    public synchronized boolean mayAttack(long now) { return !dead && !usingItem && now>=missUntil; }
    public synchronized void attacked(long now,boolean miss,boolean creative) {
        attackReset=now; if(miss&&!creative)missUntil=now+500_000_000L;
    }
    public synchronized void releaseAttack() { missUntil=0; }
    public synchronized void use(boolean active,boolean offhand) { usingItem=active;offhandUse=offhand; }
    public synchronized boolean usingItem() { return usingItem; }
    public synchronized int food() { return food; }
    public synchronized long completedUses() { return completedUses; }
    public synchronized void completeUse() { completedUses++;usingItem=false; }
    public synchronized boolean canSprint() { return !dead && food>6 && !usingItem; }
    public synchronized boolean dead() { return dead; }
    public synchronized boolean burning() { return burning; }
    public synchronized void hurt(float direction,long now) { hurtDirection=direction;hurtUntil=now+500_000_000L; }
    public synchronized void shieldStatus(int status,long now) {
        if(status==30) {shieldBreakUntil=now+5_000_000_000L;usingItem=false;}
    }
    public synchronized boolean shieldDisabled(long now) { return now<shieldBreakUntil; }
    public synchronized void markDead(String message) {
        if(!dead)deathAt=System.nanoTime(); dead=true;health=0;usingItem=false;
        if(message!=null&&!message.isEmpty())deathMessage=message;
    }
    public synchronized boolean canRespawn(long now) { return dead && now-deathAt>=1_000_000_000L; }
    public synchronized float hurtRoll(long now) {
        if(now>=hurtUntil)return 0;
        float t=(hurtUntil-now)/500_000_000f;
        return (float)(-14*Math.sin(t*t*t*t*Math.PI)*Math.cos(Math.toRadians(hurtDirection)));
    }
    public String encodeForUi(long now,PlayerEffects effects,boolean creative,boolean target) {
        int fire=effects.level(PlayerEffects.FIRE_RESISTANCE),poison=effects.level(19),wither=effects.level(20),
                hunger=effects.level(17),regeneration=effects.level(10);
        synchronized(this) {
        return health+","+maxHealth+","+food+","+saturation+","+absorption+","+armor+","+air+","+xpLevel+","+xpProgress+","+
                attackStrength(now,0)+","+(dead?1:0)+","+(canRespawn(now)?1:0)+","+(creative?1:0)+","+
                (fire>0?0:burning?1:0)+","+
                (poison>0?1:wither>0?2:0)+","+(now<hurtUntil?1:0)+","+(target?1:0)+","+
                (hunger>0?1:0)+","+(regeneration>0?1:0);
        }
    }
    public synchronized void clear() {
        health=20;food=20;saturation=5;absorption=0;air=300;xpLevel=xpTotal=0;xpProgress=0;
        maxHealth=20;armor=0;attackSpeed=Double.NaN;weapon="";
        attackReset=missUntil=hurtUntil=deathAt=shieldBreakUntil=0;dead=usingItem=offhandUse=burning=false;
        pendingMotion=0;deathMessage="";
    }
    public synchronized void respawn(int keep) {
        double previousSpeed=attackSpeed,previousMax=maxHealth,previousArmor=armor;
        String previousWeapon=weapon;
        float previousHealth=health,previousAbsorption=absorption;
        int previousFood=food;float previousSaturation=saturation;
        clear();
        if((keep&1)!=0) {attackSpeed=previousSpeed;maxHealth=previousMax;armor=previousArmor;weapon=previousWeapon;}
        if((keep&2)!=0) {health=previousHealth;absorption=previousAbsorption;food=previousFood;saturation=previousSaturation;}
    }
    public static double weaponSpeed(String material) {
        String name=ProjectileKind.materialKey(material);
        if(name.endsWith("_SWORD"))return 1.6;
        if(name.endsWith("_SHOVEL"))return 1;
        if(name.endsWith("_PICKAXE"))return 1.2;
        if(name.endsWith("_AXE"))return name.startsWith("WOODEN_")||name.startsWith("STONE_")?.8:name.startsWith("IRON_")?.9:1;
        if(name.endsWith("_HOE"))return name.startsWith("WOODEN_")||name.startsWith("GOLDEN_")?1:name.startsWith("STONE_")?2:name.startsWith("IRON_")?3:4;
        return name.equals("TRIDENT")?1.1:4;
    }
}
