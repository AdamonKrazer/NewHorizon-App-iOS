package com.newhorizon.clientpatcher;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.IOException;

/** Hash-pinned hooks: retain vanilla vertex layout, sorting, physics and renderer. */
final class VanillaMemoryOwners1201Transformer {
    private static final String BUFFER = "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final String SIMPLE = "net/minecraft/client/resources/model/SimpleBakedModel";
    private static final String POLICY = "com/newhorizon/thinclient/minecraft/VanillaBufferPolicy";
    private static final String BYTES = "Ljava/nio/ByteBuffer;";

    static byte[] transform(String entry, byte[] input) throws IOException {
        boolean buffer = entry.equals(BUFFER + ".class"), simple = entry.equals(SIMPLE + ".class");
        if (!buffer && !simple) return null;
        String expected = buffer ? "224863a3c73339fd03330d7e1f0d4d89a366fac73df938c91ed246fa9807f9e9"
                : "d3fe8a4e78793a0edddc6e0c13fdbc0f83c46a0aaf2c9d444c80048aca3f9c77";
        if (!expected.equals(NhClientPatcher.sha256(input))) throw new IOException("Memory-owner class hash mismatch: " + entry);
        ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0);
        int allocations = 0, grows = 0, clears = 0, models = 0;
        for (MethodNode method : node.methods) {
            if (buffer && (method.name.equals("<init>") || method.name.equals("m_85722_"))) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!call.owner.equals("com/mojang/blaze3d/platform/MemoryTracker")) continue;
                    if (call.name.equals("m_182527_") && call.desc.equals("(I)" + BYTES)) {
                        method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY, "allocate", "(ILjava/lang/Object;)" + BYTES, false));
                        allocations++;
                    } else if (call.name.equals("m_182529_") && call.desc.equals("(" + BYTES + "I)" + BYTES)) {
                        InsnList args = new InsnList();
                        args.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        args.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        args.add(new FieldInsnNode(Opcodes.GETFIELD, BUFFER, "f_85652_", "I"));
                        args.add(new VarInsnNode(Opcodes.ILOAD, 1)); args.add(new InsnNode(Opcodes.IADD));
                        method.instructions.insertBefore(call, args);
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY, "grow",
                                "(" + BYTES + "ILjava/lang/Object;I)" + BYTES, false));
                        grows++;
                    }
                }
            } else if (buffer && method.name.equals("m_85729_") && method.desc.equals("()V")) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); // destination for putfield
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new FieldInsnNode(Opcodes.GETFIELD, BUFFER, "f_85648_", BYTES));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                for (String field : new String[]{"f_85652_", "f_231156_", "f_85661_"}) {
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new FieldInsnNode(Opcodes.GETFIELD, BUFFER, field, field.equals("f_85661_") ? "Z" : "I"));
                }
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, POLICY, "idle",
                        "(" + BYTES + "Ljava/lang/Object;IIZ)" + BYTES, false));
                hook.add(new FieldInsnNode(Opcodes.PUTFIELD, BUFFER, "f_85648_", BYTES));
                method.instructions.insert(hook); clears++;
            } else if (simple && method.name.equals("<init>")
                    && method.desc.startsWith("(Ljava/util/List;Ljava/util/Map;")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() != Opcodes.RETURN) continue;
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/newhorizon/thinclient/minecraft/VanillaQuadDeduplicator", "completed",
                            "(Ljava/util/List;Ljava/util/Map;)V", false));
                    method.instructions.insertBefore(instruction, hook); models++;
                }
            }
        }
        if ((buffer && (allocations != 1 || grows != 1 || clears != 1)) || (simple && models != 1))
            throw new IOException("Memory-owner hooks mismatch " + entry + ": " + allocations + "/" + grows + "/" + clears + "/" + models);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
}
