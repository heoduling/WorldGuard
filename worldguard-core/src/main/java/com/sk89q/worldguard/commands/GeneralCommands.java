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

package com.sk89q.worldguard.commands;

import com.google.common.collect.Lists;
import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissions;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.auth.AuthorizationException;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.session.Session;
import com.sk89q.worldguard.session.handler.GodMode;

public class GeneralCommands {
    private final WorldGuard worldGuard;

    public GeneralCommands(WorldGuard worldGuard) {
        this.worldGuard = worldGuard;
    }
    
    @Command(aliases = {"god"}, usage = "[player]",
            desc = "Enable godmode on a player", flags = "s", max = 1)
    public void god(CommandContext args, Actor sender) throws CommandException, AuthorizationException {
        Iterable<? extends LocalPlayer> targets = null;
        
        // Detect arguments based on the number of arguments provided
        if (args.argsLength() == 0) {
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(worldGuard.checkPlayer(sender));
            
            // Check permissions!
            sender.checkPermission("worldguard.god");
        } else {
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(sender, args.getString(0));
            
            // Check permissions!
            sender.checkPermission("worldguard.god.other");
        }

        String senderName = sender.getDisplayName();
        for (LocalPlayer player : targets) {
            player.runOnOwnedThread(() -> {
                Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);

                if (GodMode.set(player, session, true)) {
                    player.setFireTicks(0);

                    // Tell the user
                    if (player.equals(sender)) {
                        player.print("God mode enabled! Use /ungod to disable.");

                    } else if (!args.hasFlag('s')) {
                        player.print("God enabled by " + senderName + ".");

                    }
                }
                if (!player.equals(sender)) {
                    sender.print("God mode enabled for " + player.getName() + ".");
                }
            }, () -> sender.printError("Player left before the operation could run: " + player.getName()));
        }
    }
    
    @Command(aliases = {"ungod"}, usage = "[player]",
            desc = "Disable godmode on a player", flags = "s", max = 1)
    public void ungod(CommandContext args, Actor sender) throws CommandException, AuthorizationException {
        Iterable<? extends LocalPlayer> targets;
        
        // Detect arguments based on the number of arguments provided
        if (args.argsLength() == 0) {
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(worldGuard.checkPlayer(sender));
            
            // Check permissions!
            sender.checkPermission("worldguard.god");
        } else {
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(sender, args.getString(0));
            
            // Check permissions!
            sender.checkPermission("worldguard.god.other");
        }

        String senderName = sender.getDisplayName();
        for (LocalPlayer player : targets) {
            player.runOnOwnedThread(() -> {
                Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);

                if (GodMode.set(player, session, false)) {
                    // Tell the user
                    if (player.equals(sender)) {
                        player.print("God mode disabled!");

                    } else if (!args.hasFlag('s')) {
                        player.print("God disabled by " + senderName + ".");

                    }
                }
                if (!player.equals(sender)) {
                    sender.print("God mode disabled for " + player.getName() + ".");
                }
            }, () -> sender.printError("Player left before the operation could run: " + player.getName()));
        }
    }
    
    @Command(aliases = {"heal"}, usage = "[player]", desc = "Heal a player", flags = "s", max = 1)
    public void heal(CommandContext args, Actor sender) throws CommandException, AuthorizationException {

        Iterable<? extends LocalPlayer> targets = null;
        
        // Detect arguments based on the number of arguments provided
        if (args.argsLength() == 0) {
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(worldGuard.checkPlayer(sender));
            
            // Check permissions!
            sender.checkPermission("worldguard.heal");
        } else if (args.argsLength() == 1) {            
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(sender, args.getString(0));
            
            // Check permissions!
            sender.checkPermission("worldguard.heal.other");
        }

        String senderName = sender.getDisplayName();
        for (LocalPlayer player : targets) {
            player.runOnOwnedThread(() -> {
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setSaturation(20);
                player.setExhaustion(0);
            
                // Tell the user
                if (player.equals(sender)) {
                    player.print("Healed!");
                
                } else if (!args.hasFlag('s')) {
                    player.print("Healed by " + senderName + ".");
                
                }
                if (!player.equals(sender)) {
                    sender.print("Healed " + player.getName() + ".");
                }
            }, () -> sender.printError("Player left before the operation could run: " + player.getName()));
        }
    }
    
    @Command(aliases = {"slay"}, usage = "[player]", desc = "Slay a player", flags = "s", max = 1)
    public void slay(CommandContext args, Actor sender) throws CommandException, AuthorizationException {
        
        Iterable<? extends LocalPlayer> targets = Lists.newArrayList();
        
        // Detect arguments based on the number of arguments provided
        if (args.argsLength() == 0) {
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(worldGuard.checkPlayer(sender));
            
            // Check permissions!
            sender.checkPermission("worldguard.slay");
        } else if (args.argsLength() == 1) {            
            targets = worldGuard.getPlatform().getMatcher().matchPlayers(sender, args.getString(0));
            
            // Check permissions!
            sender.checkPermission("worldguard.slay.other");
        }

        String senderName = sender.getDisplayName();
        for (LocalPlayer player : targets) {
            player.runOnOwnedThread(() -> {
                player.setHealth(0);
            
                // Tell the user
                if (player.equals(sender)) {
                    player.print("Slain!");
                
                } else if (!args.hasFlag('s')) {
                    player.print("Slain by " + senderName + ".");
                
                }
                if (!player.equals(sender)) {
                    sender.print("Slain " + player.getName() + ".");
                }
            }, () -> sender.printError("Player left before the operation could run: " + player.getName()));
        }
    }
    
    @Command(aliases = {"locate"}, usage = "[player]", desc = "Locate a player", max = 1)
    @CommandPermissions({"worldguard.locate"})
    public void locate(CommandContext args, Actor sender) throws CommandException {
        LocalPlayer player = worldGuard.checkPlayer(sender);
        
        if (args.argsLength() == 0) {
            player.setCompassTarget(new Location(player.getWorld(), player.getWorld().getSpawnPosition().toVector3()));
            
            sender.print("Compass reset to spawn.");
        } else {
            LocalPlayer target = worldGuard.getPlatform().getMatcher().matchSinglePlayer(sender, args.getString(0));
            player.setCompassTarget(target.getLocation());
            
            sender.print("Compass repointed.");
        }
    }
    
    @SuppressWarnings("removal")
    @Command(aliases = {"stack", ";"}, usage = "", desc = "Stack items", max = 0)
    @CommandPermissions({"worldguard.stack"})
    public void stack(CommandContext args, Actor sender) throws CommandException {
        LocalPlayer player = worldGuard.checkPlayer(sender);

        WorldGuard.getInstance().getPlatform().stackPlayerInventory(player);

        player.print("Items compacted into stacks!");
    }
}
