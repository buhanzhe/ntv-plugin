# TVBus 插件

用已经取得授权的 TVBus APK 或原生引擎生成供 nTv 上传的 JAR。引擎目录需要 `armeabi-v7a/libtvcore.so` 和 `arm64-v8a/libtvcore.so`。

```sh
python plugins/tvbus/build.py --engines-dir PATH --output plugins/tvbus/dist/tvbus.jar
```

在 nTv 网页管理的「插件管理」上传，下一次打开 TVBus 频道时使用。此目录提供打包工具和 JNI 接口，Release 会在存在该 JAR 时发布对应安装包。
