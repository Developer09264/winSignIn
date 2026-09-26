# winSignIn

重庆邮电大学专用安卓签到 App。个人自用项目，仅支持重邮。

支持：账号密码登录（金智 CAS 链 + 验证码）→ 会话保存 → 点名列表 → 二维码签到。

## 技术栈

- **UI**：Kotlin + Jetpack Compose（Material 3）
- **核心逻辑**：Rust（网络 / 登录 / 签到），交叉编译为 `.so`
- **桥接**：uniffi 0.32，自动生成 Kotlin 绑定
- **扫码**：CameraX + ML Kit 条码识别（模型打进 APK，不依赖 Google Play 服务）
- **minSdk** 26 · **targetSdk** 36 · **NDK** 27.0.12077973

## 目录结构

```
app/src/main/java/org/example/winsignin/   Kotlin 源码，UI + 会话/账号存储
app/src/main/jniLibs/arm64-v8a/            Rust 编译产物（build.sh 生成，未入库）
rust/src/                                  Rust 源码：cas.rs 登录、radar.rs 签到、qr.rs 扫码
```

## 构建

前置：JDK 17、Android SDK 36、NDK 27.0.12077973、Rust 工具链，
以及 `cargo install cargo-ndk`。

配置好 `local.properties` 里的 `sdk.dir` 后，一条命令搞定编译到安装启动：

```bash
./build.sh
```

它会依次：编译 host 版 Rust → 生成 Kotlin 绑定 → 交叉编译 Android `.so` → `assembleDebug` → `adb install` → 启动。

想单独构建 Kotlin 侧（已有 `.so` 时）：

```bash
./gradlew :app:assembleDebug
```

## 注意

`app/src/main/java/.../rust/winsignin.kt` 和 `libwinsignin.so` 都是 `build.sh` 的产物，
前者是纯源码所以随仓库提交，后者是二进制所以不入库 —— 首次构建前请先跑一遍 `build.sh`。

## License

[MIT](LICENSE)
