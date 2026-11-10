#!/usr/bin/env python3
"""Extract the full class list from every classes*.dex inside an APK.

Reading class_def_item directly is orders of magnitude faster than decompiling:
a 93 MB dex yields ~300k classes in a few seconds, which is what you want before
you decide which handful of classes actually deserve a jadx pass.

Usage: dexindex.py <apk> <out.txt>
"""
import struct
import sys
import zipfile


def uleb128(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def dex_classes(data):
    if len(data) < 0x70 or data[:4] != b"dex\n":
        return []
    n_str, off_str = struct.unpack_from("<II", data, 0x38)
    n_type, off_type = struct.unpack_from("<II", data, 0x40)
    n_cls, off_cls = struct.unpack_from("<II", data, 0x60)

    # Cache string lookups: type_ids point at strings, and many repeats occur.
    cache = {}

    def string_at(idx):
        if idx in cache:
            return cache[idx]
        if idx < 0 or idx >= n_str:
            return ""
        p = struct.unpack_from("<I", data, off_str + idx * 4)[0]
        _, p = uleb128(data, p)
        end = data.find(b"\x00", p)
        if end < 0:
            end = len(data)
        s = data[p:end].decode("utf-8", "replace")
        cache[idx] = s
        return s

    out = []
    for i in range(n_cls):
        class_idx = struct.unpack_from("<I", data, off_cls + i * 32)[0]
        if class_idx >= n_type:
            continue
        str_idx = struct.unpack_from("<I", data, off_type + class_idx * 4)[0]
        name = string_at(str_idx)
        if name:
            out.append(name)
    return out


def main():
    apk, dest = sys.argv[1], sys.argv[2]
    total = 0
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist()
                       if n.startswith("classes") and n.endswith(".dex"))
        with open(dest, "w", encoding="utf-8") as fh:
            for n in names:
                data = z.read(n)
                cls = dex_classes(data)
                total += len(cls)
                fh.write("\n".join(cls))
                fh.write("\n")
                print(f"{n}: {len(cls)} classes", file=sys.stderr)
    print(f"total {total} classes -> {dest}", file=sys.stderr)


if __name__ == "__main__":
    main()
