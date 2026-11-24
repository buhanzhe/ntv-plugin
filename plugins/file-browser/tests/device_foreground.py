"""Bring only the isolated probe forward; do not restart a live permission flow."""
import subprocess

PACKAGE='xiao.bu.filebrowserprobe'

def activate(adb,ready,needs_foreground,fixture=None):
    extra=['--es','fixture',fixture] if fixture else []
    def start(flags,action=()):
        subprocess.run(adb+['shell','am','start','-W','-n',PACKAGE+'/.ProbeActivity','-f',flags,*extra,*action],check=True,stdout=subprocess.DEVNULL)
    if not ready.wait(3):
        start('0x10008000',['-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER'])
        start('0x10008000')
        if not ready.wait(5):raise RuntimeError('Probe activity did not start')
    if needs_foreground.wait(.15):
        state=subprocess.check_output(adb+['shell','dumpsys','activity','activities'],timeout=10).decode(errors='replace')
        top=next((line for line in state.splitlines() if 'topResumedActivity=' in line),'')
        if PACKAGE in top or any(p in top for p in ['permissioncontroller','com.lbe.security.miui','com.miui.securitycenter']):return
        start('0x10020000')  # NEW_TASK | REORDER_TO_FRONT: keep this exact activity instance.
