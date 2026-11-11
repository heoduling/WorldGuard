package audit.spawn;

import audit.Counts;
import com.destroystokyo.paper.event.entity.EntityZapEvent;
import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

public final class SpawnProbe extends JavaPlugin implements Listener {
    private final Map<UUID, Boolean> cancelled = new HashMap<>();
    private UUID trackedPig;
    private Boolean zapCancelled;
    private Location home;

    @Override
    public void onEnable() {
        home = new Location(Bukkit.getWorld("world"), 64.5, 65, 64.5);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void fail(Throwable failure) {
        getLogger().log(java.util.logging.Level.SEVERE, "SPAWN_FAIL", failure);
    }

    private void at(Runnable action) {
        home.getWorld().getChunkAtAsync(home).thenAccept(chunk ->
            Bukkit.getRegionScheduler().run(this, home, task -> {
                try {
                    chunk.addPluginChunkTicket(this);
                    action.run();
                } catch (Throwable failure) {
                    fail(failure);
                }
            }));
    }

    private List<RegisteredListener> wg(HandlerList handlers) {
        return Arrays.stream(handlers.getRegisteredListeners())
                .filter(h -> h.getPlugin().getName().equals("WorldGuard")).toList();
    }

    private void state() {
        var plugin = WorldGuardPlugin.inst();
        getLogger().info("SPAWN_STATE version=" + plugin.getDescription().getVersion()
                + " config=" + plugin.getConfigManager().useRegionsPreCreatureSpawnEvent
                + " pre=" + wg(PreCreatureSpawnEvent.getHandlerList()).size()
                + " shared=" + PreCreatureSpawnEvent.getHandlerList().getRegisteredListeners().length
                + " zap=" + wg(EntityZapEvent.getHandlerList()).size()
                + " final=" + wg(CreatureSpawnEvent.getHandlerList()).size()
                + " landsId=" + System.identityHashCode(Bukkit.getPluginManager().getPlugin("Lands")));
        for (var item : PreCreatureSpawnEvent.getHandlerList().getRegisteredListeners()) {
            getLogger().info("SPAWN_HANDLER plugin=" + item.getPlugin().getName()
                    + " class=" + item.getListener().getClass().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void spawned(CreatureSpawnEvent event) {
        cancelled.put(event.getEntity().getUniqueId(), event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void zapped(EntityZapEvent event) {
        if (event.getEntity().getUniqueId().equals(trackedPig)) {
            zapCancelled = event.isCancelled();
        }
    }

    private void clearRules() {
        var manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(home.getWorld()));
        for (String id : List.copyOf(manager.getRegions().keySet())) {
            manager.removeRegion(id);
        }
        var global = WorldGuardPlugin.inst().getConfigManager();
        var world = global.get(home.getWorld().getName());
        global.activityHaltToggle = false;
        world.blockPluginSpawning = true;
        world.allowTamedSpawns = false;
        world.blockCreatureSpawn.clear();
        try {
            var field = com.sk89q.worldguard.protection.regions.RegionContainer.class.getDeclaredField("cache");
            field.setAccessible(true);
            ((com.sk89q.worldguard.protection.regions.QueryCache) field.get(WorldGuard.getInstance().getPlatform().getRegionContainer())).invalidateAll();
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private void spawnCase(String name) {
        at(() -> {
            clearRules();
            World world = home.getWorld();
            world.getBlockAt(64, 64, 64).setType(Material.STONE, false);
            var cfg = WorldGuardPlugin.inst().getConfigManager();
            var wcfg = cfg.get(world.getName());
            var region = new ProtectedCuboidRegion("spawn_test", BlockVector3.at(60, 0, 60), BlockVector3.at(70, 200, 70));
            boolean deny = name.contains("mob_deny") || name.startsWith("plugin_") || name.startsWith("tamed_") || name.equals("untamed");
            region.setFlag(Flags.MOB_SPAWNING, deny ? StateFlag.State.DENY : StateFlag.State.ALLOW);
            EntityType type = name.startsWith("tamed_") || name.equals("untamed") ? EntityType.WOLF : EntityType.COW;
            if (name.startsWith("deny_spawn")) {
                region.setFlag(Flags.DENY_SPAWN, Set.of(BukkitAdapter.adapt(name.equals("deny_spawn_other") ? EntityType.PIG : type)));
            }
            cfg.activityHaltToggle = name.startsWith("halt");
            wcfg.blockPluginSpawning = !name.equals("plugin_bypass") && !name.equals("halt_plugin_bypass");
            wcfg.allowTamedSpawns = name.equals("tamed_allow") || name.equals("untamed");
            if (name.equals("block_list")) {
                wcfg.blockCreatureSpawn.add(BukkitAdapter.adapt(type));
            }
            WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world)).addRegion(region);
            boolean expected = Set.of("mob_deny", "deny_spawn", "halt", "halt_plugin_bypass", "plugin_blocked", "tamed_blocked", "untamed", "block_list").contains(name);
            var reason = name.startsWith("plugin_") || name.equals("halt_plugin_bypass") ? CreatureSpawnEvent.SpawnReason.CUSTOM : CreatureSpawnEvent.SpawnReason.NATURAL;
            Entity entity = world.spawnEntity(home, type, reason, created -> {
                if (created instanceof LivingEntity living) {
                    living.setAI(false);
                    living.setGravity(false);
                }
                if (created instanceof Tameable tameable && name.startsWith("tamed_")) {
                    tameable.setTamed(true);
                }
            });
            Boolean observed = cancelled.remove(entity.getUniqueId());
            require(observed != null && observed == expected, name + " cancellation=" + observed + " expected=" + expected);
            Bukkit.getRegionScheduler().runDelayed(this, home, task -> {
                try {
                    require(entity.isValid() != expected, name + " entity validity=" + entity.isValid());
                    getLogger().info("SPAWN_CASE name=" + name + " cancelled=" + observed + " present=" + entity.isValid());
                    entity.remove();
                    clearRules();
                } catch (Throwable failure) {
                    fail(failure);
                }
            }, 3);
        });
    }

    private void lightning(boolean disable) {
        at(() -> {
            clearRules();
            var world = home.getWorld();
            for (Entity entity : world.getNearbyEntities(home, 8, 8, 8)) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }
            WorldGuardPlugin.inst().getConfigManager().get(world.getName()).disablePigZap = disable;
            world.getBlockAt(64, 64, 64).setType(Material.STONE, false);
            Pig pig = world.spawn(home, Pig.class, p -> {
                p.setAI(false);
                p.setGravity(false);
                p.setHealth(p.getMaxHealth());
            });
            trackedPig = pig.getUniqueId();
            zapCancelled = null;
            world.strikeLightning(home);
            Bukkit.getRegionScheduler().runDelayed(this, home, task -> {
                try {
                    long converted = world.getNearbyEntities(home, 8, 8, 8).stream()
                            .filter(e -> e.getType() == EntityType.ZOMBIFIED_PIGLIN).count();
                    require(zapCancelled != null && zapCancelled == disable, "native EntityZapEvent=" + zapCancelled);
                    require(disable ? pig.isValid() && converted == 0 : !pig.isValid() && converted == 1,
                            "native conversion pig=" + pig.isValid() + " converted=" + converted);
                    getLogger().info("SPAWN_ZAP disable=" + disable + " cancelled=" + zapCancelled + " pig=" + pig.isValid() + " converted=" + converted);
                    WorldGuardPlugin.inst().getConfigManager().get(world.getName()).disablePigZap = false;
                    trackedPig = null;
                } catch (Throwable failure) {
                    fail(failure);
                }
            }, 10);
        });
    }

    private void checkLoop(MethodHandle check, int count) throws Throwable {
        for (int index = 0; index < count; index++) {
            Object result = (Object) check.invokeExact();
            require(((Enum<?>) result).name().equals("FAIL"), "Unexpected core check result " + result);
        }
    }

    private void measure(String label) {
        at(() -> {
            try {
                ServerLevel level = ((CraftWorld) home.getWorld()).getHandle();
                Method target = NaturalSpawner.class.getDeclaredMethod("isValidSpawnPostitionForType", ServerLevel.class,
                        MobCategory.class, StructureManager.class, ChunkGenerator.class, MobSpawnSettings.SpawnerData.class,
                        BlockPos.MutableBlockPos.class, double.class);
                target.setAccessible(true);
                MethodHandle check = MethodHandles.insertArguments(MethodHandles.lookup().unreflect(target), 0,
                        level, MobCategory.CREATURE, level.structureManager(), level.getChunkSource().getGenerator(),
                        new MobSpawnSettings.SpawnerData(EntityTypes.COW, 1, 1), new BlockPos.MutableBlockPos(64, 65, 64), 1.0e10)
                        .asType(MethodType.methodType(Object.class));
                checkLoop(check, 20000);
                var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                Method carrierMethod = Thread.class.getDeclaredMethod("currentCarrierThread");
                carrierMethod.setAccessible(true);
                for (int round = 1; round <= 5; round++) {
                    Counts.start();
                    Thread carrier = (Thread) carrierMethod.invoke(null);
                    long before = mx.getThreadAllocatedBytes(carrier.threadId());
                    checkLoop(check, 100000);
                    long after = mx.getThreadAllocatedBytes(carrier.threadId());
                    boolean same = carrier == carrierMethod.invoke(null);
                    long[] counts = Counts.stop();
                    getLogger().info("SPAWN_MEASURE label=" + label + " round=" + round + " checks=100000 events=" + counts[0]
                            + " locations=" + counts[1] + " callbacks=" + counts[2] + " same=" + same + " bytes=" + (after-before));
                }
                getLogger().info("SPAWN_MEASURE_DONE label=" + label);
            } catch (Throwable failure) {
                fail(failure);
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (args[0]) {
            case "state" -> state();
            case "case" -> spawnCase(args[1]);
            case "zap" -> lightning(Boolean.parseBoolean(args[1]));
            case "measure" -> measure(args[1]);
        }
        return true;
    }
}
