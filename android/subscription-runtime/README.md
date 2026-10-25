# 手机订阅模型适配

在此目录运行 `npm ci --ignore-scripts --no-audit --no-fund`，再运行 `node build.mjs`，生成 APK assets 中的 `subscription-sdk.mjs` 与第三方许可证合并文件。需要 Node 22.19+；手机使用已锁定的 Node 22.20.0。SDK 构建依赖由 package-lock.json 锁定。

OpenAI 使用 `@earendil-works/pi-ai@1.0.4` 的 Responses 传输。OAuth 依据官方公开注册流程实现 PKCE、state、nonce、JWT 签名与账号绑定验证，使用安装级 UUID。当前账号模型列表来自官方 `/v1/models`，不把 SDK 静态 API 模型列表当成订阅权限。凭据及模型列表缓存在手机 Android Keystore 加密文件。

Google 使用 MIT 的 `@mariozechner/pi-ai@0.60.0` 中 Gemini CLI OAuth/Cloud Code Assist 适配；通过 npm alias 与当前 Pi 分离锁定。当前 Pi 已移除此提供商，不自动追踪最新版本，不调用 Antigravity 登录。模型目录取自该锁定适配器，实际账号权限和额度需真实调用验证。

`google-oauth-patch.mjs` 在 esbuild 加载锁定适配器时扩展两个回调：先加密保存成功授权，再使用安卓项目初始化流程。不改 node_modules，源文件布局不匹配时构建失败。项目设置遵循当前官方 Gemini CLI 的默认档位初始化流程；失败后保留授权，重试不再发起 OAuth。已有项目 ID 与资格/验证限制单独处理。

两个 Pi 包的 NPM 归档未包含独立 LICENSE，补充原仓库许可证：当前 Pi 对应提交 `eb326d265ae0b88489a6d10319307780df827cdf`，旧版对应 `v0.60.0`；均来自 https://github.com/earendil-works/pi 。其他实际打包依赖按 esbuild 输入清单收集 NPM 自带许可，其中 data-uri-to-buffer 的完整许可在自带 README 中。缺少许可会令构建失败。

QQ Agent 原 `src/ui/package.json` 保持不变；新增安卓传输层将原 Chat Completions 消息转换为 Pi 上下文。工具参数、原生签名、工具结果和用量保持对应；运行中的签名完整消息在内存中最多缓存 64 条、4 MiB，断开或重新连接账号时清空。未配置视觉能力的新增模型拒绝图片输入，避免静默丢弃图片。

自动测试：在仓库根目录执行 `node --test android/tools/*.test.mjs`。OAuth 测试使用本地签名与假令牌；两个实际 SDK 传输使用 SSE 样本，不访问真实模型账号，不发送 QQ 消息。
