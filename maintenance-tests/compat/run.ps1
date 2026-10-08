param([Parameter(Mandatory)][string]$RuntimeRoot,[string[]]$Commands=@('tp deny','tp allow','tp bypass','tp exit','tp exit-allow','tp pearl','tp chorus','tp cancel','tp redirect','tp vehicle-deny','tp vehicle-allow','tp vehicle-dismount','queue','queue-native entry','queue-native pearl','metadata','metadata','commands heal','commands god','commands ungod','commands slay','commands report','commands reload','spawn','scan'),[string]$Player='WgBot')
$ErrorActionPreference='Stop'
$log=Join-Path $RuntimeRoot 'server/logs/latest.log'
foreach($command in $Commands) {
    $offset=@(Get-Content -LiteralPath $log).Count
    Invoke-RestMethod -Uri 'http://127.0.0.1:53487' -Method Post -ContentType 'application/json' -Body (@{action='send';name=$Player;command="regress $command"} | ConvertTo-Json -Compress) | Out-Null
    $pattern=if($command -eq 'scan') {'LINK_SCAN_RESULT'} else {'\[WorldGuardRegressionProbe\] PASS '}
    $deadline=[DateTime]::UtcNow.AddSeconds(50)
    do {
        $new=@(Get-Content -LiteralPath $log | Select-Object -Skip $offset)
        if($new -match '\[WorldGuardRegressionProbe\] FAIL') { throw ($new -match '\[WorldGuardRegressionProbe\] FAIL')[-1] }
        if($new -match $pattern) { $new | Where-Object {$_ -match 'PASS |LINK_SCAN_RESULT'}; break }
        if([DateTime]::UtcNow -ge $deadline) { throw "Timeout $command" }
        Start-Sleep -Milliseconds 150
    } while($true)
    Start-Sleep -Milliseconds 500
}
