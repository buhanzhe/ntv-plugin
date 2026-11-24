"""Public local fixtures for progressive multi-source searches on Android devices."""
import argparse, collections, json, socket, threading, time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

def run(args):
    origin = f'http://{args.host}:{args.port}'
    counts = collections.Counter()
    lock = threading.Lock()
    stamp = str(time.time_ns())
    def item(key, query):
        return {'vod_id': 'same-id', 'vod_name': key + ' · ' + query,
                'vod_play_from': key, 'vod_play_url': '测试$' + origin + '/unused.mp4'}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def reply(self, value, status=200, mime='application/json'):
            body = value if isinstance(value, bytes) else value.encode() if isinstance(value, str) else json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status);self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)));self.end_headers()
            try: self.wfile.write(body)
            except (ConnectionError, OSError): pass
        def do_GET(self):
            path = urlsplit(self.path);query = parse_qs(path.query)
            with lock: counts[path.path] += 1
            if path.path == '/fast' and not query: time.sleep(.7)
            if path.path == '/stats':
                with lock: snapshot = dict(counts)
                return self.reply(snapshot)
            if path.path == '/config':
                sites = [{'key': k, 'name': k, 'type': 1, 'api': origin+'/'+k} for k in ['fast', 'slow', 'error']]
                sites += [{'key': k, 'name': k, 'type': 3, 'api': origin+'/'+k+ext} for k, ext in [('js','.js'), ('python','.py')]]
                sites += [{'key': 'disabled', 'name': 'disabled', 'type': 1, 'api': origin+'/disabled', 'searchable': 0}]
                config = {'sites': sites}
                if args.jar:
                    config['spider'] = origin + '/fixture.jar?v=' + stamp
                    sites += [{'key': k, 'name': k, 'type': 3, 'api': 'csp_Fixture',
                               'searchable': int(k == 'native-search'), 'ext': origin+'/fixture.mp4'} for k in ['native','native-search']]
                return self.reply(config)
            if path.path == '/fixture.jar' and args.jar:
                time.sleep(.8)
                return self.reply(args.jar.read_bytes(), mime='application/java-archive')
            if path.path == '/fixture.mp4':
                if self.headers.get('X-Fixture') != 'verified' and query.get('plain') != ['1']: return self.reply({},403)
                return self.reply(args.video.read_bytes() if args.video else b'fixture-media-verified', mime='video/mp4')
            if path.path == '/js.js':
                return self.reply("import {Spider,net} from 'catvod';export default class extends Spider {"
                    "async searchContent(q){return JSON.parse((await net.get('"+origin+"/js?wd='+encodeURIComponent(q))).content);}"
                    "async detailContent(ids){return JSON.parse((await net.get('"+origin+"/js?ids='+encodeURIComponent(ids[0]))).content);}}", mime='text/javascript')
            if path.path == '/python.py':
                return self.reply("from base.spider import Spider as Base\nimport requests\nclass Spider(Base):\n"
                    " def init(self, extend): pass\n def searchContent(self, key, quick, pg='1'):\n"
                    f"  return requests.get('{origin}/python',params={{'wd':key}},timeout=5).json()\n"
                    f" def detailContent(self, ids):\n  return requests.get('{origin}/python',params={{'ids':ids[0]}},timeout=5).json()\n", mime='text/plain')
            key = path.path.strip('/')
            if key in ['fast', 'slow', 'error', 'js', 'python', 'disabled']:
                if 'wd' in query:
                    if key == 'slow': time.sleep(4)
                    if key == 'error': return self.reply({'error': 'fixture offline'}, 503)
                    return self.reply({'list': [item(key, query['wd'][0])], 'pagecount': 1})
                return self.reply({'list': [item(key, 'detail' if 'ids' in query else 'home')],
                                   'class': [{'type_id': '1', 'type_name': '分类'}]})
            self.reply({}, 404)
    print(origin+'/config', flush=True)
    ThreadingHTTPServer(('0.0.0.0', args.port), Handler).serve_forever()

if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--port',type=int,default=39648)
    p.add_argument('--jar',type=Path,help='CatVod Fixture JAR built from Fixture.java, for cache/proxy checks')
    p.add_argument('--video',type=Path,help='Optional owned MP4 for checking playback after exiting TVBox')
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
        s.connect(('1.1.1.1',80));host=s.getsockname()[0]
    p.add_argument('--host',default=host);run(p.parse_args())
