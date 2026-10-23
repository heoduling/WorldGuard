package audit.origin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class OriginProbe extends JavaPlugin implements Listener {
    private final Map<UUID,String> tracked = new ConcurrentHashMap<>();
    private final Map<String,Location> destinations = new ConcurrentHashMap<>();
    private int pistonEvents;
    public void onEnable() { Bukkit.getPluginManager().registerEvents(this,this); }
    private void fail(Throwable ex) { getLogger().log(java.util.logging.Level.SEVERE,"ORIGIN_FAIL",ex); }
    private CompletableFuture<Void> at(Location loc, Runnable action) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        loc.getWorld().getChunkAtAsync(loc).whenComplete((chunk,ex)-> {
            if(ex!=null){done.completeExceptionally(ex);fail(ex);return;}
            Bukkit.getRegionScheduler().run(this,loc,task->{
                try {chunk.addPluginChunkTicket(this); action.run();done.complete(null);}
                catch(Throwable problem){done.completeExceptionally(problem);fail(problem);}
            });
        });
        return done;
    }
    private void region(Location loc, boolean denyPlace) {
        var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
        var region=new ProtectedCuboidRegion("origin_"+loc.getBlockX()+"_"+loc.getBlockZ(),
            BlockVector3.at(loc.getBlockX()-6,0,loc.getBlockZ()-6), BlockVector3.at(loc.getBlockX()+6,200,loc.getBlockZ()+6));
        region.setFlag(Flags.BUILD,StateFlag.State.ALLOW);
        if(denyPlace)region.setFlag(Flags.BLOCK_PLACE,StateFlag.State.DENY);
        manager.addRegion(region);
    }
    private void platform(Location loc) {
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) {
            loc.getWorld().getBlockAt(loc.getBlockX()+x,80,loc.getBlockZ()+z).setType(Material.BEDROCK,false);
            for(int y=81;y<=100;y++)loc.getWorld().getBlockAt(loc.getBlockX()+x,y,loc.getBlockZ()+z).setType(Material.AIR,false);
        }
    }
    private void spawn(String tag, String mode, boolean deny, Material material) {
        World overworld=Bukkit.getWorld("world"), end=Bukkit.getWorld("world_the_end");
        Location source=new Location(overworld,-4949.5,90,-5798.5);
        Location target=switch(mode) {
            case "cross" -> new Location(end,176.5,90,-47.5);
            case "far" -> new Location(overworld,12000.5,90,12000.5);
            default -> new Location(overworld,208.5,90,208.5);
        };
        if(mode.equals("local"))source=target.clone();
        final Location start=source;
        destinations.put(tag,target.clone().add(0,-9,0));
        at(target,()-> {
            platform(target);region(target,deny);
            if(material==Material.WHITE_CONCRETE_POWDER)target.getWorld().getBlockAt(target.getBlockX(),81,target.getBlockZ()).setType(Material.WATER,false);
        }).thenRun(()->at(start,()-> {
            FallingBlock falling=overworld.spawnFallingBlock(start,material.createBlockData());
            falling.setDropItem(true);falling.setGravity(false);falling.setVelocity(new Vector());
            tracked.put(falling.getUniqueId(),tag);
            if(mode.equals("local")){ready(tag,falling);return;}
            falling.teleportAsync(target).whenComplete((ok,ex)-> {
                if(ex!=null || !Boolean.TRUE.equals(ok)){fail(ex!=null?ex:new AssertionError("teleport rejected"));return;}
                falling.getScheduler().run(this,task->ready(tag,falling),()->getLogger().severe("ORIGIN_FAIL retired "+tag));
            });
        }));
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void breakDelegate(BreakBlockEvent event) {
        if(event.getOriginalEvent() instanceof EntityChangeBlockEvent original) {
            String tag=tracked.get(original.getEntity().getUniqueId());
            if(tag!=null)getLogger().info("ORIGIN_BREAK tag="+tag+" result="+event.getResult());
        }
    }
    private void ready(String tag,FallingBlock falling) {
        try {
            Cause cause=Cause.create(falling);
            Location origin=falling.getOrigin();
            boolean owned=Bukkit.isOwnedByCurrentRegion(origin);
            boolean readable=cause.getFirstBlock()!=null;
            boolean rootBlock=cause.getRootCause() instanceof Block;
            boolean preserved=rootBlock && ((Block)cause.getRootCause()).getLocation().equals(origin.getBlock().getLocation());
            getLogger().info("ORIGIN_CAUSE tag="+tag+" world="+falling.getWorld().getName()+" originWorld="+origin.getWorld().getName()+" owned="+owned+" readable="+readable+" preserved="+preserved+" source="+origin.getBlockX()+","+origin.getBlockY()+","+origin.getBlockZ());
            falling.setGravity(true);
        }catch(Throwable ex){fail(ex);}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void change(EntityChangeBlockEvent event) {
        String tag=tracked.get(event.getEntity().getUniqueId());
        if(tag!=null && event.getTo()!=Material.AIR) {
            getLogger().info("ORIGIN_EVENT tag="+tag+" world="+event.getBlock().getWorld().getName()+" to="+event.getTo()+" cancelled="+event.isCancelled());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void piston(BlockPistonExtendEvent event) {
        if(event.getBlock().getX()==256 && event.getBlock().getZ()==256){pistonEvents++;getLogger().info("ORIGIN_PISTON_EVENT cancelled="+event.isCancelled());}
    }
    private void inspect(String tag) {
        Location loc=destinations.get(tag);
        at(loc,()->getLogger().info("ORIGIN_BLOCK tag="+tag+" type="+loc.getBlock().getType()));
    }
    private void piston(boolean deny) {
        Location loc=new Location(Bukkit.getWorld("world"),256,81,256);
        at(loc,()-> {
            var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
            var r=new ProtectedCuboidRegion("origin_piston",BlockVector3.at(250,0,250),BlockVector3.at(265,200,265));
            r.setFlag(Flags.BUILD,StateFlag.State.ALLOW);r.setFlag(Flags.PISTONS,deny?StateFlag.State.DENY:StateFlag.State.ALLOW);manager.addRegion(r);
            for(int x=254;x<=260;x++)for(int y=81;y<=83;y++)loc.getWorld().getBlockAt(x,y,256).setType(Material.AIR,false);
            Block b=loc.getBlock();b.setType(Material.PISTON,false);
            Directional data=(Directional)b.getBlockData();data.setFacing(org.bukkit.block.BlockFace.EAST);b.setBlockData(data,false);
            b.getRelative(org.bukkit.block.BlockFace.EAST).setType(Material.STONE,false);
            b.getRelative(org.bukkit.block.BlockFace.WEST).setType(Material.REDSTONE_BLOCK,true);
            Bukkit.getRegionScheduler().runDelayed(this,loc,task->getLogger().info("ORIGIN_PISTON_RESULT deny="+deny+" near="+b.getRelative(org.bukkit.block.BlockFace.EAST).getType()+" far="+b.getRelative(org.bukkit.block.BlockFace.EAST,2).getType()+" events="+pistonEvents),20);
        });
    }
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        try {
            switch(args[0]) {
                case "fall" -> spawn(args[1],args[2],Boolean.parseBoolean(args[3]),Material.valueOf(args[4]));
                case "inspect" -> inspect(args[1]);
                case "piston" -> piston(Boolean.parseBoolean(args[1]));
                case "state" -> {
                    var wg=Bukkit.getPluginManager().getPlugin("WorldGuard");
                    var lands=Bukkit.getPluginManager().getPlugin("Lands");
                    getLogger().info("ORIGIN_STATE wg="+wg.getDescription().getVersion()+" listeners="+HandlerList.getRegisteredListeners(wg).size()+" lands="+lands.isEnabled()+" landsId="+System.identityHashCode(lands)+" players="+Bukkit.getOnlinePlayers().size());
                }
            }
        } catch(Throwable ex){fail(ex);}
        return true;
    }
}
