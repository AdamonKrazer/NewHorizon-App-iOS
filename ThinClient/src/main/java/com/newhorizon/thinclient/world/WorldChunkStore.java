package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;
import com.newhorizon.thinclient.protocol.ProtocolException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fixed-slot world cache. Eviction reuses direct storage and allocates nothing. */
public final class WorldChunkStore implements AutoCloseable {
    /** Bounded DDA against the existing vanilla collision boxes, for visual flight. */
    public synchronized double projectileClip(double ox, double oy, double oz,
                                               double dx, double dy, double dz) {
        int x=(int)Math.floor(ox), y=(int)Math.floor(oy), z=(int)Math.floor(oz);
        int sx=dx<0?-1:1, sy=dy<0?-1:1, sz=dz<0?-1:1;
        double tx=dx==0?Double.POSITIVE_INFINITY:(x+(sx>0?1:0)-ox)/dx;
        double ty=dy==0?Double.POSITIVE_INFINITY:(y+(sy>0?1:0)-oy)/dy;
        double tz=dz==0?Double.POSITIVE_INFINITY:(z+(sz>0?1:0)-oz)/dz;
        for (int cell=0;cell<64;cell++) {
            int state=blockStateAtInternal(x,y,z);
            double closest=1;
            if (state>=0) for (int box=0;box<BlockStatePhysics.collisionBoxCount(state);box++) {
                double hit=EntityTracker.rayAabb(ox,oy,oz,dx,dy,dz,
                        x+BlockStatePhysics.collisionCoordinate(state,box,0),
                        y+BlockStatePhysics.collisionCoordinate(state,box,1),
                        z+BlockStatePhysics.collisionCoordinate(state,box,2),
                        x+BlockStatePhysics.collisionCoordinate(state,box,3),
                        y+BlockStatePhysics.collisionCoordinate(state,box,4),
                        z+BlockStatePhysics.collisionCoordinate(state,box,5),1);
                if(hit>=0) closest=Math.min(closest,hit);
            }
            if(closest<1) return closest;
            if(Math.min(tx,Math.min(ty,tz))>1) break;
            if(tx<=ty && tx<=tz) { x+=sx; tx+=Math.abs(1/dx); }
            else if(ty<=tz) { y+=sy; ty+=Math.abs(1/dy); }
            else { z+=sz; tz+=Math.abs(1/dz); }
        }
        return 1;
    }
    private final MemoryBudget budget;
    private final SurfaceChunk[] chunks;
    private final MemoryBudget.Lease[] leases;
    private final int bytesPerChunk;
    private final int activeRadius;
    private final VanillaChunkDecoder vanillaChunkDecoder = new VanillaChunkDecoder();
    private final VanillaChunkAuxDecoder vanillaChunkAuxDecoder =
            new VanillaChunkAuxDecoder();
    private static final int UPDATE_JOURNAL_CAPACITY = 8192;
    private final long[] updatePositions = new long[UPDATE_JOURNAL_CAPACITY];
    private final int[] updateStates = new int[UPDATE_JOURNAL_CAPACITY];
    private final long[] updateStamps = new long[UPDATE_JOURNAL_CAPACITY];
    private int updateCount;
    private long updateClock;
    private ColdChunkCache coldChunks;
    private ByteBuffer coldReadScratch;
    private MemoryBudget.Lease coldScratchLease;
    private boolean coldCacheUnavailable;
    private int coldCacheFailureLogCount;
    private int coldPromotionLogCount;
    private int size;
    private long clock;
    private int playerChunkX;
    private int playerChunkZ;
    private double playerX;
    private double playerY = 64.0;
    private double playerZ;
    private float playerYaw;
    private float playerPitch;
    private boolean hasPlayerPosition;
    private long playerRevision;
    private long teleportRevision;
    private int vanillaBlockUpdateLogCount;
    private int evictionCount;
    private int rejectedFarChunkCount;
    private int decodedCountMismatchLogCount;
    private int bridgeAuditLogCount;
    private boolean closed;
    private String dimension = "minecraft:overworld";
    private int minY = -64;
    private int height = 384;
    private long meshEpoch;
    private boolean deferredPromotions, promotionPending;
    private final Object chunkDecodeLock=new Object();
    private SurfaceChunk decodedScratch;
    private final VanillaChunkDecoder offThreadDecoder=new VanillaChunkDecoder();

    public synchronized void deferPromotions() { deferredPromotions=true; }

    /** Called by the mesh worker, never by the GL/input thread. */
    public void serviceColdPromotion() {
        synchronized(chunkDecodeLock) {
            ColdChunkCache cache;int x=0,z=0,worldHeight;long epoch;boolean found=false;
            synchronized(this) {
                if(closed||!promotionPending||coldChunks==null)return;
                cache=coldChunks;worldHeight=Math.min(height,512);epoch=meshEpoch;
                search:for(int ring=0;ring<=activeRadius;ring++)for(int cz=playerChunkZ-ring;cz<=playerChunkZ+ring;cz++)for(int cx=playerChunkX-ring;cx<=playerChunkX+ring;cx++) {
                    if(Math.max(Math.abs(cx-playerChunkX),Math.abs(cz-playerChunkZ))!=ring)continue;
                    int slot=find(cx,cz);if(slot>=0&&chunks[slot].hasFullBlockStates)continue;
                    if(cache.contains(ColdChunkCache.KIND_SECTIONS,cx,cz)){x=cx;z=cz;found=true;break search;}
                }
                if(!found){promotionPending=false;return;}
            }
            try {
                if(!cache.readInto(ColdChunkCache.KIND_SECTIONS,x,z,coldReadScratch))return;
                SurfaceChunk decoded=decodeScratch(x,z,coldReadScratch,worldHeight);
                if(cache.readInto(ColdChunkCache.KIND_AUXILIARY,x,z,coldReadScratch))vanillaChunkAuxDecoder.decodeInitial(coldReadScratch,decoded,worldHeight);
                if(cache.readInto(ColdChunkCache.KIND_BIOMES,x,z,coldReadScratch))offThreadDecoder.decodeBiomeUpdate(coldReadScratch,decoded,worldHeight);
                synchronized(this) {
                    if(closed||meshEpoch!=epoch||!withinActiveRadius(x,z)||coldChunks!=cache)return;
                    replayBlockUpdates(decoded);publishDecoded(decoded);
                }
            } catch(IOException|ProtocolException error) { synchronized(this){promotionPending=false;} System.err.println("[NH-THIN] cold promotion: "+error); }
        }
    }

    /** Network packet's sections and light become visible together after off-lock decoding. */
    public void applyVanillaChunkWithLight(int x,int z,ByteBuffer sections,ByteBuffer auxiliary)throws ProtocolException {
        synchronized(chunkDecodeLock) {
            ColdChunkCache cache=null;int worldHeight;long epoch;boolean active;
            synchronized(this) {
                ensureOpen();clearBlockUpdates(x,z);epoch=meshEpoch;worldHeight=Math.min(height,512);
                if(activeRadius>=0&&ensureColdCache())cache=coldChunks;
                active=activeRadius<0||(hasPlayerPosition&&withinActiveRadius(x,z))||cache==null;
            }
            if(cache!=null)try {
                cache.put(ColdChunkCache.KIND_SECTIONS,x,z,sections.asReadOnlyBuffer());
                cache.put(ColdChunkCache.KIND_AUXILIARY,x,z,auxiliary.asReadOnlyBuffer());
            }catch(IOException error){System.err.println("[NH-THIN] cold write: "+error);}
            if(!active)return;
            SurfaceChunk decoded=decodeScratch(x,z,sections,worldHeight);
            vanillaChunkAuxDecoder.decodeInitial(auxiliary,decoded,worldHeight);
            synchronized(this) {
                if(!closed&&meshEpoch==epoch)publishDecoded(decoded);
            }
        }
    }

    private SurfaceChunk decodeScratch(int x,int z,ByteBuffer sections,int worldHeight)throws ProtocolException {
        if(decodedScratch==null) {
            budget.reserve(MemoryCategory.WORLD,bytesPerChunk+SurfaceChunk.FULL_BLOCK_BYTES+SurfaceChunk.BIOME_BYTES+SurfaceChunk.LIGHT_BYTES+SurfaceChunk.BLOCK_ENTITY_BYTES);
            decodedScratch=new SurfaceChunk(ByteBuffer.allocateDirect(bytesPerChunk),ByteBuffer.allocateDirect(SurfaceChunk.FULL_BLOCK_BYTES),ByteBuffer.allocateDirect(SurfaceChunk.BIOME_BYTES),ByteBuffer.allocateDirect(SurfaceChunk.LIGHT_BYTES),ByteBuffer.allocateDirect(SurfaceChunk.BLOCK_ENTITY_BYTES));
        }
        decodedScratch.reset(x,z);decodedScratch.beginFullBlockUpdate();
        offThreadDecoder.decodeSections(sections,decodedScratch,worldHeight);
        decodedScratch.finishFullBlockUpdate(worldHeight);return decodedScratch;
    }

    private void publishDecoded(SurfaceChunk decoded) {
        int slot=find(decoded.chunkX,decoded.chunkZ);if(slot<0)slot=acquireSlot(decoded.chunkX,decoded.chunkZ);
        if(slot<0)return;SurfaceChunk target=chunks[slot];int revision=target.revision+1;
        target.copyFrom(decoded);target.revision=revision;target.lastTouched=++clock;
        invalidateMeshingNeighbors(target.chunkX,target.chunkZ);
    }

    public static final class MeshSnapshot {
        public final SurfaceChunk[] neighbors=new SurfaceChunk[9];
        private final SurfaceChunk[] storage=new SurfaceChunk[9];
        public int slot, minY;
        public long epoch;
        private MeshSnapshot(int recordBytes) {
            for(int i=0;i<9;i++) storage[i]=new SurfaceChunk(
                    ByteBuffer.allocateDirect(recordBytes), ByteBuffer.allocateDirect(SurfaceChunk.FULL_BLOCK_BYTES),
                    ByteBuffer.allocateDirect(SurfaceChunk.BIOME_BYTES), ByteBuffer.allocateDirect(SurfaceChunk.LIGHT_BYTES),
                    ByteBuffer.allocate(0));
        }
    }

    public MeshSnapshot createMeshSnapshot() {
        budget.reserve(MemoryCategory.MESH,9L*(bytesPerChunk+SurfaceChunk.FULL_BLOCK_BYTES+SurfaceChunk.BIOME_BYTES+SurfaceChunk.LIGHT_BYTES));
        return new MeshSnapshot(bytesPerChunk);
    }

    /** x, z, revision per resident slot, with epoch in the final entry. */
    public synchronized int copyMeshVersions(long[] out) {
        for(int i=0;i<size;i++) { out[i*3]=chunks[i].chunkX;out[i*3+1]=chunks[i].chunkZ;out[i*3+2]=chunks[i].revision; }
        out[out.length-1]=meshEpoch;
        return size;
    }

    public synchronized void requestMeshRefresh(int x,int z,long epoch) {
        if(epoch!=meshEpoch)return;
        int slot=find(x,z);if(slot>=0)chunks[slot].revision++;
    }

    public synchronized boolean nextMeshSnapshot(MeshSnapshot out,long[] built) {
        if(closed)return false;
        int selected=-1;long nearest=Long.MAX_VALUE;
        for(int i=0;i<size;i++) {
            SurfaceChunk c=chunks[i];
            if(built[built.length-1]==meshEpoch&&built[i*3]==c.chunkX&&built[i*3+1]==c.chunkZ&&built[i*3+2]==c.revision)continue;
            long distance=chunkDistancePriority(c.chunkX,c.chunkZ);
            if(distance<nearest){nearest=distance;selected=i;}
        }
        if(selected<0)return false;
        SurfaceChunk center=chunks[selected];
        java.util.Arrays.fill(out.neighbors,null);
        for(int i=0;i<size;i++) {
            SurfaceChunk c=chunks[i];int dx=c.chunkX-center.chunkX,dz=c.chunkZ-center.chunkZ;
            if(dx<-1||dx>1||dz<-1||dz>1)continue;
            int n=(dz+1)*3+dx+1;out.storage[n].copyFrom(c);out.neighbors[n]=out.storage[n];
        }
        out.slot=selected;out.minY=minY;out.epoch=meshEpoch;return true;
    }

    public WorldChunkStore(MemoryBudget budget, int maxChunks, int maxRecordsPerChunk) {
        this(budget, maxChunks, maxRecordsPerChunk, -1);
    }

    public WorldChunkStore(MemoryBudget budget, int maxChunks, int maxRecordsPerChunk,
                           int activeRadius) {
        if (maxChunks <= 0 || maxRecordsPerChunk <= 0
                || maxRecordsPerChunk > WorldProtocol.MAX_RECORDS_PER_CHUNK
                || activeRadius < -1
                || (activeRadius >= 0
                && (2 * activeRadius + 1) * (2 * activeRadius + 1) > maxChunks)) {
            throw new IllegalArgumentException();
        }
        this.budget = budget;
        this.activeRadius = activeRadius;
        chunks = new SurfaceChunk[maxChunks];
        leases = new MemoryBudget.Lease[maxChunks];
        bytesPerChunk = maxRecordsPerChunk * SurfaceChunk.RECORD_BYTES;
    }

    public synchronized void handle(WorldMessage message) {
        ensureOpen();
        if (message instanceof WorldMessage.Reset) {
            WorldMessage.Reset reset = (WorldMessage.Reset) message;
            boolean sameWorldGeometry = dimension.equals(reset.dimension)
                    && minY == reset.minY && height == reset.height;
            boolean hasVanillaChunks = false;
            if (sameWorldGeometry) {
                for (int index = 0; index < size; index++) {
                    if (chunks[index].hasFullBlockStates) {
                        hasVanillaChunks = true;
                        break;
                    }
                }
            }
            dimension = reset.dimension;
            minY = reset.minY;
            height = reset.height;
            if (!sameWorldGeometry) {
                if (coldChunks != null) coldChunks.clear();
                updateCount = 0;
            }
            // The bridge announces the same world after vanilla already sent
            // LevelChunkWithLight. Do not let that announcement erase the
            // authoritative complete chunks and replace them with its surface.
            if (!hasVanillaChunks) {
                meshEpoch++;
                for(int i=0;i<size;i++)chunks[i].revision++;
                size=0;
            }
            return;
        }
        if (message instanceof WorldMessage.PlayerPosition) {
            WorldMessage.PlayerPosition position = (WorldMessage.PlayerPosition) message;
            int oldChunkX = playerChunkX;
            int oldChunkZ = playerChunkZ;
            boolean hadPlayerPosition = hasPlayerPosition;
            playerX = position.x;
            playerY = position.y;
            playerZ = position.z;
            playerYaw = position.yaw;
            playerPitch = position.pitch;
            hasPlayerPosition = true;
            playerRevision++;
            playerChunkX = floorChunk(position.x);
            playerChunkZ = floorChunk(position.z);
            if (!hadPlayerPosition || playerChunkX != oldChunkX
                    || playerChunkZ != oldChunkZ) promoteColdAroundPlayer();
            return;
        }
        if (message instanceof WorldMessage.RemoveChunk) {
            WorldMessage.RemoveChunk remove = (WorldMessage.RemoveChunk) message;
            int index = find(remove.chunkX, remove.chunkZ);
            // This is the optional surface bridge's removal notice. It must
            // never erase an authoritative vanilla LevelChunkWithLight copy.
            if (index >= 0 && !chunks[index].hasFullBlockStates) removeAt(index);
            return;
        }
        if (message instanceof WorldMessage.ChunkSurface) {
            put((WorldMessage.ChunkSurface) message);
        }
    }

    private void put(WorldMessage.ChunkSurface incoming) {
        if (incoming.records.remaining() > bytesPerChunk) return;
        int index = find(incoming.chunkX, incoming.chunkZ);
        if (index < 0) {
            index = acquireSlot(incoming.chunkX, incoming.chunkZ);
            if (index < 0) return;
        }
        SurfaceChunk chunk = chunks[index];
        if (chunk.hasFullBlockStates) {
            auditBridgeSurface(chunk, incoming.records, incoming.recordCount);
        } else {
            chunk.records.clear();
            chunk.records.put(incoming.records.slice());
            // Network revisions do not include changes to the eight adjacent chunks.
            chunk.revision++;
            chunk.recordCount = incoming.recordCount;
            chunk.rebuildCollisionIndex();
            invalidateMeshingNeighbors(chunk.chunkX,chunk.chunkZ);
        }
        chunk.lastTouched = ++clock;
    }

    private void allocateSlot(int index) {
        MemoryBudget.Lease lease = budget.reserve(MemoryCategory.WORLD,
                bytesPerChunk + SurfaceChunk.FULL_BLOCK_BYTES
                        + SurfaceChunk.BIOME_BYTES + SurfaceChunk.LIGHT_BYTES
                        + SurfaceChunk.BLOCK_ENTITY_BYTES);
        try {
            ByteBuffer records = ByteBuffer.allocateDirect(bytesPerChunk)
                    .order(ByteOrder.BIG_ENDIAN);
            ByteBuffer fullBlocks = ByteBuffer.allocateDirect(SurfaceChunk.FULL_BLOCK_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            ByteBuffer biomes = ByteBuffer.allocateDirect(SurfaceChunk.BIOME_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            ByteBuffer light = ByteBuffer.allocateDirect(SurfaceChunk.LIGHT_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            ByteBuffer blockEntities = ByteBuffer.allocateDirect(
                    SurfaceChunk.BLOCK_ENTITY_BYTES).order(ByteOrder.BIG_ENDIAN);
            chunks[index] = new SurfaceChunk(records, fullBlocks, biomes,
                    light, blockEntities);
            leases[index] = lease;
        } catch (RuntimeException | Error throwable) {
            lease.close();
            throw throwable;
        }
    }

    private int acquireSlot(int chunkX, int chunkZ) {
        int previousSize=size;
        int index;
        if (size < chunks.length) {
            index = size++;
        } else {
            index = evictionIndex();
            SurfaceChunk farthest = chunks[index];
            long oldDistance = chunkDistancePriority(
                    farthest.chunkX, farthest.chunkZ);
            long newDistance = chunkDistancePriority(chunkX, chunkZ);
            // Network order must never allow a late, distant chunk to evict a
            // complete chunk next to the player. The server will send that
            // distant chunk again when it actually enters the requested view.
            if (newDistance > oldDistance) {
                rejectedFarChunkCount++;
                if (rejectedFarChunkCount <= 8
                        || (rejectedFarChunkCount & 127) == 0) {
                    System.out.println("[NH-THIN] far chunk ignored #"
                            + rejectedFarChunkCount + " chunk=" + chunkX + ","
                            + chunkZ + " d2=" + newDistance + " retainedD2="
                            + oldDistance);
                }
                return -1;
            }
        }
        if (chunks[index] == null) allocateSlot(index);
        if(index<previousSize)invalidateMeshingNeighbors(chunks[index].chunkX,chunks[index].chunkZ);
        if (size == chunks.length && chunks[index].hasFullBlockStates) {
            evictionCount++;
            if (evictionCount <= 16 || (evictionCount & 63) == 0) {
                long oldDx = (long) chunks[index].chunkX - playerChunkX;
                long oldDz = (long) chunks[index].chunkZ - playerChunkZ;
                long newDx = (long) chunkX - playerChunkX;
                long newDz = (long) chunkZ - playerChunkZ;
                System.out.println("[NH-THIN] chunk eviction #" + evictionCount
                        + " old=" + chunks[index].chunkX + "," + chunks[index].chunkZ
                        + " d2=" + (oldDx * oldDx + oldDz * oldDz)
                        + " new=" + chunkX + "," + chunkZ
                        + " d2=" + (newDx * newDx + newDz * newDz)
                        + " player=" + playerChunkX + "," + playerChunkZ);
            }
        }
        int nextRevision=chunks[index].revision+1;
        chunks[index].reset(chunkX, chunkZ);
        chunks[index].revision=nextRevision;
        invalidateMeshingNeighbors(chunkX,chunkZ);
        return index;
    }

    private long chunkDistancePriority(int chunkX, int chunkZ) {
        long dx = (long) chunkX - playerChunkX;
        long dz = (long) chunkZ - playerChunkZ;
        // Chebyshev distance retains a complete square. Squared distance is
        // only a tie-breaker inside one ring.
        long ring = Math.max(Math.abs(dx), Math.abs(dz));
        return ring * 1_000_000L + dx * dx + dz * dz;
    }

    private int evictionIndex() {
        int result = 0;
        long bestDistance = Long.MIN_VALUE;
        long oldest = Long.MAX_VALUE;
        for (int index = 0; index < size; index++) {
            SurfaceChunk chunk = chunks[index];
            long dx = (long) chunk.chunkX - playerChunkX;
            long dz = (long) chunk.chunkZ - playerChunkZ;
            long ring = Math.max(Math.abs(dx), Math.abs(dz));
            long distance = ring * 1_000_000L + dx * dx + dz * dz;
            if (distance > bestDistance
                    || (distance == bestDistance && chunk.lastTouched < oldest)) {
                bestDistance = distance;
                oldest = chunk.lastTouched;
                result = index;
            }
        }
        return result;
    }

    private int find(int x, int z) {
        for (int index = 0; index < size; index++) {
            SurfaceChunk chunk = chunks[index];
            if (chunk.chunkX == x && chunk.chunkZ == z) return index;
        }
        return -1;
    }
    /** Dirty only direct neighbours; revision increments never recurse into their neighbours. */
    private void invalidateMeshingNeighbors(int chunkX,int chunkZ) {
        for(int index=0;index<size;index++) {
            SurfaceChunk chunk=chunks[index];
            long dx=(long)chunk.chunkX-chunkX,dz=(long)chunk.chunkZ-chunkZ;
            if(dx>=-1&&dx<=1&&dz>=-1&&dz<=1&&(dx!=0||dz!=0))chunk.revision++;
        }
    }
    /** Only shared chunk edges/corners depend on a single changed block. */
    private void invalidateForBlock(int worldX,int worldZ) {
        int x=worldX&15,z=worldZ&15;
        if(x!=0&&x!=15&&z!=0&&z!=15)return;
        int chunkX=worldX>>4,chunkZ=worldZ>>4;
        int minX=x==0?-1:0,maxX=x==15?1:0,minZ=z==0?-1:0,maxZ=z==15?1:0;
        for(int index=0;index<size;index++) {
            SurfaceChunk chunk=chunks[index];
            long dx=(long)chunk.chunkX-chunkX,dz=(long)chunk.chunkZ-chunkZ;
            if(dx>=minX&&dx<=maxX&&dz>=minZ&&dz<=maxZ&&(dx!=0||dz!=0))chunk.revision++;
        }
    }
    /**
     * 3x3 resident references ordered (dz+1)*3+dx+1, centre at index four.
     * Consume inside visitChunks (or under this store's lock); entries are mutable reusable slots.
     */
    public synchronized void copyMeshingNeighbors(int chunkX,int chunkZ,SurfaceChunk[] out) {
        ensureOpen();if(out==null||out.length!=9)throw new IllegalArgumentException("Nine meshing neighbour slots required");
        java.util.Arrays.fill(out,null);
        for(int index=0;index<size;index++) {
            SurfaceChunk chunk=chunks[index];
            long dx=(long)chunk.chunkX-chunkX,dz=(long)chunk.chunkZ-chunkZ;
            if(dx>=-1&&dx<=1&&dz>=-1&&dz<=1)out[(int)((dz+1)*3+dx+1)]=chunk;
        }
    }

    private void removeAt(int index) {
        invalidateMeshingNeighbors(chunks[index].chunkX,chunks[index].chunkZ);
        chunks[index].revision++;
        int last = --size;
        if (index != last) {
            SurfaceChunk removed = chunks[index];
            MemoryBudget.Lease removedLease = leases[index];
            chunks[index] = chunks[last];
            leases[index] = leases[last];
            chunks[last] = removed;
            leases[last] = removedLease;
        }
    }

    public synchronized int size() {
        return size;
    }

    public synchronized SurfaceChunk chunkAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return chunks[index];
    }

    public synchronized String dimension() {
        return dimension;
    }

    public synchronized int minY() {
        return minY;
    }

    public synchronized int height() {
        return height;
    }

    public synchronized void copyPlayerView(double[] position, float[] rotation) {
        if (position.length < 3 || rotation.length < 2) throw new IllegalArgumentException();
        position[0] = playerX;
        position[1] = playerY;
        position[2] = playerZ;
        rotation[0] = playerYaw;
        rotation[1] = playerPitch;
    }

    public synchronized boolean hasPlayerPosition() {
        return hasPlayerPosition;
    }

    public synchronized long playerRevision() {
        return playerRevision;
    }

    /** Revision changed only by Minecraft's authoritative teleport packet. */
    public synchronized long teleportRevision() {
        return teleportRevision;
    }

    /** Fast allocation-free lookup used by the local player physics. */
    public synchronized boolean isSolidBlock(int worldX, int worldY, int worldZ) {
        int relativeY = worldY - minY;
        if (relativeY < 0 || relativeY >= Math.min(height, 512)) return false;
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0) return false;
        SurfaceChunk chunk = chunks[index];
        if (chunk.hasFullBlockStates) {
            return BlockStatePhysics.blocksMovement(chunk.fullBlockState(
                    worldX & 15, relativeY, worldZ & 15));
        }
        return chunk.isCollidable(worldX & 15, relativeY, worldZ & 15);
    }

    /** Tests the player's AABB against the actual 1.20.1 VoxelShapes. */
    public synchronized boolean collidesBox(double minX, double minY, double minZ,
                                            double maxX, double maxY, double maxZ) {
        int firstX = (int) Math.floor(minX + 1.0e-7);
        int lastX = (int) Math.floor(maxX - 1.0e-7);
        int firstY = (int) Math.floor(minY + 1.0e-7);
        int lastY = (int) Math.floor(maxY - 1.0e-7);
        int firstZ = (int) Math.floor(minZ + 1.0e-7);
        int lastZ = (int) Math.floor(maxZ - 1.0e-7);
        for (int y = firstY; y <= lastY; y++) {
            for (int z = firstZ; z <= lastZ; z++) {
                for (int x = firstX; x <= lastX; x++) {
                    int state = blockStateAtInternal(x, y, z);
                    if (state >= 0 && BlockStatePhysics.intersectsCollision(
                            state, x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Vanilla Shapes.collide for one axis, without allocating a shape list. */
    public synchronized double clipMovement(int axis,
                                            double minX, double minY, double minZ,
                                            double maxX, double maxY, double maxZ,
                                            double movement) {
        if (Math.abs(movement) < 1.0e-7) return 0.0;
        double movedMinX = axis == 0 ? minX + movement : minX;
        double movedMinY = axis == 1 ? minY + movement : minY;
        double movedMinZ = axis == 2 ? minZ + movement : minZ;
        double movedMaxX = axis == 0 ? maxX + movement : maxX;
        double movedMaxY = axis == 1 ? maxY + movement : maxY;
        double movedMaxZ = axis == 2 ? maxZ + movement : maxZ;
        int firstX = (int) Math.floor(Math.min(minX, movedMinX) - 1.0e-7);
        int lastX = (int) Math.floor(Math.max(maxX, movedMaxX) + 1.0e-7);
        int firstY = (int) Math.floor(Math.min(minY, movedMinY) - 1.0e-7);
        int lastY = (int) Math.floor(Math.max(maxY, movedMaxY) + 1.0e-7);
        int firstZ = (int) Math.floor(Math.min(minZ, movedMinZ) - 1.0e-7);
        int lastZ = (int) Math.floor(Math.max(maxZ, movedMaxZ) + 1.0e-7);
        double result = movement;
        for (int y = firstY; y <= lastY; y++) {
            for (int z = firstZ; z <= lastZ; z++) {
                for (int x = firstX; x <= lastX; x++) {
                    int state = blockStateAtInternal(x, y, z);
                    int boxes = state < 0 ? 0 : BlockStatePhysics.collisionBoxCount(state);
                    for (int box = 0; box < boxes; box++) {
                        double boxMinX = x + BlockStatePhysics.collisionCoordinate(state, box, 0);
                        double boxMinY = y + BlockStatePhysics.collisionCoordinate(state, box, 1);
                        double boxMinZ = z + BlockStatePhysics.collisionCoordinate(state, box, 2);
                        double boxMaxX = x + BlockStatePhysics.collisionCoordinate(state, box, 3);
                        double boxMaxY = y + BlockStatePhysics.collisionCoordinate(state, box, 4);
                        double boxMaxZ = z + BlockStatePhysics.collisionCoordinate(state, box, 5);
                        if (axis == 0 && maxY > boxMinY && minY < boxMaxY
                                && maxZ > boxMinZ && minZ < boxMaxZ) {
                            if (result > 0.0 && maxX <= boxMinX) {
                                result = Math.min(result, boxMinX - maxX);
                            } else if (result < 0.0 && minX >= boxMaxX) {
                                result = Math.max(result, boxMaxX - minX);
                            }
                        } else if (axis == 1 && maxX > boxMinX && minX < boxMaxX
                                && maxZ > boxMinZ && minZ < boxMaxZ) {
                            if (result > 0.0 && maxY <= boxMinY) {
                                result = Math.min(result, boxMinY - maxY);
                            } else if (result < 0.0 && minY >= boxMaxY) {
                                result = Math.max(result, boxMaxY - minY);
                            }
                        } else if (axis == 2 && maxX > boxMinX && minX < boxMaxX
                                && maxY > boxMinY && minY < boxMaxY) {
                            if (result > 0.0 && maxZ <= boxMinZ) {
                                result = Math.min(result, boxMinZ - maxZ);
                            } else if (result < 0.0 && minZ >= boxMaxZ) {
                                result = Math.max(result, boxMaxZ - minZ);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Allocation-free outline lookup for buttons, levers and other non-solid targets. */
    public synchronized boolean isTargetableBlock(int worldX, int worldY, int worldZ) {
        int relativeY = worldY - minY;
        if (relativeY < 0 || relativeY >= Math.min(height, 512)) return false;
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0) return false;
        SurfaceChunk chunk = chunks[index];
        if (chunk.hasFullBlockStates) {
            int state = chunk.fullBlockState(
                    worldX & 15, relativeY, worldZ & 15);
            int liquids = BlockStatePhysics.WATER | BlockStatePhysics.LAVA;
            // LocalPlayer.pick uses ClipContext.Fluid.NONE for ordinary mouse
            // targeting: water/lava affect movement but do not get an outline.
            return !BlockStatePhysics.isAir(state)
                    && (RailState.isRail(state) || FenceState.isFence(state) || (BlockStatePhysics.flags(state) & liquids) == 0);
        }
        return chunk.isTargetableCell(worldX & 15, relativeY, worldZ & 15);
    }

    public synchronized int breakTicksAt(int worldX, int worldY, int worldZ) {
        int relativeY = worldY - minY;
        if (relativeY < 0 || relativeY >= Math.min(height, 512)) return 0;
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0) return 0;
        return chunks[index].breakTicksAt(worldX & 15, relativeY, worldZ & 15);
    }

    /** Returns movement traits for all vanilla states intersecting an AABB. */
    public synchronized int physicsFlagsInBox(double minX, double minY, double minZ,
                                               double maxX, double maxY, double maxZ) {
        int firstX = (int) Math.floor(minX + 1.0e-5);
        int lastX = (int) Math.floor(maxX - 1.0e-5);
        int firstY = (int) Math.floor(minY + 1.0e-5);
        int lastY = (int) Math.floor(maxY - 1.0e-5);
        int firstZ = (int) Math.floor(minZ + 1.0e-5);
        int lastZ = (int) Math.floor(maxZ - 1.0e-5);
        int result = 0;
        for (int y = firstY; y <= lastY; y++) {
            int relativeY = y - this.minY;
            if (relativeY < 0 || relativeY >= Math.min(height, 512)) continue;
            for (int z = firstZ; z <= lastZ; z++) {
                for (int x = firstX; x <= lastX; x++) {
                    int index = find(x >> 4, z >> 4);
                    if (index < 0) continue;
                    int state = chunks[index].fullBlockState(x & 15, relativeY, z & 15);
                    if (state < 0) continue;
                    int flags = BlockStatePhysics.flags(state);
                    result |= flags & ~(BlockStatePhysics.WATER | BlockStatePhysics.LAVA);
                    int fluid = flags & (BlockStatePhysics.WATER | BlockStatePhysics.LAVA);
                    if (fluid != 0 && minY < y + fluidHeightAtInternal(x, y, z,
                            state, fluid)) result |= fluid;
                }
            }
        }
        return result;
    }

    /** Movement traits of the block directly supporting the player's feet. */
    public synchronized int blockStateAt(int worldX, int worldY, int worldZ) {
        return blockStateAtInternal(worldX, worldY, worldZ);
    }

    /** Returns the server biome cell without retaining another chunk snapshot. */
    public synchronized int biomeAt(int worldX, int worldY, int worldZ) {
        int relativeY = worldY - minY;
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0 || relativeY < 0 || relativeY >= Math.min(height, 512)) return -1;
        return chunks[index].biomeState((worldX & 15) >> 2,
                relativeY >> 2, (worldZ & 15) >> 2);
    }

    public synchronized int lightAt(boolean sky, int x, int y, int z) {
        int index = find(x >> 4, z >> 4);
        return index < 0 ? (sky ? 15 : 0) : chunks[index].light(sky, x & 15, y - minY, z & 15);
    }

    public synchronized int rainSurface(int x, int z) {
        int index = find(x >> 4, z >> 4);
        if (index < 0 || !chunks[index].hasFullBlockStates()) return Integer.MIN_VALUE;
        SurfaceChunk chunk = chunks[index];
        for (int y = Math.min(height, 512) - 1; y >= 0; y--) {
            int state = chunk.fullBlockState(x & 15, y, z & 15);
            if (BlockStatePhysics.blocksMovement(state)
                    || (BlockStatePhysics.flags(state) & (BlockStatePhysics.WATER | BlockStatePhysics.LAVA)) != 0)
                return minY + y + 1;
        }
        return minY;
    }

    public synchronized int physicsFlagsAt(int worldX, int worldY, int worldZ) {
        int state = blockStateAtInternal(worldX, worldY, worldZ);
        return state < 0 ? 0 : BlockStatePhysics.flags(state);
    }

    public synchronized float frictionAt(int worldX, int worldY, int worldZ) {
        int state = blockStateAtInternal(worldX, worldY, worldZ);
        return state < 0 ? 0.6f : BlockStatePhysics.friction(state);
    }

    public synchronized float speedFactorAt(int worldX, int worldY, int worldZ) {
        int state = blockStateAtInternal(worldX, worldY, worldZ);
        return state < 0 ? 1.0f : BlockStatePhysics.speedFactor(state);
    }

    public synchronized float jumpFactorAt(int worldX, int worldY, int worldZ) {
        int state = blockStateAtInternal(worldX, worldY, worldZ);
        return state < 0 ? 1.0f : BlockStatePhysics.jumpFactor(state);
    }

    /** Greatest vanilla fluid depth above the player's feet. */
    public synchronized double fluidDepthInBox(int fluid,
                                               double minX, double minY, double minZ,
                                               double maxX, double maxY, double maxZ) {
        int firstX = (int) Math.floor(minX + 1.0e-5);
        int lastX = (int) Math.floor(maxX - 1.0e-5);
        int firstY = (int) Math.floor(minY + 1.0e-5);
        int lastY = (int) Math.floor(maxY - 1.0e-5);
        int firstZ = (int) Math.floor(minZ + 1.0e-5);
        int lastZ = (int) Math.floor(maxZ - 1.0e-5);
        double result = 0.0;
        for (int y = firstY; y <= lastY; y++) {
            for (int z = firstZ; z <= lastZ; z++) {
                for (int x = firstX; x <= lastX; x++) {
                    int state = blockStateAtInternal(x, y, z);
                    if (state < 0 || (BlockStatePhysics.flags(state) & fluid) == 0) continue;
                    double surface = y + fluidHeightAtInternal(x, y, z, state, fluid);
                    if (surface >= minY) result = Math.max(result, surface - minY);
                }
            }
        }
        return result;
    }

    public synchronized double waterHeightAt(int x,int y,int z) {
        int state=blockStateAt(x,y,z);
        return state<0 || (BlockStatePhysics.flags(state)&BlockStatePhysics.WATER)==0 ? 0
                : fluidHeightAtInternal(x,y,z,state,BlockStatePhysics.WATER);
    }

    private double fluidHeightAtInternal(int x, int y, int z, int state, int fluid) {
        int above = blockStateAtInternal(x, y + 1, z);
        if (above >= 0 && (BlockStatePhysics.flags(above) & fluid) != 0) return 1.0;
        return BlockStatePhysics.fluidAmount(state) / 9.0;
    }

    private int blockStateAtInternal(int worldX, int worldY, int worldZ) {
        int relativeY = worldY - minY;
        if (relativeY < 0 || relativeY >= Math.min(height, 512)) return -1;
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0) return -1;
        return chunks[index].fullBlockState(worldX & 15, relativeY, worldZ & 15);
    }

    /** Applies an authoritative vanilla block-state change without any bridge packet. */
    public synchronized boolean applyVanillaBlockUpdate(int worldX, int worldY,
                                                        int worldZ, int stateId) {
        int relativeY = worldY - minY;
        if (relativeY < 0 || relativeY >= Math.min(height, 512)) return false;
        recordBlockUpdate(worldX, worldY, worldZ, stateId);
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0) return false;

        SurfaceChunk chunk = chunks[index];
        if (chunk.hasFullBlockStates) {
            int oldState = chunk.fullBlockState(
                    worldX & 15, relativeY, worldZ & 15);
            if (oldState == stateId) return false;
            chunk.putFullBlockState(worldX & 15, relativeY, worldZ & 15, stateId);
            if(BlockStatePhysics.fullyOccludesUnitCube(oldState)&&BlockStatePhysics.isAir(stateId)) {
                // Vanilla's client light engine immediately lights a newly exposed
                // air cell. Do not mesh the surrounding faces with the removed
                // solid's zero nibbles while waiting for the server light packet.
                int sky=lightAt(true,worldX,worldY+1,worldZ),block=0;
                if(sky<15)sky=Math.max(0,sky-1);
                for(int side=0;side<6;side++) {
                    int dx=side==4?-1:side==5?1:0,dy=side==0?-1:side==1?1:0,dz=side==2?-1:side==3?1:0;
                    sky=Math.max(sky,lightAt(true,worldX+dx,worldY+dy,worldZ+dz)-1);
                    block=Math.max(block,lightAt(false,worldX+dx,worldY+dy,worldZ+dz)-1);
                }
                chunk.setLight(true,worldX&15,relativeY,worldZ&15,sky);
                chunk.setLight(false,worldX&15,relativeY,worldZ&15,block);
            }
            // Full-state chunks are meshed directly.  The legacy 4,000-record
            // surface may stay saturated, but it can no longer hide geometry,
            // collision, targeting or the next block exposed while mining.
            chunk.revision++;
            chunk.lastTouched = ++clock;
            invalidateForBlock(worldX,worldZ);
            if (vanillaBlockUpdateLogCount < 24) {
                vanillaBlockUpdateLogCount++;
                int below = relativeY > 0
                        ? chunk.fullBlockState(worldX & 15, relativeY - 1, worldZ & 15)
                        : -1;
                System.out.println("[NH-THIN] vanilla block update=" + worldX + ","
                        + worldY + "," + worldZ + " " + oldState + "->" + stateId
                        + " below=" + below + " records=" + chunk.recordCount);
            }
            return true;
        }

        if (stateId == 0) {
            boolean changed = chunk.removeCell(
                    worldX & 15, relativeY, worldZ & 15);
            // Reveal the real neighbours retained from LevelChunkWithLight.
            exposeFullNeighbour(worldX, worldY - 1, worldZ, 1 << 1);
            exposeFullNeighbour(worldX, worldY + 1, worldZ, 1 << 0);
            exposeFullNeighbour(worldX, worldY, worldZ - 1, 1 << 3);
            exposeFullNeighbour(worldX, worldY, worldZ + 1, 1 << 2);
            exposeFullNeighbour(worldX - 1, worldY, worldZ, 1 << 5);
            exposeFullNeighbour(worldX + 1, worldY, worldZ, 1 << 4);
            if (changed) {
                chunk.lastTouched=++clock;
                invalidateForBlock(worldX,worldZ);
            }
            return changed;
        }
        return false;
    }

    public synchronized void applyVanillaChunkData(int chunkX, int chunkZ,
                                                    ByteBuffer sectionData)
            throws ProtocolException {
        clearBlockUpdates(chunkX, chunkZ);
        boolean storedCold = false;
        if (activeRadius >= 0 && ensureColdCache()) {
            try {
                storedCold = coldChunks.put(ColdChunkCache.KIND_SECTIONS,
                        chunkX, chunkZ, sectionData.asReadOnlyBuffer());
            } catch (IOException exception) {
                disableColdCache("write", exception);
            }
        }
        // Before the first teleport there is no meaningful active window. Keep
        // the exact compact payload on disk and decode it once the player
        // position is authoritative. Far chunks are likewise kept cold.
        if (activeRadius >= 0 && ((storedCold && !hasPlayerPosition)
                || (hasPlayerPosition && !withinActiveRadius(chunkX, chunkZ)))) return;
        decodeActiveChunk(chunkX, chunkZ, sectionData);
    }

    private void decodeActiveChunk(int chunkX, int chunkZ, ByteBuffer sectionData)
            throws ProtocolException {
        int index = find(chunkX, chunkZ);
        if (index < 0) index = acquireSlot(chunkX, chunkZ);
        if (index < 0) return;
        SurfaceChunk chunk = chunks[index];
        chunk.revision++;
        invalidateMeshingNeighbors(chunkX,chunkZ);
        chunk.beginFullBlockUpdate();
        long blockCounts = vanillaChunkDecoder.decodeSections(sectionData, chunk,
                Math.min(height, 512));
        int expectedNonEmpty = (int) (blockCounts >>> 32);
        int decodedNonEmpty = (int) blockCounts;
        if (expectedNonEmpty != decodedNonEmpty && decodedCountMismatchLogCount < 24) {
            decodedCountMismatchLogCount++;
            System.out.println("[NH-THIN] CHUNK DECODE MISMATCH chunk=" + chunkX
                    + "," + chunkZ + " serverNonEmpty=" + expectedNonEmpty
                    + " decodedNonEmpty=" + decodedNonEmpty);
        }
        chunk.finishFullBlockUpdate(Math.min(height, 512));
        // The optional Bukkit bridge decoded this same chunk independently
        // through ChunkSnapshot. Compare its retained surface before replacing
        // it with the complete protocol-763 representation. This makes a bad
        // palette decode visible without ever substituting or fabricating map
        // blocks in the client.
        if (chunk.recordCount > 0) {
            auditBridgeSurface(chunk, chunk.records(), chunk.recordCount);
        }
        // Full chunks use their authoritative states for meshing and collision.
        // Rebuilding the discarded legacy surface scanned the entire column a second time.
        chunk.recordCount=0;
        chunk.lastTouched = ++clock;
    }

    private void auditBridgeSurface(SurfaceChunk chunk, ByteBuffer source,
                                    int declaredCount) {
        ByteBuffer records = source.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        int available = Math.min(declaredCount,
                records.remaining() / SurfaceChunk.RECORD_BYTES);
        int checked = 0;
        int missing = 0;
        int waterMismatch = 0;
        int lavaMismatch = 0;
        for (int index = 0; index < available; index++) {
            int packed = records.getInt();
            int color = records.getInt();
            int state = chunk.fullBlockState(SurfaceChunk.localX(packed),
                    SurfaceChunk.relativeY(packed), SurfaceChunk.localZ(packed));
            checked++;
            if (state < 0 || BlockStatePhysics.isAir(state)) {
                missing++;
                continue;
            }
            int flags = BlockStatePhysics.flags(state);
            if (color == 0x3f76e4ff
                    && (flags & BlockStatePhysics.WATER) == 0) waterMismatch++;
            if (color == 0xff6b16ff
                    && (flags & BlockStatePhysics.LAVA) == 0) lavaMismatch++;
        }
        if (bridgeAuditLogCount < 64
                || missing != 0 || waterMismatch != 0 || lavaMismatch != 0) {
            bridgeAuditLogCount++;
            System.out.println("[NH-THIN] BRIDGE/FULL AUDIT chunk="
                    + chunk.chunkX + "," + chunk.chunkZ + " checked=" + checked
                    + " missing=" + missing + " waterMismatch=" + waterMismatch
                    + " lavaMismatch=" + lavaMismatch);
        }
    }

    private void recordBlockUpdate(int worldX, int worldY, int worldZ, int stateId) {
        long packed = packBlockPosition(worldX, worldY, worldZ);
        for (int index = 0; index < updateCount; index++) {
            if (updatePositions[index] != packed) continue;
            updateStates[index] = stateId;
            updateStamps[index] = ++updateClock;
            return;
        }
        int slot;
        if (updateCount < UPDATE_JOURNAL_CAPACITY) {
            slot = updateCount++;
        } else {
            slot = 0;
            for (int index = 1; index < updateCount; index++) {
                if (updateStamps[index] < updateStamps[slot]) slot = index;
            }
        }
        updatePositions[slot] = packed;
        updateStates[slot] = stateId;
        updateStamps[slot] = ++updateClock;
    }

    private void clearBlockUpdates(int chunkX, int chunkZ) {
        for (int index = updateCount - 1; index >= 0; index--) {
            long packed = updatePositions[index];
            if ((unpackBlockX(packed) >> 4) != chunkX
                    || (unpackBlockZ(packed) >> 4) != chunkZ) continue;
            int last = --updateCount;
            if (index == last) continue;
            updatePositions[index] = updatePositions[last];
            updateStates[index] = updateStates[last];
            updateStamps[index] = updateStamps[last];
        }
    }

    private void replayBlockUpdates(SurfaceChunk chunk) {
        boolean changed = false;
        for (int index = 0; index < updateCount; index++) {
            long packed = updatePositions[index];
            int worldX = unpackBlockX(packed);
            int worldZ = unpackBlockZ(packed);
            if ((worldX >> 4) != chunk.chunkX || (worldZ >> 4) != chunk.chunkZ) continue;
            int relativeY = unpackBlockY(packed) - minY;
            if (relativeY < 0 || relativeY >= Math.min(height, 512)) continue;
            chunk.putFullBlockState(worldX & 15, relativeY, worldZ & 15,
                    updateStates[index]);
            changed = true;
        }
        if (changed) {
            chunk.rebuildSurfaceFromFullBlocks(Math.min(height, 512),
                    (int) Math.floor(playerY) - minY);
            chunk.lastTouched = ++clock;
            invalidateMeshingNeighbors(chunk.chunkX,chunk.chunkZ);
        }
    }

    private static long packBlockPosition(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38
                | ((long) z & 0x3ffffffL) << 12
                | (long) y & 0xfffL;
    }

    private static int unpackBlockX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackBlockY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackBlockZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    private boolean withinActiveRadius(int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - playerChunkX),
                Math.abs(chunkZ - playerChunkZ)) <= activeRadius;
    }

    private boolean ensureColdCache() {
        if (coldChunks != null) return true;
        if (coldCacheUnavailable) return false;
        MemoryBudget.Lease lease = null;
        ColdChunkCache cache = null;
        try {
            lease = budget.reserve(MemoryCategory.WORLD, ColdChunkCache.MAX_RECORD_BYTES);
            ByteBuffer scratch = ByteBuffer.allocateDirect(ColdChunkCache.MAX_RECORD_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            cache = new ColdChunkCache();
            coldScratchLease = lease;
            coldReadScratch = scratch;
            coldChunks = cache;
            System.out.println("[NH-THIN] cold chunk file cache ready bytes="
                    + ColdChunkCache.MAX_RECORD_BYTES);
            return true;
        } catch (IOException | RuntimeException exception) {
            if (cache != null) cache.close();
            if (lease != null) lease.close();
            coldCacheUnavailable = true;
            System.out.println("[NH-THIN] cold chunk cache unavailable: " + exception);
            return false;
        }
    }

    private void promoteColdAroundPlayer() {
        if(deferredPromotions){promotionPending=true;return;}
        promoteColdBatch();
    }

    private void promoteColdBatch() {
        promotionPending=false;
        if (activeRadius < 0 || coldChunks == null || coldReadScratch == null) return;
        int promoted = 0;
        for (int ring = 0; ring <= activeRadius; ring++) {
            for (int z = playerChunkZ - ring; z <= playerChunkZ + ring; z++) {
                for (int x = playerChunkX - ring; x <= playerChunkX + ring; x++) {
                    if (Math.max(Math.abs(x - playerChunkX),
                            Math.abs(z - playerChunkZ)) != ring) continue;
                    int index = find(x, z);
                    if (index >= 0 && chunks[index].hasFullBlockStates) continue;
                    try {
                        if (!coldChunks.readInto(ColdChunkCache.KIND_SECTIONS,
                                x, z, coldReadScratch)) continue;
                        decodeActiveChunk(x, z, coldReadScratch);
                        int promotedIndex = find(x, z);
                        if (promotedIndex >= 0) {
                            replayBlockUpdates(chunks[promotedIndex]);
                            if (coldChunks.readInto(ColdChunkCache.KIND_AUXILIARY,
                                    x, z, coldReadScratch)) {
                                invalidateMeshingNeighbors(x,z);
                                chunks[promotedIndex].revision++;
                                vanillaChunkAuxDecoder.decodeInitial(coldReadScratch,
                                        chunks[promotedIndex], Math.min(height, 512));
                            }
                            if (coldChunks.readInto(ColdChunkCache.KIND_BIOMES,
                                    x, z, coldReadScratch)) {
                                invalidateMeshingNeighbors(x,z);
                                chunks[promotedIndex].revision++;
                                vanillaChunkDecoder.decodeBiomeUpdate(coldReadScratch,
                                        chunks[promotedIndex], Math.min(height, 512));
                            }
                        }
                        promoted++;
                        // One column per worker iteration: never decode an entire newly
                        // entered ring while holding the world's collision/read lock.
                        if(deferredPromotions){promotionPending=true;return;}
                    } catch (IOException | ProtocolException exception) {
                        if (coldCacheFailureLogCount++ < 8) {
                            System.out.println("[NH-THIN] cold chunk promotion failed chunk="
                                    + x + "," + z + " error=" + exception);
                        }
                    }
                }
            }
        }
        if (promoted > 0 && (coldPromotionLogCount++ < 24
                || (coldPromotionLogCount & 63) == 0)) {
            System.out.println("[NH-THIN] cold chunks promoted=" + promoted
                    + " center=" + playerChunkX + "," + playerChunkZ
                    + " cached=" + coldChunks.size()
                    + " missing=" + missingFullChunksAroundPlayer(activeRadius));
        }
    }

    private void disableColdCache(String operation, IOException exception) {
        if (coldChunks != null) coldChunks.close();
        coldChunks = null;
        coldReadScratch = null;
        if (coldScratchLease != null) coldScratchLease.close();
        coldScratchLease = null;
        coldCacheUnavailable = true;
        System.out.println("[NH-THIN] cold chunk cache " + operation
                + " failed: " + exception);
    }

    public synchronized void applyVanillaChunkAuxData(int chunkX, int chunkZ,
                                                       ByteBuffer auxiliaryData)
            throws ProtocolException {
        if (activeRadius >= 0 && ensureColdCache()) {
            try {
                coldChunks.put(ColdChunkCache.KIND_AUXILIARY, chunkX, chunkZ,
                        auxiliaryData.asReadOnlyBuffer());
            } catch (IOException exception) {
                disableColdCache("auxiliary write", exception);
            }
        }
        if (activeRadius >= 0
                && (!hasPlayerPosition || !withinActiveRadius(chunkX, chunkZ))) return;
        int index = find(chunkX, chunkZ);
        if (index < 0 || !chunks[index].hasFullBlockStates) return;
        invalidateMeshingNeighbors(chunkX,chunkZ);
        chunks[index].revision++;
        vanillaChunkAuxDecoder.decodeInitial(auxiliaryData, chunks[index],
                Math.min(height, 512));
        chunks[index].lastTouched = ++clock;
    }

    public synchronized void applyVanillaBiomeData(int chunkX, int chunkZ,
                                                    ByteBuffer biomeData)
            throws ProtocolException {
        if (activeRadius >= 0 && ensureColdCache()) {
            try {
                coldChunks.put(ColdChunkCache.KIND_BIOMES, chunkX, chunkZ,
                        biomeData.asReadOnlyBuffer());
            } catch (IOException exception) {
                disableColdCache("biome write", exception);
            }
        }
        int index = find(chunkX, chunkZ);
        if (index < 0 || !chunks[index].hasFullBlockStates) return;
        invalidateMeshingNeighbors(chunkX,chunkZ);
        chunks[index].revision++;
        vanillaChunkDecoder.decodeBiomeUpdate(biomeData, chunks[index],
                Math.min(height, 512));
        chunks[index].lastTouched = ++clock;
    }

    public synchronized void applyVanillaLightData(int chunkX, int chunkZ,
                                                    ByteBuffer lightData)
            throws ProtocolException {
        int index = find(chunkX, chunkZ);
        if (index < 0 || !chunks[index].hasFullBlockStates) return;
        invalidateMeshingNeighbors(chunkX,chunkZ);
        chunks[index].revision++;
        vanillaChunkAuxDecoder.decodeLightUpdate(lightData, chunks[index],
                Math.min(height, 512));
        chunks[index].lastTouched = ++clock;
    }

    public synchronized void applyVanillaBlockEntity(int worldX, int worldY,
                                                      int worldZ, int typeId) {
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0 || !chunks[index].hasFullBlockStates) return;
        chunks[index].putBlockEntity(worldX & 15, worldY, worldZ & 15, typeId);
        chunks[index].revision++;
        invalidateMeshingNeighbors(worldX>>4,worldZ>>4);
        chunks[index].lastTouched = ++clock;
    }

    public synchronized void forgetVanillaChunk(int chunkX, int chunkZ) {
        int index = find(chunkX, chunkZ);
        if (index >= 0) removeAt(index);
        if (coldChunks != null) coldChunks.remove(chunkX, chunkZ);
        clearBlockUpdates(chunkX, chunkZ);
    }

    public synchronized boolean hasFullBlockData(int worldX, int worldY, int worldZ) {
        int relativeY = worldY - minY;
        if (relativeY < 0 || relativeY >= Math.min(height, 512)) return false;
        int index = find(worldX >> 4, worldZ >> 4);
        return index >= 0 && chunks[index].fullBlockState(
                worldX & 15, relativeY, worldZ & 15) >= 0;
    }

    /** Number of absent authoritative chunks in a square vanilla view. */
    public synchronized int missingFullChunksAroundPlayer(int radius) {
        int missing = 0;
        for (int z = playerChunkZ - radius; z <= playerChunkZ + radius; z++) {
            for (int x = playerChunkX - radius; x <= playerChunkX + radius; x++) {
                int index = find(x, z);
                if (index < 0 || !chunks[index].hasFullBlockStates) missing++;
            }
        }
        return missing;
    }

    public synchronized long playerChunkKey() {
        return (long) playerChunkX << 32 | playerChunkZ & 0xffff_ffffL;
    }

    private void exposeFullNeighbour(int worldX, int worldY, int worldZ,
                                     int faceMask) {
        int relativeY = worldY - minY;
        if (relativeY < 0 || relativeY >= Math.min(height, 512)) return;
        int index = find(worldX >> 4, worldZ >> 4);
        if (index < 0) return;
        SurfaceChunk neighbour = chunks[index];
        if (neighbour.exposeFullBlockFace(
                worldX & 15, relativeY, worldZ & 15, faceMask)) {
            neighbour.lastTouched = ++clock;
            invalidateForBlock(worldX,worldZ);
        }
    }

    public synchronized boolean hasCollisionData() {
        return size > 0;
    }

    /** Applies Minecraft's relative teleport flags and exposes one atomic view. */
    public synchronized void applyServerTeleport(double x, double y, double z,
                                                 float yaw, float pitch,
                                                 int relativeFlags) {
        int oldChunkX = playerChunkX;
        int oldChunkZ = playerChunkZ;
        boolean hadPlayerPosition = hasPlayerPosition;
        if ((relativeFlags & 0x01) != 0) x += playerX;
        if ((relativeFlags & 0x02) != 0) y += playerY;
        if ((relativeFlags & 0x04) != 0) z += playerZ;
        if ((relativeFlags & 0x08) != 0) yaw += playerYaw;
        if ((relativeFlags & 0x10) != 0) pitch += playerPitch;
        playerX = x;
        playerY = y;
        playerZ = z;
        playerYaw = yaw;
        playerPitch = pitch;
        playerChunkX = floorChunk(x);
        playerChunkZ = floorChunk(z);
        hasPlayerPosition = true;
        playerRevision++;
        teleportRevision++;
        if (!hadPlayerPosition || playerChunkX != oldChunkX
                || playerChunkZ != oldChunkZ) promoteColdAroundPlayer();
    }

    /** Executes without copying records while preventing a network update race. */
    public synchronized void visitChunks(ChunkVisitor visitor) {
        ensureOpen();
        for (int index = 0; index < size; index++) visitor.visit(index, chunks[index], minY);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        size = 0;
        if (coldChunks != null) coldChunks.close();
        coldChunks = null;
        coldReadScratch = null;
        if (coldScratchLease != null) coldScratchLease.close();
        coldScratchLease = null;
        // Slots and their reservations remain paired until process exit because
        // Java 8 offers no supported deterministic DirectByteBuffer free.
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("World store is closed");
    }

    private static int floorChunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    public interface ChunkVisitor {
        void visit(int slot, SurfaceChunk chunk, int minY);
    }

}
