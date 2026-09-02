package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.util.Collections;

final class Telemetry1201Transformer {
    static final String MANAGER_ENTRY =
            "net/minecraft/client/telemetry/ClientTelemetryManager.class";
    static final String WORLD_MANAGER_ENTRY =
            "net/minecraft/client/telemetry/WorldSessionTelemetryManager.class";

    private static final String MANAGER_SHA256 =
            "7f10e36d3d8a948f99c1dbebcf89ceeb169b83c94767bfe5568054545e31297e";
    private static final String WORLD_MANAGER_SHA256 =
            "d3852704ceefae79dc49992723adc505cfc2dc2502524c2eac5a6f4ad7ff1d0f";

    private static final String MANAGER =
            "net/minecraft/client/telemetry/ClientTelemetryManager";
    private static final String WORLD_MANAGER =
            "net/minecraft/client/telemetry/WorldSessionTelemetryManager";
    private static final String EVENT_SENDER =
            "net/minecraft/client/telemetry/TelemetryEventSender";

    private Telemetry1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (MANAGER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, MANAGER_SHA256);
            return transformManager(input);
        }
        if (WORLD_MANAGER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, WORLD_MANAGER_SHA256);
            return transformWorldManager(input);
        }
        return null;
    }

    private static byte[] transformManager(byte[] input) throws IOException {
        ClassNode node = read(input);
        if (!MANAGER.equals(node.name) || !"java/lang/Object".equals(node.superName)) {
            throw new IOException("Unexpected Minecraft 1.20.1 telemetry manager shape");
        }

        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name)
                    && ("(Lnet/minecraft/client/Minecraft;"
                    + "Lcom/mojang/authlib/minecraft/UserApiService;"
                    + "Lnet/minecraft/client/User;)V").equals(method.desc)) {
                replaceConstructor(method, node.superName);
                changed++;
            } else if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                replaceWithVoid(method);
                changed++;
            } else if (("m_285963_".equals(method.name) || "m_261052_".equals(method.name))
                    && ("()L" + EVENT_SENDER + ";").equals(method.desc)) {
                clear(method);
                method.instructions.add(new FieldInsnNode(
                        Opcodes.GETSTATIC, EVENT_SENDER, "f_260501_",
                        "L" + EVENT_SENDER + ";"));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                finish(method);
                changed++;
            } else if ("m_260914_".equals(method.name)
                    && "()Ljava/nio/file/Path;".equals(method.desc)) {
                replaceWithNull(method);
                changed++;
            } else if ("close".equals(method.name) && "()V".equals(method.desc)) {
                replaceWithVoid(method);
                changed++;
            }
        }
        if (changed != 6) {
            throw new IOException("Expected 6 telemetry manager method changes, got " + changed);
        }
        return write(node);
    }

    private static byte[] transformWorldManager(byte[] input) throws IOException {
        ClassNode node = read(input);
        if (!WORLD_MANAGER.equals(node.name) || !"java/lang/Object".equals(node.superName)) {
            throw new IOException("Unexpected Minecraft 1.20.1 world telemetry manager shape");
        }

        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name)) {
                replaceConstructor(method, node.superName);
                changed++;
            } else if (!"<clinit>".equals(method.name)
                    && Type.getReturnType(method.desc).getSort() == Type.VOID) {
                replaceWithVoid(method);
                changed++;
            }
        }
        if (changed != node.methods.size()) {
            throw new IOException("World telemetry manager gained an unsupported non-void method");
        }
        return write(node);
    }

    private static void replaceConstructor(MethodNode method, String superName) {
        clear(method);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        finish(method);
    }

    private static void replaceWithVoid(MethodNode method) {
        clear(method);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        finish(method);
    }

    private static void replaceWithNull(MethodNode method) {
        clear(method);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        finish(method);
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
        method.maxLocals = argumentSlots(method);
    }

    private static int argumentSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            slots += argument.getSize();
        }
        return slots;
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
