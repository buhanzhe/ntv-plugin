"""Run the Android integration fixture with a local MP4 you are allowed to stream."""
import argparse
import base64
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def run(args):
    video = args.video.read_bytes()
    origin = f"http://{args.device_host}:{args.port}"
    media = origin + "/fixture.mp4"
    record = {"vod_id": "1", "vod_name": "影片样本", "vod_play_from": "测试线路",
              "vod_play_url": f"第 1 集${media}"}
    home = {"class": [{"type_id": "1", "type_name": "测试分类"}], "list": [record], "pagecount": 1}
    result = {"url": media, "header": {"X-Fixture": "verified"}, "parse": 0}
    js = f"""
import {{Spider, net}} from 'catvod';
import {{marker}} from './marker.js';
export default class extends Spider {{
    async homeContent() {{
        if (marker !== 42) throw Error('relative import');
        const response = await net.get('{origin}/cms.json');
        return JSON.parse(response.content);
    }}
    detailContent() {{ return {json.dumps({"list": [record]}, ensure_ascii=False)}; }}
    playerContent() {{ return {json.dumps(result)}; }}
}}
"""
    python = f"""
from base.spider import Spider as Base
import requests

class Spider(Base):
    def init(self, extend):
        self.id = extend
    def homeContent(self, filter):
        assert self.id == 'fixture'
        return requests.get('{origin}/cms.json', timeout=4).json()
    def detailContent(self, ids):
        assert self.id == 'fixture'
        return {{"list": [{record!r}]}}
    def playerContent(self, flag, id, vipFlags):
        return {result!r}
"""
    ku9 = f"""
def main(item):
    assert item['params']['id'] == '21'
    return {{'url': '{media}', 'headers': {{'X-Fixture': 'verified'}}}}
"""
    sites = [
        {"key": "js", "name": "JS 样本", "type": 3, "api": "./fixture.js"},
        {"key": "python", "name": "Python 样本", "type": 3, "api": "./fixture.py", "ext": "fixture"},
        {"key": "cms", "name": "CMS 样本", "type": 1, "api": "./cms.json"},
    ]
    config = {"sites": sites}
    if args.jar:
        config["spider"] = "./fixture.jar"
        sites += [{"key": name, "name": f"{name} 代理样本", "type": 3, "api": "csp_Fixture",
                   "ext": media} for name in ("Quark", "UC")]
    wrapped = b"fixture image prefix\x00AA11BB22**" + base64.b64encode(json.dumps(config).encode())

    class Handler(SimpleHTTPRequestHandler):
        def do_GET(self):
            target = urlsplit(self.path)
            path = target.path
            if path == "/config":
                return self.reply(wrapped, "image/x-ms-bmp")
            if path == "/cms.json":
                query = parse_qs(target.query)
                if "ids" in query:
                    item = dict(record, vod_play_url=f"第 1 集${media}?cms=1")
                    return self.reply(json.dumps({"list": [item]}).encode())
                return self.reply(json.dumps(home).encode())
            if path in ("/fixture.js", "/marker.js", "/fixture.py", "/ku9.py"):
                data = {"/fixture.js": js, "/marker.js": "export const marker=42;",
                        "/fixture.py": python, "/ku9.py": ku9}[path]
                return self.reply(data.encode(), "text/plain; charset=utf-8")
            if path == "/fixture.jar" and args.jar:
                return self.reply(args.jar.read_bytes(), "application/java-archive")
            if path == "/fixture.mp4":
                if target.query != "cms=1" and self.headers.get("X-Fixture") != "verified":
                    return self.send_error(403)
                start, end = 0, len(video) - 1
                range_header = self.headers.get("Range", "")
                if range_header.startswith("bytes="):
                    start_text, end_text = range_header[6:].split("-", 1)
                    start = int(start_text or 0)
                    end = min(end, int(end_text)) if end_text else end
                if start > end:
                    return self.send_error(416)
                headers = {"Accept-Ranges": "bytes"}
                if range_header:
                    headers["Content-Range"] = f"bytes {start}-{end}/{len(video)}"
                return self.reply(video[start:end + 1], "video/mp4", 206 if range_header else 200, headers)
            self.send_error(404)

        def reply(self, body, mime="application/json", code=200, headers=None):
            self.send_response(code)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(body)

    with ThreadingHTTPServer(("127.0.0.1", args.port), Handler) as server:
        print(origin + "/config", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=39635)
    parser.add_argument("--device-host", default="127.0.0.1", help="Use 10.0.2.2 from the Android emulator")
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--jar", type=Path)
    run(parser.parse_args())
