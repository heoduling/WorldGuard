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

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionDifference;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.index.HashMapIndex;
import com.sk89q.worldguard.protection.managers.storage.RegionDatabase;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Original Lands' importer links the first WG API classloader permanently.
 * Supply current bounds/UUIDs in that API's types, without reopening old storage.
 * Lands' native importer uses bounding boxes for every non-global region type.
 */
final class LandsLegacyRegions {
    private LandsLegacyRegions() { }

    static void warmup() {
        // Resolve the legacy reader's classes before PlugMan closes the first JAR.
        copy(List.of(Map.of("world", "warmup", "id", "warmup", "type", "CUBOID",
                "min", new int[]{0, 0, 0}, "max", new int[]{1, 1, 1},
                "owners", List.of(), "members", List.of())));
        new GlobalProtectedRegion("__global__");
    }

    @SuppressWarnings("unchecked")
    static List<RegionManager> current() {
        var active = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (active == null || !active.isEnabled()) return List.of();
        try {
            Object snapshot = active.getClass().getMethod("landsHotloadQuery", String.class, Object[].class)
                    .invoke(active, "import", new Object[0]);
            return snapshot == null ? List.of() : copy((List<Map<String, Object>>) snapshot);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot obtain current WorldGuard import data", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RegionManager> copy(List<Map<String, Object>> snapshot) {
        Map<String, RegionManager> worlds = new LinkedHashMap<>();
        for (var row : snapshot) {
            String world = (String) row.get("world");
            RegionManager manager = worlds.get(world);
            if (manager == null) {
                manager = new RegionManager(new ImportStore(world), new HashMapIndex.Factory(),
                        WorldGuard.getInstance().getFlagRegistry());
                worlds.put(world, manager);
            }
            String id = (String) row.get("id");
            int[] min = (int[]) row.get("min"), max = (int[]) row.get("max");
            ProtectedRegion region = row.get("type").equals("GLOBAL") ? new GlobalProtectedRegion(id)
                    : new ProtectedCuboidRegion(id, BlockVector3.at(min[0], min[1], min[2]),
                            BlockVector3.at(max[0], max[1], max[2]));
            for (UUID owner : (List<UUID>) row.get("owners")) region.getOwners().addPlayer(owner);
            for (UUID member : (List<UUID>) row.get("members")) region.getMembers().addPlayer(member);
            manager.addRegion(region);
        }
        return List.copyOf(new ArrayList<>(worlds.values()));
    }

    private record ImportStore(String name) implements RegionDatabase {
        @Override
        public String getName() {
            return name;
        }
        @Override
        public Set<ProtectedRegion> loadAll(FlagRegistry flags) {
            throw new UnsupportedOperationException("Import snapshot");
        }
        @Override
        public void saveAll(Set<ProtectedRegion> regions) {
            throw new UnsupportedOperationException("Import snapshot is read-only");
        }
        @Override
        public void saveChanges(RegionDifference changes) {
            throw new UnsupportedOperationException("Import snapshot is read-only");
        }
    }
}
