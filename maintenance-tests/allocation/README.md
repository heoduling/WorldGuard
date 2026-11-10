# Isolated allocation regression fixture

Compile PerfProbe.java against the tested server, WorldGuard and WorldEdit libraries and package it with plugin.yml. Use an empty disposable world with original Lands loaded before WorldGuard. Never install this fixture on production. It replaces regions and blocks in its test locations.

Commands cover matrix, adapt, native, material, pistons, parallel, actor and bench. The task harness and raw runtime evidence are delivered separately. matrix verifies three holder reads on both cache hits and misses in the new build. native verifies actual core item transfers and conservation. parallel verifies distinct region owners. actor requires two connected test clients.

bench hopper LABEL 5 400000 and bench bulk LABEL 5 40000 warm up then run five rounds with identical regions. PERF_CARRIER records allocation on the carrier before and after one uninterrupted region callback. Require same=true and nonnegative counters. Report medians per callback, with the measurement scope stated. This test-only reflection is absent from the plugin artifact. Virtual-thread ThreadMXBean results are unavailable on this JDK. JFR weighted allocation samples were unstable and must not be used as benefit estimates. These callbacks are controlled fixtures and do not establish production heap, TPS or CPU savings.

Run the core differential oracle with -PbaselineJar pointing to the previous delivered WorldGuard JAR. It compares 126,000 results across priority, ancestor, membership, global, state, string and map flag inputs.
