"""Assertions for the unlocked reboot control; these do not prove locked-user access."""

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
CAPTURES = {
    "normal": "normal-directboot-events.txt",
    "blackbox-user0": "blackbox-directboot-user0-events.txt",
    "blackbox-user1": "blackbox-directboot-user1-events.txt",
}


def latest_events(name):
    lines = (ROOT / CAPTURES[name]).read_text(encoding="utf-8-sig").splitlines()
    events = [json.loads(line) for line in lines if line.startswith("{")]
    return {event["action"].rsplit(".", 1)[-1]: event for event in events}


class UnlockedBootControlTest(unittest.TestCase):
    def test_both_broadcasts_read_the_same_de_file_in_java_and_native(self):
        for name in CAPTURES:
            with self.subTest(environment=name):
                events = latest_events(name)
                self.assertIn("LOCKED_BOOT_COMPLETED", events)
                self.assertIn("BOOT_COMPLETED", events)
                for event in events.values():
                    self.assertTrue(event["userUnlocked"])
                    self.assertEqual("OK", event["deWrite"])
                    self.assertEqual(event["deJavaRead"], event["deNativeRead"])
                    self.assertEqual(event["deJavaRead"], event["deLogicalJavaRead"])
                    self.assertEqual(event["deJavaRead"], event["deLogicalNativeRead"])
                    self.assertEqual(event["ceJavaRead"], event["ceNativeRead"])

    def test_blackbox_virtual_users_have_distinct_de_backing(self):
        user0 = latest_events("blackbox-user0")["BOOT_COMPLETED"]
        user1 = latest_events("blackbox-user1")["BOOT_COMPLETED"]
        self.assertEqual(user0["deLogicalPath"], user1["deLogicalPath"])
        self.assertNotEqual(user0["dePath"], user1["dePath"])
        self.assertNotEqual(user0["deJavaRead"], user1["deJavaRead"])


if __name__ == "__main__":
    unittest.main()
