import test from 'node:test';
import assert from 'node:assert/strict';
import * as sdk from '../app/src/main/assets/runtime/subscription-sdk.mjs';
import {resolveGoogleProject,validateGoogleProject,GoogleSetupError} from '../app/src/main/assets/runtime/android-subscription-google.mjs';
import {SubscriptionManager,BridgeCredentialStore} from '../app/src/main/assets/runtime/android-subscriptions.mjs';

function fixture(initial,customSdk=sdk) {
  let credential=initial;const credentials=new BridgeCredentialStore({baseUrl:'http://127.0.0.1:12345',token:'a'.repeat(64)},async(_,options)=>{
    if(options.method==='PUT')credential=JSON.parse(options.body);
    if(options.method==='DELETE')credential=undefined;
    return Response.json(options.method==='GET'?{credential:credential || null}:{ok:true});
  });
  let config={api:{provider:'',model:'fixture'}};
  const m=new SubscriptionManager({sdk:customSdk,credentials,deviceId:'00000000-0000-4000-8000-000000000000',manualApi:config.api,getConfig:()=>config,updateConfig:p=>{config={...config,...p};},pause:()=>{}});
  return {m,credentials,get credential(){return credential;}};
}
async function waitFor(task,predicate) {
  for(let i=0;i<100;i++){if(predicate())return;await new Promise(r=>setTimeout(r,10));}
  assert.fail('Operation did not finish: '+task.status);
}
test('non-free default onboarding is attempted without a user project',async()=>{
  const calls=[];
  const result=await resolveGoogleProject('fixture-private',{fetchImpl:async(url,options)=>{
    calls.push({url,body:JSON.parse(options.body)});
    return Response.json(calls.length===1?{allowedTiers:[{id:'standard-tier',isDefault:true}]}:{done:true,response:{cloudaicompanionProject:{id:'managed-fixture'}}});
  }});
  assert.equal(result,'managed-fixture');assert.equal(calls.length,2);
  assert.equal(calls[1].body.tierId,'standard-tier');assert.equal(calls[1].body.cloudaicompanionProject,undefined);
});
test('a free managed tier excludes a supplied project during onboarding',async()=>{
  let onboard;
  await resolveGoogleProject('fixture-private',{projectId:'fixture-project',fetchImpl:async(url,options)=>{
    if(url.endsWith(':loadCodeAssist'))return Response.json({allowedTiers:[{id:'free-tier',isDefault:true}]});
    onboard=JSON.parse(options.body);return Response.json({done:true,response:{cloudaicompanionProject:{id:'managed-fixture'}}});
  }});
  assert.equal(onboard.cloudaicompanionProject,undefined);assert.equal(onboard.metadata.duetProject,undefined);
});
test('project requirement and account ineligibility are distinct and omit server prose',async()=>{
  for(const [response,code] of [[{currentTier:{id:'standard-tier'}},'PROJECT_REQUIRED'],[{currentTier:{id:'standard-tier'},ineligibleTiers:[{reasonCode:'UNSUPPORTED_LOCATION',reasonMessage:'fixture-private-secret'}]},'INELIGIBLE']]){
    await assert.rejects(resolveGoogleProject('private',{fetchImpl:async()=>Response.json(response)}),e=>{
      assert.equal(e.setupCode,code);assert.ok(!JSON.stringify(e).includes('fixture-private-secret'));return true;
    });
  }
  assert.throws(()=>validateGoogleProject('12345678'),GoogleSetupError);assert.throws(()=>validateGoogleProject('https://example.invalid'),GoogleSetupError);
});
test('Google validation only exposes an official account verification link',async()=>{
  for(const url of ['https://accounts.google.com/verify','https://attacker.invalid/verify','https://accounts.google.com.attacker.invalid','https://user:private@accounts.google.com/']) {
    await assert.rejects(resolveGoogleProject('private',{fetchImpl:async()=>Response.json({ineligibleTiers:[{reasonCode:'VALIDATION_REQUIRED',validationUrl:url}]})}),e=>{
      assert.equal(e.setupCode,'VALIDATION_REQUIRED');assert.equal(e.diagnostic.validationUrl,url==='https://accounts.google.com/verify'?url:'');return true;
    });
  }
});
test('restart retains authorization while blocking projectless inference and selection',async()=>{
  const f=fixture({type:'oauth',access:'fixture-private',refresh:'fixture-refresh',expires:Date.now()+60000,projectId:'',setupPending:true,setupCode:'PROJECT_REQUIRED',setupError:'项目待配置'});
  await f.m.initialise();const status=f.m.status().providers.find(p=>p.id==='google-ai-pro');
  assert.equal(status.authorized,true);assert.equal(status.connected,false);assert.equal(status.setupCode,'PROJECT_REQUIRED');
  assert.ok(!JSON.stringify(f.m.status()).includes('fixture-private'));
  await assert.rejects(f.m.select('google-ai-pro','gemini-2.5-flash'));await assert.rejects(f.m.complete('google-ai-pro',{}));
});
test('retry uses saved Google authorization and no additional OAuth login',async()=>{
  const f=fixture({type:'oauth',access:'fixture-private',refresh:'fixture-refresh',expires:Date.now()+60000,projectId:'',setupPending:true});await f.m.initialise();
  const old=globalThis.fetch;let calls=0;
  globalThis.fetch=async url=>{assert.ok(url.endsWith(':loadCodeAssist'));calls++;return Response.json({currentTier:{id:'standard-tier'},cloudaicompanionProject:'managed-fixture'});};
  try {f.m.begin('google-ai-pro',{resume:true});await waitFor(f.m.pending,()=>f.m.pending.status==='connected');
    assert.equal(calls,1);assert.equal(f.credential.access,'fixture-private');assert.equal(f.credential.setupPending,false);assert.equal(f.credential.projectId,'managed-fixture');
  }finally{globalThis.fetch=old;}
});
test('project setup failure preserves a newly issued grant and restart diagnostic',async()=>{
  const custom={...sdk,loginGeminiCli:async(_auth,_progress,_manual,save,setup)=>{
    await save({type:'oauth',access:'fixture-new-private',refresh:'fixture-new-refresh',expires:Date.now()+60000,projectId:'',setupPending:true});
    await setup('fixture-new-private');assert.fail('Setup should reject');
  }};
  const f=fixture(undefined,custom);await f.m.initialise();const old=globalThis.fetch;
  globalThis.fetch=async()=>Response.json({currentTier:{id:'standard-tier'}});
  try {f.m.begin('google-ai-pro');await waitFor(f.m.pending,()=>f.credential?.setupCode==='PROJECT_REQUIRED');
    assert.equal(f.credential.refresh,'fixture-new-refresh');assert.equal(f.m.known.get('google-ai-pro').authorized,true);
    const restarted=fixture(f.credential);await restarted.m.initialise();assert.equal(restarted.m.known.get('google-ai-pro').setupCode,'PROJECT_REQUIRED');
  }finally{globalThis.fetch=old;}
});
test('the bundled Google OAuth callback saves the grant before failing project setup',async()=>{
  const old=globalThis.fetch;const ready=Promise.withResolvers();let auth,saved;
  globalThis.fetch=async(url,options)=>{
    if(url==='https://oauth2.googleapis.com/token')return Response.json({access_token:'fixture-private',refresh_token:'fixture-refresh',expires_in:3600});
    if(String(url).includes('userinfo'))return Response.json({email:'fixture@example.invalid'});
    return old(url,options);
  };
  try {
    const operation=sdk.loginGeminiCli(event=>{auth=new URL(event.url);ready.resolve();},undefined,undefined,async grant=>{saved=grant;},async()=>{
      assert.equal(saved.refresh,'fixture-refresh');throw new GoogleSetupError('PROJECT_REQUIRED');
    });
    const rejected=assert.rejects(operation,GoogleSetupError);await ready.promise;
    const callback=new URL(auth.searchParams.get('redirect_uri'));callback.searchParams.set('state',auth.searchParams.get('state'));callback.searchParams.set('code','fixture-code');
    assert.equal((await old(callback)).status,200);await rejected;assert.equal(saved.setupPending,true);assert.equal(saved.projectId,'');
  }finally{globalThis.fetch=old;}
});
test('disconnect during setup cannot restore a deleted Google grant',async()=>{
  const f=fixture({type:'oauth',access:'fixture-private',refresh:'fixture-refresh',expires:Date.now()+60000,setupPending:true});await f.m.initialise();
  const old=globalThis.fetch,start=Promise.withResolvers(),release=Promise.withResolvers();
  globalThis.fetch=async()=>{start.resolve();await release.promise;return Response.json({currentTier:{id:'standard-tier'},cloudaicompanionProject:'managed-fixture'});};
  try {f.m.begin('google-ai-pro',{resume:true});await start.promise;await f.m.disconnect('google-ai-pro');release.resolve();
    await waitFor(f.m.pending,()=>f.m.pending.status!=='waiting');assert.equal(f.credential,undefined);assert.equal(f.m.known.get('google-ai-pro').connected,false);
  }finally{globalThis.fetch=old;}
});
