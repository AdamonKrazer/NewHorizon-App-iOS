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
    private boolean creativeMode;
    private long revision;

    public synchronized void handle(ByteBuffer payload) throws ProtocolException {
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

    public synchronized String selectedHotbarMaterial() {
        if (selectedHotbar < 0 || selectedHotbar >= materials.length) return "";
        return materials[selectedHotbar];
    }

    public synchronized int selectedHotbarAmount() {
        if (selectedHotbar < 0 || selectedHotbar >= amounts.length) return 0;
        return amounts[selectedHotbar];
    }
    public synchronized String selectedHotbarTag() {return tags[selectedHotbar()];}
    public synchronized String offhandTag() {return tags[40];}
    public synchronized String offhandMaterial() {return materials[40]==null?"":materials[40];}
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
            int slot=window==0?uiSlotForPlayerMenu(menu):window==-2?menu:-1;
            readStack(in,slot);
        } else {
            int count=com.newhorizon.thinclient.protocol.VarInts.read(in);
            if(count<0 || count>256) throw new ProtocolException("Inventory size outside limit");
            for(int i=0;i<count;i++) readStack(in,window==0?uiSlotForPlayerMenu(i):-1);
            readStack(in,-1); // The bridge owns the carried server stack.
        }
        if(in.hasRemaining()) throw new ProtocolException("Trailing inventory bytes");
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
        if(slot>=0 && slot<tags.length) {
            protocolIds[slot]=id; tags[slot]=tag;
            pads[slot]=pad;
            if(count==0) { amounts[slot]=0; materials[slot]=""; names[slot]=""; }
        }
    }

    public synchronized void clear() {
        Arrays.fill(materials,""); Arrays.fill(names,""); Arrays.fill(amounts,0);
        Arrays.fill(protocolIds,0); Arrays.fill(tags,null);
        Arrays.fill(pads,null);
        cursorMaterial=""; cursorName=""; cursorAmount=0; revision++;
    }

    private static int uiSlotForPlayerMenu(int menuSlot) {
        if (menuSlot >= 36 && menuSlot <= 44) return menuSlot - 36;
        if (menuSlot >= 9 && menuSlot <= 35) return menuSlot;
        if (menuSlot == 5) return 39;
        if (menuSlot == 6) return 38;
        if (menuSlot == 7) return 37;
        if (menuSlot == 8) return 36;
        if (menuSlot == 45) return 40;
        return -1;
    }

    /** Compact representation consumed by the Android vanilla inventory view. */
    public synchronized String encodeForUi() {
        StringBuilder output = new StringBuilder(1536);
        output.append(selectedHotbar).append('\t').append(cursorAmount).append('\t')
                .append(cursorMaterial).append('\t').append(cursorName).append('\t')
                .append(creativeMode ? 1 : 0).append('\n');
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
