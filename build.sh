#!/usr/bin/env bash
# 一键：编译 Rust -> 生成 Kotlin 绑定 -> 打包 APK -> 安装 -> 启动
set -euo pipefail
cd "$(dirname "$0")"

NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.0.12077973}"
PKG=org.example.winsignin

echo ">> 1/5 编译 host 版 Rust（uniffi 从它读接口，生成 Kotlin 绑定）"
(cd rust && cargo build)

echo ">> 2/5 生成 Kotlin 绑定 (app/src/main/java/org/example/winsignin/rust/winsignin.kt)"
(cd rust && cargo run --bin uniffi-bindgen -- generate \
    --library target/debug/libwinsignin.so \
    --language kotlin --out-dir ../app/src/main/java)

echo ">> 3/5 交叉编译 Android .so -> app/src/main/jniLibs/arm64-v8a/"
(cd rust && ANDROID_NDK_HOME="$NDK" cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release)

echo ">> 4/5 打包 + 安装"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo ">> 5/5 启动"
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity"

echo "✅ 完成"
