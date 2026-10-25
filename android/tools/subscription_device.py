"""Local device checks; never resume QQ replies, call a model, or send messages."""
import datetime, hashlib, json, pathlib, re, subprocess, sys, time, urllib.request, urllib.error, urllib.parse, xml.etree.ElementTree as ET
from adb_device import device_arguments
root=pathlib.Path(__file__).resolve().parents[2]
adb_path=root/'runtime/local-setup/adb/adb.exe'
selected=device_arguments(adb_path)
baseline_file=root/'runtime/local-setup/private-qq-logs/subscriptions-before.json'
keys=['allow','deny','store','send','persona','personaByChat','chatActive','proactive','globalBlocklist']
def adb(*args): return subprocess.check_output([str(adb_path),*selected,*args],timeout=45)
def snapshot():
    adb('shell','uiautomator','dump','/data/local/tmp/qqagent-subscriptions-check.xml')
    return ET.fromstring(adb('exec-out','cat','/data/local/tmp/qqagent-subscriptions-check.xml'))
def click(text):
    nodes=[n for n in snapshot().iter('node') if n.attrib.get('package')=='cn.qqagent.android' and n.attrib.get('text')==text]
    assert nodes,'Missing control: '+text
    left,top,right,bottom=map(int,re.findall(r'\d+',nodes[0].attrib['bounds']))
    adb('shell','input','tap',str((left+right)//2),str((top+bottom)//2))
port=adb('forward','tcp:0','tcp:3312').decode().strip()
try:
    def api(path,method='GET',value=None,authenticated=True):
        link=json.loads(adb('exec-out','run-as','cn.qqagent.android','cat','files/runtime/state/console-link.json'))
        request=urllib.request.Request(f'http://127.0.0.1:{port}{path}',method=method,
            headers={'x-console-token':link['token'] if authenticated else 'wrong','content-type':'application/json'},
            data=json.dumps(value).encode() if value is not None else None)
        return json.load(urllib.request.urlopen(request,timeout=15))
    if sys.argv[1]=='baseline':
        runtime=api('/api/android/runtime')
        if runtime['automaticRepliesEnabled']:
            api('/api/android/replies','POST',{'enabled':False})
            runtime=api('/api/android/runtime')
        assert runtime['automaticRepliesEnabled'] is False
        adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity');time.sleep(1)
        adb('shell','run-as','cn.qqagent.android','am','start-foreground-service','--user','0','-n','cn.qqagent.android/cn.qqagent.android.gate.RuntimeForegroundService','-a','cn.qqagent.android.PAUSE_REPLIES');time.sleep(1)
        config=api('/api/config')
        cipher=adb('exec-out','run-as','cn.qqagent.android','cat','files/credentials/model-settings.enc')
        baseline_file.write_text(json.dumps({'settings':{k:config.get(k) for k in keys},'api':config['api'],'manualCipherHash':hashlib.sha256(cipher).hexdigest()},ensure_ascii=False),encoding='utf-8')
        print(json.dumps({'baselineSaved':True,'automaticRepliesPaused':True,'qqConnected':runtime['onebotConnected']}))
    elif sys.argv[1]=='install':
        apk=root/'android/artifacts/QQ-Agent-0.5.1-subscription-setup-arm64-debug.apk'
        output=adb('install','-r',str(apk)).decode(errors='replace');assert 'Success' in output,output
        adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity')
        print('APK installed and opened')
    elif sys.argv[1] in ['offline-probe','network-probe']:
        name='subscription-phone-fixtures.mjs' if sys.argv[1]=='offline-probe' else 'subscription-phone-network.mjs'
        adb('push',str(root/'android/tools'/name),'/data/local/tmp/qqagent-'+name)
        adb('shell','run-as','cn.qqagent.android','cp','/data/local/tmp/qqagent-'+name,'files/runtime/scripts/'+name)
        home='/data/user/0/cn.qqagent.android/files/runtime'
        command=[str(adb_path),*selected,'exec-out','run-as','cn.qqagent.android','env',f'PROOT_TMP_DIR={home}/tmp',f'PROOT_LOADER={home}/bin/loader64',f'PROOT_LOADER_32={home}/bin/loader32',f'{home}/bin/proot','--kill-on-exit','--link2symlink','-0','-r',f'{home}/rootfs','-b','/dev','-b','/proc','-b','/sys','-b',f'{home}/tmp:/tmp','-b',f'{home}/tmp/shm:/dev/shm','-b',f'{home}/node:/opt/node','-b',f'{home}/scripts:/qqagent-scripts','-w','/root','/usr/bin/env','-i','HOME=/root','LANG=C.UTF-8','TMPDIR=/tmp','PATH=/opt/node/bin:/usr/bin:/bin','/opt/node/bin/node','/qqagent-scripts/'+name]
        result=subprocess.run(command,check=True,capture_output=True,timeout=45)
        report=json.loads(result.stdout)
        if sys.argv[1]=='offline-probe':assert report['passed'] and report['networkCalls']==0
        out='OPPO-A6m-subscription-sdk-fixtures.json' if sys.argv[1]=='offline-probe' else 'OPPO-A6m-subscription-network.json'
        (root/'runtime/local-setup'/out).write_text(json.dumps(report,indent=2),encoding='utf-8')
        adb('shell','run-as','cn.qqagent.android','rm','-f','files/runtime/scripts/'+name)
        adb('shell','rm','-f','/data/local/tmp/qqagent-'+name)
        print(json.dumps(report,indent=2))
    elif sys.argv[1]=='prepare-login':
        assert api('/api/android/runtime')['automaticRepliesEnabled'] is False
        for provider in ['google-ai-pro','chatgpt-plus']:
            api('/api/android/subscriptions/login','POST',{'provider':provider})
            for attempt in range(20):
                pending=api('/api/android/subscriptions')['pending']
                if pending.get('url'):break
                time.sleep(0.25)
            assert pending['status']=='waiting' and pending['url']
            url=urllib.parse.urlparse(pending['url']);query=urllib.parse.parse_qs(url.query)
            assert url.scheme=='https' and url.hostname in ['accounts.google.com','auth.openai.com']
            callback=urllib.parse.urlparse(query['redirect_uri'][0]);assert callback.hostname in ['localhost','127.0.0.1']
            assert query['code_challenge_method']==['S256'] and query['state'][0]
            api('/api/android/subscriptions/cancel','POST',{})
            for attempt in range(20):
                if api('/api/android/subscriptions')['pending']['status']!='waiting':break
                time.sleep(0.25)
            assert api('/api/android/subscriptions')['pending']['status']=='cancelled'
        report={'schema':1,'bothNativeOAuthListenersAndCancellationVerified':True,'credentialAuthorizationsCompleted':False,'qqMessagesSent':0,'modelCalls':0}
        (root/'runtime/local-setup/OPPO-A6m-subscription-oauth-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity');time.sleep(1)
        click('模型账号 · ChatGPT / Google');time.sleep(2);click('Continue with ChatGPT');time.sleep(4)
        pending=api('/api/android/subscriptions')['pending'];assert pending['status']=='waiting'
        print(json.dumps({**report,'chatgptLoginWaitingOnPhone':True}))
    elif sys.argv[1]=='open-google':
        assert api('/api/android/runtime')['automaticRepliesEnabled'] is False
        adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity');time.sleep(1)
        click('模型账号 · ChatGPT / Google');time.sleep(2)
        adb('shell','input','swipe','420','1390','420','440','600');time.sleep(1)
        click('使用 Google 账号连接');time.sleep(4)
        pending=api('/api/android/subscriptions')['pending'];assert pending['status']=='waiting' and pending['provider']=='google-ai-pro'
        print(json.dumps({'googleAuthorizationOpened':True,'automaticRepliesRemainPaused':True}))
    elif sys.argv[1]=='status':
        status=api('/api/android/subscriptions')
        print(json.dumps({'accounts':[{'provider':p['id'],'connected':p['connected'],'authorized':p.get('authorized',False),'setupCode':p.get('setupCode','')} for p in status['providers']],
            'pendingStatus':(status.get('pending') or {}).get('status'),'pendingProvider':(status.get('pending') or {}).get('provider'),
            'error':(status.get('pending') or {}).get('error'),'stage':(status.get('pending') or {}).get('stage'),
            'automaticRepliesEnabled':api('/api/android/runtime')['automaticRepliesEnabled']},indent=2))
    elif sys.argv[1]=='verify-google-save':
        before=api('/api/android/subscriptions')
        google=next(p for p in before['providers'] if p['id']=='google-ai-pro')
        assert google.get('authorized') and not google['connected']
        assert api('/api/android/runtime')['automaticRepliesEnabled'] is False
        encrypted=adb('exec-out','run-as','cn.qqagent.android','cat','files/credentials/subscriptions.enc')
        assert len(encrypted)>64
        old_link=json.loads(adb('exec-out','run-as','cn.qqagent.android','cat','files/runtime/state/console-link.json'))
        adb('shell','run-as','cn.qqagent.android','am','start-foreground-service','--user','0','-n','cn.qqagent.android/cn.qqagent.android.gate.RuntimeForegroundService','-a','cn.qqagent.android.RELOAD_CORE')
        restarted=False
        for attempt in range(18):
            time.sleep(2)
            try:
                new_link=json.loads(adb('exec-out','run-as','cn.qqagent.android','cat','files/runtime/state/console-link.json'))
                if new_link['token']==old_link['token']:continue
                after=api('/api/android/subscriptions')
                restored=next(p for p in after['providers'] if p['id']=='google-ai-pro')
                assert restored.get('authorized') and not restored['connected']
                assert restored['setupCode']==google['setupCode']
                assert api('/api/android/runtime')['automaticRepliesEnabled'] is False
                restarted=True;break
            except (urllib.error.URLError,ConnectionError,TimeoutError):pass
        assert restarted,'Core did not reload'
        cipher_after=adb('exec-out','run-as','cn.qqagent.android','cat','files/credentials/subscriptions.enc')
        assert encrypted==cipher_after
        report={'schema':1,'timestamp':datetime.datetime.now().isoformat(),'appVersion':'0.5.1-subscription-setup',
            'googleAuthorizationSaved':True,'encryptedCredentialFilePresent':True,'credentialRestoredAfterCoreReload':True,
            'credentialCipherUnchangedAcrossReload':True,'googleModelReady':False,'setupCode':restored['setupCode'],
            'safeSetupError':restored.get('setupError',''),'specificIneligibilityReasonConfirmed':False,
            'subscriptionInferenceVerified':False,'externalModelCallsByVerification':0,'qqMessagesSentByVerification':0,
            'automaticRepliesRemainPaused':True}
        (root/'runtime/local-setup/OPPO-A6m-google-authorization-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report,ensure_ascii=True,indent=2))
    elif sys.argv[1]=='verify':
        report={'schema':1,'timestamp':datetime.datetime.now().isoformat(),'appVersion':'0.5.1-subscription-setup','qqMessagesSentByVerification':0,'externalModelCallsByVerification':0,'actualSubscriptionAuthorizationsVerified':False,'passed':False}
        try:
            package=adb('shell','dumpsys','package','cn.qqagent.android').decode(errors='replace')
            assert 'versionName=0.5.1-subscription-setup' in package and re.search(r'versionCode=16\b',package)
            report['installedVersionVerified']=True
            for attempt in range(18):
                try:
                    runtime=api('/api/android/runtime');status=api('/api/android/subscriptions')
                    if runtime['onebotConnected']:break
                except Exception:pass
                time.sleep(3)
            assert runtime['automaticRepliesEnabled'] is False
            report['automaticRepliesRemainPaused']=True;report['qqConnected']=runtime['onebotConnected']
            before=json.loads(baseline_file.read_text(encoding='utf-8'));after=api('/api/config')
            for key in keys:assert after.get(key)==before['settings'][key],'Changed user setting: '+key
            assert after['api']['baseUrl']==before['api']['baseUrl'] and after['api']['model']==before['api']['model']
            cipher=adb('exec-out','run-as','cn.qqagent.android','cat','files/credentials/model-settings.enc')
            assert hashlib.sha256(cipher).hexdigest()==before['manualCipherHash']
            report['userSettingsAndEncryptedManualApiPreserved']=True
            assert set(p['id'] for p in status['providers'])=={'chatgpt-plus','google-ai-pro'}
            assert all(not p['connected'] for p in status['providers'])
            assert not next(p for p in status['providers'] if p['id']=='chatgpt-plus')['models']
            assert next(p for p in status['providers'] if p['id']=='google-ai-pro')['models']
            report['providerModelCounts']={p['id']:len(p['models']) for p in status['providers']}
            assert not any(word in json.dumps(status) for word in ['access_token','refresh_token','apiKey','idToken','client_secret'])
            report['statusExcludesCredentials']=True
            try:api('/api/android/subscriptions',authenticated=False);raise AssertionError('Unauthenticated access accepted')
            except urllib.error.HTTPError as e:assert e.code==401
            report['privateApiAuthenticationVerified']=True
            try:api('/api/android/subscriptions/select','POST',{'provider':'chatgpt-plus','model':'gpt-5-mini'});raise AssertionError('Unconnected account selected')
            except urllib.error.HTTPError as e:assert e.code==400
            adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity');time.sleep(2)
            click('模型账号 · ChatGPT / Google');time.sleep(3)
            texts=[n.attrib.get('text','') for n in snapshot().iter('node')]
            assert '模型账号' in texts and 'Continue with ChatGPT' in texts
            report['homeAccountEntryAndChatgptLoginVisible']=True
            adb('shell','input','swipe','420','1390','420','440','600');time.sleep(1)
            texts=[n.attrib.get('text','') for n in snapshot().iter('node')]
            assert '使用 Google 账号连接' in texts
            report['googleLoginVisible']=True
            (root/'runtime/local-setup/OPPO-A6m-subscriptions-0.5.1.png').write_bytes(adb('exec-out','screencap','-p'))
            adb('shell','input','keyevent','4');time.sleep(2)
            assert api('/api/android/runtime')['automaticRepliesEnabled'] is False
            report['passed']=True
        finally:
            (root/'runtime/local-setup/OPPO-A6m-subscriptions-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report,ensure_ascii=True,indent=2))
finally:
    adb('forward','--remove','tcp:'+port)
