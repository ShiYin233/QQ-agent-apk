// Offline probe: only synthetic credentials in memory; every HTTPS fetch is mocked.
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign} from 'node:crypto';
import * as sdk from './subscription-sdk.mjs';
import {validateOpenAIIdToken} from './android-subscription-oauth.mjs';
let calls=0;
globalThis.fetch=async(input,options)=>{
  const request=input instanceof Request ? input : new Request(input,options);calls++;
  const body=await request.json();
  if(request.url==='https://api.openai.com/v1/responses'){
    assert.equal(request.headers.get('authorization'),'Bearer fixture-access');assert.equal(body.store,false);assert.equal(body.stream,true);
    const item={type:'message',id:'msg_fixture',role:'assistant',content:[{type:'output_text',text:'pong',annotations:[]}],status:'completed'};
    const events=[{type:'response.created',response:{id:'resp_fixture'}},{type:'response.output_item.added',output_index:0,item},{type:'response.output_item.done',output_index:0,item},{type:'response.completed',response:{id:'resp_fixture',status:'completed',output:[item],usage:{input_tokens:12,output_tokens:3,total_tokens:15}}}];
    return new Response(events.map(event=>'event: '+event.type+'\ndata: '+JSON.stringify(event)+'\n\n').join(''),{headers:{'content-type':'text/event-stream'}});
  }
  assert.ok(request.url.startsWith('https://cloudcode-pa.googleapis.com/'));assert.equal(request.headers.get('authorization'),'Bearer fixture-access');
  return new Response('data: '+JSON.stringify({response:{candidates:[{content:{parts:[{text:'pong'}]},finishReason:'STOP'}],usageMetadata:{promptTokenCount:12,candidatesTokenCount:3,totalTokenCount:15}}})+'\n\n',{headers:{'content-type':'text/event-stream'}});
};
const pair=generateKeyPairSync('rsa',{modulusLength:2048});
const header=Buffer.from(JSON.stringify({alg:'RS256'})).toString('base64url');
const payload=Buffer.from(JSON.stringify({iss:'https://auth.openai.com',aud:'fixture-client',sub:'fixture-user',nonce:'fixture-nonce',exp:Math.floor(Date.now()/1000)+300})).toString('base64url');
const token=header+'.'+payload+'.'+sign('RSA-SHA256',Buffer.from(header+'.'+payload),pair.privateKey).toString('base64url');
assert.equal((await validateOpenAIIdToken(sdk,token,'fixture-client','fixture-nonce','',async()=>pair.publicKey)).sub,'fixture-user');
await assert.rejects(validateOpenAIIdToken(sdk,token,'wrong-client','fixture-nonce','',async()=>pair.publicKey));
const credential={type:'oauth',access:'fixture-access',refresh:'fixture-refresh',expires:Date.now()+3600000};
const credentials={read:async()=>credential,list:async()=>[],modify:async(_,fn)=>await fn(credential) || credential};
const models=sdk.createModels({credentials,authContext:{env:async()=>undefined,fileExists:async()=>false}});
const provider=sdk.openaiProvider();provider.auth={oauth:{toAuth:async c=>({apiKey:c.access}),refresh:async()=>{throw Error('Unexpected fixture refresh');}}};models.setProvider(provider);
const context={messages:[{role:'user',content:[{type:'text',text:'ping'}],timestamp:Date.now()}]};
const openai=await models.complete(models.getModels('openai').find(m=>m.id==='gpt-5-mini'),context);
assert.equal(openai.stopReason,'stop');assert.equal(openai.content[0].text,'pong');
const google=await sdk.streamGoogleGeminiCli(sdk.legacyModels('google-gemini-cli').find(m=>m.id==='gemini-2.5-flash'),context,{apiKey:JSON.stringify({token:'fixture-access',projectId:'fixture-project'})}).result();
assert.equal(google.stopReason,'stop');assert.equal(google.content[0].text,'pong');assert.equal(calls,2);
console.log(JSON.stringify({passed:true,nodeVersion:process.version,jwtSignatureAndAudienceVerified:true,openaiBundledStreamFixturePassed:true,googleBundledStreamFixturePassed:true,networkCalls:0,qqMessagesSent:0,usesRealCredentials:false}));
