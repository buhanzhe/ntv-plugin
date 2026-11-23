import { req, md5X, joinUrl } from "catvod";

const bridge = globalThis.NtvCjsBridge;
const item = (scope, name) => JSON.stringify([String(scope), String(name)]);
const print = (...values) => bridge.log(values.join(" "));

// Legacy drpy modules use synchronous globals before calling their exported spider methods.
Object.assign(globalThis, {
  req,
  joinUrl,
  md5: md5X,
  log: print,
  print,
  base64Encode: value => bridge.encodeBase64(String(value)),
  base64Decode: value => bridge.decodeBase64(String(value)),
  local: {
    get(scope, name) {
      const value = bridge.getCache(item(scope, name));
      return value == null ? "" : JSON.parse(value) ?? "";
    },
    set(scope, name, value) {
      bridge.setCache(item(scope, name), JSON.stringify(value ?? null));
    },
    delete(scope, name) {
      bridge.setCache(item(scope, name), "null");
    }
  }
});
