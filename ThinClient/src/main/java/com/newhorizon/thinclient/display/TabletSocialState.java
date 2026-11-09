package com.newhorizon.thinclient.display;
import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.*;
/** Same Forge SimpleChannel frame as the PC. No browser-provided sender identity. */
public final class TabletSocialState {
 public static final String CHANNEL="newhorizon:tablet_chat";
 private final ArrayDeque<String> events=new ArrayDeque<>();private int queuedBytes;
 public static ByteBuffer request(String action,String peer,String text){
  if(!Arrays.asList("contacts","history","send","friend_request","friend_accept","friend_decline","friend_cancel","friend_remove","report").contains(action)||peer.length()>36||text.length()>2048)throw new IllegalArgumentException("Invalid tablet request");
  if(!action.equals("contacts"))UUID.fromString(peer);
  ByteBuffer out=ByteBuffer.allocate(8192);VarInts.write(out,0);BinaryCodec.writeString(out,action,48);BinaryCodec.writeString(out,peer,108);BinaryCodec.writeString(out,text,6144);out.flip();return out;
 }
 public synchronized void receive(ByteBuffer packet)throws ProtocolException {
  ByteBuffer in=packet.duplicate();if(VarInts.read(in)!=1)throw new ProtocolException("Tablet response discriminator");
  String json=BinaryCodec.readString(in,393216);if(json.length()>131072||in.hasRemaining())throw new ProtocolException("Tablet response bounds");
  while(!events.isEmpty()&&(events.size()>=32||queuedBytes+json.length()>524288))queuedBytes-=events.removeFirst().length();
  events.addLast(json);queuedBytes+=json.length();
 }
 public synchronized String poll(){String next=events.pollFirst();if(next!=null)queuedBytes-=next.length();return next;}
}
