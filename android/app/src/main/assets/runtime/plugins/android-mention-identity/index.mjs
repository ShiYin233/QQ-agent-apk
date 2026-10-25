// SPDX-License-Identifier: MIT
const mentions = new Map();
export function rememberMentions({ sessionId, kind, proactive, triggerEntries = [] }) {
  mentions.delete(sessionId);
  if (kind !== 'group' || proactive) return;
  const entries = triggerEntries.filter(e => e.atMe === true);
  const ids = entries.map(e => String(e.mid)).filter(id => /^-?\d+$/.test(id));
  if (entries.length) mentions.set(sessionId, { ids: ids.slice(0, 128), count: entries.length });
  while (mentions.size > 128) mentions.delete(mentions.keys().next().value);
}
export const hooks = { 'before-context': rememberMentions };
export function promptSections({ sessionId, kind, proactive }) {
  const marker = mentions.get(sessionId);
  if (kind !== 'group' || proactive || !marker) return [];
  const label = marker.ids.length ? marker.ids.map(id => '#' + id).join('、') : `中的 ${marker.count} 条消息`;
  return [{ id: 'android-account-mention', title: '本次账号提及信息', priority: 90,
    content: `协议按机器人 QQ 账号确认：本批消息 ${label} 明确 @ 了当前机器人账号，也就是你。显示的 @ 名称可能是群名片，与角色名不同；不能因此认定“在叫另一个人”。按现有被 @ 回复规则处理，正常调用发送工具；仍须遵守暂停、回复名单和限频规则。本标记不适用于其他消息。` }];
}
