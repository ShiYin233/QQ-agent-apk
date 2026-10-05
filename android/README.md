# QQ Agent Android：第一关检查原型

**这不是可运行的 QQ 机器人 APK。第一关尚未通过，后续移植已停止。**

本目录提供 Kotlin/Compose 最小工程和第一关检查器。应用可以检查设备、记录页大小和私有目录可用空间，输出明确的阻断结果。没有内置 ARM64 PRoot，也没有已核验的完整 Debian/Node/Linux QQ/NapCat 组件锁；因此不会初始化、下载运行组件或提示 QQ 登录。

用户约束：Android 12+、ARM64、无 Root、无外部 Termux、无远程服务器、保留日常 QQ。`minSdk=31`、`compileSdk=36`、`targetSdk=28`。构建声明 `arm64-v8a` ABI 过滤；检查 APK 的 Compose 依赖带有 `lib/arm64-v8a/libandroidx.graphics.path.so`，没有其他 ABI，也没有 PRoot native 引导。这不能证明完整运行组件或 16 KB 页兼容。设备检查器拒绝不支持 ARM64 的设备。

首台候选：**OPPO A6m 5G / 天玑 6300 / ColorOS 15**（用户提供）。Android API、页大小、ROM build、剩余空间和实际兼容性等待设备报告；不从 ROM 名称推定实测结果。

## 构建

使用 JDK 17+、Android SDK 36、Build Tools 36.0.0 和 Gradle 8.13。Gradle Wrapper 固定分发版本与 SHA-256；插件和直接依赖固定版本。

创建本地测试签名（不提交密钥，已有文件时不得覆盖）：

```sh
cd android
mkdir -p .local-signing
test -f .local-signing/debug.keystore || keytool -genkeypair \
  -keystore .local-signing/debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 10000
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

设置 `ANDROID_HOME` 指向 SDK；JDK 必须包含 `javac`，JRE 不够。代理环境需使用可信 CA 和 Gradle 的代理配置，不可关闭 TLS 校验。用户要求的 targetSdk 28 小于 minSdk 31，AGP 会警告；不是 Play 商店发布工程。

测试密钥使用公开的 Android debug 密码，只适合本地检查。保留同一测试密钥可以升级测试 APK；它**不是已确定的发布签名**。正式签名、机器人升级保留数据和回滚均未完成，不应分发为正式机器人。

## 安装与设备报告

安装检查 APK 到候选手机，打开“QQ Agent 第一关检查”，点击“检查第一关并记录设备信息”。当前预期是设备前置检查结果和“组件准备：阻断”。这不能证明 PRoot、QQ 或 NapCat 可以运行。

```sh
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cn.qqagent.android.gate/.MainActivity
# 点击检查按钮后，导出应用生成的报告；仅适用于本 debug APK
adb exec-out run-as cn.qqagent.android.gate cat files/gate-report.json > gate-report.json
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell getconf PAGE_SIZE
```

无需 Root，不与安卓 QQ 包名或目录共享，不自动备份应用数据，不采集账号、密钥、聊天或登录态，也没有遥测。设备报告包含系统 fingerprint，分享前可以检查是否希望保留该设备标识。

## 已实现的检查边界

- 第一关每一步区分通过、失败、阻断、未执行；前置检查通过不算端到端通过。
- 五个组件的版本、HTTPS 来源、SHA-256、许可证据、下载与安装空间上限必须完整；APK 未内置 PRoot 时禁止推进。
- 下载器使用正常 TLS 校验，限制大小和重定向次数，拒绝降级到 HTTP；仅在完整 SHA-256 校验后保存 artifact。
- 取消、断流、校验失败或空间写入错误会删除本次临时文件；不会覆盖旧运行组件。下载器尚未接入 UI、解包或运行环境，不能声称已验证完整初始化或组件回滚。

## 第一关继续条件

1. 为独立包名构建、打包、核验 ARM64 Android PRoot 及其依赖和许可证义务。Termux 的现成包包含固定前缀及依赖，不能假定改名或复制就能运行。
2. 取得可复现的五组件版本/来源/哈希/许可证据，验证 rootfs 解包、权限、软链接、Android 私有目录执行、PRoot ptrace 和 16 KB 页。
3. 在候选手机完成首次初始化、QQ 登录、群与好友列表、私聊与群聊双向收发，以及接原 Agent 的一次真实模型回复，记录日志。

当前云环境的 ADB 设备列表为空。不能把云端 Node 模拟测试、成功构建或检查 APK 当作上述真机证据。不能转为外部 Termux、远程服务器或修改版 QQ 来绕过关卡。

## 后续范围

只有第一关全部通过才推进原生八类页面、HTTP token/SSE 与回环接口、平台进程适配、媒体依赖、扩展、社区登录、前台服务/通知/恢复、账号隔离、加密备份、组件回滚、正式签名和升级。24 小时真机稳定性、Android 12/较新系统、另一种 ROM 和 16 KB 页测试均尚未执行。见 [验证报告](docs/gate-report.md) 和 [组件审查](docs/components.md)。
