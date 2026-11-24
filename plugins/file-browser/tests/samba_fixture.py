"""Isolated Samba instances; never edits /etc/samba or the system password database."""
import argparse
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time

parser=argparse.ArgumentParser()
parser.add_argument('--video',type=Path,required=True)
parser.add_argument('--port',type=int,default=39700)
args=parser.parse_args()
root=Path(tempfile.mkdtemp(prefix='ntv-smb-'))
share=root/'share';share.mkdir(mode=0o755);root.chmod(0o755)
nested=share/'子目录 中文';nested.mkdir(mode=0o755)
shutil.copyfile(args.video,nested/'电影 sample.mp4')
(share/'hidden.txt').write_text('not a video')
cases=[('samba202-guest','SMB2_02',False,False,False),
       ('samba210-guest','SMB2_10',False,False,False),
       ('samba300-signed','SMB3_00',True,True,False),
       ('samba311-signed','SMB3_11',True,True,False),
       ('samba300-encrypted','SMB3_00',True,True,True),
       ('samba1-disabled','NT1',False,False,False)]
processes=[]
def stop(*unused):raise KeyboardInterrupt()
signal.signal(signal.SIGTERM,stop)
try:
    for i,(name,protocol,auth,signed,encrypted) in enumerate(cases):
        directory=root/name;directory.mkdir()
        for folder in ['lock','state','cache','private','run']:(directory/folder).mkdir()
        config=directory/'smb.conf'
        config.write_text(f'''[global]
workgroup = WORKGROUP
server role = standalone server
security = user
interfaces = 127.0.0.1
bind interfaces only = yes
smb ports = {args.port+i}
server min protocol = {protocol}
server max protocol = {protocol}
server signing = {'mandatory' if signed else 'default'}
smb encrypt = {'required' if encrypted else 'off'}
map to guest = {'Never' if auth else 'Bad User'}
guest account = nobody
passdb backend = smbpasswd:{directory}/smbpasswd
lock directory = {directory}/lock
state directory = {directory}/state
cache directory = {directory}/cache
private dir = {directory}/private
pid directory = {directory}/run
log file = {directory}/server.log
log level = 3
load printers = no
disable spoolss = yes
printing = bsd
printcap name = /dev/null
[媒体 videos]
path = {share}
read only = yes
browseable = yes
guest ok = {'no' if auth else 'yes'}
''',encoding='utf-8')
        if auth:subprocess.run(['smbpasswd','-s','-a','-c',str(config),'nobody'],input='test-password\ntest-password\n',text=True,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        processes.append(subprocess.Popen(['smbd','--foreground','--no-process-group','--configfile='+str(config)],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL))
    time.sleep(1)
    if any(p.poll() is not None for p in processes):raise RuntimeError('Samba fixture exited: '+str(root))
    print('READY '+str(root),flush=True)
    while True:time.sleep(1)
except KeyboardInterrupt:pass
finally:
    for p in processes:
        if p.poll() is None:p.terminate()
    for p in processes:
        try:p.wait(5)
        except subprocess.TimeoutExpired:p.kill()
    # Keep server logs for the runner to collect; caller owns this unique directory.
