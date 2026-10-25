import { randomBytes, createHash } from 'node:crypto';
import { createServer } from 'node:http';
const issuer = 'https://auth.openai.com';
const resource = 'https://api.openai.com/v1';
const directScope = 'chatgpt.tokens.use.direct';
const random = () => randomBytes(32).toString('base64url');

export async function validateOpenAIIdToken(sdk, token, clientId, nonce, previousSub = '', keyResolver) {
  const keys = keyResolver || sdk.createRemoteJWKSet(new URL(`${issuer}/.well-known/jwks.json`), { timeoutDuration:15000 });
  const { payload } = await sdk.jwtVerify(token, keys, { issuer, audience:clientId, algorithms:['RS256'], clockTolerance:30 });
  if (!payload.sub || (nonce && payload.nonce !== nonce) || (previousSub && payload.sub !== previousSub)) throw Error('身份验证未通过，请重新连接 ChatGPT');
  return payload;
}

export function createChatGPTOAuth({ sdk, deviceId, fetchImpl = globalThis.fetch, keyResolver }) {
  async function tokenRequest(body, signal) {
    const response = await fetchImpl(`${issuer}/api/accounts/oauth/token`, {
      method:'POST', headers:{'content-type':'application/x-www-form-urlencoded'}, body:new URLSearchParams(body), signal
    });
    if (!response.ok) throw Error(`ChatGPT 授权失败（${response.status}），请重新连接`);
    return response.json();
  }
  async function credential(data, clientId, nonce, previous, signal) {
    if (!data.access_token || !(data.refresh_token || previous?.refresh) || !Number.isFinite(data.expires_in) || data.expires_in <= 0) throw Error('ChatGPT 返回的授权信息不完整');
    const scopes = String(data.scope || previous?.scopes?.join(' ') || '').split(/\s+/);
    if (!scopes.includes(directScope)) throw Error('此账号未授予 ChatGPT 订阅模型权限，请重新连接');
    const idToken = data.id_token || previous?.idToken;
    if (!idToken) throw Error('ChatGPT 未返回身份凭据');
    const identity = data.id_token ? await validateOpenAIIdToken(sdk,idToken,clientId,nonce,previous?.sub || '',keyResolver) : previous;
    if (signal.aborted) throw Error('连接已取消');
    return { ...previous,type:'oauth',access:data.access_token,refresh:data.refresh_token || previous.refresh,
      expires:Date.now() + data.expires_in * 1000 - Math.min(180000,data.expires_in * 100),
      clientId,scopes,idToken,sub:identity.sub,email:identity.email || previous?.email || '' };
  }
  return {
    name:'ChatGPT Plus / Pro',isSubscription:true,
    async login(interaction, previous) {
      const state = random(), verifier = random(), nonce = random();
      let resolveCode, rejectCode;
      const codeResult = new Promise((resolve,reject) => { resolveCode=resolve; rejectCode=reject; });
      let callbackUri = '';
      const parse = input => {
        const url = new URL(input);
        const expected = new URL(callbackUri);
        if (url.origin !== expected.origin || url.pathname !== expected.pathname || url.searchParams.get('state') !== state) throw Error('授权返回地址或状态不匹配');
        if (url.searchParams.get('error')) throw Error('ChatGPT 授权已取消或未获准');
        const clientId = url.searchParams.get('client_id') || previous?.clientId;
        if (!clientId || clientId === 'dynamic_agent_client' || (previous?.clientId && clientId !== previous.clientId)) throw Error('ChatGPT 客户端注册信息不匹配');
        const code = url.searchParams.get('code'); if (!code) throw Error('授权返回缺少代码');
        return {code,clientId};
      };
      const server = createServer((req,res) => {
        try {
          const result = parse(new URL(req.url,callbackUri).toString());
          res.writeHead(200,{'content-type':'text/html; charset=utf-8','cache-control':'no-store'});
          res.end('<p>请返回 QQ Agent，等待账号验证完成。</p>'); resolveCode(result);
        } catch {
          res.writeHead(400,{'content-type':'text/plain; charset=utf-8'}); res.end('授权返回无效，请返回 QQ Agent 重试。');
          try {
            const denied = new URL(req.url,callbackUri);
            if (denied.origin === new URL(callbackUri).origin && denied.pathname === '/auth/callback' && denied.searchParams.get('state') === state && denied.searchParams.get('error')) rejectCode(Error('ChatGPT 授权未获准'));
          } catch { /* An invalid URL cannot end the process or the real login. */ }
        }
      });
      await new Promise((resolve,reject) => { server.once('error',reject); server.listen(0,'127.0.0.1',resolve); });
      callbackUri = `http://127.0.0.1:${server.address().port}/auth/callback`;
      const abort = () => rejectCode(Error('连接已取消'));
      interaction.signal.addEventListener('abort',abort,{once:true});
      try {
        interaction.signal.throwIfAborted();
        const params = new URLSearchParams({client_id:previous?.clientId || 'dynamic_agent_client',
          ext_agent_host_id:`urn:uuid:${deviceId}`,response_type:'code',redirect_uri:callbackUri,resource,
          scope:`openid profile email offline_access resource.invoke ${directScope}`,state,nonce,
          code_challenge:createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256'});
        if (!previous?.clientId) params.set('agent_name_hint','QQ Agent Android');
        if (previous?.idToken) params.set('id_token_hint',previous.idToken);
        interaction.notify({type:'auth_url',url:`${issuer}/api/accounts/authorize?${params}`,callbackUri});
        const manualAbort = new AbortController();
        const manual = interaction.prompt({type:'manual_code',signal:AbortSignal.any([interaction.signal,manualAbort.signal])}).then(parse);
        let result;
        try { result = await Promise.race([codeResult,manual]); } finally { manualAbort.abort(); }
        interaction.notify({type:'progress',stage:'换取授权凭据'});
        const data = await tokenRequest({grant_type:'authorization_code',client_id:result.clientId,code:result.code,
          code_verifier:verifier,redirect_uri:callbackUri,resource},interaction.signal);
        interaction.notify({type:'progress',stage:'校验账号身份'});
        return credential(data,result.clientId,nonce,previous,interaction.signal);
      } finally { interaction.signal.removeEventListener('abort',abort); server.close(); server.closeAllConnections(); }
    },
    async refresh(previous, signal) {
      const data = await tokenRequest({grant_type:'refresh_token',client_id:previous.clientId,refresh_token:previous.refresh,resource},signal);
      return credential(data,previous.clientId,'',previous,signal);
    },
    async toAuth(value) { return {apiKey:value.access}; }
  };
}
