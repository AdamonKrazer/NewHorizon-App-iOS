package com.newhorizon.thinbridge;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Moves expensive block interpretation to the server and streams only surfaces. */
public final class ThinBridgePlugin extends JavaPlugin
        implements PluginMessageListener, Listener {
    private static final String HELLO_CHANNEL = "newhorizon:hello_v1";
    private static final String WORLD_CHANNEL = "newhorizon:world_v1";
    private static final String INVENTORY_CHANNEL = "newhorizon:inventory_v1";
    private static final int HELLO_MAGIC = 0x4e485431; // NHT1
    private static final int WORLD_MAGIC = 0x4e485731; // NHW1
    private static final int INVENTORY_MAGIC = 0x4e484931; // NHI1
    private static final int VERSION = 1;
    private static final int INVENTORY_VERSION = 2;
    private static final int COLLIDABLE_FLAG = 1 << 23;
    private static final int RESET = 1;
    private static final int CHUNK_SURFACE = 2;
    private static final int REMOVE_CHUNK = 3;
    private static final int PLAYER_POSITION = 4;
    private static final int INVENTORY_OPEN = 1;
    private static final int INVENTORY_CLICK = 2;
    private static final int INVENTORY_CLOSE = 3;
    private static final int INVENTORY_SELECT = 4;
    private static final int INVENTORY_CREATIVE_CURSOR = 5;
    private static final int INVENTORY_SLOT_COUNT = 46;
    private static final int OUTSIDE_SLOT = 0xff;
    private static final int BUTTON_LEFT = 0;
    private static final int BUTTON_RIGHT = 1;
    private static final int CURSOR_CLEAR = 0;
    private static final int CURSOR_SHRINK = 1;
    private static final int CURSOR_GROW = 2;
    private static final int RADIUS = 3;
    private static final int MAX_RECORDS = 4_000;
    private static final int MAX_MESSAGE_BYTES = 32_700;

    private final Map<UUID, ClientState> clients = new HashMap<>();
    private ThreadPoolExecutor mesher;
    private int tick;

    @Override
    public void onEnable() {
        getServer().getMessenger().registerIncomingPluginChannel(this,
                HELLO_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this,
                INVENTORY_CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, WORLD_CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, INVENTORY_CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        mesher = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), runnable -> {
                    Thread thread = new Thread(runnable, "NH-Thin-Surface-Mesher");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
        getServer().getScheduler().runTaskTimer(this, this::updateClients, 1L, 2L);
        getLogger().info("Bounded thin-client bridge ready");
    }

    @Override
    public void onDisable() {
        for (ClientState state : clients.values()) {
            Player player = Bukkit.getPlayer(state.playerId);
            if (player != null && player.isOnline()) returnCursor(player, state);
        }
        clients.clear();
        if (mesher != null) mesher.shutdownNow();
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (HELLO_CHANNEL.equals(channel)) {
            if (message.length != 5) return;
            ByteBuffer input = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN);
            if (input.getInt() != HELLO_MAGIC || (input.get() & 0xff) != VERSION) return;
            ClientState state = new ClientState(player.getUniqueId());
            clients.put(player.getUniqueId(), state);
            resetWorld(player, state);
            sendInventory(player, state);
            getLogger().info("Thin client connected: " + player.getName());
            return;
        }
        if (INVENTORY_CHANNEL.equals(channel)) {
            handleInventoryMessage(player, message);
        }
    }

    private void handleInventoryMessage(Player player, byte[] message) {
        if (message.length < 6) return;
        ByteBuffer input = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN);
        if (input.getInt() != INVENTORY_MAGIC
                || (input.get() & 0xff) != INVENTORY_VERSION) return;
        int type = input.get() & 0xff;
        ClientState state = clients.get(player.getUniqueId());
        if (state == null) return;
        if (type == INVENTORY_OPEN) {
            state.inventoryOpen = true;
            sendInventory(player, state);
        } else if (type == INVENTORY_CLICK && input.remaining() == 3) {
            int slot = input.get() & 0xff;
            int button = input.get() & 0xff;
            boolean shift = (input.get() & 1) != 0;
            if ((slot < INVENTORY_SLOT_COUNT || slot == OUTSIDE_SLOT)
                    && (button == BUTTON_LEFT || button == BUTTON_RIGHT)) {
                clickInventory(player, state, slot, button, shift);
            }
        } else if (type == INVENTORY_CLOSE) {
            state.inventoryOpen = false;
            returnCursor(player, state);
        } else if (type == INVENTORY_SELECT && input.remaining() == 1) {
            int slot = input.get() & 0xff;
            if (slot <= 8) {
                player.getInventory().setHeldItemSlot(slot);
                sendInventory(player, state);
            }
        } else if (type == INVENTORY_CREATIVE_CURSOR && input.remaining() == 1) {
            int action = input.get() & 0xff;
            mutateCreativeCursor(player, state, action);
        }
    }

    /** Matches CreativeModeInventoryScreen's local carried-stack rules. */
    private void mutateCreativeCursor(Player player, ClientState state, int action) {
        ItemStack cursor = state.inventoryCursor;
        if (isEmpty(cursor)) {
            state.inventoryCursor = null;
        } else if (action == CURSOR_CLEAR) {
            state.inventoryCursor = null;
        } else if (action == CURSOR_SHRINK) {
            cursor.setAmount(cursor.getAmount() - 1);
            if (cursor.getAmount() <= 0) state.inventoryCursor = null;
        } else if (action == CURSOR_GROW) {
            cursor.setAmount(Math.min(cursor.getMaxStackSize(), cursor.getAmount() + 1));
        } else {
            return;
        }
        sendInventory(player, state);
    }

    private void clickInventory(Player player, ClientState state, int slot,
                                int button, boolean shift) {
        if (slot == OUTSIDE_SLOT) {
            if (!isEmpty(state.inventoryCursor)) {
                ItemStack dropped;
                if (button == BUTTON_RIGHT && state.inventoryCursor.getAmount() > 1) {
                    dropped = state.inventoryCursor.clone();
                    dropped.setAmount(1);
                    state.inventoryCursor.setAmount(state.inventoryCursor.getAmount() - 1);
                } else {
                    dropped = state.inventoryCursor;
                    state.inventoryCursor = null;
                }
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }
            sendInventory(player, state);
            return;
        }

        if (shift) {
            shiftClick(player, state, slot);
        } else if (slot == 45) {
            takeCraftingResult(player, state, button);
        } else {
            ItemStack target = getSlot(player, slot);
            if (button == BUTTON_RIGHT) {
                rightClick(player, state, slot, target);
            } else {
                leftClick(player, state, slot, target);
            }
        }
        player.updateInventory();
        sendInventory(player, state);
    }

    private void leftClick(Player player, ClientState state, int slot, ItemStack target) {
        ItemStack carried = state.inventoryCursor;
        if (isEmpty(carried)) {
            state.inventoryCursor = cloneOrNull(target);
            setSlot(player, slot, null);
            return;
        }
        if (isEmpty(target)) {
            if (accepts(slot, carried)) {
                setSlot(player, slot, carried);
                state.inventoryCursor = null;
            }
            return;
        }
        if (target.isSimilar(carried) && target.getAmount() < target.getMaxStackSize()) {
            int moved = Math.min(carried.getAmount(),
                    target.getMaxStackSize() - target.getAmount());
            target.setAmount(target.getAmount() + moved);
            carried.setAmount(carried.getAmount() - moved);
            setSlot(player, slot, target);
            if (carried.getAmount() <= 0) state.inventoryCursor = null;
            return;
        }
        if (accepts(slot, carried)) {
            setSlot(player, slot, carried);
            state.inventoryCursor = target;
        }
    }

    private void rightClick(Player player, ClientState state, int slot, ItemStack target) {
        ItemStack carried = state.inventoryCursor;
        if (isEmpty(carried)) {
            if (isEmpty(target)) return;
            int taken = (target.getAmount() + 1) / 2;
            ItemStack cursor = target.clone();
            cursor.setAmount(taken);
            target.setAmount(target.getAmount() - taken);
            state.inventoryCursor = cursor;
            setSlot(player, slot, target.getAmount() <= 0 ? null : target);
            return;
        }
        if (isEmpty(target)) {
            if (!accepts(slot, carried)) return;
            ItemStack single = carried.clone();
            single.setAmount(1);
            setSlot(player, slot, single);
            carried.setAmount(carried.getAmount() - 1);
            if (carried.getAmount() <= 0) state.inventoryCursor = null;
            return;
        }
        if (target.isSimilar(carried) && target.getAmount() < target.getMaxStackSize()) {
            target.setAmount(target.getAmount() + 1);
            carried.setAmount(carried.getAmount() - 1);
            setSlot(player, slot, target);
            if (carried.getAmount() <= 0) state.inventoryCursor = null;
            return;
        }
        if (accepts(slot, carried)) {
            setSlot(player, slot, carried);
            state.inventoryCursor = target;
        }
    }

    private void shiftClick(Player player, ClientState state, int slot) {
        if (slot == 45) {
            takeCraftingResult(player, state, BUTTON_LEFT);
            if (!isEmpty(state.inventoryCursor)) {
                ItemStack remainder = mergeInto(player.getInventory(), state.inventoryCursor,
                        0, 35);
                state.inventoryCursor = remainder;
            }
            return;
        }
        ItemStack stack = getSlot(player, slot);
        if (isEmpty(stack)) return;
        if (slot >= 36 && slot <= 40) {
            ItemStack remainder = mergeInto(player.getInventory(), stack, 0, 35);
            setSlot(player, slot, remainder);
            return;
        }
        if (slot >= 0 && slot <= 35) {
            int armour = preferredArmourSlot(stack);
            if (armour >= 0 && isEmpty(getSlot(player, armour))) {
                setSlot(player, armour, stack);
                setSlot(player, slot, null);
                return;
            }
            int first = slot < 9 ? 9 : 0;
            int last = slot < 9 ? 35 : 8;
            ItemStack remainder = mergeInto(player.getInventory(), stack, first, last);
            setSlot(player, slot, remainder);
            return;
        }
        if (slot >= 41 && slot <= 44) {
            ItemStack remainder = mergeInto(player.getInventory(), stack, 0, 35);
            setSlot(player, slot, remainder);
        }
    }

    private void takeCraftingResult(Player player, ClientState state, int button) {
        CraftingInventory crafting = crafting(player);
        if (crafting == null) return;
        ItemStack result = crafting.getResult();
        if (isEmpty(result)) return;
        ItemStack carried = state.inventoryCursor;
        int resultAmount = result.getAmount();
        if (!isEmpty(carried)) {
            if (!carried.isSimilar(result)) return;
            int capacity = carried.getMaxStackSize() - carried.getAmount();
            if (capacity < resultAmount) return;
        }
        if (isEmpty(carried)) {
            state.inventoryCursor = result.clone();
        } else {
            carried.setAmount(carried.getAmount() + resultAmount);
        }
        ItemStack[] matrix = crafting.getMatrix();
        for (int index = 0; index < matrix.length; index++) {
            ItemStack ingredient = matrix[index];
            if (isEmpty(ingredient)) continue;
            ingredient.setAmount(ingredient.getAmount() - 1);
            if (ingredient.getAmount() <= 0) matrix[index] = null;
        }
        crafting.setMatrix(matrix);
    }

    private static ItemStack mergeInto(PlayerInventory inventory, ItemStack source,
                                       int first, int last) {
        if (isEmpty(source)) return null;
        ItemStack remainder = source.clone();
        for (int slot = first; slot <= last && remainder.getAmount() > 0; slot++) {
            ItemStack target = inventory.getItem(slot);
            if (isEmpty(target) || !target.isSimilar(remainder)
                    || target.getAmount() >= target.getMaxStackSize()) continue;
            int moved = Math.min(remainder.getAmount(),
                    target.getMaxStackSize() - target.getAmount());
            target.setAmount(target.getAmount() + moved);
            remainder.setAmount(remainder.getAmount() - moved);
            inventory.setItem(slot, target);
        }
        for (int slot = first; slot <= last && remainder.getAmount() > 0; slot++) {
            if (!isEmpty(inventory.getItem(slot))) continue;
            inventory.setItem(slot, remainder);
            remainder = null;
            break;
        }
        return remainder == null || remainder.getAmount() <= 0 ? null : remainder;
    }

    private static ItemStack getSlot(Player player, int slot) {
        if (slot >= 0 && slot <= 40) return player.getInventory().getItem(slot);
        CraftingInventory crafting = crafting(player);
        if (crafting == null) return null;
        if (slot == 45) return crafting.getResult();
        int matrixSlot = slot - 41;
        ItemStack[] matrix = crafting.getMatrix();
        return matrixSlot >= 0 && matrixSlot < matrix.length ? matrix[matrixSlot] : null;
    }

    private static void setSlot(Player player, int slot, ItemStack stack) {
        if (slot >= 0 && slot <= 40) {
            player.getInventory().setItem(slot, cloneOrNull(stack));
            return;
        }
        if (slot < 41 || slot > 44) return;
        CraftingInventory crafting = crafting(player);
        if (crafting == null) return;
        ItemStack[] matrix = crafting.getMatrix();
        int matrixSlot = slot - 41;
        if (matrixSlot < matrix.length) {
            matrix[matrixSlot] = cloneOrNull(stack);
            crafting.setMatrix(matrix);
        }
    }

    private static CraftingInventory crafting(Player player) {
        if (player == null || player.getOpenInventory() == null) return null;
        if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory) {
            return (CraftingInventory) player.getOpenInventory().getTopInventory();
        }
        return null;
    }

    private static boolean accepts(int slot, ItemStack stack) {
        if (isEmpty(stack)) return true;
        if (slot < 36 || slot > 39) return slot != 45;
        String name = stack.getType().name();
        if (slot == 36) return name.endsWith("_BOOTS");
        if (slot == 37) return name.endsWith("_LEGGINGS");
        if (slot == 38) return name.endsWith("_CHESTPLATE") || "ELYTRA".equals(name);
        return name.endsWith("_HELMET") || name.endsWith("_HEAD")
                || name.endsWith("_SKULL") || "CARVED_PUMPKIN".equals(name);
    }

    private static int preferredArmourSlot(ItemStack stack) {
        if (isEmpty(stack)) return -1;
        String name = stack.getType().name();
        if (name.endsWith("_BOOTS")) return 36;
        if (name.endsWith("_LEGGINGS")) return 37;
        if (name.endsWith("_CHESTPLATE") || "ELYTRA".equals(name)) return 38;
        if (name.endsWith("_HELMET") || name.endsWith("_HEAD")
                || name.endsWith("_SKULL") || "CARVED_PUMPKIN".equals(name)) return 39;
        return -1;
    }

    private static boolean isEmpty(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return true;
        return stack.getType().isAir() && "minecraft:air".equals(itemId(stack));
    }

    private static ItemStack cloneOrNull(ItemStack stack) {
        return isEmpty(stack) ? null : stack.clone();
    }

    private static void writeStack(ByteBuffer output, ItemStack stack) {
        if (isEmpty(stack)) {
            output.put((byte) 0);
            writeString(output, "");
            writeString(output, "");
            return;
        }
        output.put((byte) Math.min(255, stack.getAmount()));
        writeString(output, itemId(stack));
        String displayName = "";
        if (stack.hasItemMeta() && stack.getItemMeta() != null
                && stack.getItemMeta().hasDisplayName()) {
            displayName = stack.getItemMeta().getDisplayName();
        }
        writeString(output, displayName);
    }

    private static void returnCursor(Player player, ClientState state) {
        if (isEmpty(state.inventoryCursor)) {
            state.inventoryCursor = null;
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory()
                .addItem(state.inventoryCursor);
        for (ItemStack stack : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
        state.inventoryCursor = null;
        player.updateInventory();
    }

    private void sendInventory(Player player, ClientState state) {
        PlayerInventory inventory = player.getInventory();
        ByteBuffer output = packet(16_384);
        output.putInt(INVENTORY_MAGIC).put((byte) INVENTORY_VERSION)
                .put((byte) INVENTORY_OPEN)
                .put((byte) inventory.getHeldItemSlot());
        writeStack(output, state.inventoryCursor);
        output.put((byte) INVENTORY_SLOT_COUNT);
        for (int slot = 0; slot < INVENTORY_SLOT_COUNT; slot++) {
            output.put((byte) slot);
            writeStack(output, getSlot(player, slot));
        }
        byte[] data = new byte[output.position()];
        output.flip();
        output.get(data);
        player.sendPluginMessage(this, INVENTORY_CHANNEL, data);
        state.inventoryFingerprint = inventoryFingerprint(player, state);
    }

    private static int inventoryFingerprint(Player player, ClientState state) {
        int hash = player.getInventory().getHeldItemSlot();
        for (int slot = 0; slot < INVENTORY_SLOT_COUNT; slot++) {
            ItemStack stack = getSlot(player, slot);
            if (isEmpty(stack)) continue;
            hash = 31 * hash + slot;
            hash = 31 * hash + stack.getAmount();
            hash = 31 * hash + itemId(stack).hashCode();
        }
        if (!isEmpty(state.inventoryCursor)) {
            hash = 31 * hash + state.inventoryCursor.getAmount();
            hash = 31 * hash + itemId(state.inventoryCursor).hashCode();
        }
        return hash;
    }

    /**
     * Bukkit's Material bridge can report AIR for Forge-only items. Resolve the
     * actual Minecraft/Forge registry key from CraftItemStack when necessary.
     */
    private static String itemId(ItemStack stack) {
        if (stack == null) return "minecraft:air";
        try {
            String bukkitKey = stack.getType().getKey().toString();
            if (!"minecraft:air".equals(bukkitKey)) return bukkitKey;
        } catch (Throwable ignored) {
        }
        try {
            Object handle = null;
            try {
                Method getHandle = stack.getClass().getMethod("getHandle");
                handle = getHandle.invoke(stack);
            } catch (ReflectiveOperationException ignored) {
                String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
                Class<?> craftStack = Class.forName(craftPackage + ".inventory.CraftItemStack");
                Method asNmsCopy = craftStack.getMethod("asNMSCopy", ItemStack.class);
                handle = asNmsCopy.invoke(null, stack);
            }
            if (handle != null) {
                Object item = handle.getClass().getMethod("getItem").invoke(handle);
                Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                Field itemField = registries.getField("ITEM");
                Object registry = itemField.get(null);
                for (Method method : registry.getClass().getMethods()) {
                    if (!"getKey".equals(method.getName()) || method.getParameterCount() != 1) continue;
                    try {
                        Object key = method.invoke(registry, item);
                        if (key != null) return key.toString();
                    } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return stack.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ClientState state = clients.remove(event.getPlayer().getUniqueId());
        if (state != null) returnCursor(event.getPlayer(), state);
    }

    private void updateClients() {
        tick++;
        Iterator<ClientState> iterator = clients.values().iterator();
        while (iterator.hasNext()) {
            ClientState state = iterator.next();
            Player player = Bukkit.getPlayer(state.playerId);
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            String dimension = player.getWorld().getKey().toString();
            if (!dimension.equals(state.dimension)) resetWorld(player, state);
            observeMovement(player, state);
            sendPosition(player);
            if (tick % 5 == 0) {
                int fingerprint = inventoryFingerprint(player, state);
                if (fingerprint != state.inventoryFingerprint) {
                    sendInventory(player, state);
                }
            }
            refreshDesiredChunks(player, state);
            if (!state.meshInFlight && !state.pending.isEmpty()
                    && mesher.getQueue().remainingCapacity() > 0) {
                requestMesh(player, state, state.pending.removeFirst());
            }
            observeVanillaPose(player, state);
        }
    }

    private void observeVanillaPose(Player player, ClientState state) {
        boolean sneaking = player.isSneaking();
        boolean sprinting = player.isSprinting();
        boolean flying = player.isFlying();
        if (!state.poseObserved) {
            state.poseObserved = true;
            state.observedSneaking = sneaking;
            state.observedSprinting = sprinting;
            state.observedFlying = flying;
            return;
        }
        if (sneaking == state.observedSneaking
                && sprinting == state.observedSprinting
                && flying == state.observedFlying) return;
        state.observedSneaking = sneaking;
        state.observedSprinting = sprinting;
        state.observedFlying = flying;
        getLogger().info("Vanilla state received " + player.getName()
                + " sneak=" + sneaking + " sprint=" + sprinting
                + " flying=" + flying + " allowFlight=" + player.getAllowFlight());
    }

    private void resetWorld(Player player, ClientState state) {
        state.dimension = player.getWorld().getKey().toString();
        state.pending.clear();
        state.sent.clear();
        state.meshInFlight = false;
        ByteBuffer packet = packet(256);
        header(packet, RESET);
        writeString(packet, state.dimension);
        packet.putInt(player.getWorld().getMinHeight());
        writeVarInt(packet, player.getWorld().getMaxHeight()
                - player.getWorld().getMinHeight());
        send(player, packet);
        state.chunkX = Integer.MIN_VALUE;
        state.chunkZ = Integer.MIN_VALUE;
    }

    private void sendPosition(Player player) {
        Location location = player.getLocation();
        ByteBuffer packet = packet(64);
        header(packet, PLAYER_POSITION);
        packet.putDouble(location.getX()).putDouble(location.getY())
                .putDouble(location.getZ()).putFloat(location.getYaw())
                .putFloat(location.getPitch());
        send(player, packet);
    }

    private void observeMovement(Player player, ClientState state) {
        Location location = player.getLocation();
        if (!state.observedPosition) {
            state.observedX = location.getX();
            state.observedY = location.getY();
            state.observedZ = location.getZ();
            state.observedPosition = true;
            return;
        }
        double dx = location.getX() - state.observedX;
        double dy = location.getY() - state.observedY;
        double dz = location.getZ() - state.observedZ;
        if (dx * dx + dy * dy + dz * dz < 0.0025) return;
        state.observedX = location.getX();
        state.observedY = location.getY();
        state.observedZ = location.getZ();
        if (state.movementSamples++ < 8) {
            getLogger().info("Movement accepted " + player.getName() + " pos="
                    + String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                    state.observedX, state.observedY, state.observedZ));
        }
    }

    private void refreshDesiredChunks(Player player, ClientState state) {
        int centerX = player.getLocation().getBlockX() >> 4;
        int centerZ = player.getLocation().getBlockZ() >> 4;
        if (centerX == state.chunkX && centerZ == state.chunkZ) return;
        state.chunkX = centerX;
        state.chunkZ = centerZ;

        Iterator<Map.Entry<Long, Integer>> sent = state.sent.entrySet().iterator();
        while (sent.hasNext()) {
            long key = sent.next().getKey();
            int x = unpackX(key);
            int z = unpackZ(key);
            if (Math.abs(x - centerX) > RADIUS || Math.abs(z - centerZ) > RADIUS) {
                sendRemove(player, x, z);
                sent.remove();
            }
        }

        state.pending.clear();
        for (int distance = 0; distance <= RADIUS; distance++) {
            for (int dz = -distance; dz <= distance; dz++) {
                for (int dx = -distance; dx <= distance; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
                    long key = pack(centerX + dx, centerZ + dz);
                    if (!state.sent.containsKey(key)) state.pending.addLast(key);
                }
            }
        }
    }

    private void requestMesh(Player player, ClientState state, long key) {
        int chunkX = unpackX(key);
        int chunkZ = unpackZ(key);
        World world = player.getWorld();
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            state.pending.addLast(key);
            return;
        }
        ChunkSnapshot snapshot = world.getChunkAt(chunkX, chunkZ)
                .getChunkSnapshot(false, false, false);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        state.meshInFlight = true;
        int revision = ++state.revision;
        try {
            mesher.execute(() -> {
                byte[] data = meshChunk(snapshot, minY, maxY, revision);
                Bukkit.getScheduler().runTask(this, () -> {
                    state.meshInFlight = false;
                    Player current = Bukkit.getPlayer(state.playerId);
                    if (current == null || !current.isOnline()
                            || !current.getWorld().getKey().toString().equals(state.dimension)) {
                        return;
                    }
                    int dx = Math.abs(chunkX - state.chunkX);
                    int dz = Math.abs(chunkZ - state.chunkZ);
                    if (dx <= RADIUS && dz <= RADIUS) {
                        current.sendPluginMessage(this, WORLD_CHANNEL, data);
                        state.sent.put(key, revision);
                    }
                });
            });
        } catch (RuntimeException rejected) {
            state.meshInFlight = false;
            state.pending.addFirst(key);
        }
    }

    private static byte[] meshChunk(ChunkSnapshot snapshot, int minY,
                                    int maxY, int revision) {
        ByteBuffer output = packet(MAX_MESSAGE_BYTES);
        header(output, CHUNK_SURFACE);
        output.putInt(snapshot.getX()).putInt(snapshot.getZ()).putInt(revision);
        int countOffset = output.position();
        // Reserve a fixed-width three-byte VarInt because count <= 4000.
        output.position(countOffset + 3);
        int count = 0;
        outer:
        for (int y = maxY - 1; y >= minY; y--) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Material material = snapshot.getBlockType(x, y, z);
                    if (material.isAir()) continue;
                    int faces = faceMask(snapshot, x, y, z, minY, maxY);
                    if (faces == 0) continue;
                    int relativeY = y - minY;
                    int packed = x | (z << 4) | (relativeY << 8) | (faces << 17);
                    if (material.isSolid()) packed |= COLLIDABLE_FLAG;
                    output.putInt(packed).putInt(color(material));
                    count++;
                    if (count >= MAX_RECORDS) break outer;
                }
            }
        }
        int end = output.position();
        output.position(countOffset);
        output.put((byte) ((count & 0x7f) | 0x80));
        output.put((byte) (((count >>> 7) & 0x7f) | 0x80));
        output.put((byte) (count >>> 14));
        output.position(end);
        byte[] result = new byte[end];
        output.flip();
        output.get(result);
        return result;
    }


    private static int faceMask(ChunkSnapshot snapshot, int x, int y, int z,
                                int minY, int maxY) {
        int mask = 0;
        if (exposed(snapshot, x, y - 1, z, minY, maxY)) mask |= 1;
        if (exposed(snapshot, x, y + 1, z, minY, maxY)) mask |= 2;
        if (exposed(snapshot, x, y, z - 1, minY, maxY)) mask |= 4;
        if (exposed(snapshot, x, y, z + 1, minY, maxY)) mask |= 8;
        if (exposed(snapshot, x - 1, y, z, minY, maxY)) mask |= 16;
        if (exposed(snapshot, x + 1, y, z, minY, maxY)) mask |= 32;
        return mask;
    }

    private static boolean exposed(ChunkSnapshot snapshot, int x, int y, int z,
                                   int minY, int maxY) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || y < minY || y >= maxY) return true;
        Material neighbor = snapshot.getBlockType(x, y, z);
        return neighbor.isAir() || !neighbor.isOccluding();
    }

    private static int color(Material material) {
        String name = material.name();
        if (name.contains("WATER")) return 0x3f76e4ff;
        if (name.contains("LAVA")) return 0xff6b16ff;
        if (name.contains("GRASS") || name.contains("LEAVES")
                || name.contains("MOSS")) return 0x659d48ff;
        if (name.contains("DIRT") || name.contains("MUD")) return 0x805a3aff;
        if (name.contains("SAND") || name.contains("SANDSTONE")) return 0xd8c783ff;
        if (name.contains("SNOW") || name.contains("ICE")) return 0xddebf2ff;
        if (name.contains("LOG") || name.contains("WOOD")
                || name.contains("PLANK")) return 0x9b7446ff;
        if (name.contains("STONE") || name.contains("ORE")
                || name.contains("DEEPSLATE")) return 0x777a7cff;
        if (name.contains("GLASS")) return 0xa9dce7ff;
        int hash = name.hashCode();
        int red = 72 + ((hash >>> 16) & 0x7f);
        int green = 72 + ((hash >>> 8) & 0x7f);
        int blue = 72 + (hash & 0x7f);
        return (red << 24) | (green << 16) | (blue << 8) | 0xff;
    }

    private void sendRemove(Player player, int x, int z) {
        ByteBuffer packet = packet(16);
        header(packet, REMOVE_CHUNK);
        packet.putInt(x).putInt(z);
        send(player, packet);
    }

    private void send(Player player, ByteBuffer packet) {
        byte[] data = new byte[packet.position()];
        packet.flip();
        packet.get(data);
        player.sendPluginMessage(this, WORLD_CHANNEL, data);
    }

    private static ByteBuffer packet(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
    }

    private static void header(ByteBuffer output, int type) {
        output.putInt(WORLD_MAGIC).put((byte) VERSION).put((byte) type);
    }

    private static void writeString(ByteBuffer output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.put(bytes);
    }

    private static void writeVarInt(ByteBuffer output, int value) {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            if (value != 0) current |= 0x80;
            output.put((byte) current);
        } while (value != 0);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    private static final class ClientState {
        final UUID playerId;
        final ArrayDeque<Long> pending = new ArrayDeque<>(64);
        final LinkedHashMap<Long, Integer> sent = new LinkedHashMap<>();
        String dimension = "";
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        int revision;
        boolean meshInFlight;
        ItemStack inventoryCursor;
        boolean inventoryOpen;
        int inventoryFingerprint = Integer.MIN_VALUE;
        boolean observedPosition;
        double observedX;
        double observedY;
        double observedZ;
        int movementSamples;
        boolean poseObserved;
        boolean observedSneaking;
        boolean observedSprinting;
        boolean observedFlying;

        ClientState(UUID playerId) {
            this.playerId = playerId;
        }
    }
}
