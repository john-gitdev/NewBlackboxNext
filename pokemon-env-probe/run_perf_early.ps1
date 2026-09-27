param([ValidateSet(0,1,2,3,4)][int]$Mode = 1)
$ErrorActionPreference = 'Stop'
$art = $PSScriptRoot
$adb = (Get-Command adb).Source
$prefix = if ($Mode -eq 0) { 'perf-early' } elseif ($Mode -eq 1) { 'perf-stage2-early' } elseif ($Mode -eq 2) { 'perf-stage3-early' } elseif ($Mode -eq 3) { 'perf-inputs-early' } else { 'perf-combined-early' }
& $adb push (Join-Path $art 'perf_capture') /data/local/tmp/pokemon-perf-capture | Out-Null
& $adb shell run-as top.niunaijun.blackbox cp /data/local/tmp/pokemon-perf-capture files/pokemon-perf-capture | Out-Null
& $adb shell run-as top.niunaijun.blackbox chmod 700 files/pokemon-perf-capture | Out-Null
& $adb shell am force-stop --user 0 top.niunaijun.blackbox | Out-Null
$watcher = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $art 'watch_perf.ps1'),'-Mode',$Mode) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $art "$prefix-watcher-out.txt") -RedirectStandardError (Join-Path $art "$prefix-watcher-err.txt") -PassThru
Start-Sleep -Milliseconds 250
$launchTime = Get-Date -Format o
$launch = Start-Process -FilePath $adb -ArgumentList @('shell','am','start','--user','0','-n','top.niunaijun.blackbox/top.niunaijun.blackboxa.view.main.ShortcutActivity','--es','pkg','jp.pokemon.pokemontcgp','--ei','userId','0') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $art "$prefix-launch.txt") -RedirectStandardError (Join-Path $art "$prefix-launch-err.txt") -PassThru
Write-Output "LAUNCHED_AT=$launchTime WATCHER_HOST_PID=$($watcher.Id)"
