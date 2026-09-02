package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;

final class DedicatedDataFixer1201Transformer {
    static final String ENTRY = "net/minecraft/util/datafix/DataFixers.class";
    static final String SHARED_CONSTANTS_ENTRY = "net/minecraft/SharedConstants.class";

    private static final String CLASS_NAME = "net/minecraft/util/datafix/DataFixers";
    private static final String CLASS_SHA256 =
            "9a9181646d588304e6fb597dcc0646dee006c507668123c5a0c218e422265578";
    private static final String SHARED_CONSTANTS = "net/minecraft/SharedConstants";
    private static final String SHARED_CONSTANTS_SHA256 =
            "ebff3fb4042388219b3ebaef3881db7fd36dd7e8c71c1ff217ccb9fc66c8f5d9";
    private static final String DATA_FIXER = "com/mojang/datafixers/DataFixer";
    private static final String DATA_FIXER_DESC = "L" + DATA_FIXER + ";";
    private static final String DYNAMIC = "com/mojang/serialization/Dynamic";
    private static final String SCHEMA = "com/mojang/datafixers/schemas/Schema";
    private static final String UPDATE_DESC =
            "(Lcom/mojang/datafixers/DSL$TypeReference;L" + DYNAMIC
                    + ";II)L" + DYNAMIC + ";";

    private DedicatedDataFixer1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (SHARED_CONSTANTS_ENTRY.equals(entryName)) {
            return transformSharedConstants(input);
        }
        if (!ENTRY.equals(entryName)) return null;
        requireHash(input);

        ClassNode node = read(input);
        if (!CLASS_NAME.equals(node.name) || !"java/lang/Object".equals(node.superName)) {
            throw new IOException("Unexpected Minecraft 1.20.1 DataFixers shape");
        }
        if (!node.interfaces.contains(DATA_FIXER)) node.interfaces.add(DATA_FIXER);

        MethodNode classInitializer = null;
        for (MethodNode method : node.methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                classInitializer = method;
                break;
            }
        }
        if (classInitializer == null) {
            throw new IOException("Minecraft 1.20.1 DataFixers has no class initializer");
        }
        clear(classInitializer);
        classInitializer.instructions.add(new TypeInsnNode(Opcodes.NEW, CLASS_NAME));
        classInitializer.instructions.add(new InsnNode(Opcodes.DUP));
        classInitializer.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, CLASS_NAME, "<init>", "()V", false));
        classInitializer.instructions.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, CLASS_NAME, "f_216514_", DATA_FIXER_DESC));
        classInitializer.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode update = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "update",
                UPDATE_DESC,
                "<T:Ljava/lang/Object;>(Lcom/mojang/datafixers/DSL$TypeReference;"
                        + "Lcom/mojang/serialization/Dynamic<TT;>;II)"
                        + "Lcom/mojang/serialization/Dynamic<TT;>;",
                null);
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        update.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(update);

        MethodNode getSchema = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "getSchema",
                "(I)L" + SCHEMA + ";",
                null,
                null);
        getSchema.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        getSchema.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(getSchema);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The SRG development artifact hard-codes IS_RUNNING_IN_IDE=true. Mojang's
     * production client keeps it false, which makes entity/item registration
     * accept a null data-fixer type. Restoring that production flag lets the
     * lightweight DataFixer discard the complete schema graph safely.
     */
    private static byte[] transformSharedConstants(byte[] input) throws IOException {
        String actual = NhClientPatcher.sha256(input);
        if (!SHARED_CONSTANTS_SHA256.equals(actual)) {
            throw new IOException("Class hash mismatch for " + SHARED_CONSTANTS_ENTRY
                    + ": expected " + SHARED_CONSTANTS_SHA256 + ", got " + actual);
        }
        ClassNode node = read(input);
        if (!SHARED_CONSTANTS.equals(node.name)) {
            throw new IOException("Unexpected Minecraft 1.20.1 SharedConstants shape");
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!"<clinit>".equals(method.name) || !"()V".equals(method.desc)) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof FieldInsnNode)
                        || instruction.getOpcode() != Opcodes.PUTSTATIC) {
                    continue;
                }
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (!SHARED_CONSTANTS.equals(field.owner)
                        || !"f_136182_".equals(field.name)
                        || !"Z".equals(field.desc)) continue;
                AbstractInsnNode value = instruction.getPrevious();
                if (value == null || value.getOpcode() != Opcodes.ICONST_1) {
                    throw new IOException("IS_RUNNING_IN_IDE initializer is not ICONST_1");
                }
                method.instructions.set(value, new InsnNode(Opcodes.ICONST_0));
                changed++;
            }
        }
        if (changed != 1) {
            throw new IOException("Expected one IS_RUNNING_IN_IDE assignment, changed " + changed);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static boolean isPatched(byte[] input) {
        ClassNode node = read(input);
        if (!CLASS_NAME.equals(node.name) || !node.interfaces.contains(DATA_FIXER)) {
            return false;
        }
        boolean updateFound = false;
        boolean schemaFound = false;
        boolean lightweightInitializer = false;
        for (MethodNode method : node.methods) {
            if ("update".equals(method.name) && UPDATE_DESC.equals(method.desc)) {
                updateFound = containsOpcode(method, Opcodes.ALOAD)
                        && containsOpcode(method, Opcodes.ARETURN);
            } else if ("getSchema".equals(method.name)
                    && ("(I)L" + SCHEMA + ";").equals(method.desc)) {
                schemaFound = containsOpcode(method, Opcodes.ACONST_NULL)
                        && containsOpcode(method, Opcodes.ARETURN);
            } else if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                int newSelf = 0;
                int legacyBuilderCalls = 0;
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                     instruction != null; instruction = instruction.getNext()) {
                    if (instruction instanceof TypeInsnNode
                            && instruction.getOpcode() == Opcodes.NEW
                            && CLASS_NAME.equals(((TypeInsnNode) instruction).desc)) {
                        newSelf++;
                    } else if (instruction instanceof MethodInsnNode
                            && "m_274588_".equals(((MethodInsnNode) instruction).name)) {
                        legacyBuilderCalls++;
                    }
                }
                lightweightInitializer = newSelf == 1 && legacyBuilderCalls == 0;
            }
        }
        return updateFound && schemaFound && lightweightInitializer;
    }

    static boolean isIdeFlagPatched(byte[] input) {
        ClassNode node = read(input);
        if (!SHARED_CONSTANTS.equals(node.name)) return false;
        for (MethodNode method : node.methods) {
            if (!"<clinit>".equals(method.name) || !"()V".equals(method.desc)) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode
                        && instruction.getOpcode() == Opcodes.PUTSTATIC) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (!SHARED_CONSTANTS.equals(field.owner)
                            || !"f_136182_".equals(field.name)
                            || !"Z".equals(field.desc)) continue;
                    AbstractInsnNode value = instruction.getPrevious();
                    return value != null && value.getOpcode() == Opcodes.ICONST_0;
                }
            }
        }
        return false;
    }

    private static boolean containsOpcode(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == opcode) return true;
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
