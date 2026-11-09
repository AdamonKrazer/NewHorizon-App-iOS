package com.newhorizon.thinclient.protocol;

import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Connection-local packed item names. No Forge runtime, textures or registry object graph. */
public final class ForgeItemRegistry implements AutoCloseable {
    private final MemoryBudget budget;
    private MemoryBudget.Lease lease;
    private byte[] names;
    private long[] index;
    public ForgeItemRegistry(MemoryBudget budget) { this.budget=budget; }
    public synchronized boolean ready() { return names!=null; }
    public synchronized long reservedBytes() { return lease==null?0:lease.bytes(); }

    public synchronized void read(ByteBuffer input) throws ProtocolException {
        if (ready()) throw new ProtocolException("Duplicate item registry");
        int count=ForgeLogin.count(input,8192), start=input.position();
        ByteBuffer scan=input.duplicate();
        for(int i=0;i<count;i++) {
            ForgeLogin.skipString(scan,256); int id=VarInts.read(scan);
            if(id<0||id>1048575)throw new ProtocolException("Item id outside thin limit");
        }
        int bytes=scan.position()-start;
        if(bytes>262144)throw new ProtocolException("Item registry exceeds 256 KiB");
        MemoryBudget.Lease allocation=budget.tryReserve(MemoryCategory.NETWORK,bytes+8L*count+256);
        if(allocation==null)throw new ProtocolException("No network budget for item registry");
        try {
            byte[] next=new byte[bytes]; long[] lookup=new long[count];
            input.get(next); ByteBuffer data=ByteBuffer.wrap(next);
            for(int i=0;i<count;i++) {
                int offset=data.position();
                String key=BinaryCodec.readString(data,256);
                if(!validKey(key))throw new ProtocolException("Invalid item key");
                int id=VarInts.read(data);lookup[i]=((long)id<<32)|(offset&0xffffffffL);
            }
            Arrays.sort(lookup);
            for(int i=1;i<count;i++)if((lookup[i]>>>32)==(lookup[i-1]>>>32))throw new ProtocolException("Duplicate item id");
            names=next;index=lookup;lease=allocation;allocation=null;
            System.out.println("[NH-FORGE] item registry entries="+count+" reservedBytes="+reservedBytes());
        } finally { if(allocation!=null)allocation.close(); }
    }

    public synchronized String material(int id) {
        if(index==null||id<0)return "";
        int lo=0,hi=index.length-1;
        while(lo<=hi) {
            int mid=(lo+hi)>>>1,found=(int)(index[mid]>>>32);
            if(found<id)lo=mid+1;else if(found>id)hi=mid-1;
            else {
                ByteBuffer data=ByteBuffer.wrap(names);data.position((int)index[mid]);
                try{return BinaryCodec.readString(data,256);}catch(ProtocolException impossible){throw new IllegalStateException(impossible);}
            }
        }
        return "";
    }
    @Override public synchronized void close() {
        names=null;index=null;if(lease!=null){lease.close();lease=null;}
    }
    private static boolean validKey(String key) {
        int colon=key.indexOf(':');if(colon<1||colon==key.length()-1)return false;
        for(int i=0;i<key.length();i++){
            char c=key.charAt(i);if(i==colon)continue;
            if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='_'||c=='.'||c=='-'||(i>colon&&c=='/')))return false;
        }
        return true;
    }
}
