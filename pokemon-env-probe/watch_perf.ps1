param([ValidateSet(0,1,2,3,4)][int]$Mode = 1)
$ErrorActionPreference = 'Stop'
$art = $PSScriptRoot
$adb = (Get-Command adb).Source
$prefix = if ($Mode -eq 0) { 'perf-early' } elseif ($Mode -eq 1) { 'perf-stage2-early' } elseif ($Mode -eq 2) { 'perf-stage3-early' } elseif ($Mode -eq 3) { 'perf-inputs-early' } else { 'perf-combined-early' }
Write-Output "WATCHING_AT=$(Get-Date -Format o)"
$guestPid = $null
for ($i = 0; $i -lt 200; $i++) {
    foreach ($line in (& $adb shell ps -A -o PID,UID,NAME)) {
        if ($line -match '^\s*(\d+)\s+10355\s+(jp\.pokemon\.pokemontcgp|top\.niunaijun\.blackbox:p0)\s*$') {
            $guestPid = $Matches[1]
            break
        }
    }
    if ($guestPid) { break }
    Start-Sleep -Milliseconds 30
}
if (!$guestPid) { throw 'No guest PID' }
Write-Output "FOUND_GUEST=$guestPid AT=$(Get-Date -Format o)"
$base = $null
for ($i = 0; $i -lt 200; $i++) {
    $maps = & $adb shell run-as top.niunaijun.blackbox cat "/proc/$guestPid/maps"
    $line = $maps | Where-Object { $_ -match 'r-xp 0460a000 .*split_config.arm64_v8a.apk' } | Select-Object -First 1
    if ($line) {
        $base = [Convert]::ToInt64(($line -split '[- ]')[0], 16) - 0x2f2e000
        break
    }
    Start-Sleep -Milliseconds 30
}
if (!$base) { throw 'No executable IL2CPP mapping' }
Write-Output "FOUND_BASE=$("0x{0:x}" -f $base) AT=$(Get-Date -Format o)"
$unityTid = $null
for ($i = 0; $i -lt 200; $i++) {
    foreach ($line in (& $adb shell ps -T -p $guestPid -o TID,COMM)) {
        if ($line -match '^\s*(\d+)\s+\[UnityMain\]\s*$') {
            $unityTid = $Matches[1]
            break
        }
    }
    if ($unityTid) { break }
    Start-Sleep -Milliseconds 30
}
if (!$unityTid) { throw 'No UnityMain TID' }
@{guestPid=$guestPid;unityTid=$unityTid;base=("0x{0:x}" -f $base);armedTime=(Get-Date -Format o);mode=$Mode} | ConvertTo-Json | Set-Content (Join-Path $art "$prefix-run.json")
Write-Output "ARMING_TID=$unityTid AT=$(Get-Date -Format o)"
& $adb shell run-as top.niunaijun.blackbox ./files/pokemon-perf-capture $unityTid ("0x{0:x}" -f $base) 35 $Mode | Out-File -LiteralPath (Join-Path $art "$prefix-out.txt") -Encoding utf8
Write-Output "DONE_AT=$(Get-Date -Format o)"
