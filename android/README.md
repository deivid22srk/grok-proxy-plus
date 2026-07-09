# Grok Proxy Plus — Android

Android app that runs the **grok-proxy-plus** Go core as a native library and
exposes a **Material You (Material 3)** UI to log in with an xAI account and
to start/stop the local OpenAI/Anthropic-compatible proxy.

## How the Go core is embedded

The Go project is compiled with the standard Go toolchain as a **c-shared
library** (`go build -buildmode=c-shared`), producing `libgrokproxy.so` for
each Android ABI. The `.so` files are placed under
`app/src/main/jniLibs/<abi>/` and loaded from Kotlin via JNI
(`System.loadLibrary("grokproxy")`).

This is **full Go, not gomobile** — no `gomobile bind`, no generated Java
bindings. The JNI layer is written in Go (cgo) in
[`mobile/main.go`](../mobile/main.go) and exports functions following the JNI
naming convention (`Java_com_deivid22srk_grokproxy_Bridge_*`).

The native library ships in `jniLibs/` (not `assets/`) because **Android 14+
blocks loading/executing native binaries from `assets/`**.

## Features

- **Login** via xAI OAuth device flow: the app fetches the verification URL +
  user code, opens it in the browser, and polls for completion.
- **Start / Stop** the local proxy server (default `0.0.0.0:8787`).
- Shows the proxy base URL (`http://127.0.0.1:8787/v1` on device,
  `http://<lan-ip>:8787/v1` on the local network) and API key, both copyable.
- Multiple accounts: activate / logout.
- Material 3 with dynamic color (Material You) on Android 12+.

## Build

The APK is built entirely in GitHub Actions (`.github/workflows/build.yml`):

1. Go 1.23 builds `libgrokproxy.so` for `arm64-v8a`, `armeabi-v7a` and
   `x86_64` using the Android NDK — see
   [`scripts/build-android-libs.sh`](../scripts/build-android-libs.sh).
2. Gradle assembles the APK with the prebuilt `.so` files packaged in
   `jniLibs/`.

To build locally you need the Android SDK + NDK and Go 1.23:

```sh
export ANDROID_NDK_HOME=/path/to/ndk
bash scripts/build-android-libs.sh      # → android/app/src/main/jniLibs/<abi>/
cd android && ./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- minSdk 24, targetSdk 35
- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1, Compose BOM 2024.12.01
