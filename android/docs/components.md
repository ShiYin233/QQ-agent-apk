# 第一关组件与许可审查

以下是已核实的**来源证据**，不是完成的运行组件锁。没有编造组件哈希，也没有下载或分发 QQ、NapCat 或 PRoot 可执行文件。

| 组件 | 已核实证据 | 当前状态 |
| --- | --- | --- |
| PRoot | Termux 构建声明 `5.1.107.96`、源码 ZIP SHA-256 `75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa`、GPL-2.0；依赖 `libandroid-shmem`、`libtalloc` | 仅阅读构建声明。未构建独立包名前缀的 Android ARM64 产物；源码声明哈希不等于最终二进制哈希 |
| Debian | 官方 Termux 路线通过 `proot-distro install debian --override-alias napcat` 安装 | 命令未锁发行归档/哈希；不能作为本项目可复现组件锁。每个预装软件包许可亦待清点 |
| Node.js | 仓库要求 Node >=20；云实例使用 Node 24.19.0 执行原核心测试 | 未取得或验证 Debian ARM64 Node 组件；不能使用主机 x86_64 Node 代替 |
| Linux QQ | NapCat 官方脚本在 Debian 内安装 QQ，通过 `xvfb-run ... /opt/QQ/qq --no-sandbox` 启动 | 腾讯专有客户端；精确 ARM64 版本、分发源、哈希、条款和真机兼容性未核实。日常安卓 QQ 不作为替代品 |
| NapCat | 上游 `454cc0eda260f13d1828ab4992d7e268ace9d812` 的许可为 Limited Redistribution License for NapCat | 非商业限制，要求保留完整许可和来源；存在修改代码不得公开的条款。没有构建或分发修改版 NapCat；运行发布版本/归档/哈希尚未锁定 |

来源：

- [NapCat 官方 Shell/Termux 部署文档](https://napneko.github.io/guide/boot/Shell)。镜像网站访问被云网络策略拒绝后，读取官方文档仓库 `NapNeko/NapCatDocs` 的 `5e43a42288118870f6340cdb981d359b003014ac` / `src/guide/boot/Shell.md`。
- [官方 Termux 安装脚本](https://github.com/NapNeko/NapCat-Installer/blob/5cb4e92aa07cfa165bf303a5f5113bc5d0753b4a/script/install.termux.sh)。依赖 Termux 的 apt/pkg、proot-distro、screen，路径明确位于 `/data/data/com.termux/files/...`。脚本使用动态下载，本项目没有直接执行。
- [NapCat 许可原文](https://github.com/NapNeko/NapCatQQ/blob/454cc0eda260f13d1828ab4992d7e268ace9d812/LICENSE)。完整文本附于本目录 `licenses/NapCat-LICENSE.txt`，文本哈希见 `source-evidence.json`。
- [Termux PRoot 构建声明](https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh)。上表固定哈希来自阅读时的官方声明，不冒充已经验证下载。
- [PRoot 源代码与 COPYING](https://github.com/termux/proot)。如以后分发其二进制，应保留版权和 GPL，并提供准确匹配的完整对应源码与构建脚本；本检查 APK 没有 PRoot 二进制。

组件门禁当前使用空运行锁，明确阻止初始化。不能用未固定的 `latest`、关闭校验或 Termux 既有登录态补齐。完整发布许可清单仍须覆盖 rootfs 软件包、Node、QQ、NapCat、PRoot 及依赖；本文件不能替代该清单。

