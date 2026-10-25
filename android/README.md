# QQ Agent Android：实机运行验证版

当前源码与 APK 为 **0.5.2-join-review**，新增绝地潜兵2入群审核、审核状态和设置；历史实机已验证插件加载及只读接口，真实批准仍待验收。发布包不预填个人群号，已有配置保留；首次安装默认暂停聊天回复并关闭审核插件。入口见 [快速开始](../QUICKSTART.md)。

首页“模型账号 · ChatGPT / Google”保留实验性订阅登录。Google 凭据保存与核心重载后的恢复已验证，但项目初始化返回资格不足；ChatGPT 仍未完成真实订阅模型验证。正常使用请配置 API Key，详见 [订阅账号接入](docs/subscription-accounts.md)。此前 [登录与提及修复](docs/login-mention-repair.md)、[活跃设置修复](docs/activity-settings-repair.md) 继续保留；原 Node 核心源码保持不变。历史实机记录均标注版本和日期，不代表当前手机在线状态。

用户约束为 Android 12+、ARM64、无 Root、无外部 Termux、无远程服务器、保留日常安卓 QQ。本应用包名 `cn.qqagent.android`，与早期 `cn.qqagent.android.gate` 检查原型不同。`minSdk=31`、`compileSdk=36`、`targetSdk=28` 是已接受的侧载配置，不是 Play 发布工程。

## 当前能力

- ARM64 PRoot、64/32 位 loader 内置，并在执行前校验 SHA-256。对应源码、适配脚本和许可位于 [native](native/README.md)。
- 四个外部运行归档固定版本和摘要；Debian、Node、安全解包、Linux QQ 依赖安装和 NapCat 启动已实测。首次准备会下载较大组件；当前 Windows 工具可将归档传入应用私有目录并核验手机端摘要，尚未做完整下载界面。
- 原 Node 核心及锁定的纯 JS 生产依赖打包到 `agent-core.zip`，不包含个人配置、登录态、密钥或 Electron 二进制。
- QQ 使用官方 ARM64 Linux 客户端及未修改的 NapCat Shell 归档。启动入口采用官方 Shell 接入方式；不改动日常安卓 QQ。
- QQ WebUI 和 OneBot 仅监听 `127.0.0.1`，使用随机令牌。模型测试密钥通过手机公钥加密传入，以 Android Keystore AES-GCM 保存；测试进程短暂使用应用私有输入/配置，结束后清理明文配置。
- 固定的调试验证入口不接收任意命令。收发测试必须先导入明确授权的好友和群；仅处理该测试账号发送的“安卓验证”消息，每个目标最多发送一条原 Agent 回复。测试自动到期，不作为长期运行入口。

## 构建与安装

需要 JDK 17+、Android SDK 36、Build Tools 36.0.0；Gradle 8.13 由 Wrapper 获取。设置 `JAVA_HOME`、`ANDROID_HOME`；使用项目私有缓存即可，不需修改系统 PATH。保留现有 `.local-signing/debug.keystore`，避免覆盖安装时签名冲突；新机器按下方生成独立的 Android debug 签名。测试密钥不提交，正式签名尚未确定。

变更订阅 SDK 时先按 [subscription-runtime](subscription-runtime/README.md) 生成 assets 与许可证；普通安卓构建使用已生成的打包文件，不在手机上安装 npm。

```powershell
cd android
if (!(Test-Path .local-signing/debug.keystore)) {
    New-Item -ItemType Directory -Force .local-signing | Out-Null
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore .local-signing/debug.keystore -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
}
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
adb -s <OPPO手机序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <OPPO手机序列号> shell am start -n cn.qqagent.android/cn.qqagent.android.gate.MainActivity
```

修改 SDK 源码后需先安装 `android/subscription-runtime` 的锁定开发依赖并重新生成 SDK。直接使用已提交的 assets 时，无需在电脑或手机安装该套依赖。首次运行组件下载和导入见 [快速开始](../QUICKSTART.md#2-首次导入运行组件)；安装 APK 不等于已准备好四个外部组件。

应用提供八类 Compose 原生页面和实机诊断页。正式账号配置从空名单开始、自动回复暂停，先在协议管理选择并保存允许回复的群/好友，再由用户主动启用。此前有限收发测试不会重复发送。保持手机联网；二维码登录由本人完成。

手机报告在应用私有目录，debug 包可通过 `adb exec-out run-as cn.qqagent.android cat files/<报告名>` 导出。原核心 HTTP 验证通过有效令牌 200、无效令牌 401；这不等于 QQ 收发验证通过。

## 验证与数据

当前实测设备为 OPPO A6m 5G / PLT140、Android 15 / API 35、4 KB 页。模型连接测试请求 `deepseek-v4-flash-0731`，网关响应模型标识为 `dsv4-flash-vision`，正文 `pong`，121 tokens。这里只记录网关实际返回结果，不推断模型身份。

原始 QQ 日志包含账号、聊天内容、登录二维码和管理令牌。用户已明确授权在自己的电脑保存完整日志；本地记录位于 Git 忽略的 `runtime/local-setup/private-qq-logs`，不放入源码、APK 或共享交付物。源码中的验证文档仅包含计数和状态。APK 禁止系统自动备份；本地测试日志保留不等于完成正式加密备份功能。

完整第一关与新功能的事实和待验项见 [gate-report](docs/gate-report.md)，版本、摘要和许可边界见 [components](docs/components.md)。原生页面与实机报告读取真实结果。模型配置加密保存，经 stdin 注入核心内存，配置文件排除密钥；仅监听 127.0.0.1:3312 的 HTTP/SSE 使用私有令牌。

原生前台服务管理 QQ 与核心，常驻通知已开启，含暂停/恢复/停止；QQ 自动恢复约 30.8 秒已有实测。手机 FFmpeg 和安卓视频插件通过原核心图片压缩、GIF 转视频、VideoReader 抽帧检查。原核心内置工具、外部扩展列表/开关/重扫及社区口令安装入口已接入；社区真实安装仍待验。

OPPO 耗电管理的“智能优化后台运行”下，已观察到 USB 保持连接时熄屏接口超时、运行报告停止更新。切换“完全允许后台行为”后，0.4.1 的 77.5 秒 / 5 次采样检查通过；0.4.2 设置页新增手机后台设置入口，安装后结果见 build-validation.json。这是短时 USB 连接检查，不代表 Doze 或全天后台运行通过。

本地工具 export_device_reports.py 导出手机报告，verify_native_console.py 核验认证/空名单/配置写回/密钥排除，verify_native_pages.py 只导航实际原生页面。export_private_logs.py --authorized-private-logs 在完整日志留存获明确授权后同步当前与归档日志。报告和截图保存在 Git 忽略的 runtime/local-setup，不随共享 APK 分发。

## 后续边界

八页的全部桌面操作与弹窗对齐、完整应用内首次下载、账号切换、其他模型/搜索/扩展秘密字段设置、社区登录/验证码、加密备份、组件回滚及正式签名仍待完成。24 小时锁屏、断网/重连、重启、Android 12、另一种 ROM、16 KB 页与耗电温度尚未验收。当前为用户本机调试版，不宣称正式分发就绪。
