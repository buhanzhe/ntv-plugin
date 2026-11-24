"""Build a debug-signed isolated test APK. No changes to installed nTv packages."""
from pathlib import Path
import os
import argparse
import subprocess
import zipfile

parser=argparse.ArgumentParser();parser.add_argument('--target-sdk',type=int,default=25);args=parser.parse_args()
ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'.codex-tmp/file-browser/device'
OUT.mkdir(parents=True,exist_ok=True)
SDK=Path(os.environ.get('ANDROID_HOME','D:/android/sdk'))
JAVA=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/corretto-1.8.0_492'))/'bin'
BT=SDK/'build-tools/30.0.3'
ANDROID=SDK/'platforms/android-30/android.jar'
manifest=OUT/'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="xiao.bu.filebrowserprobe">
<uses-sdk android:minSdkVersion="14" android:targetSdkVersion="TARGET_SDK"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
<application android:usesCleartextTraffic="true" android:theme="@android:style/Theme.Holo.NoActionBar" android:label="File browser probe">
<activity android:name=".ProbeActivity" android:screenOrientation="landscape" android:exported="true"/></application>
<instrumentation android:name=".Probe" android:targetPackage="xiao.bu.filebrowserprobe"/>
<instrumentation android:name=".SmbProbe" android:targetPackage="xiao.bu.filebrowserprobe"/>
<instrumentation android:name=".StorageProbe" android:targetPackage="xiao.bu.filebrowserprobe"/>
</manifest>'''.replace('TARGET_SDK',str(args.target_sdk)),encoding='utf-8')
classes=OUT/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(JAVA/'javac.exe'),'-source','8','-target','8','-encoding','UTF-8','-cp',str(ANDROID),'-d',str(classes),
    str(ROOT/'host-api/src/xiao/bu/tv/jar/ChannelPlugin.java'),*map(str,Path(__file__).with_name('device').glob('*.java'))],check=True)
code=OUT/'code.jar'
with zipfile.ZipFile(code,'w') as z:
    for file in classes.rglob('*.class'):z.write(file,file.relative_to(classes).as_posix())
dex=OUT/'dex';dex.mkdir(exist_ok=True)
subprocess.run([str(JAVA/'java.exe'),'-cp',str(BT/'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','14','--lib',str(ANDROID),'--output',str(dex),str(code)],check=True)
apk=OUT/'unsigned.apk'
subprocess.run([str(BT/'aapt.exe'),'package','-f','-M',str(manifest),'-I',str(ANDROID),'-F',str(apk)],check=True)
with zipfile.ZipFile(apk,'a',compression=zipfile.ZIP_DEFLATED) as z:
    z.write(dex/'classes.dex','classes.dex');z.write(ROOT/'plugins/file-browser/dist/file-browser.jar','assets/file-browser.jar')
target=OUT/'probe.apk'
subprocess.run([str(JAVA/'java.exe'),'-jar',str(BT/'lib/apksigner.jar'),'sign','--ks',str(Path.home()/'.android/debug.keystore'),
    '--ks-pass','pass:android','--key-pass','pass:android','--out',str(target),str(apk)],check=True)
print(target)
