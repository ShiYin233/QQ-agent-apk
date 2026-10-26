# 源码包与 APK 发布

当前发布版本：`0.5.2-join-review`，属于调试验证版；Release 标记为预发布。

- [浏览器下载源码 ZIP、APK 与 SHA-256](https://github.com/ShiYin233/QQ-agent-apk/releases/tag/android-v0.5.2)
- [GitHub Packages](https://github.com/users/ShiYin233/packages?repo_name=QQ-agent-apk)
- [发布工作流](../../.github/workflows/publish-android.yml)

## Packages 中包含什么

GitHub Packages 使用 Maven 格式承载这一版的两个文件：

| 项目 | 值 |
| --- | --- |
| groupId | `cn.qqagent.android` |
| artifactId | `qq-agent-apk` |
| version | `0.5.2-join-review` |
| 主文件 | 完整的已提交源码 ZIP（`zip`） |
| APK 附件 | `android-arm64` 分类，`apk` 扩展名 |
| 摘要附件 | `checksums` 分类，`txt` 扩展名 |
| 仓库 | `https://maven.pkg.github.com/shiyin233/QQ-agent-apk` |

这是用于分发的源码和安装包，不是供其他安卓工程依赖的 AAR 库。Packages 下载需要 GitHub 认证及相应权限；普通安装和下载源码可使用 Releases 的直接附件。

## 源码包范围

`android/tools/package_release.py` 从指定 Git 提交逐文件打包，并在 ZIP 内附 `SOURCE-MANIFEST.json`，记录源码提交、文件大小及 SHA-256。ZIP 不重复包含 `android/artifacts` 中的历史 APK 或日志，当前 APK 作为单独附件提供。

保留根项目源码、安卓工程、构建脚本、原生组件及对应源归档、内置 Agent 核心归档、订阅 SDK 及锁文件、许可证和文档。个人配置、登录态、聊天记录、Key、签名密钥、node_modules、SDK 和构建缓存不在源码包中。APK 必须与当前 `build-validation.json` 的摘要一致才允许打包。

## 维护者发布步骤

1. 更新版本及验证报告，构建并核对 APK，将相应源码与 APK 提交到仓库。
2. 在 GitHub 的 Actions 中打开 **Publish Android source and APK**，选择正确分支并运行。
3. 工作流打包源码和 APK，使用本次任务的 `GITHUB_TOKEN` 发布 Maven 文件，再创建预发布 Release 和下载附件。
4. 确认工作流成功，并检查 Packages 版本、Release 附件和摘要。

工作流只通过手动运行触发，不会因每次普通提交自动发布。包版本和标签均按当前安卓版本生成；新内容应使用新版本，不覆盖已经发布的 Maven 版本。调试签名由现有 APK 保留，发布工作流不接触本机签名密钥，也不进行 QQ 或模型实机操作。

第三方组件按各自许可分发，具体范围见 [components.md](components.md)。首次安装、订阅模型和实机验证边界见 [快速开始](../../QUICKSTART.md)，发布成功不表示这些待验项已完成。
