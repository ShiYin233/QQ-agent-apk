export const subscriptionProviders = ['chatgpt-plus','google-ai-pro'];
export function subscriptionFailure(error, provider) {
  if(provider==='google-ai-pro' && error?.name==='GoogleSetupError')return error.message;
  const message=String(error?.message || '');const code=String(error?.cause?.code || error?.code || '');
  if(/requires setting the GOOGLE_CLOUD_PROJECT|Could not discover or provision a Google Cloud project/.test(message))return 'Google 已授权，但该账号要求指定 Cloud Project；当前个人订阅配置未能取得可用项目。';
  if(/invalid_grant|No authorization code/.test(message))return '登录授权已过期或返回代码无效，请重新连接。';
  if(/invalid_client|unauthorized_client/.test(message))return '官方服务拒绝当前 OAuth 客户端，请更新账号接入适配。';
  if(/OAuth state mismatch|状态不匹配|注册信息不匹配/.test(message))return '本次登录返回信息不匹配，请重新连接。';
  if(/No refresh token/.test(message))return 'Google 未授予离线授权，请重新连接并完成授权。';
  if(/fetch failed|ENOTFOUND|ECONNRESET|ETIMEDOUT|TIMEOUT|CERT_/.test(message+' '+code))return '手机后台访问官方服务失败，请确认 QQ Agent 也能使用访问 OpenAI / Google 的网络。';
  if(/loadCodeAssist failed|onboardUser failed/.test(message))return 'Google 登录已返回，但模型权限或项目开通检查失败，请检查订阅账号和网络。';
  if(/Token exchange failed|ChatGPT 授权失败/.test(message))return '官方登录已返回，但手机换取授权凭据失败，请重试连接。';
  if(/EADDRINUSE/.test(message+' '+code))return '本机登录回调端口被占用，请取消后重新连接。';
  if(code.startsWith('ERR_JWKS') || code.startsWith('ERR_JWT') || code.startsWith('ERR_JWS'))return 'ChatGPT 身份凭据校验未通过，请检查网络和手机时间后重新连接。';
  if(provider==='chatgpt-plus' && /订阅模型权限|身份验证未通过|授权信息不完整|未返回身份凭据/.test(message))return message;
  return '账号连接未完成，请检查网络和官方授权页面后重试。';
}
export const virtualEndpoint = provider => `https://subscription.qqagent.invalid/v1/${provider}`;
export function subscriptionRoute(address) {
  const url = new URL(address);
  if (url.hostname !== 'subscription.qqagent.invalid') return null;
  const match = /^\/v1\/(chatgpt-plus|google-ai-pro)\/chat\/completions$/.exec(url.pathname);
  if (url.protocol !== 'https:' || !match || url.port || url.username || url.password || url.search || url.hash) throw Error('订阅模型地址无效');
  return match[1];
}
export function subscriptionOptions(body, provider, model) {
  const options = {};
  if (Number.isFinite(body.max_tokens) && body.max_tokens > 0) options.maxTokens = Math.floor(body.max_tokens);
  if (Number.isFinite(body.temperature) && !model.reasoning) options.temperature = body.temperature;
  if (body.tool_choice !== undefined) {
    const choice = body.tool_choice;
    if (['auto','none','required'].includes(choice)) options.toolChoice = provider === 'google-ai-pro' && choice === 'required' ? 'any' : choice;
    else if (choice?.type === 'function' && choice.function?.name && body.tools?.some(t=>t.function?.name===choice.function.name)) {
      if (provider === 'chatgpt-plus') options.toolChoice = {type:'function',name:choice.function.name};
      else {
        options.toolChoice = 'any';
        options.onPayload = payload => { payload.request.toolConfig.functionCallingConfig.allowedFunctionNames = [choice.function.name];return payload; };
      }
    } else throw Error('订阅模型工具选择无效');
  }
  return options;
}
function contentParts(value) {
  if (typeof value === 'string') return value ? [{type:'text',text:value}] : [];
  if (value == null) return [];
  if (!Array.isArray(value)) throw Error('消息格式无效');
  return value.map(part => {
    if (part.type === 'text') return {type:'text',text:String(part.text || '')};
    if (part.type === 'image_url') {
      const match = /^data:(image\/[a-z0-9.+-]+);base64,([a-zA-Z0-9+/=\r\n]+)$/.exec(part.image_url?.url || '');
      if (!match || match[2].length > 24 * 1024 * 1024) throw Error('订阅模型需要已读取的图片，请使用图片或视频抽帧');
      return {type:'image',mimeType:match[1],data:match[2]};
    }
    throw Error('订阅模型暂不支持此消息类型，请使用视频抽帧');
  });
}
export function toSubscriptionContext(body, provider, model, now = Date.now(), restoreAssistant = () => undefined) {
  if (!Array.isArray(body.messages) || body.messages.length > 2000) throw Error('模型消息格式无效');
  const toolsById = new Map(); const messages = []; const systems = [];
  for (const message of body.messages) {
    if (message.role === 'system' || message.role === 'developer') { systems.push(contentParts(message.content).map(p=>p.text || '').join('\n')); continue; }
    if (message.role === 'user') messages.push({role:'user',content:contentParts(message.content),timestamp:now});
    else if (message.role === 'assistant') {
      const content = contentParts(message.content);
      for (const call of message.tool_calls || []) {
        const args = typeof call.function?.arguments === 'string' ? JSON.parse(call.function.arguments) : call.function?.arguments;
        if (!call.id || !call.function?.name || !args || typeof args !== 'object' || Array.isArray(args)) throw Error('工具调用参数无效');
        toolsById.set(call.id,call.function.name);
        content.push({type:'toolCall',id:call.id,name:call.function.name,arguments:args,...(call.thoughtSignature ? {thoughtSignature:call.thoughtSignature} : {})});
      }
      const origin = message.tool_calls?.[0]?.androidSubscriptionOrigin;
      messages.push(restoreAssistant(message) || {role:'assistant',content,api:provider === 'google-ai-pro' ? 'google-gemini-cli' : 'openai-responses',
        provider:provider === 'google-ai-pro' ? 'google-gemini-cli' : 'openai',model,timestamp:now,
        ...(origin && typeof origin.provider==='string' && typeof origin.model==='string' ? {provider:origin.provider,model:origin.model} : {}),
        stopReason:message.tool_calls?.length ? 'toolUse' : 'stop',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}});
    } else if (message.role === 'tool') {
      const name = toolsById.get(message.tool_call_id) || message.name;
      if (!name) throw Error('工具结果缺少对应调用');
      messages.push({role:'toolResult',toolCallId:message.tool_call_id,toolName:name,content:contentParts(message.content),isError:message.isError === true,timestamp:now});
    } else throw Error('消息角色无效');
  }
  const tools = (body.tools || []).map(t => {
    if (t.type !== 'function' || !t.function?.name || !t.function.parameters) throw Error('订阅模型仅支持函数工具');
    return {name:t.function.name,description:t.function.description || '',parameters:t.function.parameters};
  });
  return {systemPrompt:systems.join('\n\n'),messages,tools};
}
export function fromSubscriptionMessage(message) {
  if (['error','aborted'].includes(message.stopReason)) throw Error('订阅模型请求失败，请检查账号权限、额度与网络后重试');
  const calls = message.content.filter(p=>p.type === 'toolCall').map(p => ({id:p.id,type:'function',
    function:{name:p.name,arguments:JSON.stringify(p.arguments)},androidSubscriptionOrigin:{provider:message.provider,model:message.model},...(p.thoughtSignature ? {thoughtSignature:p.thoughtSignature} : {})}));
  const text = message.content.filter(p=>p.type === 'text').map(p=>p.text).join('');
  const reasoning = message.content.filter(p=>p.type === 'thinking').map(p=>p.thinking).join('');
  const u = message.usage || {};
  return {object:'chat.completion',model:message.model,choices:[{index:0,finish_reason:calls.length ? 'tool_calls' : message.stopReason === 'length' ? 'length' : 'stop',
    message:{role:'assistant',content:text || null,...(reasoning ? {reasoning_content:reasoning} : {}),...(calls.length ? {tool_calls:calls} : {})}}],
    usage:{prompt_tokens:(u.input || 0)+(u.cacheRead || 0)+(u.cacheWrite || 0),completion_tokens:u.output || 0,total_tokens:u.totalTokens || (u.input || 0)+(u.output || 0)+(u.cacheRead || 0)+(u.cacheWrite || 0),
      prompt_tokens_details:{cached_tokens:u.cacheRead || 0}}};
}
