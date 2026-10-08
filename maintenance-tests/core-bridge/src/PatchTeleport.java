import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;

/** Build-time transform only. Refuses any layout other than the audited exact core. */
public final class PatchTeleport {
    public static void main(String[] args) throws Exception {
        ClassNode cls = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(cls, 0);
        String descriptor = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Ljava/lang/Float;Ljava/lang/Float;Lnet/minecraft/world/phys/Vec3;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;JLjava/util/function/Consumer;)Z";
        String queue = "net/minecraft/world/entity/Entity$QueuedAsyncTeleport";
        String cause = "Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;";
        int enqueue = 0, dequeue = 0;
        int patched = 0;
        for (MethodNode method : cls.methods) {
            if (method.name.equals("startNextPendingAsyncTeleport")) {
                LabelNode poll = new LabelNode();
                method.instructions.insert(poll);
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof FieldInsnNode field && field.owner.equals(queue) && field.name.equals("teleportFlags")) {
                        InsnList retainCause = new InsnList();
                        retainCause.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        retainCause.add(new FieldInsnNode(Opcodes.GETFIELD, queue, "worldguardCause", cause));
                        method.instructions.insertBefore(instruction.getPrevious(), retainCause);
                    }
                    if (instruction instanceof MethodInsnNode call && call.name.equals("startPendingAsyncTeleport")) {
                        call.name = "teleportAsync";
                        call.desc = descriptor;
                        InsnList complete = new InsnList();
                        // Rejected queued requests still complete and cannot strand later requests.
                        LabelNode rejected = new LabelNode();
                        complete.add(new JumpInsnNode(Opcodes.IFEQ, rejected));
                        complete.add(new JumpInsnNode(Opcodes.GOTO, poll));
                        complete.add(rejected);
                        complete.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        complete.add(new InsnNode(Opcodes.ACONST_NULL));
                        complete.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, queue, "complete", "(Lnet/minecraft/world/entity/Entity;)V", false));
                        complete.add(new JumpInsnNode(Opcodes.GOTO, poll));
                        method.instructions.insert(instruction, complete);
                        dequeue++;
                    }
                }
            }
            if (!method.name.equals("teleportAsync") || !method.desc.equals(descriptor)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(queue) && call.name.equals("<init>")) {
                    InsnList retainCause = new InsnList();
                    retainCause.add(new InsnNode(Opcodes.DUP));
                    retainCause.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    retainCause.add(new FieldInsnNode(Opcodes.PUTFIELD, queue, "worldguardCause", cause));
                    method.instructions.insert(instruction, retainCause);
                    enqueue++;
                }
                if (!(instruction instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                        || !type.desc.equals("me/earthme/luminol/api/entity/EntityTeleportAsyncEvent")) continue;
                InsnList code = new InsnList();
                for (int slot : new int[]{0, 1, 2, 3, 4, 6}) code.add(new VarInsnNode(Opcodes.ALOAD, slot));
                code.add(new VarInsnNode(Opcodes.LLOAD, 7));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "wgbridge/TeleportBridge", "check",
                        "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Ljava/lang/Float;Ljava/lang/Float;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;J)Lwgbridge/TeleportBridge$Target;", false));
                int temporary = method.maxLocals++;
                code.add(new VarInsnNode(Opcodes.ASTORE, temporary));
                code.add(new VarInsnNode(Opcodes.ALOAD, temporary));
                LabelNode accepted = new LabelNode();
                code.add(new JumpInsnNode(Opcodes.IFNONNULL, accepted));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                code.add(accepted);
                String[] names = {"world", "position", "yaw", "pitch"};
                String[] types = {"Lnet/minecraft/server/level/ServerLevel;", "Lnet/minecraft/world/phys/Vec3;", "Ljava/lang/Float;", "Ljava/lang/Float;"};
                for (int i = 0; i < 4; i++) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, temporary));
                    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "wgbridge/TeleportBridge$Target", names[i], "()" + types[i], false));
                    code.add(new VarInsnNode(Opcodes.ASTORE, i + 1));
                }
                method.instructions.insertBefore(instruction, code);
                patched++;
            }
        }
        if (patched != 1 || enqueue != 1 || dequeue != 1) throw new IllegalStateException("Unexpected teleport layout: " + patched + "/" + enqueue + "/" + dequeue);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cls.accept(writer);
        Files.write(Path.of(args[1]), writer.toByteArray());
        ClassNode queued = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[2]))).accept(queued, 0);
        queued.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "worldguardCause", cause, null, null));
        ClassWriter queueWriter = new ClassWriter(0);
        queued.accept(queueWriter);
        Files.write(Path.of(args[3]), queueWriter.toByteArray());
        System.out.println("Patched pre-teleport and queued execution; preserved queued cause and native notification.");
    }
}
