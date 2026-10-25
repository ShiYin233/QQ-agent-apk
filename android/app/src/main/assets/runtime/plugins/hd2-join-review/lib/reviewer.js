import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

const hash = value => createHash('sha256').update(String(value)).digest('hex');
const bounded = (value,min,max,fallback) => Number.isFinite(Number(value)) ? Math.max(min,Math.min(max,Number(value))) : fallback;
export class ReviewEngine {
  constructor({settings=()=>({}),runtimeConfig=()=>({}),stateFile,fetchImpl=globalThis.fetch,matchRule,judgeByAi,normalizeRequests,resolveEndpoint,catalog=[],log=()=>{},warn=()=>{},now=Date.now}={}) {
    Object.assign(this,{settings,runtimeConfig,stateFile,fetchImpl,matchRule,judgeByAi,normalizeRequests,resolveEndpoint,catalog,log,warn,now});
    this.catalogVersion=hash(JSON.stringify(catalog));
    this.running=false;this.generation=0;this.timer=null;this.checking=false;this.controller=null;
    this.botUin='';this.botRole='未知';this.lastCheckTime=null;this.lastCheckResult='未开始';this.protocol='未知';
    this.processedFlags=new Set();this.attemptedFlags=new Set();this.reviewed=new Map();this.cache=new Map();
    this.stats={checks:0,approved:0,retained:0,aiCalls:0,aiTokens:{prompt_tokens:0,completion_tokens:0,total_tokens:0}};this.recentAudits=[];
    try {
      const state=JSON.parse(fs.readFileSync(stateFile,'utf8'));
      if(state.schema===2){
        this.processedFlags=new Set((state.processedFlags || []).slice(-1000));
        this.attemptedFlags=new Set((state.attemptedFlags || []).slice(-1000));
        // A catalog update invalidates decisions, never approval dispatch history.
        if(state.catalogVersion===this.catalogVersion){
          this.reviewed=new Map((state.reviewed || []).slice(-1000));
          this.cache=new Map((state.cache || []).slice(-500));
        }
        this.recentAudits=(state.recentAudits || []).slice(0,200);
        for(const k of ['checks','approved','retained','aiCalls']) this.stats[k]=bounded(state.stats?.[k],0,1e12,0);
        for(const k of Object.keys(this.stats.aiTokens)) this.stats.aiTokens[k]=bounded(state.stats?.aiTokens?.[k],0,1e12,0);
      }
    } catch { /* First run or older unsafe cache: do not reuse approved decisions. */ }
  }
  saveState() {
    if(!this.stateFile) return true;
    try {
      fs.mkdirSync(path.dirname(this.stateFile),{recursive:true});
      const data={schema:2,catalogVersion:this.catalogVersion,stats:this.stats,processedFlags:[...this.processedFlags].slice(-1000),attemptedFlags:[...this.attemptedFlags].slice(-1000),reviewed:[...this.reviewed].slice(-1000),cache:[...this.cache].slice(-500),recentAudits:this.recentAudits.slice(0,200)};
      const temp=this.stateFile+'.tmp';
      fs.writeFileSync(temp,JSON.stringify(data,null,2),{encoding:'utf8',mode:0o600});fs.renameSync(temp,this.stateFile);return true;
    } catch {this.lastCheckResult='无法保存审核状态，暂停批准';this.warn(this.lastCheckResult);return false;}
  }
  current(gen,target) {const s=this.settings();return this.running && gen===this.generation && s.enabled!==false && (!target || String(s.targetGroup ?? '').trim()===target);}
  start() {
    if(this.running)return;
    this.running=true;const gen=++this.generation;
    const loop=async()=>{
      try {await this.runCheck(gen);} catch {if(gen===this.generation)this.lastCheckResult='QQ 接口检查失败，留人工';}
      if(this.running && gen===this.generation){this.timer=setTimeout(loop,bounded(this.settings().checkInterval,5,3600,30)*1000);this.timer.unref?.();}
    };
    void loop();
  }
  stop(){this.running=false;this.generation++;clearTimeout(this.timer);this.timer=null;this.controller?.abort();this.saveState();}
  async callOneBot(action,params={},signal) {
    const {url,token}=this.resolveEndpoint(this.runtimeConfig());
    const timeout=AbortSignal.timeout(12000);
    const response=await this.fetchImpl(url+'/'+action,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:'Bearer '+token}:{})},body:JSON.stringify(params),signal:signal?AbortSignal.any([signal,timeout]):timeout});
    if(!response.ok)throw Error('QQ HTTP '+response.status);
    const body=await response.json();
    if(body.status!=='ok' || body.retcode!==0)throw Error('QQ 接口失败');
    return body.data;
  }
  async checkAdminPermission(target,gen,signal) {
    const login=await this.callOneBot('get_login_info',{},signal);
    if(!this.current(gen,target))return false;
    this.botUin=String(login?.user_id ?? '');
    if(!/^[1-9]\d{4,14}$/.test(this.botUin))return false;
    const member=await this.callOneBot('get_group_member_info',{group_id:Number(target),user_id:Number(this.botUin),no_cache:true},signal);
    if(!this.current(gen,target))return false;
    this.botRole=String(member?.role ?? '未知');return ['admin','owner'].includes(this.botRole);
  }
  async pending(target,signal) {
    const data=await this.callOneBot('get_group_system_msg',{group_id:Number(target),only_pending:true,count:100},signal);
    this.protocol=Array.isArray(data)?'snowluma':'napcat';return this.normalizeRequests(data,target);
  }
  audit(req,decision,status) {
    this.recentAudits.unshift({time:new Date(this.now()).toISOString(),userId:req.userId,groupId:req.groupId,answer:req.message.slice(0,1000),stratagem:decision.name || '',method:decision.method,reason:decision.reason || '',status});
    this.recentAudits.length=Math.min(this.recentAudits.length,200);
    this.log(status+'：'+(decision.name || decision.reason || '留人工'));
  }
  async decide(raw,settings,signal) {
    const key=hash(this.catalogVersion+'|'+(settings.aiAssist!==false)+'|'+raw);
    const cached=this.cache.get(key);
    if(cached && this.now()-cached.time<86400000 && typeof cached.decision?.approved==='boolean' && (!cached.decision.approved || this.catalog.some(s=>s.key===cached.decision.key)))return cached.decision;
    const rule=this.matchRule(raw,this.catalog);
    let decision={approved:false,method:'rule',reason:rule.reason};
    if(rule.matched)decision={approved:true,key:rule.stratagemKey,name:rule.name,method:rule.method};
    else if(settings.aiAssist!==false && rule.aiAllowed){
      this.stats.aiCalls++;
      const result=await this.judgeByAi(rule.extracted,{signal:AbortSignal.any([signal,AbortSignal.timeout(25000)]),catalog:this.catalog});
      for(const k of Object.keys(this.stats.aiTokens))this.stats.aiTokens[k]+=bounded(result.usage?.[k],0,1e9,0);
      decision={approved:result.recognized===true && this.catalog.some(s=>s.key===result.stratagemKey),key:result.stratagemKey,name:result.name,method:'ai',reason:result.reason,transient:result.transient===true};
    }
    if(!decision.transient && !signal.aborted){this.cache.set(key,{time:this.now(),decision});while(this.cache.size>500)this.cache.delete(this.cache.keys().next().value);}
    return decision;
  }
  async runCheck(gen=this.generation) {
    if(this.checking || !this.current(gen))return;
    const settings=this.settings(),target=String(settings.targetGroup ?? '').trim();
    if(!/^[1-9]\d{4,14}$/.test(target) || !Number.isSafeInteger(Number(target))){this.lastCheckResult='目标群号无效';return;}
    this.checking=true;const controller=new AbortController();this.controller=controller;const signal=controller.signal;
    this.stats.checks++;this.lastCheckTime=new Date(this.now()).toISOString();
    try {
      if(!await this.checkAdminPermission(target,gen,signal)){this.lastCheckResult='机器人不具备目标群审核权限';return;}
      const pending=await this.pending(target,signal);
      for(const req of pending){
        if(!this.current(gen,target) || signal.aborted)break;
        const flagKey=this.botUin+'|'+target+'|'+req.flag;
        if(this.processedFlags.has(flagKey) || this.attemptedFlags.has(flagKey))continue;
        const answerHash=hash(req.message+'|'+(settings.aiAssist!==false));
        const reviewed=this.reviewed.get(flagKey);
        if(reviewed?.hash===answerHash && (!reviewed.retryAt || reviewed.retryAt>this.now()))continue;
        const decision=await this.decide(req.message,settings,signal);
        if(!this.current(gen,target) || signal.aborted)break;
        // Configuration can change while AI is running. Re-evaluate on the next cycle.
        if(this.settings().aiAssist===false && decision.method==='ai')continue;
        if(!decision.approved){
          this.stats.retained++;this.reviewed.set(flagKey,{hash:answerHash,retryAt:decision.transient?this.now()+300000:0});
          this.audit(req,decision,'留人工审核');continue;
        }
        if(!await this.checkAdminPermission(target,gen,signal))continue;
        const fresh=(await this.pending(target,signal)).find(r=>r.flag===req.flag && r.userId===req.userId && r.message===req.message);
        if(!fresh || !this.current(gen,target) || signal.aborted || (this.settings().aiAssist===false && decision.method==='ai'))continue;
        // Persist before dispatch: an ambiguous timeout must never cause a blind retry.
        this.attemptedFlags.add(flagKey);if(!this.saveState()){this.attemptedFlags.delete(flagKey);break;}
        if(!this.current(gen,target))break;
        try {
          await this.callOneBot('set_group_add_request',{flag:req.flag,sub_type:'add',approve:true,reason:'',count:100},signal);
          this.processedFlags.add(flagKey);this.attemptedFlags.delete(flagKey);this.stats.approved++;this.audit(req,decision,'已自动批准');
        }catch {this.audit(req,decision,'批准结果不确定，请人工核对');}
        this.saveState();
      }
      if(this.current(gen,target))this.lastCheckResult='本轮完成：读取 '+pending.length+' 条普通待审申请';
    }catch {if(this.current(gen,target))this.lastCheckResult='QQ 接口不可用，保留待审';}
    finally{if(gen===this.generation)this.saveState();this.checking=false;if(this.controller===controller)this.controller=null;}
  }
  getStatus(){return {running:this.running,enabled:this.settings().enabled!==false,targetGroup:String(this.settings().targetGroup ?? ''),checkInterval:bounded(this.settings().checkInterval,5,3600,30),aiAssist:this.settings().aiAssist!==false,botRole:this.botRole,lastCheckTime:this.lastCheckTime,lastCheckResult:this.lastCheckResult,protocol:this.protocol,stats:structuredClone(this.stats),inFlightCount:this.checking?1:0,cachedAnswersCount:this.cache.size,uncertainApprovals:this.attemptedFlags.size,recentAudits:this.recentAudits.slice(0,15)};}
}
