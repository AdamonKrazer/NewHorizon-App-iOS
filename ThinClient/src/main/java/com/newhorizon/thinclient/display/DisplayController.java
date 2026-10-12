package com.newhorizon.thinclient.display;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded display lifecycle. Browsers are retained throughout the player radius. */
public final class DisplayController implements AutoCloseable {
    private final MemoryBudget budget;
    private final BrowserPort browsers;
    private final int maxDisplays;
    private final double visibleRadiusSquared;
    private final double retainRadiusSquared;
    private final LinkedHashMap<UUID, Entry> displays = new LinkedHashMap<>();
    private final Entry[] selected;
    private boolean viewKnown;private double viewX,viewY,viewZ,viewCy,viewSy,viewCp,viewSp,viewAspect,viewProjection;
    private boolean playerKnown;
    private String playerDimension;
    private double playerX,playerY,playerZ;
    /** Native Gecko frame slots are indexed directly and currently span 0..127. */
    private int nextBrowserId = 1;
    private final java.util.BitSet auxiliaryBrowsers=new java.util.BitSet(128);

    /** Shared native slots prevent tablets from replacing a live world display. */
    public synchronized int reserveAuxiliaryBrowser(){int id=allocateBrowserId();if(id>=0)auxiliaryBrowsers.set(id);return id;}
    public synchronized void releaseAuxiliaryBrowser(int id){if(id>=0)auxiliaryBrowsers.clear(id);}

    public DisplayController(MemoryBudget budget, BrowserPort browsers, int maxDisplays,
                             double visibleRadius, double retainRadius) {
        if (maxDisplays <= 0 || visibleRadius <= 0.0 || retainRadius < visibleRadius) {
            throw new IllegalArgumentException();
        }
        this.budget = budget;
        this.browsers = browsers;
        this.maxDisplays = maxDisplays;
        this.selected = new Entry[maxDisplays];
        this.visibleRadiusSquared = visibleRadius * visibleRadius;
        this.retainRadiusSquared = retainRadius * retainRadius;
    }

    public synchronized boolean handle(DisplayMessage message) {
        if (message instanceof DisplayMessage.Spawn) {
            return spawn(((DisplayMessage.Spawn) message).display);
        }
        if (message instanceof DisplayMessage.Remove) {
            remove(((DisplayMessage.Remove) message).id);
            return true;
        }
        if (message instanceof DisplayMessage.Clear) {
            clear();
            return true;
        }
        if (message instanceof DisplayMessage.Navigate) {
            DisplayMessage.Navigate navigate = (DisplayMessage.Navigate) message;
            Entry entry = displays.get(navigate.id);
            if (entry == null) return false;
            entry.url = navigate.url;
            if (entry.browserId >= 0) browsers.navigate(entry.browserId, navigate.url);
            return true;
        }
        return false;
    }

    private boolean spawn(VirtualDisplay display) {
        remove(display.id);
        // Keep cheap server records independently of expensive Gecko sessions.
        // Otherwise eight distant screens permanently discard new nearby ones.
        if (displays.size() >= 128) {
            Entry farthest=null;
            for(Entry e:displays.values())if(e.browserId<0&&(farthest==null||e.distance>farthest.distance))farthest=e;
            if(farthest==null)return false;
            remove(farthest.display.id);
        }
        MemoryBudget.Lease lease = budget.tryReserve(
                MemoryCategory.DISPLAY_STATE, display.estimatedStateBytes());
        if (lease == null) return false;
        Entry entry = new Entry(display, lease);
        displays.put(display.id, entry);
        System.out.println("[NH-THIN] display spawn id=" + display.id
                + " pos=" + display.x + "," + display.y + "," + display.z
                + " side=" + display.side + " blocks=" + display.widthBlocks
                + "x" + display.heightBlocks + " pixels=" + display.pixelWidth
                + "x" + display.pixelHeight + " url=" + describeUrl(display.url));
        if(playerKnown)updatePlayer(playerDimension,playerX,playerY,playerZ);
        else if(activeBrowsers()<maxDisplays)openBrowser(entry);
        return true;
    }

    /**
     * Keeps every browser alive inside retainRadius. Outside it only the tiny
     * display record remains; returning recreates the Gecko surface on demand.
     */
    public synchronized void updatePlayer(String dimension, double x, double y, double z) {
        playerKnown=true;playerDimension=dimension;playerX=x;playerY=y;playerZ=z;
        java.util.Arrays.fill(selected,null);
        for (Entry entry : displays.values()) {
            VirtualDisplay display = entry.display;
            boolean sameDimension = display.dimension.equals(dimension);
            double dx = display.x - x;
            double dy = display.y - y;
            double dz = display.z - z;
            double distanceSquared = sameDimension
                    ? dx * dx + dy * dy + dz * dz : Double.POSITIVE_INFINITY;
            entry.distance=distanceSquared;entry.selected=false;
            if(distanceSquared<=retainRadiusSquared){
                for(int i=0;i<selected.length;i++)if(selected[i]==null||distanceSquared<selected[i].distance){
                    for(int j=selected.length-1;j>i;j--)selected[j]=selected[j-1];selected[i]=entry;break;
                }
            }
        }
        for(Entry entry:selected)if(entry!=null)entry.selected=true;
        // Release first so replacement screens never exceed the browser budget.
        for(Entry entry:displays.values()){
            if(!entry.selected&&entry.browserId>=0){
                browsers.destroy(entry.browserId);
                entry.browserId = -1;
                entry.visible = false;
            }
        }
        for(Entry entry:selected)if(entry!=null){
            if(entry.browserId<0)openBrowser(entry);
            boolean visible=entry.distance<=visibleRadiusSquared&&inView(entry.display);
            if(entry.browserId>=0&&visible!=entry.visible){browsers.setVisible(entry.browserId,visible);entry.visible=visible;}
        }
    }

    /** Keep off-camera pages resident but suspend their compositor and background activity. */
    public synchronized void updateView(double x,double y,double z,float yaw,float pitch,float aspect,float projection){
        viewKnown=true;viewX=x;viewY=y;viewZ=z;viewCy=Math.cos(Math.toRadians(-yaw));viewSy=Math.sin(Math.toRadians(-yaw));viewCp=Math.cos(Math.toRadians(pitch));viewSp=Math.sin(Math.toRadians(pitch));viewAspect=aspect;viewProjection=projection;
        for(Entry e:displays.values())if(e.browserId>=0){boolean visible=e.distance<=visibleRadiusSquared&&inView(e.display);if(visible!=e.visible){browsers.setVisible(e.browserId,visible);e.visible=visible;}}
    }
    private boolean inView(VirtualDisplay d){
        if(!viewKnown)return true;
        double dx=d.x-viewX,dy=d.y-viewY,dz=d.z-viewZ,rx=viewCy*dx-viewSy*dz,rz=viewSy*dx+viewCy*dz,py=viewCp*dy+viewSp*rz,depth=viewCp*rz-viewSp*dy;
        double radius=.5*Math.sqrt(d.widthBlocks*d.widthBlocks+d.heightBlocks*d.heightBlocks)+1;
        return depth>=-radius&&Math.abs(rx)<=Math.max(0,depth)*viewAspect/viewProjection+radius*(1+viewAspect/viewProjection)&&Math.abs(py)<=Math.max(0,depth)/viewProjection+radius*(1+1/viewProjection);
    }

    private int activeBrowsers(){int count=0;for(Entry e:displays.values())if(e.browserId>=0)count++;return count;}

    public synchronized VirtualDisplay get(UUID id) {
        Entry entry = displays.get(id);
        return entry == null ? null : entry.display;
    }

    public synchronized int size() {
        return displays.size();
    }

    /** Copies render state into caller-owned records without retaining the lock during GL work. */
    public synchronized int snapshotVisible(Renderable[] output) {
        int count = 0;
        for (Entry entry : displays.values()) {
            if (!entry.visible || entry.browserId < 0 || count >= output.length) continue;
            Renderable target = output[count++];
            target.display = entry.display;
            target.browserId = entry.browserId;
        }
        return count;
    }

    public synchronized void focusBrowser(int browserId) {
        for (Entry entry : displays.values()) {
            if (entry.browserId == browserId) {
                browsers.setFocused(browserId, true);
                return;
            }
        }
    }

    private void openBrowser(Entry entry) {
        int browserId = allocateBrowserId();
        if (browserId < 0) return;
        entry.browserId = browserId;
        browsers.create(browserId, entry.url, true);
        browsers.resize(browserId, entry.display.pixelWidth, entry.display.pixelHeight);
        browsers.setVisible(browserId, true);
        entry.visible = true;
    }

    private int allocateBrowserId() {
        for (int attempt = 0; attempt < 127; attempt++) {
            int candidate = nextBrowserId++;
            if (nextBrowserId >= 128) nextBrowserId = 1;
            boolean inUse = auxiliaryBrowsers.get(candidate);
            for (Entry entry : displays.values()) {
                if (entry.browserId == candidate) {
                    inUse = true;
                    break;
                }
            }
            if (!inUse) return candidate;
        }
        return -1;
    }

    private static String describeUrl(String url) {
        if (url == null) return "null";
        int limit = Math.min(url.length(), 96);
        return url.substring(0, limit) + (url.length() > limit ? "..." : "");
    }

    private void remove(UUID id) {
        Entry entry = displays.remove(id);
        if (entry == null) return;
        if (entry.browserId >= 0) browsers.destroy(entry.browserId);
        entry.lease.close();
    }

    private void clear() {
        Iterator<Map.Entry<UUID, Entry>> iterator = displays.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.browserId >= 0) browsers.destroy(entry.browserId);
            entry.lease.close();
            iterator.remove();
        }
    }

    @Override
    public synchronized void close() {
        clear();
    }

    private static final class Entry {
        final VirtualDisplay display;
        final MemoryBudget.Lease lease;
        String url;
        int browserId = -1;
        boolean visible;
        boolean selected;
        double distance=Double.POSITIVE_INFINITY;

        Entry(VirtualDisplay display, MemoryBudget.Lease lease) {
            this.display = display;
            this.lease = lease;
            this.url = display.url;
        }
    }

    /** Mutable snapshot slot owned and reused by the render thread. */
    public static final class Renderable {
        public VirtualDisplay display;
        public int browserId = -1;
    }
}

