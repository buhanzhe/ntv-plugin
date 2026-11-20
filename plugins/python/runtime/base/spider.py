"""Baseline CatVod Python Spider contract."""

import json
import re

import requests


class Spider:
    def init(self, extend=""):
        self.extend = extend

    def homeContent(self, filter):
        return {"class": []}

    def homeVideoContent(self):
        return {"list": []}

    def categoryContent(self, tid, pg, filter, extend):
        return {"list": []}

    def detailContent(self, ids):
        return {"list": []}

    def searchContent(self, key, quick, pg="1"):
        return {"list": []}

    def playerContent(self, flag, id, vipFlags):
        return {"url": id, "parse": 0}

    def destroy(self):
        pass

    def fetch(self, url, headers=None, params=None, cookies=None, timeout=12, **kwargs):
        return requests.get(url, headers=headers, params=params, cookies=cookies, timeout=timeout, **kwargs)

    def post(self, url, data=None, headers=None, cookies=None, timeout=12, **kwargs):
        return requests.post(url, data=data, headers=headers, cookies=cookies, timeout=timeout, **kwargs)

    def regStr(self, source, pattern, group=1):
        match = re.search(pattern, source)
        return match.group(group) if match else ""

    def log(self, message):
        print(json.dumps(message, ensure_ascii=False) if isinstance(message, (dict, list)) else message)
