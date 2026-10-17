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

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.session.BukkitSessionManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Supported PlugManX commands prepare asynchronously before the real manager operation. */
final class WorldGuardHotload implements Listener {
    private final WorldGuardPlugin plugin;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Set<UUID> cleanedPlayers = ConcurrentHashMap.newKeySet();
    private final List<Registration> suspended = new ArrayList<>();
    private volatile boolean draining;
    private volatile boolean prepared;
    private volatile long attempt;
    private Map<Plugin, Object> gentleUnloads;
    private Object hook;
    private Plugin plugMan;

    private record Registration(HandlerList list, RegisteredListener listener) {
    }

    WorldGuardHotload(WorldGuardPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        installHook();
    }

    boolean isPending() {
        return pending.get();
    }

    boolean isDraining() {
        return draining;
    }

    boolean isPrepared() {
        return prepared;
    }

    @EventHandler
    public void enabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("PlugManX")) {
            installHook();
        }
    }

    @SuppressWarnings("unchecked")
    private void installHook() {
        Plugin manager = Bukkit.getPluginManager().getPlugin("PlugManX");
        if (manager == null || !manager.isEnabled() || hook != null) {
            return;
        }
        if (!manager.getDescription().getVersion().equals("3.1.0-Beta.2")) {
            plugin.getLogger().warning("Hot unload support requires the verified PlugManX 3.1.0-Beta.2.");
            return;
        }
        try {
            ClassLoader loader = manager.getClass().getClassLoader();
            Class<?> api = Class.forName("bukkit.com.rylinaux.plugman.api.PlugManAPI", true, loader);
            Class<?> gentle = Class.forName("bukkit.com.rylinaux.plugman.api.GentleUnload", true, loader);
            Object registration = Proxy.newProxyInstance(loader, new Class<?>[]{gentle}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "askingForGentleUnload" -> prepared;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "WorldGuard hot unload readiness";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            // This exact API exposes only a copy and no unregister method. Keep our own
            // registration token and remove only that entry from the verified manager.
            Field field = api.getDeclaredField("gentleUnloads");
            field.setAccessible(true);
            Map<Plugin, Object> registrations = (Map<Plugin, Object>) field.get(null);
            boolean added = (boolean) api.getMethod("pleaseAddMeToGentleUnload", Plugin.class, gentle)
                    .invoke(null, plugin, registration);
            if (!added) {
                throw new IllegalStateException("Another WorldGuard unload hook is already registered");
            }
            gentleUnloads = registrations;
            hook = registration;
            plugMan = manager;
            plugin.getLogger().info("Prepared hot unload enabled for PlugManX 3.1.0-Beta.2.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Cannot install supported hot unload integration", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void playerCommand(PlayerCommandPreprocessEvent event) {
        if (intercept(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consoleCommand(ServerCommandEvent event) {
        if (intercept(event.getSender(), event.getCommand())) {
            event.setCancelled(true);
        }
    }

    private boolean intercept(CommandSender sender, String command) {
        String[] parts = command.replaceFirst("^/", "").trim().split("\\s+");
        if (parts.length != 3 || plugMan == null || !plugMan.isEnabled()) {
            return false;
        }
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (!Set.of("plugman", "plm", "plugmanx:plugman", "plugmanx:plm").contains(label)) {
            return false;
        }
        String operation = parts[1].toLowerCase(Locale.ROOT);
        if (!Set.of("reload", "restart", "unload", "disable").contains(operation)) {
            return false;
        }
        if (!(sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender)
                && !sender.hasPermission("plugman." + operation)) {
            return false; // Preserve PlugManX's own permission-denied response.
        }
        if (parts[2].equalsIgnoreCase("all") || parts[2].equals("*")) {
            sender.sendMessage("WorldGuard: use a command targeting WorldGuard alone so it can finish saving safely.");
            return true;
        }
        if (!parts[2].equalsIgnoreCase("WorldGuard")) {
            return false;
        }
        if (!pending.compareAndSet(false, true)) {
            sender.sendMessage("WorldGuard: a hot unload is already in progress.");
            return true;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> begin(sender, operation));
        return true;
    }

    private void begin(CommandSender sender, String operation) {
        long ticket = ++attempt;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> restore(sender, ticket,
                new IllegalStateException("Hot unload preparation timed out")), 400);
        try {
            BukkitWorldGuardPlatform platform = (BukkitWorldGuardPlatform) WorldGuard.getInstance().getPlatform();
            if (platform.isReloadingConfiguration() || !WorldGuard.getInstance().getSupervisor().getTasks().isEmpty()) {
                throw new IllegalStateException("WorldGuard is busy; wait for configuration/background tasks to finish.");
            }
            Object manager = plugMan.getClass().getMethod("getPluginManager").invoke(plugMan);
            if ((boolean) manager.getClass().getMethod("isIgnored", String.class).invoke(manager, "WorldGuard")) {
                throw new IllegalStateException("WorldGuard is in PlugManX's ignored list.");
            }
            for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
                if (other != plugin && other.isEnabled()
                        && !LandsHotloadBridge.supported(other)
                        && (other.getDescription().getDepend().contains("WorldGuard")
                        || other.getDescription().getSoftDepend().contains("WorldGuard"))) {
                    throw new IllegalStateException("Unload the WorldGuard integration first: " + other.getName());
                }
            }
            if (!plugin.landsBridge().onlySupportedFlags()
                    || platform.getSessionManager().customHandlersRegistered()) {
                throw new IllegalStateException("Custom WorldGuard flags/session handlers require a full restart.");
            }
            draining = true;
            cleanedPlayers.clear();
            for (HandlerList list : HandlerList.getHandlerLists()) {
                for (RegisteredListener listener : list.getRegisteredListeners()) {
                    if (listener.getPlugin() == plugin && listener.getListener() != this) {
                        suspended.add(new Registration(list, listener));
                        list.unregister(listener);
                    }
                }
            }
            var completions = new ArrayList<CompletableFuture<Void>>();
            completions.add(plugin.landsBridge().drain());
            for (Player player : Bukkit.getOnlinePlayers()) {
                CompletableFuture<Void> completion = new CompletableFuture<>();
                completions.add(completion);
                Runnable retired = () -> {
                    if (cleanedPlayers.contains(player.getUniqueId())) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(new IllegalStateException("Player retired before cleanup: " + player.getUniqueId()));
                    }
                };
                var scheduled = player.getScheduler().runDelayed(plugin, task -> {
                    try {
                        if (ticket != attempt || !draining) {
                            throw new IllegalStateException("Hot unload preparation expired");
                        }
                        cleanupPlayer(player);
                        completion.complete(null);
                    } catch (Exception e) {
                        completion.completeExceptionally(e);
                    }
                }, retired, 2);
                if (scheduled == null) {
                    retired.run();
                }
            }
            CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new))
                    .orTimeout(10, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
                        if (!plugin.isEnabled()) {
                            return;
                        }
                        if (failure != null) {
                            Bukkit.getGlobalRegionScheduler().run(plugin, task -> restore(sender, ticket, failure));
                        } else {
                            finishPreparation(sender, operation, ticket);
                        }
                    });
        } catch (Exception e) {
            restore(sender, ticket, e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        if (draining) {
            cleanupPlayer(event.getPlayer());
        }
    }

    private void cleanupPlayer(Player player) {
        UUID id = player.getUniqueId();
        if (!cleanedPlayers.contains(id)) {
            var local = plugin.wrapPlayer(player);
            var session = WorldGuard.getInstance().getPlatform().getSessionManager().getIfPresent(local);
            if (session != null) {
                session.uninitialize(local);
            }
            cleanedPlayers.add(id);
        }
    }

    private void finishPreparation(CommandSender sender, String operation, long ticket) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                // The existing executor has one worker. Await its prior work only off-thread.
                WorldGuard.getInstance().getExecutorService().submit(() -> { }).get(5, TimeUnit.SECONDS);
                ((BukkitRegionContainer) WorldGuard.getInstance().getPlatform().getRegionContainer()).flushForHotUnload();
                Bukkit.getGlobalRegionScheduler().run(plugin, ready -> {
                    if (ticket != attempt || !pending.get()) {
                        return;
                    }
                    try {
                        WorldGuardHelpTopics.update(plugin, true);
                        prepared = true;
                        // Run the verified manager through a JDK-owned adapter. Loading a new
                        // plugin from our own Java stack would retain this old classloader in
                        // the new PluginClassLoader's initialization diagnostic backtrace.
                        Class<?> handlerClass = Class.forName("bukkit.com.rylinaux.plugman.commands.PlugManCommandHandler",
                                true, plugMan.getClass().getClassLoader());
                        CommandExecutor handler = (CommandExecutor) handlerClass.getConstructor().newInstance();
                        var invoke = MethodHandles.publicLookup().findVirtual(CommandExecutor.class, "onCommand",
                                MethodType.methodType(boolean.class, CommandSender.class, Command.class, String.class, String[].class));
                        var call = MethodHandles.insertArguments(invoke.bindTo(handler), 0,
                                sender, null, "plugman", new String[]{operation, "WorldGuard"})
                                .asType(MethodType.methodType(void.class));
                        var stillReady = MethodHandles.insertArguments(MethodHandles.lookup().findVirtual(
                                WorldGuardHotload.class, "canReplay", MethodType.methodType(boolean.class, long.class))
                                .bindTo(this), 0, ticket);
                        call = MethodHandles.guardWithTest(stillReady, call, MethodHandles.empty(MethodType.methodType(void.class)));
                        if (sender instanceof Player player) {
                            Runnable retired = () -> Bukkit.getGlobalRegionScheduler().run(plugin,
                                    rollback -> restore(sender, ticket, new IllegalStateException("Command sender disconnected")));
                            Runnable replay = MethodHandleProxies.asInterfaceInstance(Runnable.class, call);
                            if (!player.getScheduler().execute(plugMan, replay, retired, 1)) {
                                retired.run();
                            }
                        } else {
                            @SuppressWarnings("unchecked")
                            Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> replay =
                                    MethodHandleProxies.asInterfaceInstance(Consumer.class,
                                            MethodHandles.dropArguments(call, 0, Object.class));
                            Bukkit.getGlobalRegionScheduler().run(plugMan, replay);
                        }
                        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, check -> restore(sender, ticket,
                                new IllegalStateException("PlugManX did not disable WorldGuard; restored active protection.")), 20);
                    } catch (Exception e) {
                        restore(sender, ticket, e);
                    }
                });
            } catch (Exception e) {
                if (plugin.isEnabled()) {
                    Bukkit.getGlobalRegionScheduler().run(plugin, rollback -> restore(sender, ticket, e));
                }
            }
        });
    }

    private boolean canReplay(long ticket) {
        return plugin.isEnabled() && ticket == attempt && pending.get() && prepared;
    }

    private void restore(CommandSender sender, long ticket, Throwable failure) {
        if (ticket != attempt || !pending.getAndSet(false)) {
            return;
        }
        ++attempt;
        boolean wasDraining = draining;
        draining = false;
        prepared = false;
        plugin.landsBridge().resume();
        for (Registration registration : suspended) {
            registration.list().register(registration.listener());
        }
        suspended.clear();
        try {
            WorldGuardHelpTopics.update(plugin, false);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "Cannot restore help references", e);
        }
        if (wasDraining) {
            ((BukkitSessionManager) WorldGuard.getInstance().getPlatform().getSessionManager()).resetAllStates();
        }
        String message = "WorldGuard hot unload refused: " + failure.getMessage();
        plugin.getLogger().warning(message);
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> sender.sendMessage(message), null);
        } else {
            sender.sendMessage(message);
        }
    }

    void close() {
        if (gentleUnloads != null) {
            gentleUnloads.remove(plugin, hook);
        }
        hook = null;
        gentleUnloads = null;
        suspended.clear();
        cleanedPlayers.clear();
    }
}
