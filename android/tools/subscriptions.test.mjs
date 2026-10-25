import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPair,SignJWT} from '../subscription-runtime/node_modules/jose/dist/webapi/index.js';
import * as sdk from '../app/src/main/assets/runtime/subscription-sdk.mjs';
import {validateOpenAIIdToken,createChatGPTOAuth} from '../app/src/main/assets/runtime/android-subscription-oauth.mjs';
import {subscriptionRoute,toSubscriptionContext,fromSubscriptionMessage,subscriptionOptions,subscriptionFailure} from '../app/src/main/assets/runtime/android-subscription-adapter.mjs';
import {BridgeCredentialStore,SubscriptionManager} from '../app/src/main/assets/runtime/android-subscriptions.mjs';

const keys=await generateKeyPair('RS256');
const sign=(overrides={})=>new SignJWT({nonce:'expected-nonce',email:'fixture@example.invalid',...overrides}).setProtectedHeader({alg:'RS256'}).setIssuer('https://auth.openai.com').setAudience('issued-client').setSubject('fixture-user').setExpirationTime('5m').sign(keys.privateKey);
const resolver=async()=>keys.publicKey;
const usage={input:12,output:3,cacheRead:4,cacheWrite:0,totalTokens:19,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}};
function store(initial={}) {
  const values=new Map(Object.entries(initial));let puts=0;
  const bridge=new BridgeCredentialStore({baseUrl:'http://127.0.0.1:12345',token:'a'.repeat(64)},async(url,options)=>{
    const id=url.split('/').at(-1);
    if(options.method==='PUT'){values.set(id,JSON.parse(options.body));puts++;}
    if(options.method==='DELETE')values.delete(id);
    return Response.json(options.method==='GET'?{credential:values.get(id)||null}:{ok:true});
  });return {bridge,values,get puts(){return puts;}};
}
test('subscription requests are intercepted only at exact virtual endpoints',()=>{
  assert.equal(subscriptionRoute('https://subscription.qqagent.invalid/v1/chatgpt-plus/chat/completions'),'chatgpt-plus');
  assert.equal(subscriptionRoute('https://api.example.invalid/v1/chat/completions'),null);
  for(const url of ['http://subscription.qqagent.invalid/v1/chatgpt-plus/chat/completions','https://subscription.qqagent.invalid/v1/unknown/chat/completions','https://subscription.qqagent.invalid:123/v1/chatgpt-plus/chat/completions','https://subscription.qqagent.invalid/v1/chatgpt-plus/chat/completions?token=x'])assert.throws(()=>subscriptionRoute(url));
});
test('tool calls round trip through the original QQ chat format with Google signatures',()=>{
  const reply=fromSubscriptionMessage({model:'gemini-2.5-flash',stopReason:'toolUse',usage,content:[{type:'toolCall',id:'call-1',name:'finish',arguments:{reply:'pong'},thoughtSignature:'fixture-signature'}]});
  const ctx=toSubscriptionContext({messages:[{role:'system',content:'role'},{role:'developer',content:'rules'},{role:'user',content:'ping'},reply.choices[0].message,{role:'tool',tool_call_id:'call-1',content:'ok'}],tools:[{type:'function',function:{name:'finish',parameters:{type:'object'}}}]},'google-ai-pro','gemini-2.5-flash',100);
  assert.equal(ctx.systemPrompt,'role\n\nrules');assert.equal(ctx.messages[1].content[0].thoughtSignature,'fixture-signature');
  assert.deepEqual(ctx.messages[1].content[0].arguments,{reply:'pong'});assert.equal(ctx.messages[2].toolName,'finish');
  assert.equal(reply.usage.prompt_tokens,16);assert.equal(reply.usage.total_tokens,19);assert.equal(reply.choices[0].finish_reason,'tool_calls');
});
test('images use loaded data and unsupported video never silently loses its content',()=>{
  const body={messages:[{role:'user',content:[{type:'image_url',image_url:{url:'data:image/png;base64,YWJj'}}]}]};
  assert.equal(toSubscriptionContext(body,'chatgpt-plus','gpt-5-mini').messages[0].content[0].mimeType,'image/png');
  assert.throws(()=>toSubscriptionContext({messages:[{role:'user',content:[{type:'video_url',video_url:{url:'file://x'}}]}]},'chatgpt-plus','gpt-5-mini'));
});
test('forced QQ finish tools retain their selection in both transports',()=>{
  const body={tools:[{type:'function',function:{name:'finish'}}],tool_choice:{type:'function',function:{name:'finish'}},max_tokens:128,temperature:0.8};
  const openai=subscriptionOptions(body,'chatgpt-plus',{reasoning:true});assert.deepEqual(openai.toolChoice,{type:'function',name:'finish'});assert.equal(openai.temperature,undefined);
  const google=subscriptionOptions(body,'google-ai-pro',{});assert.equal(google.toolChoice,'any');
  assert.deepEqual(google.onPayload({request:{toolConfig:{functionCallingConfig:{mode:'ANY'}}}}).request.toolConfig.functionCallingConfig.allowedFunctionNames,['finish']);
});
test('OAuth verifies RSA signature, identity, issuer, audience and nonce',async()=>{
  const token=await sign();assert.equal((await validateOpenAIIdToken(sdk,token,'issued-client','expected-nonce','fixture-user',resolver)).sub,'fixture-user');
  for(const [client,nonce,sub] of [['other','expected-nonce','fixture-user'],['issued-client','wrong','fixture-user'],['issued-client','expected-nonce','other']])await assert.rejects(validateOpenAIIdToken(sdk,token,client,nonce,sub,resolver));
  const wrongIssuer=await new SignJWT({nonce:'expected-nonce'}).setProtectedHeader({alg:'RS256'}).setIssuer('https://wrong.invalid').setAudience('issued-client').setSubject('fixture-user').setExpirationTime('5m').sign(keys.privateKey);
  await assert.rejects(validateOpenAIIdToken(sdk,wrongIssuer,'issued-client','expected-nonce','',resolver));
  const wrong=await generateKeyPair('RS256');await assert.rejects(validateOpenAIIdToken(sdk,token,'issued-client','expected-nonce','',async()=>wrong.publicKey));
});
async function oauthFixture(previous,scope='openid chatgpt.tokens.use.direct'){
  let notice,form;
  const controller=new AbortController();const ready=Promise.withResolvers();
  const oauth=createChatGPTOAuth({sdk,deviceId:'00000000-0000-4000-8000-000000000000',keyResolver:resolver,fetchImpl:async(url,options)=>{
    assert.equal(url,'https://auth.openai.com/api/accounts/oauth/token');form=options.body;
    const nonce=new URL(notice.url).searchParams.get('nonce');
    return Response.json({access_token:'fixture-access',refresh_token:'fixture-refresh',expires_in:3600,scope,id_token:await sign({nonce})});
  }});
  const operation=oauth.login({signal:controller.signal,notify:event=>{if(event.type==='auth_url'){notice=event;ready.resolve();}},prompt:({signal})=>new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(Error('cancelled')),{once:true}))},previous);
  await ready.promise;
  const auth=new URL(notice.url),callback=new URL(notice.callbackUri);
  callback.searchParams.set('code','fixture-code');callback.searchParams.set('state',auth.searchParams.get('state'));callback.searchParams.set('client_id','issued-client');
  return {operation,auth,callback,controller,get form(){return form;}};
}
test('first sign in registers the app, rejects forged state, then binds the issued client',async()=>{
  const fixture=await oauthFixture();assert.equal(fixture.auth.searchParams.get('client_id'),'dynamic_agent_client');assert.equal(fixture.auth.searchParams.get('agent_name_hint'),'QQ Agent Android');
  assert.equal(new URL(fixture.auth.searchParams.get('redirect_uri')).hostname,'127.0.0.1');
  const bad=new URL(fixture.callback);bad.searchParams.set('state','forged');assert.equal((await fetch(bad)).status,400);
  assert.equal((await fetch(fixture.callback)).status,200);const credential=await fixture.operation;
  assert.equal(credential.clientId,'issued-client');assert.equal(credential.sub,'fixture-user');assert.equal(fixture.form.get('client_id'),'issued-client');
});
test('returning sign in reuses registration and refuses missing subscription scope',async()=>{
  const previous={clientId:'issued-client',sub:'fixture-user',idToken:'fixture-old-id-token',refresh:'fixture-old-refresh'};
  const fixture=await oauthFixture(previous,'openid profile');assert.equal(fixture.auth.searchParams.get('client_id'),'issued-client');assert.equal(fixture.auth.searchParams.has('agent_name_hint'),false);
  const rejected=assert.rejects(fixture.operation,/订阅模型权限/);await fetch(fixture.callback);await rejected;
});
test('a valid-state refusal closes authorization without token exchange',async()=>{
  const fixture=await oauthFixture();const refused=new URL(fixture.callback);refused.searchParams.delete('code');refused.searchParams.set('error','access_denied');
  const rejected=assert.rejects(fixture.operation,/未获准/);await fetch(refused);await rejected;assert.equal(fixture.form,undefined);
});
test('simultaneous refresh persists rotation once and later requests see fresh credentials',async()=>{
  const s=store({'chatgpt-plus':{refresh:'old',expires:0}});let refreshed=0;
  const refresh=current=>{if(current.expires>Date.now())return undefined;refreshed++;return {...current,refresh:'rotated',expires:Date.now()+60000};};
  const [a,b]=await Promise.all([s.bridge.modify('chatgpt-plus',refresh),s.bridge.modify('chatgpt-plus',refresh)]);
  assert.equal(refreshed,1);assert.equal(s.puts,1);assert.equal(a.refresh,'rotated');assert.equal(b.refresh,'rotated');
});
test('disconnect waits for pending refresh and cancellation never persists a credential',async()=>{
  const s=store({'google-ai-pro':{refresh:'old'}});const start=Promise.withResolvers(),release=Promise.withResolvers();
  const pending=s.bridge.modify('google-ai-pro',async()=>{start.resolve();await release.promise;return {refresh:'rotated'};});await start.promise;
  const deletion=s.bridge.delete('google-ai-pro');release.resolve();await Promise.all([pending,deletion]);assert.equal(s.values.has('google-ai-pro'),false);
  const controller=new AbortController();controller.abort();await assert.rejects(s.bridge.modify('chatgpt-plus',async()=>({access:'must-not-save'}),{signal:controller.signal}));assert.equal(s.values.has('chatgpt-plus'),false);
});
function manager(initial={}){
  if(initial['chatgpt-plus'] && !initial['chatgpt-plus'].modelCatalog)initial['chatgpt-plus'].modelCatalog=[{slug:'gpt-5-mini',display_name:'Fixture GPT',visibility:'list'}];
  const s=store(initial);let config={api:{provider:'',baseUrl:'https://api.example.invalid/v1',apiKey:'fixture-manual',model:'old-model'}};let pauses=0;
  const m=new SubscriptionManager({sdk,credentials:s.bridge,deviceId:'00000000-0000-4000-8000-000000000000',manualApi:{...config.api},getConfig:()=>config,updateConfig:patch=>{config={...config,...patch};},pause:()=>pauses++});
  return {m,s,get config(){return config;},get pauses(){return pauses;}};
}
test('model selection pauses replies, never discloses credentials and restores the original API',async()=>{
  const fixture=manager({'chatgpt-plus':{type:'oauth',access:'fixture-private-access',refresh:'fixture-private-refresh',email:'fixture@example.invalid',expires:Date.now()+60000}});await fixture.m.initialise();
  await assert.rejects(fixture.m.select('google-ai-pro','gemini-2.5-flash'));await fixture.m.select('chatgpt-plus','gpt-5-mini');
  assert.equal(fixture.config.androidSubscriptionReady,true);assert.equal(fixture.pauses,1);
  assert.ok(!JSON.stringify(fixture.m.status()).includes('fixture-private'));await fixture.m.disconnect('chatgpt-plus');assert.equal(fixture.config.androidSubscriptionReady,false);
  await fixture.m.manual();assert.equal(fixture.config.api.apiKey,'fixture-manual');assert.equal(fixture.config.api.model,'old-model');
});
test('the bundled Google provider handles actual SSE fixtures and QQ tool followups',async()=>{
  const fixture=manager({'google-ai-pro':{type:'oauth',access:'fixture-access',refresh:'fixture-refresh',projectId:'fixture-project',expires:Date.now()+60000}});await fixture.m.initialise();
  const oldFetch=globalThis.fetch;const requests=[];
  globalThis.fetch=async(url,options)=>{assert.ok(String(url).startsWith('https://cloudcode-pa.googleapis.com/'));requests.push(JSON.parse(options.body));
    const parts=requests.length===1?[{text:'reasoning',thought:true,thoughtSignature:'YWJjZA=='},{functionCall:{name:'finish',args:{reply:'pong'}},thoughtSignature:'YWJjZA=='}]:[{text:'pong'}];
    return new Response('data: '+JSON.stringify({response:{candidates:[{content:{parts},finishReason:'STOP'}],usageMetadata:{promptTokenCount:12,candidatesTokenCount:3,totalTokenCount:15}}})+'\n\n',{headers:{'content-type':'text/event-stream'}});
  };
  try{
    const tools=[{type:'function',function:{name:'finish',parameters:{type:'object',properties:{reply:{type:'string'}}}}}];const messages=[{role:'user',content:'ping'}];
    const first=await fixture.m.complete('google-ai-pro',{model:'gemini-2.5-flash',messages,tools,tool_choice:'required'});
    assert.equal(first.choices[0].message.tool_calls[0].function.name,'finish');assert.equal(requests[0].request.toolConfig.functionCallingConfig.mode,'ANY');
    messages.push(first.choices[0].message,{role:'tool',tool_call_id:first.choices[0].message.tool_calls[0].id,content:'ok'});
    const next=await fixture.m.complete('google-ai-pro',{model:'gemini-2.5-flash',messages,tools});assert.equal(next.choices[0].message.content,'pong');
    assert.equal(requests[1].request.contents[1].parts[0].thought,true);
    assert.equal(requests[1].request.contents[1].parts[1].thoughtSignature,'YWJjZA==');assert.equal(requests[1].request.contents[2].parts[0].functionResponse.name,'finish');
  }finally{globalThis.fetch=oldFetch;}
});

test('the bundled OpenAI Responses provider uses the subscription token and restores signed tool history',async()=>{
  const fixture=manager({'chatgpt-plus':{type:'oauth',access:'fixture-access',refresh:'fixture-refresh',expires:Date.now()+3600000,clientId:'issued-client',sub:'fixture-user'}});await fixture.m.initialise();
  const oldFetch=globalThis.fetch,requests=[];
  globalThis.fetch=async(input,options)=>{
    const req=input instanceof Request?input:new Request(input,options);
    assert.equal(req.url,'https://api.openai.com/v1/responses');assert.equal(req.headers.get('authorization'),'Bearer fixture-access');
    const body=await req.json();requests.push(body);assert.equal(body.store,false);
    const items=requests.length===1?[{type:'reasoning',id:'rs_fixture',summary:[{type:'summary_text',text:'fixture thought'}],encrypted_content:'fixture-encrypted'},{type:'function_call',id:'fc_fixture',call_id:'call_fixture',name:'finish',arguments:'{"reply":"pong"}',status:'completed'}]:[{type:'message',id:'msg_fixture',role:'assistant',content:[{type:'output_text',text:'pong',annotations:[]}],status:'completed'}];
    const events=[{type:'response.created',response:{id:'resp_fixture'}}];
    items.forEach((item,index)=>events.push({type:'response.output_item.added',output_index:index,item},{type:'response.output_item.done',output_index:index,item}));
    events.push({type:'response.completed',response:{id:'resp_fixture',status:'completed',output:items,usage:{input_tokens:12,output_tokens:3,total_tokens:15}}});
    return new Response(events.map(event=>'event: '+event.type+'\ndata: '+JSON.stringify(event)+'\n\n').join(''),{headers:{'content-type':'text/event-stream'}});
  };
  try{
    const tools=[{type:'function',function:{name:'finish',parameters:{type:'object'}}}],messages=[{role:'user',content:'ping'}];
    const first=await fixture.m.complete('chatgpt-plus',{model:'gpt-5-mini',messages,tools,tool_choice:{type:'function',function:{name:'finish'}}});assert.equal(first.choices[0].message.tool_calls[0].function.name,'finish');
    assert.deepEqual(requests[0].tool_choice,{type:'function',name:'finish'});
    messages.push(first.choices[0].message,{role:'tool',tool_call_id:first.choices[0].message.tool_calls[0].id,content:'ok'});
    const next=await fixture.m.complete('chatgpt-plus',{model:'gpt-5-mini',messages,tools});assert.equal(next.choices[0].message.content,'pong');
    assert.ok(requests[1].input.some(item=>item.type==='reasoning' && item.encrypted_content==='fixture-encrypted'));
    assert.ok(requests[1].input.some(item=>item.type==='function_call_output' && item.call_id==='call_fixture'));
  }finally{globalThis.fetch=oldFetch;}
});

test('cancelling an inference retains rotated subscription credentials for both providers',async()=>{
  for(const provider of ['chatgpt-plus','google-ai-pro']) {
    const fixture=manager({[provider]:{type:'oauth',access:'old-access',refresh:'old-refresh',expires:0,projectId:'fixture-project'}});await fixture.m.initialise();
    const started=Promise.withResolvers(),release=Promise.withResolvers(),controller=new AbortController();
    const refresh=async()=>{started.resolve();await release.promise;return {type:'oauth',access:'rotated-access',refresh:'rotated-refresh',expires:Date.now()+3600000,projectId:'fixture-project'};};
    if(provider==='chatgpt-plus')fixture.m.oauth.refresh=refresh;
    else fixture.m.sdk={...sdk,refreshGoogleCloudToken:refresh};
    const operation=fixture.m.complete(provider,{model:provider==='chatgpt-plus'?'gpt-5-mini':'gemini-2.5-flash',messages:[{role:'user',content:'ping'}]},controller.signal);
    const rejected=assert.rejects(operation);await started.promise;controller.abort();release.resolve();await rejected;
    for(let i=0;i<20 && fixture.s.values.get(provider).refresh!=='rotated-refresh';i++)await new Promise(resolve=>setTimeout(resolve,5));
    assert.equal(fixture.s.values.get(provider).refresh,'rotated-refresh');
  }
});

test('ChatGPT choices come from the account catalog, including newly added models and server ordering',async()=>{
  const fixture=manager({'chatgpt-plus':{type:'oauth',access:'fixture-access',refresh:'fixture-refresh',expires:Date.now()+3600000}});await fixture.m.initialise();
  const oldFetch=globalThis.fetch;
  globalThis.fetch=async(url,options)=>{assert.equal(url,'https://api.openai.com/v1/models');assert.equal(options.headers.authorization,'Bearer fixture-access');return Response.json({models:[{slug:'gpt-6.1-sol',display_name:'GPT Latest Fixture',visibility:'list'},{slug:'hidden-model',display_name:'Hidden Fixture',visibility:'hidden'},{slug:'gpt-5-mini',display_name:'Mini Fixture',visibility:'list'}]});};
  try{await fixture.m.refreshModels('chatgpt-plus');assert.deepEqual(fixture.m.catalog('chatgpt-plus').map(m=>m.id),['gpt-6.1-sol','gpt-5-mini']);assert.equal(fixture.m.catalog('chatgpt-plus')[0].name,'GPT Latest Fixture');assert.equal(fixture.s.values.get('chatgpt-plus').modelCatalog.length,2);}
  finally{globalThis.fetch=oldFetch;}
});

test('an old model-list response cannot reconnect a disconnected account or repopulate its catalog',async()=>{
  const fixture=manager({'chatgpt-plus':{type:'oauth',access:'fixture-access',refresh:'fixture-refresh',expires:Date.now()+3600000}});await fixture.m.initialise();
  const oldFetch=globalThis.fetch,started=Promise.withResolvers(),release=Promise.withResolvers();
  globalThis.fetch=async()=>{started.resolve();await release.promise;return Response.json({models:[{slug:'gpt-5-mini',display_name:'Old account fixture',visibility:'list'}]});};
  try{
    const operation=fixture.m.refreshModels('chatgpt-plus');const rejected=assert.rejects(operation);await started.promise;
    await fixture.m.disconnect('chatgpt-plus');release.resolve();await rejected;
    assert.equal(fixture.m.catalog('chatgpt-plus').length,0);assert.equal(fixture.s.values.has('chatgpt-plus'),false);
  }finally{globalThis.fetch=oldFetch;}
});

test('login errors explain the failed step without copying upstream tokens or response bodies',()=>{
  const cases=[['fetch failed','手机后台'],['loadCodeAssist failed: 403 fixture-private-secret','模型权限'],['This account requires setting the GOOGLE_CLOUD_PROJECT environment variable fixture-private-secret','Cloud Project'],['Token exchange failed: invalid_grant fixture-private-secret','已过期']];
  for(const [message,expected] of cases){const result=subscriptionFailure(Error(message),'google-ai-pro');assert.ok(result.includes(expected));assert.ok(!result.includes('fixture-private-secret'));}
});
