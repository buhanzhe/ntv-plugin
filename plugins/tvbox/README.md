# nTv TVBox 插件

TVBox 沿用 BL 的频道 JAR 接口，点播交给 nTv 播放器。安装支持插件管理的 nTv APK 后，在管理网页的「插件管理」上传 `dist/tvbox.jar`，输入配置地址并点击「打开 TVBox」。电视端可以用遥控器切换站点、浏览分类、搜索、查看详情和选集。播放时按返回键回到选集。

## 频道地址

已安装的本地 JAR 可以写进 M3U 频道列表。BL 使用 `ntv-plugin://local/bl.jar`。TVBox 的配置放在 `url` 参数，带查询参数的配置地址需要整体进行 URL 编码。

```m3u
#EXTM3U
#EXTINF:-1 group-title="点播",TVBox
ntv-plugin://local/tvbox.jar?url=http%3A%2F%2Fwww.xn--sss604efuw.cc%2Ftv
#EXTINF:-1 group-title="点播",BL
ntv-plugin://local/bl.jar
```

也可以发布 JAR 到 HTTPS，然后在频道 URL 同时传入 `sha256` 和 `url`。SHA-256 取自同一版 JAR 随附的 `.sha256` 文件。

```text
https://example.org/tvbox.jar?sha256=<SHA-256>&url=<编码后的配置地址>
```

## 兼容范围

| 配置 | 支持范围 |
| --- | --- |
| TVBox 配置 | 单仓 `sites`、多仓 `urls`、相对资源 URL、FongMi 的 Base64 包装和 AES 配置格式 |
| HTTP 站源 | type 0 XML、type 1 JSON、type 4 HTTP，支持 type 1 JSON 解析接口 |
| CatVod Java | type 3 `csp_*`，从全局 `spider` 或站点 `jar` 加载 DEX JAR，支持 `;md5;`。兼容常用的 CatVod API 和本机流式代理 |
| 夸克 / UC | 使用配置中提供的兼容爬虫及其登录配置。支持爬虫返回的媒体请求头和 CatVod 本机代理 Range 传输 |
| JavaScript | type 3 `.js`，支持 ES module 导入、相对模块及基础 `catvod` API |
| Python | type 3 `.py`，通过独立的 Python 插件运行 |

TVBox JAR 的最低版本是 Android 4.0（API 14）。在旧版 ARM 设备上使用 Python 站源时安装 `python-arm32.jar`。第三方站点的 JAR 可能额外依赖特定版本的 FongMi 宿主接口、原生库或登录流程。JS 和 Python 兼容基础爬虫协议，站点的专有模块、浏览器嗅探解析和依赖需要逐站核验。

源协议和扩展格式参考 [FongMi TV 的爬虫文档](https://github.com/FongMi/TV/blob/5bf18d6ae8b0ccd9b753ef98f932838de2eb7254/website/app/spider/page.tsx)。

## 构建

先构建宿主使 Gradle 下载 OkHttp 和 Okio，再用 JDK 17、Android SDK 34 和 Build Tools 35.0.0 生成 JAR。

```powershell
python plugins/build-jar.py tvbox --sdk D:/android/sdk --java-home C:/path/to/jdk17
```

设备联调可用 `tests/fixture.py --video PATH` 运行本机测试源，通过 `adb reverse tcp:39635 tcp:39635` 后，在管理网页打开 `http://127.0.0.1:39635/config`。测试视频使用本地 MP4。

编译 `tests/Fixture.java` 时，以 Android 平台 JAR 与 `.codex-tmp/tvbox/build/classes` 为 classpath，通过 D8 将生成的 `Fixture.class` 打包成 DEX JAR。启动测试源时加 `--jar PATH`，可验证模拟的夸克与 UC 代理。将 TVBox 和 Python 安装到测试 APK 后，运行 `xiao.bu.tv.TvBoxProtocolInstrumentation`，可检查站源、ES module、Python 和媒体 Range 请求。
