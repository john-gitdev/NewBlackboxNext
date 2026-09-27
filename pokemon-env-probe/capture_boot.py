import lldb
import time
from pathlib import Path

BASE_FILE = Path(r"C:\Users\johnw\repos\NewBlackbox\pokemon-env-probe\base.txt")

def reg(frame, name):
    r = frame.FindRegister(name)
    return int(r.GetValue(), 16) if r.IsValid() and r.GetValue() else 0

def managed_string(process, ptr):
    if ptr < 0x10000:
        return "<null>"
    error = lldb.SBError()
    raw = process.ReadMemory(ptr, 220, error)
    if not error.Success() or len(raw) < 20:
        return "<unreadable>"
    count = int.from_bytes(raw[16:20], "little")
    if count > 100:
        return "<invalid-length>"
    return raw[20:20 + 2 * count].decode("utf-16-le", errors="replace")

def run(debugger):
    debugger.SetAsync(False)
    process = debugger.GetSelectedTarget().GetProcess()
    base = int(BASE_FILE.read_text().strip(), 16)
    points = {
        base + 0x396f644: "BOOT_CALL",
        base + 0x396f648: "BOOT_RETURN",
        base + 0x4a45f34: "TPR_ENTRY",
    }
    ci = debugger.GetCommandInterpreter()
    for address, name in points.items():
        result = lldb.SBCommandReturnObject()
        ci.HandleCommand(f"breakpoint set -H -a {address:#x}", result)
        print("SET", name, result.GetOutput().strip(), result.GetError().strip(), flush=True)
    for name in ("SIGSEGV", "SIGBUS", "SIGCONT"):
        debugger.HandleCommand(f"process handle {name} -s false -n false -p true")
    start = time.monotonic()
    hits = []
    while time.monotonic() - start < 35:
        error = process.Continue()
        if error.Fail() or process.GetState() == lldb.eStateExited:
            print("DONE", error, process.GetState(), flush=True)
            break
        for thread in process:
            if thread.GetStopReason() != lldb.eStopReasonBreakpoint:
                continue
            frame = thread.GetFrameAtIndex(0)
            address = frame.GetPC()
            label = points.get(address)
            if not label:
                continue
            now = time.monotonic() - start
            if label == "BOOT_RETURN":
                detail = f"w0={reg(frame, 'w0') & 0xffffffff} x0={reg(frame, 'x0'):#x}"
            elif label == "TPR_ENTRY":
                detail = f"type={managed_string(process, reg(frame, 'x0'))!r} level={reg(frame, 'w1') & 0xffffffff}"
            else:
                detail = f"lr={reg(frame, 'x30'):#x} sp={reg(frame, 'sp'):#x}"
            print("HIT", label, f"elapsed={now:.6f}", f"unix={time.time():.6f}",
                  "thread=" + str(thread.GetName()), detail, flush=True)
            hits.append(label)
        if "BOOT_RETURN" in hits and "TPR_ENTRY" in hits:
            break
    if process.GetState() != lldb.eStateExited:
        process.Detach()
        print("DETACHED", flush=True)

