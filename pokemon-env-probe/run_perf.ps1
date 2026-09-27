param([ValidateSet(0,1)][int]$Mode = 0)
$ErrorActionPreference = 'Stop'
$art = $PSScriptRoot
$prefix = if ($Mode -eq 0) { 'perf' } else { 'perf-stage2' }
$adb = (Get-Command adb).Source
& $adb push (Join-Path $art 'perf_capture') /data/local/tmp/pokemon-perf-capture | Out-Null
& $adb shell run-as top.niunaijun.blackbox cp /data/local/tmp/pokemon-perf-capture files/pokemon-perf-capture | Out-Null
& $adb shell run-as top.niunaijun.blackbox chmod 700 files/pokemon-perf-capture | Out-Null
& $adb shell am force-stop --user 0 top.niunaijun.blackbox | Out-Null
$launchTime = Get-Date -Format o
$launch = Start-Process -FilePath $adb -ArgumentList @('shell','am','start','--user','0','-n','top.niunaijun.blackbox/top.niunaijun.blackboxa.view.main.ShortcutActivity','--es','pkg','jp.pokemon.pokemontcgp','--ei','userId','0') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $art "$prefix-launch.txt") -RedirectStandardError (Join-Path $art "$prefix-launch-err.txt") -PassThru
$guestPid = $null
for ($i = 0; $i -lt 150; $i++) {
    foreach ($line in (& $adb shell ps -A -o PID,UID,NAME)) {
        if ($line -match '^\s*(\d+)\s+10355\s+(jp\.pokemon\.pokemontcgp|top\.niunaijun\.blackbox:p0)\s*$') {
            $guestPid = $Matches[1]
            break
        }
    }
    if ($guestPid) { break }
    Start-Sleep -Milliseconds 50
}
if (!$guestPid) { throw 'No guest PID' }
Write-Output "FOUND_GUEST=$guestPid AT=$(Get-Date -Format o)"
$base = $null
for ($i = 0; $i -lt 150; $i++) {
    $maps = & $adb shell run-as top.niunaijun.blackbox cat "/proc/$guestPid/maps"
    $mapLine = $maps | Where-Object { $_ -match 'r-xp 0460a000 .*split_config.arm64_v8a.apk' } | Select-Object -First 1
    if ($mapLine) {
        $startHex = ($mapLine -split '[- ]')[0]
        $base = [Convert]::ToInt64($startHex, 16) - 0x2f2e000
        break
    }
    Start-Sleep -Milliseconds 50
}
if (!$base) { throw 'No executable IL2CPP mapping' }
Write-Output "FOUND_BASE=$("0x{0:x}" -f $base) AT=$(Get-Date -Format o)"
$unityTid = $null
for ($i = 0; $i -lt 150; $i++) {
    foreach ($line in (& $adb shell ps -T -p $guestPid -o TID,COMM)) {
        if ($line -match '^\s*(\d+)\s+\[UnityMain\]\s*$') {
            $unityTid = $Matches[1]
            break
        }
    }
    if ($unityTid) { break }
    Start-Sleep -Milliseconds 50
}
if (!$unityTid) { throw 'No UnityMain TID' }
@{guestPid=$guestPid;unityTid=$unityTid;base=("0x{0:x}" -f $base);launchTime=$launchTime;armedTime=(Get-Date -Format o);mode=$Mode} | ConvertTo-Json | Set-Content (Join-Path $art "$prefix-run.json")
Write-Output "PID=$guestPid UNITY_TID=$unityTid BASE=$("0x{0:x}" -f $base) LAUNCH=$launchTime ARMING=$(Get-Date -Format o)"
$probe = Start-Process -FilePath $adb -ArgumentList @('shell','run-as','top.niunaijun.blackbox','./files/pokemon-perf-capture',$unityTid,("0x{0:x}" -f $base),'40',$Mode) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $art "$prefix-out.txt") -RedirectStandardError (Join-Path $art "$prefix-err.txt") -PassThru
Write-Output "PROBE_HOST_PID=$($probe.Id)"
