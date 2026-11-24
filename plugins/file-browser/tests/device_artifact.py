"""Stage only inside the isolated debuggable probe; never depends on storage permission."""
import subprocess
import base64

PACKAGE='xiao.bu.filebrowserprobe'

def stage(adb,jar):
    ready=subprocess.run(adb+['shell','run-as',PACKAGE,'mkdir','-p','files'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if ready.returncode:return 'false'  # Legacy/installed asset mode; Java verifies its exact hash.
    # Windows adb shell stdin can stop at Ctrl-Z in binary data. Use ASCII on
    # stdin, then decode inside this probe's private directory.
    result=subprocess.run(adb+['shell','run-as',PACKAGE,'tee','files/plugin-under-test.b64'],input=base64.b64encode(jar.read_bytes()),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if result.returncode:return 'false'
    command="run-as "+PACKAGE+" sh -c 'base64 -d files/plugin-under-test.b64 > files/plugin-under-test.jar'"
    result=subprocess.run(adb+['shell',command],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    return 'true' if result.returncode==0 else 'false'
