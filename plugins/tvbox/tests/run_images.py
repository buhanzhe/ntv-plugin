from pathlib import Path
import os,subprocess,sys
root=Path(__file__).resolve().parents[3];out=root/'.codex-tmp/tvbox/image-tests';out.mkdir(parents=True,exist_ok=True)
deps=root/'.codex-tmp/tvbox/search-tests'
if not (deps/'json.jar').is_file():subprocess.run([sys.executable,Path(__file__).with_name('run_search.py')],check=True)
java=Path(os.environ.get('JAVA_HOME',str(Path.home()/'.jdks/jbr-17.0.14')))/'bin'
sources=[root/'plugins/tvbox/src/xiao/bu/channel/tvbox/BoxImageHeaders.java',Path(__file__).with_name('ImageHeadersTest.java')]
subprocess.run([str(java/'javac'),'--release','8','-encoding','UTF-8','-cp',str(deps/'json.jar'),'-d',str(out),*map(str,sources)],check=True)
subprocess.run([str(java/'java'),'-cp',str(out)+os.pathsep+str(deps/'json.jar'),'xiao.bu.channel.tvbox.ImageHeadersTest'],check=True)
