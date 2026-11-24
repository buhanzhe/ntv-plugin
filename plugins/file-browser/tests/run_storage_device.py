"""API 23+ permission deny/grant, real SAF persistence and D-pad checks in an isolated APK."""
import argparse
import datetime
import hashlib
import json
import re
import uuid
from pathlib import Path
import subprocess
import threading

ROOT=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',default='127.0.0.1:21503')
parser.add_argument('--adb',default='D:/android/sdk/platform-tools/adb.exe')
parser.add_argument('--video',type=Path,required=True)
args=parser.parse_args()
adb=[args.adb,'-s',args.serial]
out=ROOT/'output/file-browser-storage'/(datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+args.serial.replace(':','_'))
out.mkdir(parents=True)
package='xiao.bu.filebrowserprobe'
def run(*cmd,**kw):return subprocess.run(adb+list(cmd),check=True,**kw)
sdk=int(run('shell','getprop','ro.build.version.sdk',capture_output=True,text=True).stdout.strip())
read='READ_MEDIA_VIDEO' if sdk>=33 else 'READ_EXTERNAL_STORAGE'
permissions=[read] if sdk>=33 else ['WRITE_EXTERNAL_STORAGE',read]
fixture='ntv-file-browser-test-'+uuid.uuid4().hex[:12]
remote='/sdcard/Movies/'+fixture if sdk>=30 else f'/sdcard/Android/data/{package}/files'
run('shell','mkdir','-p',remote+'/subfolder')
run('push',str(args.video),remote+'/sample.mp4',stdout=subprocess.DEVNULL)
if sdk>=30:run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file://'+remote+'/sample.mp4',stdout=subprocess.DEVNULL)
for phase in ['grant','restore']:
    for permission in permissions:
        run('shell','pm','revoke',package,'android.permission.'+permission,stdout=subprocess.DEVNULL)
        run('shell','appops','set',package,permission,'allow' if phase=='grant' and permission=='WRITE_EXTERNAL_STORAGE' else 'ignore')
    command=['shell','am','instrument','-w','-e','fixture',fixture,'-e','phase',phase,package+'/'+package+'.StorageProbe']
    process=subprocess.Popen(adb+command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    lines=[];captured=set()
    def collect():
        for line in process.stdout:
            lines.append(line)
            match=re.search(r'CAPTURE ([\w-]+)',line)
            if match and match[1] not in captured:
                captured.add(match[1])
                with (out/(phase+'-'+match[1]+'.png')).open('wb') as image:run('exec-out','screencap','-p',stdout=image)
            if line.strip()=='WAIT_APP_OP_ALLOW':
                for permission in permissions:run('shell','pm','grant',package,'android.permission.'+permission);run('shell','appops','set',package,permission,'allow')
    reader=threading.Thread(target=collect,daemon=True);reader.start()
    try:process.wait(timeout=150)
    except subprocess.TimeoutExpired:
        subprocess.run(adb+['shell','uiautomator','dump','/sdcard/file-browser-storage-failed.xml'])
        process.kill();raise
    reader.join(5);result=''.join(lines)
    (out/(phase+'.txt')).write_text(result,encoding='utf-8');print(result,flush=True)
    if 'STORAGE_RESULT=PASS' not in result:raise SystemExit('Storage device probe failed: '+str(out))
print('PASS effective permission recovery, D-pad navigation and persisted SAF read after revocation:',out)

(out/'summary.json').write_text(json.dumps({'serial':args.serial,'sdk':sdk,'plugin_sha256':hashlib.sha256((ROOT/'plugins/file-browser/dist/file-browser.jar').read_bytes()).hexdigest(),'phases':['grant','restore'],'result':'PASS','limitation':'Runtime dialog not exercised; install-granted ROM, AppOps/settings and SAF paths tested' if any('LIMIT emulator treats storage' in p.read_text(encoding='utf-8') for p in out.glob('*.txt')) else None},indent=2),encoding='utf-8')
