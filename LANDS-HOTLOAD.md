# Original Lands 7.28.0 and WorldGuard hot unload

This build changes WorldGuard only. It accepts intentional loss of WorldGuard
protection during unload, including the Lands claim restriction imposed by WG.
It does not disable Lands, rewrite Lands classes, change Lands configuration,
or write Lands storage. The first installation requires a server restart.
Subsequent supported WG reloads use PlugManX 3.1.0-Beta.2.

## Exact compatibility scope

- Supplied original Lands 7.28.0 SHA-256:
  `7a106831c7c7424565ff09d79a96e46c20e9679f1de54fa6a5a449a8742c8027`.
- Shiroha wrapper SHA-256:
  `ea152171a3b8bac185056077f9c8674b3b8fa12420ebc0c5ea279fc38eb7345e`.
- WorldEdit 7.4.5-foliafix-1, Java 25, PlugManX 3.1.0-Beta.2.
- This is not support for hot unloading Lands or WorldEdit, older WG builds,
  arbitrary future adapter protocols, or unrelated plugins' WG integrations.

## Implementation

WorldGuard registers its own provider through Lands' existing provider API at
startup. The adapter subclasses the original provider because Lands' native
creation check explicitly tests that subtype and its StateFlag return type.
Its superclass retains Lands itself, not a replaceable WG plugin. Queries resolve
the active WG on every call, returning only JDK data across generations.
Absent WG produces the caller's normal no-provider result; there is no emergency
region lock, player kick, or retained protection listener.

The adapter lives in an embedded JAR, loaded through a closed-after-loading JDK
URLClassLoader whose parent is Lands. Its three classes are our code; no Lands
class is bundled, transformed, redefined, or written back to Lands.jar.
The configured claim flag is restored before WG region loading on every enable.

The unmodified Lands bytecode permanently links the first WG API classloader.
The 19 WG types referenced by that bytecode must be resolved against the same
first generation, including signatures first used by the native import command.
Otherwise a later import reproduces a WorldGuardPlatform loader constraint error.
The first API generation remains reachable until Lands/server restart; later WG
generations must not accumulate. This is a deliberate, measured compatibility
boundary, not a claim of completely collecting every historical WG API class.

Lands' native importer also calls the old RegionContainer.getLoaded(). The retired
container supplies read-only bounds/owner/member snapshots of the current WG,
using the first API's types. It never reopens the retired region database or
saves the snapshot. Lands' own importer still performs the import and all Lands
database writes. Its existing bounding-box treatment of non-global regions is
preserved. This adapter is not a general-purpose old WorldGuard API proxy.

Normal saving, player-state cleanup and correct Folia scheduler ownership remain.
Unknown integrations retain their existing restrictions; this change specifically
supports the supplied original Lands instead of removing dependency checks blindly.

## Build

Use the repository wrapper and a licensed, local original Lands input:

```powershell
$env:JAVA_HOME = 'D:/java'
./gradlew.bat clean check :worldguard-bukkit:shadowJar '-PlandsJar=D:/path/Lands-7.28.0.jar'
```

The compile-only archive omits two unused obfuscated root classes whose names
collide with Java packages. That archive is never distributed or used by the
server. The final plugin includes only our three adapter classes in
`compat/lands728-adapter.jar`, automatically extracted under WorldGuard's data
folder. No extra plugin or configuration key needs manual installation.

On Windows installations where Gradle's worker argfile cannot represent the
Unicode workspace path, set build output directories to an ASCII path using an
external init script. This is a build-environment workaround, not a server change.

Regression evidence must include: original Lands hash equality, actual native
creation and import, existing land membership/protection, WG absent vs active
claim results, repeated reloads, retained loader counts, JAR replacement, and
normal restart persistence. Isolated SQLite/item-currency tests do not certify
production MySQL, Vault transactions, unrelated plugins, or large player loads.
