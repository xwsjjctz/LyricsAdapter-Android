#!/usr/bin/env python3
"""Fails unless every native library in an APK can load on a 16 KB page device.

    python3 tools/ci/check_page_size.py app/build/outputs/apk/release/app-release.apk

Each library must be stored uncompressed at a 16 KB offset in the APK and have
its ELF LOAD segments aligned to 16 KB or more. NDK r28 and later link that way
by default; an older NDK, or a prebuilt library from a dependency, may not.
"""
import struct
import sys
import zipfile

PAGE = 16 * 1024
PT_LOAD = 1


def load_alignments(elf: bytes) -> list[int]:
    if elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF file")
    (table,) = struct.unpack_from("<Q", elf, 0x20)
    entry_size, count = struct.unpack_from("<HH", elf, 0x36)
    entries = (table + index * entry_size for index in range(count))
    return [struct.unpack_from("<Q", elf, entry + 0x30)[0] for entry in entries
            if struct.unpack_from("<I", elf, entry)[0] == PT_LOAD]


def data_offset(apk, info: zipfile.ZipInfo) -> int:
    apk.seek(info.header_offset + 26)
    name_length, extra_length = struct.unpack("<HH", apk.read(4))
    return info.header_offset + 30 + name_length + extra_length


def problems(path: str) -> tuple[int, list[str]]:
    found = []
    with open(path, "rb") as raw, zipfile.ZipFile(path) as apk:
        libraries = [info for info in apk.infolist() if info.filename.startswith("lib/") and info.filename.endswith(".so")]
        for info in libraries:
            try:
                alignments = load_alignments(apk.read(info))
            except (ValueError, struct.error) as error:
                found.append(f"{info.filename}: {error}")
                continue
            if any(alignment < PAGE for alignment in alignments):
                found.append(f"{info.filename}: LOAD segments aligned to {min(alignments)} bytes")
            if info.compress_type != zipfile.ZIP_STORED:
                found.append(f"{info.filename}: compressed in the APK")
            elif data_offset(raw, info) % PAGE:
                found.append(f"{info.filename}: not at a 16 KB offset in the APK")
    return len(libraries), found


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        count, found = problems(sys.argv[1])
    except (OSError, zipfile.BadZipFile) as error:
        print(f"Cannot read {sys.argv[1]}: {error}", file=sys.stderr)
        return 2
    if count == 0:
        print(f"{sys.argv[1]} has no native libraries; the app ships four.", file=sys.stderr)
        return 1
    for problem in found:
        print(f"::error::{problem}")
    if not found:
        print(f"{count} native libraries, all ready for 16 KB pages.")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
