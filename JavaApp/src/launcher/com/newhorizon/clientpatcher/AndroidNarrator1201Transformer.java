package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;

final class AndroidNarrator1201Transformer {
    static final String ENTRY = "net/minecraft/client/GameNarrator.class";

    private static final String CLASS_NAME = "net/minecraft/client/GameNarrator";
    private static final String CLASS_SHA256 =
            "310a0510fcdfcfcb6b8855a930d23822eb4565fb6c5633321bb51751e6f44b6f";
    private static final String NARRATOR = "com/mojang/text2speech/Narrator";
    private static final String NARRATOR_DESC = "L" + NARRATOR + ";";
    private static final String CONSTRUCTOR_DESC = "(Lnet/minecraft/client/Minecraft;)V";

    private AndroidNarrator1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (!ENTRY.equals(entryName)) {
            return null;
        }
        requireHash(input);

        ClassNode node = read(input);
        if (!CLASS_NAME.equals(node.name) || !"java/lang/Object".equals(node.superName)) {
            throw new IOException("Unexpected Minecraft 1.20.1 GameNarrator shape");
        }

        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || !CONSTRUCTOR_DESC.equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && NARRATOR.equals(call.owner)
                        && "getNarrator".equals(call.name)
                        && ("()" + NARRATOR_DESC).equals(call.desc)) {
                    method.instructions.set(call, new FieldInsnNode(
                            Opcodes.GETSTATIC, NARRATOR, "EMPTY", NARRATOR_DESC));
                    changed++;
                }
            }
        }
        if (changed != 1) {
            throw new IOException("Expected one Android narrator initialization change, got "
                    + changed);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static boolean isPatched(byte[] input) {
        ClassNode node = read(input);
        if (!CLASS_NAME.equals(node.name)) {
            return false;
        }
        int emptyReads = 0;
        int factoryCalls = 0;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || !CONSTRUCTOR_DESC.equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (field.getOpcode() == Opcodes.GETSTATIC
                            && NARRATOR.equals(field.owner)
                            && "EMPTY".equals(field.name)
                            && NARRATOR_DESC.equals(field.desc)) {
                        emptyReads++;
                    }
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (NARRATOR.equals(call.owner) && "getNarrator".equals(call.name)) {
                        factoryCalls++;
                    }
                }
            }
        }
        return emptyReads == 1 && factoryCalls == 0;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void requireHash(byte[] input) throws IOException {
        String actual = NhClientPatcher.sha256(input);
        if (!CLASS_SHA256.equals(actual)) {
            throw new IOException("Class hash mismatch for " + ENTRY + ": expected "
                    + CLASS_SHA256 + ", got " + actual);
        }
    }
}
