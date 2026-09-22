package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;

/** Defers vanilla entity/block-entity renderers and model layers to first use. */
final class VanillaDynamicRenderers1201Transformer {
    static final String ENTITY_RENDERERS_ENTRY =
            "net/minecraft/client/renderer/entity/EntityRenderers.class";
    static final String BLOCK_ENTITY_RENDERERS_ENTRY =
            "net/minecraft/client/renderer/blockentity/BlockEntityRenderers.class";
    static final String ENTITY_MODEL_SET_ENTRY =
            "net/minecraft/client/model/geom/EntityModelSet.class";

    private static final String ENTITY_RENDERERS_HASH =
            "e0a76d925eea3743fc9415f54ffb56f4cb2cef6500184a28a8a5d427cce2dddf";
    private static final String BLOCK_ENTITY_RENDERERS_HASH =
            "441e98d8a86d2d685f2571d7ec2ace73b8b904d6aec025aaab3df093d3d2f8e4";
    private static final String ENTITY_MODEL_SET_HASH =
            "5b66679a5ba96b215c131fec5bc9c7862a668188d3f9fd3465d513f21132a74f";
    private static final String POLICY =
            "com/newhorizon/thinclient/minecraft/VanillaDynamicRenderers";

    private VanillaDynamicRenderers1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (ENTITY_RENDERERS_ENTRY.equals(entryName)) {
            requireHash(entryName, input, ENTITY_RENDERERS_HASH);
            return transformEntityRenderers(input);
        }
        if (BLOCK_ENTITY_RENDERERS_ENTRY.equals(entryName)) {
            requireHash(entryName, input, BLOCK_ENTITY_RENDERERS_HASH);
            return transformBlockEntityRenderers(input);
        }
        if (ENTITY_MODEL_SET_ENTRY.equals(entryName)) {
            requireHash(entryName, input, ENTITY_MODEL_SET_HASH);
            return transformEntityModelSet(input);
        }
        return null;
    }

    private static byte[] transformEntityRenderers(byte[] input) throws IOException {
        ClassNode node = read(input);
        int entityHooks = 0;
        int playerHooks = 0;
        for (MethodNode method : node.methods) {
            if ("m_174049_".equals(method.name)
                    && method.desc.endsWith(")Ljava/util/Map;")) {
                replaceFactory(method, "entityRenderers");
                entityHooks++;
            } else if ("m_174051_".equals(method.name)
                    && method.desc.endsWith(")Ljava/util/Map;")) {
                replaceFactory(method, "playerRenderers");
                playerHooks++;
            }
        }
        if (entityHooks != 1 || playerHooks != 1) {
            throw new IOException("Dynamic entity renderer hooks mismatch entity="
                    + entityHooks + " player=" + playerHooks);
        }
        return write(node);
    }

    private static byte[] transformBlockEntityRenderers(byte[] input) throws IOException {
        ClassNode node = read(input);
        int hooks = 0;
        for (MethodNode method : node.methods) {
            if ("m_173598_".equals(method.name)
                    && method.desc.endsWith(")Ljava/util/Map;")) {
                replaceFactory(method, "blockEntityRenderers");
                hooks++;
            }
        }
        if (hooks != 1) {
            throw new IOException("Expected one dynamic block-entity renderer hook, got "
                    + hooks);
        }
        return write(node);
    }

    private static byte[] transformEntityModelSet(byte[] input) throws IOException {
        ClassNode node = read(input);
        int hooks = 0;
        for (MethodNode method : node.methods) {
            if ("m_6213_".equals(method.name)
                    && "(Lnet/minecraft/server/packs/resources/ResourceManager;)V"
                    .equals(method.desc)) {
                clear(method);
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        POLICY, "layerDefinitions", "()Ljava/util/Map;", false));
                method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/model/geom/EntityModelSet",
                        "f_171099_", "Ljava/util/Map;"));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                hooks++;
            }
        }
        if (hooks != 1) {
            throw new IOException("Expected one dynamic entity-model-set hook, got " + hooks);
        }
        return write(node);
    }

    private static void replaceFactory(MethodNode method, String policyMethod) {
        clear(method);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                POLICY, policyMethod, "(Ljava/lang/Object;)Ljava/util/Map;", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
    }

    private static void clear(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
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
