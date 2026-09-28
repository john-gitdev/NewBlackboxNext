# NewBlackbox Master Summary

**Start here for current engineering state.** The repository handoff was consolidated on `main` at `5dc2160` (documentation only). The known-good production CE-path behavior is from `15c86e1`, and `089f1b0` records its Pokémon proof pack. This document describes that measured baseline; it is not a claim that every Android version or guest app works. [POKEMON_INVESTIGATION.md](POKEMON_INVESTIGATION.md) preserves the detailed evidence and historical hypotheses.

## 1. Project purpose

NewBlackbox is an Android userspace application-virtualization/container project. The host application runs installed guest packages under virtual package and user identities while providing Android framework, Binder, and filesystem compatibility. The aim is general Android behavior that works across apps. Pokémon TCG Pocket was a demanding integration case, not the sole purpose of the engine. Prefer general correctness fixes; evaluate any package-specific change on its evidence, scope, and regression risk rather than introducing one by default.

## 2. Current known-good baseline

| Baseline item | Measured state |
| --- | --- |
| Production CE-path milestone | `15c86e1` — separates logical guest CE paths from physical backing; no guest data migration |
| Later proof/documentation milestone | `089f1b0` — records working launches; no production code change |
| Repository handoff milestone | `5dc2160` — consolidates current documentation; no production compatibility change |
| Host/device | BlackBox `top.niunaijun.blackbox` 4.0.0, target SDK 28; Pixel 7 Pro, Android API 37 |
| Installed host APK | SHA-256 `aa1eda576042f2fc2b2ff5716295e8a481b24cc26fdf2606b0257e45b7f46a9a`, matching the arm64 debug build |
| Generic captured-result suite | 32 checks: 31 pass, one expected nonzero-virtual-user UID failure |
| Pocket integration | Pocket 1.7.2 (`versionCode=374561`): three cold launches and one reopen reached usable in-game UI; no historical early UnityMain SIGSEGV in those runs |
| Play Asset Delivery | Play Core still logged `onError(-5)` while the game reached usable UI |

The compact [known-good proof pack](pokemon-env-probe/known-good-15c86e1/baseline.txt) contains the exact APK identity, suite output, a representative launch log, and screenshots. The source behavior is at `15c86e1`; `089f1b0` documents the validation; `5dc2160` organizes the repository handoff. None establishes indefinite game stability or a single cause of the historical crash.

## 3. Architecture to preserve

- `Bcore/` holds the engine; `app/` is the Android host UI. `BlackBoxSystem`, virtual user/package managers, `SystemCallProvider`, proxy activities/providers, and `BActivityThread` start and bind guest processes. Guest names and virtual package/UID answers must not be confused with Linux process identity or the host user's Binder identity.
- Virtual packages/users have isolated internal data and package state. For CE storage, `BEnvironment.getBackingDataDir(package,user)` is an **internal physical path**, under `/data/user/0/top.niunaijun.blackbox/blackbox/data/user/<virtual-user>/<package>`. Installation, user creation/removal, and internal cleanup use backing paths.
- `BEnvironment.getLogicalDataDir(package)` is the **guest-facing CE namespace**, such as `/data/user/0/<package>`. `PackageManagerCompat` supplies this through `ApplicationInfo.dataDir` and the credential-protected aliases; guest `Context` CE directory APIs derive conventional paths from it.
- `IOCore` owns the logical-to-backing rules. Java/libcore path handling and the native `FileSystemHook` use those rules; the native layer covers common libc path operations including FORTIFY open/openat entry points. Canonical/real paths, `getcwd`, and `/proc/self/fd` links have logical presentation support. The generic suite tests Java/native access to the same content and inode, package/user isolation, and traversal/prefix boundaries.
- `BPackageManagerService` and its proxies supply virtual package, UID, service, provider, and installer-source information. System/open and missing package behavior remain distinct from installed virtual package answers. The virtual user-0 `getPackageUid()` inconsistency and unconditional Play Store installer answer were corrected; nonzero-user process/UID identity remains unresolved.
- `BEnvironment.getDeDataDir()` still places virtual **device-protected** data underneath the host's **credential-protected** root. Java/native DE routing after unlock works, but this does **not** establish true before-unlock direct boot. The DE bootstrap design is documented, not implemented.

Key source entry points: [`BEnvironment.java`](Bcore/src/main/java/top/niunaijun/blackbox/core/env/BEnvironment.java), [`IOCore.java`](Bcore/src/main/java/top/niunaijun/blackbox/core/IOCore.java), [`PackageManagerCompat.java`](Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java), [`BActivityThread.java`](Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java), and [`FileSystemHook.cpp`](Bcore/src/main/cpp/Hook/FileSystemHook.cpp). See the [CE path](POKEMON_INVESTIGATION.md#logical-ce-framework-paths-on-main-2026-09-27) and [native routing](POKEMON_INVESTIGATION.md#native-logical-path-routing-on-main-2026-09-27) sections for exact behavior and limits.

## 4. Major completed compatibility work

| Area | Earlier problem | Current result | Milestone |
| --- | --- | --- | --- |
| Virtual package UID, user 0 | `getPackageUid()` could fall through to a host UID inconsistent with virtual `ApplicationInfo.uid` | Installed virtual package queries use the virtual PM; system/open and missing cases are handled separately | `24bf9bd` |
| Installer/source metadata | Legacy installer query unconditionally answered `com.android.vending` | Virtual install-source metadata and legacy/modern answers made coherent, without a constant Play Store answer | `24bf9bd` |
| Native logical files | Direct libc access to conventional guest paths returned `EACCES` while Java access worked | Native path-taking functions route via IOCore rules; Java/native marker and inode checks pass | `517766e` |
| Boot broadcast stability | Null/incomplete receiver result crashed the broadcast loop | Narrow null-result guard; unlocked control receives both broadcasts | `f698547` |
| Direct-boot investigation | DE lived beneath host CE and locked behavior was unproven | Probe and architecture audit exist; **true DE bootstrap is not implemented** | `f698547`, `cc37df1`, `9f23ce2` |
| CE framework paths | Guest `ApplicationInfo` exposed host physical backing while logical path access already worked | Explicit logical CE presentation and physical internal helpers; generic isolation/routing checks pass | `15c86e1` |
| Generic regression coverage | Narrow snapshots left API inconsistencies hard to compare | Same diagnostic APK compared normal Android, Multiple App, and BlackBox; 32 captured-result assertions | `24bf9bd` onward |
| Pocket startup | Historical 16–20-second UnityMain SIGSEGV | Working build reaches title/onboarding and later UI in limited repeatability runs; causal chain not fully proven | `15c86e1`, proof at `089f1b0` |

## 5. Pokémon integration: conclusion and limits

Historically, BlackBox Pocket repeatedly hit an instruction-fetch `SIGSEGV / SEGV_MAPERR` on `UnityMain` after about 16–20 seconds. The exact installed `libil2cpp.so` code was identified: `+0x305fb00` calculates an unmapped target, `+0x305fb04` copies it to LR, and `+0x305fb08` branches. A historical boot invocation of `BootManager.RequestLaunch` read the physical BlackBox `ApplicationInfo.dataDir`, returned `IsVspaceApp()=1`, and was followed immediately by `Rainbow.OnTpr("dvsa",1)`. The old game-side crash decision also had another true input (`onLoggedIn`); no one value was proven necessary for the fault.

With general CE logical-path presentation, Pocket receives `/data/user/0/jp.pokemon.pokemontcgp`. Earlier integration launches reached title/onboarding, and the proof-pack's three cold launches plus one reopen reached in-game screens with the guest process alive beyond the old crash window. PAD `-5` persisted. This is a strong **before/after correlation**, not proof that changing the path string alone removed the crash. `IsVspaceApp()` and `dvsa` were intentionally **not remeasured** on the working build. The old branch alone does not establish an integrity mechanism or a permanent ban on future game-specific investigation. The full [investigation](POKEMON_INVESTIGATION.md) separates direct observations from hypotheses.

## 6. Current regression baseline

Run from the repository root:

```powershell
python -m unittest discover -s android-env-probe -p 'test_*.py' -v
```

Expected: **31 pass, one expected failure** across 32 captured-result checks. This checks preserved probe captures; it does not execute a new on-device launch. A healthy generic environment presents conventional logical CE framework paths, routes Java and native access to the same files, and retains distinct package/user backing. The current integration expectation is that Pocket gets past the old 16–20-second failure and reaches usable UI; PAD `-5` is still observable. Compare regressions with the [proof pack](pokemon-env-probe/known-good-15c86e1/baseline.txt) before changing the engine.

## 7. Known unresolved issues

1. **Nonzero virtual-user UID identity — highest general correctness priority.** Package APIs can describe a full virtual-user UID while the kernel process runs under host Android user 0. The suite's virtual-user-1 consistency assertion is the single expected failure. A trial that globally exposed the full virtual UID to host framework calls caused cross-user `SecurityException`; it was reverted. Future design must distinguish guest-facing package identity from host/kernel/Binder identity and test both virtual users without merely substituting a UID everywhere.
2. **True DE/direct boot — dedicated future phase.** Virtual DE data, package registry, APK/native code, and boot state currently depend on host CE. The [architecture audit](POKEMON_INVESTIGATION.md#device-protected-bootstrap-architecture-2026-09-27) outlines a host DE root, minimal boot metadata/code, state-based broadcasts, and recoverable migration. None is in production. The test device had no secure lock credential; every reboot probe reported `isUserUnlocked()==true`, so real locked-user validation is pending on a suitable device.
3. **PAD / Play services — secondary.** Pocket resolves and binds the virtual `com.android.vending` asset service. Companion's default disabled `vending_asset_delivery` gate returns `-5`; enabling it allowed session lookup but pack requests still returned `-5` with an empty virtual account list. Account sign-in remains an A/B test, not an established crash fix. Current gameplay UI works despite the error. Reprioritize only if a required feature is shown to fail.
4. **Future broad compatibility.** Continue using the generic probe to distinguish expected container structure from Android API-semantic mismatches. Historical package/service and class-loader leads are documented but are not active defects without a fresh reproducer.

## 8. Experiments not to redo blindly

| Experiment | Result and present relevance |
| --- | --- |
| Disable BlackBox native hooks / bypass Java OS rewriting | Old SIGSEGV pattern persisted; neither broadly identifies the cause. Revisit only with a specific regression. |
| Change host/runtime target SDK | Clean SDK-35 host test still crashed; SDK-28 host restored. Do not repeat as a generic crash fix. |
| Native library extraction / class-loader reconstruction | Guest libraries and splits were present; independent trials still reached old crash. Keep as historical comparison, not active fix. |
| PAD feature gate and account observation | Service worked through Binder; enabled gate changed session result but not old native crash. PAD remains independent. |
| PAC/BTI and exact bad-target analysis | First SIGSEGV and intentional invalid-branch instruction were located; PAC was not the immediate cause. No need for duplicate tombstones. |
| Host/virtual package metadata comparison | APK/splits/version/signature/ABI/installer and checked assets matched; no missing installed split identified. |
| Unlocked direct-boot control | CE/DE Java/native access passed after unlock, but this does not prove locked-mode support. |
| Three-environment CE comparison | Normal Android and Multiple App expose logical CE paths; BlackBox now does too while retaining isolated backing. Generic captures pass. |

The detailed runs, caveats, and raw references remain in [POKEMON_INVESTIGATION.md](POKEMON_INVESTIGATION.md).

## 9. Important commits

| Commit | Verified subject / role |
| --- | --- |
| `24bf9bd` | `Add BlackBox compatibility fixes and investigation probes` — package UID, installer/source, generic probe foundation |
| `517766e` | `Route native guest filesystem paths through IOCore rules` |
| `f698547` | `Record direct-boot storage gate and guard boot receiver delivery` |
| `cc37df1` | `Document DE bootstrap architecture and extend locked-boot probe` |
| `9f23ce2` | `Clarify DE path routing boundary before migration` |
| `15c86e1` | `Separate logical CE guest paths from physical backing` — production known-good behavior |
| `089f1b0` | `Document known-good Pokémon launch baseline` — proof/documentation only |
| `5dc2160` | `docs: consolidate project state and clean repository` — master handoff/documentation only |

## 10. Repository artifacts and document map

| Location | Role / retention |
| --- | --- |
| [`README.md`](README.md) | Project introduction and build/use entry point; this summary is the current engineering handoff |
| [`Bcore/`](Bcore/) and [`app/`](app/) | Production engine and host app source |
| [`android-env-probe/`](android-env-probe/) | Generic probe/peer source, captured snapshots/logs, Python suite, and [probe instructions](android-env-probe/README.md) |
| [`pokemon-env-probe/`](pokemon-env-probe/) | Tracked historical crash/boot diagnostic source, logs, disassembly and screenshots |
| [`pokemon-env-probe/known-good-15c86e1/`](pokemon-env-probe/known-good-15c86e1/) | Tracked working-baseline proof pack |
| [`POKEMON_INVESTIGATION.md`](POKEMON_INVESTIGATION.md) | Detailed Pokémon chronology, evidence, failed trials, and DE audit; historical recommendations are marked as such |
| [`Docs.md`](Docs.md), [`RELEASE_NOTES.md`](RELEASE_NOTES.md) | Older user/API guide and dated release notes; not current engineering status |
| `app/build/`, `Bcore/build/`, probe `build/` | Reproducible, Git-ignored build output; not evidence of the installed APK by themselves |

Two forensic directories existed under `C:/Users/johnw/AppData/Local/Temp/` when this summary was written: `blackbox-pokemon-20260925-103903` and `blackbox-pokemon-native-20260925-235946`. They contain older raw traces and the exact analyzed native binaries. They are **outside Git** and their continued availability or backup is not guaranteed; their contents are referenced in the investigation document. Tracked repository artifacts are retained even where filenames look temporary, because no safe duplicate/evidence audit justified deletion.

Repository hygiene audit: no tracked APK or redundant generated build product was found. Tracked `app/libs/*.aar` files are used by UI source through `fileTree`; `Bcore/src/main/assets/{empty,junit}.jar` are consumed by `JarConfig`/`JarManager`; the Gradle wrapper JAR is needed for the wrapper; `assets/usage.gif` is the README illustration. These binaries are intentionally retained. The existing root/probe `.gitignore` rules already keep local Gradle/build output and `local.properties` out of status. No tracked evidence or historical tool was deleted, no files were moved, and no new broad ignore rule was needed.

## 11. Safe future workflow and priorities

1. Start from a clean `main`; read this summary, then the relevant detailed section in the investigation or probe README.
2. Preserve BlackBox, microG, and guest app data. Compare the captured-result suite with 31 pass / one expected failure before changing production code.
3. Make narrow, generally justified engine changes. Verify affected generic probes, virtual user/package isolation, and build output first; use Pokémon as an integration test when the generic change warrants it.
4. Update this summary only for a meaningful change to current architecture, status, or priority. Put experiment details and raw evidence near the probe or in the investigation archive.

Recommended order: design the nonzero-user UID model; harden generic regression coverage for any implementation; treat true DE/direct boot as a separate project requiring a credential-locked test device; investigate PAD when a demonstrated feature blocker warrants it. Prefer general compatibility fixes; evaluate any package-specific workaround explicitly on its technical merits, scope, evidence, regression risk, and applicable constraints rather than introducing one by default.
