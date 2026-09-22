package com.newhorizon.thinclient.display;

/**
 * OpenJDK-to-Reynard bridge on iOS (same command and GPU texture ABI as Android).
 *
 * <p>This replaces the MCEF Java facade in the thin runtime while preserving
 * the validated Android Gecko compositor and native GPU frame path.</p>
 */
public final class GeckoNativeBridge implements BrowserPort {
    private static final String PREFIX = "com.cinemamod.mcef.MCEFBrowser.";

    static {
        System.loadLibrary("gecko_mcef_jni");
    }

    @Override
    public void create(int browserId, String url, boolean transparent) {
        command("__nh_display_create", Integer.toString(browserId), url,
                Boolean.toString(transparent));
        resize(browserId, 16, 16);
    }

    @Override
    public void resize(int browserId, int width, int height) {
        command(PREFIX + "RemoteWasResized", Integer.toString(browserId),
                Integer.toString(width), Integer.toString(height));
    }

    @Override
    public void navigate(int browserId, String url) {
        command(PREFIX + "RemoteLoadURL", Integer.toString(browserId), url);
    }

    @Override
    public void setVisible(int browserId, boolean visible) {
        command(PREFIX + "RemoteSetVisible", Integer.toString(browserId),
                Boolean.toString(visible));
    }

    @Override
    public void setFocused(int browserId, boolean focused) {
        command(PREFIX + "RemoteSetFocus", Integer.toString(browserId),
                Boolean.toString(focused));
    }

    @Override
    public void destroy(int browserId) {
        command(PREFIX + "RemoteDestroyBrowser", Integer.toString(browserId));
    }

    public static void mouse(int browserId, int type, int x, int y, int clickCount,
                             int button, int modifiers) {
        command(PREFIX + "RemoteSendMouseEvent", Integer.toString(browserId),
                Integer.toString(type), Integer.toString(x), Integer.toString(y),
                Integer.toString(clickCount), Integer.toString(button),
                Integer.toString(modifiers));
    }

    public static void wheel(int browserId, int x, int y, int amount, int modifiers) {
        command(PREFIX + "RemoteSendMouseWheelEvent", Integer.toString(browserId),
                Integer.toString(x), Integer.toString(y), Integer.toString(amount),
                Integer.toString(modifiers));
    }

    public static void displayLaser(boolean enabled){command("__nh_display_laser",enabled?"1":"0");}
    public static void cancelDisplayInput(int browser){command("__nh_display_cancel",Integer.toString(browser));}

    public static long gpuFrameState(int browserId, int[] metadata) {
        return nativeGpuFrameInfo(browserId, metadata);
    }

    public static boolean drawGpuFrame(int browserId, float[] vertices) {
        return nativeGpuDraw(browserId, vertices);
    }

    /** Registers three render-thread-owned textures for Gecko's GPU producer. */
    public static boolean registerGpuConsumerTextures(int browserId, int width,
                                                      int height, int[] textureIds) {
        return nativeGpuRegisterConsumerTextures(browserId, width, height, textureIds);
    }

    /** Returns the newest completed shared texture, or 0 while no frame is ready. */
    public static int gpuSharedTextureId(int browserId, int width, int height) {
        return nativeGpuSharedTexture(browserId, width, height);
    }

    public static String[] pollEvent() {
        String[] event = nativePollEvent();
        if (event != null && event.length >= 3 && "nh-pad-location".equals(event[1])) {
            event[2] = MinePadResources.canonical(event[2]);
        }
        return event;
    }

    public static void toggleDiagnosticHud() {
        command("__nh_toggle_diagnostic_hud");
    }
    public static void updateHud(String state,String debug,boolean tab,boolean hidden,boolean screenOpen,int camera){
        command("__nh_game_hud",state,debug,tab?"1":"0",hidden?"1":"0",screenOpen?"1":"0",Integer.toString(camera));
    }
    public static void playerSkin(String id,String pixels,boolean slim){command("__nh_player_skin",id,pixels,slim?"1":"0");}

    public static void showInventory(String encodedSlots) {
        command("__nh_inventory_show", encodedSlots == null ? "" : encodedSlots);
    }

    public static void hideInventory() {
        command("__nh_inventory_hide");
    }

    public static void updateHotbar(String encodedSlots) {
        command("__nh_hotbar_update", encodedSlots == null ? "" : encodedSlots);
    }

    public static void hideHotbar() {
        command("__nh_hotbar_hide");
    }

    public static void updateEffects(String encoded) { command("__nh_effects",encoded); }
    public static void updateCombat(String encoded,boolean inventory) {command("__nh_combat",encoded,inventory?"1":"0");}

    public static void actionBar(String json){command("__nh_actionbar",json);}
    public static void chatMessage(String json){command("__nh_chat_message",json);}
    public static void showChat(String initial){command("__nh_chat_show",initial);}
    public static void hideChat(){command("__nh_chat_hide");}
    public static void resetChat(){command("__nh_chat_reset");}
    public static void createMinePad(int id,String url){command("__nh_pad_create",Integer.toString(id),MinePadResources.resolve(url));}
    public static void navigateMinePad(int id,String url){command("__nh_pad_navigate",Integer.toString(id),MinePadResources.resolve(url));}
    public static void showMinePad(int id,String url,int index,String[] urls,boolean editing){command("__nh_pad_show",Integer.toString(id),url,Integer.toString(index),String.join("\n",urls),editing?"1":"0");}
    public static void hideMinePad(){command("__nh_pad_hide");}
    public static void updateRiding(int mode,String hint,boolean inventory){command("__nh_riding",Integer.toString(mode),hint,inventory?"1":"0");}

    private static String command(String op, String... args) {
        return nativeCommand(op, args);
    }

    private static native String nativeCommand(String op, String[] args);

    private static native String[] nativePollEvent();

    private static native long nativeGpuFrameInfo(int browserId, int[] metadata);

    private static native boolean nativeGpuDraw(int browserId, float[] vertices);

    private static native boolean nativeGpuRegisterConsumerTextures(
            int browserId, int width, int height, int[] textureIds);

    private static native int nativeGpuSharedTexture(int browserId, int width, int height);
}
