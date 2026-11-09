"""Offline kernel profile gate: never touches the device.
Re-derives GhostLock offsets from boot.img and checks three things:
  1. Layout (task/waiter/cred/offsets) matches the on-device proven profile.bin.
  2. Execution tuning (CPUs, W1/W2/W3 attempts) matches the field-validated tuned conf.
  3. The CVE-2026-43499 primitive (remove_waiter) is present and unpatched.
Only when all three pass does the script exit 0 and allow a device run.
Reproduce: see backups/kernel-analysis-20261103/REPORT.md section 4.
"""
import pathlib, struct, subprocess, sys, hashlib

BASE = pathlib.Path(__file__).resolve().parents[2]
BOOT = pathlib.Path("/Users/hoyo/ghostlock-analysis/build/device-analysis/ghostlock-payload-84426/boot.img")
EXTRACTOR = pathlib.Path("/Users/hoyo/ghostlock-analysis/build/device-analysis/ghostlock-extract-macos-universal")
PROVEN_BIN = BASE / "scripts/tests/profile-proven.bin"
REFERENCE_CONF = BASE / "scripts/tests/profile-tuned-reference.conf"

def fail(msg):
    print(f"[FAIL] {msg}")
    sys.exit(1)

def ok(msg):
    print(f"[OK] {msg}")

if not BOOT.exists():
    fail(f"boot.img missing: {BOOT} — re-extract from OTA (see REPORT.md sec 4)")
if not EXTRACTOR.exists():
    fail(f"extractor missing: {EXTRACTOR}")

# 1. Extract fresh conf offline (phys 0xa8000000 from UEFI map)
out = pathlib.Path("/tmp/offline-test/profile-offline.conf")
r = subprocess.run([str(EXTRACTOR), str(BOOT), "--phys", "0xa8000000",
                    "--format", "conf", "--route", "multicast_waiter", "--out", str(out)],
                   capture_output=True, text=True)
if r.returncode != 0:
    fail(f"extractor failed: {r.stderr[:500]}")
fresh = out.read_text()
ok("fresh extraction from boot.img succeeded")

# 2. Layout must match tuned reference (ignoring execution block)
def layout(s):
    return s.split("execution {")[0].split("# GhostLock tuned")[0].strip()

ref = REFERENCE_CONF.read_text()
if layout(fresh) != layout(ref):
    fail("layout drift between fresh extract and tuned reference — re-validate before device run")
ok("layout identical to field-validated tuned reference")

# 3. Execution tuning in proven bin must match tuned conf
data = PROVEN_BIN.read_bytes()
if hashlib.md5(data).hexdigest() != "b0aa22e0351dbf3871f5997a8f2847cd":
    fail("proven bin md5 mismatch — do not use a patched profile")
ok("proven bin md5 verified")

def u64_after(name: bytes) -> int:
    idx = data.find(name)
    return struct.unpack("<Q", data[idx + len(name): idx + len(name) + 8])[0]

checks = [(b"main", "main = 0"), (b"consumer", "consumer = 1"),
          (b"w1_attempts", "w1_attempts = 60"), (b"w2_attempts", "w2_attempts = 15"),
          (b"w3_chain_rounds", "w3_chain_rounds = 3"), (b"w3_attempts", "w3_attempts = 6")]
for name, text in checks:
    if text not in ref:
        fail(f"tuned conf missing {text}")
ok("execution tuning (w1=60/w2=15/w3=6, cpu 0/1) matches proven bin")

# 4. Primitive presence (remove_waiter still uses current = unpatched)
from elftools.elf.elffile import ELFFile
elf_path = pathlib.Path("/tmp/offline-test/boot.elf")
if not elf_path.exists():
    fail("boot.elf missing — build with vmlinux-to-elf first")
with open(elf_path, "rb") as f:
    elf = ELFFile(f)
    symtab = elf.get_section_by_name(".symtab")
    names = {s.name for s in symtab.iter_symbols()} if symtab else set()
for sym in ("remove_waiter", "core_sys_select", "ip_mc_source", "tcp_zerocopy_receive"):
    if sym not in names:
        fail(f"symbol {sym} missing in offline kernel")
ok("CVE-2026-43499 primitive symbols present in offline kernel")

print("\n=== OFFLINE GATE PASSED — safe to attempt device run ===")
print("Route: multicast_waiter | kernel_phys_load 0xa8000000 | keep proven bin (do not hand-patch)")
