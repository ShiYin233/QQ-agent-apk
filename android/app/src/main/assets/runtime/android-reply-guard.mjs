import { AsyncLocalStorage } from 'node:async_hooks';
import { canEnableReplies } from './android-console-policy.mjs';

// Dispatch is checked after the original send queue's delay, including queued media.
export function installReplyGuard(app, config) {
  let generation = 0;
  const origin = new AsyncLocalStorage();
  const originalPause = app.orchestrator.setPaused.bind(app.orchestrator);
  app.orchestrator.setPaused = (paused, ...args) => {
    if (Boolean(paused) !== Boolean(app.orchestrator.paused)) generation++;
    return originalPause(paused, ...args);
  };
  const methods = new Set([...Object.getOwnPropertyNames(Object.getPrototypeOf(app.sender)), ...Object.keys(app.sender)]);
  for (const name of methods) if (name.startsWith('send') && typeof app.sender[name] === 'function') {
    const original = app.sender[name].bind(app.sender);
    app.sender[name] = (...args) => origin.run({ generation }, () => original(...args));
  }
  const originalCall = app.onebot.call.bind(app.onebot);
  app.onebot.call = async (action, params = {}, ...args) => {
    if (/^send_|^(friend|group)_poke$/.test(action)) {
      const current = config();
      if (app.orchestrator.paused || !canEnableReplies(current) || (origin.getStore() && origin.getStore().generation !== generation))
        throw Error('安卓自动回复已暂停或此发送任务已失效');
      const kind = params.group_id != null ? 'groups' : 'private';
      const id = String(params.group_id ?? params.user_id ?? '');
      if (!(current.allow?.[kind] || []).map(String).includes(id)) throw Error('此聊天不在当前允许回复的名单中');
    }
    return originalCall(action, params, ...args);
  };
}
