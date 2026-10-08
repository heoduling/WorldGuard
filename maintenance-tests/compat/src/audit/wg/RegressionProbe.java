package audit.wg;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import io.papermc.paper.entity.TeleportFlag;
import me.earthme.luminol.api.entity.EntityTeleportAsyncEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only in the isolated test server. It creates and removes test entities/regions. */
public final class RegressionProbe extends JavaPlugin implements Listener {
    final AtomicInteger assertions = new AtomicInteger(), regular = new AtomicInteger(), nativeEvents = new AtomicInteger();
    final AtomicInteger preCancelled = new AtomicInteger(), deaths = new AtomicInteger(), drops = new AtomicInteger();
    final Set<String> removedIds = new HashSet<>();
    final Map<UUID,List<Entity>> mobs = new ConcurrentHashMap<>();
    final Map<UUID,List<Entity>> preserved = new ConcurrentHashMap<>();
    volatile String teleportMode = "";
    volatile UUID teleportPlayer, slayPlayer;
    volatile Location redirect;
    private void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); assertions.incrementAndGet(); }
    private void pass(String test) { getLogger().info("PASS " + test + " assertions=" + assertions.get()); }
    private void guarded(Runnable action) { try { action.run(); } catch(Throwable e) { getLogger().severe("FAIL " + e); e.printStackTrace(); } }
    private void on(Player p,long ticks,Runnable action) { p.getScheduler().runDelayed(this,t -> guarded(action),() -> getLogger().severe("FAIL player retired"),ticks); }
    private RegionManager manager(Player p) { return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(p.getWorld())); }
    private ProtectedCuboidRegion region(String id,int x,int z) { return new ProtectedCuboidRegion(id,BlockVector3.at(x-8,-64,z-8),BlockVector3.at(x+8,320,z+8)); }
    private long regionId() {
        try {
            Object region=Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler").getMethod("getCurrentRegion").invoke(null);
            return region.getClass().getField("id").getLong(region);
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this,this);
        for(World world:Bukkit.getWorlds()) {
            var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
            for(String id:manager.getRegions().keySet()) if(id.startsWith("regression_")) manager.removeRegion(id);
        }
        getLogger().info("REGRESSION_READY version="+WorldGuardPlugin.inst().getDescription().getVersion());
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        on(e.getPlayer(),1,() -> { if(e.getPlayer().isDead()) e.getPlayer().spigot().respawn(); e.getPlayer().setInvulnerable(true); });
        on(e.getPlayer(),10,() -> e.getPlayer().teleportAsync(new Location(e.getPlayer().getWorld(),-4,-60,-4)));
    }
    @EventHandler(priority=EventPriority.LOWEST) public void alter(PlayerTeleportEvent e) {
        if(!e.getPlayer().getUniqueId().equals(teleportPlayer)) return;
        if(teleportMode.equals("cancel")) e.setCancelled(true);
        if(teleportMode.equals("redirect")) e.setTo(redirect.clone());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void teleport(PlayerTeleportEvent e) {
        regular.incrementAndGet();
        getLogger().info("TELEPORT_EVENT cause="+e.getCause()+" cancelled="+e.isCancelled()+" owned="+Bukkit.isOwnedByCurrentRegion(e.getPlayer()));
    }
    @EventHandler public void nativeTeleport(EntityTeleportAsyncEvent e) { nativeEvents.incrementAndGet(); }
    @EventHandler(priority=EventPriority.MONITOR) public void pre(PreCreatureSpawnEvent e) { if(e.isCancelled()) preCancelled.incrementAndGet(); }
    @EventHandler public void death(PlayerDeathEvent e) {
        if(e.getEntity().getUniqueId().equals(slayPlayer)) {
            guarded(() -> require(Bukkit.isOwnedByCurrentRegion(e.getEntity()),"death must run on target owner"));
            deaths.incrementAndGet();
        }
    }
    @EventHandler public void drop(ItemSpawnEvent e) { if(e.getEntity().getItemStack().getType()==Material.STONE) drops.incrementAndGet(); }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==0) return true;
        if(args[0].equals("stop")) { Bukkit.getGlobalRegionScheduler().execute(this,Bukkit::shutdown);return true; }
        if(!(sender instanceof Player p)) return true;
        on(p,1,() -> {
            p.setInvulnerable(true);
            require(!p.isDead(),"player alive");
            switch(args[0]) {
                case "tp" -> teleportTest(p,args[1]);
                case "queue" -> queueTest(p);
                case "queue-native" -> nativeQueueTest(p,args[1]);
                case "metadata" -> metadata(p);
                case "commands" -> command(p,args[1]);
                case "spawn" -> spawnTest(p);
                case "seed" -> seed(p);
                case "halt" -> halt(p);
                case "halt-join" -> { WorldGuardPlugin.inst().getConfigManager().activityHaltToggle=true;pass("halt-join armed"); }
                case "check-halt" -> checkHalt();
                case "far" -> p.teleportAsync(new Location(p.getWorld(),12000,-60,12000)).whenComplete((ok,error) -> on(p,3,() -> {require(Boolean.TRUE.equals(ok)&&error==null,"far teleport");pass("far region="+regionId());}));
                case "scan" -> { try { LinkScan.run(this); } catch(Exception e) {throw new IllegalStateException(e);} }
                default -> throw new IllegalArgumentException(Arrays.toString(args));
            }
        });return true;
    }
    void teleportTest(Player p,String mode) {
        require(!p.isOp(),"non-op protection test");
        Location home=p.getLocation().clone(), target=new Location(p.getWorld(),1030,-60,1030);
        RegionManager manager=manager(p);
        ProtectedCuboidRegion region=mode.startsWith("exit") ? region("regression_tp",home.getBlockX(),home.getBlockZ()) : region("regression_tp",1030,1030);
        boolean deny=Set.of("deny","exit","pearl","chorus","cancel","vehicle-deny").contains(mode);
        if(mode.equals("deny")||mode.equals("bypass")||mode.equals("vehicle-deny")) region.setFlag(Flags.ENTRY,StateFlag.State.DENY);
        if(mode.startsWith("exit")) region.setFlag(Flags.EXIT,StateFlag.State.DENY);
        if(mode.equals("exit")) region.setFlag(Flags.EXIT_VIA_TELEPORT,StateFlag.State.DENY);
        if(mode.equals("pearl")) region.setFlag(Flags.ENDERPEARL,StateFlag.State.DENY);
        if(mode.equals("chorus")) region.setFlag(Flags.CHORUS_TELEPORT,StateFlag.State.DENY);
        manager.addRegion(region);
        var attachment=mode.equals("bypass") ? p.addAttachment(this,"worldguard.region.bypass."+p.getWorld().getName(),true) : null;
        // Allow both the query cache and two-second permission cache to expire.
        on(p,50,() -> {
            WorldGuard.getInstance().getPlatform().getSessionManager().get(WorldGuardPlugin.inst().wrapPlayer(p)).resetState(WorldGuardPlugin.inst().wrapPlayer(p));
            regular.set(0);nativeEvents.set(0);teleportMode=mode;teleportPlayer=p.getUniqueId();
            redirect=target.clone().add(100,0,100);
            Entity vehicle=mode.startsWith("vehicle") ? p.getWorld().spawnEntity(home,EntityType.OAK_BOAT) : null;
            if(vehicle!=null) require(vehicle.addPassenger(p),"mounted player");
            var cause=mode.equals("pearl") ? PlayerTeleportEvent.TeleportCause.ENDER_PEARL : mode.equals("chorus") ? PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT : PlayerTeleportEvent.TeleportCause.PLUGIN;
            CompletableFuture<Boolean> future=vehicle==null ? p.teleportAsync(target,cause)
                    : mode.equals("vehicle-dismount") ? nativeTeleport(vehicle,target,cause,7L)
                    : vehicle.teleportAsync(target,cause,TeleportFlag.EntityState.RETAIN_PASSENGERS);
            future.whenComplete((ok,error) -> on(p,5,() -> {
                require(error==null,"teleport exception "+error);
                require(Boolean.TRUE.equals(ok)==!deny,"teleport result "+mode+"="+ok);
                Location expected=deny || mode.equals("vehicle-dismount") ? home : mode.equals("redirect") ? redirect : target;
                require(p.getLocation().distanceSquared(expected)<9,"teleport final position "+mode+" "+p.getLocation());
                require(regular.get()==1,"one player event (DISMOUNT for unmounted passenger), got "+regular.get());
                require(nativeEvents.get()==(deny?0:1),"native notification only after acceptance");
                teleportMode="";teleportPlayer=null;manager.removeRegion(region.getId());
                if(attachment!=null) p.removeAttachment(attachment);
                if(vehicle!=null) vehicle.getScheduler().run(this,task -> { vehicle.eject();vehicle.remove(); },null);
                on(p,3,() -> p.teleportAsync(home).whenComplete((back,failure) -> on(p,2,() -> { require(Boolean.TRUE.equals(back)&&failure==null,"return home");pass("tp "+mode); })));
            }));
        });
    }
    CompletableFuture<Boolean> nativeTeleport(Entity entity,Location to,PlayerTeleportEvent.TeleportCause cause,long flags) {
        CompletableFuture<Boolean> future=new CompletableFuture<>();
        boolean accepted=((org.bukkit.craftbukkit.entity.CraftEntity)entity).getHandle().teleportAsync(
                ((org.bukkit.craftbukkit.CraftWorld)to.getWorld()).getHandle(),
                new net.minecraft.world.phys.Vec3(to.getX(),to.getY(),to.getZ()),to.getYaw(),to.getPitch(),
                net.minecraft.world.phys.Vec3.ZERO,cause,flags,result -> future.complete(result!=null));
        if(!accepted) future.complete(false);
        return future;
    }
    void nativeQueueTest(Player p,String kind) {
        Location home=p.getLocation().clone();var manager=manager(p);
        int x=40000+(int)(System.nanoTime()%10000)*16;
        var denied=region("regression_queue",x+30,x+30);
        denied.setFlag(kind.equals("pearl")?Flags.ENDERPEARL:Flags.ENTRY,StateFlag.State.DENY);manager.addRegion(denied);
        on(p,50,() -> {
            var first=nativeTeleport(p,new Location(p.getWorld(),x,-60,x),PlayerTeleportEvent.TeleportCause.PLUGIN,3L);
            try {
                Field pending=net.minecraft.world.entity.Entity.class.getDeclaredField("pendingAsyncTeleport");pending.setAccessible(true);
                require(pending.get(((org.bukkit.craftbukkit.entity.CraftEntity)p).getHandle())!=null,"forced pending native teleport");
            } catch(Exception e) {throw new IllegalStateException(e);}
            // Force the actual private queue deterministically; Bukkit's scheduler normally serializes requests.
            CompletableFuture<Boolean> second=new CompletableFuture<>();
            CompletableFuture<Boolean> third=new CompletableFuture<>();
            CompletableFuture<Boolean> fourth=new CompletableFuture<>();
            try {
                var handle=((org.bukkit.craftbukkit.entity.CraftEntity)p).getHandle();
                Class<?> queueType=Class.forName("net.minecraft.world.entity.Entity$QueuedAsyncTeleport");
                var constructor=queueType.getDeclaredConstructors()[0];constructor.setAccessible(true);
                Object queued=constructor.newInstance(handle,((org.bukkit.craftbukkit.CraftWorld)home.getWorld()).getHandle(),
                        new net.minecraft.world.phys.Vec3(x+30,-60,x+30),0f,0f,net.minecraft.world.phys.Vec3.ZERO,3L,
                        (java.util.function.Consumer<net.minecraft.world.entity.Entity>)(result -> second.complete(result!=null)));
                try {
                    Field cause=queueType.getDeclaredField("worldguardCause");cause.setAccessible(true);
                    cause.set(queued,kind.equals("pearl")?PlayerTeleportEvent.TeleportCause.ENDER_PEARL:PlayerTeleportEvent.TeleportCause.PLUGIN);
                } catch(NoSuchFieldException originalCore) { /* Baseline queue loses cause. */ }
                Field queue=net.minecraft.world.entity.Entity.class.getDeclaredField("pendingAsyncTeleportQueue");queue.setAccessible(true);
                ((java.util.ArrayDeque)queue.get(handle)).addLast(queued);
                for(var future:List.of(third,fourth)) {
                    Object next=constructor.newInstance(handle,((org.bukkit.craftbukkit.CraftWorld)home.getWorld()).getHandle(),
                            new net.minecraft.world.phys.Vec3(x+1,-60,x+1),0f,0f,net.minecraft.world.phys.Vec3.ZERO,3L,
                            (java.util.function.Consumer<net.minecraft.world.entity.Entity>)(result -> future.complete(result!=null)));
                    try {Field cause=queueType.getDeclaredField("worldguardCause");cause.setAccessible(true);cause.set(next,PlayerTeleportEvent.TeleportCause.PLUGIN);}
                    catch(NoSuchFieldException originalCore) { }
                    ((java.util.ArrayDeque)queue.get(handle)).addLast(next);
                }
            } catch(Exception e) {throw new IllegalStateException(e);}
            CompletableFuture.allOf(first,second,third,fourth).whenComplete((unused,error) -> on(p,5,() -> {
                require(error==null&&first.getNow(false)&&!second.getNow(true),"native queued "+kind+" futures: "+first.getNow(false)+"/"+second.getNow(true));
                require(third.getNow(false)&&fourth.getNow(false),"queue drains after denial and synchronous success");
                require(p.getLocation().getBlockX()==x+1,"native queue completes at last allowed destination");
                manager.removeRegion(denied.getId());p.teleportAsync(home).thenAccept(ok -> on(p,2,() -> {require(ok,"native queue return");pass("queue-native "+kind);}));
            }));
        });
    }
    void queueTest(Player p) {
        Location home=p.getLocation().clone();var manager=manager(p);var denied=region("regression_queue",2030,2030);
        denied.setFlag(Flags.ENTRY,StateFlag.State.DENY);manager.addRegion(denied);
        on(p,3,() -> {
            var first=p.teleportAsync(new Location(p.getWorld(),2000,-60,2000));
            var second=p.teleportAsync(new Location(p.getWorld(),2030,-60,2030));
            CompletableFuture.allOf(first,second).whenComplete((unused,error) -> on(p,5,() -> {
                require(error==null && first.getNow(false) && !second.getNow(true),"queued allowed/denied futures");
                require(p.getLocation().getBlockX()==2000,"queued denial keeps allowed destination");
                manager.removeRegion(denied.getId());p.teleportAsync(home).thenAccept(ok -> on(p,2,() -> {require(ok,"queue return");pass("queue");}));
            }));
        });
    }
    void metadata(Player p) {
        p.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        NamespacedKey key=new NamespacedKey("worldguard","parent_player");
        for(int i=0;i<40;i++) {
            LivingEntity cube=(LivingEntity)p.getWorld().spawnEntity(p.getLocation().add(2,0,0),EntityType.SULFUR_CUBE);
            require(cube.isValid(),"sulfur spawn");
            Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(p,cube,EquipmentSlot.HAND));
            require(p.getUniqueId().toString().equals(cube.getPersistentDataContainer().get(key,PersistentDataType.STRING)),"UUID attribution");
            require(Cause.create(cube).getRootCause()==p,"online player attribution preserved");
            require(!cube.hasMetadata("worldguard.cause"),"no server metadata entry");
            if(i%3==0) {
                p.getInventory().setItemInMainHand(new ItemStack(Material.SHEARS));
                Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(p,cube,EquipmentSlot.HAND));
                require(!cube.getPersistentDataContainer().has(key),"shears clears attribution");
                p.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
            }
            removedIds.add(cube.getUniqueId().toString());
            if(i%2==0) cube.remove(); else cube.setHealth(0);
        }
        on(p,100,() -> {
            try {
                Object store=Bukkit.getServer().getClass().getMethod("getEntityMetadata").invoke(Bukkit.getServer());
                Field field=store.getClass().getSuperclass().getDeclaredField("metadataMap");field.setAccessible(true);
                Map<?,?> map=(Map<?,?>)field.get(store);
                long retained=map.keySet().stream().map(Object::toString).filter(k -> k.contains("worldguard.cause")&&removedIds.stream().anyMatch(k::contains)).count();
                require(retained==0,"zero removed entity metadata: "+retained);pass("metadata removed="+removedIds.size()+" retained="+retained);
            } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
        });
    }
    void command(Player p,String name) {
        p.setHealth(5);p.setFoodLevel(1);deaths.set(0);drops.set(0);slayPlayer=name.equals("slay")?p.getUniqueId():null;
        if(name.equals("slay")) p.getInventory().setItemInMainHand(new ItemStack(Material.STONE,2));
        Path report=WorldGuardPlugin.inst().getDataFolder().toPath().resolve("report.txt");
        if(name.equals("report")) try {Files.deleteIfExists(report);} catch(Exception e){throw new IllegalStateException(e);}
        Bukkit.getGlobalRegionScheduler().run(this,t -> guarded(() -> {
            require(!Bukkit.isOwnedByCurrentRegion(p),"global command does not own target");
            String text=Set.of("reload","report").contains(name) ? "worldguard:worldguard "+name : "worldguard:"+name+" "+p.getName();
            require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(),text),"command found "+name);
            on(p,10,() -> {
                switch(name) {
                    case "heal" -> require(p.getHealth()==20&&p.getFoodLevel()==20,"heal completed");
                    case "slay" -> {require(p.isDead()&&deaths.get()==1&&drops.get()>0,"slay and drops completed");p.spigot().respawn();}
                    case "god","ungod" -> require(WorldGuard.getInstance().getPlatform().getSessionManager().get(WorldGuardPlugin.inst().wrapPlayer(p)).isInvincible(WorldGuardPlugin.inst().wrapPlayer(p))==name.equals("god"),"god state");
                    case "report" -> {try {require(Files.readString(report).contains("Unavailable on Folia"),"report generated with explicit omitted statistics");}catch(Exception e){throw new IllegalStateException(e);}}
                    case "reload" -> require(WorldGuardPlugin.inst().isEnabled(),"reload stays enabled");
                }
                on(p,5,() -> pass("commands "+name));
            });
        }));
    }
    void seed(Player p) {
        List<Entity> remove=new ArrayList<>(),keep=new ArrayList<>();
        for(int i=0;i<6;i++) remove.add(p.getWorld().spawnEntity(p.getLocation().add(2,0,0),EntityType.PIG));
        keep.add(p.getWorld().spawnEntity(p.getLocation().add(3,0,0),EntityType.ARMOR_STAND));
        Wolf wolf=(Wolf)p.getWorld().spawnEntity(p.getLocation().add(4,0,0),EntityType.WOLF);wolf.setOwner(p);keep.add(wolf);
        require(remove.stream().allMatch(Entity::isValid)&&keep.stream().allMatch(Entity::isValid),"seed spawned");
        mobs.put(p.getUniqueId(),remove);preserved.put(p.getUniqueId(),keep);pass("seed "+p.getName()+" region="+regionId());
    }
    void halt(Player caller) {
        require(mobs.size()==2,"two separated players seeded");
        Bukkit.getGlobalRegionScheduler().run(this,t -> guarded(() -> {
            require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"worldguard:halt-activity confirm -s"),"halt command");
            checkHalt();
        }));
    }
    void checkHalt() {
            AtomicInteger pending=new AtomicInteger(mobs.size());
            for(UUID id:mobs.keySet()) {
                Player p=Bukkit.getPlayer(id);require(p!=null,"test player online");
                on(p,20,() -> {
                    require(mobs.get(id).stream().noneMatch(Entity::isValid),"all six pigs removed on "+regionId());
                    require(preserved.get(id).stream().allMatch(Entity::isValid),"tamed wolf and armor stand preserved");
                    preserved.get(id).forEach(Entity::remove);
                    if(pending.decrementAndGet()==0) {
                        require(WorldGuardPlugin.inst().getConfigManager().activityHaltToggle,"halt still enabled");
                        Bukkit.getGlobalRegionScheduler().execute(this,() -> {Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"worldguard:halt-activity -c -s");pass("halt two regions");});
                    }
                });
            }
    }
    void spawnTest(Player p) {
        var manager=manager(p);int x=p.getLocation().getBlockX(),z=p.getLocation().getBlockZ();
        var region=new ProtectedCuboidRegion("regression_spawn",BlockVector3.at(x+1,-64,z-128),BlockVector3.at(x+128,320,z+128));
        region.setFlag(Flags.MOB_SPAWNING,StateFlag.State.DENY);manager.addRegion(region);
        WorldGuardPlugin.inst().getConfigManager().useRegionsPreCreatureSpawnEvent=true;
        preCancelled.set(0);
        on(p,5,() -> {
            Entity denied=p.getWorld().spawnEntity(p.getLocation().add(3,0,0),EntityType.PIG);
            Entity allowed=p.getWorld().spawnEntity(p.getLocation().add(-3,0,0),EntityType.PIG);
            require(!denied.isValid()&&allowed.isValid(),"final spawn protection denies only region");allowed.remove();
            on(p,200,() -> {
                try {
                    Object handle=p.getClass().getMethod("getHandle").invoke(p);
                    int total=Arrays.stream((int[])handle.getClass().getField("mobBackoffCounts").get(handle)).sum();
                    require(preCancelled.get()==0&&total==0,"no pre-cancellation/backoff "+preCancelled+"/"+total);
                    manager.removeRegion(region.getId());pass("spawn protected, backoff=0 with pre-setting=true");
                } catch(Exception e) {throw new IllegalStateException(e);}
            });
        });
    }
}
