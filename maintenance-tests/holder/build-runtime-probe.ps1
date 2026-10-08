param(
    [Parameter(Mandatory)][string]$RuntimeRoot,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$WorldGuardJar,
    [Parameter(Mandatory)][string]$WorldEditJar
)
$ErrorActionPreference = 'Stop'
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
$WorldGuardJar = (Resolve-Path -LiteralPath $WorldGuardJar).Path
$WorldEditJar = (Resolve-Path -LiteralPath $WorldEditJar).Path
foreach($part in @('agent/lib','agent/classes','probe/classes','server/plugins/WorldGuardHolderProbe')) {
    New-Item -ItemType Directory -Force (Join-Path $RuntimeRoot $part) | Out-Null
}
$asm = Join-Path $RuntimeRoot 'agent/lib/asm-9.10.1.jar'
if (!(Test-Path -LiteralPath $asm)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar' -OutFile $asm
}
if ((Get-FileHash -LiteralPath $asm -Algorithm SHA256).Hash -ne 'ED825D10AB1399C8C0CB669E688CF0C8C82629B4C8399B58352B68E92CA10FCB') { throw 'ASM checksum mismatch' }
$agentClasses = Join-Path $RuntimeRoot 'agent/classes'
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $asm -d $agentClasses (Join-Path $PSScriptRoot 'src/audit/holder/SnapshotAgent.java') (Join-Path $PSScriptRoot 'src/audit/holder/SnapshotCounters.java')
if ($LASTEXITCODE) { throw 'Agent compilation failed' }
& (Join-Path $JavaHome 'bin/jar.exe') cfm (Join-Path $RuntimeRoot 'agent/SnapshotAgent.jar') (Join-Path $PSScriptRoot 'MANIFEST.MF') -C $agentClasses .
if ($LASTEXITCODE) { throw 'Agent packaging failed' }
& (Join-Path $JavaHome 'bin/jar.exe') cf (Join-Path $RuntimeRoot 'agent/SnapshotCounters.jar') -C $agentClasses audit/holder/SnapshotCounters.class
if ($LASTEXITCODE) { throw 'Counter packaging failed' }
$libraries = @(Get-ChildItem (Join-Path $RuntimeRoot 'server/libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName)
if (!$libraries.Count) { throw 'Start and stop the isolated core first to resolve its libraries' }
$classpath = ($libraries + @($WorldGuardJar, $WorldEditJar)) -join ';'
$arguments = '-encoding UTF-8 -classpath "' + $classpath.Replace('\','/') + '" -d "' + (Join-Path $RuntimeRoot 'probe/classes').Replace('\','/') + '" "' + (Join-Path $PSScriptRoot 'src/audit/wg/WorldGuardProbe.java').Replace('\','/') + '"'
$argfile = Join-Path $RuntimeRoot 'probe/javac.args'
[IO.File]::WriteAllText($argfile, $arguments, [Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') "@$argfile"
if ($LASTEXITCODE) { throw 'Probe compilation failed' }
& (Join-Path $JavaHome 'bin/jar.exe') cf (Join-Path $RuntimeRoot 'server/plugins/WorldGuardHolderProbe.jar') -C (Join-Path $RuntimeRoot 'probe/classes') . -C $PSScriptRoot plugin.yml
if ($LASTEXITCODE) { throw 'Probe packaging failed' }
Copy-Item (Join-Path $PSScriptRoot 'blacklist.txt') (Join-Path $RuntimeRoot 'server/plugins/WorldGuardHolderProbe/blacklist.txt')
foreach($name in @('run-runtime-tests.ps1','wire-bots.js','packet-ids.json')) { Copy-Item (Join-Path $PSScriptRoot $name) (Join-Path $RuntimeRoot $name) }
'Probe and agent built successfully'
