"""Read-only phone/UI checks. Never refresh a logged-in QQ, resume replies or send."""
import datetime, json, pathlib, re, subprocess, time, urllib.request, xml.etree.ElementTree as ET
from adb_device import device_arguments
root = pathlib.Path(__file__).resolve().parents[2]
adb_path = root / 'runtime/local-setup/adb/adb.exe'
selected = device_arguments(adb_path)
def adb(*args):
    return subprocess.check_output([str(adb_path), *selected, *args], timeout=25)
def snapshot():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/qqagent-login-check.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/data/local/tmp/qqagent-login-check.xml'))
def click(text):
    nodes = [n for n in snapshot().iter('node') if n.attrib.get('package') == 'cn.qqagent.android' and n.attrib.get('text') == text]
    assert nodes, 'Missing UI control: ' + text
    left, top, right, bottom = map(int, re.findall(r'\d+', nodes[0].attrib['bounds']))
    adb('shell', 'input', 'tap', str((left + right)//2), str((top + bottom)//2))
def texts(ui): return [n.attrib.get('text','') for n in ui.iter('node')]
report = {'schema':1,'timestamp':datetime.datetime.now().isoformat(),'appVersion':'0.4.4-login-mentions',
          'qqMessagesSentByVerification':0,'modelCallsByVerification':0,'expiredQrPhoneTestPerformed':False,'passed':False}
port = None
try:
    package = adb('shell','dumpsys','package','cn.qqagent.android').decode(errors='replace')
    assert 'versionName=0.4.4-login-mentions' in package and re.search(r'versionCode=14\b',package)
    report['installedVersionVerified'] = True
    adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity')
    port = adb('forward','tcp:0','tcp:3312').decode().strip()
    def api(path):
        link = json.loads(adb('exec-out','run-as','cn.qqagent.android','cat','files/runtime/state/console-link.json'))
        request = urllib.request.Request(f'http://127.0.0.1:{port}{path}',headers={'x-console-token':link['token']})
        return json.load(urllib.request.urlopen(request,timeout=10))
    for attempt in range(12):
        try:
            runtime = api('/api/android/runtime')
            if runtime.get('onebotConnected'): break
        except Exception: pass
        time.sleep(3)
    assert runtime['onebotConnected'] and runtime['automaticRepliesEnabled'] is False
    report['qqConnectedWhileRepliesPaused'] = True
    skills = api('/api/skills')['skills']
    plugin = next(s for s in skills if s['id'] == 'android-mention-identity')
    assert plugin['active'], plugin.get('reason')
    report['mentionPluginLoadedAndActive'] = True
    before = json.loads((root/'runtime/local-setup/private-qq-logs/login-mention-config-before.json').read_text(encoding='utf-8'))
    after = api('/api/config')
    # Core credentials intentionally differ across launches; preserve actual user settings.
    keys = ['allow','deny','store','send','persona','personaByChat','chatActive','proactive','globalBlocklist']
    for key in keys: assert after.get(key) == before.get(key), 'User setting changed: ' + key
    report['userSettingsPreserved'] = keys
    prefs = ET.fromstring(adb('exec-out','run-as','cn.qqagent.android','cat','shared_prefs/runtime-service.xml'))
    flags = {n.attrib['name']:n.attrib.get('value') for n in prefs}
    assert flags['repliesEnabled'] == 'false' and flags['enabled'] == 'true'
    report['nativePausePreserved'] = True
    click('QQ 登录')
    for attempt in range(8):
        ui = snapshot()
        if 'QQ 已登录' in texts(ui): break
        time.sleep(1)
    assert 'QQ 已登录' in texts(ui)
    assert '刷新登录二维码' not in texts(ui)
    assert not any(n.attrib.get('content-desc') == 'QQ 登录二维码' for n in ui.iter('node'))
    report['homeLoginOpensInOneTap'] = True
    report['liveLoginStateRendered'] = True
    report['loggedInHidesExpiredQrAndRefresh'] = True
    (root/'runtime/local-setup/OPPO-A6m-login-0.4.4.png').write_bytes(adb('exec-out','screencap','-p'))
    click('同步登录状态'); time.sleep(2)
    assert 'QQ 已登录' in texts(snapshot())
    click('返回')
    assert api('/api/android/runtime')['automaticRepliesEnabled'] is False
    report['readOnlySyncPreservesPause'] = True
    report['passed'] = True
finally:
    if port: adb('forward','--remove','tcp:'+port)
    adb('shell','rm','-f','/data/local/tmp/qqagent-login-check.xml')
    (root/'runtime/local-setup/OPPO-A6m-login-mentions-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=True,indent=2))
