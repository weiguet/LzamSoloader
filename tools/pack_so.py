#!/usr/bin/env python3
"""
SO 压缩打包脚本
用法:
  python3 pack_so.py \
    --so-dir   app/src/main/jniLibs/arm64-v8a \
    --manifest so_manifest.json \
    --out      app/src/main/assets/so \
    --algo     lzma          # lzma | gzip（默认lzma）

so_manifest.json 格式（由 so_analyzer.py 生成，或手动维护）：
{
  "levels": {
    "0": [{"name": "libcore.so", "deps": []}],
    "1": [{"name": "libui.so",   "deps": ["libcore.so"]}],
    ...
  }
}
"""

import lzma
import gzip
import json
import shutil
import hashlib
import argparse
from pathlib import Path


LZMA_PRESETS = {
    1: {"preset": 3,                           "dict_size": 4  * 1024 * 1024},
    2: {"preset": 6,                           "dict_size": 16 * 1024 * 1024},
    3: {"preset": 9 | lzma.PRESET_EXTREME,    "dict_size": 8  * 1024 * 1024},
}

GZIP_LEVELS = {1: 3, 2: 6, 3: 9}


def md5(path: Path) -> str:
    h = hashlib.md5()
    h.update(path.read_bytes())
    return h.hexdigest()


def compress_lzma(raw: bytes, level: int) -> bytes:
    cfg = LZMA_PRESETS[level]
    filters = [{"id": lzma.FILTER_LZMA2,
                "preset": cfg["preset"],
                "dict_size": cfg["dict_size"]}]
    return lzma.compress(raw, format=lzma.FORMAT_XZ, filters=filters)


def compress_gzip(raw: bytes, level: int) -> bytes:
    return gzip.compress(raw, compresslevel=GZIP_LEVELS[level])


def pack(so_dir: str, manifest_path: str, out_dir: str, algo: str):
    manifest = json.loads(Path(manifest_path).read_text())
    out_root = Path(out_dir)

    ext = ".lzma" if algo == "lzma" else ".gz"
    compress_fn = compress_lzma if algo == "lzma" else compress_gzip

    result_manifest = {"version": 1, "abi": "arm64-v8a", "entries": {}}
    total_orig = 0
    total_comp = 0

    for level_str, so_list in manifest["levels"].items():
        level = int(level_str)
        level_dir = out_root / f"l{level}"
        level_dir.mkdir(parents=True, exist_ok=True)

        for entry in so_list:
            src = Path(so_dir) / entry["name"]
            if not src.exists():
                print(f"  [SKIP] {src} not found")
                continue

            raw = src.read_bytes()
            orig_size = len(raw)
            orig_md5_ = hashlib.md5(raw).hexdigest()
            total_orig += orig_size

            if level == 0:
                dst = level_dir / entry["name"]
                dst.write_bytes(raw)
                asset_path = f"so/l0/{entry['name']}"
                compressed_size = orig_size
                compressed = False
            else:
                dst_name = entry["name"] + ext
                compressed_data = compress_fn(raw, level)
                dst = level_dir / dst_name
                dst.write_bytes(compressed_data)
                asset_path = f"so/l{level}/{dst_name}"
                compressed_size = len(compressed_data)
                compressed = True

            total_comp += compressed_size
            ratio = compressed_size / orig_size * 100
            saving = (orig_size - compressed_size) // 1024
            print(f"  [L{level}][{algo}] {entry['name']:30s} "
                  f"{orig_size//1024:5d}KB -> {compressed_size//1024:5d}KB "
                  f"({ratio:5.1f}%, 节省{saving}KB)")

            result_manifest["entries"][entry["name"]] = {
                "level":           level,
                "asset_path":      asset_path,
                "compressed":      compressed,
                "orig_size":       orig_size,
                "compressed_size": compressed_size,
                "orig_md5":        orig_md5_,
                "deps":            entry.get("deps", []),
            }

    out_root.joinpath("manifest.json").write_text(
        json.dumps(result_manifest, indent=2, ensure_ascii=False)
    )

    total_saving = (total_orig - total_comp) // 1024
    print(f"\n总计: {total_orig//1024}KB -> {total_comp//1024}KB, 节省 {total_saving}KB "
          f"({(1 - total_comp/total_orig)*100:.1f}%)")
    print(f"manifest -> {out_root}/manifest.json")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--so-dir",   required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out",      required=True)
    ap.add_argument("--algo",     default="lzma", choices=["lzma", "gzip"])
    args = ap.parse_args()
    pack(args.so_dir, args.manifest, args.out, args.algo)
