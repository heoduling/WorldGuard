# Blacklist holder regression

This test-only plugin and Java agent exercise the installed WorldGuard JAR on an
isolated server. They are not packaged in WorldGuard and must not be deployed to
production. The agent counts calls without replacing their arguments or results.

Baseline: EngineHub/WorldGuard `4181ebfe364e6e99c87c1166cad7c5715e54553a`.
Runtime used: supplied Shiroha bundler, SHA-256
`aff6e1f1033f87e44c5b49fb77f44d6ac694dc388ec114ce3f72259211ba6b77`;
actual startup identity `mizuki 26.2-DEV-ver/26.2@28fe077`, Minecraft protocol 776,
Java 25, WorldEdit 7.4.4. `packet-ids.json` and the small TCP client are specific to
this protocol. Re-export and review the packet map before using a different core.

## Reproduce

1. Prepare a separate `RUNTIME/server` with the supplied core as `server.jar`.
   Bind it to `127.0.0.1:53486`, set `online-mode=false`,
   `network-compression-threshold=-1`, `enforce-secure-profile=false`,
   `allow-flight=true`, and use a disposable flat world. Accept the Minecraft
   EULA if applicable. Start and stop it once to resolve its libraries.
2. Install WorldEdit and the WorldGuard version to test. Copy the supplied
   WorldGuard configuration into this isolated server. The main world's
   production blacklist must be empty (comments only), as it was in this case.
   Do not publish production configuration or credentials.
3. Run `build-runtime-probe.ps1 -RuntimeRoot RUNTIME -JavaHome JDK25
   -WorldGuardJar WG_JAR -WorldEditJar WE_JAR`. It downloads pinned ASM 9.10.1
   into the test directory, verifies its checksum, then compiles the probe and
   agent with the resolved server libraries.
4. From `RUNTIME/server`, start Java with
   `-Xms512m -Xmx2g -javaagent:../agent/SnapshotAgent.jar -jar server.jar --nogui`.
   Write that Java process ID to `server/pid.txt`. From `RUNTIME`, start
   `node wire-bots.js` (control endpoint `127.0.0.1:53487`). Use hidden processes
   on Windows when automating these steps.
5. Run `RUNTIME/run-runtime-tests.ps1 -Phase production` for 7.0.18,
   `-Phase upstream` for the unpatched upstream head, or `-Phase fixed` for the
   patched build. The probe identifies the patched build by `holderfix` or `foliafix` in its
   version. Each normal run performs 114 checks plus nine actual TCP inventory
   clicks, restores the in-memory production blacklist and stops the server.

The 114 checks include three exact-core holder contracts, 90 container/mode/click
combinations, five guards and player-inventory clearing checks, 14 equip cases,
and two synthetic nested-item stress runs of 40 events each. Synthetic event
tests invoke all registered BlacklistListener click/creative executors with the
server's normal cancelled-event filtering; the nine TCP clicks use the complete
server event pipeline and assert final slot/cursor contents. Stress timing is
instrumented and is not a production latency benchmark.

The double-chest implementation overrides holder methods and creates no block
snapshot in the baseline either; it is tested for unchanged identity and item
protection, not counted as another snapshot bug. No original incident container
NBT was supplied, so these tests do not reproduce the reported 20.98-second pause.

For the plugin build use the Gradle Wrapper with Java 25:
`gradlew.bat clean check :worldguard-bukkit:shadowJar`. On the tested Windows
environment the test worker failed to load classes from the Chinese checkout
path; an identical source copy in an ASCII build directory passed all 177
upstream tests. Local proxy JVM flags, if required, are environment settings and
are not committed to the build.
