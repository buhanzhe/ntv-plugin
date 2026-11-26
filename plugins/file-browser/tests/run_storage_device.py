"""API 23+ permission deny/grant, real SAF persistence and D-pad checks in an isolated APK."""
import argparse
from device_artifact import stage
from device_foreground import activate
import datetime
import hashlib
import json
import re
import uuid
from pathlib import Path
import subprocess
import threading
import time

ROOT=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',default='127.0.0.1:21503')
parser.add_argument('--adb',default='D:/android/sdk/platform-tools/adb.exe')
parser.add_argument('--video',type=Path,required=True)
parser.add_argument('--phases',default='grant,restore',help='grant, restore, or cancel; use cancel to verify prompt/picker cancellation')
args=parser.parse_args()
adb=[args.adb,'-s',args.serial]
out=ROOT/'output/file-browser-storage'/(datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+args.serial.replace(':','_'))
out.mkdir(parents=True)
package='xiao.bu.filebrowserprobe'
def run(*cmd,**kw):return subprocess.run(adb+list(cmd),check=True,**kw)
sdk=int(run('shell','getprop','ro.build.version.sdk',capture_output=True,text=True).stdout.strip())
read='READ_MEDIA_VIDEO' if sdk>=33 else 'READ_EXTERNAL_STORAGE'
permissions=(['READ_MEDIA_VISUAL_USER_SELECTED',read] if sdk>=34 else [read]) if sdk>=33 else ['WRITE_EXTERNAL_STORAGE',read]
jar=ROOT/'plugins/file-browser/dist/file-browser.jar'
jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
external=stage(adb,jar)
fixture='ntv-file-browser-test-'+uuid.uuid4().hex[:12]
remote='/sdcard/Movies/'+fixture if sdk>=30 else f'/sdcard/Android/data/{package}/files'
run('shell','mkdir','-p',remote+'/subfolder')
run('push',str(args.video),remote+'/sample.mp4',stdout=subprocess.DEVNULL)
if sdk>=30:run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file://'+remote+'/sample.mp4',stdout=subprocess.DEVNULL)
phases=args.phases.split(',')
assert phases and set(phases)<={'grant','restore','cancel'}
for phase in phases:
    for permission in permissions:
        if sdk>=28:run('shell','pm','clear-permission-flags',package,'android.permission.'+permission,'user-set','user-fixed',stdout=subprocess.DEVNULL)
        run('shell','pm','revoke',package,'android.permission.'+permission,stdout=subprocess.DEVNULL)
        if permission!='READ_MEDIA_VISUAL_USER_SELECTED':run('shell','appops','set',package,permission,'ignore' if phase=='restore' else 'allow')
    # Some TV ROMs keep storage install-granted even after revoke. Restrict AppOps
    # only for that case; otherwise a real runtime grant must remain effective.
    if phase!='restore':
        state=run('shell','dumpsys','package',package,capture_output=True,text=True,encoding='utf-8',errors='replace').stdout
        if re.search(r'android\.permission\.'+read+r':\s+granted=true',state):run('shell','appops','set',package,read,'ignore')
    command=['shell','am','instrument','-w','-e','captureApp','true' if sdk>=30 else 'false','-e','externalJar',external,'-e','expectedHash',jar_hash,'-e','fixture',fixture,'-e','phase',phase,package+'/'+package+'.StorageProbe']
    process=subprocess.Popen(adb+command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    lines=[];captured=set();activity_ready=threading.Event();needs_foreground=threading.Event()
    def collect():
        for line in process.stdout:
            lines.append(line)
            if line.strip()=="ACTIVITY_READY":activity_ready.set()
            if line.strip()=="WAIT_FOREGROUND":needs_foreground.set()
            if line.startswith(("TRACE ","FAIL ","APP_CAPTURE ")):print(line,end="",flush=True)
            app_match=re.search(r'APP_CAPTURE ([\w-]+)',line)
            if app_match and 'app-'+app_match[1] not in captured:
                captured.add('app-'+app_match[1])
                with (out/(phase+'-app-'+app_match[1]+'.png')).open('wb') as image:run('exec-out','run-as',package,'cat','files/probe-app-'+app_match[1]+'.png',stdout=image)
            match=None if app_match else re.search(r'CAPTURE ([\w-]+)',line)
            if match and match[1] not in captured:
                captured.add(match[1])
                with (out/(phase+'-'+match[1]+'.png')).open('wb') as image:run('exec-out','screencap','-p',stdout=image)
            if line.strip()=='WAIT_APP_OP_ALLOW':
                for permission in permissions:run('shell','pm','grant',package,'android.permission.'+permission);run('shell','appops','set',package,permission,'allow')
    reader=threading.Thread(target=collect,daemon=True);reader.start()
    if sdk>=29:activate(adb,activity_ready,needs_foreground,fixture)
    try:process.wait(timeout=150)
    except subprocess.TimeoutExpired:
        process.kill()
        run('shell','am','force-stop',package)
        (out/(phase+'.txt')).write_text(''.join(lines)+'TIMEOUT before probe completion\n',encoding='utf-8')
        raise
    reader.join(5);result=''.join(lines)
    (out/(phase+'.txt')).write_text(result,encoding='utf-8');print(result,flush=True)
    if 'STORAGE_RESULT=PASS' not in result:raise SystemExit('Storage device probe failed: '+str(out))
print('PASS storage phases '+','.join(phases)+':',out)

(out/'summary.json').write_text(json.dumps({'serial':args.serial,'sdk':sdk,'plugin_sha256':hashlib.sha256((ROOT/'plugins/file-browser/dist/file-browser.jar').read_bytes()).hexdigest(),'phases':phases,'result':'PASS','limitation':'Runtime dialog not exercised; install-granted ROM, AppOps/settings and SAF paths tested' if any('LIMIT emulator treats storage' in p.read_text(encoding='utf-8') for p in out.glob('*.txt')) else None},indent=2),encoding='utf-8')
