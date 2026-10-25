import { AsyncLocalStorage } from 'node:async_hooks';
import { randomUUID } from 'node:crypto';
import { createChatGPTOAuth } from './android-subscription-oauth.mjs';
import { resolveGoogleProject, validateGoogleProject } from './android-subscription-google.mjs';
import { subscriptionProviders, virtualEndpoint, toSubscriptionContext, fromSubscriptionMessage, subscriptionOptions, subscriptionFailure } from './android-subscription-adapter.mjs';

export class BridgeCredentialStore {
  constructor(link, fetchImpl = globalThis.fetch) {
    if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(link.baseUrl) || !/^[a-f0-9]{64}$/.test(link.token)) throw Error('Invalid private credential bridge');
    this.link=link;this.fetch=fetchImpl;this.locks=new Map();
  }
  async request(provider,method='GET',value,signal) {
    if (!subscriptionProviders.includes(provider)) throw Error('Unknown subscription provider');
    const response = await this.fetch(`${this.link.baseUrl}/credentials/${provider}`,{method,
      headers:{'x-credential-token':this.link.token,...(value ? {'content-type':'application/json'} : {})},
      ...(value ? {body:JSON.stringify(value)} : {}),signal:signal ? AbortSignal.any([signal,AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000)});
    if (!response.ok) throw Error('手机加密凭据暂时不可用');
    return response.json();
  }
  async read(provider,options={}) { return (await this.request(provider,'GET',undefined,options.signal)).credential || undefined; }
  async list() { const result=[];for (const providerId of subscriptionProviders) if (await this.read(providerId)) result.push({providerId,type:'oauth'});return result; }
  locked(provider,fn) {
    const previous=this.locks.get(provider) || Promise.resolve(); const result=previous.catch(()=>{}).then(fn);
    this.locks.set(provider,result);
    result.finally(()=>{if(this.locks.get(provider)===result)this.locks.delete(provider);}).catch(()=>{});
    return result;
  }
  modify(provider,fn,options={}) { return this.locked(provider,async()=> {
    const current=await this.read(provider,options); const next=await fn(current);
    if (options.signal?.aborted) throw Error('连接已取消');
    if (next !== undefined) await this.request(provider,'PUT',next,options.signal);
    return next === undefined ? current : next;
  }); }
  delete(provider,options={}) { return this.locked(provider,()=>this.request(provider,'DELETE',undefined,options.signal)); }
}

export class SubscriptionManager {
  constructor({sdk,credentials,deviceId,manualApi,getConfig,updateConfig,pause}) {
    this.sdk=sdk;this.credentials=credentials;this.manualApi=manualApi;this.getConfig=getConfig;this.updateConfig=updateConfig;this.pause=pause;
    this.pending=null;this.known=new Map();this.work=new AsyncLocalStorage();this.transcripts=[];this.transcriptBytes=0;this.chatgptModels=[];this.catalogError='';this.accountGeneration=0;this.googleGeneration=0;
    this.oauth=createChatGPTOAuth({sdk,deviceId,fetchImpl:(...args)=>this.work.run(undefined,()=>globalThis.fetch(...args))});
    const store={read:(_,opts)=>credentials.read('chatgpt-plus',opts),list:async()=>[],
      modify:(_,fn,opts)=>credentials.modify('chatgpt-plus',fn,opts),delete:(_,opts)=>credentials.delete('chatgpt-plus',opts)};
    this.models=sdk.createModels({credentials:store,authContext:{env:async()=>undefined,fileExists:async()=>false}});
    const provider=sdk.openaiProvider();provider.auth={...provider.auth,oauth:this.oauth}; this.models.setProvider(provider);
    this.googleModels=sdk.legacyModels('google-gemini-cli');
  }
  async initialise() {
    for (const provider of subscriptionProviders) {
      const c=await this.credentials.read(provider);this.known.set(provider,this.credentialStatus(provider,c));
      if(provider==='chatgpt-plus' && c?.modelCatalog)this.chatgptModels=this.accountModels(c.modelCatalog);
    }
    this.updateReady();
  }
  credentialStatus(provider,c) {
    if(!c)return {connected:false,authorized:false};
    if(provider!=='google-ai-pro')return {connected:true,authorized:true,email:c.email || ''};
    return {connected:!!c.projectId && c.setupPending!==true,authorized:true,email:c.email || '',
      setupError:c.setupError || '',setupCode:c.setupCode || '',validationUrl:c.diagnostic?.validationUrl || ''};
  }
  active() { const provider=this.getConfig().api?.provider || '';return provider.startsWith('android-') ? provider.slice(8) : ''; }
  updateReady() { const active=this.active();this.updateConfig({androidSubscriptionReady:subscriptionProviders.includes(active) && this.known.get(active)?.connected === true && this.catalog(active).some(model=>model.id===this.getConfig().api?.model)}); }
  catalog(provider) {
    if (provider==='chatgpt-plus') return this.chatgptModels;
    if (provider==='google-ai-pro') return this.googleModels;
    throw Error('请选择 ChatGPT 或 Google 订阅账号');
  }
  accountModels(catalog) {
    if(!Array.isArray(catalog) || catalog.length>200)throw Error('ChatGPT 模型列表无效');
    const bundled=this.models.getModels('openai');
    const template=bundled.find(m=>m.id==='gpt-5-mini') || bundled[0];
    return catalog.filter(item=>item.visibility==='list' && /^[a-zA-Z0-9._-]{1,128}$/.test(item.slug || '')).map(item=>{
      const known=bundled.find(m=>m.id===item.slug);
      return {...(known || {...template,input:['text'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0}}),id:item.slug,name:String(item.display_name || item.slug).slice(0,128)};
    });
  }
  async refreshModels(provider,signal) {
    if(!this.known.get(provider)?.connected)throw Error('请先连接此订阅账号');
    if(provider!=='chatgpt-plus')return this.status();
    const deadline=signal ? AbortSignal.any([signal,AbortSignal.timeout(30000)]) : AbortSignal.timeout(30000);
    const generation=this.accountGeneration;
    try {
      const auth=await this.models.getAuth('openai',{signal:deadline});
      if(!auth?.auth?.apiKey)throw Error('请重新连接 ChatGPT');
      const response=await globalThis.fetch('https://api.openai.com/v1/models',{headers:{authorization:`Bearer ${auth.auth.apiKey}`},signal:deadline});
      if(!response.ok)throw Error('账号模型列表不可用');
      const text=await response.text();if(text.length>512*1024)throw Error('模型列表过大');
      const catalog=JSON.parse(text).models;const models=this.accountModels(catalog);
      if(!models.length)throw Error('此账号未返回可选择模型');
      const cached=models.map(item=>({slug:item.id,display_name:item.name,visibility:'list'}));
      if(Buffer.byteLength(JSON.stringify(cached))>48000)throw Error('账号模型列表过大');
      await this.credentials.modify(provider,async current=>{
        if(generation!==this.accountGeneration)throw Error('账号连接已改变');
        return current ? {...current,modelCatalog:cached} : undefined;
      },{signal:deadline});
      if(!this.known.get(provider)?.connected || generation!==this.accountGeneration)throw Error('账号已断开');
      this.chatgptModels=models;this.catalogError='';this.updateReady();
      if(this.active()==='chatgpt-plus' && !this.getConfig().androidSubscriptionReady)this.pause();
      return this.status();
    } catch {
      const error='账号已连接，模型列表暂时无法刷新，请检查网络后重试。';
      if(generation===this.accountGeneration)this.catalogError=error;
      throw Error(error);
    }
  }
  status() {
    const pending=this.pending;
    return {active:this.active(),model:this.getConfig().api?.model || '',providers:subscriptionProviders.map(id=>({id,
      name:id==='chatgpt-plus' ? 'ChatGPT Plus / Pro' : 'Google AI Pro / Ultra',...this.known.get(id),modelError:id==='chatgpt-plus' ? this.catalogError : '',
      models:this.catalog(id).filter(m=>m.type===undefined || m.type==='chat').map(m=>({id:m.id,name:m.name,vision:m.input?.includes('image')===true}))})),
      pending:pending ? {id:pending.id,provider:pending.provider,status:pending.status,url:pending.url || '',manualRequired:!!pending.manual,error:pending.error || '',stage:pending.stage || ''} : null};
  }
  prompt(task,signal) {
    return new Promise((resolve,reject)=> {
      let slot;
      const abort=()=> { if(task.manual===slot)task.manual=null; reject(Error('连接已取消')); };
      signal.addEventListener('abort',abort,{once:true});
      slot={resolve:value=>{signal.removeEventListener('abort',abort);task.manual=null;resolve(value);},reject:abort};task.manual=slot;
      if(signal.aborted)abort();
    });
  }
  begin(provider,{resume=false,projectId}={}) {
    if (!subscriptionProviders.includes(provider)) throw Error('不支持此订阅账号');
    if(resume && provider!=='google-ai-pro')throw Error('此账号不需要项目设置');
    projectId=validateGoogleProject(projectId);
    this.cancel();
    const googleGeneration=provider==='google-ai-pro' ? ++this.googleGeneration : this.googleGeneration;
    const task={id:randomUUID(),provider,status:'waiting',stage:'准备官方登录',controller:new AbortController(),url:'',manual:null};this.pending=task;
    const timer=setTimeout(()=>task.controller.abort(),10*60*1000);timer.unref();
    const notify=event=>{if(event.type==='auth_url'){task.url=event.url;task.callbackUri=event.callbackUri;task.stage='等待官方授权';}else if(event.type==='progress')task.stage=event.stage;};
    const operation=async()=>{
      let credential;
      if(provider==='chatgpt-plus') {
        const previous=await this.credentials.read(provider);
        credential=await this.oauth.login({signal:task.controller.signal,notify,prompt:p=>this.prompt(task,p.signal || task.controller.signal)},previous);
      } else {
        const saveGrant=async grant=>{
          await this.credentials.modify(provider,async()=> {
            if(googleGeneration!==this.googleGeneration || this.pending!==task || task.controller.signal.aborted)throw Error('连接已取消');
            return {...grant,requestedProject:projectId || ''};
          },{signal:AbortSignal.timeout(20000)});
          this.known.set(provider,this.credentialStatus(provider,grant));this.updateReady();
        };
        const setup=access=>resolveGoogleProject(access,{projectId,signal:task.controller.signal,onProgress:stage=>{task.stage=stage;},onDiagnostic:diagnostic=>{task.diagnostic=diagnostic;}});
        if(resume) {
          credential=await this.credentials.modify(provider,async current=>{
            if(!current)throw Error('请先连接 Google 账号');
            projectId=projectId || validateGoogleProject(current.requestedProject || '');
            if(current.expires>Date.now())return undefined;
            task.controller.signal.throwIfAborted();
            return {...current,...await this.work.run(AbortSignal.timeout(15000),()=>this.sdk.refreshGoogleCloudToken(current.refresh,current.projectId))};
          },{signal:AbortSignal.timeout(20000)});
          const resolved=await setup(credential.access);
          credential={...credential,projectId:resolved};
        } else credential=await this.sdk.loginGeminiCli(event=>{task.url=event.url;task.callbackUri='http://localhost:8085/oauth2callback';},message=>{
          const stages=[[/Starting local server/,'启动本机回调'],[/Waiting for OAuth/,'等待官方授权'],[/Exchanging.*code/,'换取授权凭据'],[/Getting user info/,'检查账号身份'],[/Checking for existing|Discovering project/,'检查模型权限和项目'],[/Provisioning|provisioning|operation/,'开通个人模型项目']];
          for(const [pattern,stage] of stages)if(pattern.test(message)){task.stage=stage;break;}
        },()=>this.prompt(task,task.controller.signal),saveGrant,setup);
        credential={...credential,type:'oauth',setupPending:false,setupError:'',setupCode:'',diagnostic:task.diagnostic,requestedProject:projectId || ''};
      }
      if(task.controller.signal.aborted || this.pending!==task)throw Error('连接已取消');
      if(provider==='chatgpt-plus')this.accountGeneration++;
      await this.credentials.modify(provider,async()=> {
        if(provider==='google-ai-pro' && googleGeneration!==this.googleGeneration)throw Error('连接已取消');
        return credential;
      },{signal:task.controller.signal});
      this.transcripts=[];this.transcriptBytes=0;
      this.known.set(provider,this.credentialStatus(provider,credential));this.updateReady();task.status='connected';task.stage='账号已连接';task.url='';
      if(provider==='chatgpt-plus') {
        this.chatgptModels=[];
        await this.refreshModels(provider,task.controller.signal).catch(()=>{});
      }
    };
    this.work.run(task.controller.signal,operation).catch(async error=>{
      task.status=task.controller.signal.aborted ? 'cancelled' : 'error';task.url='';task.error=task.status==='cancelled' ? '连接已取消' : subscriptionFailure(error,provider);
      if(provider==='google-ai-pro' && googleGeneration===this.googleGeneration) {
        const stored=await this.credentials.modify(provider,async current=> {
          if(!current || googleGeneration!==this.googleGeneration)return undefined;
          if(current.projectId && current.setupPending!==true)return undefined;
          return {...current,setupPending:true,setupError:task.error,setupCode:error.setupCode || '',diagnostic:error.diagnostic || task.diagnostic};
        },{signal:AbortSignal.timeout(20000)}).catch(()=>undefined);
        if(stored && googleGeneration===this.googleGeneration)this.known.set(provider,this.credentialStatus(provider,stored));
        this.updateReady();
      }
    }).finally(()=>{clearTimeout(timer);task.controller.abort();task.manual=null;});
    return this.status();
  }
  submit(id,input) {
    const task=this.pending;
    if(!task || task.id!==id || !task.manual || !task.url)throw Error('此登录已结束，请重新连接');
    const url=new URL(input);const expected=new URL(task.callbackUri);
    if(url.origin!==expected.origin || url.pathname!==expected.pathname || !url.searchParams.get('state') ||
      url.searchParams.get('state')!==new URL(task.url).searchParams.get('state'))throw Error('请粘贴本次官方登录返回的完整地址');
    task.manual.resolve(input);return {ok:true};
  }
  cancel() { if(this.pending?.status==='waiting') {this.pending.controller.abort();this.pending.manual?.reject();} }
  async disconnect(provider) {
    if(!subscriptionProviders.includes(provider))throw Error('不支持此订阅账号');
    this.pause();if(provider==='chatgpt-plus')this.accountGeneration++;else this.googleGeneration++;
    this.transcripts=[];this.transcriptBytes=0;if(this.pending?.provider===provider)this.cancel();
    await this.credentials.delete(provider);this.known.set(provider,{connected:false});
    if(provider==='chatgpt-plus'){this.chatgptModels=[];this.catalogError='';}
    this.updateReady();return this.status();
  }
  async select(provider,model) {
    if(!this.known.get(provider)?.connected || !this.catalog(provider).some(m=>m.id===model))throw Error('请先连接账号并选择可用模型');
    this.pause();this.updateConfig({api:{provider:`android-${provider}`,baseUrl:virtualEndpoint(provider),apiKey:'android-subscription',model,
      visionModel:'',videoModel:'',fallbackModels:[]}});this.updateReady();return this.status();
  }
  async manual() {
    this.pause();this.updateConfig({api:{...this.manualApi,provider:this.manualApi.provider || '',visionModel:this.manualApi.visionModel || '',videoModel:this.manualApi.videoModel || '',fallbackModels:[]}});
    this.updateReady();return this.status();
  }
  async complete(provider,body,signal) {
    if(!this.known.get(provider)?.connected)throw Error('请先连接此订阅账号');
    if(provider==='chatgpt-plus' && !this.chatgptModels.length)await this.refreshModels(provider,signal);
    const model=this.catalog(provider).find(m=>m.id===body.model);if(!model)throw Error('此模型不在当前订阅接入目录中');
    const context=toSubscriptionContext(body,provider,model.id,Date.now(),message=> {
      if(!message.tool_calls?.length)return;
      return this.transcripts.find(entry=>entry.provider===provider && entry.message.model===model.id &&
        entry.calls.length===message.tool_calls.length && entry.calls.every((call,index)=>{
          const current=message.tool_calls[index];return current.id===call.id && current.function?.name===call.function.name && current.function?.arguments===call.function.arguments;
        }))?.message;
    });
    const options=subscriptionOptions(body,provider,model);
    if(!model.input.includes('image') && context.messages.some(m=>m.content.some(p=>p.type==='image')))throw Error('此模型尚未配置图片支持，请选择具备图片能力的模型');
    const requestSignal=signal ? AbortSignal.any([signal,AbortSignal.timeout(180000)]) : AbortSignal.timeout(180000);
    return this.work.run(requestSignal,async()=> {
      let result;
      if(provider==='chatgpt-plus') result=await this.models.complete(model,context,{signal:requestSignal,...options});
      else {
        const credential=await this.credentials.modify(provider,async current=> {
          if(!current)throw Error('请重新连接 Google 账号');
          if(current.expires > Date.now())return undefined;
          requestSignal.throwIfAborted();
          return {...current,...await this.work.run(AbortSignal.timeout(15000),()=>this.sdk.refreshGoogleCloudToken(current.refresh,current.projectId))};
        },{signal:AbortSignal.timeout(20000)});
        const stream=this.sdk.streamGoogleGeminiCli(model,context,{signal:requestSignal,
          apiKey:JSON.stringify({token:credential.access,projectId:credential.projectId}),...options});
        result=await stream.result();
      }
      const response=fromSubscriptionMessage(result);
      const calls=response.choices[0].message.tool_calls;
      if(calls?.length) {
        const bytes=Buffer.byteLength(JSON.stringify(result));
        if(bytes<=1024*1024) {
          this.transcripts.push({provider,message:result,calls,bytes});this.transcriptBytes+=bytes;
          while(this.transcripts.length>64 || this.transcriptBytes>4*1024*1024)this.transcriptBytes-=this.transcripts.shift().bytes;
        }
      }
      return response;
    }).catch(()=> { throw Error(requestSignal.aborted ? '订阅模型请求已取消或超时' : '订阅模型请求失败，请检查账号权限、额度与网络后重试'); });
  }
}
