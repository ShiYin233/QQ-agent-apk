"""Verify native drafts and persistence with QQ/core stopped; sends no messages."""
import datetime, json, pathlib, re, subprocess, time, xml.etree.ElementTree as ET
from adb_device import device_arguments
root = pathlib.Path(__file__).resolve().parents[2]
adb_path = root / 'runtime/local-setup/adb/adb.exe'
selected = device_arguments(adb_path)
def adb(*args): return subprocess.run([str(adb_path), *selected, *args], capture_output=True, check=True, timeout=20).stdout
def snapshot():
    adb('shell','uiautomator','dump','/data/local/tmp/qqagent-activity.xml')
    return ET.fromstring(adb('exec-out','cat','/data/local/tmp/qqagent-activity.xml'))
def click(text, scroll=False):
    for _ in range(5 if scroll else 1):
        ui=snapshot()
        found=[n for n in ui.iter('node') if n.attrib.get('package')=='cn.qqagent.android' and n.attrib.get('text')==text]
        if found:
            bounds=[int(v) for v in re.findall(r'\d+',found[0].attrib['bounds'])]
            adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2));return
        if scroll: adb('shell','input','swipe','360','1220','360','570','400')
    raise AssertionError('UI control missing: '+text)
def profile():
    link=json.loads(adb('exec-out','run-as','cn.qqagent.android','cat','files/runtime/state/protocol-link.json'))
    account=link['userId'];assert re.fullmatch(r'\d{5,15}',account)
    return json.loads(adb('exec-out','run-as','cn.qqagent.android','cat',f'files/runtime/state/data-account-{account}/config.json'))
def field(label,value):
    for _ in range(6):
        ui=snapshot()
        fields=[n for n in ui.iter('node') if n.attrib.get('class')=='android.widget.EditText' and any(label in x.attrib.get('text','') for x in n.iter('node'))]
        if fields:
            node=fields[0];bounds=[int(v) for v in re.findall(r'\d+',node.attrib['bounds'])]
            adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
            adb('shell','input','keyevent','KEYCODE_MOVE_END')
            for _ in range(8):adb('shell','input','keyevent','KEYCODE_DEL')
            adb('shell','input','text',value);adb('shell','input','keyevent','KEYCODE_BACK');return
        adb('shell','input','swipe','360','1240','360','540','400')
    raise AssertionError('Editable field missing: '+label)
def stopped():
    prefs=ET.fromstring(adb('exec-out','run-as','cn.qqagent.android','cat','shared_prefs/runtime-service.xml'))
    flags={n.attrib['name']:n.attrib.get('value') for n in prefs}
    assert flags['enabled']=='false' and flags['repliesEnabled']=='false'
    uid=adb('shell','run-as','cn.qqagent.android','id','-u').decode().strip()
    processes=adb('shell','ps','-A','-o','UID,PID,NAME').decode().splitlines()
    guests=[line for line in processes if line.split() and line.split()[0]==uid and re.search(r'\b(proot|node|qq|Xvfb)\b',line)]
    assert not guests, 'QQ/core guest unexpectedly running'
    return True
report={'schema':1,'timestamp':datetime.datetime.now().isoformat(),'appVersion':'0.4.3-activity-settings','qqMessagesSent':0,'modelCalls':0,'passed':False}
try:
    stopped(); before=profile()
    adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity')
    for _ in range(5):
        ui=snapshot();tabs=[n for n in ui.iter('node') if n.attrib.get('text')=='设置' and int(re.findall(r'\d+',n.attrib['bounds'])[1])<500]
        if tabs: click('设置');break
        adb('shell','input','swipe','650','365','80','365','500')
    click('编辑活跃设置',scroll=True)
    click('3档15%')
    texts=[n.attrib.get('text','') for n in snapshot().iter('node')]
    assert any('15.0%' in value and '3 档' in value for value in texts)
    report['fifteenPercentPreview']=True
    (root/'runtime/local-setup/OPPO-A6m-activity-draft.png').write_bytes(adb('exec-out','screencap','-p'))
    click('取消');assert profile()==before;report['cancelPreservesConfiguration']=True
    click('编辑活跃设置',scroll=True);click('1档')
    field('每分钟最多发送条数','3');field('每小时最多发送条数','30')
    click('保存活跃设置并保持暂停')
    for _ in range(10):
        time.sleep(.3);after=profile()
        if after.get('store',{}).get('contextSliderPos')==5: break
    assert after['store']['contextTier']==1 and after['store']['randomPercent']==0
    assert after['androidActivityReviewed'] is True and after['allow']==before['allow']
    assert after['send']['maxPerMinute']==3 and after['send']['maxPerHour']==30
    report['perChatSendLimits']={'maxPerMinute':3,'maxPerHour':30}
    stopped();report['savedTier']=1;report['settingsSaveLeavesRuntimeStopped']=True
    adb('shell','am','force-stop','cn.qqagent.android')
    adb('shell','am','start','-n','cn.qqagent.android/cn.qqagent.android.gate.MainActivity')
    stopped();assert profile()['store']['contextTier']==1
    report['reopenPreservesPauseAndTier']=True;report['passed']=True
finally:
    (root/'runtime/local-setup/OPPO-A6m-activity-settings-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    adb('shell','rm','-f','/data/local/tmp/qqagent-activity.xml')
print(json.dumps(report,indent=2))
