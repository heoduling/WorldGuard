package wgbridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Exact-core pre-commit bridge, installed in the server jar, not the plugin jar. */
public final class TeleportBridge {
    private TeleportBridge() {}
    public record Target(ServerLevel world, Vec3 position, Float yaw, Float pitch) {}

    public static Target check(Entity entity, ServerLevel world, Vec3 position, Float yaw, Float pitch,
                               PlayerTeleportEvent.TeleportCause cause, long flags) {
        Target target = new Target(world, position, yaw, pitch);
        if (entity.getBukkitEntity() instanceof Player player) {
            target = checkPlayer(player, target, cause, true);
            if (target == null) return null;
        }
        if ((flags & Entity.TELEPORT_FLAG_UNMOUNT) != 0) return target;
        // A vehicle teleport must not carry a player past the same protection check.
        for (Entity passenger : entity.getIndirectPassengers()) {
            if (passenger.getBukkitEntity() instanceof Player player && checkPlayer(player, target, cause, false) == null) {
                return null;
            }
        }
        return target;
    }

    private static Target checkPlayer(Player player, Target target, PlayerTeleportEvent.TeleportCause cause, boolean canRedirect) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) return null;
        Location from = player.getLocation();
        Location requested = new Location(target.world().getWorld(), target.position().x, target.position().y,
                target.position().z, target.yaw() == null ? from.getYaw() : target.yaw(),
                target.pitch() == null ? from.getPitch() : target.pitch());
        PlayerTeleportEvent event = new PlayerTeleportEvent(player, from, requested.clone(), cause);
        Bukkit.getPluginManager().callEvent(event);
        Location to = event.getTo();
        if (event.isCancelled() || to == null || to.getWorld() == null) return null;
        to.checkFinite();
        if (to.equals(requested)) return target;
        // All passengers share the vehicle's target. Conflicting redirects reject the group.
        if (!canRedirect) return null;
        Vec3 position = new Vec3(to.getX(), to.getY(), to.getZ());
        if (!ServerLevel.isInSpawnableBounds(net.minecraft.core.BlockPos.containing(position))) return null;
        return new Target(((CraftWorld) to.getWorld()).getHandle(), position, to.getYaw(), to.getPitch());
    }
}
