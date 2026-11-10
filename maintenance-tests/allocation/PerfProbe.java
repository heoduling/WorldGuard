package audit.perf;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent;
import com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent;
import com.sk89q.worldguard.bukkit.listener.EventAbstractionListener;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.file.*;
import jdk.jfr.*;
import jdk.jfr.consumer.*;

public final class PerfProbe extends JavaPlugin implements Listener {
    private EventAbstractionListener abstraction;
    private Location home;
    private int nativeEvents,nativeCancelled;
    private String nativeLabel="";
    private static final String PREFIX="PERF_";
    public void onEnable(){
        Bukkit.getPluginManager().registerEvents(this,this);
        for(var h:InventoryMoveItemEvent.getHandlerList().getRegisteredListeners())if(h.getListener() instanceof EventAbstractionListener a){abstraction=a;break;}
        home=new Location(Bukkit.getWorld("world"),8,65,8);
    }
    private void fail(Throwable ex){getLogger().log(java.util.logging.Level.SEVERE,PREFIX+"FAIL",ex);}
    private CompletableFuture<Void> at(Location loc,Runnable work){
        var done=new CompletableFuture<Void>();
        loc.getWorld().getChunkAtAsync(loc).whenComplete((chunk,ex)->{
            if(ex!=null){done.completeExceptionally(ex);fail(ex);return;}
            Bukkit.getRegionScheduler().run(this,loc,t->{try{chunk.addPluginChunkTicket(this);work.run();done.complete(null);}catch(Throwable e){fail(e);done.completeExceptionally(e);}});
        });return done;
    }
    private void clearDebounce(){try{
        var f=EventAbstractionListener.class.getDeclaredField("moveItemDebounce");f.setAccessible(true);Object debounce=f.get(abstraction);
        var cache=debounce.getClass().getSuperclass().getDeclaredField("cache");cache.setAccessible(true);
        Object value=cache.get(debounce);
        ((com.google.common.cache.Cache<?,?>)value).invalidateAll();
    }catch(Throwable e){throw new RuntimeException(e);}}
    private void clearPistons(){try{
        for(String name:List.of("pistonExtendDebounce","pistonRetractDebounce")){
            var field=EventAbstractionListener.class.getDeclaredField(name);field.setAccessible(true);Object debounce=field.get(abstraction);
            var cache=debounce.getClass().getDeclaredField("cache");cache.setAccessible(true);((com.google.common.cache.Cache<?,?>)cache.get(debounce)).invalidateAll();
        }
    }catch(Exception e){throw new RuntimeException(e);}}
    private void clearArea(int x){
        World w=home.getWorld();
        for(int dx=0;dx<8;dx++)for(int dz=0;dz<4;dz++)for(int y=64;y<69;y++)w.getBlockAt(x+dx,y,8+dz).setType(y==64?Material.STONE:Material.AIR,false);
    }
    private void region(String id,int x,StateFlag flag,StateFlag.State state){
        var r=new ProtectedCuboidRegion(id,BlockVector3.at(x-1,0,7),BlockVector3.at(x+8,200,12));
        r.setFlag(Flags.BUILD,StateFlag.State.ALLOW);r.setFlag(flag,state);
        var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(home.getWorld()));
        manager.addRegion(r);
        // Test isolation: avoid carrying a previous case's query results into this case.
        try{var f=com.sk89q.worldguard.protection.regions.RegionContainer.class.getDeclaredField("cache");f.setAccessible(true);((com.sk89q.worldguard.protection.regions.QueryCache)f.get(WorldGuard.getInstance().getPlatform().getRegionContainer())).invalidateAll();}catch(Throwable e){throw new RuntimeException(e);}
    }
    private Inventory[] inventories(int x,String kind,boolean push){
        clearArea(x);World w=home.getWorld();
        Block hopper=w.getBlockAt(x,65,8);hopper.setType(Material.HOPPER,false);
        Block chest=w.getBlockAt(x+1,65,8);chest.setType(Material.CHEST,false);
        if(kind.equals("double")){
            Block right=chest.getRelative(BlockFace.EAST);right.setType(Material.CHEST,false);
            var leftData=(org.bukkit.block.data.type.Chest)chest.getBlockData();leftData.setFacing(BlockFace.NORTH);leftData.setType(org.bukkit.block.data.type.Chest.Type.LEFT);chest.setBlockData(leftData,false);
            var rightData=(org.bukkit.block.data.type.Chest)right.getBlockData();rightData.setFacing(BlockFace.NORTH);rightData.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);right.setBlockData(rightData,false);
        }
        Inventory hi=((Hopper)hopper.getState(false)).getInventory();
        if(kind.equals("minecart")){
            HopperMinecart cart=w.spawn(new Location(w,x+.5,65.5,10.5),HopperMinecart.class);cart.setGravity(false);hi=cart.getInventory();
        }
        Inventory ci=((Chest)chest.getState(false)).getInventory();
        if(kind.equals("double") && (ci.getSize()!=54 || !(ci.getHolder(false) instanceof DoubleChest)))throw new AssertionError("double-chest fixture not connected");
        hi.clear();ci.clear();
        return push?new Inventory[]{hi,ci,hi}:new Inventory[]{ci,hi,hi};
    }
    private Inventory counted(Inventory inv,long[] calls){
        return (Inventory)Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{Inventory.class},(proxy,method,args)->{
            if(method.getName().equals("getHolder"))calls[0]++;
            try{return method.invoke(inv,args);}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    private void matrix(){at(home,()->{
        resetRegions();
        int index=0;
        var cfg=WorldGuardPlugin.inst().getConfigManager().get(home.getWorld().getName());
        cfg.ignoreHopperMoveEvents=false;cfg.breakDeniedHoppers=false;
        for(String kind:List.of("single","double","minecart"))for(boolean push:List.of(true,false))for(boolean deny:List.of(false,true)){
            int x=8;Inventory[] inv=inventories(x,kind,push);region("hopper_case",x,Flags.CHEST_ACCESS,deny?StateFlag.State.DENY:StateFlag.State.ALLOW);clearDebounce();
            long[] calls={0};Inventory source=counted(inv[0],calls),dest=counted(inv[1],calls);
            var event=new InventoryMoveItemEvent(source,new ItemStack(Material.STONE),dest,push);
            Bukkit.getPluginManager().callEvent(event);long miss=calls[0];boolean first=event.isCancelled();
            event.setCancelled(false);calls[0]=0;Bukkit.getPluginManager().callEvent(event);long hit=calls[0];
            if(first!=deny||event.isCancelled()!=deny)throw new AssertionError("hopper cancellation mismatch "+kind+push+deny);
            getLogger().info(PREFIX+"HOLDER kind="+kind+" push="+push+" deny="+deny+" miss="+miss+" hit="+hit+" cancelled="+first);
            cfg.ignoreHopperMoveEvents=true;calls[0]=0;event.setCancelled(false);Bukkit.getPluginManager().callEvent(event);
            if(calls[0]!=1||event.isCancelled())throw new AssertionError("ignore changed");cfg.ignoreHopperMoveEvents=false;
            if(kind.equals("minecart"))((Entity)inv[2].getHolder(false)).remove();
            index++;
        }
        getLogger().info(PREFIX+"MATRIX_DONE cases="+index);
    });}
    private void adaptCheck(){at(home,()->{
        clearArea(8);
        var event=new PlaceBlockEvent(null,Cause.unknown(),home.getWorld(),new ArrayList<>(List.of(home.getBlock())),Material.STONE);
        Method method=null;
        try{method=event.getClass().getMethod("toWorldEditLocation",Location.class);method.setAccessible(true);}catch(NoSuchMethodException ignored){}
        Object first=null;boolean shared=true,coordinates=true;
        for(int i=0;i<12;i++){
            Location loc=new Location(home.getWorld(),8.25+i,65.75,8.5,37,12);
            try{
                var adapted=method==null?BukkitAdapter.adapt(loc):(com.sk89q.worldedit.util.Location)method.invoke(event,loc);
                if(first==null)first=adapted.getExtent();else shared&=first==adapted.getExtent();
                coordinates&=adapted.getX()==loc.getX()&&adapted.getY()==loc.getY()&&adapted.getZ()==loc.getZ()&&adapted.getYaw()==loc.getYaw()&&adapted.getPitch()==loc.getPitch();
            }catch(Exception e){throw new RuntimeException(e);}
        }
        if(!coordinates)throw new AssertionError("location adapted incorrectly");
        getLogger().info(PREFIX+"ADAPT shared="+shared+" coordinates="+coordinates);
    });}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void move(InventoryMoveItemEvent event){
        if(!nativeLabel.isEmpty()){nativeEvents++;if(event.isCancelled())nativeCancelled++;}
    }
    private int stones(Inventory inv){int total=0;for(ItemStack item:inv.getContents())if(item!=null&&item.getType()==Material.STONE)total+=item.getAmount();return total;}
    private void resetRegions(){
        var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(home.getWorld()));
        for(String id:List.copyOf(manager.getRegions().keySet()))manager.removeRegion(id);
    }
    private void nativeFlow(String kind,boolean push,String denySide,boolean breaking,boolean ignore){at(home,()->{
        resetRegions();clearDebounce();clearArea(8);
        World w=home.getWorld();for(Entity e:w.getNearbyEntities(home,7,7,7))if(e instanceof Item||e instanceof org.bukkit.entity.Minecart)e.remove();
        var cfg=WorldGuardPlugin.inst().getConfigManager().get(w.getName());cfg.ignoreHopperMoveEvents=ignore;cfg.breakDeniedHoppers=breaking;
        var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w));
        var base=new ProtectedCuboidRegion("native_base",BlockVector3.at(0,0,0),BlockVector3.at(20,200,20));base.setFlag(Flags.BUILD,StateFlag.State.ALLOW);base.setFlag(Flags.CHEST_ACCESS,StateFlag.State.ALLOW);manager.addRegion(base);
        boolean mine=kind.equals("minecart");
        Block hopper=w.getBlockAt(8,65,8);hopper.setType(mine?Material.RAIL:Material.HOPPER,false);
        if(!mine){var data=(org.bukkit.block.data.type.Hopper)hopper.getBlockData();data.setFacing(push?BlockFace.EAST:BlockFace.DOWN);data.setEnabled(true);hopper.setBlockData(data,false);}
        Block chest=w.getBlockAt(push?9:8,push?65:66,8);chest.setType(Material.CHEST,false);
        if(kind.equals("double")){
            Block right=chest.getRelative(BlockFace.EAST);right.setType(Material.CHEST,false);
            var d=(org.bukkit.block.data.type.Chest)chest.getBlockData();d.setFacing(BlockFace.NORTH);d.setType(org.bukkit.block.data.type.Chest.Type.LEFT);chest.setBlockData(d,false);
            var d2=(org.bukkit.block.data.type.Chest)right.getBlockData();d2.setFacing(BlockFace.NORTH);d2.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);right.setBlockData(d2,false);
        }
        Inventory ci=((Chest)chest.getState(false)).getInventory();ci.clear();
        if(kind.equals("double")&&ci.getSize()!=54)throw new AssertionError("native double-chest not connected");
        HopperMinecart cart=mine?w.spawn(new Location(w,8.5,65.0625,8.5),HopperMinecart.class):null;
        if(cart!=null){cart.setMaxSpeed(0);cart.setVelocity(new org.bukkit.util.Vector());}
        Inventory hi=mine?cart.getInventory():((Hopper)hopper.getState(false)).getInventory();hi.clear();
        if(!denySide.equals("none")){
            Block denied=denySide.equals("right")?chest.getRelative(BlockFace.EAST):chest;
            var deny=new ProtectedCuboidRegion("native_deny",BlockVector3.at(denied.getX(),denied.getY(),denied.getZ()),BlockVector3.at(denied.getX(),denied.getY(),denied.getZ()));
            deny.setPriority(10);deny.setFlag(Flags.CHEST_ACCESS,StateFlag.State.DENY);manager.addRegion(deny);
        }
        // Invalidate the previous region layout, retaining the real event cache rules.
        try{var f=com.sk89q.worldguard.protection.regions.RegionContainer.class.getDeclaredField("cache");f.setAccessible(true);((com.sk89q.worldguard.protection.regions.QueryCache)f.get(WorldGuard.getInstance().getPlatform().getRegionContainer())).invalidateAll();}catch(Exception e){throw new RuntimeException(e);}
        (push?hi:ci).setItem(0,new ItemStack(Material.STONE,16));nativeEvents=0;nativeCancelled=0;
        nativeLabel=kind+"_"+push+"_"+denySide+"_"+breaking+"_"+ignore;
        final String label=nativeLabel;final boolean denied=!denySide.equals("none")&&!ignore;
        Bukkit.getRegionScheduler().runAtFixedRate(this,home,new Consumer<>(){
            int ticks;
            public void accept(io.papermc.paper.threadedregions.scheduler.ScheduledTask task){try{
                ticks++;
                int inHopper=hopper.getType()==Material.AIR?0:stones(hi),inChest=stones(ci),dropped=0;
                for(Entity e:w.getNearbyEntities(home,7,7,7))if(e instanceof Item item&&item.getItemStack().getType()==Material.STONE)dropped+=item.getItemStack().getAmount();
                int dest=push?inChest:inHopper;
                boolean finished=denied?(breaking&&!mine?hopper.getType()==Material.AIR&&(push?dropped==16:inChest==16):ticks>=40):dest==16;
                if(!finished&&ticks<240)return;
                task.cancel();
                if(inHopper+inChest+dropped!=16)throw new AssertionError("item conservation "+label+" "+inHopper+","+inChest+","+dropped);
                if(nativeEvents==0||dest!=(denied?0:16)||nativeCancelled==(denied?0:-1)||ticks>=240)throw new AssertionError("native transfer outcome "+label+" events="+nativeEvents+" dest="+dest+" ticks="+ticks);
                if(denied&&breaking&&!mine&&hopper.getType()!=Material.AIR)throw new AssertionError("denied hopper was not broken");
                getLogger().info(PREFIX+"NATIVE_DONE label="+label+" source="+(push?inHopper:inChest)+" destination="+dest+" dropped="+dropped+" total="+(inHopper+inChest+dropped)+" events="+nativeEvents+" cancelled="+nativeCancelled);
                nativeLabel="";cfg.ignoreHopperMoveEvents=false;cfg.breakDeniedHoppers=false;if(cart!=null)cart.remove();
            }catch(Throwable e){task.cancel();nativeLabel="";fail(e);}}
        },1,1);
    });}
    private void materialKey(){at(home,()->{
        Inventory[] inv=inventories(8,"single",true);region("hopper_case",8,Flags.CHEST_ACCESS,StateFlag.State.ALLOW);clearDebounce();
        var first=new InventoryMoveItemEvent(inv[0],new ItemStack(Material.STONE),inv[1],true);Bukkit.getPluginManager().callEvent(first);
        if(first.isCancelled())throw new AssertionError("material baseline");
        Block target=home.getWorld().getBlockAt(9,65,8);target.setType(Material.BARREL,false);
        region("hopper_case",8,Flags.CHEST_ACCESS,StateFlag.State.DENY);
        var second=new InventoryMoveItemEvent(inv[0],new ItemStack(Material.STONE),((Barrel)target.getState(false)).getInventory(),true);Bukkit.getPluginManager().callEvent(second);
        if(!second.isCancelled())throw new AssertionError("material change reused stale debounce result");
        getLogger().info(PREFIX+"MATERIAL_DONE changedKey=true denied=true");
    });}
    private void pistonMatrix(){at(home,()->{
        int cases=0;
        for(boolean retract:List.of(false,true))for(String scenario:List.of("allow","source","target","piston","high_allow","high_deny","parent_deny","child_allow","global_deny","global_override")){
            resetRegions();clearPistons();clearArea(0);
            var manager=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(home.getWorld()));
            var base=new ProtectedCuboidRegion("base",BlockVector3.at(0,0,0),BlockVector3.at(15,200,15));
            base.setFlag(Flags.BUILD,StateFlag.State.ALLOW);base.setFlag(Flags.PISTONS,StateFlag.State.ALLOW);manager.addRegion(base);
            int sourceX=3,targetX=retract?2:4;
            if(scenario.equals("source")||scenario.equals("target")){
                int x=scenario.equals("source")?sourceX:targetX;
                var r=new ProtectedCuboidRegion("border",BlockVector3.at(x,0,8),BlockVector3.at(x,200,8));r.setPriority(20);
                r.setFlag(scenario.equals("source")?Flags.BLOCK_BREAK:Flags.BLOCK_PLACE,StateFlag.State.DENY);manager.addRegion(r);
            }else if(scenario.equals("piston")){base.setFlag(Flags.PISTONS,StateFlag.State.DENY);
            }else if(scenario.startsWith("high_")){
                base.setFlag(Flags.PISTONS,scenario.equals("high_allow")?StateFlag.State.DENY:StateFlag.State.ALLOW);
                var high=new ProtectedCuboidRegion("high",BlockVector3.at(0,0,0),BlockVector3.at(15,200,15));high.setPriority(30);
                high.setFlag(Flags.PISTONS,scenario.equals("high_allow")?StateFlag.State.ALLOW:StateFlag.State.DENY);manager.addRegion(high);
            }else if(scenario.equals("parent_deny")||scenario.equals("child_allow")){
                var parent=new ProtectedCuboidRegion("parent",BlockVector3.at(0,0,0),BlockVector3.at(15,200,15));parent.setFlag(Flags.PISTONS,StateFlag.State.DENY);parent.setPriority(10);manager.addRegion(parent);
                var child=new ProtectedCuboidRegion("child",BlockVector3.at(0,0,0),BlockVector3.at(15,200,15));child.setPriority(20);
                try{child.setParent(parent);}catch(Exception e){throw new RuntimeException(e);}
                if(scenario.equals("child_allow"))child.setFlag(Flags.PISTONS,StateFlag.State.ALLOW);manager.addRegion(child);
            }else if(scenario.startsWith("global_")){
                var global=new com.sk89q.worldguard.protection.regions.GlobalProtectedRegion("__global__");global.setFlag(Flags.BLOCK_PLACE,StateFlag.State.DENY);manager.addRegion(global);
                if(scenario.equals("global_override"))base.setFlag(Flags.BLOCK_PLACE,StateFlag.State.ALLOW);
            }
            try{var f=com.sk89q.worldguard.protection.regions.RegionContainer.class.getDeclaredField("cache");f.setAccessible(true);((com.sk89q.worldguard.protection.regions.QueryCache)f.get(WorldGuard.getInstance().getPlatform().getRegionContainer())).invalidateAll();}catch(Exception e){throw new RuntimeException(e);}
            Block piston=home.getWorld().getBlockAt(1,65,8);piston.setType(retract?Material.STICKY_PISTON:Material.PISTON,false);
            Block source=home.getWorld().getBlockAt(sourceX,65,8);source.setType(Material.STONE,false);
            org.bukkit.event.block.BlockPistonEvent event=retract?new BlockPistonRetractEvent(piston,List.of(source),BlockFace.WEST):new BlockPistonExtendEvent(piston,List.of(source),BlockFace.EAST);
            Bukkit.getPluginManager().callEvent(event);
            boolean deny=List.of("source","target","piston","high_deny","parent_deny","global_deny").contains(scenario);
            if(event.isCancelled()!=deny)throw new AssertionError("piston mismatch "+retract+" "+scenario+" "+event.isCancelled());
            getLogger().info(PREFIX+"PISTON retract="+retract+" scenario="+scenario+" cancelled="+event.isCancelled());cases++;
        }
        resetRegions();getLogger().info(PREFIX+"PISTON_DONE cases="+cases);
    });}
    private void parallel(){
        var finished=new java.util.concurrent.atomic.AtomicInteger();
        for(int x:List.of(8,4104)){
            Location own=new Location(home.getWorld(),x,65,8),other=new Location(home.getWorld(),x==8?4104:8,65,8);
            at(own,()->{
                // Load both distant topologies before checking actual ownership.
            }).thenRun(()->at(other,()->{})).thenRun(()->at(own,()->{
                if(Bukkit.isOwnedByCurrentRegion(other))throw new AssertionError("parallel regions merged");
                Inventory[] inv=inventories(x,"single",true);region("parallel_"+x,x,Flags.CHEST_ACCESS,StateFlag.State.ALLOW);
                for(int i=0;i<1000;i++){
                    var e=new InventoryMoveItemEvent(inv[0],new ItemStack(Material.STONE),inv[1],true);Bukkit.getPluginManager().callEvent(e);
                    if(e.isCancelled())throw new AssertionError("parallel denial");
                }
                getLogger().info(PREFIX+"PARALLEL x="+x+" otherOwned=false events=1000");
                if(finished.incrementAndGet()==2)getLogger().info(PREFIX+"PARALLEL_DONE events=2000");
            }));
        }
    }
    private void actor(Player player){
        player.teleportAsync(new Location(home.getWorld(),8.5,66,8.5)).whenComplete((ok,problem)->{
            if(problem!=null||!Boolean.TRUE.equals(ok)){fail(problem!=null?problem:new AssertionError("actor teleport"));return;}
            player.getScheduler().run(this,t->{try{
                resetRegions();clearArea(8);World w=home.getWorld();
                var r=new ProtectedCuboidRegion("actor",BlockVector3.at(0,0,0),BlockVector3.at(15,200,15));
                r.setFlag(Flags.BUILD,StateFlag.State.ALLOW);r.setFlag(Flags.BLOCK_PLACE,StateFlag.State.DENY);
                WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w)).addRegion(r);
                var local=WorldGuardPlugin.inst().wrapPlayer(player);
                var sessions=WorldGuard.getInstance().getPlatform().getSessionManager();
                var permission=player.addAttachment(this,"worldguard.region.bypass.world",false);
                var first=new PlaceBlockEvent(null,Cause.create(player),home,Material.STONE);Bukkit.getPluginManager().callEvent(first);
                if(!first.isCancelled())throw new AssertionError("actor normal deny");
                permission.setPermission("worldguard.region.bypass.world",true);
                player.getScheduler().runDelayed(this,task->{try{
                    var bypass=new PlaceBlockEvent(null,Cause.create(player),home,Material.STONE);Bukkit.getPluginManager().callEvent(bypass);
                    if(bypass.isCancelled())throw new AssertionError("actor bypass denied");
                    player.removeAttachment(permission);getLogger().info(PREFIX+"ACTOR_DONE normalDenied=true bypassAllowed=true");
                }catch(Throwable ex){fail(ex);}},()->getLogger().severe(PREFIX+"FAIL bypass actor retired"),45);
            }catch(Throwable ex){fail(ex);}},()->getLogger().severe(PREFIX+"FAIL actor retired"));
        });
    }
    private void hopperLoop(InventoryMoveItemEvent event,int count){
        for(int i=0;i<count;i++){event.setCancelled(false);Bukkit.getPluginManager().callEvent(event);if(event.isCancelled())throw new AssertionError("benchmark event unexpectedly denied");}
    }
    private void bulkLoop(Block piston,List<Block> blocks,int count){
        for(int i=0;i<count;i++){
            var event=new BreakBlockEvent(null,Cause.create(piston),piston.getWorld(),new ArrayList<>(blocks),Material.AIR);
            Bukkit.getPluginManager().callEvent(event);
            if(event.isCancelled()||event.getBlocks().size()!=blocks.size())throw new AssertionError("benchmark bulk denied");
        }
    }
    private void bench(String kind,String label,int rounds,int count){
        var job=new CompletableFuture<Runnable>();
        at(home,()->{
            resetRegions();clearArea(8);region("hopper_case",8,Flags.CHEST_ACCESS,StateFlag.State.ALLOW);
            var r=new ProtectedCuboidRegion("bench",BlockVector3.at(0,0,0),BlockVector3.at(31,200,31));
            r.setFlag(Flags.BUILD,StateFlag.State.ALLOW);r.setFlag(Flags.PISTONS,StateFlag.State.ALLOW);
            WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(home.getWorld())).addRegion(r);
            Runnable loop;
            if(kind.equals("hopper")){
                Inventory[] inv=inventories(8,"single",true);clearDebounce();
                var event=new InventoryMoveItemEvent(inv[0],new ItemStack(Material.STONE),inv[1],true);
                hopperLoop(event,100000);loop=()->hopperLoop(event,count);
            }else{
                Block piston=home.getWorld().getBlockAt(1,65,8);piston.setType(Material.PISTON,false);
                List<Block> blocks=new ArrayList<>();for(int x=2;x<14;x++){Block b=home.getWorld().getBlockAt(x,65,8);b.setType(Material.STONE,false);blocks.add(b);}
                bulkLoop(piston,blocks,10000);loop=()->bulkLoop(piston,blocks,count);
            }
            job.complete(loop);
        });
        job.thenAccept(loop->round(kind,label,rounds,count,loop,1));
    }
    private void round(String kind,String label,int rounds,int count,Runnable loop,int round){
        Bukkit.getAsyncScheduler().runNow(this,ignored->{
            try{
                Recording recording=new Recording();recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle","off");recording.start();
                long begin=System.nanoTime();
                at(home,()->{
                    var mx=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
                    Thread carrier=null;
                    Method getCarrier=null;
                    try{getCarrier=Thread.class.getDeclaredMethod("currentCarrierThread");getCarrier.setAccessible(true);carrier=(Thread)getCarrier.invoke(null);}catch(Exception unavailable){}
                    long carrierBefore=carrier==null?-1:mx.getThreadAllocatedBytes(carrier.threadId());
                    long allocatedBefore=mx.getCurrentThreadAllocatedBytes();loop.run();long allocatedAfter=mx.getCurrentThreadAllocatedBytes();
                    long carrierAfter=carrier==null?-1:mx.getThreadAllocatedBytes(carrier.threadId());
                    boolean sameCarrier=false;
                    try{sameCarrier=carrier!=null&&getCarrier.invoke(null)==carrier;}catch(Exception ignoredCarrier){}
                    getLogger().info(PREFIX+"MX virtual="+Thread.currentThread().isVirtual()+" allocated="+(allocatedBefore<0||allocatedAfter<0?"N/A":Long.toString(allocatedAfter-allocatedBefore)));
                    getLogger().info(PREFIX+"CARRIER label="+label+" kind="+kind+" round="+round+" count="+count+" same="+sameCarrier+" bytes="+(carrierBefore<0||carrierAfter<0?-1:carrierAfter-carrierBefore));
                }).whenComplete((v,problem)->Bukkit.getAsyncScheduler().runNow(this,task->{
                    try{
                        if(problem!=null)throw new RuntimeException(problem);
                        long elapsed=System.nanoTime()-begin;recording.stop();
                        Path file=Path.of("../evidence/"+label+"-"+kind+"-"+round+".jfr");recording.dump(file);recording.close();
                        long bytes=0,samples=0,rawBytes=0,boundaryBytes=0;Set<Long> seenThreads=new HashSet<>();
                        try(var reader=new RecordingFile(file)){
                            while(reader.hasMoreEvents()){
                                var e=reader.readEvent();if(!e.getEventType().getName().equals("jdk.ObjectAllocationSample")||e.getStackTrace()==null)continue;
                                boolean first=seenThreads.add(e.getThread().getJavaThreadId());
                                boolean probe=false,wg=false;
                                for(var frame:e.getStackTrace().getFrames()){
                                    String name=frame.getMethod().getType().getName();
                                    if(name.equals("audit.perf.PerfProbe")&&frame.getMethod().getName().equals(kind.equals("hopper")?"hopperLoop":"bulkLoop"))probe=true;
                                    if(name.startsWith("com.sk89q.worldguard."))wg=true;
                                }
                                if(probe&&wg){rawBytes+=e.getLong("weight");if(first)boundaryBytes+=e.getLong("weight");else{bytes+=e.getLong("weight");samples++;}}
                            }
                        }
                        if(samples==0)throw new AssertionError("No matching allocation samples");
                        getLogger().info(PREFIX+"ALLOC label="+label+" kind="+kind+" round="+round+" count="+count+" bytes="+bytes+" samples="+samples+" bytesPerEvent="+(bytes/(double)count)+" elapsedMs="+(elapsed/1e6)+" rawBytes="+rawBytes+" firstThreadSampleBytes="+boundaryBytes);
                        if(round<rounds)round(kind,label,rounds,count,loop,round+1);else getLogger().info(PREFIX+"BENCH_DONE label="+label+" kind="+kind);
                    }catch(Throwable e){fail(e);}
                }));
            }catch(Throwable e){fail(e);}
        });
    }
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args){try{
        switch(args[0]){
            case "park"->{for(Player player:Bukkit.getOnlinePlayers())player.getScheduler().run(this,t->{
                getLogger().info(PREFIX+"PLAYER_ITEMS name="+player.getName()+" stones="+stones(player.getInventory())+" location="+player.getLocation());
                player.teleportAsync(new Location(home.getWorld(),40.5,-60,40.5)).thenAccept(ok->getLogger().info(PREFIX+"PARK name="+player.getName()+" success="+ok));
            },null);}
            case "matrix"->matrix();case "adapt"->adaptCheck();
            case "native"->nativeFlow(args[1],Boolean.parseBoolean(args[2]),args[3],Boolean.parseBoolean(args[4]),Boolean.parseBoolean(args[5]));
            case "material"->materialKey();
            case "pistons"->pistonMatrix();case "parallel"->parallel();case "actor"->actor((Player)sender);
            case "bench"->bench(args[1],args[2],Integer.parseInt(args[3]),Integer.parseInt(args[4]));
            case "state"->getLogger().info(PREFIX+"STATE version="+WorldGuardPlugin.inst().getDescription().getVersion()+" lands="+Bukkit.getPluginManager().isPluginEnabled("Lands"));
        }
    }catch(Throwable e){fail(e);}return true;}
}
