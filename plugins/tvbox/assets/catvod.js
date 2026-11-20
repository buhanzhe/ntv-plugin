import host from "catvod:host";

const bridge = globalThis.NtvCjsBridge;
export function req(url, options = {}) {
  const request = {...options};
  if (request.data && typeof request.data === "object") {
    request.body = Object.keys(request.data).map(key =>
      encodeURIComponent(key) + "=" + encodeURIComponent(request.data[key])).join("&");
  }
  return JSON.parse(bridge.request(String(url), JSON.stringify(request)));
}
export const net = {
  request: req,
  get: (url, options) => req(url, options),
  post: (url, options) => req(url, {...options, method: "POST"})
};
export const local = {
  get: (key, fallback = null) => {
    const raw = bridge.getCache(String(key));
    return raw == null ? fallback : JSON.parse(raw);
  },
  set: (key, value) => bridge.setCache(String(key), JSON.stringify(value)),
  remove: (key) => bridge.setCache(String(key), "null")
};
export const joinUrl = (base, path) => bridge.parseUri(String(base), String(path));
export const md5X = (value) => bridge.md5(String(value));
export const sha256X = (value) => bridge.digest("SHA-256", String(value));
export function page(items, number, limit, total = items.length) {
  const pg = Math.max(1, Number(number) || 1);
  return {list: items, page: pg, pagecount: Math.max(1, Math.ceil(total / limit)), limit, total};
}
export class Spider {
  constructor() { this.siteKey = host.siteKey; this.net = net; this.local = local; }
  init(extend) { this.extend = extend; }
  homeContent() { return {class: []}; }
  homeVideoContent() { return {list: []}; }
  categoryContent() { return {list: []}; }
  detailContent() { return {list: []}; }
  searchContent() { return {list: []}; }
  playerContent(flag, id) { return {url: id, parse: 0}; }
  log(message) { bridge.log(String(message)); }
  page(...args) { return page(...args); }
}
globalThis.req = req;
globalThis.md5 = md5X;
globalThis.console = {log: (...args) => bridge.log(args.join(" ")), error: (...args) => bridge.log(args.join(" "))};
