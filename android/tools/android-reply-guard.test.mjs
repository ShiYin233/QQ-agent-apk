import test from 'node:test';
import assert from 'node:assert/strict';
import { installReplyGuard } from '../app/src/main/assets/runtime/android-reply-guard.mjs';
function fixture() {
  let calls=0;let release;
  const wait=new Promise(resolve=>release=resolve);
  const config={api:{baseUrl:'https://example.test',apiKey:'synthetic',model:'synthetic'},allow:{groups:['123456'],private:[]},androidActivityReviewed:true,store:{contextSliderPos:5}};
  const app={orchestrator:{paused:false,setPaused(value){this.paused=value;}},onebot:{async call(){calls++;return {ok:true};}},sender:{}};
  app.sender.sendTextBatch=async()=>{await wait;return app.onebot.call('send_group_msg',{group_id:123456});};
  installReplyGuard(app,()=>config);
  return {app,config,release,get calls(){return calls;}};
}
test('paused text/media/poke dispatch is blocked, while read queries remain available',async()=>{
  const f=fixture();f.app.orchestrator.setPaused(true);
  for(const action of ['send_group_msg','send_private_msg','send_group_forward_msg','send_poke','friend_poke','group_poke'])
    await assert.rejects(f.app.onebot.call(action,{group_id:123456}));
  assert.equal(f.calls,0);await f.app.onebot.call('get_group_info');assert.equal(f.calls,1);
});
test('a queued message cancelled by pause cannot reappear after resume',async()=>{
  const f=fixture();const pending=f.app.sender.sendTextBatch();
  f.app.orchestrator.setPaused(true);f.app.orchestrator.setPaused(false);f.release();
  await assert.rejects(pending);assert.equal(f.calls,0);
});
test('allow-list removal before actual dispatch blocks an old queued task',async()=>{
  const f=fixture();const pending=f.app.sender.sendTextBatch();f.config.allow.groups=[];f.release();
  await assert.rejects(pending);assert.equal(f.calls,0);
});
