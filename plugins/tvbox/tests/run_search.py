"""Run deterministic search concurrency, cache integrity and music parsing checks."""
import hashlib,json,os,subprocess,urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'.codex-tmp/tvbox/search-tests';OUT.mkdir(parents=True,exist_ok=True)
JAVA=Path(os.environ.get('JAVA_HOME',str(Path.home()/'.jdks/jbr-17.0.14')))/'bin'
deps=json.loads((ROOT/'plugins/bl/dependencies.json').read_text())
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
libraries=[]
for name in ['json.jar','okhttp.jar','okio.jar']:
    info=deps[name];target=OUT/name
    if not target.is_file():
        candidates=list(cache.rglob(Path(info['maven']).name)) if cache.is_dir() else []
        valid=next((p for p in candidates if hashlib.sha256(p.read_bytes()).hexdigest()==info['sha256']),None)
        target.write_bytes(valid.read_bytes() if valid else urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+info['maven'],timeout=30).read())
    assert hashlib.sha256(target.read_bytes()).hexdigest()==info['sha256'],name
    libraries.append(target)
classpath=os.pathsep.join(map(str,libraries))
sources=[ROOT/'plugins/tvbox/src/xiao/bu/channel/tvbox'/n for n in ['SearchFanout.java','SpiderJarCache.java','MusicSearch.java','BoxHttp.java']]
sources.append(Path(__file__).with_name('SearchFanoutTest.java'))
sources.extend(Path(__file__).with_name('stubs').rglob('*.java'))
subprocess.run([str(JAVA/'javac'),'-encoding','UTF-8','--release','8','-cp',classpath,'-d',str(OUT),*map(str,sources)],check=True)
subprocess.run([str(JAVA/'java'),'-cp',classpath+os.pathsep+str(OUT),'xiao.bu.channel.tvbox.SearchFanoutTest'],check=True)
