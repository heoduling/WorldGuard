# Exact-core teleport bridge

This companion core is required to make asynchronous teleports cancellable on the
supplied Shiroha bundler (runtime identity `mizuki 26.2-DEV-ver/26.2@28fe077`).
Installing the WorldGuard plugin alone does not repair the core's event pipeline.
The original native event is notification-only: it is not cancellable and its
return value is discarded. A WorldGuard listener alone cannot safely fix that.

The builder refuses any input except SHA-256
`aff6e1f1033f87e44c5b49fb77f44d6ac694dc388ec114ce3f72259211ba6b77`.
It modifies `Entity.class` and `Entity$QueuedAsyncTeleport.class`, adds two bridge
classes and a marker resource, and updates the bundler's inner-JAR checksum.
All other existing entries are compared byte-for-byte after the build. No runtime
agent is required. This is a binary patch with full patch source; it is not a
rebuild of unavailable Shiroha source.

Changes:

- On the source entity's owning thread, fire `PlayerTeleportEvent` before detach
  or chunk loading. Cancellation returns false without committing the teleport.
- Preserve accepted destination/world/rotation changes and the existing native
  notification. Keep null rotation semantics when the event does not change them.
- Check retained player passengers; cancellation or conflicting passenger
  redirection rejects the whole vehicle move. Unmounted passengers stay behind.
- Preserve causes in queued requests and run each dequeued request through the
  same checks. Complete rejected callbacks and continue draining the queue,
  including after synchronous successful requests.

Build with Python 3 and JDK 25 (Windows example):

```
python build.py --core ORIGINAL_BUNDLER --libraries RUNTIME/server/libraries --asm ASM_JAR --asm-tree ASM_TREE_JAR --java JDK25/bin --work D:/codex-build/wg-core --output PATCHED_BUNDLER
```

Use the original core's resolved libraries, excluding stale local SNAPSHOT API
variants (the script filters these). ASM and ASM Tree 9.9.1 were used. Keep the
work directory ASCII on Windows. Test using `../compat` before deployment.
Install the patched bundler as the actual server launch JAR during a full stop,
alongside the matching WorldGuard release. Keep original files for rollback.
Do not install this bundler in `plugins`, hot reload the core, or apply this
transform to a different server build. Do not copy the disposable test world or
offline-mode server properties into production.

The exact-core tests exercise plugin/native API calls. They do not constitute
complete survival-client portal, end-credit, or all third-party teleport-plugin
coverage. Other plugins will now receive an event previously missing on this
core; their cancellation and redirection behavior therefore takes effect.
