# 第一关运行组件与许可

下列归档已下载、校验并在 OPPO A6m 5G 上实际运行。固定下载锁为 `app/src/main/assets/runtime/component-manifest.json`；本地下载助手通过手机端 SHA-256 再确认传输结果。

| 组件 | 固定版本 | SHA-256 | 验证与来源 |
| --- | --- | --- | --- |
| PRoot | 5.1.107.96 | `ae390526a9a77af0b907be461285ad0d845da0a15f1fd8f0cf9642db39b1f60d` | Android NDK r23.1.7779620 从对应源码构建；这是最终可执行文件摘要 |
| Debian ARM64 rootfs | trixie / proot-distro v4.26.0 | `cda75346f2c9e09e8a802665745b5a7e2bd6d8584dbf1c86c8c57ef54c4e2d3c` | [官方归档](https://github.com/termux/proot-distro/releases/tag/v4.26.0)，与上游发布摘要匹配 |
| Node Linux ARM64 | 22.20.0 | `06907b9c088ce62305bc1530e5c1ae1510245114645768f7750c349c5b6fe667` | [Node 官方发布](https://nodejs.org/dist/v22.20.0/)，官方 SHASUMS 校验 |
| Linux QQ ARM64 | 3.2.32-52194 | `8796ccfd66acc025ef18db37185532d40bc8c58921e17da6d28c295acbcf8f92` | 腾讯官方 CDN `QQ_3.2.32_260812_arm64_01.deb`；版本取自 deb control。摘要为 TLS 下载后本地计算，未声称上游签署摘要 |
| NapCat Shell | 4.18.30 | `902207c64c249eae98f868e41d5546920d78d7ecb5eae8cabb5b3d6fae556583` | [官方 Release](https://github.com/NapNeko/NapCatQQ/releases/tag/v4.18.30)，GitHub asset digest 校验；使用未修改归档 |
| 原 Agent 核心 | e2dc5bbeec4f2b5bde884676cd4917681da84d36 | `7f9db8a552b646b91eeea708822bd415081ac8054389c314baf7b6b780102e7d` | 本仓库 `src/ui/assets`、生产依赖与许可证；`tools/package_agent_core.py` 生成，不包括个人数据 |

PRoot 源码、talloc、android-shmem 的独立摘要、Bionic/临时目录适配、loader 产物及构建方法见 [native/README](../native/README.md) 和 assets 中的 `native-artifacts.json`。PRoot 的 GPL-2.0-or-later 与静态链接 talloc LGPL-3.0-or-later 的组合按 GPL-3.0-or-later 处理，附对应完整源码与构建脚本；shmem 保留 BSD 版权。不是把所有第三方组件都改成仓库 MIT。

Debian 包保留 `/usr/share/doc/*/copyright`，Node 保留上游 LICENSE。首次安装的 APT 依赖使用 Debian 签名仓库、记录包版本及缓存归档摘要；当前依赖安装基于当时仓库，不声称具备完整长期可重现的 APT 快照锁。实际包版本/摘要只保存在忽略的本地报告中。

Linux QQ 是腾讯专有客户端。NapCat 使用 Limited Redistribution License，存在非商业和修改分发限制；旧来源审查证据保留在 `source-evidence.json` 和 `licenses/NapCat-LICENSE.txt`。当前只进行用户本机安装测试，正式分发前仍须按精确 Release 补齐整套第三方版权和许可清单。

官方 [Shell 部署说明](https://napneko.github.io/guide/boot/Shell) 支持 Linux QQ/Xvfb/NapCat 接入方式。项目固定实现所需步骤，不运行动态 `latest` 安装脚本，不关闭 TLS/摘要校验，也不借用 Termux 的现有数据目录。Debian 初始 rootfs 来自 proot-distro 发行归档，不依赖安装外部 Termux。

## 0.5.0 订阅模型 SDK

OpenAI 使用 MIT 的 @earendil-works/pi-ai 1.0.4；Google Gemini CLI 使用 MIT 的 @mariozechner/pi-ai 0.60.0，通过独立 npm alias 锁定。身份 JWT 校验使用 jose 6.1.3，构建使用 esbuild 0.25.12。精确传递依赖和下载完整性在 android/subscription-runtime/package-lock.json，构建依据见其 README；APK 包含 subscription-sdk.mjs 及 subscription-sdk-LICENSES.txt。

许可文件按 esbuild 的实际打包依赖收集，包含 OpenAI SDK、Google GenAI SDK 等自带许可。Pi 的两个 NPM 包补充相应上游提交/标签的 MIT 许可证，data-uri-to-buffer 从归档自带 README 提取完整许可；缺少许可会阻止 SDK 构建。个人 OAuth 凭据、模型 API 密钥和浏览器会话不打入 APK。
