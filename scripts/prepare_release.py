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
    ("tvbox", "TVBox", "channel", ["tvbox/dist/tvbox.jar"]),
    ("python", "Python 运行时", "python", ["python/dist/python-arm32.jar", "python/dist/python-arm64.jar"]),
    ("bl", "BL 哔哩哔哩", "channel", ["bl/dist/bl.jar"]),
    ("tvbus", "TVBus", "tvbus", ["tvbus/dist/tvbus.jar"]),
    ("folia-full", "Folia 音乐", "channel", ["folia-full/dist/folia-full.jar"]),
    ("folia", "Folia 音频频道", "channel", ["folia/dist/folia.jar"]),
    ("ku9-hnyx", "Ku9 湖南 IPv6", "ku9-js", ["ku9/hnyx.js"]),
    ("radio-classic", "经典唱片", "radio-ui", ["radio-ui2/dist/radio-classic.jar"]),
    ("radio-optical", "柔光唱片", "radio-ui", ["radio-ui2/dist/radio-ui2.jar"]),
    ("radio-ui-legacy", "旧版电台界面", "archive", ["radio-ui/dist/radio-ui-v1.jar", "radio-ui/dist/radio-ui-v2.jar"]),
    ("tv-ui", "电视界面", "tv-ui", [f"tv-ui/dist/{name}.jar" for name in ("builtin", "ui2", "ui3", "ui4", "ui5")]),
]
METADATA = {
    "channel": "channel-plugin.json", "python": "python-plugin.json", "tvbus": "tvbus-plugin.json",
    "radio-ui": "radio-ui.json", "archive": "radio-ui.json", "tv-ui": "tv-ui.json",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(path, kind, tag):
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
    write_json(output / "plugins.json", {"schema": 1, "release": tag, "plugins": list(entries.values())})
    styles = []
    for style in ("classic", "optical"):
        entry = entries["radio-" + style]
        item = entry["artifacts"][0]
        styles.append({"id": style, "name": entry["name"], "style": style, "url": item["url"], "sha256": item["sha256"]})
    write_json(output / "radio-ui.json", {"schema": 1, "styles": styles})

    extras = {
        "tvbox": {"url": "http://www.xn--sss604efuw.cc/tv"},
        "folia": {"audio": "https://example.org/song.mp3", "title": "Folia"},
    }
    for plugin, entry in entries.items():
        if entry["kind"] != "channel":
            continue
        item = entry["artifacts"][0]
        query = urlencode({"sha256": item["sha256"], **extras.get(plugin, {})})
        playlist = f'#EXTM3U\n#EXTINF:-1 group-title="插件",{entry["name"]}\n{item["url"]}?{query}\n'
        (output / f"{plugin}.m3u").write_bytes(playlist.encode("utf-8"))
    names = sorted(list(assets) + ["plugins.json", "radio-ui.json"]
                   + [plugin + ".m3u" for plugin, entry in entries.items() if entry["kind"] == "channel"])
    sums = "".join(f"{digest(output / name)}  {name}\n" for name in names)
    (output / "SHA256SUMS").write_bytes(sums.encode("ascii"))
    print(f"Prepared {tag} with {len(assets)} plugin files and {len(entries)} catalog entries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.tag, args.output)
