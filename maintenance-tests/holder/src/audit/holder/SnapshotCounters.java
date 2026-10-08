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

package audit.holder;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Isolated test only: count the original core calls without changing their results. */
public final class SnapshotCounters {
    private static final ConcurrentHashMap<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    public static void holder(boolean snapshot) { record(snapshot ? "holder.true" : "holder.false"); }
    public static void record(String kind) {
        increment("total." + kind);
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals("com.sk89q.worldguard.bukkit.listener.BlacklistListener") && frame.getMethodName().equals("onInventoryClick")) {
                increment("wg." + kind);
                increment(frame.getClassName() + "#" + frame.getMethodName() + "." + kind);
                return;
            }
        }
    }
    private static void increment(String key) { COUNTS.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet(); }
    public static void reset() { COUNTS.clear(); }
    public static Map<String, Long> counts() {
        Map<String, Long> snapshot = new TreeMap<>();
        COUNTS.forEach((key, value) -> snapshot.put(key, value.get()));
        return snapshot;
    }
}
