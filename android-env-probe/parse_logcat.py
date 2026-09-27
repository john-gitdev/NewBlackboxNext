"""Extract the last complete ENVPROBE JSON record from an adb logcat dump."""
import json
import re
import sys
from pathlib import Path


def extract(path: Path):
    runs = {}
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        match = re.search(r"ENVPROBE: BEGIN id=(\S+) chunks=(\d+)", line)
        if match:
            runs[match[1]] = {"expected": int(match[2]), "chunks": {}, "ended": False}
            continue
        match = re.search(r"ENVPROBE: CHUNK id=(\S+) index=(\d+) data=(.*)$", line)
        if match and match[1] in runs:
            runs[match[1]]["chunks"][int(match[2])] = match[3]
            continue
        match = re.search(r"ENVPROBE: END id=(\S+)", line)
        if match and match[1] in runs:
            runs[match[1]]["ended"] = True
    for run_id, run in reversed(list(runs.items())):
        if run["ended"] and len(run["chunks"]) == run["expected"]:
            return run_id, json.loads("".join(run["chunks"][i] for i in range(run["expected"])))
    raise ValueError(f"No complete run in {path}")


if __name__ == "__main__":
    source = Path(sys.argv[1])
    dest = Path(sys.argv[2])
    run_id, data = extract(source)
    dest.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{run_id}: {dest}")
