package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;

/**
 * Keeps Minecraft's vanilla sound subsystem dormant on the low-memory client.
 *
 * GeckoView/MCEF audio is owned by Android and does not pass through these
 * Minecraft classes. The patched prepare method avoids parsing sounds.json and
 * indexing every OGG resource, while the patched SoundEngine reload method
 * avoids opening an OpenAL device. SoundManager and SoundEngine objects remain
 * present so ordinary game/mod calls retain their expected object graph.
 */
final class MinimalSound1201Transformer {
    static final String MANAGER_ENTRY =
            "net/minecraft/client/sounds/SoundManager.class";
    static final String ENGINE_ENTRY =
            "net/minecraft/client/sounds/SoundEngine.class";

    private static final String MANAGER =
            "net/minecraft/client/sounds/SoundManager";
    private static final String PREPARATIONS = MANAGER + "$Preparations";
    private static final String ENGINE =
            "net/minecraft/client/sounds/SoundEngine";
    private static final String MANAGER_SHA256 =
            "7035cb8892e3ee4ecd60e980d185e685ecf298f13104b8203fe559a6933b9506";
    private static final String ENGINE_SHA256 =
            "e68c9c7b55d6c642b60472597c8043d896271973ffadc26e5fbe57c7a0a2a552";
    private static final String PREPARE_DESC =
            "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                    + "Lnet/minecraft/util/profiling/ProfilerFiller;)L"
                    + PREPARATIONS + ";";

    private MinimalSound1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (MANAGER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, MANAGER_SHA256);
            return transformManager(input);
        }
        if (ENGINE_ENTRY.equals(entryName)) {
            requireHash(entryName, input, ENGINE_SHA256);
            return transformEngine(input);
        }
        return null;
    }

    private static byte[] transformManager(byte[] input) throws IOException {
        ClassNode node = read(input);
        if (!MANAGER.equals(node.name)) {
            throw new IOException("Unexpected Minecraft 1.20.1 SoundManager shape");
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("m_5944_".equals(method.name) && PREPARE_DESC.equals(method.desc)) {
                clear(method);
                method.instructions.add(new TypeInsnNode(Opcodes.NEW, PREPARATIONS));
                method.instructions.add(new InsnNode(Opcodes.DUP));
                method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, PREPARATIONS, "<init>", "()V", false));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                finish(method);
                changed++;
            }
        }
        if (changed != 1) {
            throw new IOException("Expected one SoundManager prepare method, got " + changed);
        }
        return write(node);
    }

    private static byte[] transformEngine(byte[] input) throws IOException {
        ClassNode node = read(input);
        if (!ENGINE.equals(node.name)) {
            throw new IOException("Unexpected Minecraft 1.20.1 SoundEngine shape");
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("m_120239_".equals(method.name) && "()V".equals(method.desc)) {
                clear(method);
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                finish(method);
                changed++;
            }
        }
        if (changed != 1) {
            throw new IOException("Expected one SoundEngine reload method, got " + changed);
        }
        return write(node);
    }

    static boolean isManagerPatched(byte[] input) {
        ClassNode node = read(input);
        for (MethodNode method : node.methods) {
            if ("m_5944_".equals(method.name) && PREPARE_DESC.equals(method.desc)) {
                int newPreparations = 0;
                int resourceManagerCalls = 0;
                for (org.objectweb.asm.tree.AbstractInsnNode instruction =
                     method.instructions.getFirst(); instruction != null;
                     instruction = instruction.getNext()) {
                    if (instruction instanceof TypeInsnNode
                            && instruction.getOpcode() == Opcodes.NEW
                            && PREPARATIONS.equals(((TypeInsnNode) instruction).desc)) {
                        newPreparations++;
                    } else if (instruction instanceof MethodInsnNode
                            && ((MethodInsnNode) instruction).owner.startsWith(
                            "net/minecraft/server/packs/resources/")) {
                        resourceManagerCalls++;
                    }
                }
                return newPreparations == 1 && resourceManagerCalls == 0;
            }
        }
        return false;
    }

    static boolean isEnginePatched(byte[] input) {
        ClassNode node = read(input);
        for (MethodNode method : node.methods) {
            if ("m_120239_".equals(method.name) && "()V".equals(method.desc)) {
                return method.instructions.size() == 1
                        && method.instructions.getFirst().getOpcode() == Opcodes.RETURN;
            }
        }
        return false;
    }

    private static void clear(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    private static void finish(MethodNode method) {
        method.maxStack = 0;
        int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            slots += argument.getSize();
        }
        method.maxLocals = slots;
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
