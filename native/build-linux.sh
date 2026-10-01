#!/usr/bin/env bash
# Builds libtessera_bridge.so for linux-x86_64 and linux-aarch64 inside Docker
# and stages them where collectNatives expects them (natives/linux/<arch>/).
#
# Ubuntu 20.04 (glibc 2.31) keeps the binaries loadable on older distros;
# aarch64 is cross-compiled, so no emulation is needed. Run from anywhere:
#   ./native/build-linux.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="ubuntu:20.04"

# Git Bash on Windows rewrites /src-style paths; disable that and hand Docker a
# native path.
export MSYS_NO_PATHCONV=1
if command -v cygpath >/dev/null 2>&1; then
    MOUNT="$(cygpath -m "$ROOT")"
else
    MOUNT="$ROOT"
fi

# jni.h is platform-independent, so the host JDK's copy is mounted read-only;
# only the tiny Linux jni_md.h is written below. This avoids installing a JDK
# in the container (not needed just for headers).
JDK_HOME="${JAVA_HOME:?JAVA_HOME must point at a JDK}"
if command -v cygpath >/dev/null 2>&1; then
    JDK_INCLUDE="$(cygpath -m "$JDK_HOME")/include"
else
    JDK_INCLUDE="$JDK_HOME/include"
fi

docker run --rm -v "$MOUNT:/src" -v "$JDK_INCLUDE:/jdk-include:ro" -w /src "$IMAGE" bash -euo pipefail -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq >/dev/null
    apt-get install -y -qq --no-install-recommends \
        cmake make g++ g++-aarch64-linux-gnu >/dev/null
    JNI_ROOT=/tmp/jni-include
    mkdir -p "$JNI_ROOT/linux"
    cp /jdk-include/jni.h "$JNI_ROOT/"
    # Linux jni_md.h (same for x86_64 and aarch64; _LP64 picks jlong width).
    cat > "$JNI_ROOT/linux/jni_md.h" <<"JNIMD"
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_
#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL
typedef int jint;
#ifdef _LP64
typedef long jlong;
#else
typedef long long jlong;
#endif
typedef signed char jbyte;
#endif
JNIMD

    build() {
        local arch="$1"; shift
        local dir="build-native/linux-$arch"
        cmake -S native -B "$dir" -DCMAKE_BUILD_TYPE=Release -DJNI_INCLUDE_ROOT="$JNI_ROOT" "$@" >/dev/null
        cmake --build "$dir" -j"$(nproc)" >/dev/null
        mkdir -p "natives/linux/$arch"
        cp "$dir/libtessera_bridge.so" "natives/linux/$arch/"
    }

    build x86_64
    build aarch64 \
        -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
        -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++
'

ls -l "$ROOT"/natives/linux/*/libtessera_bridge.so
