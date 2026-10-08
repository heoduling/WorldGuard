# Folia compatibility regressions

Use only a disposable server; this probe changes health, inventory, regions and
entities. It is not part of the delivered WorldGuard plugin. Reuse the exact-core
environment described in `../holder/README.md`, with the companion core from
`../core-bridge` and Java 25. Bind both server and bot control to localhost.

1. Install the built WorldGuard as `server/plugins/WorldGuard.jar`, plus WorldEdit
   7.4.4. Start/stop the patched core once to unpack its version and libraries.
2. Run `build.ps1 -RuntimeRoot RUNTIME -OutputDirectory PROBE_BUILD`. Start the
   server with no holder probe/agent for this phase. Start `node wire-bots.js` in
   RUNTIME and connect `WgBot` with JSON `{"action":"connect","name":"WgBot"}`
   posted to `http://127.0.0.1:53487`. Wait for login and initial teleport.
3. Run `run.ps1 -RuntimeRoot RUNTIME`. It aborts on a failed assertion or timeout.
4. Connect WgBot2, then run `run.ps1 -RuntimeRoot RUNTIME -Player WgBot2
   -Commands far,seed`. Run WgBot's `seed,halt`. Confirm different region IDs in
   the log; physical distance alone is not proof of separate Folia regions.
5. Seed both players again, run WgBot's `halt-join`, connect WgBot3, wait for join,
   then run WgBot's `check-halt`. This separately tests the join cleanup path.
6. Run `regress stop` through the bot control endpoint. Save logs. Remove this
   probe and rerun `../holder` with its agent to verify the original optimization.

Checks include cancellable async entry/exit, bypass, teleport causes, third-party
cancellation/redirection, mounted/unmounted passengers, metadata retention after
80 sulfur-cube deaths/removals, real console commands with inventory drops,
diagnostic report and reload, final spawn denial without pre-spawn backoff,
two-region halt cleanup, preservation of tamed wolves/armor stands, and runtime
linkage of all WorldGuard classes against the supplied core and WorldEdit.

Sulfur interactions are synthetic Bukkit events on real runtime entities. Spawn
tests combine real natural ticks and explicit CUSTOM spawn calls. The `queue`
case uses Bukkit's API; `queue-native` deterministically inserts requests into
the core's actual private queue while a move is pending, via reflection. The
latter validates queue processing and preserved causes, not normal player packet
reachability. It also checks successful requests after a denial and synchronous
completion so no futures are stranded. These distinctions must remain in reports.

The plugin branch is based on the latest upstream `version/7.0.x` revision
`4181ebfe364e6e99c87c1166cad7c5715e54553a`, verified 2026-10-08. Upstream `master`
is the separate 7.1.0 development line and is not this release's base.
