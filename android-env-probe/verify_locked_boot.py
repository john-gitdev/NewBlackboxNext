"""Verify a real locked-to-unlocked boot capture; unlocked controls must fail.

Usage: python android-env-probe/verify_locked_boot.py NORMAL_EVENTS BB_USER0_EVENTS BB_USER1_EVENTS
Pull each event file after unlocking; the receiver wrote it to its DE directory.
"""

import argparse
import json
from pathlib import Path


def events(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8-sig").splitlines()
            if line.startswith("{")]


def check(name, records):
    locked = [event for event in records
              if event["action"].endswith("LOCKED_BOOT_COMPLETED")
              and event["userUnlocked"] is False]
    assert locked, f"{name}: no receiver event while the host user was locked"
    before = locked[-1]
    unlocked = [event for event in records
                if event["action"].endswith("BOOT_COMPLETED")
                and not event["action"].endswith("LOCKED_BOOT_COMPLETED")
                and event["userUnlocked"] is True and event["time"] > before["time"]]
    assert unlocked, f"{name}: no later unlocked BOOT_COMPLETED event"
    after = unlocked[0]
    assert before["deWrite"] == "OK", f"{name}: locked DE write failed"
    assert after["deWrite"] == "UNCHANGED", f"{name}: unlocked event rewrote DE marker"
    for phase in (before, after):
        marker = phase["deJavaRead"]
        assert marker.startswith("boot;"), f"{name}: missing DE marker"
        assert marker == phase["deNativeRead"] == phase["deLogicalJavaRead"] \
            == phase["deLogicalNativeRead"], f"{name}: Java/native DE mismatch"
        assert phase["peerDeLogicalJavaRead"].startswith("ERR:"), \
            f"{name}: peer DE path readable through Java"
        assert phase["peerDeLogicalNativeRead"].startswith("ERR:"), \
            f"{name}: peer DE path readable through native libc"
    assert before["deJavaRead"] == after["deJavaRead"], f"{name}: DE marker changed at unlock"
    for key in ("ceJavaRead", "ceNativeRead", "ceLogicalJavaRead", "ceLogicalNativeRead"):
        assert before[key].startswith("ERR:"), f"{name}: {key} accessible while locked"
        assert not after[key].startswith("ERR:"), f"{name}: {key} unavailable after unlock"
    return before, after


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("normal")
    parser.add_argument("blackbox_user0")
    parser.add_argument("blackbox_user1")
    args = parser.parse_args()
    results = {
        "normal": check("normal", events(args.normal)),
        "blackbox user 0": check("blackbox user 0", events(args.blackbox_user0)),
        "blackbox user 1": check("blackbox user 1", events(args.blackbox_user1)),
    }
    before0 = results["blackbox user 0"][0]
    before1 = results["blackbox user 1"][0]
    assert before0["deLogicalPath"] == before1["deLogicalPath"], \
        "virtual users observed different logical guest paths"
    assert before0["dePath"] != before1["dePath"], "virtual users share a DE backing path"
    assert before0["deJavaRead"] != before1["deJavaRead"], "virtual users share DE marker data"
    print("Locked-to-unlocked DE, CE, and virtual-user checks passed")


if __name__ == "__main__":
    main()
