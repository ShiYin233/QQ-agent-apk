import fs from 'node:fs';
import readline from 'node:readline';
import { withoutSecrets, canEnableReplies, isTelemetryAddress, activityPatch } from './android-console-policy.mjs';
import { normalizeToolResponse } from './model-response-adapter.mjs';
import { installReplyGuard } from './android-reply-guard.mjs';
import * as subscriptionSdk from './subscription-sdk.mjs';
import { BridgeCredentialStore, SubscriptionManager } from './android-subscriptions.mjs';
import { subscriptionRoute } from './android-subscription-adapter.mjs';

// Keys arrive through the app-owned anonymous stdin pipe, never a bootstrap file.
const lines = readline.createInterface({ input: process.stdin });
const bootstrap = await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(Error('Bootstrap timeout')), 15000);
  lines.once('line', line => { clearTimeout(timer); resolve(JSON.parse(line)); lines.close(); process.stdin.pause(); });
});
if (!/^[a-f0-9]{64}$/.test(bootstrap.token) || !/^\d{5,15}$/.test(bootstrap.account)) throw Error('Invalid bootstrap');
if (bootstrap.timeZone) { new Intl.DateTimeFormat('en', { timeZone: bootstrap.timeZone }); process.env.TZ = bootstrap.timeZone; }
const originalWrite = fs.writeFileSync;
const configFile = `${process.env.QQ_AGENT_DATA_DIR}/config.json`;
fs.writeFileSync = function (file, data, ...args) {
  if ([configFile, `${configFile}.tmp`].includes(String(file))) data = JSON.stringify(withoutSecrets(JSON.parse(String(data))), null, 2);
  return originalWrite.call(this, file, data, ...args);
};
const fetchOriginal = globalThis.fetch;
let subscriptions;
globalThis.fetch = async (input, options = {}) => {
  const address = new URL(typeof input === 'string' ? input : input.url || String(input));
  if (isTelemetryAddress(address)) throw Error('Android telemetry is disabled');
  const provider = subscriptionRoute(address);
  if (provider) {
    if (!subscriptions || options.method !== 'POST' || typeof options.body !== 'string') throw Error('订阅模型请求格式无效');
    const result = await subscriptions.complete(provider,JSON.parse(options.body),options.signal);
    return new Response(JSON.stringify(result),{status:200,headers:{'content-type':'application/json'}});
  }
  const ambient = subscriptions?.work.getStore();
  if (ambient) options = {...options,signal:options.signal ? AbortSignal.any([ambient,options.signal]) : ambient};
  const response = await fetchOriginal(input, options);
  if (response.ok && address.pathname.endsWith('/chat/completions') && typeof options.body === 'string') {
    try {
      const body = JSON.parse(options.body);
      if (body.stream !== true) {
        const raw = await response.clone().json();
        const normalized = normalizeToolResponse(raw, body.tools);
        if (normalized !== raw) {
          const headers = new Headers(response.headers); headers.delete('content-length'); headers.delete('content-encoding');
          return new Response(JSON.stringify(normalized), { status: response.status, headers });
        }
      }
    } catch { /* Preserve responses that are not tool JSON. */ }
  }
  return response;
};
const { getConfig, updateConfig } = await import('/opt/agent/src/config.js');
const { skillManager } = await import('/opt/agent/src/skills/manager.js');
const { createApp } = await import('/opt/agent/src/app.js');
const fresh = !fs.existsSync(configFile);
updateConfig({ ...(fresh ? { allow: { groups: [], private: [] }, proactive: { enabled: false }, store: { contextSliderPos: 5 }, androidActivityReviewed: false } : {}),
  server: { port: 3312, token: bootstrap.token }, allowAllWhenEmpty: false,
  api: { ...(bootstrap.api || {}), apiKey: bootstrap.api?.apiKey || '' },
  snowluma: { autoLaunch: false, wsUrl: 'ws://127.0.0.1:3001', httpUrl: 'http://127.0.0.1:3000', accessToken: bootstrap.protocolToken },
  extensions: { hotReload: true } });
// No model call or QQ send is caused by opening the management console.
const app = createApp({ log: message => {
  let safe = String(message);
  for (const secret of [bootstrap.token, bootstrap.protocolToken, bootstrap.api?.apiKey,bootstrap.manualApi?.apiKey,bootstrap.credentialBridge?.token]) if (secret) safe = safe.split(secret).join('[hidden]');
  console.log(safe);
} });
subscriptions = new SubscriptionManager({sdk:subscriptionSdk,credentials:new BridgeCredentialStore(bootstrap.credentialBridge,fetchOriginal),
  deviceId:bootstrap.subscriptionHostId,manualApi:bootstrap.manualApi,getConfig,updateConfig,pause:()=>app.orchestrator.setPaused(true)});
await subscriptions.initialise();
if(subscriptions.known.get('chatgpt-plus')?.connected)subscriptions.refreshModels('chatgpt-plus').catch(()=>{});
installReplyGuard(app, getConfig);
app.orchestrator.setPaused(bootstrap.repliesEnabled !== true || !canEnableReplies(getConfig()));
const handlers = app.server.listeners('request');
app.server.removeAllListeners('request');
app.server.on('request', async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (!url.pathname.startsWith('/api/android/')) { for (const handler of handlers) handler.call(app.server, req, res); return; }
  const json = (status, body) => { res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(body)); };
  if (req.headers['x-console-token'] !== bootstrap.token) return json(401, { error: '未授权' });
  if (req.headers.origin && req.headers.origin !== 'http://127.0.0.1:3312') return json(403, { error: '请求来源不被信任' });
  if (url.pathname.startsWith('/api/android/subscriptions')) {
    try {
      if (req.method==='GET' && url.pathname==='/api/android/subscriptions') return json(200,subscriptions.status());
      if (req.method!=='POST') return json(405,{error:'不支持此操作'});
      let text='';for await (const chunk of req){text+=chunk;if(text.length>16384)return json(413,{error:'请求过大'});}
      const body=JSON.parse(text || '{}');
      switch(url.pathname) {
        case '/api/android/subscriptions/login': return json(200,subscriptions.begin(body.provider));
        case '/api/android/subscriptions/setup-google': return json(200,subscriptions.begin('google-ai-pro',{resume:true,projectId:body.projectId}));
        case '/api/android/subscriptions/callback': return json(200,subscriptions.submit(body.id,body.url));
        case '/api/android/subscriptions/cancel': subscriptions.cancel();return json(200,{ok:true});
        case '/api/android/subscriptions/disconnect': return json(200,await subscriptions.disconnect(body.provider));
        case '/api/android/subscriptions/select': return json(200,await subscriptions.select(body.provider,body.model));
        case '/api/android/subscriptions/manual': return json(200,await subscriptions.manual());
        case '/api/android/subscriptions/refresh-models': return json(200,await subscriptions.refreshModels(body.provider));
        case '/api/android/subscriptions/test': {
          const result=await subscriptions.complete(body.provider,{model:body.model,messages:[{role:'user',content:'请只回复 pong。'}],max_tokens:1024},AbortSignal.timeout(45000));
          return json(200,{ok:true,text:result.choices[0].message.content || '',usage:result.usage});
        }
        default:return json(404,{error:'此账号操作不存在'});
      }
    } catch(error) { return json(400,{error:error.message || '账号操作未完成'}); }
  }
  if (req.method === 'GET' && url.pathname === '/api/android/runtime') {
    let runtime = {}; try { runtime = JSON.parse(fs.readFileSync('/qqagent-state/android-runtime.json', 'utf8')); } catch {}
    return json(200, { ...runtime, coreRunning: true, onebotConnected: app.onebot.connected,
      automaticRepliesEnabled: !app.orchestrator.paused, telemetryEnabled: false, account: bootstrap.account, platform: 'android', consolePort: 3312 });
  }
  if (req.method === 'GET' && url.pathname === '/api/android/join-review') {
    const plugin = skillManager.registry.get('hd2-join-review');
    const status = plugin?.providers?.['hd2-join-review.status']?.() || { running: false, lastCheckResult: '插件未加载' };
    return json(200, { ...status, loaded: Boolean(plugin && !plugin.loadError),
      active: skillManager.isActive('hd2-join-review').active });
  }
  if (req.method === 'GET' && url.pathname === '/api/android/capabilities') {
    const has = path => { try { fs.accessSync(path, fs.constants.X_OK); return true; } catch { return false; } };
    return json(200, { capabilities: [
      { name: '原核心与 OneBot', available: true, reason: '在手机私有 Linux 环境运行' },
      { name: 'FFmpeg 视频处理', available: has('/usr/bin/ffmpeg'), reason: has('/usr/bin/ffmpeg') ? '已安装；媒体实机回归待完成' : '尚未安装 ARM64 FFmpeg' },
      { name: '原核心图片压缩与 GIF 转视频', available: has('/usr/bin/ffmpeg'), reason: has('/usr/bin/ffmpeg') ? '由 FFmpeg 提供；实测记录见媒体报告' : '尚未安装 ARM64 FFmpeg' },
      { name: '桌面进程管理', available: false, reason: '由安卓运行服务管理 Linux QQ' },
      { name: '社区登录、加密备份与账号切换', available: false, reason: '后续移植步骤尚未完成' }
    ] });
  }
  if (req.method === 'GET' && url.pathname === '/api/android/media') {
    let report = { passed: false, notRun: true };
    try { report = JSON.parse(fs.readFileSync('/qqagent-state/media-report.json', 'utf8')); } catch {}
    return json(200, report);
  }
  if (req.method === 'POST' && url.pathname === '/api/android/replies') {
    try {
      let text = ''; for await (const chunk of req) { text += chunk; if (text.length > 4096) return json(413, { error: '请求过大' }); }
      const enabled = JSON.parse(text).enabled === true;
      if (enabled && !canEnableReplies(getConfig())) return json(409, { error: '请先保存活跃设置，填写模型配置并选择允许回复的群或好友' });
      app.orchestrator.setPaused(!enabled);
      if (enabled) for (const chatKey of app.store.listChats()) app.store.drainUnread(chatKey);
      return json(200, { ok: true, automaticRepliesEnabled: enabled });
    } catch { return json(400, { error: '无法更改回复状态' }); }
  }
  if (req.method === 'POST' && url.pathname === '/api/android/activity') {
    try {
      let text = ''; for await (const chunk of req) { text += chunk; if (text.length > 131072) return json(413, { error: '设置过大' }); }
      const patch = activityPatch(JSON.parse(text), getConfig());
      app.orchestrator.setPaused(true);
      updateConfig(patch);
      return json(200, { ok: true, automaticRepliesEnabled: false, config: withoutSecrets(getConfig()) });
    } catch (error) { return json(400, { error: error.message || '活跃设置无效' }); }
  }
  return json(404, { error: '接口不存在' });
});
await app.start();
if (app.server.address()?.address !== '127.0.0.1') { await app.stop(); throw Error('Loopback binding required'); }
console.log('QQAGENT_ANDROID_CONSOLE_READY');
let stopping = false;
async function stop() { if (stopping) return; stopping = true; subscriptions.cancel(); await app.stop(); process.exit(0); }
process.on('SIGTERM', stop); process.on('SIGINT', stop);
// Parent pipe closure also terminates the console rather than leaving a stale bot.
process.stdin.on('end', stop);
