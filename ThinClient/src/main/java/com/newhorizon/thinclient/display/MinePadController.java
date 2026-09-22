package com.newhorizon.thinclient.display;

import com.newhorizon.thinclient.inventory.InventoryState;
import java.util.*;

/** Render-thread tablet state. The server item remains the durable source of PadID/PadTabs. */
public final class MinePadController implements AutoCloseable {
    // Original client's pad_resolution=720 with MinePad's 59:30 viewport ratio.
    public static final int WIDTH=1416,HEIGHT=720,MAX_LIVE=8;
    public interface Port extends BrowserPort {
        void show(int browser,String url,int index,String[] urls,boolean editing);
        void hide();
        void error(String message);
    }
    public interface Sender {void send(UUID id,int action,int index,String url)throws Exception;}
    private static final class Tab {
        String url,persisted;int browser=-1;long used,changed;boolean visible;
        Tab(String url){this.url=this.persisted=url;}
    }
    private static final class Pad {
        final UUID id;final ArrayList<Tab> tabs=new ArrayList<>();int index;
        MinePadData source;long optimisticUntil;boolean present;
        Pad(MinePadData data){id=data.id;source=data;index=data.index;for(String url:data.urls)tabs.add(new Tab(url));}
    }
    private final DisplayController displays;
    private final Port port;
    private final Sender sender;
    private final LinkedHashMap<UUID,Pad> pads=new LinkedHashMap<>();
    private final UUID[] slots=new UUID[41];
    private final Set<UUID> shutdownIds=new HashSet<>();
    private Pad opened;
    private long revision=-1,clock;
    private int heldMain=-1,heldOff=-1;
    public MinePadController(DisplayController displays,Port port,Sender sender){this.displays=displays;this.port=port;this.sender=sender;}
    public boolean isOpen(){return opened!=null;}
    public int activeBrowser(){return opened==null?-1:opened.tabs.get(opened.index).browser;}
    public int heldBrowser(boolean offhand){return offhand?heldOff:heldMain;}
    public void update(InventoryState inventory,long now){
        clock=now;
        for(Pad pad:pads.values())if(pad.optimisticUntil!=0&&now>=pad.optimisticUntil){revision=-1;pad.optimisticUntil=0;}
        if(revision!=inventory.revision()){
            revision=inventory.revision();for(Pad pad:pads.values())pad.present=false;
            for(int slot=0;slot<41;slot++){
                if(!MinePadData.item(inventory.material(slot))){slots[slot]=null;continue;}
                MinePadData data=inventory.pad(slot);Pad pad=null;
                if(data!=null&&data.id!=null){
                    if(shutdownIds.contains(data.id)){
                        Pad pending=pads.get(slots[slot]);
                        if(pending!=null&&now<pending.optimisticUntil){pending.present=true;continue;}
                        slots[slot]=null;continue;
                    }
                    slots[slot]=data.id;pad=pads.get(data.id);
                    if(pad==null){pad=new Pad(data);pads.put(data.id,pad);}
                    else if(!data.same(pad.source)){
                        if(now>=pad.optimisticUntil||matches(pad,data)){apply(pad,data);pad.source=data;pad.optimisticUntil=0;}
                    }
                }else if(slots[slot]!=null){pad=pads.get(slots[slot]);
                    if(pad!=null&&now>=pad.optimisticUntil){if(opened==pad)hide();destroy(pad);pads.remove(pad.id);slots[slot]=null;pad=null;}
                }
                if(pad!=null)pad.present=true;
            }
            Iterator<Pad> iterator=pads.values().iterator();
            while(iterator.hasNext()){Pad pad=iterator.next();if(!pad.present){if(opened==pad)hide();destroy(pad);iterator.remove();}}
        }
        for(Pad pad:pads.values()){
            Tab tab=pad.tabs.get(pad.index);
            if(tab.changed!=0&&now-tab.changed>=750_000_000L){persist(pad,tab);}
        }
        heldMain=held(inventory.selectedHotbar());heldOff=held(40);
        for(Pad pad:pads.values())for(Tab tab:pad.tabs){
            boolean visible=opened==pad&&pad.tabs.get(pad.index)==tab||tab.browser==heldMain||tab.browser==heldOff;
            if(tab.browser>=0&&tab.visible!=visible){port.setVisible(tab.browser,visible);tab.visible=visible;}
        }
    }
    private int held(int slot){Pad pad=pads.get(slots[slot]);return pad==null?-1:browser(pad.tabs.get(pad.index));}
    public void open(InventoryState inventory,int slot,boolean editing,long now){
        Pad pad=ensurePad(inventory,slot,now);if(pad==null)return;
        opened=pad;show(editing);
    }
    private Pad ensurePad(InventoryState inventory,int slot,long now){
        clock=now;if(!MinePadData.item(inventory.material(slot)))return null;
        Pad pad=pads.get(slots[slot]);
        if(pad==null){
            MinePadData data=inventory.pad(slot);
            if(data!=null&&shutdownIds.contains(data.id))data=null;
            String url=data==null?MinePadData.HOME:data.urls[data.index];
            UUID id=data==null||data.id==null?UUID.randomUUID():data.id;
            if(!send(id,0,0,url))return null;
            pad=new Pad(new MinePadData(id,new String[]{url},0));pad.optimisticUntil=now+5_000_000_000L;
            pad.present=true;pads.put(id,pad);slots[slot]=id;
        }
        return pad;
    }
    /** WDClientBrowser.enviarUrlParaMinePad adds/selects a tab without forcing the GUI open. */
    public boolean receiveDisplayUrl(InventoryState inventory,String url,long now){
        try{url=MinePadData.normalize(url);}catch(IllegalArgumentException e){return false;}
        if(!url.startsWith("http://")&&!url.startsWith("https://"))return false;
        int slot=MinePadData.item(inventory.selectedHotbarMaterial())?inventory.selectedHotbar():MinePadData.item(inventory.offhandMaterial())?40:-1;
        if(slot<0)for(int i=0;i<36;i++)if(MinePadData.item(inventory.material(i))){slot=i;break;}
        if(slot<0){port.error("Tenha um MinePad no inventário para abrir este link.");return false;}
        Pad pad=ensurePad(inventory,slot,now);if(pad==null||pad.tabs.size()>=MinePadData.MAX_TABS)return false;
        persist(pad,pad.tabs.get(pad.index));
        if(!send(pad.id,1,0,url))return false;
        pad.tabs.add(new Tab(url));pad.index=pad.tabs.size()-1;pad.optimisticUntil=now+3_000_000_000L;
        if(opened==pad)show(false);
        return true;
    }
    public void action(int action,int index,String value){
        if(action==5){hide();return;} // close GUI, distinct from item shutdown
        Pad pad=opened;if(pad==null)return;
        Tab old=pad.tabs.get(pad.index);persist(pad,old);
        if(action==0||action==1){
            String url;
            try{url=MinePadData.normalize(value);}catch(IllegalArgumentException e){port.error("Endere\u00e7o inv\u00e1lido. Use http:// ou https://.");return;}
            if(action==1&&pad.tabs.size()>=MinePadData.MAX_TABS){port.error("Limite de abas atingido.");return;}
            if(!send(pad.id,action,0,url))return;
            if(action==0){old.url=old.persisted=url;old.changed=0;if(old.browser>=0)port.navigate(old.browser,url);}
            else{pad.tabs.add(new Tab(url));pad.index=pad.tabs.size()-1;}
        }else if(action==2){
            int selected=Math.floorMod(index,pad.tabs.size());if(!send(pad.id,2,selected,""))return;pad.index=selected;
        }else if(action==3){
            if(pad.tabs.size()==1)return;
            int selected=index<0?pad.index:Math.max(0,Math.min(pad.tabs.size()-1,index));if(!send(pad.id,3,selected,""))return;
            Tab removed=pad.tabs.remove(selected);release(removed);
            if(selected<=pad.index&&pad.index>0)pad.index--;pad.index=Math.min(pad.index,pad.tabs.size()-1);
        }else if(action==4){
            if(!send(pad.id,4,0,""))return;UUID id=pad.id;hide();destroy(pad);pads.remove(id);
            if(shutdownIds.size()>=64)shutdownIds.remove(shutdownIds.iterator().next());shutdownIds.add(id);
            for(int i=0;i<slots.length;i++)if(id.equals(slots[i]))slots[i]=null;
            // Wait for the server's removal of PadID rather than resurrecting stale item NBT.
            return;
        }else return;
        pad.optimisticUntil=clock+3_000_000_000L;show(false);
    }
    public void location(int browser,String url){
        try{url=MinePadData.normalize(url);}catch(IllegalArgumentException ignored){return;}
        for(Pad pad:pads.values())for(Tab tab:pad.tabs)if(tab.browser==browser){
            if(!url.equals(tab.url)){tab.url=url;tab.changed=clock==0?1:clock;}
            return;
        }
    }
    public void hide(){
        if(opened==null)return;Tab tab=opened.tabs.get(opened.index);persist(opened,tab);tab.visible=false;opened=null;port.hide();
    }
    private void persist(Pad pad,Tab tab){
        if(tab.changed==0||tab.url.equals(tab.persisted)){tab.changed=0;return;}
        // The original packet changes the current tab. Background tabs cannot overwrite it.
        if(pad.tabs.get(pad.index)!=tab)return;
        if(send(pad.id,0,pad.index,tab.url)){tab.persisted=tab.url;tab.changed=0;pad.optimisticUntil=clock+3_000_000_000L;}
    }
    private boolean send(UUID id,int action,int index,String url){
        try{sender.send(id,action,index,url);return true;}
        catch(Exception failure){port.error("N\u00e3o foi poss\u00edvel atualizar o MinePad no servidor.");return false;}
    }
    private void show(boolean editing){
        if(opened==null)return;Tab tab=opened.tabs.get(opened.index);int id=browser(tab);
        if(id<0){port.error("Navegador indispon\u00edvel.");hide();return;}
        String[] urls=new String[opened.tabs.size()];for(int i=0;i<urls.length;i++)urls[i]=opened.tabs.get(i).url;
        port.show(id,tab.url,opened.index,urls,editing);
    }
    private int browser(Tab tab){
        tab.used=++clock;if(tab.browser>=0)return tab.browser;
        int count=0;Tab oldest=null;
        for(Pad pad:pads.values())for(Tab candidate:pad.tabs)if(candidate.browser>=0){
            count++;if(candidate.browser!=heldMain&&candidate.browser!=heldOff&&(opened==null||opened.tabs.get(opened.index)!=candidate)
                    &&(oldest==null||candidate.used<oldest.used))oldest=candidate;
        }
        if(count>=MAX_LIVE){if(oldest==null)return -1;release(oldest);}
        int id=displays.reserveAuxiliaryBrowser();if(id<0)return -1;
        tab.browser=id;tab.visible=true;port.create(id,tab.url,true);port.resize(id,WIDTH,HEIGHT);return id;
    }
    private void release(Tab tab){if(tab.browser>=0){port.destroy(tab.browser);displays.releaseAuxiliaryBrowser(tab.browser);tab.browser=-1;tab.visible=false;}}
    private void destroy(Pad pad){for(Tab tab:pad.tabs)release(tab);}
    private static boolean matches(Pad pad,MinePadData data){
        if(pad.index!=data.index||pad.tabs.size()!=data.urls.length)return false;
        for(int i=0;i<data.urls.length;i++)if(!pad.tabs.get(i).url.equals(data.urls[i]))return false;return true;
    }
    private void apply(Pad pad,MinePadData data){
        boolean changed=!matches(pad,data);
        while(pad.tabs.size()>data.urls.length)release(pad.tabs.remove(pad.tabs.size()-1));
        while(pad.tabs.size()<data.urls.length)pad.tabs.add(new Tab(data.urls[pad.tabs.size()]));
        for(int i=0;i<data.urls.length;i++){Tab tab=pad.tabs.get(i);String url=data.urls[i];
            if(!url.equals(tab.url)&&tab.changed==0){tab.url=tab.persisted=url;if(tab.browser>=0)port.navigate(tab.browser,url);}
        }
        pad.index=data.index;if(changed&&opened==pad)show(false);
    }
    @Override public void close(){hide();for(Pad pad:pads.values())destroy(pad);pads.clear();Arrays.fill(slots,null);}
}
