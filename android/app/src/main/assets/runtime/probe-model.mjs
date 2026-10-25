import fs from 'node:fs';
import path from 'node:path';
import { updateConfig, CONFIG_FILE } from '/opt/agent/src/config.js';
import { listModels, chatCompletion } from '/opt/agent/src/llm.js';

const input = '/qqagent-state/model-probe-input.json';
const reportFile = '/qqagent-state/model-connectivity.json';
let credentials;
const report = { schema: 1, executedOn: 'ANDROID_APPLICATION_PROCESS', timestamp: Date.now(),
  modelConnectivityPassed: false, agentQqReplyPassed: false, qqMessagesSent: 0 };
const sanitize = (value) => String(value).split(credentials?.apiKey || '\0').join('[REDACTED]')
  .replace(/Bearer\s+\S+/gi, 'Bearer [REDACTED]').replace(/sk-[A-Za-z0-9_-]+/g, '[REDACTED]');
try {
  credentials = JSON.parse(fs.readFileSync(input, 'utf8'));
  fs.unlinkSync(input);
  if (credentials.baseUrl !== 'https://discovery-api.intern-ai.org.cn/v1') throw Error('Unexpected test endpoint');
  updateConfig({ api: { baseUrl: credentials.baseUrl, apiKey: credentials.apiKey, provider: '', model: '',
    fallbackModels: [], thinkingMode: 'off', thinkingEffort: 'off', timeoutMs: 60000 },
    allowAllWhenEmpty: false, allow: { groups: [], privates: [] }, snowluma: { autoLaunch: false } });
  const models = await listModels();
  const ids = models.map(x => String(x.id)).filter(Boolean);
  report.availableModels = ids;
  const preferred = ['intern-s1-mini', 'intern-s1-pro', 'intern-s1', 'internlm3-8b-instruct'];
  const chosen = preferred.map(p => ids.find(x => x.toLowerCase() === p)).find(Boolean)
    || ids.find(x => /mini|flash|8b/i.test(x) && !/embed|rerank|audio|tts|image/i.test(x))
    || ids.find(x => !/embed|rerank|audio|tts|image/i.test(x));
  if (!chosen) throw Error('No text chat model returned by authenticated /models');
  updateConfig({ api: { model: chosen } });
  report.requestedModel = chosen;
  const start = Date.now();
  const result = await chatCompletion({ messages: [{ role: 'user', content: '手机连接测试。请只回复 pong，不要解释。' }],
    tools: null, temperature: 0, maxTokens: 128, skillContext: { thinkingMode: 'off' } });
  report.latencyMs = Date.now() - start;
  report.reply = sanitize(result.message?.content || '').slice(0, 1000);
  report.model = result.model;
  report.usage = result.usage;
  report.finishReason = result.raw?.choices?.[0]?.finish_reason;
  report.modelConnectivityPassed = Boolean(report.reply.trim());
  if (!report.modelConnectivityPassed) throw Error('Model returned no visible reply within the 128 token budget');
  report.marker = 'QQAGENT_MODEL_CONNECTIVITY_OK';
} catch (error) {
  report.error = sanitize(error?.message || error).slice(0, 1500);
} finally {
  // This profile is used only for the probe. No credential is retained in Node configuration.
  if (fs.existsSync(input)) fs.unlinkSync(input);
  if (fs.existsSync(CONFIG_FILE)) fs.unlinkSync(CONFIG_FILE);
  fs.mkdirSync(path.dirname(reportFile), { recursive: true });
  fs.writeFileSync(reportFile, JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(report));
  process.exitCode = report.modelConnectivityPassed ? 0 : 1;
}
