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

import org.bukkit.Bukkit;
import java.lang.reflect.InvocationTargetException;

/** Resolves the active plugin per call; never retains a replaceable WorldGuard instance. */
final class LandsLink {
    private LandsLink() { }

    static Object query(String operation, Object... args) {
        var plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (plugin == null || !plugin.isEnabled()) return null;
        try {
            return plugin.getClass().getMethod("landsHotloadQuery", String.class, Object[].class)
                    .invoke(plugin, operation, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("WorldGuard integration query failed: " + operation, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WorldGuard requires the Lands hotload adapter endpoint", e);
        }
    }

    static boolean test(String operation, boolean absent, Object... args) {
        Object value = query(operation, args);
        return value == null ? absent : (Boolean) value;
    }
}
