package audit.wg;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Resolves statically referenced server API symbols using the actual WG runtime loader. */
final class LinkScan {
    record Ref(String owner, String name, String descriptor, boolean method) {}
    static final Map<Ref,Set<String>> refs = new TreeMap<>(Comparator.comparing(Ref::toString));
    static boolean core(String name) {
        return name.startsWith("org/bukkit/") || name.startsWith("io/papermc/") || name.startsWith("com/destroystokyo/") || name.startsWith("net/kyori/");
    }
    static void add(String caller, String owner, String name, String desc, boolean method) {
        if(core(owner)) refs.computeIfAbsent(new Ref(owner,name,desc,method), key -> new TreeSet<>()).add(caller);
    }
    static boolean resolve(Class<?> type, Ref r, Set<Class<?>> seen) {
        if(type == null || !seen.add(type)) return false;
        if(r.method) {
            if(r.name.equals("<init>")) return Arrays.stream(type.getDeclaredConstructors()).anyMatch(c -> Type.getConstructorDescriptor(c).equals(r.descriptor));
            for(Method m:type.getDeclaredMethods()) if(m.getName().equals(r.name) && Type.getMethodDescriptor(m).equals(r.descriptor)) return true;
        } else for(Field f:type.getDeclaredFields()) if(f.getName().equals(r.name) && Type.getDescriptor(f.getType()).equals(r.descriptor)) return true;
        if(resolve(type.getSuperclass(),r,seen)) return true;
        for(Class<?> parent:type.getInterfaces()) if(resolve(parent,r,seen)) return true;
        if(type.isInterface() && r.method) for(Method m:Object.class.getMethods()) if(m.getName().equals(r.name) && Type.getMethodDescriptor(m).equals(r.descriptor)) return true;
        return false;
    }
    static void run(JavaPlugin probe) throws Exception {
        refs.clear(); int classes=0;
        var plugin = WorldGuardPlugin.inst();
        var location = Paths.get(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        try(JarFile jar = new JarFile(location.toFile())) {
            for(var entry:Collections.list(jar.entries())) {
                if(!entry.getName().endsWith(".class")) continue;
                classes++; String caller=entry.getName();
                new ClassReader(jar.getInputStream(entry)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions) {
                        String at=caller+"#"+name;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op,String owner,String n,String d,boolean itf) { add(at,owner,n,d,true); }
                            @Override public void visitFieldInsn(int op,String owner,String n,String d) { add(at,owner,n,d,false); }
                            @Override public void visitInvokeDynamicInsn(String n,String d,Handle b,Object... args) {
                                for(Object arg:args) if(arg instanceof Handle h) add(at,h.getOwner(),h.getName(),h.getDesc(),h.getTag()>Opcodes.H_PUTSTATIC);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            }
        }
        List<String> lines=new ArrayList<>(); lines.add("status\tsymbol\tcallers"); int missing=0;
        for(var entry:refs.entrySet()) {
            Ref r=entry.getKey(); String status;
            try { status=resolve(Class.forName(r.owner.replace('/','.'),false,plugin.getClass().getClassLoader()),r,new HashSet<>()) ? "OK":"MISSING_MEMBER"; }
            catch(Throwable e) { status="UNRESOLVED:"+e; }
            if(!status.equals("OK")) { missing++; probe.getLogger().warning("LINK_SCAN " + status + " " + r); }
            lines.add(status+"\t"+r+"\t"+String.join(",",entry.getValue()));
        }
        Files.createDirectories(probe.getDataFolder().toPath());
        Files.write(probe.getDataFolder().toPath().resolve("linkage.tsv"),lines);
        probe.getLogger().info("LINK_SCAN_RESULT classes="+classes+" symbols="+refs.size()+" unresolved="+missing);
    }
}
