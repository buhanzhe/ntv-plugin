"""Build the standalone API-14 native file browser; dependencies are SHA-256 pinned."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile
import backport
import io

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', 'D:/android/sdk'))
parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/corretto-1.8.0_492'))
args = parser.parse_args()
sdk, java = Path(args.sdk), Path(args.java_home) / 'bin'
out = ROOT / '.codex-tmp/file-browser'
deps = out / 'deps'
deps.mkdir(parents=True, exist_ok=True)
libraries = []
for name, entry in json.loads((HERE / 'dependencies.json').read_text()).items():
    target = deps / name
    if not target.exists():
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + entry['maven'], timeout=60) as response:
            target.write_bytes(response.read())
    if hashlib.sha256(target.read_bytes()).hexdigest() != entry['sha256']:
        raise SystemExit('Dependency SHA-256 mismatch: ' + name)
    patched = deps / ('android-' + name)
    backport.jar(target, patched)
    libraries.append(patched)
classes = out / 'classes'
if classes.exists():
    assert classes.resolve().parent == out.resolve()
    shutil.rmtree(classes)
classes.mkdir()
android = sdk / 'platforms/android-30/android.jar'
# Compile the baseline entry point against actual Android 4.0 stubs too. Modern
# storage is isolated and compiled separately against the installed SDK.
legacy = out / 'android14.jar'
if not legacy.exists():
    with urllib.request.urlopen('https://dl.google.com/android/repository/android-14_r04.zip', timeout=60) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != 'da1af15c77ba41d062eb6d0ef5921cc424ab6167587033b830609d65f04802b6':
        raise SystemExit('Android API 14 archive hash mismatch')
    with zipfile.ZipFile(io.BytesIO(archive)) as archive:
        legacy.write_bytes(archive.read('android-4.0.2/android.jar'))
if hashlib.sha256(legacy.read_bytes()).hexdigest() != '1fbb4b4e493389f99e27dff98dbfed1b134855f204948229f4f31cb66908205f':
    raise SystemExit('Android API 14 stub hash mismatch')
exe = '.exe' if os.name == 'nt' else ''
subprocess.run([str(java / ('javac' + exe)), '-source', '8', '-target', '8', '-encoding', 'UTF-8',
                '-cp', os.pathsep.join(map(str, [android, *libraries])), '-d', str(classes),
                str(ROOT / 'host-api/src/xiao/bu/tv/jar/ChannelPlugin.java'),
                *map(str, (HERE / 'src').rglob('*.java'))], check=True)
subprocess.run([str(java / ('javac' + exe)), '-source', '8', '-target', '8', '-encoding', 'UTF-8',
                '-cp', os.pathsep.join(map(str, [legacy, classes, *libraries])), '-d', str(classes),
                str(ROOT / 'host-api/src/xiao/bu/tv/jar/ChannelPlugin.java'),
                *[str(p) for p in (HERE / 'src').rglob('*.java') if p.name != 'ModernStorage.java']], check=True)
program, contract = out / 'program.jar', out / 'contract.jar'
with zipfile.ZipFile(program, 'w') as code, zipfile.ZipFile(contract, 'w') as api:
    for file in sorted(classes.rglob('*.class')):
        name = file.relative_to(classes).as_posix()
        (api if name.startswith('xiao/bu/tv/jar/') else code).writestr(name, backport.class_bytes(file.read_bytes()))
dex = out / 'dex'
dex.mkdir(exist_ok=True)
subprocess.run([str(java / ('java' + exe)), '-cp', str(sdk / 'build-tools/30.0.3/lib/d8.jar'),
                'com.android.tools.r8.R8', '--min-api', '14', '--pg-conf', str(HERE / 'shrink.pro'),
                '--lib', str(android), '--lib', str(contract), '--output', str(dex),
                str(program), *map(str, libraries)], check=True)
dist = HERE / 'dist'
dist.mkdir(exist_ok=True)
jar = dist / 'file-browser.jar'
with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    def write(name, data):
        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, data)
    write('classes.dex', (dex / 'classes.dex').read_bytes())
    write('channel-plugin.json', json.dumps(dict(api=1, version=1, minSdk=14, native=True,
          entry='xiao.bu.channel.files.FileBrowserPlugin')))
    for name in ['NOTICE', 'LICENSE-jcifs', 'LICENSE-slf4j', 'LICENSE-bouncycastle']:
        write(name, (HERE / name).read_bytes().replace(b'\r\n', b'\n'))
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
(dist / 'file-browser.sha256').write_text(digest + '\n', encoding='ascii')
# This is a deployment template until the JAR is published to the given HTTPS host.
(dist / 'example.m3u').write_text('#EXTM3U\n#EXTINF:-1 group-title="视频",文件浏览\n'
        'https://raw.githubusercontent.com/buhanzhe/ntv-plugin/main/plugins/file-browser/dist/file-browser.jar?sha256=' + digest + '\n', encoding='utf-8')
print(f'Built {jar}: {jar.stat().st_size} bytes, SHA-256 {digest}')
