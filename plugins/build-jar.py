"""Build the optional nTv TVBox channel and Android Python runtime JARs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
from urllib.request import urlopen
import zipfile

PLUGINS = Path(__file__).resolve().parent
ROOT = PLUGINS.parent
JAVA_API = ROOT / "host-api/src/xiao/bu/tv/jar"
PYTHON_VERSION = "3.14.8"
PYTHON_HASH = "11324313957da3736f44e90257304d288864321a4dd376ed0cc35a54eacf9a33"
GSON_HASH = "4241c14a7727c34feea6507ec801318a3d4a90f070e4525681079fb94ee4c593"
LIBRARIES = ["libcrypto_python.so", "libssl_python.so", "libsqlite3_python.so", "libpython3.14.so"]


def run(*command):
    subprocess.run([os.fspath(item) for item in command], check=True)


def download(path, url, expected):
    if not path.exists():
        with urlopen(url, timeout=90) as source, path.open("wb") as target:
            shutil.copyfileobj(source, target)
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f"Hash mismatch for {path}")


def python_arm64(output, ndk):
    archive = output / "cpython.tgz"
    download(archive, f"https://www.python.org/ftp/python/{PYTHON_VERSION}/"
             f"python-{PYTHON_VERSION}-aarch64-linux-android.tar.gz", PYTHON_HASH)
    sdk = output / "cpython"
    sdk.mkdir(exist_ok=True)
    stdlib = "lib/python3.14/"
    excluded = {"test", "idlelib", "tkinter", "turtledemo", "ensurepip", "__pycache__"}
    with tarfile.open(archive) as source:
        for member in source:
            relative = member.name.removeprefix("./").removeprefix("prefix/")
            parts = Path(relative).parts
            if not member.isfile() or not parts or ".." in parts or Path(relative).is_absolute():
                continue
            include = relative.startswith("include/python3.14/") or Path(relative).name in LIBRARIES
            if relative.startswith(stdlib) and not excluded.intersection(parts):
                include = True
            if include:
                target = sdk / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as stream, target.open("wb") as file:
                    shutil.copyfileobj(stream, file)
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    clang = ndk / "toolchains/llvm/prebuilt" / host / "bin" / ("clang.exe" if os.name == "nt" else "clang")
    run(clang, "--target=aarch64-linux-android24", "-shared", "-fPIC", "-O2",
        "-Wl,-z,max-page-size=16384", "-I" + str(sdk / "include/python3.14"),
        PLUGINS / "python/bridge.c", sdk / "lib/libpython3.14.so", "-o", sdk / "lib/libntvpython.so")
    return sdk


def python_assets(output, sdk):
    native = sdk / "lib"
    if not (native / "libntvpython.so").is_file() or not (
            (native / "libpython3.14.so").is_file()
            or ((native / "libpython3.11.so").is_file() and (native / "python311.zip").is_file())):
        raise ValueError("Runtime prefix is incomplete. Build arm32 with plugins/python/build-arm32.sh.")
    for file in sorted((sdk / "lib").rglob("*")):
        if file.is_file():
            yield "runtime/" + file.relative_to(sdk).as_posix(), file
    with tempfile.TemporaryDirectory(dir=output, prefix="wheels-") as folder:
        packages = Path(folder)
        run(sys.executable, "-m", "pip", "install", "--disable-pip-version-check", "--ignore-installed",
            "--only-binary=:all:", "--platform", "any", "--no-compile", "--target", packages,
            "-r", PLUGINS / "python/requirements.txt")
        for file in sorted(packages.rglob("*")):
            if file.is_file() and "bin" not in file.relative_to(packages).parts:
                yield "runtime/" + file.relative_to(packages).as_posix(), file
        yield "runtime/cacert.pem", packages / "certifi/cacert.pem"


def build(args):
    folder = PLUGINS / args.plugin
    arm32 = args.plugin == "python" and args.abi == "armeabi-v7a"
    output = ROOT / ".codex-tmp" / args.plugin / ("build-arm32" if arm32 else "build")
    output.mkdir(parents=True, exist_ok=True)
    is_python = args.plugin == "python"
    info = (dict(api=1, version=2, minSdk=14 if arm32 else 24, entry="xiao.bu.python.RuntimePlugin",
                 abis=[args.abi], pythonVersion="3.11.17" if arm32 else PYTHON_VERSION)
            if is_python else dict(api=1, version=6, minSdk=14, entry="xiao.bu.channel.tvbox.TvBoxPlugin",
                                   native=True, hostCapability="VodHost"))
    android = args.sdk / "platforms/android-34/android.jar"
    classes = output / "classes"
    if classes.exists():
        if classes.resolve().parent != output.resolve() or classes.is_symlink():
            raise ValueError("Build class directory escapes the plugin build directory")
        shutil.rmtree(classes)
    classes.mkdir(exist_ok=True)
    jar = output / "program.jar"
    java = args.java_home / "bin"
    extension = ".exe" if os.name == "nt" else ""
    dependency = [android]
    bundled_jars = []
    sources = sorted((folder / "src").rglob("*.java"))
    if not is_python:
        bl_ui = PLUGINS / "bl/src/xiao/bu/channel/bl"
        sources.extend(bl_ui / name for name in ("BlUi.java", "BlTheme.java", "BlTile.java"))
        cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
        for name in ("okhttp-3.12.13.jar", "okio-1.15.0.jar"):
            candidates = sorted(cache.rglob(name))
            if not candidates:
                raise ValueError(f"Build the host first to resolve {name}")
            dependency.append(candidates[0])
        gson = output / "gson-2.10.1.jar"
        download(gson, "https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar",
                 GSON_HASH)
        dependency.append(gson)
        bundled_jars.append(gson)
        for component, sha in (
                ("core", "201fb7deb6a40c61de5256c36a504486c6131a022e651107c6d2cab08e8e3168"),
                ("dash", "d90279c972a344365eeaa5e2d540408267d81b3481e85f91b673e56bc756d981")):
            archive = output / f"exoplayer-{component}-2.10.8.aar"
            download(archive, f"https://maven.aliyun.com/repository/jcenter/com/google/android/exoplayer/"
                     f"exoplayer-{component}/2.10.8/exoplayer-{component}-2.10.8.aar", sha)
            library = output / f"exoplayer-{component}.jar"
            with zipfile.ZipFile(archive) as source:
                library.write_bytes(source.read("classes.jar"))
            dependency.append(library)
            bundled_jars.append(library)
    contract = JAVA_API / ("PythonPlugin.java" if is_python else "ChannelPlugin.java")
    run(java / ("javac" + extension), "--release", "8", "-encoding", "UTF-8",
        "-d", classes, "-cp", os.pathsep.join(map(str, dependency)),
        contract, *sources)
    with zipfile.ZipFile(jar, "w") as z:
        for file in sorted(classes.rglob("*.class")):
            name = file.relative_to(classes).as_posix()
            if not name.startswith("xiao/bu/tv/jar/"):
                z.write(file, name)
    d8 = args.sdk / "build-tools/35.0.0/lib/d8.jar"
    run(java / ("java" + extension), "-cp", d8, "com.android.tools.r8.D8", "--min-api", str(info["minSdk"]),
        "--lib", android, "--classpath", classes, "--output", output, jar, *bundled_jars)
    dist = folder / "dist"
    dist.mkdir(exist_ok=True)
    destination = dist / ("python-arm32.jar" if arm32 else "python-arm64.jar" if is_python else "tvbox.jar")
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as z:
        def add(name, data):
            entry = zipfile.ZipInfo(name)
            entry.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(entry, data)
        add("classes.dex", (output / "classes.dex").read_bytes())
        add("python-plugin.json" if is_python else "channel-plugin.json", json.dumps(info))
        if is_python:
            sdk = args.runtime_dir if arm32 else python_arm64(output, args.ndk)
            for name, file in python_assets(output, sdk):
                add(name, file.read_bytes())
        assets = folder / ("runtime" if is_python else "assets")
        for file in sorted(assets.rglob("*")):
            if file.is_file() and "__pycache__" not in file.parts:
                add(("runtime/" if is_python else "assets/") + file.relative_to(assets).as_posix(), file.read_bytes())
    sha = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix(".sha256").write_bytes((sha + "\n").encode("ascii"))
    print(destination, sha)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plugin", choices=["tvbox", "python"])
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME", "D:/android/sdk"))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME", str(Path.home() / ".jdks/jbr-17.0.14")))
    parser.add_argument("--ndk", type=Path, default=os.environ.get("ANDROID_NDK_HOME", "D:/android/sdk/ndk/27.2.12479018"))
    parser.add_argument("--abi", choices=["armeabi-v7a", "arm64-v8a"], default="arm64-v8a")
    parser.add_argument("--runtime-dir", type=Path, help="ARM32 prefix produced by plugins/python/build-arm32.sh")
    args = parser.parse_args()
    if args.plugin == "python" and args.abi == "armeabi-v7a" and not args.runtime_dir:
        parser.error("ARM32 requires --runtime-dir")
    build(args)
