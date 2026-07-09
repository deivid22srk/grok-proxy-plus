#!/usr/bin/env bash
#
# Cross-compiles the Go mobile bridge (mobile/) as a c-shared library for each
# Android ABI and drops the resulting libgrokproxy.so files into
# android/app/src/main/jniLibs/<abi>/ so AGP packages them into the APK.
#
# This is full Go (`go build -buildmode=c-shared`), NOT gomobile. The C
# compiler comes from the Android NDK; the NDK's sysroot provides jni.h for the
# cgo preamble in mobile/main.go.
#
# Required env: ANDROID_NDK_HOME (or ANDROID_HOME/ANDROID_SDK_ROOT with an NDK
# installed under <sdk>/ndk/<version>).
set -euo pipefail

cd "$(dirname "$0")/.."

# ---- Locate the NDK -------------------------------------------------------
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  NDK_DIR="$(ls -d "${SDK}/ndk/"* 2>/dev/null | sort -V | tail -1)" || true
  if [ -z "${NDK_DIR:-}" ] || [ ! -d "${NDK_DIR}" ]; then
    echo "::error::No Android NDK found. Set ANDROID_NDK_HOME or install an NDK under ${SDK}/ndk/" >&2
    exit 1
  fi
  export ANDROID_NDK_HOME="${NDK_DIR}"
fi
echo "Using NDK: ${ANDROID_NDK_HOME}"

NDK_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -x "${NDK_BIN}/aarch64-linux-android24-clang" ]; then
  # Fall back to the darwin host if somehow running on macOS.
  NDK_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
if [ ! -x "${NDK_BIN}/aarch64-linux-android24-clang" ]; then
  echo "::error::NDK clang wrappers not found under ${NDK_BIN}" >&2
  exit 1
fi

API="${ANDROID_MIN_API:-24}"
JNILIBS="android/app/src/main/jniLibs"

build() {
  local abi="$1" goarch="$2" goarm="$3" clang_prefix="$4"
  export GOOS=android
  export GOARCH="$goarch"
  export CGO_ENABLED=1
  export CC="${NDK_BIN}/${clang_prefix}${API}-clang"
  export CXX="${CC}++"
  if [ -n "$goarm" ]; then export GOARM="$goarm"; fi

  echo "::group::Build libgrokproxy.so for ${abi} (GOARCH=${goarch} GOARM=${goarm} CC=${CC})"
  mkdir -p "${JNILIBS}/${abi}"
  go build -buildmode=c-shared -trimpath -ldflags="-s -w" \
    -o "${JNILIBS}/${abi}/libgrokproxy.so" ./mobile
  ls -la "${JNILIBS}/${abi}/libgrokproxy.so"
  echo "::endgroup::"
}

# ABI                GOARCH   GOARM  NDK clang prefix
build arm64-v8a      arm64    ""     aarch64-linux-android
build armeabi-v7a    arm      7      armv7a-linux-androideabi
build x86_64         amd64    ""     x86_64-linux-android

echo
echo "All native libraries built:"
find "${JNILIBS}" -name '*.so' -printf '%p  %s bytes\n' | sort
