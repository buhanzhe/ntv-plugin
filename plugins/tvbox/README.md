# nTv TVBox 插件

TVBox 使用全幅内容背景、液态玻璃侧栏、横向推荐卡片与竖版海报墙；沿用 BL 的原生布局工具和遥控器键盘。搜索位于左上角，不显示电视直播导航或 TVBox 角标。左上角不显示品牌标识，页面保留实际内容标题。电视端通过左侧导航切换站点、查看推荐和分类、搜索影片。选中站点即加载该站点的爬虫。电影详情页采用上方横幅、下方线路与选集，音乐详情保留完整专辑封面。片名采用宿主随附的文鼎 PL 简报宋，集数、数字与按钮使用无衬线字体。按播放线路切换选集。普通点播交给 nTv 播放器，标准 DASH MPD 使用插件内的 ExoPlayer 合并音视频。两种播放路径均复用 nTv 网页电视的原有播放控件。

v7 的搜索页默认「多站搜索」，也可以选择「当前站点」。聚搜遵循配置的搜索开关，边完成边显示结果，并标注来源；点击同名或相同 ID 的结果仍进入对应站点。旧设备同时查询 2 站，Android 5.0+ 同时查询 3 站；每站显示前 12 条、合计最多 160 条。可停止并保留结果、重试失败站点，总等待上限 60 秒。JS、Python、HTTP 和 Java 站源使用独立搜索会话；第三方 Java 爬虫自建的网络请求仍受其自身实现限制。

搜索使用九键遥控器键盘，同一按键在 900 毫秒内连按可切换该组字母；支持数字、空格、删除和清空，也可以使用系统输入法。配置源仅从 `tvbox.jar?url=...` 参数读取；已移除配置导航、配置编辑与保存地址列表，也不读取旧的保存地址。未传入 url 时显示使用提示。站点自身的网盘授权入口继续保留。

同一配置下缓存最近 8 个站点首页，2 分钟内再次进入直接复用，过期后重新请求。首页右上角不再显示刷新按钮。切换配置、网盘授权更新和站点操作会清理相应缓存。Java 爬虫包按内容哈希保存并校验，未固定 MD5 的地址缓存 10 分钟，固定 MD5 的包继续校验后复用；跨站搜索通常省略首页请求。易听 Music/MusicGuard 缺失搜索接口时，单独初始化其原有会话，使用站点的公开搜索入口，保留歌曲详情和原播放器 ID。旧系统播放器返回空内容时可回退到该站公开网页播放器；遇到验证要求会明确报错。

成功的分类页与详情也缓存 2 分钟，最多 24 项、合计约 100 万字符；筛选和页码独立，空结果、错误与播放解析结果不缓存。授权、配置和站点操作更新会清空。缓存页直接显示，播放前重新绑定正确站点。当前配置全部 50 个条目的初始化、首页、推荐与首分类耗时记录见 [VALIDATION-PERFORMANCE.md](VALIDATION-PERFORMANCE.md)。

配合 Bro 1.8.3 新构建（versionCode 12），TVBox 在浏览和点播期间屏蔽宿主左侧频道入口，避免误触切台；按返回键可返回选集或退出。此策略通过 JAR 元数据 `blockHostChannelClick` 声明，离开 TVBox 后解除。

首页优先展示站点推荐。没有影片时先显示可用分类与搜索；Java/HTTP 的推荐与首分类在最多两个独立后台会话中探测，先采用有效结果，分类最多检查两项。JavaScript/Python 使用单会话兜底。离开页面会取消探测并忽略迟到回包。首页接口失败时仍可搜索。片单中只有片名和封面的条目会保留，点击后可选择其他站点搜索。支持目录条目、分类筛选、手机推送地址输入和爬虫返回的登录操作条目。常见网盘授权字段更新后提供提示；站点自带登录弹窗的标签需要关闭并重新打开后刷新。Java 站源的初始化使用可加载插件依赖的 Application 上下文，插件随附 Gson。

首页按实际卡片尺寸预留完整推荐行，再分配横幅高度；推荐卡之间和区块之间分别留白，适应短屏幕。电影详情的海报独立居右，线路通过单一选择入口切换。长文件名使用全宽文件行，容量与格式另列，长按查看原始完整文件名；短集名与音乐保留网格，播放标识不变。

播放接口的 `url` 支持单个地址、多清晰度数组及其 JSON 字符串，也接受带 `url` 的数组对象和相对地址。列表按站源提供的顺序使用第一个有效地址。兼容 CatVod 的 `proxy://` 及旧版 `127.0.0.1:-1/proxy`，转换后使用当前站点的本机流式代理。HLS 代理保留格式后缀，由 nTv 代理处理 HTTPS 视频分段。

玻璃播放器需要更新 nTv 宿主构建，宿主版本名为 1.8.3（versionCode 12）。DASH 和统一播放控件需要更新 nTv 宿主，使其提供 `ChannelPlugin.PlayerUiHost`。DASH 最低 Android 4.1（API 16）；其他功能的最低系统版本仍为 Android 4.0。新版宿主支持遥控器左右单击快退/快进 5 秒；长按预览目标进度，松开后跳转，避免连续向解码器发送定位。旧宿主的插件 DASH 路径在松开按键时跳转 5 秒。播放器使用底部玻璃控制条，显示实际片名/集名、分辨率、已播与剩余时间；支持返回选集、可用音轨/字幕和倍速。TextureView 输出在控件出现时使用最长边 192 像素的单帧模糊，SurfaceView 等无法取帧的路径使用半透明渐变；不持续逐帧截图。视频底色为黑色。播放时按返回键先隐藏控件，再按返回键回到选集。暂停或展开倍速选项时保留控件。

片名字体优先使用宿主内置的文鼎 PL 简报宋；缺少时从固定 HTTPS 地址在线下载原版字体，核对 SHA-256 后保存至应用私有目录，随后自动刷新标题。缓存可供离线使用，字体不会重复打包进 JAR。片名不再使用人工加粗。原始授权见 `assets/fonts/NOTICE` 和 `assets/fonts/license/`。

站点采用最多四列的紧凑文字卡片，删除重复的默认图标。操作按钮、选集与焦点轮廓采用按实际高度计算的胶囊圆角；主次按钮使用相同内边距，不再给主按钮强加更大宽度。标题栏和正文保留明确间隔。玻璃仅参考 Tigris 的材质方向，保留背景颜色和轻薄定向边缘，焦点半透明提亮；取消角部贴图亮斑，旧 PNG 仅作设计历史保存。填充直接以抗锯齿圆角绘制，避免 Android 4.4 的硬裁切锯齿，详见 [MATERIAL.md](MATERIAL.md)。图片支持 `@Referer=...@User-Agent=...`、表单和 JSON 请求头后缀，相同图片请求合并，图片队列与爬虫请求分离。

`parse=1` 和配置中的 type 0 网页解析通过限时 WebView 嗅探取得媒体地址，并保留媒体请求的 Cookie、Referer、User-Agent 等请求头。旧版设备会优先读取普通 HTML 的媒体标签；动态网页解析每次最多等待 20 秒。网页需要登录、验证或设备网页内核不支持时，仍可能需要切换线路。

下载 [TVBox JAR](https://github.com/buhanzhe/ntv-plugin/releases/latest/download/tvbox.jar) 后使用自己的配置地址。共用界面组件随 JAR 打包。

## 遥控器

浏览页使用上下左右选择内容，确认打开，返回回到上一级；兼容常见厂商的确认键，页面切换或失焦后恢复焦点。播放时确认或上下键打开控件；控件内上移到进度区，下移到按钮区。进度区左右单击快退/快进 5 秒，长按预览、松开定位；按钮区左右选择，确认执行暂停、选集、音轨/字幕或倍速。IJK、系统播放器与插件 DASH 使用相同的操作方式。

## 频道地址

已安装的本地 JAR 可以写进 M3U 频道列表。BL 使用 `ntv-plugin://local/bl.jar`。TVBox 的配置放在 `url` 参数，带查询参数的配置地址需要整体进行 URL 编码。

```m3u
#EXTM3U
#EXTINF:-1 group-title="点播",TVBox
ntv-plugin://local/tvbox.jar?url=https%3A%2F%2Fexample.org%2Fconfig.json
#EXTINF:-1 group-title="点播",BL
ntv-plugin://local/bl.jar
```

也可以使用 HTTPS JAR，并在频道地址同时传入 `sha256` 和 `url`。发布包的 SHA-256 在 [plugins.json](https://github.com/buhanzhe/ntv-plugin/releases/latest/download/plugins.json) 中。

```text
https://example.org/tvbox.jar?sha256=<SHA-256>&url=<编码后的配置地址>
```

音乐详情使用完整方形封面，宽屏分为资料和歌曲两栏，窄屏上下排列；歌名较长时降低列数并保留两行文字，上方显示当前完整标题。重复的歌手前缀集中显示，歌曲与播放 ID 保持完整。

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

图片请求头回归：`python plugins/tvbox/tests/run_images.py` 检查多个 `@` 请求头、表单与编码 JSON、加号保留和换行注入拒绝。

配置解码回归：`python plugins/tvbox/tests/run_config.py` 检查明文、Base64 包装、AES 单仓和多仓配置及错误包头。

浏览数据回归：`python plugins/tvbox/tests/run_browse.py` 检查文件显示、缓存副本、容量边界、过期和失效。`tests/BrowseFixture.java` 提供原生设备联调所用的慢推荐、慢分类、首页依赖、长文件名和 24 线路样本。

编译 `tests/Fixture.java` 时，以 Android 平台 JAR 与 `.codex-tmp/tvbox/build/classes` 为 classpath，通过 D8 将生成的 `Fixture.class` 打包成 DEX JAR。启动测试源时加 `--jar PATH`，可验证模拟的夸克与 UC 代理。将 TVBox 和 Python 安装到测试 APK 后，运行 `xiao.bu.tv.TvBoxProtocolInstrumentation`，可检查站源、ES module、Python 和媒体 Range 请求。

完整界面验收项目见 [DESIGN.md](DESIGN.md)。
