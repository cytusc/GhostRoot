#!/usr/bin/env python3
"""Dump method tables for classes matching a keyword, straight from dex bytes.

jadx needs minutes to index a 90 MB multi-dex APK before printing a single line.
Reading class_def_item + class_data_item directly answers "what methods does
WelComeActivity have" in under a second, which is all a hook module needs.

Usage: dexmethods.py <apk> <keyword>
"""
import struct
import sys
import zipfile


def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def dump(apk, keyword):
    kw = keyword.lower()
    with zipfile.ZipFile(apk) as z:
        dexs = sorted(n for n in z.namelist()
                      if n.startswith("classes") and n.endswith(".dex"))
        for name in dexs:
            d = z.read(name)
            if len(d) < 0x70 or d[:4] != b"dex\n":
                continue
            n_str, off_str = struct.unpack_from("<II", d, 0x38)
            n_type, off_type = struct.unpack_from("<II", d, 0x40)
            _n_proto, off_proto = struct.unpack_from("<II", d, 0x48)
            _n_meth, off_meth = struct.unpack_from("<II", d, 0x58)
            n_cls, off_cls = struct.unpack_from("<II", d, 0x60)

            cache = {}

            def string_at(idx):
                if idx in cache:
                    return cache[idx]
                if idx >= n_str:
                    return ""
                p = struct.unpack_from("<I", d, off_str + idx * 4)[0]
                _, p = uleb(d, p)
                e = d.find(b"\x00", p)
                s = d[p:e if e >= 0 else len(d)].decode("utf-8", "replace")
                cache[idx] = s
                return s

            def type_at(idx):
                if idx >= n_type:
                    return ""
                return string_at(struct.unpack_from("<I", d, off_type + idx * 4)[0])

            for i in range(n_cls):
                (cls_idx, _acc, _sup, _ioff, _sf, _anno,
                 cdata, _sv) = struct.unpack_from("<8I", d, off_cls + i * 32)
                cname = type_at(cls_idx)
                if kw not in cname.lower():
                    continue
                print(f"\n=== {cname}   [{name}]")
                if cdata == 0:
                    print("  (no class_data)")
                    continue
                p = cdata
                n_sf, p = uleb(d, p)
                n_if, p = uleb(d, p)
                n_dm, p = uleb(d, p)
                n_vm, p = uleb(d, p)
                for _ in range(n_sf + n_if):
                    _, p = uleb(d, p)
                    _, p = uleb(d, p)
                for label, cnt in (("direct", n_dm), ("virtual", n_vm)):
                    midx = 0
                    for _ in range(cnt):
                        diff, p = uleb(d, p)
                        midx += diff
                        flags, p = uleb(d, p)
                        _code, p = uleb(d, p)
                        m_name = string_at(
                            struct.unpack_from("<I", d, off_meth + midx * 8 + 4)[0])
                        proto_idx = struct.unpack_from(
                            "<H", d, off_meth + midx * 8 + 2)[0]
                        # proto: shorty_idx, return_type_idx, parameters_off
                        ret = type_at(struct.unpack_from(
                            "<I", d, off_proto + proto_idx * 12 + 4)[0])
                        params_off = struct.unpack_from(
                            "<I", d, off_proto + proto_idx * 12 + 8)[0]
                        argc = 0
                        if params_off:
                            argc = struct.unpack_from("<I", d, params_off)[0]
                        vis = "public" if flags & 1 else "package"
                        if flags & 4:
                            vis = "protected"
                        if flags & 2:
                            vis = "private"
                        print(f"  [{label:7s}] {vis:9s} {m_name}"
                              f"({argc} args) -> {ret}")


if __name__ == "__main__":
    dump(sys.argv[1], sys.argv[2])
