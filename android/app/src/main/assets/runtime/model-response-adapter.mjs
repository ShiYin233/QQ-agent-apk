import { randomUUID } from 'node:crypto';

/** Normalize the observed gateway DSML envelope before the original Agent sees the response. */
export function normalizeToolResponse(raw, tools) {
  const message = raw?.choices?.[0]?.message;
  if (!message || message.tool_calls?.length || typeof message.content !== 'string' || !Array.isArray(tools) || !tools.length) return raw;
  const text = message.content.trim();
  if (text.length > 65536 || !/^<｜DSML｜\s*tool_calls>/.test(text)) return raw;
  const envelope = /^<｜DSML｜\s*tool_calls>\s*([\s\S]*?)\s*<\/｜DSML｜\s*tool_calls>$/.exec(text);
  if (!envelope) return raw;
  const definitions = new Map(tools.filter(t => t.type === 'function').map(t => [t.function?.name, t.function]));
  const calls = [];
  let remainder = envelope[1];
  while (remainder.trim()) {
    const invocation = /^\s*<｜DSML｜\s*invoke\s+name="([A-Za-z_][\w.-]*)">([\s\S]*?)<\/｜DSML｜\s*invoke>/.exec(remainder);
    if (!invocation || calls.length >= 32 || !definitions.has(invocation[1])) return raw;
    const definition = definitions.get(invocation[1]);
    const args = Object.create(null);
    let parameters = invocation[2];
    while (parameters.trim()) {
      const parameter = /^\s*<｜DSML｜\s*parameter\s+name="([A-Za-z_][\w.-]*)"(?:\s+string="(true|false)")?>([\s\S]*?)<\/｜DSML｜\s*parameter>/.exec(parameters);
      if (!parameter) return raw;
      const key = parameter[1];
      if (['__proto__', 'constructor', 'prototype'].includes(key) || Object.hasOwn(args, key)
        || !Object.hasOwn(definition.parameters?.properties || {}, key)) return raw;
      if (parameter[2] === 'false') {
        try { args[key] = JSON.parse(parameter[3]); } catch { return raw; }
      } else args[key] = parameter[3];
      parameters = parameters.slice(parameter[0].length);
    }
    if ((definition.parameters?.required || []).some(key => !Object.hasOwn(args, key))) return raw;
    calls.push({ id: 'dsml_' + randomUUID(), type: 'function', function: { name: invocation[1], arguments: JSON.stringify(args) } });
    remainder = remainder.slice(invocation[0].length);
  }
  if (!calls.length) return raw;
  const result = structuredClone(raw);
  result.choices[0].message.content = null;
  result.choices[0].message.tool_calls = calls;
  result.choices[0].finish_reason = 'tool_calls';
  return result;
}
