# Falling-block origin regression fixture

This is an isolated test plugin, never a production dependency. Compile against
the tested server API, WorldEdit and WorldGuard, then package `plugin.yml` and the
compiled `audit.origin` classes in `OriginProbe.jar`.

The native-entity cases use `spawnFallingBlock` and `teleportAsync`, followed by
the server's actual falling-block tick and block placement. They do not manually
fire the landing event. Prepare independent `world` and `world_the_end` worlds;
use only an empty test server, because the fixture modifies test locations.

From the console, run:

```
originprobe fall test cross false SAND
originprobe inspect test
```

WorldGuard 7 produces the reported `RegionFlagsListener.onPlaceBlock:67` exception
and logs `owned=false readable=true`. The fix must log
`owned=false readable=false preserved=true` and land SAND without that exception.
`preserved=true` verifies that the historical origin remains the root cause for
region association; it is not discarded to suppress the error.

Repeat `cross`, `far`, `local`, with both `true`/`false` for deny and SAND/GRAVEL.
For local cases, `owned=true readable=true` must be retained. Allowed cases land
the requested block; denied cases leave AIR. Cross-world WHITE_CONCRETE_POWDER
cases place water first and cover both the break and place delegate paths: the
allowed result is WHITE_CONCRETE and the denied result remains WATER.

`originprobe piston true` must retain the adjacent STONE. `originprobe piston
false` must extend the real piston and move STONE two blocks away. These cases
verify that the shared accessor still supplies local piston blocks to flag checks.

Tested with exact Shiroha `26.2-DEV-f10fd16`, Java 25, WorldEdit
`7.4.5-foliafix-1`, original Lands 7.28.0 and PlugManX 3.1.0-Beta.2. The full
controller, executable assertions and logs are included in the release evidence.
