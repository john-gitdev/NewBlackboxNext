"""Regression checks over captures from the same generic probe APK.

Run with: python -m unittest discover -s android-env-probe -p 'test_*.py'
Override any capture with ENVPROBE_NORMAL_CAPTURE, ENVPROBE_MULTIPLE_APP_CAPTURE,
or ENVPROBE_BLACKBOX_CAPTURE. Historical captures remain untouched.
"""

import json
import os
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
DEFAULTS = {
    "normal": "normal-framework-ce-v11.json",
    "multiple-app": "multiple-app-framework-ce-v11.json",
    "blackbox": "blackbox-cold-framework-ce-v11.json",
}


def capture(environment):
    override = os.environ.get(f"ENVPROBE_{environment.upper().replace('-', '_')}_CAPTURE")
    path = Path(override) if override else ROOT / DEFAULTS[environment]
    return json.loads(path.read_text(encoding="utf-8"))


class PackageUidConsistencyTest(unittest.TestCase):
    def test_normal_android(self):
        self.assert_consistent(capture("normal"))

    def test_working_container(self):
        self.assert_consistent(capture("multiple-app"))

    def test_blackbox(self):
        self.assert_consistent(capture("blackbox"))

    def assert_consistent(self, result):
        identity = result["identity"]
        app_info = result["applicationInfo"]["pmApplicationInfo"]
        self.assertEqual(identity["packageName"], app_info["packageName"])
        self.assertEqual(identity["pmPackageUid"], app_info["uid"])
        self.assertEqual(identity["pmPackageUid"], identity["androidUid"])

    def test_system_and_missing_package_behavior(self):
        normal = capture("normal")["identity"]
        blackbox = capture("blackbox")["identity"]
        self.assertEqual(normal["systemPackageUid"], blackbox["systemPackageUid"])
        self.assertEqual(normal["missingPackageUid"], blackbox["missingPackageUid"])

    @unittest.expectedFailure
    def test_blackbox_nonzero_virtual_user(self):
        # Process.myUid/Os.getuid currently expose only the appId. A direct
        # full-UID substitution broke host-framework calls for physical user 0.
        result = json.loads((ROOT / "blackbox-user1-framework-ce-v11.json").read_text(encoding="utf-8"))
        identity = result["identity"]
        pm_uid = result["applicationInfo"]["pmApplicationInfo"]["uid"]
        self.assertEqual(110023, pm_uid)
        self.assertEqual(pm_uid, identity["pmPackageUid"])
        self.assertEqual(pm_uid, identity["androidUid"])
        self.assertEqual(pm_uid, identity["osGetuid"])


class InstallSourceConsistencyTest(unittest.TestCase):
    def test_normal_android(self):
        self.assert_consistent(capture("normal"))

    def test_blackbox(self):
        self.assert_consistent(capture("blackbox"))

    def assert_consistent(self, result):
        package_manager = result["packageManager"]
        self.assertEqual(
            package_manager["installerPackageName"],
            package_manager["installSourceInfo"]["installing"],
        )


class TwoPackageInteractionTest(unittest.TestCase):
    def test_normal_android(self):
        self.assert_interactions(capture("normal"))

    def test_working_container(self):
        # The refreshed v9 clone no longer exposes the old peer clone. The
        # preceding same-APK v8 run established this interaction separately.
        result = json.loads((ROOT / "multiple-app-v8.json").read_text(encoding="utf-8"))
        self.assert_interactions(result)

    def test_blackbox(self):
        self.assert_interactions(capture("blackbox"))

    def assert_interactions(self, result):
        pm = result["packageManager"]
        self.assertEqual(pm["peerUid"], pm["peer"]["applicationInfo"]["uid"])
        self.assertTrue(result["peerBinding"]["bindResult"])
        self.assertEqual("dev.codex.envpeer.PeerService", result["peerBinding"]["descriptor"])
        self.assertEqual("peer-ok", result["peerProvider"]["query"]["value"])
        self.assertEqual(result["identity"]["androidUid"],
                         result["peerBinding"]["callerIdentity"]["serviceCallingUid"])
        self.assertEqual(result["identity"]["androidUid"],
                         result["peerProvider"]["query"]["callingUid"])
        self.assertEqual(pm["peerUid"],
                         result["peerBinding"]["callerIdentity"]["serviceProcessUid"])
        self.assertEqual(pm["peerUid"],
                         result["peerProvider"]["query"]["processUid"])
        self.assertNotEqual(result["identity"]["pid"],
                            result["peerBinding"]["callerIdentity"]["serviceProcessPid"])


class StorageAndRuntimeTest(unittest.TestCase):
    def test_isolated_markers_across_three_environments(self):
        markers = [capture(name)["directories"]["isolationMarker"]["written"]
                   for name in ("normal", "multiple-app", "blackbox")]
        self.assertEqual(3, len(set(markers)))

    def test_ce_de_and_native_backing_paths(self):
        for name in ("normal", "multiple-app", "blackbox"):
            with self.subTest(environment=name):
                result = capture(name)
                dirs = result["directories"]
                self.assertTrue(dirs["userUnlocked"])
                self.assertEqual(dirs["isolationMarker"]["written"],
                                 dirs["isolationMarker"]["readBack"])
                self.assertEqual(dirs["deviceProtectedMarker"]["readBack"],
                                 result["nativeFilesystem"]["deviceProtectedPath"]["openRead"])
                self.assertEqual(dirs["isolationMarker"]["written"],
                                 result["nativeFilesystem"]["backingPath"]["openRead"])
                self.assertNotEqual(dirs["dataDir"], dirs["deviceProtectedContextDataDir"])

    def test_loaded_native_probe_and_runtime_identity(self):
        for name in ("normal", "multiple-app", "blackbox"):
            with self.subTest(environment=name):
                result = capture(name)
                self.assertEqual("libenvprobe.so", result["nativeFilesystem"]["libraryName"])
                self.assertIn("PathClassLoader", result["runtime"]["classLoader"])
                self.assertIn("arm64-v8a", result["runtime"]["supportedAbis"])
                self.assertEqual("dev.codex.envprobe", result["identity"]["appProcessName"])

    def test_permission_and_appops_snapshot(self):
        for name in ("normal", "multiple-app", "blackbox"):
            with self.subTest(environment=name):
                values = capture(name)["permissionsAndAppOps"]
                self.assertEqual(-1, values["readContactsPermission"])
                self.assertEqual(-1, values["cameraPermission"])
                self.assertIn(values["readContactsAppOp"], (0, 1))


class NativeFilesystemTest(unittest.TestCase):
    def test_normal_android_logical_path(self):
        self.assert_logical_path_works(capture("normal"))

    def test_working_container_logical_path(self):
        self.assert_logical_path_works(capture("multiple-app"))

    def test_blackbox_logical_path(self):
        self.assert_logical_path_works(capture("blackbox"))

    def assert_logical_path_works(self, result):
        expected = result["directories"]["isolationMarker"]["written"]
        native = result["nativeFilesystem"]["logicalPath"]
        self.assertEqual(expected, native["openRead"])
        self.assertEqual(expected, native["openatRead"])
        self.assertEqual(expected, native["open64Read"])
        self.assertEqual(expected, native["openat64Read"])
        self.assertFalse(native["stat"].startswith("ERR:"))
        self.assertFalse(native["lstat"].startswith("ERR:"))
        self.assertEqual(native["stat"], native["fstatat"])
        self.assertEqual("OK", native["access"])
        self.assertEqual("OK", native["faccessat"])
        self.assertFalse(native["realpath"].startswith("ERR:"))
        self.assertIn("/dev.codex.envprobe/files/", native["realpath"])
        self.assertTrue(native["readlink"].startswith("ERR:"))  # regular file
        self.assertIn("/dev.codex.envprobe/files/", native["procSelfFdReadlink"])
        self.assertEqual(native["procSelfFdReadlink"], native["procSelfFdReadlinkat"])

    def test_native_writes_visible_to_java_in_ce_and_de(self):
        for name in ("normal", "multiple-app", "blackbox"):
            with self.subTest(environment=name):
                result = capture(name)
                native = result["nativeFilesystem"]
                self.assertEqual(result["directories"]["deviceProtectedMarker"]["readBack"],
                                 native["logicalDeviceProtectedPath"]["openRead"])
                for storage in ("nativeWriteCe", "nativeWriteDe"):
                    self.assertEqual("OK", native[storage]["status"])
                    self.assertEqual(native[storage]["written"], native[storage]["javaRead"])
                    self.assertEqual(native[storage]["written"], native[storage]["nativeRead"])

    def test_other_package_and_prefix_neighbor_remain_inaccessible(self):
        for name in ("normal", "multiple-app", "blackbox"):
            with self.subTest(environment=name):
                result = capture(name)
                if name != "multiple-app":
                    self.assertTrue(result["peerProvider"]["query"]["markerValue"].startswith("peer;"))
                native = result["nativeFilesystem"]
                self.assertTrue(native["peerLogicalPath"]["openRead"].startswith("ERR:"))
                self.assertTrue(native["prefixNeighborPath"]["openRead"].startswith("ERR:"))
                self.assertTrue(native["parentTraversalPath"]["openRead"].startswith("ERR:"))

    def test_native_mutations_and_symlink(self):
        for name in ("normal", "blackbox"):
            with self.subTest(environment=name):
                result = capture(name)
                mutations = result["nativeFilesystem"]["mutations"]
                self.assertEqual("OK", mutations["status"])
                self.assertEqual(result["directories"]["isolationMarker"]["written"],
                                 mutations["symlinkRead"])
                self.assertIn("/dev.codex.envprobe/files/envprobe-isolation-marker.txt",
                              mutations["symlinkTarget"])
        # Multiple App's refreshed clone can create/read the symlink but its
        # dirfd-relative renameat returns ENOENT. It is a reference, not an oracle.
        reference = capture("multiple-app")
        self.assertEqual("ERR:renameat:2", reference["nativeFilesystem"]["mutations"]["status"])
        self.assertEqual(reference["directories"]["isolationMarker"]["written"],
                         reference["nativeFilesystem"]["mutations"]["symlinkRead"])

    def test_blackbox_virtual_users_have_distinct_backing_files(self):
        user0 = capture("blackbox")
        user1 = json.loads((ROOT / "blackbox-user1-framework-ce-v11.json").read_text(encoding="utf-8"))
        self.assertEqual(user0["nativeFilesystem"]["logicalPath"]["path"],
                         user1["nativeFilesystem"]["logicalPath"]["path"])
        self.assertEqual(user0["directories"]["dataDir"], user1["directories"]["dataDir"])
        self.assertNotEqual(user0["directories"]["isolationMarker"]["written"],
                            user1["directories"]["isolationMarker"]["written"])
        self.assertNotEqual(user0["nativeFilesystem"]["logicalPath"]["stat"],
                            user1["nativeFilesystem"]["logicalPath"]["stat"])
        self.assertNotEqual(user0["directories"]["deviceProtectedMarker"]["readBack"],
                            user1["directories"]["deviceProtectedMarker"]["readBack"])
        for result in (user0, user1):
            self.assert_logical_path_works(result)
            self.assertEqual(result["directories"]["isolationMarker"]["written"],
                             result["nativeFilesystem"]["logicalPath"]["openRead"])
            self.assertEqual("OK", result["nativeFilesystem"]["nativeWriteCe"]["status"])
            self.assertEqual("OK", result["nativeFilesystem"]["nativeWriteDe"]["status"])
            self.assertEqual("OK", result["nativeFilesystem"]["mutations"]["status"])


if __name__ == "__main__":
    unittest.main()
