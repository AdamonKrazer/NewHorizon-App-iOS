package com.newhorizon.clientpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;

/** Disables the Forge replacement of SoundEngine.reload(). */
final class ForgeSound1201Transformer {
    static final String ENTRY = "net/minecraft/client/sounds/SoundEngine.class";

    private static final String CLASS_NAME =
            "net/minecraft/client/sounds/SoundEngine";
    private static final String CLASS_SHA256 =
            "e40032adeacbd29ff12af1900a220ea66c0aefed39b4d8452de50d08d7575d5d";

    private ForgeSound1201Transformer() {
    }

    static byte[] transform(String entryName, byte[] input) throws IOException {
        if (!ENTRY.equals(entryName)) return null;
        String actual = NhClientPatcher.sha256(input);
        if (!CLASS_SHA256.equals(actual)) {
            throw new IOException("Class hash mismatch for " + ENTRY + ": expected "
                    + CLASS_SHA256 + ", got " + actual);
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        if (!CLASS_NAME.equals(node.name)) {
            throw new IOException("Unexpected Forge 47.4.0 SoundEngine shape");
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if ("m_120239_".equals(method.name) && "()V".equals(method.desc)) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.visibleLocalVariableAnnotations = null;
                method.invisibleLocalVariableAnnotations = null;
                method.instructions.add(new InsnNode(Opcodes.RETURN));
                method.maxStack = 0;
                int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                for (Type argument : Type.getArgumentTypes(method.desc)) {
                    slots += argument.getSize();
                }
                method.maxLocals = slots;
                changed++;
            }
        }
        if (changed != 1) {
            throw new IOException("Expected one Forge SoundEngine reload method, got "
                    + changed);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
