"""Unlocked controls for the DE markers used by future locked-user tests."""

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def capture(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


class DeBootstrapControlTest(unittest.TestCase):
    def test_peer_de_marker_exists_before_a_future_locked_boot(self):
        for name in ("normal-de-architecture-v1.json", "blackbox-de-architecture-v1.json"):
            with self.subTest(capture=name):
                result = capture(name)
                self.assertEqual(8, result["schema"])
                peer = result["peerProvider"]["query"]
                self.assertTrue(peer["deviceMarkerValue"].startswith("peer-de;"))
                self.assertIn("/dev.codex.envpeer/files/", peer["deviceMarkerPath"])

    def test_virtual_users_have_distinct_unlocked_de_storage(self):
        user0 = capture("blackbox-de-architecture-v1.json")
        user1 = capture("blackbox-de-architecture-user1-v1.json")
        self.assertEqual(8, user1["schema"])
        self.assertNotEqual(user0["directories"]["deviceProtectedContextDataDir"],
                            user1["directories"]["deviceProtectedContextDataDir"])
        self.assertNotEqual(user0["directories"]["deviceProtectedMarker"]["readBack"],
                            user1["directories"]["deviceProtectedMarker"]["readBack"])
        for result in (user0, user1):
            self.assertEqual(result["directories"]["deviceProtectedMarker"]["readBack"],
                             result["nativeFilesystem"]["logicalDeviceProtectedPath"]["openRead"])


if __name__ == "__main__":
    unittest.main()
