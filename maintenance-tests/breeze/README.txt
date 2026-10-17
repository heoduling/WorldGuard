WorldGuard wind-charge regression (isolated server only)

Core: the unmodified Shiroha bundle supplied on 2026-10-17.
Bundle SHA-256: ea152171a3b8bac185056077f9c8674b3b8fa12420ebc0c5ea279fc38eb7345e
Extracted server SHA-256: 7fe4526f202c0781de4b3a50027d7e32d903ffc4072973c68bc19401f9623341
Java 25; WorldEdit 7.4.5-foliafix-1; original core thread checks enabled.

The probe changes test worlds, permissions, entities and region flags. Do not
install it on production. It requires two real protocol-776 test clients named
BreezeTestA and BreezeTestB, 12000 blocks apart. Tests verify actual ownership.

Reproduction:
1. Prepare RUNTIME/server with the exact core, dependency libraries, WorldEdit,
   WorldGuard, and disposable flat worlds. Bind to 127.0.0.1:53516, offline mode,
   network-compression-threshold=-1, allow-flight=true. Use two Folia tick threads.
2. Compile BreezeProbe.java with Java 25, UTF-8, and the exact core API plus the
   server's libraries, WorldEdit and WorldGuard on the classpath. Package classes
   and plugin.yml as server/plugins/WorldGuardBreezeProbe.jar.
3. The accompanying audit evidence includes server-control.js, wire-bots.js and
   packet-ids.json. Copy these to RUNTIME and start the controllers there. Adjust
   the Java executable path if necessary. Both controllers bind to loopback only.
   The server controller writes RUNTIME/evidence/runtime.log; bot control is on
   port 53517 and console control on 53518.
4. Run: python run.py RUNTIME baseline-full (WorldGuard -4).
   Stop the server normally, archive runtime.log, replace ONLY WorldGuard.jar
   with -5, restart, and run: python run.py RUNTIME fixed.
5. The fixed matrix requires 91 assertions and zero failures, event-dispatch
   errors or region-ownership violations. Stop the server and bot controller
   afterwards. Keep all test artifacts separate from production.

Matrix: local Breeze, remote Breeze, no shooter, remote player, local player
using an ordinary WindCharge; DENY and ALLOW; direct event checks and native
AbstractWindCharge.explode(). Native tests check events, real cow velocity and
real lever state. One additional check covers a foreign metadata parent, with
try/finally removal. Player tests use non-OP clients without bypass permission.
Commands are rate-limited to avoid vanilla chat-spam disconnection.

The remote fixture sets the projectile's shooter while both are owned, then
teleports the SHOOTER away and verifies the retained foreign reference. Moving
the projectile itself cleared its shooter on this core and was not a valid
reproduction. Local Breeze tests use an explicitly null target. No reflection,
core transformation, Java agent or disabled ownership guard is used.

The Bukkit knockback event retains the direct projectile, whereas the Paper
event can identify its shooter. The extra plugin listener checks wind-charge
flags on the former; the core copies cancellation into the latter.

Build from repository root:
  gradlew.bat clean check :worldguard-bukkit:shadowJar -Dfile.encoding=GBK
The encoding option is needed on the tested GBK Windows host with a Chinese
workspace path; it addresses Gradle test-worker argument-file encoding. It is
not a server launch option or a plugin configuration change.

Scope: this suite validates these wind-charge paths, not every WorldGuard
feature, third-party plugin combination or hot-unload scenario. The previously
deferred non-cancellable async teleport limitation is unchanged.
