"""Verify the distributable catalog and guard against shipping stale output."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("prepare_release", ROOT / "scripts/prepare_release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class ReleaseTest(unittest.TestCase):
    def test_plugin_only_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "release"
            RELEASE.prepare("v9.9.9", output)
            catalog = json.loads((output / "plugins.json").read_text(encoding="utf-8"))
            self.assertEqual({p["id"] for p in catalog["plugins"]},
                             {"file-browser", "tvbox", "python", "bl", "tvbus", "ku9-hnyx"})
            allowed = {"file-browser.jar", "tvbox.jar", "python-arm32.jar", "python-arm64.jar",
                       "bl.jar", "tvbus.jar", "hnyx.js", "plugins.json", "SHA256SUMS", "all.m3u",
                       "file-browser.m3u", "tvbox.m3u", "bl.m3u"}
            self.assertLessEqual({p.name for p in output.iterdir()}, allowed)
            for plugin in catalog["plugins"]:
                for artifact in plugin["artifacts"]:
                    data = (output / artifact["file"]).read_bytes()
                    self.assertEqual(len(data), artifact["bytes"])
                    self.assertEqual(hashlib.sha256(data).hexdigest(), artifact["sha256"])
            sums = dict(line.split("  ", 1)[::-1] for line in
                        (output / "SHA256SUMS").read_text().splitlines())
            self.assertEqual(set(sums), {p.name for p in output.iterdir()} - {"SHA256SUMS"})
            for name, expected in sums.items():
                self.assertEqual(hashlib.sha256((output / name).read_bytes()).hexdigest(), expected)
            playlist = (output / "all.m3u").read_text(encoding="utf-8")
            self.assertEqual(playlist.count("#EXTINF:"), 3)

    def test_nonempty_output_is_rejected_without_modifying_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            stale = output / "old-installer.apk"
            stale.write_bytes(b"old output")
            with self.assertRaisesRegex(ValueError, "must be empty"):
                RELEASE.prepare("v9.9.9", output)
            self.assertEqual(stale.read_bytes(), b"old output")
            self.assertEqual(list(output.iterdir()), [stale])


if __name__ == "__main__":
    unittest.main()
