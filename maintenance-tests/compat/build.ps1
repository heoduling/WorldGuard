param([Parameter(Mandatory)][string]$RuntimeRoot,[Parameter(Mandatory)][string]$OutputDirectory,[string]$JavaHome='D:/java')
$ErrorActionPreference='Stop'
$runtime=(Resolve-Path -LiteralPath $RuntimeRoot).Path
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$output=(Resolve-Path -LiteralPath $OutputDirectory).Path
$classes=Join-Path $output 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$libs=@(Get-ChildItem "$runtime/server/libraries" -Recurse -Filter '*.jar' | Where-Object FullName -NotMatch 'local-SNAPSHOT|26\.2\.local' | ForEach-Object FullName)
$asm="$runtime/agent/lib/asm-9.10.1.jar"
$cp=(@($asm,"$runtime/server/versions/26.2/shiroha-26.2.jar")+$libs+@("$runtime/server/plugins/WorldGuard.jar","$runtime/server/plugins/worldedit-bukkit-7.4.4.jar")) -join ';'
$sources=@(Get-ChildItem "$PSScriptRoot/src" -Recurse -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' })
$argfile=Join-Path $output 'javac.args'
[IO.File]::WriteAllText($argfile,('-encoding UTF-8 -classpath "'+$cp.Replace('\','/')+'" -d "'+$classes.Replace('\','/')+'" '+($sources -join ' ')),[Text.UTF8Encoding]::new($false))
& "$JavaHome/bin/javac.exe" "@$argfile"
if($LASTEXITCODE){throw 'Probe compilation failed'}
Push-Location $classes
try { & "$JavaHome/bin/jar.exe" xf $asm org } finally { Pop-Location }
& "$JavaHome/bin/jar.exe" cf "$runtime/server/plugins/WorldGuardRegressionProbe.jar" -C $classes . -C $PSScriptRoot plugin.yml
if($LASTEXITCODE){throw 'Probe packaging failed'}
