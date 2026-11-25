"""Collect nTv plugin JARs and their installation catalog."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
RELEASES = "https://github.com/buhanzhe/ntv-plugin/releases/"

PLUGINS = [
    ("file-browser", "文件浏览", "channel", ["file-browser/dist/file-browser.jar"]),
    ("tvbox", "TVBox", "channel", ["tvbox/dist/tvbox.jar"]),
    ("python", "Python 运行时", "python", ["python/dist/python-arm32.jar", "python/dist/python-arm64.jar"]),
    ("bl", "BL 哔哩哔哩", "channel", ["bl/dist/bl.jar"]),
    ("tvbus", "TVBus", "tvbus", ["tvbus/dist/tvbus.jar"]),
]
METADATA = {
    "channel": "channel-plugin.json", "python": "python-plugin.json", "tvbus": "tvbus-plugin.json",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(path, kind, tag):
    with ZipFile(path) as archive:
        info = json.loads(archive.read(METADATA[kind]))
        if archive.getinfo("classes.dex").file_size < 112:
            raise ValueError(f"Missing plugin DEX in {path}")
    size = path.stat().st_size
    sha = digest(path)
    recorded = path.with_suffix(".sha256")
    if recorded.is_file() and recorded.read_text().strip() != sha:
        raise ValueError(f"SHA-256 changed without rebuilding {path}")
    if not 0 < size <= 32 * 1024 * 1024:
        raise ValueError(f"Plugin size is invalid: {path}")
    item = {
        "file": path.name, "url": f"{RELEASES}download/{tag}/{path.name}",
        "sha256": sha, "bytes": size, "version": str(info["version"]),
        "minSdk": info.get("minSdk", 14), "abis": info.get("abis", sorted(info.get("engines", {}))),
    }
    for key in ("pythonVersion", "minWebView"):
        if key in info:
            item[key] = info[key]
    return item


def write_json(path, data):
    path.write_bytes((json.dumps(data, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def prepare(tag, output):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Use a release tag like v1.0.0")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Release output must be empty; use a new directory to avoid publishing stale files")
    catalog, assets = [], {}
    for plugin, name, kind, paths in PLUGINS:
        versions = []
        for relative in paths:
            path = ROOT / "plugins" / relative
            if kind == "tvbus" and not path.is_file():
                continue
            item = artifact(path, kind, tag)
            if path.name in assets:
                raise ValueError(f"Repeated release filename: {path.name}")
            assets[path.name] = path
            versions.append(item)
        catalog.append({"id": plugin, "name": name, "kind": kind, "artifacts": versions})
    for filename, source in assets.items():
        shutil.copyfile(source, output / filename)
    write_json(output / "plugins.json", {"schema": 1, "release": tag, "plugins": catalog})
    print(f"Prepared {tag} with {len(assets)} plugin files and {len(catalog)} catalog entries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.tag, args.output)
