import path from 'node:path';
let engine=null;
export async function setup(api) {
  const version='?v='+Date.now()+'-'+Math.random();
  const [{ReviewEngine},{matchRule},{judgeByAi},{STRATAGEMS},protocol,config,llm]=await Promise.all([
    import('./lib/reviewer.js'+version),import('./lib/matcher.js'+version),import('./lib/ai-judge.js'+version),
    import('./lib/stratagems.js'+version),import('./lib/protocol.js'+version),import('../../src/config.js'),import('../../src/llm.js')]);
  engine=new ReviewEngine({settings:()=>api.config(),runtimeConfig:config.getConfig,stateFile:path.join(config.DATA_DIR,'hd2-join-review.json'),fetchImpl:(...args)=>api.fetch(...args),matchRule,
    judgeByAi:(answer,options)=>judgeByAi(answer,{...options,complete:llm.chatCompletionWithRetry}),catalog:STRATAGEMS,...protocol,log:api.log,warn:api.warn});
  api.log('入群审核 1.1.0 已加载（SnowLuma / NapCat）');
}
export function available(){return {ok:engine!==null,reason:engine?'':'审核引擎未初始化'};}
export function activate(){engine?.start();}
export function deactivate(){engine?.stop();}
export function dispose(){engine?.stop();}
export const providers={'hd2-join-review.status':()=>engine?.getStatus() || {running:false,lastCheckResult:'未初始化'}};
