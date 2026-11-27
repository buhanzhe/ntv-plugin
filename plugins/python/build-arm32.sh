#!/usr/bin/env bash
# Build the embeddable CPython runtime for armeabi-v7a / Android API 14.
set -euo pipefail

if (( $# < 2 || $# > 3 )); then
    echo "Usage: $0 LINUX_NDK_R17C OUTPUT_RUNTIME_DIRECTORY [BUILD_CACHE_DIRECTORY]" >&2
    exit 2
fi

source_dir=$(cd "$(dirname "$0")" && pwd)
ndk=$(realpath "$1")
output=$(realpath -m "$2")
cache=$(realpath -m "${3:-$source_dir/../../.codex-tmp/python/arm32-native}")
jobs=${JOBS:-4}
export LC_ALL=C
mkdir -p "$cache" "$output/lib/python3.11"
cd "$cache"

if ! grep -q 'Pkg.Revision = 17.2.4988734' "$ndk/source.properties"; then
    echo "Android NDK r17c is required for the API 14 link libraries." >&2
    exit 1
fi

fetch() {
    local file=$1 url=$2 sha=$3
    if [[ ! -f $file ]]; then
        curl --fail --location --silent --show-error --retry 2 --max-time 180 "$url" -o "$file.part"
        echo "$sha  $file.part" | sha256sum --check --status
        mv "$file.part" "$file"
    fi
    echo "$sha  $file" | sha256sum --check --status
}

fetch Python-3.11.17.tar.xz https://www.python.org/ftp/python/3.11.17/Python-3.11.17.tar.xz \
    bfb74ad39efae27cda510f134ab408e00f9992c56851cfc0b1cdb5646da11599
fetch openssl-3.5.9.tar.gz https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz \
    603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
fetch libffi-3.4.8.tar.gz https://github.com/libffi/libffi/releases/download/v3.4.8/libffi-3.4.8.tar.gz \
    bc9842a18898bfacb0ed1252c4febcc7e78fa139fd27fdc7a3e30d9d9356119b
[[ -d libffi-3.4.8 ]] || tar -xf libffi-3.4.8.tar.gz
[[ -d Python-3.11.17 ]] || tar -xf Python-3.11.17.tar.xz
[[ -d openssl-3.5.9 ]] || tar -xf openssl-3.5.9.tar.gz

toolchain="$cache/toolchain"
if [[ ! -x $toolchain/bin/arm-linux-androideabi-clang ]]; then
    python3 "$ndk/build/tools/make_standalone_toolchain.py" --api 14 --arch arm --install-dir "$toolchain"
fi

python_src="$cache/Python-3.11.17"
host="$cache/build-host"
mkdir -p "$host"
if [[ ! -x $host/python ]]; then
    (cd "$host" && "$python_src/configure" --without-ensurepip >configure.log 2>&1
     make -j"$jobs" python >build.log 2>&1)
fi

openssl="$cache/openssl-3.5.9"
if [[ ! -f $openssl/libcrypto.a || ! -f $openssl/libssl.a ]]; then
    (cd "$openssl"
     export ANDROID_NDK_ROOT="$toolchain" PATH="$toolchain/bin:$PATH"
     ./Configure android-arm -D__ANDROID_API__=14 -march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp \
         -fPIC no-shared no-tests no-apps no-asm no-module no-async >configure.log 2>&1
     make -j"$jobs" build_libs >build.log 2>&1)
fi
mkdir -p "$openssl/lib"
ln -sfn ../libcrypto.a "$openssl/lib/libcrypto.a"
ln -sfn ../libssl.a "$openssl/lib/libssl.a"

target="$cache/build-arm32"
mkdir -p "$target"
cd "$target"
cc="$toolchain/bin/arm-linux-androideabi-clang"
readelf="$ndk/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-readelf"
flags="-march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp"
link_flags="-Wl,--no-undefined -Wl,--hash-style=sysv"
ffi="$cache/libffi-android"
if [[ ! -f $ffi/lib/libffi.a ]]; then
    mkdir -p "$cache/build-libffi"
    (cd "$cache/build-libffi"
     CC="$cc" AR="$toolchain/bin/arm-linux-androideabi-ar" RANLIB="$toolchain/bin/arm-linux-androideabi-ranlib" \
         CFLAGS="$flags -fPIC -Os -fno-integrated-as" "$cache/libffi-3.4.8/configure" --host=arm-linux-androideabi \
         --disable-shared --enable-static --disable-docs --disable-multi-os-directory --prefix="$ffi" >configure.log 2>&1
     make -j"$jobs" install >build.log 2>&1)
fi
"$cc" $flags -fPIC -fvisibility=hidden -Os -c "$source_dir/api14.c" -o "$target/api14.o"
# Use only target libraries. configure cannot execute its getaddrinfo probe on the build host.
ac_cv_file__dev_ptmx=yes ac_cv_file__dev_ptc=no ac_cv_working_tzset=yes ac_cv_buggy_getaddrinfo=no MODULE_BUILDTYPE=static \
    ac_cv_func_sendfile=no ac_cv_func_sendfile64=no ac_cv_func_truncate=no ac_cv_header_sys_xattr_h=no \
    PKG_CONFIG_LIBDIR="$toolchain/sysroot/usr/lib/pkgconfig" PKG_CONFIG_PATH="" \
    CC="$cc" AR="$toolchain/bin/arm-linux-androideabi-ar" RANLIB="$toolchain/bin/arm-linux-androideabi-ranlib" READELF="$readelf" \
    CFLAGS="$flags -Os -g0" LDFLAGS="$link_flags " LIBS="$target/api14.o -lm" \
    "$python_src/configure" --host=arm-linux-androideabi --build="$("$python_src/config.guess")" \
    --with-build-python="$host/python" --enable-shared --without-ensurepip --disable-test-modules \
    --with-openssl="$openssl" --with-openssl-rpath=no >configure.log 2>&1 || { tail -35 configure.log; exit 1; }

# Early Android linkers cannot reliably load dependencies from an additional directory.
# The grp extension requires group enumeration functions that Android 4.0 lacks.
sed '/^grp /d' Modules/Setup.stdlib > Modules/Setup.local
make -j"$jobs" libpython3.11.so INSTSONAME=libpython3.11.so \
    "CPPFLAGS=-include $source_dir/api14-math.h" \
    "LDFLAGS=$link_flags -Wl,--exclude-libs,libcrypto.a:libssl.a -Wl,-soname,libpython3.11.so" >build.log 2>&1 \
    || { tail -35 build.log; exit 1; }

"$cc" $flags -shared -fPIC -Os $link_flags -I"$python_src/Include" -I"$target" \
    "$source_dir/bridge.c" -L"$target" -lpython3.11 -o "$output/lib/libntvpython.so"
# CPython 3.11 builds _ctypes outside the core library.
ctypes_dir="$python_src/Modules/_ctypes"
dynload="$output/lib/python3.11/lib-dynload"
mkdir -p "$dynload"
"$cc" $flags -shared -fPIC -Os $link_flags -DPy_BUILD_CORE=1 -DPy_BUILD_CORE_MODULE=1 \
    -I"$python_src/Include" -I"$python_src/Include/internal" -I"$target" -I"$ffi/include" \
    "$ctypes_dir"/{_ctypes,callbacks,callproc,cfield,stgdict}.c \
    -L"$target" -lpython3.11 "$ffi/lib/libffi.a" -ldl -lm -o "$dynload/_ctypes.so"
cp libpython3.11.so "$output/lib/libpython3.11.so"
"$toolchain/bin/arm-linux-androideabi-strip" --strip-unneeded \
    "$output/lib/libpython3.11.so" "$output/lib/libntvpython.so"
cp "$python_src/LICENSE" "$output/lib/python3.11/LICENSE.txt"
cp "$openssl/LICENSE.txt" "$output/lib/python3.11/OPENSSL-LICENSE.txt"
cp "$cache/libffi-3.4.8/LICENSE" "$output/lib/python3.11/LIBFFI-LICENSE.txt"

python3 - "$source_dir/../build-jar.py" "$cache" "$output/lib/python3.11" "$cc" "$flags" "$link_flags" <<'PY'
import pathlib
import runpy
import shutil
import sys

build, cache, destination, compiler = map(pathlib.Path, sys.argv[1:5])
crypto = runpy.run_path(str(build))["android_crypto"]
for name, source in crypto(cache, compiler, sys.argv[5].split(), 32, sys.argv[6].split()):
    path = destination / name.removeprefix("runtime/")
    path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, path)
PY

stdlib="$target/stdlib-pyc"
"$host/python" -S - "$python_src/Lib" "$stdlib" <<'PY'
import importlib._bootstrap_external
import marshal
import os
import sys

source, target = sys.argv[1:]
exclude = {"test", "tests", "idlelib", "tkinter", "turtledemo", "ensurepip", "__pycache__"}
magic = importlib._bootstrap_external.MAGIC_NUMBER
for root, folders, names in os.walk(source):
    folders[:] = sorted(folder for folder in folders if folder not in exclude)
    for name in sorted(names):
        if not name.endswith(".py"):
            continue
        path = os.path.join(root, name)
        relative = os.path.relpath(path, source)
        with open(path, "rb") as stream:
            data = stream.read()
        out = os.path.join(target, relative + "c")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "wb") as stream:
            stream.write(magic + b"\0" * 8 + len(data).to_bytes(4, "little"))
            marshal.dump(compile(data, relative, "exec", dont_inherit=True), stream)
PY
python3 - "$stdlib" "$output/lib/python311.zip" <<'PY'
import pathlib
import sys
import zipfile

stdlib, target = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(target, "w") as archive:
    for path in sorted(stdlib.rglob("*.pyc")):
        archive.write(path, path.relative_to(stdlib).as_posix())
PY

echo "Built CPython 3.11.17 / OpenSSL 3.5.9 for Android API 14 at $output"
