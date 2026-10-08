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
    final AtomicInteger assertions = new AtomicInteger();
    final AtomicInteger preCancelled = new AtomicInteger(), deaths = new AtomicInteger(), drops = new AtomicInteger();
    final Set<String> removedIds = new HashSet<>();
    final Map<UUID,List<Entity>> mobs = new ConcurrentHashMap<>();
    final Map<UUID,List<Entity>> preserved = new ConcurrentHashMap<>();
    volatile UUID slayPlayer;
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
