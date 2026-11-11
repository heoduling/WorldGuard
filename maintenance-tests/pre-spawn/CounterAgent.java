package audit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

public final class CounterAgent {
    public static void premain(String input, Instrumentation instrumentation) throws Exception {
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(System.getProperty("audit.bridge")));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                int index;
                if (name.equals("com/destroystokyo/paper/event/entity/PreCreatureSpawnEvent")) {
                    index = 0;
                } else if (name.equals("org/bukkit/Location")) {
                    index = 1;
                } else if (name.equals("com/sk89q/worldguard/bukkit/listener/WorldGuardEntityListener$PaperListener")
                        || name.equals("com/sk89q/worldguard/bukkit/listener/WorldGuardEntityListener$PreCreatureSpawnListener")) {
                    index = 2;
                } else {
                    return null;
                }
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
                        boolean target = index == 0 && method.equals("<init>")
                                || index == 1 && method.equals("<init>") && descriptor.equals("(Lorg/bukkit/World;DDDFF)V")
                                || index == 2 && method.equals("onPreCreatureSpawn");
                        if (!target) {
                            return visitor;
                        }
                        return new AdviceAdapter(Opcodes.ASM9, visitor, access, method, descriptor) {
                            @Override
                            protected void onMethodEnter() {
                                push(index);
                                visitMethodInsn(INVOKESTATIC, "audit/Counts", "hit", "(I)V", false);
                            }
                        };
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            }
        });
    }
}
