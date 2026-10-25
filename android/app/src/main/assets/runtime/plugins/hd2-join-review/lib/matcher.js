const { STRATAGEMS, NON_STRATAGEM_WEAPONS, PROMPT_INJECTIONS } = await import('./stratagems.js?v=' + Date.now() + '-' + Math.random());

export function normalizeText(text) {
  return String(text ?? '').normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();
}
export function extractAnswer(raw) {
  if (typeof raw !== 'string') return '';
  const text = raw.trim();
  // Never accept a name appearing only in the question, or in another question's answer.
  if (/^(?:问题|question)\s*[:：]/i.test(text)) {
    const blocks = text.split(/(?=(?:问题|question)\s*[:：])/i).filter(Boolean);
    for (const block of blocks) {
      const pair = block.match(/^(?:问题|question)\s*[:：]([\s\S]*?)(?:答案|回答|answer)\s*[:：]([\s\S]*)$/i);
      if (pair && /战备|stratagem/i.test(pair[1])) return pair[2].trim();
    }
    const lines = text.split(/\r?\n/);
    if (lines.length === 2 && /战备|stratagem/i.test(lines[0]) && !/答案|回答|answer/i.test(lines[0])) return lines[1].trim();
    return '';
  }
  const marked = text.match(/^(?:答案|回答|answer)\s*[:：]([\s\S]*)$/i);
  return marked ? marked[1].trim() : text;
}
export function isInjectionOrBypass(text) {
  return PROMPT_INJECTIONS.some(re => re.test(text)) || /忽略|无视|直接批准|管理员指令|ignore.*(?:rules|previous)|approve.*request|recognized\s*["']?\s*:/i.test(text);
}
const VAGUE = new Set(['不知道','不知道啊','不清楚','随便','没有','都没用','都讨厌','都喜欢','枪','大炮','武器','装备','轰炸','炮','投弹','炸弹','子弹','飞机','那个武器','那个炮','那个技能','不玩','没玩过','null','undefined','none']);
function escapePattern(text) { return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
export function containsTerm(text, term) {
  const value = normalizeText(term);
  if (!value) return false;
  // English and numeric aliases need token boundaries: great != EAT, 5000 != 500.
  const left = /^[a-z0-9]/i.test(value) ? '(^|[^a-z0-9])' : '';
  const right = /[a-z0-9]$/i.test(value) ? '(?=$|[^a-z0-9])' : '';
  return new RegExp(left + escapePattern(value) + right, 'i').test(text);
}
export function matchRule(raw, catalog = STRATAGEMS) {
  const extracted = extractAnswer(raw);
  const invalid = reason => ({matched:false, extracted, reason, aiAllowed:false});
  if (!extracted) return invalid('答案为空或问答格式无法确认');
  if (extracted.length > 1000) return invalid('答案过长，留人工');
  if (isInjectionOrBypass(extracted)) return invalid('包含诱导绕过规则指令');
  const norm = normalizeText(extracted);
  const stripped = norm.replace(/[^a-z0-9\u3400-\u9fff]/g, '');
  if (!stripped || VAGUE.has(stripped)) return invalid('答案过于模糊或无有效文字');
  const candidates = [];
  for (const item of catalog) {
    for (const [term, method] of [[item.name,'rule'],[item.enName,'rule'],...(item.aliases || []).map(t=>[t,'rule']),...(item.typos || []).map(t=>[t,'rule-typo'])]) {
      if (containsTerm(norm, term)) candidates.push({item, term, method});
    }
  }
  candidates.sort((a,b)=>b.term.length-a.term.length);
  if (candidates.length) {
    const {item,method} = candidates[0];
    return {matched:true,stratagemKey:item.key,name:item.name,method,extracted,aiAllowed:false};
  }
  if (NON_STRATAGEM_WEAPONS.some(term => containsTerm(norm, term))) return invalid('仅识别到普通单兵武器，留人工');
  return {matched:false,extracted,reason:'规则未匹配到已知战备',aiAllowed:true};
}
