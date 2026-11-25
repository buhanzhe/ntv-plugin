# nTv 插件

插件 JAR 和安装清单见 [Release](https://github.com/buhanzhe/ntv-plugin/releases/latest)。各包的系统要求、架构和 SHA-256 见 [plugins.json](https://github.com/buhanzhe/ntv-plugin/releases/latest/download/plugins.json)。

[文件浏览](plugins/file-browser/README.md) · [TVBox](plugins/tvbox/README.md) · [BL](plugins/bl/README.md) · [Python](plugins/python/README.md) · [TVBus](plugins/tvbus/README.md)

TVBox 使用自己的配置地址，通过 JAR 地址的 `url` 参数传入。

发布打包使用 `python scripts/prepare_release.py v1.0.5 --output release`，输出目录须为空。
