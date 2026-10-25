# 独立包名的 Android ARM64 PRoot

这些二进制通过 Windows 主机上的 Android NDK r23.1.7779620 从对应源代码构建，不依赖 Termux 安装、Termux 路径或系统 Root。APK 的 targetSdk 28 是已接受的侧载条件；16 KB 对齐不等于 16 KB 手机验证通过。

提供完整、未改动的上游源归档，以及生成 Bionic 适配头、shmem 临时路径适配和两个 loader 的构建脚本。适配修改由脚本生成，不能遗漏脚本后仅提供上游源码。脚本执行时传入路径，不会修改 Unity SDK/NDK 或系统 PATH。

| 源码 | SHA-256 |
| --- | --- |
| termux/proot v5.1.107.96 | `75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa` |
| Samba talloc 2.5.0 | `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007` |
| termux/libandroid-shmem v0.7 | `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867` |

先核验三个归档，再解包到同一工作目录，目录名分别为 `proot-5.1.107.96`、`talloc-2.5.0`、`libandroid-shmem-0.7`。运行：

```powershell
python build_proot.py --work-dir '<已解包源码目录>' --ndk '<Android NDK r23.1.7779620目录>'
```

产物位于 `out-arm64`；`native-artifacts.json` 记录每个文件的大小与 SHA-256。复制三个二进制及 JSON 到 `app/src/main/assets/native` 后由应用校验再启用。默认 4 KB 实机已验证 PRoot、ptrace、Debian shell、Node 22 和 HTTP 回环。

PRoot 原始代码 GPL-2.0-or-later，talloc 为 LGPL-3.0-or-later，android-shmem 为 BSD-3-Clause。静态组合产物按 GPL-3.0-or-later 提供，并附完整对应源码和构建适配；不是 LGPL 动态链接豁免。版权和许可文本保留在 `../docs/licenses`。非系统依赖 talloc/shmem 直接包含在可重编译源码中。

本地 `out-arm64`、NDK、签名密钥和构建缓存不用提交。应用登录态与聊天记录也不用提交。第三方许可证据与整体正式分发清单尚未完成；当前产物仅是用户本机的实机验证版。
