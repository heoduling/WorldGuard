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

import com.sk89q.worldguard.protection.flags.StateFlag;
import me.angeschossen.lands.ztTjN.BdHcv.ztTjN.wioZV;
import me.angeschossen.lands.ztTjN.BdHcv.ztTjN.rndpt;
import me.angeschossen.lands.api.framework.blockutil.impl.BlockPosition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.UUID;

/** A WorldGuard-owned provider registered through Lands' existing provider API.
 * The native subtype/flag signature is required by Lands' creation check.
 * Its first API classloader remains linked by Lands; later WG instances are not retained.
 */
public final class LandsProvider extends wioZV {
    private final StateFlag marker = new StateFlag("lands-hotload-marker", false);

    public LandsProvider() {
        // The native superclass retains this plugin. Keep Lands itself, never WG.
        super("Lands");
    }

    @Override
    public String Dgduf() {
        return "WorldGuard";
    }
    @Override
    public Plugin ztTjN() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard");
    }
    @Override
    public boolean equals(Object other) {
        return this == other;
    }
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
    @Override
    public StateFlag pdjAO() {
        return LandsLink.query("claimAvailable") == null ? null : marker;
    }
    private int[] pos(BlockPosition p) {
        return new int[]{p.x, p.y, p.z};
    }
    private boolean global() {
        return me.angeschossen.lands.ztTjN.BdHcv.pdjAO.HXvGq;
    }
    @Override
    public boolean RLHXh(Player p, Block b) {
        return LandsLink.test("flag", true, p, b.getLocation(), "break");
    }
    @Override
    public boolean HXvGq(Player p, Block b) {
        return HXvGq(p, b.getLocation());
    }
    @Override
    public boolean HXvGq(Player p, Location l) {
        return LandsLink.test("flag", true, p, l, "place");
    }
    @Override
    public boolean pdjAO(Player p, Block b) {
        return LandsLink.test("flag", true, p, b.getLocation(), "interact");
    }
    @Override
    public boolean RLHXh(Player p, Location l) {
        return LandsLink.test("flag", true, p, l, "pickup");
    }
    @Override public boolean RLHXh(BlockPosition min, BlockPosition max) {
        return LandsLink.test("overlap", false, null, min.world, pos(min), pos(max), global());
    }
    @Override public boolean RLHXh(Player p, BlockPosition min, BlockPosition max) {
        return LandsLink.test("claimBox", true, p, min.world, pos(min), pos(max), global());
    }
    @Override public boolean HXvGq(Player p, Location l, boolean fallback) {
        return LandsLink.test("claimPoint", fallback, p, l, fallback, global());
    }
    @Override
    public boolean RLHXh(Player p, Location l, Material material) {
        return RLHXh(p, l, false);
    }
    @Override public rndpt RLHXh(Location l) {
        String id = (String) LandsLink.query("region", l);
        return id == null ? null : new LandsRegion(this, id, l.clone());
    }
    @Override
    public boolean RLHXh(World w) {
        return LandsLink.test("world", false, w);
    }
    @Override
    public boolean RLHXh(UUID id, Location l) {
        return LandsLink.test("ownerAll", false, id, l);
    }
    @Override
    public boolean HXvGq() {
        return true;
    }
    @Override
    public boolean HXvGq(Location l) {
        return LandsLink.query("region", l) != null;
    }
    @Override
    public boolean JHesM() {
        return true;
    }
    @Override
    public boolean RLHXh(Player p, Location l, boolean fallback) {
        return LandsLink.test("member", fallback, p, l, fallback);
    }
    @Override
    public boolean RLHXh(UUID id, Location l, boolean fallback) {
        return LandsLink.test("member", fallback, id, l, fallback);
    }
    @Override public void igzHW() { /* No cached region managers or event listeners. */ }
    @Override public void SknVT() { /* The active WG registers its flag before loading regions. */ }
    @Override public void RLHXh(Object ignored) { }
    @Override public void pdjAO(Location l) { }
}
