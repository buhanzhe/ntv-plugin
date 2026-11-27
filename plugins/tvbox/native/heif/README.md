# TVBox HEIC decoder

TVBox uses the Android image decoder first, then uses its bundled HEIC decoder when needed. The ARMv7 build for Android 4.0.4 through 4.3 runs on the artwork worker without extra codec threads. Its output is capped at 420 pixels on the long edge. Opaque covers use RGB_565 and transparent covers retain ARGB_8888.

The JAR bundles [libheif 1.23.7](https://github.com/strukturag/libheif/tree/v1.23.7) and [libde265 1.1.3](https://github.com/strukturag/libde265/tree/v1.1.3). Their license texts are in `assets/heif/`. The corresponding upstream sources and pinned hashes are listed in `build.py`. The JNI wrapper and build script allow rebuilding or relinking against modified copies of both libraries.

```sh
python plugins/tvbox/native/heif/build.py --ndk /path/to/android-ndk-r25c --ics-ndk /path/to/android-ndk-r17c
python plugins/build-jar.py tvbox --sdk /path/to/android-sdk
```

Use `--cmake` to specify CMake when it is outside PATH. The r17c system libraries resolve the ARMv7 decoder imports against API 15. The other decoder assets cover armeabi-v7a and x86 from API 19 and arm64-v8a from API 21.
