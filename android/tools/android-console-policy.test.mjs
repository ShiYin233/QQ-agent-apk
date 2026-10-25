import test from 'node:test';
import assert from 'node:assert/strict';
import { withoutSecrets, canEnableReplies, isTelemetryAddress } from '../app/src/main/assets/runtime/android-console-policy.mjs';

test('configuration serialization removes nested keys and tokens without mutating live credentials', () => {
  const config = { api: { baseUrl: 'https://example.com/v1', apiKey: 'private-api-secret', model: 'test' },
    server: { port: 3312, token: 'private-console-token' }, snowluma: { accessToken: 'private-protocol-token' },
    webSearch: { providers: [{ apiKey: 'private-search-secret', name: 'search' }] }, skills: { web: { cookie: 'private-cookie-secret', enabled: true } },
    dshProviderKeys: { provider: 'other-private' }, allow: { private: ['123456'], groups: [] } };
  const disk = withoutSecrets(config);
  assert.equal(config.api.apiKey, 'private-api-secret'); assert.equal(disk.api.apiKey, undefined);
  for (const secret of ['private-api-secret', 'private-console-token', 'private-protocol-token', 'private-search-secret', 'private-cookie-secret', 'other-private'])
    assert.ok(!JSON.stringify(disk).includes(`\"${secret}\"`), secret);
  assert.deepEqual(disk.allow, config.allow); assert.equal(disk.skills.web.enabled, true);
});
test('empty allow lists and incomplete model configuration cannot enable replies', () => {
  const api = { baseUrl: 'https://example.com/v1', apiKey: 'secret', model: 'model' };
  assert.equal(canEnableReplies({ api, allow: { groups: [], private: [] }, allowAllWhenEmpty: true }), false);
  assert.equal(canEnableReplies({ api: { ...api, apiKey: '' }, allow: { groups: ['1'] } }), false);
  assert.equal(canEnableReplies({ api, allow: { private: ['1'], groups: [] } }), false);
  assert.equal(canEnableReplies({ api, allow: { private: ['1'], groups: [] }, androidActivityReviewed: true, store: { contextSliderPos: 5 } }), true);
});
test('telemetry exclusion does not block community pages or public price data', () => {
  assert.equal(isTelemetryAddress('https://kondius.cn/qq-agent/api/telemetry'), true);
  assert.equal(isTelemetryAddress('https://kondius.cn/qq-agent/api/telemetry/'), true);
  assert.equal(isTelemetryAddress('https://kondius.cn/qq-agent/model-prices.json'), false);
  assert.equal(isTelemetryAddress('https://kondius.cn/qq-agent/api/market'), false);
  assert.equal(isTelemetryAddress('https://elsewhere.test/qq-agent/api/telemetry'), false);
});

test('a subscription placeholder cannot resume replies without an authenticated account', () => {
  const config={api:{baseUrl:'https://subscription.qqagent.invalid/v1/chatgpt-plus',provider:'android-chatgpt-plus',apiKey:'android-subscription',model:'gpt-5-mini'},allow:{groups:['1']},androidActivityReviewed:true,store:{contextSliderPos:5}};
  assert.equal(canEnableReplies(config),false);
  assert.equal(canEnableReplies({...config,androidSubscriptionReady:true}),true);
  assert.equal(canEnableReplies({...config,androidSubscriptionReady:false}),false);
});
