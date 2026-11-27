"""Build the Android HEIC cover decoder from pinned upstream sources."""

import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from urllib.request import urlretrieve


HERE = Path(__file__).resolve().parent
ASSETS = HERE.parents[1] / "assets/heif"
SOURCES = {
    "libde265": ("1.1.3", "189baa08fd6d2dd34db099de411bd6b2e6bd5eb88e81236a3d0fa3826b9715c4"),
    "libheif": ("1.23.7", "90e389caad285ca4946461d56d1c115e56bcbb5141d9038050282af91064fbc7"),
}
API = {"armeabi-v7a": 19, "arm64-v8a": 21, "x86": 19}


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def source(directory, project):
    version, digest = SOURCES[project]
    archive = directory / f"{project}-{version}.tar.gz"
    if not archive.is_file():
        urlretrieve(f"https://codeload.github.com/strukturag/{project}/tar.gz/refs/tags/v{version}", archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        raise ValueError(f"Unexpected SHA-256 for {archive.name}")
    checkout = directory / f"{project}-{version}"
    if not checkout.is_dir():
        with tarfile.open(archive) as bundle:
            bundle.extractall(directory, filter="data")
    return checkout


def link_ics(compiler, llvm, prefix, old_ndk):
    system = old_ndk / "platforms/android-15/arch-arm/usr/lib"
    runtime = llvm.parent / "sysroot/usr/lib/arm-linux-androideabi"
    resource = subprocess.check_output([str(compiler), "-print-resource-dir"], text=True).strip()
    clang = Path(resource) / "lib/linux"
    target = ASSETS / "armeabi-v7a-ics/libntv_box_heif.so"
    target.parent.mkdir(parents=True, exist_ok=True)
    wrapped = ("__memset_chk", "__strcat_chk", "__strlen_chk",
               "__vsnprintf_chk", "__vsprintf_chk", "posix_memalign", "de265_start_worker_threads")
    # Compile C++ with the current toolchain while resolving all imports against API 15.
    run(compiler, "--target=armv7a-linux-androideabi19", "-std=c++20", "-Os", "-fPIC", "-shared",
        "-DNTV_HEIF_ICS", "-fvisibility=hidden",
        "-nostdlib", "-Wl,--no-undefined,--hash-style=sysv,--exclude-libs,ALL,--gc-sections,-s",
        *(f"-Wl,--wrap={symbol}" for symbol in wrapped), "-I", prefix / "include",
        system / "crtbegin_so.o", HERE / "decode.cpp", HERE / "ics_compat.cpp",
        prefix / "lib/libheif.a", prefix / "lib/libde265.a",
        "-Wl,--start-group", runtime / "libc++_static.a", runtime / "libc++abi.a",
        runtime / "libandroid_support.a",
        clang / "libclang_rt.builtins-arm-android.a", clang / "arm/libunwind.a", "-Wl,--end-group",
        *(system / f"{name}.so" for name in ("libjnigraphics", "libm", "libdl", "libc")),
        system / "crtend_so.o", "-o", target)


def build(args, sources, abi):
    work = args.work / abi
    prefix = work / "prefix"
    tools = args.cmake.parent
    ninja = next(tools.glob("ninja*"), None) or shutil.which("ninja")
    common = [
        "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
        f"-DCMAKE_TOOLCHAIN_FILE={args.ndk / 'build/cmake/android.toolchain.cmake'}",
        f"-DANDROID_ABI={abi}", f"-DANDROID_PLATFORM=android-{API[abi]}",
        "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=MinSizeRel",
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DBUILD_SHARED_LIBS=OFF",
        f"-DCMAKE_INSTALL_PREFIX={prefix}", "-DCMAKE_INSTALL_LIBDIR=lib",
    ]
    de265 = work / "de265"
    run(args.cmake, "-S", sources["libde265"], "-B", de265, *common,
        "-DENABLE_SDL=OFF", "-DENABLE_AVX2=OFF", "-DENABLE_AVX512=OFF")
    run(args.cmake, "--build", de265, "--target", "install", "--parallel", args.jobs)
    heif = work / "heif"
    run(args.cmake, "-S", sources["libheif"], "-B", heif, *common,
        "-DENABLE_PLUGIN_LOADING=OFF", "-DWITH_LIBDE265=ON", "-DWITH_LIBDE265_PLUGIN=OFF",
        f"-DLIBDE265_INCLUDE_DIR={prefix / 'include'}", f"-DLIBDE265_LIBRARY={prefix / 'lib/libde265.a'}",
        "-DWITH_X265=OFF", "-DWITH_X264=OFF", "-DWITH_OpenH264_DECODER=OFF",
        "-DWITH_AOM_DECODER=OFF", "-DWITH_AOM_ENCODER=OFF", "-DWITH_LIBSHARPYUV=OFF",
        "-DWITH_EXAMPLES=OFF", "-DWITH_GDK_PIXBUF=OFF", "-DBUILD_TESTING=OFF",
        "-DBUILD_DOCUMENTATION=OFF", "-DENABLE_PARALLEL_TILE_DECODING=OFF")
    run(args.cmake, "--build", heif, "--target", "install", "--parallel", args.jobs)
    target = ASSETS / abi / "libntv_box_heif.so"
    target.parent.mkdir(parents=True, exist_ok=True)
    llvm = next((args.ndk / "toolchains/llvm/prebuilt").glob("*/bin"))
    compiler = next(llvm.glob("clang++*"))
    triple = {"armeabi-v7a": "armv7a-linux-androideabi", "arm64-v8a": "aarch64-linux-android",
              "x86": "i686-linux-android"}[abi]
    run(compiler, f"--target={triple}{API[abi]}", "-std=c++20", "-Os", "-fPIC", "-shared",
        "-static-libstdc++", "-Wl,--exclude-libs,ALL", "-Wl,--gc-sections", "-Wl,-s",
        "-Wl,-z,max-page-size=16384", "-I", prefix / "include", HERE / "decode.cpp",
        prefix / "lib/libheif.a", prefix / "lib/libde265.a", "-ljnigraphics", "-o", target)
    if abi == "armeabi-v7a" and args.ics_ndk:
        link_ics(compiler, llvm, prefix, args.ics_ndk)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--ics-ndk", type=Path, help="NDK r17c for linking the Android 4.0.4 ARMv7 variant")
    parser.add_argument("--cmake", default=Path(shutil.which("cmake") or "cmake"), type=Path)
    parser.add_argument("--work", default=Path(tempfile.gettempdir()) / "ntv-heif-build", type=Path)
    parser.add_argument("--abi", choices=API, action="append")
    parser.add_argument("--jobs", type=int, default=6)
    options = parser.parse_args()
    options.work.mkdir(parents=True, exist_ok=True)
    checkouts = {project: source(options.work, project) for project in SOURCES}
    for architecture in options.abi or API:
        build(options, checkouts, architecture)
