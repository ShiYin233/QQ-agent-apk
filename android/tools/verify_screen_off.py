"""Bounded screen-off check while USB remains connected; not a 24-hour or Doze test."""
import datetime, json, pathlib, re, subprocess, time, urllib.request
root = pathlib.Path(__file__).resolve().parents[2]
adb_path = root / 'runtime/local-setup/adb/adb.exe'
def adb(*args): return subprocess.run([str(adb_path), '-d', *args], capture_output=True, check=True, timeout=15).stdout
def service(): return json.loads(adb('exec-out','run-as','cn.qqagent.android','cat','files/foreground-service-report.json'))
def sleeping(): return bool(re.search(rb'mWakefulness=Asleep|mWakefulness=Dozing', adb('shell','dumpsys','power')))
def endpoint():
    token = json.loads(adb('exec-out','run-as','cn.qqagent.android','cat','files/runtime/state/console-link.json'))['token']
    request = urllib.request.Request(f'http://127.0.0.1:{port}/api/android/runtime', headers={'x-console-token': token})
    with urllib.request.urlopen(request, timeout=10) as response:
        data = json.load(response)
        return {key: data[key] for key in ['coreRunning','onebotConnected','automaticRepliesEnabled']}
port = int(adb('forward','tcp:0','tcp:3312').strip())
report = {'schema': 1, 'timestamp': datetime.datetime.now().isoformat(), 'usbConnected': True, 'twentyFourHourPassed': False, 'dozeTestPassed': False, 'samples': [], 'passed': False}
before = service(); start = time.monotonic()
report['appVersion'] = before['appVersion']
destination = root / 'runtime/local-setup/OPPO-A6m-screen-off-report.json'
if destination.exists():
    previous = json.loads(destination.read_text(encoding='utf-8'))
    stamp = re.sub(r'[^0-9]', '', previous['timestamp'])
    destination.with_name(f'OPPO-A6m-screen-off-{stamp}.json').write_text(json.dumps(previous, indent=2), encoding='utf-8')
try:
    assert before['coreHttpReachable'] and before['oneBotHttpReachable']
    assert endpoint()['onebotConnected']
    adb('shell','input','keyevent','KEYCODE_HOME'); adb('shell','input','keyevent','KEYCODE_SLEEP')
    for index in range(5):
        time.sleep(15)
        state = service()
        sample = {'elapsedSeconds': round(time.monotonic()-start,1), 'screenAsleep': sleeping(), 'reportTimestamp': state['timestamp'], 'qqRunning': state['qqProcessRunning'], 'coreProcessRunning': state['coreProcessRunning']}
        report['samples'].append(sample)
        sample.update(endpoint())
        assert sample['screenAsleep'] and sample['qqRunning'] and sample['coreProcessRunning'] and sample['coreRunning'] and sample['onebotConnected'] and not sample['automaticRepliesEnabled']
    assert report['samples'][-1]['reportTimestamp'] > before['timestamp']
    report['durationSeconds'] = round(time.monotonic()-start,1); report['passed'] = True; report['status'] = 'PASSED_SHORT_USB_CHECK'
except Exception as error:
    report['errorType'] = type(error).__name__
    state = subprocess.run([str(adb_path), '-d', 'get-state'], capture_output=True, timeout=5)
    connected = state.stdout.strip() == b'device'
    report['deviceConnectedAtFailure'] = connected
    report['status'] = 'FAILED_REQUIRES_DIAGNOSIS' if connected else 'INCONCLUSIVE_DEVICE_DISCONNECTED'
    raise
finally:
    try: adb('shell','input','keyevent','KEYCODE_WAKEUP')
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired): pass
    try: adb('forward','--remove',f'tcp:{port}')
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired): pass
    destination.write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({'passed': report['passed'], 'durationSeconds': report.get('durationSeconds'), 'usbConnected': True, 'twentyFourHourPassed': False}))
