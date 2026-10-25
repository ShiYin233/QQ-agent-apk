import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'qqagent-mentions-'));
process.env.QQ_AGENT_DATA_DIR = temporary;
const { SkillManager } = await import('../../src/skills/manager.js');
test.after(async () => { await fs.rm(temporary, {recursive:true,force:true}); });
import { hooks, promptSections } from '../app/src/main/assets/runtime/plugins/android-mention-identity/index.mjs';
const manager = new SkillManager();
manager.register({ manifest: { id: 'android-mention-identity', enabledByDefault: true }, hooks, promptSections });
test('protocol atMe survives a different displayed nickname in original hook and prompt pipeline', async () => {
  const ctx = { sessionId:'one',kind:'group',proactive:false };
  await manager.runHook('before-context', { ...ctx, triggerEntries:[{id:3,mid:42,text:'@different-nickname hello',atMe:true}] });
  const sections = manager.getPromptSections(ctx);
  assert.equal(sections.length,1);assert.match(sections[0].content,/#42/);assert.doesNotMatch(sections[0].content,/#3\b/);assert.match(sections[0].content,/明确 @ 了当前机器人账号/);
});
test('another account mention and plain nickname do not become authoritative mentions', async () => {
  const ctx = {sessionId:'other',kind:'group'};
  await manager.runHook('before-context',{...ctx,triggerEntries:[{id:43,text:'@bot hello',atMe:false},{id:44,text:'@bot hello'}]});
  assert.deepEqual(manager.getPromptSections(ctx),[]);
});
test('private, proactive and unrelated sessions retain original prompt decisions', async () => {
  for (const ctx of [{sessionId:'private',kind:'private'},{sessionId:'proactive',kind:'group',proactive:true}]) {
    await manager.runHook('before-context',{...ctx,triggerEntries:[{id:42,atMe:true}]});
    assert.deepEqual(manager.getPromptSections(ctx),[]);
  }
  assert.deepEqual(manager.getPromptSections({sessionId:'missing',kind:'group'}),[]);
});
test('message IDs are validated and reusing a session clears stale mention information', async () => {
  const ctx={sessionId:'reuse',kind:'group'};
  await manager.runHook('before-context',{...ctx,triggerEntries:[{id:1,mid:'evil\ntext',atMe:true}]});
  assert.doesNotMatch(manager.getPromptSections(ctx)[0].content,/evil/);
  await manager.runHook('before-context',{...ctx,triggerEntries:[{id:1,mid:45,atMe:true}]});
  assert.equal(manager.getPromptSections(ctx).length,1);
  await manager.runHook('before-context',{...ctx,triggerEntries:[{id:46,atMe:false}]});
  assert.deepEqual(manager.getPromptSections(ctx),[]);
});
test('packaged plugin loads through original loader and injects actual original system prompt', async () => {
  const { loadPlugins } = await import('../../src/plugin-loader.js');
  const { skillManager } = await import('../../src/skills/manager.js');
  const { buildSystemPrompt } = await import('../../src/prompt.js');
  const result = await loadPlugins({log:()=>{},roots:{skills:temporary,plugins:fileURLToPath(new URL('../app/src/main/assets/runtime/plugins',import.meta.url))}});
  assert.ok(skillManager.registry.get('android-mention-identity') && !skillManager.registry.get('android-mention-identity').loadError,JSON.stringify(result));
  const ctx={sessionId:'actual',kind:'group',proactive:false};
  await skillManager.runHook('before-context',{...ctx,triggerEntries:[{id:3,mid:123456,text:'@different-name',atMe:true}]});
  assert.match(buildSystemPrompt({skillContext:ctx}),/#123456.*明确 @ 了当前机器人账号/);
  assert.doesNotMatch(buildSystemPrompt({skillContext:{...ctx,sessionId:'not-mentioned'}}),/协议按机器人 QQ 账号确认/);
});
