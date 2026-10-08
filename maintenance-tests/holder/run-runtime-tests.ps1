param([string]$Phase = 'production', [switch]$SkipSuite)
$ErrorActionPreference = 'Stop'
$log = Join-Path $PSScriptRoot 'server/logs/latest.log'
$url = 'http://127.0.0.1:53487'
function Post($data) {
    $response = Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' -Body ($data | ConvertTo-Json -Compress)
    foreach ($entry in $response) { $entry }
}
function Wait-Log($pattern, $offset, $seconds=45) {
    $deadline=[DateTime]::UtcNow.AddSeconds($seconds)
    do {
        $lines=@(Get-Content -LiteralPath $log -Encoding UTF8 | Select-Object -Skip $offset)
        $fail=$lines | Select-String '\[WorldGuardHolderProbe\] FAIL'
        if($fail) { throw $fail[-1].Line }
        $found=$lines | Select-String -Pattern $pattern
        if($found) { return $found[-1].Line }
        Start-Sleep -Milliseconds 150
    } while([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $pattern"
}
function Send-Test($command, $pattern) {
    $offset=(Get-Content -LiteralPath $log).Count
    Post @{action='send';name='WgBot';command=$command} | Out-Null
    Wait-Log $pattern $offset
}
Wait-Log 'Done .*For help' 0 | Out-Null
$state=@(Post @{action='status'}) | Where-Object name -eq 'WgBot'
if(!$state -or $state.state -eq 'closed') { Post @{action='connect';name='WgBot'} | Out-Null }
$deadline=[DateTime]::UtcNow.AddSeconds(20)
do { $state=@(Post @{action='status'}) | Where-Object name -eq 'WgBot'; if($state.state -eq 'play') { break }; Start-Sleep -Milliseconds 150 } while([DateTime]::UtcNow -lt $deadline)
if($state.state -ne 'play') { throw 'Client failed to login' }
Start-Sleep -Milliseconds 1000
if (!$SkipSuite) { Send-Test 'wgprobe run' 'SUITE_PASS' }
foreach($container in @('barrel','chest','double')) {
    foreach($mode in @('none','allow','deny')) {
        Send-Test "wgprobe wire $mode $container" 'WIRE_READY'
        Start-Sleep -Milliseconds 400
        Post @{action='click';name='WgBot';slot=0} | Out-Null
        Start-Sleep -Milliseconds 400
        $report=Send-Test "wgprobe report $mode-$container" 'WIRE_REPORT'
        if ($mode -eq 'deny') {
            if($report -notmatch 'slot=ItemStack\{DIAMOND x 32' -or $report -notmatch 'cursor=ItemStack\{AIR') { throw "Denied transfer changed items: $report" }
        } else {
            if($report -notmatch 'slot=null' -or $report -notmatch 'cursor=ItemStack\{STONE x 32') { throw "Allowed transfer failed: $report" }
        }
        if($Phase -eq 'fixed' -and $report -match 'wg.snapshot=') { throw "Snapshot in fixed wire click: $report" }
        if($container -ne 'double') {
            if($Phase -eq 'fixed') {
                if($mode -eq 'none' -and $report -match 'wg.holder') { throw "Holder queried without blacklist: $report" }
                if($mode -ne 'none' -and $report -notmatch 'wg.holder.false=1') { throw "Expected one false holder call: $report" }
            } else {
                $expected=if($mode -eq 'deny'){2}else{1}
                if($report -notmatch "wg.snapshot=$expected") { throw "Baseline snapshot missing: $report" }
            }
        }
        "WIRE_PASS $Phase $container $mode $report"
    }
}
Send-Test 'wgprobe restore' 'RESTORED'
Post @{action='send';name='WgBot';command='wgprobe stop'} | Out-Null
Wait-Log 'Awaiting termination of I/O pool' 0
$serverProcess = [int](Get-Content (Join-Path $PSScriptRoot 'server/pid.txt'))
$deadline=[DateTime]::UtcNow.AddSeconds(30)
while(Get-Process -Id $serverProcess -ErrorAction SilentlyContinue) {
    if([DateTime]::UtcNow -gt $deadline) { throw 'Server did not exit after shutdown' }
    Start-Sleep -Milliseconds 150
}
"RUNTIME_PHASE_PASS $Phase"
