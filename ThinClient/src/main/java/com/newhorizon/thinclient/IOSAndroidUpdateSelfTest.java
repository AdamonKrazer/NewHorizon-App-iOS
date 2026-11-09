package com.newhorizon.thinclient;

import com.newhorizon.thinclient.display.TabletLinkUrl;
import com.newhorizon.thinclient.display.TabletSocialState;
import com.newhorizon.thinclient.memory.MemoryBudget;
import com.newhorizon.thinclient.memory.MemoryCategory;
import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;

/** Integration boundaries introduced by the November Android import. */
final class IOSAndroidUpdateSelfTest {
    static void run() throws Exception {
        check(TabletLinkUrl.validate("https://example.org/a?q=1").equals("https://example.org/a?q=1"), "ordinary message link");
        for (String link : new String[]{"javascript:alert(1)","file:///private/test","https://user@example.org/","https://example.org/\n","mod://webdisplays/main.html"}) {
            try { TabletLinkUrl.validate(link); throw new AssertionError("Unsafe message link: "+link); }
            catch (IllegalArgumentException expected) { }
        }
        ByteBuffer request = TabletSocialState.request("send","00112233-4455-6677-8899-aabbccddeeff","Olá 👋");
        check(VarInts.read(request)==0 && BinaryCodec.readString(request,48).equals("send"), "social request discriminator/action");
        check(BinaryCodec.readString(request,108).equals("00112233-4455-6677-8899-aabbccddeeff")
                && BinaryCodec.readString(request,6144).equals("Olá 👋") && !request.hasRemaining(), "social request UTF-8");
        TabletSocialState state = new TabletSocialState();
        for (int i=0;i<40;i++) {
            ByteBuffer packet = ByteBuffer.allocate(64); VarInts.write(packet,1);
            BinaryCodec.writeString(packet,"{\"sequence\":"+i+"}",128); packet.flip(); state.receive(packet);
        }
        check(state.poll().equals("{\"sequence\":8}"), "bounded social queue drops oldest");
        for(int i=9;i<40;i++) check(state.poll()!=null,"remaining social event");
        check(state.poll()==null,"social queue drained");

        MemoryBudget budget = MemoryBudget.lowRamDefaults();
        try (ForgeLogin login = new ForgeLogin(budget)) {
            ByteBuffer body = ByteBuffer.allocate(256);
            body.put((byte)1); VarInts.write(body,0); VarInts.write(body,1);
            BinaryCodec.writeString(body,TabletSocialState.CHANNEL,256); BinaryCodec.writeString(body,"1",256);
            VarInts.write(body,0); VarInts.write(body,0); body.flip();
            check(login.receive("fml:loginwrapper",wrap(body))==2 && login.tabletSocial,"FML3 social negotiation");
            login.verifyComplete();
            try { login.receive("fml:loginwrapper",wrap(body)); throw new AssertionError("Duplicate Forge mod list accepted"); }
            catch (ProtocolException expected) { }
        }
        try (ForgeItemRegistry items = new ForgeItemRegistry(budget)) {
            ByteBuffer registry = ByteBuffer.allocate(256); VarInts.write(registry,2);
            BinaryCodec.writeString(registry,"webdisplays:minepad",256); VarInts.write(registry,5000);
            BinaryCodec.writeString(registry,"minecraft:stone",256); VarInts.write(registry,1); registry.flip();
            items.read(registry);
            check(items.material(5000).equals("webdisplays:minepad") && items.material(1).equals("minecraft:stone")
                    && items.material(7).isEmpty(),"server-owned Forge item IDs");
        }
        check(budget.used(MemoryCategory.NETWORK)==0,"Forge registry releases budget");
        System.out.println("iOS Android update tests passed: social framing/queue, safe message links, Forge negotiation and item IDs");
    }
    private static ByteBuffer wrap(ByteBuffer body) {
        ByteBuffer packet = ByteBuffer.allocate(512); BinaryCodec.writeString(packet,"fml:handshake",256);
        VarInts.write(packet,body.remaining()); packet.put(body.duplicate()); packet.flip(); return packet;
    }
    private static void check(boolean value,String name) { if(!value) throw new AssertionError(name); }
}
