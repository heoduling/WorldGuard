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

import com.sk89q.bukkit.util.DynamicPluginCommand;
import com.sk89q.bukkit.util.DynamicPluginCommandHelpTopic;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Detach our command references without invalidating help-map/index iterators. */
final class WorldGuardHelpTopics {
    private static final String MARKER = "WorldGuard unloaded command";

    private WorldGuardHelpTopics() {
    }

    static void update(WorldGuardPlugin plugin, boolean detach) throws ReflectiveOperationException {
        Field commandField = GenericCommandHelpTopic.class.getDeclaredField("command");
        Field dynamicField = DynamicPluginCommandHelpTopic.class.getDeclaredField("cmd");
        Field indexField = IndexHelpTopic.class.getDeclaredField("allTopics");
        commandField.setAccessible(true);
        dynamicField.setAccessible(true);
        indexField.setAccessible(true);
        var map = Bukkit.getHelpMap();
        synchronized (map) {
            Set<HelpTopic> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var pending = new ArrayList<>(map.getHelpTopics());
            HelpTopic defaultTopic = map.getHelpTopic("");
            if (defaultTopic != null) {
                pending.add(defaultTopic);
            }
            for (int i = 0; i < pending.size(); i++) {
                HelpTopic topic = pending.get(i);
                if (!seen.add(topic)) {
                    continue;
                }
                if (topic instanceof IndexHelpTopic) {
                    @SuppressWarnings("unchecked")
                    Collection<HelpTopic> children = (Collection<HelpTopic>) indexField.get(topic);
                    pending.addAll(children);
                }
                Field reference;
                if (topic instanceof GenericCommandHelpTopic) {
                    reference = commandField;
                } else if (topic instanceof DynamicPluginCommandHelpTopic) {
                    reference = dynamicField;
                } else {
                    continue;
                }
                Command old = (Command) reference.get(topic);
                if (detach && old instanceof PluginIdentifiableCommand owned && owned.getPlugin() == plugin) {
                    Command replacement;
                    if (old instanceof DynamicPluginCommand dynamic) {
                        var worldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit");
                        var placeholder = new DynamicPluginCommand(new String[]{old.getName()}, old.getDescription(),
                                old.getUsage(), (CommandExecutor) worldEdit, MARKER, worldEdit);
                        placeholder.setPermissions(dynamic.getPermissions() == null ? null : dynamic.getPermissions().clone());
                        placeholder.setAliases(new ArrayList<>(old.getAliases()));
                        replacement = placeholder;
                    } else {
                        replacement = new FormattedCommandAlias(old.getName(), new String[0]);
                        replacement.setDescription(MARKER);
                    }
                    replacement.setPermission(old.getPermission());
                    reference.set(topic, replacement);
                } else if (!detach && ((old instanceof FormattedCommandAlias && MARKER.equals(old.getDescription()))
                        || (old instanceof DynamicPluginCommand dynamic && MARKER.equals(dynamic.getRegisteredWith())))) {
                    Command current = Bukkit.getCommandMap().getCommand("worldguard:" + old.getName());
                    if (current instanceof PluginIdentifiableCommand owned && owned.getPlugin() == plugin) {
                        reference.set(topic, current);
                    }
                }
            }
        }
    }
}
