# Plugin-only Folia regressions

Use a disposable server with Java 25 and the ORIGINAL Shiroha bundler, SHA-256
`aff6e1f1033f87e44c5b49fb77f44d6ac694dc388ec114ce3f72259211ba6b77`.
Its extracted server JAR hash is
`e9ea932c10dc685ece1d2035dbebd405d7a674db9374b062c03cda0857042cd8`.
Do not modify the core, use a Java agent, or inject/transform core classes.

Prepare RUNTIME/server with the original bundler as server.jar, WorldEdit 7.4.4,
the plugin as plugins/WorldGuard.jar and a copy of the production WG config.
Use disposable flat worlds, server-ip=127.0.0.1, server-port=53486,
online-mode=false, network-compression-threshold=-1,
enforce-secure-profile=false, allow-flight=true. Start/stop once to resolve the
libraries. These test-server settings must not be deployed to production.

The probe uses ASM 9.10.1 only to READ plugin bytecode for the linkage scan.
Place it in RUNTIME/agent/lib/asm-9.10.1.jar (legacy directory name; no agent is
launched). SHA-256:
`ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb`.
Copy ../holder/wire-bots.js and ../holder/packet-ids.json to RUNTIME.

1. Run build.ps1 -RuntimeRoot RUNTIME -OutputDirectory PROBE_BUILD. This builds
   only the test plugin. It changes health, regions and entities; never install
   it on production. Do not install the historical holder probe.
2. Start the server with `java -Xms512m -Xmx2g -jar server.jar --nogui`; start
   `node wire-bots.js` in RUNTIME. Use hidden processes on Windows.
3. POST JSON {"action":"connect","name":"WgBot"} to 127.0.0.1:53487. Wait
   for login and initial teleport; run run.ps1 -RuntimeRoot RUNTIME.
4. Connect WgBot2, wait for login, run its `far,seed` cases via -Player WgBot2.
   Run WgBot's `seed,halt`. Verify different region IDs in the log.
5. Seed both players again. Run WgBot's `halt-join`, connect WgBot3, wait for
   join, then run WgBot's `check-halt` to test the join cleanup path.
6. Send `regress stop` through the bot endpoint, wait for shutdown and stop the
   bot process. Save logs and hashes. No runtime instrumentation is required.

This suite tests 80 actual sulfur-cube deaths/removals with synthetic interaction
events, owner-thread console commands with real drops, explicit report omission,
reload, real natural spawn ticks plus CUSTOM spawn controls, two-region cleanup
and runtime API linkage. It does not claim to fix async teleport bypasses.

The native async teleport issue is deliberately deferred. The earlier core
patch and private-queue mutation tests have been removed from this release.
Baseline: upstream version/7.0.x `4181ebfe364e6e99c87c1166cad7c5715e54553a`.
