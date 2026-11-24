# nTv TVBox 插件

TVBox 复用 BL 的原生卡片、遥控器键盘和焦点样式。电视端通过左侧导航切换站点、查看推荐和分类、搜索影片。选中站点即加载该站点的爬虫。详情页显示封面和简介，按播放线路切换选集。普通点播交给 nTv 播放器，标准 DASH MPD 使用插件内的 ExoPlayer 合并音视频。两种播放路径均复用 nTv 网页电视的原有播放控件。

v7 的搜索页默认「多站搜索」，也可以选择「当前站点」。聚搜遵循配置的搜索开关，边完成边显示结果，并标注来源；点击同名或相同 ID 的结果仍进入对应站点。旧设备同时查询 2 站，Android 5.0+ 同时查询 3 站；每站显示前 12 条、合计最多 160 条。可停止并保留结果、重试失败站点，总等待上限 60 秒。JS、Python、HTTP 和 Java 站源使用独立搜索会话；第三方 Java 爬虫自建的网络请求仍受其自身实现限制。

同一配置下缓存最近 8 个站点首页，2 分钟内再次进入直接复用，点击「刷新」可重新加载。切换配置、网盘授权更新和站点操作会清理相应缓存。Java 爬虫包按内容哈希保存并校验，未固定 MD5 的地址缓存 10 分钟，固定 MD5 的包继续校验后复用；跨站搜索通常省略首页请求。易听 Music/MusicGuard 缺失搜索接口时，单独初始化其原有会话，使用站点的公开搜索入口，保留歌曲详情和原播放器 ID。旧系统播放器返回空内容时可回退到该站公开网页播放器；遇到验证要求会明确报错。

配合 Bro 1.8.4，TVBox 在浏览和点播期间屏蔽宿主左侧频道入口，避免误触切台；按返回键可返回选集或退出。此策略通过 JAR 元数据 `blockHostChannelClick` 声明，离开 TVBox 后解除。

首页优先展示站点推荐。站点没有返回推荐影片时，在前三个分类中寻找可用的第一页，并显示分类名。首页接口失败时仍可进入分类和搜索。片单中只有片名和封面的条目会保留，点击后可选择其他站点搜索。支持目录条目、分类筛选、手机推送地址输入和爬虫返回的登录操作条目。常见网盘授权字段更新后提供提示；站点自带登录弹窗的标签需要关闭并重新打开后刷新。Java 站源的初始化使用可加载插件依赖的 Application 上下文，插件随附 Gson。

播放接口的 `url` 支持单个地址、多清晰度数组及其 JSON 字符串，也接受带 `url` 的数组对象和相对地址。列表按站源提供的顺序使用第一个有效地址。兼容 CatVod 的 `proxy://` 及旧版 `127.0.0.1:-1/proxy`，转换后使用当前站点的本机流式代理。HLS 代理保留格式后缀，由 nTv 代理处理 HTTPS 视频分段。

DASH 和统一播放控件需要更新 nTv 宿主，使其提供 `ChannelPlugin.PlayerUiHost`。DASH 最低 Android 4.1（API 16）；其他功能的最低系统版本仍为 Android 4.0。播放时按返回键先隐藏控件，再按返回键回到选集。暂停或展开倍速选项时保留控件。

`parse=1` 和配置中的 type 0 网页解析通过限时 WebView 嗅探取得媒体地址，并保留媒体请求的 Cookie、Referer、User-Agent 等请求头。旧版设备会优先读取普通 HTML 的媒体标签；动态网页解析每次最多等待 20 秒。网页需要登录、验证或设备网页内核不支持时，仍可能需要切换线路。

在 nTv 管理网页的「插件管理」上传 `dist/tvbox.jar`，输入配置地址并点击「打开 TVBox」。共用界面组件随 TVBox JAR 一并打包。

也可以导入 [TVBox 在线订阅](https://github.com/buhanzhe/ntv-plugin/releases/latest/download/tvbox.m3u)。

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
| HTTP 站源 | type 0 XML、type 1 JSON、type 4 HTTP，支持 type 1 JSON 解析接口及 type 0 网页解析 |
| CatVod Java | type 3 `csp_*`，从全局 `spider` 或站点 `jar` 加载 DEX JAR，支持 `;md5;`。兼容常用的 CatVod API 和本机流式代理 |
| 夸克 / UC / 百度 | 使用配置中提供的兼容爬虫及其登录配置。支持爬虫返回的媒体请求头和 CatVod 本机代理 Range 传输 |
| JavaScript | type 3 `.js`，支持 ES module、相对模块、基础 `catvod` API，以及旧版 drpy 常用的 Cheerio、CryptoJS、选择器和 URL 解析接口 |
| Python | type 3 `.py`，通过独立的 Python 插件运行 |

TVBox JAR 的最低版本是 Android 4.0（API 14）。在旧版 ARM 设备上使用 Python 站源时安装 `python-arm32.jar`。第三方站点的 JAR 可能额外依赖特定版本的 FongMi 宿主接口、原生库或登录流程。JS 和 Python 兼容基础爬虫协议，站点的专有模块和依赖需要逐站核验。站点接口为空、分享失效、网盘未授权或上游线路不可用时，插件不能保证播放。

虎牙站源返回公开直播间网页时，TVBox 会通过虎牙接口读取服务端提供的直播线路，检查可用的 HLS 或 FLV 后交给 nTv 播放。

源协议和扩展格式参考 [FongMi TV 的爬虫文档](https://github.com/FongMi/TV/blob/5bf18d6ae8b0ccd9b753ef98f932838de2eb7254/website/app/spider/page.tsx)。

逐站设备实测结果见 [VALIDATION.md](VALIDATION.md)。

## 构建

先构建宿主使 Gradle 下载 OkHttp 和 Okio，再用 JDK 17、Android SDK 34 和 Build Tools 35.0.0 生成 JAR。

```powershell
python plugins/build-jar.py tvbox --sdk D:/android/sdk --java-home C:/path/to/jdk17
```

设备联调可用 `tests/fixture.py --video PATH` 运行本机测试源，通过 `adb reverse tcp:39635 tcp:39635` 后，在管理网页打开 `http://127.0.0.1:39635/config`。测试视频使用本地 MP4。

搜索回归：`python plugins/tvbox/tests/run_search.py` 检查并发、取消、失败隔离、爬虫缓存校验、歌曲详情和延迟初始化。`python plugins/tvbox/tests/search_fixture.py --jar PATH` 启动快慢 CMS、失败站、JS、Python 和 Java 代理样本；Android 4.4 不支持 adb reverse，可使用输出的局域网地址。

编译 `tests/Fixture.java` 时，以 Android 平台 JAR 与 `.codex-tmp/tvbox/build/classes` 为 classpath，通过 D8 将生成的 `Fixture.class` 打包成 DEX JAR。启动测试源时加 `--jar PATH`，可验证模拟的夸克与 UC 代理。将 TVBox 和 Python 安装到测试 APK 后，运行 `xiao.bu.tv.TvBoxProtocolInstrumentation`，可检查站源、ES module、Python 和媒体 Range 请求。
