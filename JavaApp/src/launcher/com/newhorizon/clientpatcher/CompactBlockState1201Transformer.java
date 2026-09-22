package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;

/** Exact 1.20.1 hooks for shared state tables and immutable shape caches. */
final class CompactBlockState1201Transformer {
    private static final String STATE = "net/minecraft/world/level/block/state/StateHolder";
    private static final String CACHE =
            "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache";
    private static final String RUNTIME =
            "com/newhorizon/thinclient/minecraft/CompactStateRuntime";
    private static final String CACHE_DEDUP =
            "com/newhorizon/thinclient/minecraft/BlockStateCacheDeduplicator";

    private CompactBlockState1201Transformer() {
    }

    static byte[] transform(String entry, byte[] input) throws IOException {
        boolean state = entry.equals(STATE + ".class");
        boolean cache = entry.equals(CACHE + ".class");
        if (!state && !cache) return null;
        String expected = state
                ? "22fa9946012cd418ac8016af19876b6b84a63b5c44edb621fa98cf06129eab57"
                : "7e9f17ef6fe286162b52f74b48a718f729252887d02980cdd4e02fd53ae84f9e";
        if (!expected.equals(NhClientPatcher.sha256(input))) {
            throw new IOException("Compact block-state class hash mismatch: " + entry);
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        int mutableFields = 0;
        int populateHooks = 0;
        int neighborHooks = 0;
        int cacheHooks = 0;
        for (FieldNode field : node.fields) {
            if (state && field.name.equals("f_61111_")
                    && field.desc.equals("Lcom/google/common/collect/ImmutableMap;")) {
                field.access &= ~Opcodes.ACC_FINAL;
                mutableFields++;
            } else if (cache && (field.name.equals("f_60849_")
                    || field.name.equals("f_60842_")
                    || field.name.equals("f_60850_"))) {
                field.access &= ~Opcodes.ACC_FINAL;
                mutableFields++;
            }
        }

        for (MethodNode method : node.methods) {
            if (state && method.name.equals("m_61133_")
                    && method.desc.equals("(Ljava/util/Map;)V")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
                replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                        "populate",
                        "(Lnet/minecraft/world/level/block/state/StateHolder;Ljava/util/Map;)V",
                        false));
                replacement.add(new InsnNode(Opcodes.RETURN));
                method.instructions.add(replacement);
                populateHooks++;
            } else if (state && (method.name.equals("m_61124_")
                    || method.name.equals("m_263224_"))) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.getOpcode() == Opcodes.INVOKEINTERFACE
                            && call.owner.equals("com/google/common/collect/Table")
                            && call.name.equals("get")
                            && call.desc.equals(
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")) {
                        method.instructions.insertBefore(call,
                                new VarInsnNode(Opcodes.ALOAD, 0));
                        method.instructions.set(call, new MethodInsnNode(
                                Opcodes.INVOKESTATIC, RUNTIME, "neighbor",
                                "(Lcom/google/common/collect/Table;Ljava/lang/Object;"
                                        + "Ljava/lang/Object;Lnet/minecraft/world/level/block/state/"
                                        + "StateHolder;)Ljava/lang/Object;",
                                false));
                        neighborHooks++;
                    }
                }
            } else if (cache && method.name.equals("<init>")
                    && method.desc.equals(
                    "(Lnet/minecraft/world/level/block/state/BlockState;)V")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() != Opcodes.RETURN) continue;
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CACHE_DEDUP,
                            "completed", "(Ljava/lang/Object;)V", false));
                    method.instructions.insertBefore(instruction, hook);
                    cacheHooks++;
                }
            }
        }

        if ((state && (mutableFields != 1 || populateHooks != 1 || neighborHooks != 2))
                || (cache && (mutableFields != 3 || cacheHooks != 1))) {
            throw new IOException("Compact block-state hooks mismatch " + entry
                    + ": fields=" + mutableFields + " populate=" + populateHooks
                    + " neighbors=" + neighborHooks + " cache=" + cacheHooks);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
