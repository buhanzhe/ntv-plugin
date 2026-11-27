import inspect
import json
import os
import socket
import sys
import threading
import time
import types
from pathlib import Path

os.environ.setdefault("SSL_CERT_FILE", str(Path(__file__).with_name("cacert.pem")))
socket.setdefaulttimeout(12)

_sessions = {}
_lock = threading.RLock()
_actions = {
    "homeContent", "homeVideoContent", "categoryContent", "detailContent",
    "searchContent", "playerContent", "liveContent", "action"
}


def load(code, origin):
    module = types.ModuleType("ntv_user_spider")
    module.__file__ = origin
    exec(compile(code, origin, "exec"), module.__dict__)
    return module


def invoke_compatible(function, arguments):
    signature = inspect.signature(function)
    try:
        signature.bind(*arguments)
    except TypeError:
        signature.bind(*arguments[:-1])
        return function(*arguments[:-1])
    return function(*arguments)


def run(request):
    action = request["action"]
    if action == "ku9":
        module = load(request["source"], request.get("origin", "ku9.py"))
        result = module.main(request["item"])
        if isinstance(result, str):
            if result.startswith("#EXTM3U"):
                return {"m3u8": result}
            if result.lstrip().startswith("{"):
                return json.loads(result)
            return {"url": result}
        return result

    session = request["session"]
    if action == "init":
        if len(_sessions) >= 32:
            raise ValueError("Too many active Python spiders")
        spider = load(request["source"], request.get("origin", "spider.py")).Spider()
        spider.siteKey = request.get("siteKey", session)
        spider.init(request.get("extend", ""))
        _sessions[session] = spider
        return {}
    if action == "close":
        spider = _sessions.pop(session, None)
        if spider is not None and hasattr(spider, "destroy"):
            spider.destroy()
        return {}
    if action not in _actions:
        raise ValueError("Invalid Python spider operation")
    spider = _sessions[session]
    function = getattr(spider, action)
    args = request.get("args", [])
    result = invoke_compatible(function, args) if action == "searchContent" else function(*args)
    return json.loads(result) if isinstance(result, str) else result


def dispatch(data):
    with _lock:
        deadline = time.monotonic() + (45 if sys.maxsize == 2**31 - 1 else 18)
        previous = sys.gettrace()
        checkpoints = 0

        def watchdog(frame, event, arg):
            nonlocal checkpoints
            checkpoints += 1
            if checkpoints % 128 == 0 and time.monotonic() > deadline:
                raise TimeoutError("Python spider timed out")
            return watchdog

        try:
            sys.settrace(watchdog)
            result = run(json.loads(data))
            if not isinstance(result, dict):
                raise TypeError("Python spider must return a dict")
            return json.dumps({"result": result}, ensure_ascii=False, allow_nan=False).encode()
        except ModuleNotFoundError as error:
            name = error.name or ""
            detail = "（" + name + "）" if name and all(part.isidentifier() for part in name.split(".")) else ""
            return json.dumps({"error": "Python 爬虫缺少依赖" + detail}, ensure_ascii=False).encode()
        except Exception as error:
            return json.dumps({"error": "Python: " + type(error).__name__}, ensure_ascii=False).encode()
        finally:
            sys.settrace(previous)
