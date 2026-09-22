package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;

/** Adds stable calls to the tiny non-Forge display/session extension. */
final class LiteDisplayBridge1201Transformer {
    static final String LISTENER_ENTRY =
            "net/minecraft/client/multiplayer/ClientPacketListener.class";
    static final String RENDERER_ENTRY =
            "net/minecraft/client/renderer/LevelRenderer.class";
    static final String MINECRAFT_ENTRY =
            "net/minecraft/client/Minecraft.class";

    private static final String LISTENER_SHA256 =
            "440ca921f6ca96e7a71508c2408af5d0b3004922e5200e04a1b768e658d1c944";
    private static final String RENDERER_SHA256 =
            "c1603c5a96efece8736bcfc9842962ce5ffb3580331d0baf4d1e0f9984fecb3b";
    private static final String MINECRAFT_SHA256 =
            "e19f6b95ab7620a1b42cd04b8c41d91089fb4556d8eb53efefc2f810653b7f01";
    private static final String LISTENER =
            "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String PAYLOAD =
            "net/minecraft/network/protocol/game/ClientboundCustomPayloadPacket";
    private static final String RENDERER =
            "net/minecraft/client/renderer/LevelRenderer";
    private static final String EXTENSION =
            "com/newhorizon/thinclient/minecraft/MinecraftDisplayExtension";

    private LiteDisplayBridge1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (LISTENER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, LISTENER_SHA256);
            return transformListener(input);
        }
        if (RENDERER_ENTRY.equals(entryName)) {
            requireHash(entryName, input, RENDERER_SHA256);
            return transformRenderer(input);
        }
        if (MINECRAFT_ENTRY.equals(entryName)) {
            requireHash(entryName, input, MINECRAFT_SHA256);
            return transformMinecraft(input);
        }
        return null;
    }

    private static byte[] transformListener(byte[] input) throws IOException {
        ClassNode node = read(input);
        if (!LISTENER.equals(node.name)) {
            throw new IOException("Unexpected Minecraft 1.20.1 packet listener shape");
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("m_5998_".equals(method.name)
                    && "(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V"
                    .equals(method.desc)) {
                insertBeforeReturns(method, loginCall());
                changed++;
            } else if ("m_7413_".equals(method.name)
                    && ("(L" + PAYLOAD + ";)V").equals(method.desc)) {
                method.instructions.insert(payloadCall());
                changed++;
            }
        }
        if (changed != 2) {
            throw new IOException("Expected login and payload hooks, got " + changed);
        }
        return write(node);
    }

    private static byte[] transformRenderer(byte[] input) throws IOException {
        ClassNode node = read(input);
        if (!RENDERER.equals(node.name)) {
            throw new IOException("Unexpected Minecraft 1.20.1 level renderer shape");
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("m_109599_".equals(method.name)
                    && ("(Lcom/mojang/blaze3d/vertex/PoseStack;FJZ"
                    + "Lnet/minecraft/client/Camera;"
                    + "Lnet/minecraft/client/renderer/GameRenderer;"
                    + "Lnet/minecraft/client/renderer/LightTexture;"
                    + "Lorg/joml/Matrix4f;)V").equals(method.desc)) {
                insertBeforeReturns(method, renderCall());
                changed++;
            }
        }
        if (changed != 1) {
            throw new IOException("Expected one level-render hook, got " + changed);
        }
        return write(node);
    }

    private static byte[] transformMinecraft(byte[] input) throws IOException {
        ClassNode node = read(input);
        int screenHooks = 0;
        int tickHooks = 0;
        for (MethodNode method : node.methods) {
            if ("m_91152_".equals(method.name)
                    && "(Lnet/minecraft/client/gui/screens/Screen;)V".equals(method.desc)) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft", "f_91080_",
                        "Lnet/minecraft/client/gui/screens/Screen;"));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EXTENSION,
                        "onScreen", "(Lnet/minecraft/client/Minecraft;"
                                + "Lnet/minecraft/client/gui/screens/Screen;)V", false));
                insertBeforeReturns(method, hook);
                screenHooks++;
            } else if ("m_91398_".equals(method.name) && "()V".equals(method.desc)) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EXTENSION,
                        "tick", "(Lnet/minecraft/client/Minecraft;)V", false));
                insertBeforeReturns(method, hook);
                tickHooks++;
            }
        }
        if (screenHooks != 1 || tickHooks != 1) {
            throw new IOException("Expected screen/tick hooks, got screen="
                    + screenHooks + " tick=" + tickHooks);
        }
        return write(node);
    }

    private static InsnList loginCall() {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EXTENSION,
                "onLogin", "(L" + LISTENER + ";)V", false));
        return instructions;
    }

    private static InsnList payloadCall() {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EXTENSION,
                "onCustomPayload", "(L" + PAYLOAD + ";)V", false));
        return instructions;
    }

    private static InsnList renderCall() {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        // long consumes slots 3 and 4; boolean is 5 and Camera is 6.
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EXTENSION,
                "render", "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/Camera;)V", false));
        return instructions;
    }

    private static void insertBeforeReturns(MethodNode method, InsnList template) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.insertBefore(instruction, clone(template));
            }
        }
    }

    private static InsnList clone(InsnList source) {
        InsnList result = new InsnList();
        for (AbstractInsnNode instruction = source.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) instruction;
                result.add(new VarInsnNode(variable.getOpcode(), variable.var));
            } else if (instruction instanceof MethodInsnNode) {
                MethodInsnNode method = (MethodInsnNode) instruction;
                result.add(new MethodInsnNode(method.getOpcode(), method.owner,
                        method.name, method.desc, method.itf));
            } else if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode) {
                org.objectweb.asm.tree.FieldInsnNode field =
                        (org.objectweb.asm.tree.FieldInsnNode) instruction;
                result.add(new org.objectweb.asm.tree.FieldInsnNode(field.getOpcode(),
                        field.owner, field.name, field.desc));
            } else {
                throw new IllegalArgumentException("Unsupported hook instruction");
            }
        }
        return result;
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
