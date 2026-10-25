// Verified against bundled SnowLuma and the phone's actual NapCat implementation.
function id(value) {
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value <= 0)) return '';
  const text = String(value ?? '');
  return /^[1-9]\d*$/.test(text) ? text : '';
}
export function normalizeRequests(data, targetGroup) {
  const snow = Array.isArray(data);
  const raw = snow ? data : data?.join_requests;
  if (!Array.isArray(raw)) throw Error('不支持的群申请响应格式');
  const requests = [];
  const seen = new Set();
  for (const item of raw) {
    if (!item || id(item.group_id) !== targetGroup || item.checked !== false) continue;
    let flag, user;
    if (snow) {
      flag = String(item.flag ?? '');
      const match = /^slreq:1:([1-9]\d*):([1-9]\d*):1:[01]$/.exec(flag);
      if (!match || match[2] !== targetGroup) continue;
      user = id(item.requester_uin);
    } else {
      // NapCat puts the applicant, NOT an inviter, in invitor_uin for join_requests.
      flag = item.flag != null ? id(item.flag) : id(item.request_id);
      if (!flag) continue;
      user = id(item.requester_uin ?? item.invitor_uin);
    }
    if (!user || seen.has(flag) || typeof item.message !== 'string') continue;
    seen.add(flag);
    requests.push({flag,groupId:targetGroup,userId:user,message:item.message,protocol:snow?'snowluma':'napcat'});
  }
  return requests;
}
export function resolveEndpoint(cfg) {
  const url = String(cfg?.snowluma?.httpUrl || 'http://127.0.0.1:3000').replace(/\/+$/,'');
  const parsed = new URL(url);
  if (!['http:','https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) throw Error('QQ HTTP 地址无效');
  return {url,token:String(cfg?.snowluma?.httpToken || cfg?.snowluma?.httpAccessToken || cfg?.snowluma?.accessToken || '')};
}
