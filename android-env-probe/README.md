# Android environment probe

This standalone diagnostic APK (`dev.codex.envprobe`) and peer APK (`dev.codex.envpeer`) compare ordinary Android, Multiple App, and NewBlackbox without modifying a game. The activity collects package/UID, installer, service/provider, Context path, Java/native filesystem, and limited app-readable runtime observations. It writes marker files only in its own CE/DE app storage. The peer app supplies cross-package service/provider checks.

## Build and capture

From the repository root, with Android SDK/JDK 17 and the configured NDK installed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\johnw\AppData\Local\Android\Sdk'
.\gradlew.bat -p android-env-probe :probe:assembleDebug :peer:assembleDebug --offline
```

Generated APKs are `android-env-probe/probe/build/outputs/apk/debug/probe-debug.apk` and `android-env-probe/peer/build/outputs/apk/debug/peer-debug.apk`; build directories are Git-ignored. Install the same APK versions normally and into each container under test. Import both packages for the two-package checks. Do not clear existing virtual or normal app data just to refresh a capture.

Launch the probe via its UI or `adb shell am start -n dev.codex.envprobe/.ProbeActivity` in the normal environment. Launch it through each container's ordinary guest UI for virtual runs. It emits one JSON record as numbered `ENVPROBE` logcat chunks. Save a run and decode it from this directory:

```powershell
$captureLog = Join-Path $env:TEMP 'envprobe-new-run-logcat.txt'
$captureJson = Join-Path $env:TEMP 'envprobe-new-run.json'
adb logcat -d -s ENVPROBE:I '*:S' > $captureLog
python android-env-probe/parse_logcat.py $captureLog $captureJson
python -m unittest discover -s android-env-probe -p 'test_*.py' -v
```

`parse_logcat.py` selects the last **complete** chunked record. The Python suite compares checked-in captures by default; it does not run a live device test. The current CE-path captures are `normal-framework-ce-v11.json`, `multiple-app-framework-ce-v11.json`, `blackbox-cold-framework-ce-v11.json`, `blackbox-user1-framework-ce-v11.json`, and `blackbox-restart-framework-ce-v11.json`, with matching logcat files. `ENVPROBE_NORMAL_CAPTURE`, `ENVPROBE_MULTIPLE_APP_CAPTURE`, and `ENVPROBE_BLACKBOX_CAPTURE` can override main capture paths for selected tests; inspect individual test files for additional capture keys.

The known baseline is **32 captured-result checks: 31 pass and one expected virtual-user-1 UID failure**. Earlier `v1`–`v10` snapshots are historical comparisons, not the latest baseline. The direct-boot receiver's unlocked controls do not demonstrate access before host credential unlock; [`verify_locked_boot.py`](verify_locked_boot.py) requires a genuinely locked observation. See the [master summary](../NEWBLACKBOX_MASTER_SUMMARY.md) for current engineering priorities and the [investigation archive](../POKEMON_INVESTIGATION.md) for interpretation and evidence limits.
