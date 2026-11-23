# nTv 插件

此仓库维护 nTv 的频道 JAR、Python 运行时、Ku9 脚本、电台界面和 TVBus 打包工具。宿主从 GitHub Release 获取清单，并按设备架构和 Android 版本选择安装包。

- 在线清单和产物 · [最新 Release](https://github.com/buhanzhe/ntv-plugin/releases/latest) · [plugins.json](https://github.com/buhanzhe/ntv-plugin/releases/latest/download/plugins.json)
- 频道 · [文件浏览](plugins/file-browser/README.md) · [TVBox](plugins/tvbox/README.md) · [BL](plugins/bl/README.md) · [Folia](plugins/folia-full/README.md)
- 一键导入全部频道插件 · [all.m3u 在线订阅](https://raw.githubusercontent.com/buhanzhe/ntv-plugin/main/all.m3u)
- 运行时 · [Python 和 Ku9](plugins/python/README.md)
- 外部引擎 · [TVBus 打包](plugins/tvbus/README.md)

TVBox v6 的 DASH 播放和统一控件需要更新 nTv 宿主。可从同版 Release 下载 ARM32、ARM64 或 x86 APK；[逐站设备验证](plugins/tvbox/VALIDATION.md) 列出已播放和仍待排查的入口。

## 发布

`all.m3u` 汇总所有可通过频道入口打开的 JAR：文件浏览、TVBox、BL、Folia 音乐和 Folia 音频频道。每项包含实际 SHA-256，并固定到对应的仓库提交；Folia 音乐的 Android 7.0+ 要求会在频道名中标明。Python、Ku9、TVBus 运行时及界面插件通过插件管理安装，不作为播放频道塞入 M3U。

更新仓库订阅时，先提交 JAR 产物，再运行 `python scripts/prepare_release.py --repository-ref 完整产物提交SHA --output all.m3u` 并提交生成的文件。发布打包也会生成合并的 `all.m3u`，并将其纳入 `SHA256SUMS`。

分别构建需要更新的插件，将 JAR 和 SHA-256 放入相应的 `dist/`。构建脚本使用 `host-api/src` 里的宿主接口快照。更新接口时，与 [NativeWasmTv](https://github.com/buhanzhe/NativeWasmTv) 中的定义一起更新。

```sh
python scripts/prepare_release.py v1.0.0 --output release
```

将 `release/` 中的文件上传到同名 GitHub Release。先上传完整产物及清单，再公开发布。清单 `schema: 1` 以 `id` 标识插件，通过 `kind` 标识安装入口，每个 `artifacts` 项记录发布 URL、版本、大小、SHA-256、最低 API 和 ABI。空 `abis` 表示 Java 或脚本包与 CPU 架构无关。电台设置继续使用发布中的 `radio-ui.json`。历史电视主题 JAR 保留在 Release，当前 nTv 在偏好设置中切换内置主题。

TVBus 清单项支持上传自己的 JAR。提供经授权的 TVBus 引擎后可生成 `plugins/tvbus/dist/tvbus.jar`，发布脚本会自动把它加入清单。

各插件的第三方许可见对应目录的 LICENSE 和 NOTICE。源代码来自 NativeWasmTv 的插件目录，现有历史版本仍可在原仓库的历史提交中查阅。
