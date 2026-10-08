/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package audit.holder;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.io.File;
import java.util.jar.JarFile;

/** Test-only counter: instrumentation changes no target method arguments or results. */
public final class SnapshotAgent {
    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        File directory = new File(SnapshotAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(new File(directory, "SnapshotCounters.jar")));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                    ProtectionDomain domain, byte[] bytes) {
                boolean inventory = "org/bukkit/craftbukkit/inventory/CraftInventory".equals(name);
                boolean blockState = "org/bukkit/craftbukkit/block/CraftBlockEntityState".equals(name);
                if (!inventory && !blockState) return null;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor original = super.visitMethod(access, method, descriptor, signature, exceptions);
                        boolean snapshot = blockState && method.equals("createSnapshot");
                        boolean holder = inventory && method.equals("getHolder");
                        if (!snapshot && !holder) return original;
                        return new MethodVisitor(Opcodes.ASM9, original) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                if (holder && descriptor.startsWith("(Z)")) {
                                    visitVarInsn(Opcodes.ILOAD, 1);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, "audit/holder/SnapshotCounters",
                                            "holder", "(Z)V", false);
                                } else {
                                    visitLdcInsn(snapshot ? "snapshot" : "holder.noarg");
                                    visitMethodInsn(Opcodes.INVOKESTATIC, "audit/holder/SnapshotCounters",
                                            "record", "(Ljava/lang/String;)V", false);
                                }
                            }
                        };
                    }
                }, 0);
                System.out.println("[HolderAgent] instrumented " + name);
                return writer.toByteArray();
            }
        });
    }

}
