"""Exercise the delivered plugin on an explicitly selected KitKat device. Requires Impacket 0.12.0."""
import argparse
import datetime
import hashlib
import json
import re
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
from impacket.smbserver import SimpleSMBServer
from impacket.ntlm import compute_lmhash, compute_nthash
from impacket import smb3structs as smb2

ROOT=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--host',required=True,help='LAN address reachable from the device')
parser.add_argument('--port',type=int,default=39628)
parser.add_argument('--guest',action='store_true')
parser.add_argument('--screenshots',action='store_true')
parser.add_argument('--perf',action='store_true')
parser.add_argument('--adb',default='D:/android/sdk/platform-tools/adb.exe')
args=parser.parse_args()
adb=[args.adb,'-s',args.serial]
def run(*command,**kwargs):return subprocess.run(adb+list(command),check=True,**kwargs)
version=run('shell','getprop','ro.build.version.sdk',capture_output=True,text=True).stdout.strip()
if version not in {'15','19'}:raise SystemExit('This probe targets Android 4.0.4 / API 15 or Android 4.4 / API 19')
out=ROOT/'output/file-browser'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
out.mkdir(parents=True)
fixture=ROOT/'.codex-tmp/file-browser/device-share';fixture.mkdir(exist_ok=True)
video=ROOT/'.codex-tmp/file-browser/fixture/sample.mp4'
shutil.copyfile(video,fixture/'电影 sample.mp4')
port=args.port
server=SimpleSMBServer(listenAddress='0.0.0.0',listenPort=port)
server.addShare('videos',str(fixture.resolve()))
server.setSMB2Support(True)
if not args.guest: server.addCredential('viewer',0,compute_lmhash('test-password'),compute_nthash('test-password'))
stats={'requests':0,'bytes':0,'offsets':[]}
internal=server._SimpleSMBServer__server
old=None
def read(connection,instance,packet):
    req=smb2.SMB2Read(packet['Data'])
    # Bound throughput so a first frame cannot depend on a completed full download.
    time.sleep(min(0.25,req['Length']/350000))
    result=old(connection,instance,packet)
    if result[2]==0:
        stats['requests']+=1;stats['bytes']+=result[0][0]['DataLength'];stats['offsets'].append(req['Offset'])
    return result
old=internal.hookSmb2Command(smb2.SMB2_READ,read)
threading.Thread(target=server.start,daemon=True).start()
package='xiao.bu.filebrowserprobe'
remote='/sdcard/Android/data/'+package+'/files'
run('shell','mkdir','-p',remote+'/subfolder')
run('push',str(video),remote+'/sample.mp4',stdout=subprocess.DEVNULL)
(fixture/'hidden.txt').write_text('video filter fixture')
run('push',str(fixture/'hidden.txt'),remote+'/hidden.txt',stdout=subprocess.DEVNULL)
run('shell','input','keyevent','224')
command=['shell','am','instrument','-w','-e','smb',f'smb://{args.host}:{port}/videos/','-e','scanHost',args.host,'-e','guest','true' if args.guest else 'false','-e','screenshots','true' if args.screenshots else 'false','-e','perf','true' if args.perf else 'false',package+'/'+package+'.Probe']
process=subprocess.Popen(adb+command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
lines=[];first_frame=None;captured=set()
def collect():
    global first_frame
    for line in process.stdout:
        lines.append(line);print(line,end='',flush=True)
        if 'CAPTURE ' in line:
            match=re.search(r'CAPTURE (\w+)',line)
            if not match:continue
            name=match.group(1)
            if name in captured:continue
            captured.add(name)
            if name=='smb' and first_frame is None:first_frame=dict(bytes=stats['bytes'],requests=stats['requests'],file_bytes=video.stat().st_size)
            screenshot_remote=remote+'/'+name+'.png'
            subprocess.run(adb+['shell','screencap','-p',screenshot_remote],timeout=15)
            subprocess.run(adb+['pull',screenshot_remote,str(out/(name+'.png'))],timeout=15,stdout=subprocess.DEVNULL)
reader=threading.Thread(target=collect,daemon=True);reader.start()
try:process.wait(timeout=160)
except subprocess.TimeoutExpired:process.kill();raise
reader.join(20)
result=''.join(lines);(out/'result.txt').write_text(result,encoding='utf-8')
stats['first_frame']=first_frame;stats['plugin_sha256']=hashlib.sha256((ROOT/'plugins/file-browser/dist/file-browser.jar').read_bytes()).hexdigest()
(out/'transport.json').write_text(json.dumps(stats,indent=2),encoding='utf-8')
run('shell','cat','/proc/mounts',stdout=(out/'mounts.txt').open('w'),text=True)
print('Evidence:',out)
if 'FAIL ' in result or 'FILE_BROWSER_RESULT=PASS' not in result:raise SystemExit('Device probe failed')
if not args.screenshots:
    if not first_frame or first_frame['bytes']>=first_frame['file_bytes']:raise SystemExit('Could not prove first frame before full transfer')
    print('PASS streaming began before full transfer:',first_frame)
