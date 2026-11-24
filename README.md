# nTv 插件

此仓库维护 nTv 的频道 JAR、Python 运行时、Ku9 脚本和 TVBus 打包工具。宿主从 GitHub Release 获取清单，并按设备架构和 Android 版本选择插件。

- 在线清单和产物 · [最新 Release](https://github.com/buhanzhe/ntv-plugin/releases/latest) · [plugins.json](https://github.com/buhanzhe/ntv-plugin/releases/latest/download/plugins.json)
- 频道 · [文件浏览](plugins/file-browser/README.md) · [TVBox](plugins/tvbox/README.md) · [BL](plugins/bl/README.md)
- 一键导入全部频道插件 · [all.m3u 在线订阅](https://raw.githubusercontent.com/buhanzhe/ntv-plugin/main/all.m3u)
- 运行时 · [Python 和 Ku9](plugins/python/README.md)
- 外部引擎 · [TVBus 打包](plugins/tvbus/README.md)

TVBox v7 增加多站搜索、站点与爬虫包缓存，并修复易听歌手搜索。屏蔽 TVBox 左侧宿主频道点击需要 Bro 1.8.4 或更新宿主；DASH 播放和统一控件也需要支持相应接口的 nTv。宿主请在 [NativeWasmTv](https://github.com/buhanzhe/NativeWasmTv) 项目构建或获取；本仓库 Release 仅发布插件、订阅和清单。[设备验证](plugins/tvbox/VALIDATION.md) 列出本轮回归及历史逐站结果。

## 发布

`all.m3u` 汇总文件浏览、TVBox、BL 三个频道 JAR。每项包含实际 SHA-256，并固定到对应的仓库提交。Python、Ku9、TVBus 通过插件管理安装。

更新仓库订阅时，先提交 JAR 产物，再运行 `python scripts/prepare_release.py --repository-ref 完整产物提交SHA --output all.m3u` 并提交生成的文件。发布打包也会生成合并的 `all.m3u`，并将其纳入 `SHA256SUMS`。

分别构建需要更新的插件，将 JAR 和 SHA-256 放入相应的 `dist/`。构建脚本使用 `host-api/src` 里的宿主接口快照。更新接口时，与 [NativeWasmTv](https://github.com/buhanzhe/NativeWasmTv) 中的定义一起更新。

```sh
python scripts/prepare_release.py v1.0.0 --output release
```

输出目录必须为空，避免混入旧产物。将生成的文件上传到同名 GitHub Release，先上传完整产物及清单，再公开发布。清单 `schema: 1` 以 `id` 标识插件，通过 `kind` 标识安装入口，每个 `artifacts` 项记录发布 URL、版本、大小、SHA-256、最低 API 和 ABI。空 `abis` 表示 Java 或脚本包与 CPU 架构无关。发布附件不包含宿主 APK、映射或构建包。

TVBus 清单项支持上传自己的 JAR。提供经授权的 TVBus 引擎后可生成 `plugins/tvbus/dist/tvbus.jar`，发布脚本会自动把它加入清单。

各插件的第三方许可见对应目录的 LICENSE 和 NOTICE。源代码来自 NativeWasmTv 的插件目录，现有历史版本仍可在原仓库的历史提交中查阅。
