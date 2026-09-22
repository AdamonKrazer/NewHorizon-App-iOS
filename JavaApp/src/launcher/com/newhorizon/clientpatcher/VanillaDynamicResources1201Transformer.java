package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.InsnNode;

import java.io.IOException;

/**
 * Converts vanilla's eager model graph into a first-use model service while
 * preserving the real ModelBakery, BlockModelShaper and ItemModelShaper.
 */
final class VanillaDynamicResources1201Transformer {
    static final String BAKERY_ENTRY =
            "net/minecraft/client/resources/model/ModelBakery.class";
    static final String MANAGER_ENTRY =
            "net/minecraft/client/resources/model/ModelManager.class";
    static final String BLOCK_SHAPER_ENTRY =
            "net/minecraft/client/renderer/block/BlockModelShaper.class";
    static final String ITEM_SHAPER_ENTRY =
            "net/minecraft/client/renderer/ItemModelShaper.class";

    private static final String BAKERY_HASH =
            "4be0d2eff8a85facb65e338472593c9aa02a6f37a201810b9f66c8b163af82ec";
    private static final String MANAGER_HASH =
            "901710409ce2f53ea6019b762114929743ba18853c38e8bb1476c521c8d97a79";
    private static final String BLOCK_SHAPER_HASH =
            "d7cc3d4652e1d1273763c9ab6e8636a871950f7af735e65c5d8dd57b4c9a8851";
    private static final String ITEM_SHAPER_HASH =
            "eb159b4315ff505d6576731469a413a7e7603f884eaa615f60e2a8b1d6a28348";

    private static final String BAKERY =
            "net/minecraft/client/resources/model/ModelBakery";
    private static final String MANAGER =
            "net/minecraft/client/resources/model/ModelManager";
    private static final String RELOAD_STATE = MANAGER + "$ReloadState";
    private static final String MODEL_RESOURCE_LOCATION =
            "net/minecraft/client/resources/model/ModelResourceLocation";
    private static final String BAKED_MODEL =
            "net/minecraft/client/resources/model/BakedModel";
    private static final String BLOCK_SHAPER =
            "net/minecraft/client/renderer/block/BlockModelShaper";
    private static final String ITEM_SHAPER =
            "net/minecraft/client/renderer/ItemModelShaper";
    private static final String BLOCK_STATE =
            "net/minecraft/world/level/block/state/BlockState";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String DEFAULTED_REGISTRY = "net/minecraft/core/DefaultedRegistry";
    private static final String POLICY =
            "com/newhorizon/thinclient/minecraft/VanillaDynamicResources";

    private VanillaDynamicResources1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (BAKERY_ENTRY.equals(entryName)) {
            requireHash(entryName, input, BAKERY_HASH);
            return transformBakery(input);
        }
        if (MANAGER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, MANAGER_HASH);
            return transformManager(input);
        }
        if (BLOCK_SHAPER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, BLOCK_SHAPER_HASH);
            return transformBlockShaper(input);
        }
        if (ITEM_SHAPER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, ITEM_SHAPER_HASH);
            return transformItemShaper(input);
        }
        return null;
    }

    private static byte[] transformBakery(byte[] input) throws IOException {
        ClassNode node = read(input);
        for (org.objectweb.asm.tree.FieldNode field : node.fields) {
            if ("f_119212_".equals(field.name) || "f_119213_".equals(field.name) || "f_119215_".equals(field.name))
                field.access &= ~Opcodes.ACC_FINAL;
        }
        int blockHooks = 0;
        int itemHooks = 0;
        int textureHooks = 0;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name)
                    && ("(Lnet/minecraft/client/color/block/BlockColors;"
                    + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                    + "Ljava/util/Map;Ljava/util/Map;)V").equals(method.desc)) {
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null;) {
                    AbstractInsnNode next = instruction.getNext();
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (DEFAULTED_REGISTRY.equals(call.owner)
                                && "iterator".equals(call.name)
                                && "()Ljava/util/Iterator;".equals(call.desc)) {
                            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    POLICY, "blockIterator",
                                    "(Ljava/lang/Object;)Ljava/util/Iterator;", false));
                            blockHooks++;
                        } else if (DEFAULTED_REGISTRY.equals(call.owner)
                                && "m_6566_".equals(call.name)
                                && "()Ljava/util/Set;".equals(call.desc)) {
                            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    POLICY, "itemIds",
                                    "(Ljava/lang/Object;)Ljava/util/Set;", false));
                            itemHooks++;
                        }
                    }
                    instruction = next;
                }
            } else if ("m_245909_".equals(method.name)
                    && "(Ljava/util/function/BiFunction;)V".equals(method.desc)) {
                InsnList capture = new InsnList();
                capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
                capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
                capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY,
                        "captureTextureGetter", "(L" + BAKERY
                        + ";Ljava/lang/Object;)V", false));
                method.instructions.insert(capture);
                textureHooks++;
            }
        }
        if (blockHooks != 1 || itemHooks != 1 || textureHooks != 1) {
            throw new IOException("Dynamic bakery hooks mismatch block=" + blockHooks
                    + " item=" + itemHooks + " texture=" + textureHooks);
        }
        return write(node);
    }

    private static byte[] transformManager(byte[] input) throws IOException {
        ClassNode node = read(input);
        int blockHooks = 0;
        int activateHooks = 0;
        int modelHooks = 0;
        int lazySourceHooks = 0;
        for (MethodNode method : node.methods) {
            if (("m_246704_".equals(method.name) || "m_246899_".equals(method.name))
                    && "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;".equals(method.desc)) {
                String owner = "com/newhorizon/thinclient/minecraft/LazyModelSources";
                InsnList hook = new InsnList();
                LabelNode original = new LabelNode();
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "enabled", "()Z", false));
                hook.add(new JumpInsnNode(Opcodes.IFEQ, original));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner,
                        "m_246704_".equals(method.name) ? "models" : "states", method.desc, false));
                hook.add(new InsnNode(Opcodes.ARETURN));
                hook.add(original);
                hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(hook);
                lazySourceHooks++;
            } else if ("m_245476_".equals(method.name)
                    && ("(Lnet/minecraft/util/profiling/ProfilerFiller;Ljava/util/Map;"
                    + "L" + BAKERY + ";)L" + RELOAD_STATE + ";").equals(method.desc)) {
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null;) {
                    AbstractInsnNode next = instruction.getNext();
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (DEFAULTED_REGISTRY.equals(call.owner)
                                && "iterator".equals(call.name)
                                && "()Ljava/util/Iterator;".equals(call.desc)) {
                            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    POLICY, "blockIterator",
                                    "(Ljava/lang/Object;)Ljava/util/Iterator;", false));
                            blockHooks++;
                        }
                    }
                    instruction = next;
                }
            } else if ("m_247616_".equals(method.name)
                    && ("(L" + RELOAD_STATE
                    + ";Lnet/minecraft/util/profiling/ProfilerFiller;)V").equals(method.desc)) {
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null; instruction = instruction.getNext()) {
                    if (instruction.getOpcode() != Opcodes.RETURN) continue;
                    InsnList activate = new InsnList();
                    activate.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    activate.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    activate.add(new FieldInsnNode(Opcodes.GETFIELD, RELOAD_STATE,
                            "f_244394_", "L" + BAKERY + ";"));
                    activate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY,
                            "activate", "(L" + MANAGER + ";L" + BAKERY + ";)V", false));
                    method.instructions.insertBefore(instruction, activate);
                    activateHooks++;
                }
            } else if ("m_119422_".equals(method.name)
                    && ("(L" + MODEL_RESOURCE_LOCATION + ";)L"
                    + BAKED_MODEL + ";").equals(method.desc)) {
                replaceBody(method, POLICY, "getModel",
                        "(L" + MANAGER + ";L" + MODEL_RESOURCE_LOCATION
                                + ";)L" + BAKED_MODEL + ";", 1, Opcodes.ARETURN);
                modelHooks++;
            }
        }
        if (blockHooks != 1 || activateHooks != 1 || modelHooks != 1 || lazySourceHooks != 2) {
            throw new IOException("Dynamic manager hooks mismatch block=" + blockHooks
                    + " activate=" + activateHooks + " model=" + modelHooks + " sources=" + lazySourceHooks);
        }
        return write(node);
    }

    private static byte[] transformBlockShaper(byte[] input) throws IOException {
        ClassNode node = read(input);
        int hooks = 0;
        for (MethodNode method : node.methods) {
            if ("m_110893_".equals(method.name)
                    && ("(L" + BLOCK_STATE + ";)L" + BAKED_MODEL + ";")
                    .equals(method.desc)) {
                replaceBody(method, POLICY, "getBlockModel",
                        "(L" + BLOCK_SHAPER + ";L" + BLOCK_STATE
                                + ";)L" + BAKED_MODEL + ";", 1, Opcodes.ARETURN);
                hooks++;
            }
        }
        if (hooks != 1) throw new IOException("Expected one block shaper hook, got " + hooks);
        return write(node);
    }

    private static byte[] transformItemShaper(byte[] input) throws IOException {
        ClassNode node = read(input);
        int modelHooks = 0;
        int rebuildHooks = 0;
        for (MethodNode method : node.methods) {
            if ("m_109394_".equals(method.name)
                    && ("(L" + ITEM + ";)L" + BAKED_MODEL + ";").equals(method.desc)) {
                replaceBody(method, POLICY, "getItemModel",
                        "(L" + ITEM_SHAPER + ";L" + ITEM
                                + ";)L" + BAKED_MODEL + ";", 1, Opcodes.ARETURN);
                modelHooks++;
            } else if ("m_109403_".equals(method.name) && "()V".equals(method.desc)) {
                replaceBody(method, POLICY, "clearItemCache",
                        "(L" + ITEM_SHAPER + ";)V", 0, Opcodes.RETURN);
                rebuildHooks++;
            }
        }
        if (modelHooks != 1 || rebuildHooks != 1) {
            throw new IOException("Dynamic item shaper hooks mismatch model="
                    + modelHooks + " rebuild=" + rebuildHooks);
        }
        return write(node);
    }

    private static void replaceBody(MethodNode method, String owner, String name,
                                    String descriptor, int argumentCount,
                                    int returnOpcode) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int index = 0; index < argumentCount; index++) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, index + 1));
        }
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                owner, name, descriptor, false));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(returnOpcode));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void requireHash(String entryName, byte[] input, String expected)
            throws IOException {
        String actual = NhClientPatcher.sha256(input);
        if (!expected.equals(actual)) {
            throw new IOException("Class hash mismatch for " + entryName
                    + ": expected " + expected + ", got " + actual);
        }
    }
}
