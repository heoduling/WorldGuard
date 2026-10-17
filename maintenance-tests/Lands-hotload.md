# Lands 7.28.0 compatibility boundary

WorldGuard foliafix-6 supports prepared PlugManX 3.1.0-Beta.2 unload while the
exact local Lands 7.28.0-foliafix-1 compatibility build remains enabled.
Original Lands is intentionally refused: it retains RegionManager, StateFlag,
ProtectedRegion and plugin references from the outgoing WorldGuard loader.

The paired build uses `landsHotloadQuery(String,Object[])`, protocol 1, with
JDK result values only. Neither Methods nor WorldGuard objects are cached by
Lands. The original owner's/member's/flag queries and importer are retained.
WorldGuard registers lands-claim before deserializing regions; prepared unload
stops accepting and drains bridge queries without blocking region ticks.
Other dependent plugins and custom flags still require a full restart.

The first installation of the pair requires a full server restart. Once the
pair is installed, WorldGuard alone can be unloaded/replaced/loaded. Its
protection is absent during the intentional unload gap; Lands keeps running.
This does not implement hot replacement of Lands or WorldEdit themselves.

Runtime evidence and the independent Bukkit-only fixture live in the local
`audit/worldguard-lands-hotupdate-20261017` workspace and the delivery bundle.
Lands is proprietary; its original JAR and full decompiled source are not
included in this public repository.
