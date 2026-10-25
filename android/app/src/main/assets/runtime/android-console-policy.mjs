// Android policy is outside the unchanged original Node core.
export function withoutSecrets(value) {
  if (Array.isArray(value)) return value.map(withoutSecrets);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).filter(([key]) =>
    !/api.?key|access.?token|secret|password|cookie|^token$|providerKeys/i.test(key)
  ).map(([key, child]) => [key, withoutSecrets(child)]));
}
export function canEnableReplies(config) {
  if (['android-chatgpt-plus','android-google-ai-pro'].includes(config.api?.provider) && config.androidSubscriptionReady !== true) return false;
  return Boolean(config.androidActivityReviewed === true && Number.isFinite(config.store?.contextSliderPos)
    && config.api?.baseUrl && config.api?.apiKey && config.api?.model
    && (config.allow?.groups?.length || config.allow?.private?.length));
}
export function activityPatch(input, current) {
  const within = (n, min, max) => typeof n === 'number' && Number.isFinite(n) && n >= min && n <= max;
  const position = n => { if (!within(n, 0, 100)) throw Error('响应档位必须在 0–100 之间'); return n; };
  const store = input?.store; const active = input?.chatActive; const send = input?.send;
  if (!store || typeof store.unifiedTier !== 'boolean' || !within(store.historyCount, 1, 500) || !Number.isInteger(store.historyCount)) throw Error('历史条数或统一设置无效');
  position(store.contextSliderPos);
  const ps = store.peakSchedule;
  if (!ps || typeof ps.enabled !== 'boolean' || !['start','end'].every(k => /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(ps.peak?.[k] || ''))) throw Error('峰谷时段必须是有效 HH:MM');
  position(ps.peak.sliderPos); position(ps.valley?.sliderPos);
  if (!Array.isArray(store.keywords) || store.keywords.length > 128 || store.keywords.some(k => typeof k !== 'string' || k.length > 128)) throw Error('关键词表无效');
  if (typeof active?.enabled !== 'boolean' || !within(active.ttlMinutes, 1, 240) || !Number.isInteger(active.ttlMinutes)) throw Error('持续参与时长无效');
  if (!within(send?.maxPerMinute, 1, 120) || !within(send.maxPerHour, 1, 1000) || !Number.isInteger(send.maxPerMinute) || !Number.isInteger(send.maxPerHour)) throw Error('发送频率上限无效');
  for (const key of ['groupSliderPos','groupPeakPos']) for (const [id, value] of Object.entries(store[key] || {})) {
    if (!/^\d{5,15}$/.test(id)) throw Error('分群设置标识无效');
    if (value === null) continue;
    if (key === 'groupSliderPos') position(value); else { position(value?.peak); position(value?.valley); }
  }
  return { androidActivityReviewed: true,
    store: { ...current.store, contextSliderPos: store.contextSliderPos, unifiedTier: store.unifiedTier, historyCount: store.historyCount,
      keywords: store.keywords, groupSliderPos: store.groupSliderPos || {}, groupPeakPos: store.groupPeakPos || {},
      peakSchedule: { ...current.store?.peakSchedule, enabled: ps.enabled, peak: ps.peak, valley: ps.valley } },
    chatActive: { ...current.chatActive, enabled: active.enabled, ttlMinutes: active.ttlMinutes },
    send: { ...current.send, maxPerMinute: send.maxPerMinute, maxPerHour: send.maxPerHour } };
}
export function isTelemetryAddress(address) {
  const url = new URL(address);
  return url.hostname === 'kondius.cn' && url.pathname.replace(/\/$/, '') === '/qq-agent/api/telemetry';
}
