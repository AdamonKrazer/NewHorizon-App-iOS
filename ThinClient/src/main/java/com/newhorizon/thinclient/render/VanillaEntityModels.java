package com.newhorizon.thinclient.render;

import com.newhorizon.thinclient.world.EntityTracker;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Static cuboids baked offline from the official 1.20.1 model-layer builders. */
final class VanillaEntityModels {
    static final int STRIDE=22;
    static final class Model {
        final String name;final int width,height;final String[] paths;final float[] boxes;final VanillaEntityRig rig;
        Model(String name,int width,int height,String[] paths,float[] boxes){this.name=name;this.width=width;this.height=height;this.paths=paths;this.boxes=boxes;this.rig=VanillaEntityRig.find(name);if(rig.boxJoints.length!=paths.length)throw new IllegalStateException("Rig/catalog mismatch");}
    }
    static final Model[] MODELS=load();
    private static Model[] load() {
        try(InputStream resource=VanillaEntityModels.class.getResourceAsStream("/assets/newhorizon/entities/models.bin.gz")) {
            if(resource==null)throw new IllegalStateException("Missing vanilla entity models");
            try(DataInputStream in=new DataInputStream(new GZIPInputStream(resource))) {
                if(in.readInt()!=0x4e484d31)throw new IllegalStateException("Entity model magic");
                int count=in.readInt();if(count<1||count>128)throw new IllegalStateException("Entity model count");
                Model[] out=new Model[count];
                for(int i=0;i<count;i++) {
                    String name=in.readUTF();int width=in.readInt(),height=in.readInt(),parts=in.readInt();
                    if(width<1||height<1||width>512||height>512||parts<1||parts>40)throw new IllegalStateException("Entity model bounds");
                    String[] paths=new String[parts];float[] boxes=new float[parts*STRIDE];
                    for(int p=0;p<parts;p++){paths[p]=in.readUTF();for(int f=0;f<STRIDE;f++){float v=in.readFloat();if(!Float.isFinite(v))throw new IllegalStateException("Entity model float");boxes[p*STRIDE+f]=v;}}
                    out[i]=new Model(name,width,height,paths,boxes);
                }
                if(in.read()!=-1)throw new IllegalStateException("Trailing entity model data");return out;
            }
        } catch(java.io.IOException e){throw new IllegalStateException("Vanilla entity models",e);}
    }
    static Model find(String prefix) {
        for(Model model:MODELS)if(model.name.startsWith(prefix))return model;
        throw new IllegalArgumentException("Missing vanilla model "+prefix);
    }
    static Model model(EntityTracker.Renderable e) {
        if(e.player||e.type==122)return find("PlayerModel.mesh");
        switch(e.type) {
            case 0:return find("AllayModel.");case 2:return find("ArmorStandModel.");case 4:return find("AxolotlModel.");
            case 5:return find("BatModel.");case 6:return find("BeeModel.");case 7:return find("BlazeModel.");
            case 9:return find(e.appearance[11]==7?"RaftModel.":"BoatModel.");
            case 13:return find(e.appearance[11]==7?"ChestRaftModel.":"ChestBoatModel.");
            case 10:return find("CamelModel.");case 11:case 67:return find("OcelotModel.mesh");
            case 12:case 95:return find("SpiderModel.");case 15:return find("ChickenModel.");case 16:return find("CodModel.");
            case 18:case 65:return find("CowModel.");case 19:return find("CreeperModel.");case 20:return find("DolphinModel.");
            case 27:return find("DragonModel.");case 25:case 46:return find("GuardianModel.");case 30:return find("EndermiteModel.");case 41:return find("GhastModel.");case 85:return find("SilverfishModel.");
            case 21:case 66:return find("ChestedHorseModel.");case 23:return find("DrownedModel.");case 29:return find("EndermanModel.");
            case 31:case 51:case 75:case 109:return find("IllagerModel.");case 32:return find("EvokerFangsModel.");
            case 38:return find("FoxModel.");case 39:return find("FrogModel.");case 42:case 50:case 118:return find("HumanoidModel.mesh");
            case 44:case 96:return find("SquidModel.");case 45:return find("GoatModel.");case 47:case 117:return find("HoglinModel.");
            case 49:case 87:case 119:return find("HorseModel.mesh");case 53:return find("IronGolemModel.");
            case 60:case 103:return find("LlamaModel.");case 62:return find("LavaSlimeModel.");
            case 69:return find("PandaModel.");case 70:return find("ParrotModel.");case 71:return find("PhantomModel.");
            case 72:return find("PigModel.");case 73:case 74:case 121:return find("PiglinModel.mesh");case 76:return find("PolarBearModel.");
            case 78:return find(e.appearance[17]==0?"PufferfishSmallModel.":e.appearance[17]==1?"PufferfishMidModel.":"PufferfishBigModel.");
            case 79:return find("RabbitModel.");case 80:return find("RavagerModel.");case 81:return find("SalmonModel.");
            case 82:return find("SheepModel.");case 83:return find("ShulkerModel.");case 86:case 97:case 114:return find("SkeletonModel.");
            case 88:return find("SlimeModel.b");case 90:return find("SnifferModel.");case 91:return find("SnowGolemModel.");
            case 98:return find("StriderModel.");case 99:return find("TadpoleModel.");case 106:return find("TurtleModel.");
            case 108:case 110:return find("VillagerModel.mesh");case 105:return find((e.appearance[17]&255)==0?"TropicalFishModelA.":"TropicalFishModelB.");case 107:return find("VexModel.");case 111:return find("WardenModel.");case 112:return find("WitchModel.");
            case 113:return find("WitherBossModel.");case 116:return find("WolfModel.");case 120:return find("ZombieVillagerModel.c");
            default:return com.newhorizon.thinclient.world.RidingState.minecart(e.type)?find("MinecartModel."):null;
        }
    }
    private VanillaEntityModels() { }
}
