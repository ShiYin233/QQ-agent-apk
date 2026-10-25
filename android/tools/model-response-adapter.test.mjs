import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeToolResponse } from './model-response-adapter.mjs';
const tools = [{ type: 'function', function: { name: 'send_message', parameters: { type: 'object', properties: { messages: {}, count: {} }, required: ['messages'] } } },
  { type: 'function', function: { name: 'finish', parameters: { type: 'object', properties: { summary: {} } } } }];
const wrap = body => ({ choices: [{ message: { role: 'assistant', content: body }, finish_reason: 'stop' }], usage: { total_tokens: 45 } });
const invoke = body => `<｜DSML｜tool_calls>\n<｜DSML｜invoke name="send_message">${body}</｜DSML｜invoke>\n</｜DSML｜tool_calls>`;
const observed = '\n\n<｜DSML｜tool_calls>\n<｜DSML｜invoke name="send_message">\n<｜DSML｜parameter name="messages" string="true">pong</｜DSML｜parameter>\n</｜DSML｜invoke>\n</｜DSML｜tool_calls>';
test('observed phone response becomes a native send tool call, preserving usage and input', () => {
  const raw = wrap(observed), original = structuredClone(raw);
  const result = normalizeToolResponse(raw, tools);
  assert.equal(result.choices[0].finish_reason, 'tool_calls');
  assert.equal(result.choices[0].message.content, null);
  const call = result.choices[0].message.tool_calls[0];
  assert.equal(call.function.name, 'send_message');
  assert.deepEqual(JSON.parse(call.function.arguments), { messages: 'pong' });
  assert.deepEqual(result.usage, original.usage);assert.deepEqual(raw, original);
});
test('raw string values and JSON typed values keep their distinct types', () => {
  const result = normalizeToolResponse(wrap(invoke('<｜DSML｜parameter name="messages" string="true"> 001\n true </｜DSML｜parameter><｜DSML｜parameter name="count" string="false">2</｜DSML｜parameter>')), tools);
  assert.deepEqual(JSON.parse(result.choices[0].message.tool_calls[0].function.arguments), { messages: ' 001\n true ', count: 2 });
});
test('native tool calls and ordinary assistant text remain unchanged', () => {
  const raw = wrap(observed);raw.choices[0].message.tool_calls = [{ id: 'native', function: { name: 'finish', arguments: '{}' } }];
  assert.equal(normalizeToolResponse(raw, tools), raw);
  const ordinary = wrap('pong');assert.equal(normalizeToolResponse(ordinary, tools), ordinary);
});
test('quoted examples, missing tools, unknown function and missing required argument do not execute', () => {
  for (const raw of [wrap('Example: ' + observed), wrap(observed.replace('send_message', 'unknown')), wrap(invoke(''))]) assert.equal(normalizeToolResponse(raw, tools), raw);
  const raw = wrap(observed);assert.equal(normalizeToolResponse(raw, []), raw);
});
test('truncated tags, invalid JSON and duplicate parameters do not execute', () => {
  const parameter='<｜DSML｜parameter name="messages" string="false">invalid</｜DSML｜parameter>';
  const good='<｜DSML｜parameter name="messages" string="true">pong</｜DSML｜parameter>';
  for (const raw of [wrap(observed.slice(0,-8)),wrap(invoke(parameter)),wrap(invoke(good+good))]) assert.equal(normalizeToolResponse(raw,tools),raw);
});
test('unknown and prototype parameter names are rejected', () => {
  for (const key of ['__proto__','constructor','prototype','unknown']) {
    const raw=wrap(invoke(`<｜DSML｜parameter name="${key}" string="true">x</｜DSML｜parameter>`));
    assert.equal(normalizeToolResponse(raw,tools),raw);
  }
});
test('parallel calls preserve order and whitespace variant is accepted', () => {
  const body=observed.trim().replace('</｜DSML｜tool_calls>','<｜DSML｜invoke name="finish"><｜DSML｜parameter name="summary" string="true">done</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls>').replaceAll('｜DSML｜','｜DSML｜ ');
  const result=normalizeToolResponse(wrap(body),tools);
  assert.deepEqual(result.choices[0].message.tool_calls.map(x=>x.function.name),['send_message','finish']);
});
