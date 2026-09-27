"""Captured checks for guest-facing CE paths after physical/logical separation."""

import json
import os
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def capture(name):
    override = os.environ.get(f"ENVPROBE_{name.upper().replace('-', '_')}_CAPTURE")
    file = Path(override) if override else ROOT / f"{name}-framework-ce-v11.json"
    return json.loads(file.read_text(encoding="utf-8"))


class FrameworkCePathsTest(unittest.TestCase):
    def check_guest_paths(self, result, blackbox=False):
        package = result["identity"]["packageName"]
        root = f"/data/user/0/{package}"
        context = result["applicationInfo"]["contextApplicationInfo"]
        pm = result["applicationInfo"]["pmApplicationInfo"]
        dirs = result["directories"]
        for info in (context, pm):
            self.assertEqual(root, info["dataDir"])
            self.assertEqual(root, info["credentialProtectedDataDir"])
        for key, suffix in (("dataDir", ""), ("filesDir", "/files"),
                            ("cacheDir", "/cache"), ("codeCacheDir", "/code_cache"),
                            ("noBackupFilesDir", "/no_backup")):
            self.assertEqual(root + suffix, dirs[key])
        self.assertEqual(root + "/files/envprobe-isolation-marker.txt",
                         dirs["isolationMarker"]["path"])
        self.assertTrue(dirs["dataDirExists"])
        self.assertTrue(dirs["dataDirCanRead"])
        self.assertTrue(dirs["dataDirCanWrite"])
        content = dirs["isolationMarker"]["written"]
        self.assertEqual(content, dirs["isolationMarker"]["readBack"])
        self.assertEqual(content, result["nativeFilesystem"]["backingPath"]["openRead"])
        self.assertEqual(content, result["nativeFilesystem"]["logicalPath"]["openRead"])
        self.assertEqual(result["nativeFilesystem"]["backingPath"]["stat"],
                         result["nativeFilesystem"]["logicalPath"]["stat"])
        self.assertIn(f"/{package}/files", dirs["filesDirCanonical"])
        self.assertIn(f"/{package}/files/",
                      dirs["isolationMarker"]["kernelFdPath"])
        if blackbox:
            self.assertIn("/blackbox/data/user_de/", dirs["deviceProtectedContextDataDir"])
            self.assertIn("/blackbox/data/user_de/", pm["deviceProtectedDataDir"])
        else:
            self.assertEqual(f"/data/user_de/0/{package}",
                             dirs["deviceProtectedContextDataDir"])

    def test_normal_android(self):
        self.check_guest_paths(capture("normal"))

    def test_multiple_app(self):
        self.check_guest_paths(capture("multiple-app"))

    def test_blackbox_user_zero(self):
        self.check_guest_paths(capture("blackbox"), blackbox=True)

    def test_blackbox_user_one(self):
        self.check_guest_paths(capture("blackbox-user1"), blackbox=True)

    def test_virtual_users_share_namespace_but_not_files(self):
        user0 = capture("blackbox")
        user1 = capture("blackbox-user1")
        self.assertEqual(user0["directories"]["filesDir"],
                         user1["directories"]["filesDir"])
        self.assertNotEqual(user0["nativeFilesystem"]["logicalPath"]["stat"],
                            user1["nativeFilesystem"]["logicalPath"]["stat"])
        self.assertNotEqual(user0["directories"]["isolationMarker"]["written"],
                            user1["directories"]["isolationMarker"]["written"])

    def test_logical_reads_match_host_backing_inodes_and_content(self):
        lines = (ROOT / "blackbox-framework-ce-v11-backing.txt").read_text().splitlines()
        for user, name in ((0, "blackbox-restart"), (1, "blackbox-user1")):
            with self.subTest(virtual_user=user):
                result = capture(name)
                backing = next(line for line in lines if
                               f"blackbox/data/user/{user}/dev.codex.envprobe/" in line)
                inode = backing.split(" ", 1)[0]
                self.assertIn(f"ino={inode} ",
                              result["nativeFilesystem"]["logicalPath"]["stat"])
                marker = lines[lines.index(f"user{user}:") + 1]
                self.assertEqual(marker, result["directories"]["isolationMarker"]["written"])

    def test_restart_keeps_namespace_and_prior_content(self):
        before = capture("blackbox")
        after = capture("blackbox-restart")
        self.assertEqual(before["directories"]["filesDir"], after["directories"]["filesDir"])
        self.assertEqual(before["directories"]["isolationMarker"]["written"],
                         after["directories"]["isolationMarker"]["previous"])


if __name__ == "__main__":
    unittest.main()
