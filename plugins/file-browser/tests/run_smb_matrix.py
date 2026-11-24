"""Real Android DEX/player matrix: Impacket plus isolated Samba/WSL instances."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import socket
import shlex
import subprocess
import tempfile
import threading
import time
from impacket.smbserver import SimpleSMBServer
from impacket.ntlm import compute_lmhash,compute_nthash
from impacket import smb3structs as smb2

ROOT=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--video',type=Path,required=True)
parser.add_argument('--adb',default='D:/android/sdk/platform-tools/adb.exe')
parser.add_argument('--only',help='Comma-separated case names for focused retests')
parser.add_argument('--distro',default='Ubuntu-22.04')
parser.add_argument('--base-port',type=int,default=39700)
args=parser.parse_args()
adb=[args.adb,'-s',args.serial];package='xiao.bu.filebrowserprobe'
out=ROOT/'output/file-browser-smb'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S');out.mkdir(parents=True)
servers=[];reverses=[];samba=None;samba_root=None;results=[]
def run(*command,**kw):return subprocess.run(list(command),check=True,**kw)
def wslpath(path):return run('wsl','-d',args.distro,'--exec','wslpath','-a',Path(path).resolve().as_posix(),capture_output=True,text=True).stdout.strip()
def start_impacket(port,auth):
    folder=Path(tempfile.mkdtemp(prefix='ntv-smb-'));nested=folder/'子目录 中文';nested.mkdir();shutil.copyfile(args.video,nested/'电影 sample.mp4');(folder/'hidden.txt').write_text('not video')
    server=SimpleSMBServer(listenAddress='127.0.0.1',listenPort=port);server.addShare('媒体 videos',str(folder));server.setSMB2Support(True)
    if auth:server.addCredential('viewer',0,compute_lmhash('test-password'),compute_nthash('test-password'))
    stats={'bytes':0,'reads':0,'offsets':[]};internal=server._SimpleSMBServer__server
    def read(connection,instance,packet):
        request=smb2.SMB2Read(packet['Data']);time.sleep(min(.25,request['Length']/350000));response=original(connection,instance,packet)
        if response[2]==0:stats['bytes']+=response[0][0]['DataLength'];stats['reads']+=1;stats['offsets'].append(request['Offset'])
        return response
    original=internal.hookSmb2Command(smb2.SMB2_READ,read);
    def serve():
        try:server.start()
        except OSError:pass  # Fixture socket intentionally closed during cleanup.
    threading.Thread(target=serve,daemon=True).start();servers.append((server,folder));return stats
try:
    for permission in ['READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE']:run(*adb,'shell','appops','set',package,permission,'allow')
    script=Path(__file__).with_name('samba_fixture.py')
    samba=subprocess.Popen(['wsl','-d',args.distro,'--exec','python3','-u',wslpath(script),'--video',wslpath(args.video),'--port',str(args.base_port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(out/'samba-setup.txt').open('w'),text=True,encoding='utf-8')
    line=samba.stdout.readline().strip()
    if not line.startswith('READY '):raise RuntimeError(line+' '+(out/'samba-setup.txt').read_text(encoding='utf-8'))
    samba_root=line[6:];print(line,flush=True)
    names=[('samba202-guest','',False,True),('samba210-guest','',False,False),('samba300-signed','nobody',False,False),('samba311-signed','nobody',False,False),('samba300-encrypted','nobody',True,False),('samba1-disabled','',True,False)]
    cases=[dict(label=n,port=args.base_port+i,user=u,negative=neg,enumerate=enum) for i,(n,u,neg,enum) in enumerate(names)]
    for i,auth in enumerate([False,True]):
        port=args.base_port+10+i;stats=start_impacket(port,auth)
        cases.insert(i,dict(label='impacket-auth' if auth else 'impacket-guest',port=port,user='viewer' if auth else '',negative=False,enumerate=not auth,stats=stats))
    range_hash=hashlib.sha256(args.video.read_bytes()[1048576:1052672]).hexdigest()
    for case in cases:
        if args.only and case['label'] not in args.only.split(','):continue
        name=case['label'];port=case['port'];print('TEST '+name,flush=True)
        # ADB reverse reaches only loopback fixtures, without exposing test shares to the LAN.
        run(*adb,'reverse',f'tcp:{port}',f'tcp:{port}');reverses.append(port)
        with socket.create_connection(('127.0.0.1',port),timeout=8):pass
        address=f'smb://127.0.0.1:{port}/'+('' if case['enumerate'] else '媒体 videos/')
        remote=['am','instrument','-w','-e','smb',address,'-e','user',case['user'],'-e','label',name,'-e','rangeHash',range_hash,'-e','negative',str(case['negative']).lower(),'-e','enumerate',str(case['enumerate']).lower(),package+'/'+package+'.SmbProbe']
        command=adb+['shell',shlex.join(remote)]
        process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace');lines=[];captured=False;watchdog=threading.Timer(150,process.kill);watchdog.start()
        for line in process.stdout:
            lines.append(line)
            if 'CAPTURE smb' in line and not captured:
                captured=True
                if 'stats' in case:case['first_frame']=dict(case['stats'],file_bytes=args.video.stat().st_size)
                else:
                    status=run('wsl','-d',args.distro,'--exec','smbstatus','--configfile='+samba_root+'/'+name+'/smb.conf','--brief',capture_output=True,text=True)
                    (out/(name+'-negotiated.txt')).write_text(status.stdout+status.stderr,encoding='utf-8')
        process.wait(timeout=10);watchdog.cancel();text=''.join(lines);(out/(name+'.txt')).write_text(text,encoding='utf-8');print('\n'.join(line for line in text.splitlines() if line.startswith(('FAIL','SERVER','SMB_RESULT','PASS SMB','PASS password','PASS correct','PASS unsupported'))),flush=True)
        passed='SMB_RESULT=PASS' in text
        if 'first_frame' in case:passed=passed and case['first_frame']['bytes']<case['first_frame']['file_bytes']
        
        if not case['negative'] and name.startswith('samba'):
            status_file=out/(name+'-negotiated.txt');negotiated=status_file.read_text(encoding='utf-8') if status_file.exists() else ''
            protocol={'samba202-guest':'SMB2_02','samba210-guest':'SMB2_10','samba300-signed':'SMB3_00','samba311-signed':'SMB3_11'}[name]
            passed=passed and protocol in negotiated and ('signed' not in name or 'AES-128-CMAC' in negotiated)
            case['negotiated']=negotiated
        case['passed']=passed;results.append(case)
    if not all(c['passed'] for c in results):raise RuntimeError('SMB matrix contains failures')
finally:
    for port in reverses:subprocess.run(adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL)
    if samba_root:
        # Terminate only fixtures whose pid files live under this unique temporary root.
        subprocess.run(['wsl','-d',args.distro,'--exec','python3','-c','import pathlib,os,signal; root=pathlib.Path('+repr(samba_root)+'); [os.kill(int(p.read_text().strip()),signal.SIGTERM) for p in root.glob("*/run/smbd.pid") if p.exists()]'])
        run('wsl','-d',args.distro,'--exec','python3','-c','import pathlib,tarfile; r=pathlib.Path('+repr(samba_root)+'); t=tarfile.open('+repr(wslpath(out/'samba-logs.tar.gz'))+',"w:gz"); [t.add(p,arcname=str(p.relative_to(r))) for pattern in ["*/server.log","*/smb.conf"] for p in r.glob(pattern)]; t.close()')
    if samba:samba.terminate()
    for server,folder in servers:
        server.stop()
    (out/'matrix.json').write_text(json.dumps({'plugin_sha256':hashlib.sha256((ROOT/'plugins/file-browser/dist/file-browser.jar').read_bytes()).hexdigest(),'serial':args.serial,'cases':results},indent=2),encoding='utf-8')
    print('Evidence: '+str(out),flush=True)
