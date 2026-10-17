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

package com.sk89q.worldguard.bukkit.compat.lands728;

import me.angeschossen.lands.ztTjN.BdHcv.ztTjN.rndpt;
import me.angeschossen.lands.ztTjN.BdHcv.ztTjN.Vjrip;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Retains identity only, so a cached result can query a later WG generation. */
final class LandsRegion extends rndpt {
    private final LandsProvider provider;
    private final String id;
    private final Location location;
    LandsRegion(LandsProvider provider, String id, Location location) {
        this.provider = provider; this.id = id; this.location = location;
    }
    @Override public boolean RLHXh(UUID id) {
        var player = Bukkit.getPlayer(id);
        return player == null || LandsLink.test("member", true, player, location, true, this.id);
    }
    @Override public UUID RLHXh() {
        UUID owner = (UUID) LandsLink.query("owner", location.getWorld(), id);
        return owner == null ? me.angeschossen.lands.ztTjN.Dgduf.HXvGq.AEoc : owner;
    }
    @Override public boolean HXvGq(UUID id) {
        var player = Bukkit.getPlayer(id);
        return player == null || LandsLink.test("flag", true, player, location, "pickup");
    }
    @Override
    public Vjrip HXvGq() {
        return provider;
    }
    @Override
    public Collection<?> pdjAO() {
        return List.of();
    }
}
