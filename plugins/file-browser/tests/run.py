"""Run after build.py; --smb adds a real loopback SMB2 fixture (requires impacket)."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / '.codex-tmp/file-browser'
JAVA = Path(os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/corretto-1.8.0_492')) / 'bin'
parser = argparse.ArgumentParser()
parser.add_argument('--smb', action='store_true')
parser.add_argument('--guest', action='store_true')
args = parser.parse_args()
cp = os.pathsep.join(map(str, [OUT / 'classes', *sorted((OUT / 'deps').glob('android-*.jar'))]))
exe = '.exe' if os.name == 'nt' else ''
subprocess.run([str(JAVA / ('javac' + exe)), '-encoding', 'UTF-8', '-cp', cp, '-d', str(OUT / 'classes'),
                str(Path(__file__).with_name('TransportTest.java'))], check=True)
command = [str(JAVA / ('java' + exe)), '-cp', cp, 'xiao.bu.channel.files.TransportTest']
if args.smb:
    from impacket.smbserver import SimpleSMBServer
    from impacket.ntlm import compute_lmhash, compute_nthash
    fixture = OUT / 'fixture'
    fixture.mkdir(exist_ok=True)
    (fixture / '电影 sample.mp4').write_bytes(bytes(range(256)) * 1024)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    server = SimpleSMBServer(listenAddress='127.0.0.1', listenPort=port)
    server.addShare('videos', str(fixture.resolve()))
    server.setSMB2Support(True)
    if not args.guest: server.addCredential('viewer', 0, compute_lmhash('test-password'), compute_nthash('test-password'))
    threading.Thread(target=server.start, daemon=True).start()
    command.append(f'smb://127.0.0.1:{port}/videos/')
    if args.guest: command.append('guest')
subprocess.run(command, check=True)
