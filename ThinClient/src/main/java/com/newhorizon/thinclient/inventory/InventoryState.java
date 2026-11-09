package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Fixed-slot inventory view shared by the network and render threads. */
public final class InventoryState {
    private final String[] materials = new String[InventoryProtocol.SLOT_COUNT];
    private final String[] names = new String[InventoryProtocol.SLOT_COUNT];
    private final int[] amounts = new int[InventoryProtocol.SLOT_COUNT];
    private final int[] protocolIds = new int[InventoryProtocol.SLOT_COUNT];
    private final String[] tags = new String[InventoryProtocol.SLOT_COUNT];
    private final com.newhorizon.thinclient.display.MinePadData[] pads=new com.newhorizon.thinclient.display.MinePadData[InventoryProtocol.SLOT_COUNT];
    private int selectedHotbar;
    private int cursorAmount;
    private String cursorMaterial = "";
    private String cursorName = "";
    private String cursorTag = "";
    private boolean nativeReady;
    private boolean creativeMode;
    private long revision;
    private com.newhorizon.thinclient.protocol.ForgeItemRegistry nativeItems;
    public synchronized void setNativeItems(com.newhorizon.thinclient.protocol.ForgeItemRegistry items) {
        nativeItems = items;
    }
    public synchronized String registryMaterial(int id) {
        if (nativeItems != null && nativeItems.ready()) return nativeItems.material(id);
        String name = WorkbenchState.vanillaMaterial(id);
        return name.isEmpty() ? "" : "minecraft:" + name;
    }


    public synchronized void handle(ByteBuffer payload) throws ProtocolException {
        if (nativeReady && !creativeMode) { payload.position(payload.limit()); return; }
        InventoryProtocol.decodeSnapshot(payload, this);
    }

    synchronized void beginSnapshot(int selected, int carriedAmount,
                                    String carriedMaterial, String carriedName) {
        Arrays.fill(materials, "");
        Arrays.fill(names, "");
        Arrays.fill(amounts, 0);
        selectedHotbar = selected;
        cursorAmount = carriedAmount;
        cursorMaterial = clean(carriedMaterial);
        cursorName = clean(carriedName);
        cursorTag = "";
    }

    synchronized void setSlot(int slot, int amount, String material, String name) {
        amounts[slot] = amount;
        materials[slot] = clean(material);
        names[slot] = clean(name);
    }

    synchronized void endSnapshot() {
        revision++;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized int selectedHotbar() {
        return Math.max(0, Math.min(8, selectedHotbar));
    }

    public synchronized void selectHotbar(int slot) {
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("hotbar slot");
        if (selectedHotbar != slot) { selectedHotbar = slot; revision++; }
    }

    public synchronized String selectedHotbarMaterial() {
        if (selectedHotbar < 0 || selectedHotbar >= materials.length) return "";
        return materials[selectedHotbar];
    }

    public synchronized int selectedHotbarAmount() {
        if (selectedHotbar < 0 || selectedHotbar >= amounts.length) return 0;
        return amounts[selectedHotbar];
    }
    public synchronized int selectedHotbarProtocolId(){return protocolIds[selectedHotbar()];}
    public synchronized String selectedHotbarTag() {return tags[selectedHotbar()];}
    public synchronized String offhandTag() {return tags[40];}
    /** The server sends our equipment through the player inventory, not remote-entity equipment packets. */
    public synchronized void copyEquipment(com.newhorizon.thinclient.world.EntityTracker.Renderable player) {
        for(int equipment=0;equipment<6;equipment++) {
            int slot=equipment==0?selectedHotbar():equipment==1?40:34+equipment;
            player.equipment[equipment]=amounts[slot]>0?protocolIds[slot]:-1;
            player.equipmentTags[equipment]=amounts[slot]>0&&tags[slot]!=null?tags[slot]:"";
        }
    }
    public synchronized String offhandMaterial() {return materials[40]==null?"":materials[40];}
    public synchronized String materialForProtocolId(int id) {
        if (nativeItems != null && nativeItems.ready()) return nativeItems.material(id);
        for (int slot = 0; slot < protocolIds.length; slot++)
            if (id == protocolIds[slot] && amounts[slot] > 0 && materials[slot] != null) return materials[slot];
        return "";
    }
    public synchronized String material(int slot){return slot>=0&&slot<materials.length?materials[slot]:"";}
    public synchronized com.newhorizon.thinclient.display.MinePadData pad(int slot){return slot>=0&&slot<pads.length?pads[slot]:null;}

    public synchronized void setCreativeMode(boolean creative) {
        if (creativeMode == creative) return;
        creativeMode = creative;
        revision++;
    }

    public synchronized boolean isCreativeMode() { return creativeMode; }

    /** Mirrors an accepted creative pick immediately while the server snapshot catches up. */
    public synchronized void setCreativeHotbar(String material, int amount) {
        if (selectedHotbar < 0 || selectedHotbar >= 9) return;
        materials[selectedHotbar] = clean(material);
        names[selectedHotbar] = "";
        amounts[selectedHotbar] = Math.max(0, Math.min(64, amount));
        revision++;
    }

    /** Mirrors a vanilla player-menu slot changed by the creative packet. */
    public synchronized void setCreativeSlot(int menuSlot, String material, int amount) {
        int slot = uiSlotForPlayerMenu(menuSlot);
        if (slot < 0 || slot >= materials.length) return;
        materials[slot] = clean(material);
        pads[slot] = null;
        names[slot] = "";
        amounts[slot] = Math.max(0, Math.min(64, amount));
        revision++;
    }

    public synchronized void setCreativeSlot(int menuSlot,String material,int amount,int protocolId,String nbt) {
        setCreativeSlot(menuSlot,material,amount);
        int slot=uiSlotForPlayerMenu(menuSlot);
        if(slot>=0) {
            protocolIds[slot]=protocolId; tags[slot]=nbt;
            if(nbt!=null&&nbt.length()<=131072&&(nbt.length()&1)==0&&!nbt.isEmpty()) {
                try {
                    byte[] bytes=new byte[nbt.length()/2];
                    for(int i=0;i<bytes.length;i++) {
                        int hi=Character.digit(nbt.charAt(i*2),16),lo=Character.digit(nbt.charAt(i*2+1),16);
                        if(hi<0||lo<0)throw new IllegalArgumentException("NBT hex");bytes[i]=(byte)(hi*16+lo);
                    }
                    pads[slot]=com.newhorizon.thinclient.display.MinePadData.read(ByteBuffer.wrap(bytes));
                }catch(ProtocolException|IllegalArgumentException ignored){}
            }
        }
    }

    /** Keep the complete small stack tag from vanilla, including potion/enchantment variants. */
    public synchronized void readVanillaContent(ByteBuffer in,boolean single) throws ProtocolException {
        com.newhorizon.thinclient.protocol.BinaryCodec.require(in,1);
        int window=in.get(); com.newhorizon.thinclient.protocol.VarInts.read(in);
        if(single) {
            com.newhorizon.thinclient.protocol.BinaryCodec.require(in,2); int menu=in.getShort();
            int slot=window==0?uiSlotForPlayerMenu(menu):window==-2?menu:
                    window==-1&&menu==-1&&!creativeMode?-3:-1;
            readStack(in,slot);
        } else {
            int count=com.newhorizon.thinclient.protocol.VarInts.read(in);
            if(count<0 || count>256) throw new ProtocolException("Inventory size outside limit");
            for(int i=0;i<count;i++) readStack(in,window==0?uiSlotForPlayerMenu(i):-1);
            readStack(in,window==0&&!creativeMode?-3:-1);
        }
        if(in.hasRemaining()) throw new ProtocolException("Trailing inventory bytes");
        if(window==0)nativeReady=true;
        revision++;
    }

    private void readStack(ByteBuffer in,int slot) throws ProtocolException {
        com.newhorizon.thinclient.protocol.BinaryCodec.require(in,1);
        int id=0,count=0; String tag="";
        com.newhorizon.thinclient.display.MinePadData pad=null;
        if(in.get()!=0) {
            id=com.newhorizon.thinclient.protocol.VarInts.read(in);
            com.newhorizon.thinclient.protocol.BinaryCodec.require(in,1); count=in.get()&255;
            int start=in.position(); com.newhorizon.thinclient.protocol.NbtSkipper.skipRoot(in);
            int end=in.position();
            if(slot>=0&&end-start<=65536){
                ByteBuffer data=in.duplicate();data.position(start);data.limit(end);
                try{pad=com.newhorizon.thinclient.display.MinePadData.read(data);}catch(ProtocolException ignored){}
            }
            // Oversized plugin/book data is left server-owned, never recreated with a missing tag.
            if(end-start<=4096) {
                StringBuilder hex=new StringBuilder((end-start)*2);
                final String digits="0123456789abcdef";
                for(int p=start;p<end;p++) { int b=in.get(p)&255; hex.append(digits.charAt(b>>4)).append(digits.charAt(b&15)); }
                tag=hex.toString();
            } else tag=null;
        }
        if(slot==-3) {
            cursorAmount=count; cursorMaterial=count==0?"":registryMaterial(id);
            cursorName=""; cursorTag=tag;
        } else if(slot>=0 && slot<tags.length) {
            int previousId=protocolIds[slot];
            protocolIds[slot]=id; tags[slot]=tag;
            pads[slot]=pad;
            if(count==0) { amounts[slot]=0; materials[slot]=""; names[slot]=""; }
            else {
                // Vanilla contents are authoritative even when the optional bridge is absent.
                // Unknown Forge IDs keep a bridge-provided key only for the same item.
                amounts[slot]=count;
                String vanilla=registryMaterial(id);
                if (nativeItems != null && nativeItems.ready()) {
                    materials[slot]=vanilla; names[slot]=""; return;
                }
                boolean resolved=materials[slot]!=null&&!materials[slot].isEmpty()
                        &&(previousId==0||previousId==id);
                // An explicit bridge key also wins over the bundled registry if Forge
                // assigned that numeric ID differently on this server.
                if(!resolved&&!vanilla.isEmpty()) materials[slot]=vanilla;
                else if(!resolved) materials[slot]="";
                names[slot]=""; // Custom display names remain available in the original NBT.
            }
        }
    }

    public synchronized void clear() {
        Arrays.fill(materials,""); Arrays.fill(names,""); Arrays.fill(amounts,0);
        Arrays.fill(protocolIds,0); Arrays.fill(tags,null);
        Arrays.fill(pads,null);
        cursorMaterial=""; cursorName=""; cursorTag=""; cursorAmount=0; nativeReady=false; revision++;
    }

    private static int uiSlotForPlayerMenu(int menuSlot) {
        if (menuSlot >= 36 && menuSlot <= 44) return menuSlot - 36;
        if (menuSlot >= 9 && menuSlot <= 35) return menuSlot;
        if (menuSlot == 5) return 39;
        if (menuSlot == 6) return 38;
        if (menuSlot == 7) return 37;
        if (menuSlot == 8) return 36;
        if (menuSlot == 45) return 40;
        if (menuSlot == 0) return 45;
        if (menuSlot >= 1 && menuSlot <= 4) return 40 + menuSlot;
        return -1;
    }

    public static int menuSlot(int slot) {
        if(slot==InventoryProtocol.OUTSIDE_SLOT)return -999;
        if(slot>=0&&slot<9)return slot+36;
        if(slot>=9&&slot<36)return slot;
        if(slot>=36&&slot<=39)return 44-slot;
        if(slot==40)return 45;
        if(slot>=41&&slot<=44)return slot-40;
        if(slot==45)return 0;
        throw new IllegalArgumentException("Player inventory slot");
    }

    public synchronized void clearCursor() {
        cursorAmount=0;cursorMaterial="";cursorName="";cursorTag="";revision++;
    }

    /** Compact representation consumed by the Android vanilla inventory view. */
    public synchronized String encodeForUi() {
        StringBuilder output = new StringBuilder(1536);
        output.append(selectedHotbar).append('\t').append(cursorAmount).append('\t')
                .append(cursorMaterial).append('\t').append(cursorName).append('\t')
                .append(creativeMode ? 1 : 0).append("\t\t").append(cursorTag==null?"?":cursorTag).append('\n');
        for (int slot = 0; slot < materials.length; slot++) {
            output.append(slot).append('\t').append(amounts[slot]).append('\t')
                    .append(materials[slot]).append('\t').append(names[slot]).append('\t')
                    .append(protocolIds[slot]).append('\t').append(tags[slot]==null?"?":tags[slot]).append('\n');
        }
        return output.toString();
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
