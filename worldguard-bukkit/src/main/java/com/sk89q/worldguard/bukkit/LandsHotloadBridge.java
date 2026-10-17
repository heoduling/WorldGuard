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

package com.sk89q.worldguard.bukkit;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.association.RegionOverlapAssociation;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Versioned boundary returning only JDK values, never WorldGuard objects to Lands. */
final class LandsHotloadBridge {
    private final WorldGuardPlugin plugin;
    private final AtomicInteger readers = new AtomicInteger();
    private volatile boolean accepting;
    private volatile CompletableFuture<Void> drained = CompletableFuture.completedFuture(null);
    private StateFlag claim;

    LandsHotloadBridge(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    static boolean supported(Plugin other) {
        if (!other.getName().equals("Lands") || !other.getDescription().getVersion().equals("7.28.0-foliafix-1")) {
            return false;
        }
        try {
            return Integer.valueOf(1).equals(other.getClass().getMethod("worldGuardHotloadProtocol").invoke(other));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    void initializeFlag() {
        Plugin lands = Bukkit.getPluginManager().getPlugin("Lands");
        if (lands != null && supported(lands)) {
            try {
                register((boolean) lands.getClass().getMethod("worldGuardClaimDefault").invoke(lands));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot restore the Lands claim flag", e);
            }
        }
    }

    private boolean register(boolean defaultState) {
        var registry = WorldGuard.getInstance().getFlagRegistry();
        var existing = registry.get("lands-claim");
        if (existing == null) {
            claim = new StateFlag("lands-claim", defaultState);
            registry.register(claim);
        } else if (existing instanceof StateFlag state && (state.getDefault() == StateFlag.State.ALLOW) == defaultState) {
            claim = state;
        } else {
            throw new IllegalStateException("Incompatible lands-claim flag");
        }
        return true;
    }

    boolean onlySupportedFlags() {
        return WorldGuard.getInstance().getFlagRegistry().getAll().stream()
                .allMatch(flag -> Flags.INBUILT_FLAGS.contains(flag.getName()) || flag == claim);
    }

    void resume() {
        accepting = true;
    }

    CompletableFuture<Void> drain() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        drained = result;
        accepting = false;
        if (readers.get() == 0) {
            result.complete(null);
        }
        return result;
    }

    Object query(String operation, Object[] args) {
        if (operation.equals("register")) {
            return register((boolean) args[0]);
        }
        readers.incrementAndGet();
        try {
            if (!accepting || !plugin.isEnabled()) {
                return null;
            }
            return evaluate(operation, args);
        } finally {
            if (readers.decrementAndGet() == 0 && !accepting) {
                drained.complete(null);
            }
        }
    }

    private RegionManager manager(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    private ApplicableRegionSet regions(Location location) {
        RegionManager manager = manager(location.getWorld());
        return manager == null ? null : manager.getApplicableRegions(
                BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    private ProtectedRegion first(Location location) {
        ApplicableRegionSet regions = regions(location);
        return regions == null || regions.size() == 0 ? null : regions.iterator().next();
    }

    private LocalPlayer player(Object identity) {
        return identity instanceof Player live ? plugin.wrapPlayer(live)
                : plugin.wrapOfflinePlayer(Bukkit.getOfflinePlayer((UUID) identity));
    }

    private boolean member(ProtectedRegion region, LocalPlayer player) {
        return region.isMember(player) || region.isOwner(player)
                || WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(player, player.getWorld());
    }

    private Object evaluate(String operation, Object[] args) {
        switch (operation) {
            case "claimAvailable":
                return claim == null ? null : Boolean.TRUE;
            case "flag": {
                Player player = (Player) args[0];
                Location location = (Location) args[1];
                StateFlag flag = switch ((String) args[2]) {
                    case "break" -> Flags.BLOCK_BREAK;
                    case "place" -> Flags.BLOCK_PLACE;
                    case "interact" -> Flags.INTERACT;
                    case "pickup" -> Flags.ITEM_PICKUP;
                    default -> throw new IllegalArgumentException("Unknown Lands flag query");
                };
                return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                        .testState(BukkitAdapter.adapt(location), plugin.wrapPlayer(player), flag);
            }
            case "world":
                return manager((World) args[0]) != null;
            case "region": {
                ProtectedRegion region = first((Location) args[0]);
                return region == null ? null : region.getId();
            }
            case "owner": {
                RegionManager manager = manager((World) args[0]);
                ProtectedRegion region = manager == null ? null : manager.getRegion((String) args[1]);
                return region == null || region.getOwners().getUniqueIds().isEmpty()
                        ? null : region.getOwners().getUniqueIds().iterator().next();
            }
            case "member": {
                Location location = (Location) args[1];
                RegionManager manager = manager(location.getWorld());
                ProtectedRegion region = args.length == 4
                        ? (manager == null ? null : manager.getRegion((String) args[3])) : first(location);
                return region == null ? args[2] : member(region, player(args[0]));
            }
            case "ownerAll": {
                Player player = Bukkit.getPlayer((UUID) args[0]);
                ApplicableRegionSet regions = regions((Location) args[1]);
                return player != null && regions != null && regions.isOwnerOfAll(plugin.wrapPlayer(player));
            }
            case "claimPoint": {
                ApplicableRegionSet regions = regions((Location) args[1]);
                return regions == null || (regions.size() == 0 && !(boolean) args[3])
                        ? args[2] : regions.testState(plugin.wrapPlayer((Player) args[0]), claim);
            }
            case "overlap":
            case "claimBox": {
                RegionManager manager = manager((World) args[1]);
                if (manager == null) {
                    return operation.equals("claimBox");
                }
                int[] min = (int[]) args[2];
                int[] max = (int[]) args[3];
                var box = new ProtectedCuboidRegion("check", BlockVector3.at(min[0], min[1], min[2]),
                        BlockVector3.at(max[0], max[1], max[2]));
                ApplicableRegionSet regions = manager.getApplicableRegions(box);
                if (operation.equals("overlap")) {
                    return regions.size() != 0;
                }
                if (regions.size() == 0 && !(boolean) args[4]) {
                    return true;
                }
                RegionAssociable associable = args[0] == null
                        ? new RegionOverlapAssociation(Set.of()) : plugin.wrapPlayer((Player) args[0]);
                return regions.testState(associable, claim);
            }
            case "import": {
                List<Map<String, Object>> snapshot = new ArrayList<>();
                for (RegionManager manager : WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded()) {
                    for (ProtectedRegion region : manager.getRegions().values()) {
                        var min = region.getMinimumPoint();
                        var max = region.getMaximumPoint();
                        snapshot.add(Map.of("world", manager.getName(), "id", region.getId(),
                                "type", region.getType().name(), "owners", List.copyOf(region.getOwners().getUniqueIds()),
                                "members", List.copyOf(region.getMembers().getUniqueIds()),
                                "min", new int[]{min.x(), min.y(), min.z()}, "max", new int[]{max.x(), max.y(), max.z()}));
                    }
                }
                return snapshot;
            }
            default:
                throw new IllegalArgumentException("Unknown Lands operation: " + operation);
        }
    }
}
