package net.kdt.pojavlaunch.gecko;

import java.io.*;

/** Sorted ASCII domains in one byte array: no per-domain Java objects or linear scans. */
final class MinePadDomainList {
    private final byte[] data;
    private final int[] offsets;
    private MinePadDomainList(byte[] data, int[] offsets) {this.data=data;this.offsets=offsets;}
    static MinePadDomainList read(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1) {
            if(out.size()+count>16*1024*1024)throw new IOException("Domain list too large");
            out.write(buffer,0,count);
        }
        byte[] data=out.toByteArray();
        if(data.length==0||data[data.length-1]!='\n')throw new IOException("Incomplete domain list");
        int lines=0;for(byte b:data)if(b=='\n')lines++;
        int[] offsets=new int[lines+1];int index=0,start=0;
        for(int i=0;i<data.length;i++) {
            int c=data[i]&255;
            if(c=='\n') {
                if(i==start||i-start>253)throw new IOException("Invalid domain length");
                offsets[index++]=start;start=i+1;
            } else if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='.'||c=='-')) {
                throw new IOException("Non-domain entry");
            }
        }
        offsets[lines]=data.length;
        MinePadDomainList list=new MinePadDomainList(data,offsets);
        for(int i=1;i<lines;i++)if(compare(data,offsets[i-1],offsets[i]-1,offsets[i],offsets[i+1]-1)>=0)
            throw new IOException("Domain list is not sorted and unique");
        return list;
    }
    private static int compare(byte[] data,int a,int endA,int b,int endB) {
        while(a<endA&&b<endB){int d=data[a++]-data[b++];if(d!=0)return d;}
        return (endA-a)-(endB-b);
    }
    int size(){return offsets.length-1;}
    int bytes(){return data.length+offsets.length*4;}
    boolean blocks(String host) {
        for(int start=0;start<host.length();) {
            int low=0,high=size()-1;
            while(low<=high) {
                int middle=(low+high)>>>1,offset=offsets[middle],end=offsets[middle+1]-1,i=start;
                int diff=0;
                while(offset<end&&i<host.length()) {diff=(data[offset++]&255)-host.charAt(i++);if(diff!=0)break;}
                if(diff==0)diff=(end-offset)-(host.length()-i);
                if(diff==0)return true;
                if(diff<0)low=middle+1;else high=middle-1;
            }
            int dot=host.indexOf('.',start);if(dot<0)break;start=dot+1;
        }
        return false;
    }
}
