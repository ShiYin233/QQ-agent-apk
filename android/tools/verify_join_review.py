"""Install and verify the Android plugin without submitting any QQ approvals or model prompts."""
import datetime, hashlib, json, pathlib, subprocess, sys, time, urllib.request, urllib.error
from adb_device import device_arguments

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = ROOT/'runtime/local-setup/adb/adb.exe'
SELECTED = device_arguments(ADB)
PACKAGE = 'cn.qqagent.android'
ARTIFACT = ROOT/'android/artifacts/QQ-Agent-0.5.2-join-review-arm64-debug.apk'

def adb(*args):
    return subprocess.check_output([str(ADB),*SELECTED,*args],timeout=120)

if len(sys.argv)>1 and sys.argv[1]=='install':
    assert b'Success' in adb('install','-r',str(ARTIFACT))
    adb('shell','am','start','-n',PACKAGE+'/cn.qqagent.android.gate.MainActivity')
    print('0.5.2 APK installed; existing app data retained')
    sys.exit(0)

baseline=json.loads((ROOT/'runtime/local-setup/join-review-before.json').read_text())
report={'schema':1,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'version':'0.5.2-join-review','qqApprovalsByVerifier':0,'qqMessagesByVerifier':0,'modelInferenceCallsByVerifier':0}
port=adb('forward','tcp:0','tcp:3312').decode().strip()
protocol_port=None
try:
    def api(route):
        link=json.loads(adb('exec-out','run-as',PACKAGE,'cat','files/runtime/state/console-link.json'))
        request=urllib.request.Request('http://127.0.0.1:'+port+route,headers={'x-console-token':link['token']})
        return json.load(urllib.request.urlopen(request,timeout=10))
    for attempt in range(30):
        try:
            runtime=api('/api/android/runtime')
            skills=api('/api/skills')
            plugin=next(x for x in skills['skills'] if x['id']=='hd2-join-review')
            status=api('/api/android/join-review')
            break
        except (OSError,StopIteration):time.sleep(2)
    else:raise RuntimeError('Updated runtime did not become available')
    assert plugin['version']=='1.1.0' and not plugin.get('loadError'),plugin.get('reason')
    assert status['loaded'] and not status['running'] and not plugin['enabled']
    assert runtime['automaticRepliesEnabled']==baseline['runtime']['automaticRepliesEnabled']
    config=api('/api/config')
    current=hashlib.sha256(json.dumps({k:v for k,v in config.items() if k not in ['server','api','snowluma','skills']},sort_keys=True).encode()).hexdigest()
    assert current==baseline['settingsHash'],'Existing settings changed'
    for filename in ['model-settings.enc','subscriptions.enc']:
        field=filename+'Hash'
        if field in baseline:assert hashlib.sha256(adb('exec-out','run-as',PACKAGE,'cat','files/credentials/'+filename)).hexdigest()==baseline[field]
    report.update(pluginLoaded=True,pluginEnabled=False,pluginRunning=False,settingsPreserved=True,
                  encryptedCredentialsPreserved=True,automaticRepliesEnabled=runtime['automaticRepliesEnabled'],
                  qqConnected=runtime['onebotConnected'],targetGroup=status['targetGroup'])
    req=urllib.request.Request('http://127.0.0.1:'+port+'/api/android/join-review',headers={'x-console-token':'invalid'})
    try:urllib.request.urlopen(req,timeout=5);raise AssertionError('Unauthenticated status accepted')
    except urllib.error.HTTPError as err:assert err.code==401
    report['privateStatusAuthenticationVerified']=True
    # Actual phone's protocol: read-only permission and pending-list shape, no approvals.
    protocol_port=adb('forward','tcp:0','tcp:3000').decode().strip()
    protocol=json.loads(adb('exec-out','run-as',PACKAGE,'cat','files/runtime/state/protocol-link.json'))
    def onebot(action,params):
        req=urllib.request.Request('http://127.0.0.1:'+protocol_port+'/'+action,
            headers={'Authorization':'Bearer '+protocol['accessToken'],'Content-Type':'application/json'},data=json.dumps(params).encode())
        return json.load(urllib.request.urlopen(req,timeout=15))
    try:
        target=int(status['targetGroup'])
        login=onebot('get_login_info',{})
        role=onebot('get_group_member_info',{'group_id':target,'user_id':login['data']['user_id'],'no_cache':True})
        pending=onebot('get_group_system_msg',{'count':100})
        report['protocolReadOnlyCheck']={'roleQueryStatus':role.get('status'),'botRole':role.get('data',{}).get('role'),
            'pendingQueryStatus':pending.get('status'),'hasNapCatJoinRequests':isinstance(pending.get('data',{}).get('join_requests'),list),
            'visibleTargetPendingCount':sum(x.get('group_id')==target and x.get('checked') is False for x in pending.get('data',{}).get('join_requests',[]))}
    except (OSError,KeyError,ValueError):report['protocolReadOnlyCheck']={'available':False}
    # Same shipped modules run under the phone's real ARM64 Node, with synthetic HTTP/AI.
    adb('push',str(ROOT/'android/tools/join-review.test.mjs'),'/data/local/tmp/qqagent-join-review.test.mjs')
    adb('shell','run-as',PACKAGE,'mkdir','-p','files/runtime/rootfs/opt/agent/android/tools')
    adb('shell','run-as',PACKAGE,'cp','/data/local/tmp/qqagent-join-review.test.mjs','files/runtime/rootfs/opt/agent/android/tools/join-review.test.mjs')
    # Host source path becomes the shipped /opt/agent/plugins directory; core stays original.
    adb('shell','run-as',PACKAGE,'mkdir','-p','files/runtime/rootfs/opt/agent/android/app/src/main/assets/runtime')
    adb('shell','run-as',PACKAGE,'ln','-sfn','/opt/agent/plugins','files/runtime/rootfs/opt/agent/android/app/src/main/assets/runtime/plugins')
    home='/data/user/0/'+PACKAGE+'/files/runtime'
    output=adb('shell','run-as',PACKAGE,'env','PROOT_TMP_DIR='+home+'/tmp','HOME='+home,
        'PROOT_LOADER='+home+'/bin/loader64','PROOT_LOADER_32='+home+'/bin/loader32',
        home+'/bin/proot','--kill-on-exit','--link2symlink','-0','-r',home+'/rootfs',
        '-b',home+'/state:/qqagent-state','-b',home+'/tmp:/tmp','-b',home+'/node:/opt/node',
        '-b','/dev','-b','/proc','-b','/sys','-w','/opt/agent','/usr/bin/env','QQ_AGENT_DATA_DIR=/tmp/hd2-offline-tests',
        '/opt/node/bin/node','--test','--test-reporter=tap','/opt/agent/android/tools/join-review.test.mjs').decode('utf-8',errors='replace')
    (ROOT/'runtime/local-setup/join-review-phone-tests.log').write_text(output,encoding='utf-8')
    assert '# pass 51' in output and '# fail 0' in output,output[-1500:]
    report['phoneOfflineTests']=51
    report['passed']=True
finally:
    adb('forward','--remove','tcp:'+port)
    if protocol_port:adb('forward','--remove','tcp:'+protocol_port)
    (ROOT/'runtime/local-setup/OPPO-A6m-join-review-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
