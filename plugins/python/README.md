# nTv Python 运行时

在 nTv 管理网页打开「插件管理 → 在线插件」，选择 Python。电视按系统版本和 nTv 的进程架构自动选择安装包。也可直接上传对应的 JAR。运行时安装在电视上，启动 Python 后更新 JAR 需要重启 nTv。

| 安装包 | nTv 架构 | 最低系统 | Python |
| --- | --- | --- | --- |
| `python-arm32.jar` | ARM32（armeabi-v7a） | Android 4.0 / API 14 | CPython 3.11.17 + OpenSSL 3.5.9 |
| `python-arm64.jar` | ARM64（arm64-v8a） | Android 7.0 / API 24 | CPython 3.14.8 |

ARM32 安装包已在 Android 4.0.2 API 14 的 ARMv7 模拟器验证。测试包含在设备上运行 Python、使用证书校验的 HTTPS、执行 TVBox Python 爬虫和 Ku9 `.py`。

TVBox 通过同一运行时执行 type 3 `.py` 爬虫，支持 JSON 配置和 `key|name|type: 3|api: 地址.py` 文本列表。运行时提供 `base.spider`、`requests`、`beautifulsoup4`、PyCryptodome（`Crypto`）和标准库。

Ku9 可以直接使用在线 `.py`，或在「插件管理 → Ku9 Python 脚本」上传文件后使用 `http://A/文件名.py?id=1`。脚本入口是 `main(item)`。用 `item["url"]` 读取原频道地址、`item["params"]` 读取查询参数、`item["name"]` 读取频道名。返回 URL 字符串或 Ku9 结果字典，例如：

```python
def main(item):
    return {"url": "https://media.example.org/" + item["params"]["id"] + ".m3u8"}
```

## 构建

打包使用 JDK 17、Android SDK 34 和 Build Tools 35.0.0。ARM32 的本地库用 Linux 或 WSL 中的 Android NDK r17c 构建，链接 API 14 库。HTTPS、哈希、socket、JSON 和 zlib 等常用模块静态编入，适配旧版 Android 的动态链接器。

```sh
bash plugins/python/build-arm32.sh /path/to/android-ndk-r17c /path/to/arm32-runtime
python plugins/build-jar.py python --abi armeabi-v7a --runtime-dir /path/to/arm32-runtime
```

ARM64 使用 Android NDK 27.2 和官方 Android 嵌入式包。

```powershell
python plugins/build-jar.py python --sdk D:/android/sdk --java-home C:/path/to/jdk17 --ndk D:/android/sdk/ndk/27.2.12479018
```

构建脚本校验上游源码或发行包的 SHA-256。CPython 和 ARM32 所用 OpenSSL 的许可随标准库打包，Python 依赖的许可随发行元数据打包。ARM32 运行时面向 Python 和常用纯 Python 爬虫库，桌面系统的本地扩展包需要另行移植。 [CPython 3.11.17](https://www.python.org/downloads/release/python-31117/) · [CPython 3.14.8](https://www.python.org/downloads/release/python-3148/) · [OpenSSL](https://openssl-library.org/source/)
