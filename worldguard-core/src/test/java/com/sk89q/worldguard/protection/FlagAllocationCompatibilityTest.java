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

package com.sk89q.worldguard.protection;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.MapFlag;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlagAllocationCompatibilityTest {

    private static final StateFlag[] STATES = {
        Flags.BUILD, Flags.BLOCK_BREAK, Flags.BLOCK_PLACE, Flags.PISTONS,
        Flags.PASSTHROUGH, Flags.CHEST_ACCESS, Flags.PVP
    };

    @Test
    void matchesDeliveredVersionAcrossPriorityParentsGroupsAndDefaults() throws Exception {
        String input = System.getProperty("worldguard.baselineJar");
        assumeTrue(input != null, "Pass -PbaselineJar with the previous delivered JAR");
        try (ZipFile baseline = new ZipFile(Path.of(input).toFile())) {
            Class<?> previous = new CalculatorLoader(baseline).loadClass(FlagValueCalculator.class.getName());
            Random random = new Random(0x574739L);
            StringFlag text = new StringFlag("allocation-text");
            MapFlag<String, String> map = new MapFlag<>("allocation-map", new StringFlag(null), text);
            List<RegionAssociable> subjects = new ArrayList<>();
            subjects.add(null);
            subjects.add(regions -> Association.NON_MEMBER);
            subjects.add(regions -> Association.MEMBER);
            subjects.add(regions -> Association.OWNER);
            // This subject depends on the whole visited ancestor chain, not only
            // the last region: it catches accidental removal of the seen list.
            subjects.add(regions -> regions.stream().anyMatch(r -> r.getId().endsWith("0"))
                    ? Association.MEMBER : Association.NON_MEMBER);
            long comparisons = 0;
            for (int layout = 0; layout < 1200; layout++) {
                List<ProtectedRegion> regions = new ArrayList<>();
                int count = layout < 10 ? layout : random.nextInt(9);
                for (int i = 0; i < count; i++) {
                    ProtectedRegion region = new ProtectedCuboidRegion("r" + i,
                            BlockVector3.ZERO, BlockVector3.at(10, 10, 10));
                    region.setPriority(random.nextInt(5) - 2);
                    if (i > 0 && random.nextBoolean()) {
                        region.setParent(regions.get(random.nextInt(i)));
                    }
                    configure(region, text, map, random);
                    regions.add(region);
                }
                regions.sort(Comparator.comparingInt(ProtectedRegion::getPriority).reversed()
                        .thenComparing(ProtectedRegion::getId));
                ProtectedRegion global = random.nextBoolean() ? new GlobalProtectedRegion("__global__") : null;
                if (global != null) {
                    configure(global, text, map, random);
                }
                Object oldCalculator = previous.getConstructor(List.class, ProtectedRegion.class)
                        .newInstance(regions, global);
                Object current = new FlagValueCalculator(regions, global);
                for (RegionAssociable subject : subjects) {
                    for (StateFlag flag : STATES) {
                        compare(previous, oldCalculator, current, "queryState",
                                new Class<?>[]{RegionAssociable.class, StateFlag.class}, subject, flag);
                        compare(previous, oldCalculator, current, "queryAllValues",
                                new Class<?>[]{RegionAssociable.class, Flag.class}, subject, flag);
                        comparisons += 2;
                    }
                    for (Flag<?> flag : List.of(text, map)) {
                        compare(previous, oldCalculator, current, "queryValue",
                                new Class<?>[]{RegionAssociable.class, Flag.class}, subject, flag);
                        compare(previous, oldCalculator, current, "queryAllValues",
                                new Class<?>[]{RegionAssociable.class, Flag.class}, subject, flag);
                        comparisons += 2;
                    }
                    compare(previous, oldCalculator, current, "queryState",
                            new Class<?>[]{RegionAssociable.class, StateFlag[].class},
                            subject, new StateFlag[]{Flags.BLOCK_BREAK, Flags.BLOCK_PLACE, Flags.PISTONS});
                    for (String key : List.of("one", "missing")) {
                        compare(previous, oldCalculator, current, "queryMapValue",
                                new Class<?>[]{RegionAssociable.class, MapFlag.class, Object.class, Flag.class},
                                subject, map, key, text);
                    }
                    comparisons += 3;
                }
            }
            System.out.println("FLAG_BASELINE_COMPARISONS=" + comparisons);
        }
    }

    private static void configure(ProtectedRegion region, StringFlag text,
                                  MapFlag<String, String> map, Random random) {
        for (StateFlag flag : STATES) {
            int choice = random.nextInt(4);
            if (choice < 2) {
                region.setFlag(flag, choice == 0 ? StateFlag.State.ALLOW : StateFlag.State.DENY);
            }
            if (random.nextBoolean() && flag.getRegionGroupFlag() != null) {
                region.setFlag(flag.getRegionGroupFlag(), RegionGroup.values()[random.nextInt(RegionGroup.values().length)]);
            }
        }
        if (random.nextBoolean()) {
            region.setFlag(text, "value" + random.nextInt(4));
        }
        if (random.nextBoolean()) {
            region.setFlag(map, Map.of("one", "mapped" + random.nextInt(3)));
        }
    }

    private static Object outcome(Method method, Object calculator, Object[] args) throws Exception {
        try {
            Object result = method.invoke(calculator, args);
            return result instanceof Collection<?> values ? new HashSet<>(values) : result;
        } catch (InvocationTargetException ex) {
            return ex.getCause().getClass().getName() + ":" + ex.getCause().getMessage();
        }
    }

    private static void compare(Class<?> previous, Object oldCalculator, Object current,
                                String name, Class<?>[] types, Object... args) throws Exception {
        Object expected = outcome(previous.getMethod(name, types), oldCalculator, args);
        Object actual = outcome(FlagValueCalculator.class.getMethod(name, types), current, args);
        assertEquals(expected, actual, name);
    }

    private static final class CalculatorLoader extends ClassLoader {
        private final ZipFile jar;

        CalculatorLoader(ZipFile jar) {
            super(FlagAllocationCompatibilityTest.class.getClassLoader());
            this.jar = jar;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(FlagValueCalculator.class.getName())) {
                return super.loadClass(name, resolve);
            }
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try {
                    byte[] bytes = jar.getInputStream(jar.getEntry(name.replace('.', '/') + ".class")).readAllBytes();
                    type = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException ex) {
                    throw new ClassNotFoundException(name, ex);
                }
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }
}
