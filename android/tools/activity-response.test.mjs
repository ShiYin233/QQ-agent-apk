import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { activityPatch, canEnableReplies } from '../app/src/main/assets/runtime/android-console-policy.mjs';
import { installReplyGuard } from '../app/src/main/assets/runtime/android-reply-guard.mjs';
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'qqagent-activity-'));
process.env.QQ_AGENT_DATA_DIR = temporary;
const { getConfig, updateConfig, setRuntimeConfig, storeConfigForChat, resolvePeakSliderPos } = await import('../../src/config.js');
const { resolveContextTier } = await import('../../src/prompt.js');
const { SendQueue } = await import('../../src/sender.js');
const { OneBotClient } = await import('../../src/onebot.js');
const initial = structuredClone(getConfig());
test.after(async () => { await fs.rm(temporary, { recursive: true, force: true }); });
const group = 'group:123456';
const ordinary = [{ text: 'a normal group message', atMe: false }];
const policy = () => ({ store: { contextSliderPos: 30.5, historyCount: 80, unifiedTier: true, keywords: ['hello-bot'],
  peakSchedule: { enabled: false, peak: { start: '09:00', end: '18:00', sliderPos: 5 }, valley: { sliderPos: 30.5 } }, groupSliderPos: {}, groupPeakPos: {} },
  chatActive: { enabled: false, ttlMinutes: 30 }, send: { maxPerMinute: 3, maxPerHour: 30 } });
function install(input = policy()) { setRuntimeConfig(structuredClone(initial)); return updateConfig(activityPatch(input, getConfig())); }
function decision(entries, roll = 50) { return resolveContextTier({ triggerEntries: entries, cfg: storeConfigForChat(group), roll }); }

test('legacy default reproduces full response; unreviewed profile cannot resume Android replies', () => {
  setRuntimeConfig(structuredClone(initial));
  assert.equal(decision(ordinary).shouldRespond, true);
  assert.equal(decision(ordinary).reason, '全部响应');
  assert.equal(canEnableReplies({ api: { baseUrl:'https://example.test/v1',apiKey:'test',model:'test' },allow:{groups:['123456']} }), false);
});
test('saving 15 percent uses original config conversion and threshold rather than full response', () => {
  const result = install();
  assert.equal(result.store.contextTier, 3); assert.equal(result.store.randomPercent, 15);
  assert.equal(decision(ordinary, 14.9).shouldRespond, true);
  for (const roll of [15, 16, 30, 50, 99]) assert.equal(decision(ordinary, roll).shouldRespond, false);
  assert.equal(decision([{text:'hello-bot', atMe:false}], 99).reason, '关键词命中');
  assert.equal(decision([{text:'called explicitly',atMe:true}], 99).reason, '被艾特');
});
test('one tier ignores ordinary group messages; private-message behavior is explicitly preserved', () => {
  const p = policy(); p.store.contextSliderPos = 5; install(p);
  for (let roll = 0; roll < 100; roll++) assert.equal(decision(ordinary, roll).shouldRespond, false);
  assert.equal(resolveContextTier({ triggerEntries: ordinary, cfg: storeConfigForChat(group), isPrivate:true }).reason, '私聊消息');
});
test('per-group settings and reset do not change the global policy', () => {
  const p = policy(); p.store.unifiedTier=false; p.store.groupSliderPos={'123456':5}; install(p);
  assert.equal(storeConfigForChat(group).contextTier, 1);
  assert.equal(storeConfigForChat('group:654321').randomPercent, 15);
  p.store.groupSliderPos['123456']=null; updateConfig(activityPatch(p,getConfig()));
  assert.equal(storeConfigForChat(group).randomPercent, 15);
});
test('peak schedule uses original daytime and cross-midnight semantics', () => {
  const p=policy(); p.store.peakSchedule.enabled=true;
  for (const [start,end,hours,expected] of [['09:00','18:00',10,5],['09:00','18:00',19,30.5],['22:00','06:00',23,5],['22:00','06:00',2,5],['22:00','06:00',12,30.5]]) {
    p.store.peakSchedule.peak.start=start;p.store.peakSchedule.peak.end=end; const saved=install(p);
    assert.equal(resolvePeakSliderPos(saved.store, {getHours:()=>hours,getMinutes:()=>0}),expected);
  }
});
test('invalid schedule, probability, group policy and delivery limits are rejected before save', () => {
  for (const change of [p=>p.store.contextSliderPos=NaN,p=>p.store.peakSchedule.peak.start='25:99',p=>p.store.groupSliderPos={'123456':110},p=>p.send.maxPerMinute=0,p=>p.chatActive.ttlMinutes=999]) {
    const p=policy();change(p);assert.throws(()=>activityPatch(p,initial));
  }
});
test('original send queue applies edited limits and cancels queued tasks at dispatch, without a real QQ connection', async () => {
  const saved=install(); saved.api={baseUrl:'https://example.test',apiKey:'synthetic',model:'synthetic'};saved.allow={groups:['123456'],private:[]};
  saved.send.minGapMs=0;saved.send.maxGapMs=0;saved.send.byLengthMs=0;
  let calls=0;
  const onebot=new OneBotClient({}); onebot.call=async()=>{calls++;return {message_id:calls};};
  const sender=new SendQueue({onebot,store:{appendSelf(){}}});
  const app={onebot,sender,orchestrator:{paused:false,setPaused(v){this.paused=v;}}};installReplyGuard(app,getConfig);
  for(let index=0;index<3;index++) await sender.sendTextBatch(group,[`synthetic ${index}`]);
  await assert.rejects(sender.sendTextBatch(group,['over limit']),/发送频率超限/);assert.equal(calls,3);
  const fresh=new SendQueue({onebot,store:{appendSelf(){}}});app.sender=fresh;installReplyGuard(app,getConfig);
  const pending=fresh.sendTextBatch(group,['cancelled before dispatch']);app.orchestrator.setPaused(true);app.orchestrator.setPaused(false);
  await assert.rejects(pending);assert.equal(calls,3);
});
