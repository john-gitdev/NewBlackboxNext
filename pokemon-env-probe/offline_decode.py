"""Read-only decoder for obfuscated constants copied by IsVspaceApp()."""
import os
import re
import struct
from pathlib import Path

ROOT = Path(os.environ["TEMP"]) / "blackbox-pokemon-native-20260925-235946"
ELF = ROOT / "libil2cpp.so"
ISV = ROOT / "entry-probe" / "isvspace-full.txt"
HELPERS = Path(__file__).with_name("string-helpers-disassembly.txt")

blob = ELF.read_bytes()
phoff = struct.unpack_from("<Q", blob, 32)[0]
phsize, phcount = struct.unpack_from("<HH", blob, 54)
segments = []
for i in range(phcount):
    typ, flags, file_off, va, _, file_size, _, _ = struct.unpack_from(
        "<IIQQQQQQ", blob, phoff + i * phsize
    )
    if typ == 1:
        segments.append((va, file_off, file_size))

def read_va(va, size):
    for base, file_off, file_size in segments:
        if base <= va and va + size <= base + file_size:
            return blob[file_off + va - base:file_off + va - base + size]
    raise ValueError(f"Unmapped file VA {va:#x}")

shoff = struct.unpack_from("<Q", blob, 40)[0]
shsize, shcount, shstrndx = struct.unpack_from("<HHH", blob, 58)
sections = [struct.unpack_from("<IIQQQQIIQQ", blob, shoff + i * shsize)
            for i in range(shcount)]
relocations = {}
for sec in sections:
    name, typ, flags, va, off, size, link, info, align, entsize = sec
    if typ != 4 or entsize != 24:
        continue
    for pos in range(off, off + size, entsize):
        target, rinfo, addend = struct.unpack_from("<QQq", blob, pos)
        if rinfo & 0xffffffff == 0x403:
            relocations[target] = addend

line_re = re.compile(r"^\s*([0-9a-f]+):\s+(?:[0-9a-f]{8}\s+)?([a-z.]+)\s+([^<]*?)(?:\s+<.*)?$")
def parse(path):
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = line_re.match(line)
        if m:
            out[int(m[1], 16)] = (m[2], m[3].split("//")[0].strip())
    return out

ins = parse(ISV)
helper = parse(HELPERS)

def imm(operand):
    m = re.search(r"#0x([0-9a-f]+)", operand)
    return int(m[1], 16) if m else None

def target(operand):
    values = re.findall(r"0x([0-9a-f]+)", operand)
    return int(values[-1], 16) if values else None

results = []
for pc in sorted(ins):
    op, args = ins[pc]
    if op != "tbz" or "w8, #0x0" not in args or pc >= 0x3081100:
        continue
    fallback = target(args)
    ctor = None
    for q in range(fallback, fallback + 0x30, 4):
        if q in ins and ins[q][0] == "bl":
            candidate = target(ins[q][1])
            if candidate != 0x330c03c:
                ctor = candidate
                break
    if ctor is None:
        continue
    source = None
    for q in range(pc - 0x38, pc, 4):
        if q not in ins or ins[q][0] != "adrp":
            continue
        a = ins[q][1]
        m = re.match(r"(x\d+), 0x([0-9a-f]+)", a)
        if not m or not (0x1c00000 <= int(m[2], 16) < 0x2000000):
            continue
        reg, page = m[1], int(m[2], 16)
        for r in range(q + 4, min(q + 0x18, pc), 4):
            if r in ins and ins[r][0] == "add" and ins[r][1].startswith(f"{reg}, {reg},"):
                source = page + imm(ins[r][1])
                break
    immediate_data = None
    if source is None:
        for q in range(pc - 0x38, pc, 4):
            if q not in ins or ins[q][0] != "mov" or not ins[q][1].startswith("x8, #"):
                continue
            value = imm(ins[q][1])
            for r in range(q + 4, pc, 4):
                if r in ins and ins[r][0] == "movk" and ins[r][1].startswith("x8, #"):
                    shift_match = re.search(r"lsl #([0-9]+)", ins[r][1])
                    if shift_match:
                        value |= imm(ins[r][1]) << int(shift_match[1])
                if r in ins and ins[r][0] == "str" and ins[r][1].startswith("x8, [x19, #0xf0]"):
                    immediate_data = value.to_bytes(8, "little")
                    break
    vt = None
    length = None
    for q in range(ctor, ctor + 0x80, 4):
        if q not in helper:
            continue
        hop, hargs = helper[q]
        if hop == "adrp" and hargs.startswith("x8, 0x"):
            page = target(hargs)
            if q + 4 in helper and helper[q + 4][0] == "add" and helper[q + 4][1].startswith("x8, x8,"):
                vt = page + imm(helper[q + 4][1]) + 0x10
        if hop == "cmp" and hargs.startswith("x8, #"):
            length = imm(hargs)
    if vt is None or length is None:
        continue
    decoder = relocations.get(vt + 0x18)
    if decoder is None:
        continue
    n = None
    key = 0
    for q in range(decoder, decoder + 0x50, 4):
        if q not in helper:
            continue
        dop, dargs = helper[q]
        if dop == "mov" and dargs.startswith("x1, #"):
            n = imm(dargs)
        if dop == "mov" and dargs.startswith("x2, #"):
            key = imm(dargs)
        if dop == "movk" and dargs.startswith("x2, #"):
            shift_match = re.search(r"lsl #([0-9]+)", dargs)
            if shift_match:
                key |= imm(dargs) << int(shift_match[1])
    if n != length:
        continue
    if source is None and (immediate_data is None or n > len(immediate_data)):
        continue
    ciphertext = read_va(source, n) if source is not None else immediate_data[:n]
    decoded = bytes(b ^ ((key >> (8 * (i % 8))) & 255)
                    for i, b in enumerate(ciphertext))
    results.append((pc, source, ctor, decoder, decoded))

for pc, source, ctor, decoder, decoded in results:
    source_text = f"{source:#x}" if source is not None else "immediate"
    print(f"check={pc:#x} cipher={source_text} ctor={ctor:#x} decoder={decoder:#x} text={decoded!r}")
print(f"decoded={len(results)}", flush=True)
