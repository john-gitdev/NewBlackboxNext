$ErrorActionPreference = 'Stop'
$art = $PSScriptRoot
$adb = (Get-Command adb).Source
$lldb = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\lldb.exe"
$serverBin = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\19\lib\linux\aarch64\lldb-server"
& $adb push $serverBin /data/local/tmp/blackbox-lldb-server19 | Out-Null
& $adb shell run-as top.niunaijun.blackbox cp /data/local/tmp/blackbox-lldb-server19 files/blackbox-lldb-server19 | Out-Null
& $adb shell run-as top.niunaijun.blackbox chmod 700 files/blackbox-lldb-server19 | Out-Null
& $adb shell am force-stop --user 0 top.niunaijun.blackbox | Out-Null
$launch = Start-Process -FilePath $adb -ArgumentList @('shell','am','start','--user','0','-n','top.niunaijun.blackbox/top.niunaijun.blackboxa.view.main.ShortcutActivity','--es','pkg','jp.pokemon.pokemontcgp','--ei','userId','0') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $art 'launch.txt') -RedirectStandardError (Join-Path $art 'launch-err.txt') -PassThru
$guestPid = $null
for ($i = 0; $i -lt 120; $i++) {
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
Write-Output "GUEST_PID=$guestPid"
$mapLine = $null
for ($i = 0; $i -lt 100; $i++) {
    $maps = & $adb shell run-as top.niunaijun.blackbox cat "/proc/$guestPid/maps"
    $mapLine = $maps | Where-Object { $_ -match 'r-xp 0460a000 .*split_config.arm64_v8a.apk' } | Select-Object -First 1
    if ($mapLine) { break }
    Start-Sleep -Milliseconds 50
}
if (!$mapLine) { throw 'No executable IL2CPP mapping' }
$startHex = ($mapLine -split '[- ]')[0]
$base = [Convert]::ToInt64($startHex, 16) - 0x2f2e000
"0x{0:x}" -f $base | Set-Content (Join-Path $art 'base.txt')
$mapLine | Set-Content (Join-Path $art 'exec-map.txt')
Write-Output ("BASE=0x{0:x}" -f $base)
$server = Start-Process -FilePath $adb -ArgumentList @('shell','run-as','top.niunaijun.blackbox','./files/blackbox-lldb-server19','gdbserver','127.0.0.1:5039','--attach',$guestPid) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $art 'server-out.txt') -RedirectStandardError (Join-Path $art 'server-err.txt') -PassThru
Start-Sleep -Milliseconds 350
& $adb forward tcp:5039 tcp:5039 | Out-Null
@{ guestPid=$guestPid; serverHostPid=$server.Id } | ConvertTo-Json | Set-Content (Join-Path $art 'active-pids.json')
Write-Output "SERVER_HOST_PID=$($server.Id) ATTACHING_AT=$(Get-Date -Format o)"
& $lldb --batch -s (Join-Path $art 'capture_boot.lldb') 2>&1 | Tee-Object -FilePath (Join-Path $art 'lldb-out.txt')
