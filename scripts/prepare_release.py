"""Collect versioned nTv plugin assets and generate the release catalogs."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path
from urllib.parse import urlencode
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
RELEASES = "https://github.com/buhanzhe/ntv-plugin/releases/"

PLUGINS = [
    ("file-browser", "文件浏览", "channel", ["file-browser/dist/file-browser.jar"]),
    ("tvbox", "TVBox", "channel", ["tvbox/dist/tvbox.jar"]),
    ("python", "Python 运行时", "python", ["python/dist/python-arm32.jar", "python/dist/python-arm64.jar"]),
    ("bl", "BL 哔哩哔哩", "channel", ["bl/dist/bl.jar"]),
    ("tvbus", "TVBus", "tvbus", ["tvbus/dist/tvbus.jar"]),
    ("ku9-hnyx", "Ku9 湖南 IPv6", "ku9-js", ["ku9/hnyx.js"]),
]
METADATA = {
    "channel": "channel-plugin.json", "python": "python-plugin.json", "tvbus": "tvbus-plugin.json",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(path, kind, tag, *, url=None):
    if kind == "ku9-js":
        info = {"version": digest(path)[:12], "minSdk": 14}
    else:
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
        "file": path.name, "url": url or f"{RELEASES}download/{tag}/{path.name}",
        "sha256": sha, "bytes": size, "version": str(info["version"]),
        "minSdk": info.get("minSdk", 14), "abis": info.get("abis", sorted(info.get("engines", {}))),
    }
    for key in ("pythonVersion", "minWebView"):
        if key in info:
            item[key] = info[key]
    return item


def write_json(path, data):
    path.write_bytes((json.dumps(data, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def write_playlist(path, entries):
    lines = ["#EXTM3U"]
    for entry in entries:
        if entry["kind"] != "channel":
            continue
        for item in entry["artifacts"]:
            name = entry["name"]
            minimum = item.get("minSdk", 14)
            if minimum == 24:
                name += "（Android 7.0+）"
            elif minimum > 14:
                name += f"（API {minimum}+）"
            if item.get("abis"):
                name += " · " + "/".join(item["abis"])
            query = {"sha256": item["sha256"]}
            if entry["id"] == "tvbox":
                query["url"] = "http://www.xn--sss604efuw.cc/tv"
            lines.extend([f'#EXTINF:-1 group-title="插件",{name}',
                          item["url"] + "?" + urlencode(query)])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))


def prepare_repository_playlist(ref, output):
    if not re.fullmatch(r"[0-9a-f]{40}", ref):
        raise ValueError("Use a full Git commit SHA to pin repository artifacts")
    entries = []
    for plugin, name, kind, paths in PLUGINS:
        if kind != "channel":
            continue
        items = []
        for relative in paths:
            path = ROOT / "plugins" / relative
            url = f"https://raw.githubusercontent.com/buhanzhe/ntv-plugin/{ref}/plugins/{relative}"
            items.append(artifact(path, kind, ref, url=url))
        entries.append({"id": plugin, "name": name, "kind": kind, "artifacts": items})
    write_playlist(output, entries)
    print(f"Prepared {output} with {sum(len(e['artifacts']) for e in entries)} channel plugins")


def prepare(tag, output):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Use a release tag like v1.0.0")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Release output must be empty; use a new directory to avoid publishing stale files")
    entries, assets = {}, {}
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
        entries[plugin] = {"id": plugin, "name": name, "kind": kind, "artifacts": versions}
    for filename, source in assets.items():
        shutil.copyfile(source, output / filename)
    catalog = list(entries.values())
    write_json(output / "plugins.json", {"schema": 1, "release": tag, "plugins": catalog})

    for plugin, entry in entries.items():
        if entry["kind"] != "channel":
            continue
        write_playlist(output / f"{plugin}.m3u", [entry])
    write_playlist(output / "all.m3u", entries.values())
    names = sorted(list(assets) + ["plugins.json", "all.m3u"]
                   + [plugin + ".m3u" for plugin, entry in entries.items() if entry["kind"] == "channel"])
    sums = "".join(f"{digest(output / name)}  {name}\n" for name in names)
    (output / "SHA256SUMS").write_bytes(sums.encode("ascii"))
    print(f"Prepared {tag} with {len(assets)} plugin files and {len(catalog)} catalog entries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", nargs="?")
    parser.add_argument("--repository-ref", help="Generate one repository all.m3u pinned to a full commit SHA")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.repository_ref:
        if args.tag:
            parser.error("Use a release tag or --repository-ref, not both")
        prepare_repository_playlist(args.repository_ref, args.output)
    elif args.tag:
        prepare(args.tag, args.output)
    else:
        parser.error("Provide a release tag or --repository-ref")
