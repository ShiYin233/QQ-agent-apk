# 0.5.0 订阅模型账号接入

首页新增“模型账号 · ChatGPT / Google”，分别提供 Continue with ChatGPT 与 Google 账号登录。每个账号可选择模型、测试模型连接、作为回复模型或断开；也可切回原 API Key。账号切换和断开都会暂停 QQ 自动回复。页面的模型测试只请求短文本，不发送 QQ 消息。

## 登录方式

ChatGPT 按 [官方公开注册与登录流程](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) 连接，首次使用 dynamic_agent_client 注册真实应用名 QQ Agent Android，以每次安装的 UUID 标识宿主；后续重用已注册的 client ID。浏览器回调固定为本机 127.0.0.1 的临时端口及 /auth/callback。PKCE、state、nonce、JWKS 签名、发行方、受众、过期时间、账号身份与订阅直接调用 scope 都进行校验。发行方和 JWKS 地址取自 [官方 OpenID 配置](https://auth.openai.com/.well-known/openid-configuration)。

模型列表按 [官方模型与推理说明](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)，使用该账号 OAuth token 查询 /v1/models，保留 visibility=list 的 slug/display_name 及服务端顺序。API 通道为 /v1/responses，store=false、stream=true，不使用 ChatGPT backend-api。SDK 已按订阅请求限制省略不支持的采样、最大输出 token 等字段；测试有 45 秒时限，普通调用有 180 秒时限，这些时限不等于模型输出 token 上限。

Google AI Pro/Ultra 使用订阅所属的个人 Google 账号，通过 Gemini CLI 的 OAuth/Cloud Code Assist 通道接入。依据 [Google 官方认证说明](https://geminicli.com/docs/get-started/authentication/)，个人订阅通常无需额外配置 Cloud Project；组织账号及某些 Code Assist 许可不在本版个人账号配置范围内。当前 Pi 版本移除了 Google CLI 适配，故独立锁定旧 Pi 0.60.0 的 MIT 适配器；没有采用 Antigravity。Google 模型候选目录来自锁定适配器，权限、额度和网络是否可用需登录后真实请求确认。

两种登录均在本手机浏览器打开官方 HTTPS 页面，回调由本机 Node 接收。可取消登录，10 分钟未完成会到期；电脑完成登录时，可在手机粘贴本次完整的本地返回地址，由原 state/路径校验，不能只填授权代码。应用不会收集账号密码。

## 凭据与原配置

凭据仅以 Android Keystore AES-256-GCM 写入手机私有目录的 credentials/subscriptions.enc。Node 通过另一个仅监听 127.0.0.1、随机端口、随机令牌的本机接口读取和更新；令牌经匿名 stdin 注入，不写配置或参数。刷新按提供商串行化，已开始的续期即使模型请求取消也保留旋转后的 refresh token。断开与模型列表刷新存在并发时，不允许旧响应恢复已断开的账号。

原 API Key 继续保存在其既有加密文件中。订阅连接配置只存提供商和模型名，原核心内存使用一个严格拦截的虚拟地址，不向该地址发网络请求；配置文件、状态接口和诊断报告不包含订阅 bearer token、refresh token 或 ID token。

QQ Agent 原核心源码保持不变。安卓层转换文字、已读取图片、工具参数和工具结果，保留多轮工具签名和用量。新增且未配置视觉能力的模型拒绝图片输入；原生视频输入需要使用现有抽帧方式。静态提供商目录或成功显示登录按钮不等于账号已连接，也不等于真实订阅推理已通过。

## 本次验证

Windows 上 25 项安卓单元测试、52 项 Node 测试及 lint 通过。Node 测试涵盖实际打包 SDK 的 SSE 解析、签名工具历史、账号模型目录、OAuth 身份校验、伪造回调、续期并发、取消后的凭据保存，以及失败原因不复制上游凭据；另验证 Google 项目失败前保存授权、重启后的待配置状态、免登录重试、组织/地区/验证限制分类、断开期间旧请求不能恢复凭据。全部使用模拟凭据和响应，不调用真实账号。

OPPO A6m 5G 已安装新版，并验证首页入口、两个登录按钮、本机 OAuth 回调监听和取消、接口令牌验证，以及未连接账号不能选用。升级核对了原回复名单、活跃设置、人设等配置与原 API Key 加密文件；QQ 保持在线、自动回复暂停，本次验证未发送 QQ 消息。

手机 Node 22.20.0 另以模拟响应验证了两个实际打包 SDK 的流处理及 RSA 身份签名/受众校验；全程使用内存中的测试令牌，没有外网模型调用。手机后台无凭据连通性检查中，Google 账号元数据、令牌端点和模型服务均有 HTTP 响应；OpenAI JWKS 返回 200，OpenAI 模型端点在 10 秒内未响应。401/400 只说明无授权请求能够到达服务，不能证明实际账号权限。

两轮 Google 浏览器授权后，0.5.0 在模型项目检查处抛出 Cloud Project 要求；由于 Pi 旧登录函数在项目检查后才返回凭据，授权未保存。0.5.1 通过可复现构建补丁，先将成功授予的 OAuth 凭据写入原生加密存储，再初始化项目。项目处理参照 [当前官方 Gemini CLI setup.ts](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/setup.ts)：允许 Google 尝试初始化默认档位，不提前拒绝没有手填项目的非免费档位；已有档位但无项目、账号资格限制及官方验证分别处理，不创建用户计费项目，也不绕过账号限制。

已授权但无可用项目时，不允许选用或调用模型；页面显示“授权已保存，模型项目尚未就绪”，可填写既有 Cloud Project ID，或完成官方账号验证后点“重试模型项目设置”，无需再登录。验证链接仅允许 Google 官方账号域名；状态与诊断不复制上游原文或令牌。Pi 0.60.0 登录函数的扩展来自 google-oauth-patch.mjs，构建会检查锁定源文件匹配，未直接修改 node_modules。

0.5.1 本人授权后，实机确认 Google OAuth 凭据已加密保存；重新加载原核心后，授权状态及项目失败状态均恢复，加密文件不变。项目初始化仍返回 INELIGIBLE，映射后的原因是 UNKNOWN，具体原因没有确认；尚未调用真实订阅模型。QQ 自动回复暂停，本次未发送 QQ 消息。ChatGPT 仍未完成本人授权。

后续核对 [Antigravity 官方 FAQ](https://www.antigravity.google/docs/faq/) 与 [Gemini CLI 官方 FAQ](https://geminicli.com/docs/resources/faq/) 发现，当前官方明确不支持第三方借用产品 OAuth 凭据调用后端，并说明可能暂停或终止账号。Pi 历史适配代码存在不等于官方支持这种集成；本版没有新增 Antigravity 路由，也没有继续用当前账号做模型调用。官方 [Antigravity SDK](https://www.antigravity.google/docs/sdk/overview/) 使用 Gemini API Key 或企业 Cloud 凭据。上述限制不是本次 INELIGIBLE 的已证实原因。真实订阅推理仍未验收，结果以 build-validation.json 为准。
