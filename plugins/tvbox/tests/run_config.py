"""Test the TVBox configuration decoder on the JVM."""
import os
from pathlib import Path
import subprocess
import sys

tests = Path(__file__).resolve().parent
root = tests.parents[2]
deps = root / ".codex-tmp/tvbox/search-tests"
json_jar = deps / "json.jar"
if not json_jar.is_file():
    subprocess.run([sys.executable, tests / "run_search.py"], check=True)

java = Path(os.environ.get("JAVA_HOME", str(Path.home() / ".jdks/jbr-17.0.14"))) / "bin"
out = deps.parent / "config-tests"
out.mkdir(parents=True, exist_ok=True)
sources = [
    root / "plugins/tvbox/src/xiao/bu/channel/tvbox/BoxConfig.java",
    tests / "config-stubs/android/util/Base64.java",
    tests / "BoxConfigTest.java",
]
subprocess.run([java / "javac", "--release", "8", "-encoding", "UTF-8", "-cp", json_jar,
                "-d", out, *sources], check=True)
subprocess.run([java / "java", "-cp", os.pathsep.join(map(str, (out, json_jar))),
                "xiao.bu.channel.tvbox.BoxConfigTest"], check=True)
