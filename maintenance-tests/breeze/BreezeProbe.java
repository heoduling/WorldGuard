package audit.wg;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.cause.Cause;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Powerable;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real entities and native wind-charge explosions; isolated test server only. */
public final class BreezeProbe extends JavaPlugin implements Listener {
    private volatile Breeze local, remote;
    private final AtomicReference<AbstractWindCharge> pending = new AtomicReference<>();
    private GlobalProtectedRegion region;
    private UUID active, activeShooter;
    private int knockbacks, explosions, cancelledKnockbacks, filteredExplosions;
    private int passed, failed;
    private Cow victim;
    private String mode;

    @Override public void onEnable() { Bukkit.getPluginManager().registerEvents(this, this); }
    @EventHandler public void join(PlayerJoinEvent e) {
        Player p=e.getPlayer();
        if (!p.getName().startsWith("BreezeTest")) return;
        p.setOp(false); p.setGameMode(GameMode.CREATIVE); p.setAllowFlight(true); p.setFlying(true);
        p.addAttachment(this,"breezeprobe.admin",true);
        p.addAttachment(this,"worldguard.region.bypass.world",false);
    }
    private void test(String label, Runnable run) {
        try { run.run(); passed++; getLogger().info("PASS "+label); }
        catch (Throwable e) { failed++; getLogger().severe("FAIL "+label+" "+e); }
    }
    private void require(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
    private Location at(World w,double x,double y,double z) { return new Location(w,x,y,z); }
    @Override public boolean onCommand(CommandSender s,Command c,String l,String[] a) {
        if (!(s instanceof Player p) || !p.getName().startsWith("BreezeTest")) return true;
        try {
            switch(a[0]) {
                case "home" -> p.teleportAsync(new Location(p.getWorld(),p.getName().endsWith("B")?12000.5:0.5,-59,8.5))
                    .thenAccept(ok -> getLogger().info("HOME "+p.getName()+" success="+ok));
                case "here" -> p.teleportAsync(at(p.getWorld(),0.5,-59,8.5))
                    .thenAccept(ok -> getLogger().info("HERE "+p.getName()+" success="+ok));
                case "setup" -> {
                    int x=p.getName().endsWith("B")?12000:0;
                    Breeze breeze=p.getWorld().spawn(at(p.getWorld(),x+8,-59,4),Breeze.class);
                    breeze.setAI(false); breeze.setGravity(false); breeze.setPersistent(true);
                    if(x==0) {
                        local=breeze;
                        for(int bx=0;bx<=9;bx++) for(int bz=-3;bz<=3;bz++)
                            p.getWorld().getBlockAt(bx,-61,bz).setType(Material.STONE,false);
                        region=new GlobalProtectedRegion("__global__");
                        region.setFlag(Flags.BUILD,StateFlag.State.ALLOW);
                        region.setFlag(Flags.MOB_DAMAGE,StateFlag.State.ALLOW);
                        WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(p.getWorld())).addRegion(region);
                    } else remote=breeze;
                    getLogger().info("SETUP "+p.getName());
                }
                case "charge" -> {
                    mode=a[1];
                    AbstractWindCharge charge=mode.equals("wind")
                        ? p.getWorld().spawn(at(p.getWorld(),4,-59,0),WindCharge.class)
                        : p.getWorld().spawn(at(p.getWorld(),4,-59,0),BreezeWindCharge.class);
                    charge.setGravity(false); charge.setVelocity(new Vector());
                    if(mode.equals("remote") || mode.equals("player")) {
                        LivingEntity source;
                        if(mode.equals("player")) source=p;
                        else { Breeze b=p.getWorld().spawn(at(p.getWorld(),8,-59,4),Breeze.class);b.setAI(false);b.setGravity(false);source=b; }
                        charge.setShooter(source);
                        source.teleportAsync(at(p.getWorld(),12008,-59,4)).thenAccept(ok -> {
                            if(ok) { pending.set(charge);getLogger().info("CHARGE_READY "+mode); }
                            else getLogger().severe("FAIL source teleport");
                        });
                    } else {
                        if(mode.equals("wind")) charge.setShooter(p);
                        else if(!mode.equals("none")) charge.setShooter(local);
                        pending.set(charge);getLogger().info("CHARGE_READY "+mode);
                    }
                }
                case "run" -> run(p,a[1],Boolean.parseBoolean(a[2]),a[3].equals("native"));
                case "summary" -> getLogger().info("SUMMARY passed="+passed+" failed="+failed);
                default -> throw new IllegalArgumentException(Arrays.toString(a));
            }
        } catch(Throwable e) { failed++;getLogger().severe("FAIL command "+e);e.printStackTrace(); }
        return true;
    }
    private void run(Player p,String label,boolean deny,boolean nativeExplosion) {
        AbstractWindCharge charge=pending.getAndSet(null);
        require(charge!=null,"no pending charge");
        test(label+" ownership",() -> {
            require(Bukkit.isOwnedByCurrentRegion(charge),"charge must be owned");
            require(Bukkit.isOwnedByCurrentRegion(local),"local breeze must be owned");
            require(!Bukkit.isOwnedByCurrentRegion(remote),"remote breeze must be foreign");
            if(mode.equals("remote")||mode.equals("player")) {
                require(charge.getShooter() instanceof Entity,"lost shooter");
                require(!Bukkit.isOwnedByCurrentRegion((Entity)charge.getShooter()),"shooter must be foreign");
            }
        });
        StateFlag.State flag=deny?StateFlag.State.DENY:StateFlag.State.ALLOW;
        region.setFlag(Flags.BREEZE_WIND_CHARGE,flag);
        region.setFlag(Flags.WIND_CHARGE_BURST,flag);
        if(victim!=null) victim.remove();
        victim=p.getWorld().spawn(at(p.getWorld(),5.5,-60,0.5),Cow.class);
        victim.setAI(false);victim.setGravity(false);
        local.setTarget(null);
        Block lever=p.getWorld().getBlockAt(4,-60,1);
        lever.setType(Material.LEVER,false);
        var data=lever.getBlockData();
        ((FaceAttachable)data).setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
        ((Powerable)data).setPowered(false);lever.setBlockData(data,false);
        test(label+" cause",() -> {
            Cause cause=Cause.create(charge);
            Object expected=switch(mode) {case "remote","none" -> charge;case "player" -> Bukkit.getPlayerExact("BreezeTestB");case "wind" -> p;default -> local;};
            require(cause.getRootCause()==expected,"wrong root cause "+cause.getRootCause());
            require(cause.find(charge.getType())==charge.getType(),"lost projectile attribution");
            if(expected instanceof Player player) require(!player.isOp()&&!player.hasPermission("worldguard.region.bypass.world"),"test player must not bypass protection");
            if(mode.equals("remote"))require(cause.isIndirect(),"foreign traversal must stay indirect");
        });
        if(label.startsWith("0-")) test(label+" metadata-foreign-source",() -> {
            BreezeWindCharge separate=p.getWorld().spawn(at(p.getWorld(),4,-50,0),BreezeWindCharge.class);
            try {
                Cause.trackParentCause(separate,remote);
                Cause cause=Cause.create(separate);
                require(cause.getRootCause()==separate,"foreign metadata parent escaped ownership check");
                require(cause.isIndirect(),"metadata traversal must stay indirect");
            } finally { Cause.untrackParentCause(separate);separate.remove(); }
        });
        if(!nativeExplosion) {
            test(label+" knockback",() -> {
                var event=new EntityPushedByEntityAttackEvent(victim,io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION,charge,new Vector(1,0,0));
                Bukkit.getPluginManager().callEvent(event);
                require(event.isCancelled()==deny,"knockback cancellation="+event.isCancelled());
            });
            test(label+" block-event",() -> {
                var event=new EntityExplodeEvent(charge,charge.getLocation(),new ArrayList<>(List.of(lever)),0,ExplosionResult.TRIGGER_BLOCK);
                Bukkit.getPluginManager().callEvent(event);
                require((event.isCancelled()||event.blockList().isEmpty())==deny,"block interaction not filtered correctly");
            });
            charge.remove();getLogger().info("CASE_DONE "+label);
        } else {
            active=charge.getUniqueId();
            activeShooter=charge.getShooter() instanceof Entity shooter?shooter.getUniqueId():null;
            knockbacks=explosions=cancelledKnockbacks=filteredExplosions=0;
            charge.explode();
            charge.remove();
            p.getScheduler().runDelayed(this,t -> {
                test(label+" native-events",() -> {
                    require(knockbacks>0,"native knockback event missing");require(explosions==1,"native explosion event missing");
                    require((cancelledKnockbacks==knockbacks)==deny,"native knockback flags wrong");
                    require((filteredExplosions==1)==deny,"native block filtering wrong");
                });
                test(label+" actual-velocity",() -> require((victim.getVelocity().lengthSquared()<1.0e-8)==deny,"velocity="+victim.getVelocity()));
                test(label+" actual-lever",() -> require(((Powerable)lever.getBlockData()).isPowered()!=deny,"lever powered="+((Powerable)lever.getBlockData()).isPowered()));
                active=null;activeShooter=null;getLogger().info("CASE_DONE "+label);
            },null,2L);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void knockback(EntityPushedByEntityAttackEvent e) {
        if(active!=null&&(active.equals(e.getPushedBy().getUniqueId())||Objects.equals(activeShooter,e.getPushedBy().getUniqueId()))) {
            knockbacks++;if(e.isCancelled())cancelledKnockbacks++;
            getLogger().info("NATIVE_KNOCKBACK source="+e.getPushedBy().getType()+" cancelled="+e.isCancelled());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=false)
    public void explode(EntityExplodeEvent e) {
        if(active!=null&&active.equals(e.getEntity().getUniqueId())) {
            explosions++;if(e.isCancelled()||e.blockList().isEmpty())filteredExplosions++;
            getLogger().info("NATIVE_EXPLODE cancelled="+e.isCancelled()+" blocks="+e.blockList().size());
        }
    }
}
