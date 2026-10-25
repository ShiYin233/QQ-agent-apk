"""Exercise the actual Compose pages over ADB; never click reply or send controls."""
import datetime, json, pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')
root = pathlib.Path(__file__).resolve().parents[2]
adb_path = root / 'runtime/local-setup/adb/adb.exe'
def adb(*args):
    return subprocess.run([str(adb_path), '-d', *args], capture_output=True, check=True).stdout
def snapshot():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/qqagent-native-pages.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/data/local/tmp/qqagent-native-pages.xml'))
def bounds(node): return [int(x) for x in re.findall(r'\d+', node.attrib['bounds'])]
def choose(tab):
    for attempt in range(4):
        ui = snapshot()
        matches = [node for node in ui.iter('node') if node.attrib.get('package') == 'cn.qqagent.android' and node.attrib.get('text') == tab and bounds(node)[1] < 430]
        if matches:
            left, top, right, bottom = bounds(matches[0])
            adb('shell', 'input', 'tap', str((left+right)//2), str((top+bottom)//2)); return
        adb('shell', 'input', 'swipe', '650', '365', '80', '365', '500')
    raise AssertionError(f'Tab not visible: {tab}')
pages = [('会话','模型处理记录'), ('存档','聊天存档'), ('记忆','长期记忆'), ('用量','模型用量'), ('技能','原核心内置工具'), ('插件','安卓视频抽帧'), ('协议管理','手机 QQ 协议'), ('设置','模型连接')]
report = {'schema': 1, 'timestamp': datetime.datetime.now().isoformat(), 'nativeCompose': True, 'onlyNavigationClicked': True, 'pages': [], 'passed': False}
adb('shell', 'am', 'start', '-n', 'cn.qqagent.android/cn.qqagent.android.gate.MainActivity')
try:
    for tab, title in pages:
        choose(tab)
        found = False
        for attempt in range(5):
            time.sleep(1)
            texts = [node.attrib.get('text','') for node in snapshot().iter('node') if node.attrib.get('package') == 'cn.qqagent.android']
            found = title in texts and not any('控制台正在启动或已停止' in text for text in texts)
            if found: break
        assert found, f'Page data did not render: {tab}'
        report['pages'].append({'page': tab, 'contentRendered': True})
    (root / 'runtime/local-setup/OPPO-A6m-native-settings.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    report['passed'] = True
finally:
    (root / 'runtime/local-setup/OPPO-A6m-native-pages-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    adb('shell', 'rm', '-f', '/data/local/tmp/qqagent-native-pages.xml')
print(json.dumps(report, ensure_ascii=False, indent=2))
