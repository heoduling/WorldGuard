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

package audit.wg;

import com.sk89q.worldguard.blacklist.Blacklist;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.listener.BlacklistListener;
import com.sk89q.worldguard.bukkit.util.PaperInterop;
import com.sk89q.worldguard.config.WorldConfiguration;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

import java.lang.reflect.*;
import java.util.*;

/** Isolated runtime regression; never part of the delivered WorldGuard jar. */
public final class WorldGuardProbe extends JavaPlugin implements Listener {
    private Method reset, counts;
    private final List<RegisteredListener> listeners = new ArrayList<>();
    private Field blacklistField;
    private Blacklist original, rules;
    private WorldConfiguration config;
    private final Map<String, Inventory> containers = new LinkedHashMap<>();
    private int passed;
    private boolean fixed;

    @Override public void onEnable() {
        try {
            Class<?> counters = Class.forName("audit.holder.SnapshotCounters", true, ClassLoader.getSystemClassLoader());
            reset = counters.getMethod("reset"); counts = counters.getMethod("counts");
            blacklistField = WorldConfiguration.class.getDeclaredField("blacklist");
            blacklistField.setAccessible(true);
            for (RegisteredListener candidate : InventoryClickEvent.getHandlerList().getRegisteredListeners()) {
                if (candidate.getListener() instanceof BlacklistListener) listeners.add(candidate);
            }
            if (listeners.isEmpty()) throw new IllegalStateException("Registered blacklist listener not found");
            String version = WorldGuardPlugin.inst().getDescription().getVersion();
            fixed = version.contains("holderfix") || version.contains("foliafix");
            rules = new Blacklist(false);
            rules.load(new java.io.File(getDataFolder(), "blacklist.txt"));
            getLogger().info("PROBE_READY fixed=" + fixed + " version=" + WorldGuardPlugin.inst().getDescription().getVersion());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equals("stop")) {
            Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown); return true;
        }
        if (!(sender instanceof Player player)) return true;
        player.getScheduler().execute(this, () -> {
            try {
                switch (args.length == 0 ? "run" : args[0]) {
                    case "run" -> run(player);
                    case "wire" -> {
                        blacklistField.set(config, args[1].equals("none") ? original : rules);
                        Inventory inventory = containers.get(args[2]);
                        inventory.clear();
                        inventory.setItem(0, new ItemStack(args[1].equals("deny") ? Material.DIAMOND : Material.STONE, 32));
                        player.getInventory().clear(); player.setItemOnCursor(null);
                        player.openInventory(inventory);
                        reset.invoke(null);
                        getLogger().info("WIRE_READY mode=" + args[1] + " container=" + args[2]);
                    }
                    case "report" -> getLogger().info("WIRE_REPORT " + Arrays.toString(args) + " counts=" + counts.invoke(null)
                            + " slot=" + player.getOpenInventory().getTopInventory().getItem(0)
                            + " cursor=" + player.getItemOnCursor());
                    case "restore" -> { blacklistField.set(config, original); player.closeInventory(); getLogger().info("RESTORED"); }
                    default -> throw new IllegalArgumentException(Arrays.toString(args));
                }
            } catch (Throwable t) { getLogger().severe("FAIL " + t); t.printStackTrace(); }
        }, null, 0L);
        return true;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private void pass(String message) { passed++; getLogger().info("PASS " + message); }
    private void dispatch(InventoryClickEvent event) throws Exception {
        for (RegisteredListener listener : listeners) listener.callEvent(event);
    }
    @SuppressWarnings("unchecked") private Map<String, Long> measured() throws Exception {
        return (Map<String, Long>) counts.invoke(null);
    }
    private long count(Map<String, Long> map, String key) { return map.getOrDefault(key, 0L); }
    private void run(Player player) throws Exception {
        config = WorldGuardPlugin.inst().getConfigManager().get(player.getWorld().getName());
        original = config.getBlacklist();
        require(original == null, "Production world blacklist must be null");
        require(!player.isOp(), "Player must not bypass protections");
        require(Bukkit.isOwnedByCurrentRegion(player), "Player owner thread");
        setup(player);
        try {
            for (Map.Entry<String, Inventory> entry : containers.entrySet()) {
                Inventory inventory = entry.getValue();
                player.openInventory(inventory);
                InventoryView view = player.getOpenInventory();
                for (String mode : List.of("none", "allow", "deny")) {
                    blacklistField.set(config, mode.equals("none") ? null : rules);
                    for (ClickType click : List.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT,
                            ClickType.SHIFT_RIGHT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK,
                            ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND, ClickType.MIDDLE)) {
                        inventory.setItem(0, new ItemStack(mode.equals("deny") ? Material.DIAMOND : Material.STONE, 32));
                        InventoryAction action = switch (click) {
                            case SHIFT_LEFT, SHIFT_RIGHT -> InventoryAction.MOVE_TO_OTHER_INVENTORY;
                            case NUMBER_KEY, SWAP_OFFHAND -> InventoryAction.HOTBAR_SWAP;
                            case DOUBLE_CLICK -> InventoryAction.COLLECT_TO_CURSOR;
                            case DROP -> InventoryAction.DROP_ONE_SLOT;
                            case CONTROL_DROP -> InventoryAction.DROP_ALL_SLOT;
                            case MIDDLE -> InventoryAction.CLONE_STACK;
                            default -> InventoryAction.PICKUP_ALL;
                        };
                        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, click, action, 0);
                        reset.invoke(null); dispatch(event);
                        Map<String, Long> observed = measured();
                        require(event.isCancelled() == mode.equals("deny"), "Cancellation " + entry.getKey() + "/" + mode + "/" + click);
                        require(inventory.getItem(0) != null && inventory.getItem(0).getAmount() == 32, "Container item retained");
                        long noarg = count(observed, "wg.holder.noarg"), snapshots = count(observed, "wg.snapshot");
                        if (fixed) {
                            require(noarg == 0 && snapshots == 0, "No snapshot path: " + observed);
                            if (!entry.getKey().equals("double")) require(count(observed, "wg.holder.false") == (mode.equals("none") ? 0 : 1), "Exactly one/zero lookup: " + observed);
                        } else if (!entry.getKey().equals("double")) {
                            require(noarg == (mode.equals("deny") ? 2 : 1), "Baseline holder calls: " + observed);
                            require(snapshots == noarg, "Baseline snapshot calls: " + observed);
                        }
                        pass("container=" + entry.getKey() + " mode=" + mode + " click=" + click + " counts=" + observed);
                    }
                }
            }
            edgeCases(player);
            equipment(player);
            stress(player);
            getLogger().info("SUITE_PASS cases=" + passed + " fixed=" + fixed);
        } finally { blacklistField.set(config, original); player.closeInventory(); }
    }

    private void setup(Player player) throws Exception {
        Location base = player.getLocation().getBlock().getLocation().add(2, 0, 0);
        for (int i = 0; i < 5; i++) require(Bukkit.isOwnedByCurrentRegion(base.clone().add(i, 0, 0)), "Container owner");
        Block barrel = base.getBlock(); barrel.setType(Material.BARREL, false);
        containers.put("barrel", ((Barrel) barrel.getState(false)).getInventory());
        Block chest = base.clone().add(1, 0, 0).getBlock(); chest.setType(Material.CHEST, false);
        containers.put("chest", ((Chest) chest.getState(false)).getBlockInventory());
        Block left = base.clone().add(3, 0, 0).getBlock(), right = base.clone().add(4, 0, 0).getBlock();
        left.setType(Material.CHEST, false); right.setType(Material.CHEST, false);
        org.bukkit.block.data.type.Chest l = (org.bukkit.block.data.type.Chest) left.getBlockData();
        org.bukkit.block.data.type.Chest r = (org.bukkit.block.data.type.Chest) right.getBlockData();
        l.setFacing(BlockFace.NORTH); r.setFacing(BlockFace.NORTH);
        l.setType(org.bukkit.block.data.type.Chest.Type.LEFT); r.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
        left.setBlockData(l, false); right.setBlockData(r, false);
        Inventory doubleChest = ((Chest) left.getState(false)).getInventory();
        require(doubleChest.getSize() == 54, "Real double chest"); containers.put("double", doubleChest);
        for (Map.Entry<String, Inventory> entry : containers.entrySet()) {
            reset.invoke(null); InventoryHolder snapshot = entry.getValue().getHolder();
            Map<String, Long> positive = measured();
            reset.invoke(null); InventoryHolder live = PaperInterop.getHolder(entry.getValue(), false);
            require(snapshot != null && live != null && snapshot.getClass() == live.getClass(), "Holder class semantics");
            require(count(measured(), "total.snapshot") == 0, "PaperInterop false skips snapshots");
            if (!entry.getKey().equals("double")) {
                require(count(positive, "total.snapshot") == 1, "Positive core snapshot control");
                require(!((TileState) live).isSnapshot() && ((TileState) snapshot).isSnapshot(), "Snapshot identity semantics");
            }
            pass("holder-contract " + entry.getKey() + " class=" + live.getClass().getName());
        }
    }

    private void edgeCases(Player player) throws Exception {
        blacklistField.set(config, rules);
        Inventory barrel = containers.get("barrel"); player.openInventory(barrel);
        InventoryView view = player.getOpenInventory(); barrel.setItem(0, new ItemStack(Material.DIAMOND));
        InventoryClickEvent cancelled = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        cancelled.setCancelled(true); reset.invoke(null); dispatch(cancelled);
        require(measured().isEmpty(), "ignoreCancelled avoids calls"); pass("already-cancelled");
        InventoryClickEvent empty = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL) {
            @Override public ItemStack getCurrentItem() { return null; }
        };
        reset.invoke(null); dispatch(empty); require(measured().isEmpty() && !empty.isCancelled(), "Null current item"); pass("null-item");
        HumanEntity nonPlayer = (HumanEntity) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{HumanEntity.class}, (p,m,a) -> null);
        InventoryClickEvent nonPlayerEvent = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL) {
            @Override public HumanEntity getWhoClicked() { return nonPlayer; }
        };
        reset.invoke(null); dispatch(nonPlayerEvent); require(measured().isEmpty() && !nonPlayerEvent.isCancelled(), "Non-player"); pass("non-player");
        Inventory ownerless = Bukkit.createInventory(null, 9); ownerless.setItem(0, new ItemStack(Material.DIAMOND));
        player.openInventory(ownerless);
        InventoryClickEvent ownerlessEvent = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        dispatch(ownerlessEvent); require(!ownerlessEvent.isCancelled() && ownerless.getItem(0) != null, "Null holder unchanged"); pass("null-holder");
        player.closeInventory(); player.getInventory().setItem(9, new ItemStack(Material.DIAMOND));
        InventoryClickEvent own = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 9, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        dispatch(own); require(own.isCancelled() && own.getCurrentItem().getType().isAir(), "Own inventory forbidden item cleared"); pass("player-inventory-clear");
    }

    private void equipment(Player player) throws Exception {
        blacklistField.set(config, rules); player.closeInventory(); player.getInventory().clear();
        InventoryView view = player.getOpenInventory();
        for (InventoryAction action : List.of(InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ALL,
                InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP, InventoryAction.MOVE_TO_OTHER_INVENTORY)) {
            for (boolean deny : List.of(false, true)) {
                ItemStack armor = new ItemStack(deny ? Material.DIAMOND_HELMET : Material.IRON_HELMET);
                player.getInventory().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                player.setItemOnCursor(armor); player.getInventory().setItem(0, armor); player.getInventory().setItem(9, armor);
                int slot = action == InventoryAction.MOVE_TO_OTHER_INVENTORY ? 9 : 5;
                InventoryClickEvent event = new InventoryClickEvent(view,
                        slot == 5 ? InventoryType.SlotType.ARMOR : InventoryType.SlotType.CONTAINER, slot,
                        action == InventoryAction.HOTBAR_SWAP ? ClickType.NUMBER_KEY : action == InventoryAction.MOVE_TO_OTHER_INVENTORY ? ClickType.SHIFT_LEFT : ClickType.LEFT,
                        action, 0);
                dispatch(event); require(event.isCancelled() == deny, "Equip " + action + "/" + deny); pass("equip " + action + " deny=" + deny);
            }
        }
        for (boolean deny : List.of(false,true)) {
            player.getInventory().setItemInOffHand(new ItemStack(deny ? Material.DIAMOND_HELMET : Material.IRON_HELMET));
            InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.ARMOR, 5, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP);
            dispatch(event); require(event.isCancelled() == deny, "Offhand equip"); pass("equip offhand deny=" + deny);
        }
        player.setItemOnCursor(null); player.getInventory().clear();
    }

    private void stress(Player player) throws Exception {
        Inventory barrel = containers.get("barrel");
        ItemStack paper = new ItemStack(Material.PAPER); ItemMeta meta = paper.getItemMeta();
        Component text = Component.text("Synthetic regression fixture " + "nested text ".repeat(100));
        meta.displayName(text); meta.lore(Collections.nCopies(10, text)); paper.setItemMeta(meta);
        ItemStack box = new ItemStack(Material.SHULKER_BOX); BlockStateMeta b = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) b.getBlockState();
        for (int i = 0; i < 27; i++) state.getInventory().setItem(i, paper);
        b.setBlockState(state); box.setItemMeta(b);
        for (int i = 0; i < 27; i++) barrel.setItem(i, box);
        player.openInventory(barrel);
        for (boolean enabled : List.of(false,true)) {
            blacklistField.set(config, enabled ? rules : null);
            reset.invoke(null); long[] elapsed = new long[40];
            for (int i = 0; i < elapsed.length; i++) {
                InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                long start = System.nanoTime(); dispatch(event); elapsed[i] = System.nanoTime() - start;
                require(!event.isCancelled(), "Stress item allowed");
            }
            Arrays.sort(elapsed); Map<String, Long> observed = measured();
            require(count(observed,"wg.snapshot") == (fixed ? 0 : elapsed.length), "Stress snapshot count");
            pass("stress blacklist=" + enabled + " events=40 median.us=" + elapsed[20]/1000 + " counts=" + observed);
        }
    }
}
