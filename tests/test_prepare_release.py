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
                             {"file-browser", "tvbox", "python", "bl", "tvbus"})
            expected = {"file-browser.jar", "tvbox.jar", "python-arm32.jar", "python-arm64.jar",
                        "bl.jar", "plugins.json"}
            if (ROOT / "plugins/tvbus/dist/tvbus.jar").is_file():
                expected.add("tvbus.jar")
            self.assertEqual({p.name for p in output.iterdir()}, expected)
            listed = set()
            for plugin in catalog["plugins"]:
                for artifact in plugin["artifacts"]:
                    listed.add(artifact["file"])
                    data = (output / artifact["file"]).read_bytes()
                    self.assertEqual(len(data), artifact["bytes"])
                    self.assertEqual(hashlib.sha256(data).hexdigest(), artifact["sha256"])
            self.assertEqual(listed, expected - {"plugins.json"})

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
