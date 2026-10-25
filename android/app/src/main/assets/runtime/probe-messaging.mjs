import fs from 'node:fs';
import crypto from 'node:crypto';
import { normalizeToolResponse } from './model-response-adapter.mjs';

const state = '/qqagent-state';
const credentialsFile = `${state}/messaging-probe-input.json`;
const targets = JSON.parse(fs.readFileSync(`${state}/messaging-targets.json`, 'utf8'));
if (targets.authorized !== true || !/^\d{5,12}$/.test(targets.friend) || !/^\d{5,12}$/.test(targets.group)
  || !/^[a-f0-9-]{36}$/.test(targets.runId)) throw Error('Explicitly authorized test targets are required');
const reportFile = `${state}/messaging-report-${targets.runId}.json`;
const previous = fs.existsSync(reportFile) ? JSON.parse(fs.readFileSync(reportFile, 'utf8')) : null;
const resuming = targets.resume === true && previous?.private?.modelReplySent === true
  && previous?.group?.incomingReceived === true && previous?.group?.modelReplySent === false && (previous.resumeAttempts || 0) < 2;
if (previous && !resuming) {
  console.log(JSON.stringify({ alreadyAttempted: true, explanation: 'Reuse the existing test report; no automatic repeat sends.' }));
  process.exit(1);
}
const credentials = JSON.parse(fs.readFileSync(credentialsFile, 'utf8'));
fs.unlinkSync(credentialsFile);
const link = JSON.parse(fs.readFileSync(`${state}/protocol-link.json`, 'utf8'));
const report = previous || { schema: 1, executedOn: 'ANDROID_APPLICATION_PROCESS', timestamp: Date.now(),
  status: 'STARTING', modelCalls: 0, gatePassed: false, agentQqReplyPassed: false,
  private: { initialSent: false, incomingReceived: false, modelReplySent: false },
  group: { initialSent: false, incomingReceived: false, modelReplySent: false } };
if (resuming) { report.resumeAttempts = (report.resumeAttempts || 0) + 1; report.status = 'RESUMING_FAILED_GROUP_REPLY'; }
const callBudget = resuming ? report.modelCalls + 2 : 4;
function save() {
  fs.writeFileSync(reportFile + '.partial', JSON.stringify(report, null, 2), { mode: 0o600 });
  fs.renameSync(reportFile + '.partial', reportFile);
  fs.writeFileSync(`${state}/messaging-report.json`, JSON.stringify(report, null, 2), { mode: 0o600 });
}
const fetchOriginal = globalThis.fetch;
globalThis.fetch = async (input, options = {}) => {
  const address = new URL(typeof input === 'string' ? input : input.url || String(input));
  if (address.host === 'discovery-api.intern-ai.org.cn' && address.protocol === 'https:') {
    if (address.pathname.endsWith('/chat/completions')) {
      if (report.modelCalls >= callBudget) throw Error('The bounded validation call budget has been reached');
      report.modelCalls++; save();
      // A transport budget only; original Agent prompts, tools, orchestration and model client run unchanged.
      const body = JSON.parse(options.body);
      body.max_tokens = 512;
      options = { ...options, body: JSON.stringify(body) };
    }
  } else if (!(address.hostname === '127.0.0.1' && ['3000', '3311'].includes(address.port))) {
    return Promise.reject(Error('External background requests are disabled for this bounded device validation'));
  }
  const response = await fetchOriginal(input, options);
  if (response.ok && address.host === 'discovery-api.intern-ai.org.cn' && address.pathname.endsWith('/chat/completions')) {
    const raw = await response.clone().json();
    const normalized = normalizeToolResponse(raw, JSON.parse(options.body).tools);
    if (normalized !== raw) {
      report.backendToolCallNormalizations = (report.backendToolCallNormalizations || 0) + 1; save();
      const headers = new Headers(response.headers);
      headers.delete('content-length'); headers.delete('content-encoding');
      return new Response(JSON.stringify(normalized), { status: response.status, statusText: response.statusText, headers });
    }
  }
  return response;
};
const { updateConfig, CONFIG_FILE } = await import('/opt/agent/src/config.js');
const { createApp } = await import('/opt/agent/src/app.js');
const { OneBotClient } = await import('/opt/agent/src/onebot.js');
const safe = value => String(value).split(credentials.apiKey).join('[REDACTED]').split(link.accessToken).join('[REDACTED]')
  .replace(/Bearer\s+\S+/gi, 'Bearer [REDACTED]').replace(/sk-[A-Za-z0-9_-]+/g, '[REDACTED]');
let app;
let finishing = false;
async function finish(status) {
  if (finishing) return;
  finishing = true;
  report.status = status;
  report.agentQqReplyPassed = ['private', 'group'].some(k => report[k].modelReplySent);
  report.messagingPassed = ['private', 'group'].every(k => report[k].initialSent && report[k].incomingReceived && report[k].modelReplySent);
  if (report.messagingPassed) report.marker = 'QQAGENT_MESSAGING_OK';
  save();
  if (app) await app.stop().catch(() => {});
  if (fs.existsSync(CONFIG_FILE)) fs.unlinkSync(CONFIG_FILE);
  console.log(safe(JSON.stringify(report)));
  process.exit(report.messagingPassed ? 0 : 1);
}
process.on('SIGTERM', () => finish('STOPPED'));
process.on('SIGINT', () => finish('STOPPED'));
setTimeout(() => finish('TIMED_OUT'), 20 * 60 * 1000).unref();
try {
  save();
  updateConfig({ api: { baseUrl: credentials.baseUrl, apiKey: credentials.apiKey, provider: '',
    model: credentials.model, fallbackModels: [], thinkingMode: 'off', thinkingEffort: 'off', timeoutMs: 60000, maxRounds: 2 },
    server: { port: 3311, token: crypto.randomBytes(32).toString('hex') },
    allowAllWhenEmpty: false, allow: { groups: [targets.group], private: [targets.friend] },
    sessionRetryAttempts: 0, maxConcurrentRuns: 1, wakeDelayMs: 1000, drainDelayMs: 1000,
    snowluma: { autoLaunch: false, wsUrl: 'ws://127.0.0.1:3001', httpUrl: 'http://127.0.0.1:3000',
      accessToken: link.accessToken, httpAccessToken: link.accessToken },
    persona: { botName: 'QQ Agent', roleText: '你正在进行安卓实机通信测试。收到安卓验证消息后，调用 send_message 只发送 pong，然后 finish。', participation: 'low' },
    store: { contextTier: 4, historyCount: 1, promptAnchor: { enabled: false } },
    proactive: { enabled: false }, chatActive: { enabled: false }, commandMute: { enabled: false },
    memory: { consolidateEnabled: false }, sticker: { enabled: false, collectEnabled: false },
    webSearch: { enabled: false }, extensions: { hotReload: false },
    tools: { crossChatSend: false, categories: { messaging: true, system: true, sticker: false, query: false, memory: false,
      web: false, knowledge: false, media: false } } });
  app = createApp({ log: () => {} });
  const incoming = app.onebot.onEvent;
  const acceptedIds = new Set();
  app.onebot.onEvent = event => {
    if (event.post_type !== 'message' || String(event.user_id) !== targets.friend) return;
    const kind = event.message_type;
    if (!(kind === 'private' || (kind === 'group' && String(event.group_id) === targets.group))) return;
    const text = Array.isArray(event.message) ? event.message.filter(x => x.type === 'text').map(x => x.data?.text || '').join('') : String(event.raw_message || event.message || '');
    if (!text.includes('安卓验证') || report[kind].incomingReceived || acceptedIds.has(String(event.message_id))) return;
    acceptedIds.add(String(event.message_id));
    report[kind].incomingReceived = true; save();
    return incoming(event);
  };
  const send = app.onebot.sendSegments.bind(app.onebot);
  app.onebot.sendSegments = async (kind, id, segments) => {
    if (!(kind === 'private' && String(id) === targets.friend || kind === 'group' && String(id) === targets.group)
      || !report[kind].incomingReceived || report[kind].modelReplySent) throw Error('Validation permits one reply to each approved test target');
    if (!segments.every(x => ['text', 'reply', 'at'].includes(x.type))) throw Error('This validation permits text replies only');
    const sent = await send(kind, id, segments);
    report[kind].modelReplySent = true;
    report[kind].modelReply = safe(segments.filter(x => x.type === 'text').map(x => x.data?.text || '').join('')).slice(0, 500);
    save();
    if (['private', 'group'].every(k => report[k].modelReplySent)) setTimeout(() => finish('COMPLETED'), 1000);
    return sent;
  };
  await app.start();
  const readyUntil = Date.now() + 30000;
  while (!app.onebot.connected && Date.now() < readyUntil) await new Promise(r => setTimeout(r, 500));
  if (!app.onebot.connected) throw Error('Original Agent failed to connect to phone OneBot WebSocket');
  report.originalCoreWebSocketConnected = true;
  const client = new OneBotClient({ httpUrl: 'http://127.0.0.1:3000', accessToken: link.accessToken });
  for (const kind of ['private', 'group']) {
    if (report[kind].initialSendAttempted) continue;
    // Save before each non-idempotent send so a stopped test cannot automatically duplicate it.
    report[kind].initialSendAttempted = true; save();
    await client.sendText(kind, kind === 'private' ? targets.friend : targets.group, 'QQ Agent 安卓实机测试');
    report[kind].initialSent = true; save();
  }
  report.status = 'WAITING_FOR_USER_TEST_MESSAGES'; save();
  console.log(JSON.stringify({ status: report.status, originalCoreWebSocketConnected: true, initialTestMessagesSent: 2 }));
  if (resuming) {
    // Recover only the actual failed incoming group message already stored by the original Agent.
    // No synthetic QQ event, new message, or duplicate private reply is created.
    const key = 'group:' + targets.group;
    const entries = app.store.recent(key, { limit: 2, includeSelf: false });
    if (entries.length !== 1 || entries[0].senderId !== targets.friend || !entries[0].text.includes('安卓验证'))
      throw Error('The original failed group message cannot be recovered unambiguously');
    const restored = app.store.markUnreadInWindow(key, entries[0].ts, entries[0].ts);
    if (restored !== 1) throw Error('Expected exactly one real stored group message to retry');
    report.group.reprocessedStoredInbound = true; report.status = 'REPROCESSING_REAL_GROUP_MESSAGE'; save();
    // Use the original backlog path. onIncoming creates a new-time verified window and would
    // intentionally archive the older real entry instead of retrying it.
    app.orchestrator.scheduleWake(key, 0);
  }
} catch (error) {
  report.error = safe(error?.message || error).slice(0, 1500);
  await finish('FAILED');
}
