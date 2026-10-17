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

import org.bukkit.plugin.Plugin;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.jar.JarFile;

/** Exact original Lands 7.28.0 integration; no Lands classes or files are rewritten. */
final class LandsProviderAdapter {
    private static final String PROVIDER = "com.sk89q.worldguard.bukkit.compat.lands728.LandsProvider";
    private static final String MANAGER = "me.angeschossen.lands.ztTjN.BdHcv.pdjAO";

    private LandsProviderAdapter() {
    }

    static boolean original(Plugin lands) {
        return lands.getName().equals("Lands") && lands.getDescription().getVersion().equals("7.28.0");
    }

    static Object manager(Plugin lands) throws ReflectiveOperationException {
        return Class.forName(MANAGER, true, lands.getClass().getClassLoader()).getMethod("pdjAO").invoke(null);
    }

    static Object provider(Plugin lands) throws ReflectiveOperationException {
        Object manager = manager(lands);
        return manager.getClass().getMethod("RLHXh", String.class).invoke(manager, "WorldGuard");
    }

    static boolean installed(Plugin lands) {
        if (!original(lands)) return false;
        try {
            Object provider = provider(lands);
            return provider != null && provider.getClass().getName().equals(PROVIDER);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    static String flagName(Plugin lands) throws ReflectiveOperationException {
        Object manager = manager(lands);
        return (String) manager.getClass().getField("UhzX").get(manager);
    }

    static boolean flagDefault(Plugin lands) throws ReflectiveOperationException {
        Class<?> configType = Class.forName("me.angeschossen.lands.ztTjN.RLHXh", true, lands.getClass().getClassLoader());
        Object config = configType.getMethod("BdHcv").invoke(null);
        Object yaml = config.getClass().getMethod("rndpt").invoke(config);
        return (boolean) yaml.getClass().getMethod("getBoolean", String.class).invoke(yaml, "integration.flag.default-state");
    }

    static void install(WorldGuardPlugin plugin, Plugin lands) throws ReflectiveOperationException, IOException {
        if (installed(lands)) return;
        // Lands resolves these API signatures lazily. Bind the exact 19 WG types
        // referenced by its original bytecode while the first WG is still loaded;
        // otherwise a later import mixes old WorldGuard with a new platform type.
        ClassLoader landsLoader = lands.getClass().getClassLoader();
        for (String type : new String[]{
                "LocalPlayer", "WorldGuard", "bukkit.WorldGuardPlugin", "domains.DefaultDomain",
                "internal.platform.WorldGuardPlatform", "protection.ApplicableRegionSet",
                "protection.association.RegionAssociable", "protection.association.RegionOverlapAssociation",
                "protection.flags.Flag", "protection.flags.Flags", "protection.flags.StateFlag",
                "protection.flags.registry.FlagRegistry", "protection.managers.RegionManager",
                "protection.regions.ProtectedCuboidRegion", "protection.regions.ProtectedRegion",
                "protection.regions.RegionContainer", "protection.regions.RegionQuery",
                "protection.regions.RegionType", "session.SessionManager"}) {
            Class.forName("com.sk89q.worldguard." + type, true, landsLoader);
        }
        Object manager = manager(lands);
        Object old = provider(lands);
        @SuppressWarnings("unchecked")
        Collection<Object> providers = (Collection<Object>) manager.getClass().getMethod("Dgduf").invoke(manager);
        @SuppressWarnings("unchecked")
        Collection<Object> flags = (Collection<Object>) manager.getClass().getMethod("ztTjN").invoke(manager);

        var file = plugin.getDataFolder().toPath().resolve("compat/lands728-adapter.jar");
        Files.createDirectories(file.getParent());
        try (var in = plugin.getResource("compat/lands728-adapter.jar")) {
            if (in == null) throw new IOException("Missing bundled Lands adapter");
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        // The parent is Lands, not this replaceable WG instance. Eager loading then
        // closing the JDK loader also releases the adapter JAR on Windows.
        try (var loader = new URLClassLoader(new java.net.URL[]{file.toUri().toURL()}, lands.getClass().getClassLoader());
             var jar = new JarFile(file.toFile())) {
            for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                Class.forName(entry.getName().replace('/', '.').replace(".class", ""), true, loader);
            }
            var constructor = Class.forName(PROVIDER, true, loader).getConstructor();
            providers.remove(old);
            flags.remove(old);
            try {
                constructor.newInstance(); // Native superclass registers using Lands' own API.
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (old != null) {
                    Class<?> base = Class.forName("me.angeschossen.lands.ztTjN.BdHcv.ztTjN.Vjrip", true, lands.getClass().getClassLoader());
                    manager.getClass().getMethod("RLHXh", base).invoke(manager, old);
                }
                throw e;
            }
        }
        plugin.getLogger().info("Installed WorldGuard-owned adapter for original Lands 7.28.0 (Lands JAR unchanged).");
    }
}
