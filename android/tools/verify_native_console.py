"""Read-only device verification. Reports contain counts and states, never tokens or chats."""
import datetime, json, pathlib, re, subprocess, time, urllib.request, urllib.error

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = ROOT / 'runtime/local-setup/adb/adb.exe'
REPORT = ROOT / 'runtime/local-setup/OPPO-A6m-native-console-report.json'

def adb(*args):
    result = subprocess.run([str(ADB), '-d', *args], capture_output=True, check=True)
    return result.stdout

token = json.loads(adb('exec-out', 'run-as', 'cn.qqagent.android', 'cat', 'files/runtime/state/console-link.json'))['token']
port = int(adb('forward', 'tcp:0', 'tcp:3312').strip())
base = f'http://127.0.0.1:{port}'

def get(path, *, invalid=False, origin=None, body=None):
    headers = {'x-console-token': 'invalid' if invalid else token}
    if origin: headers['Origin'] = origin
    if body is not None: headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + path, headers=headers, data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, {}

report = {'schema': 1, 'timestamp': datetime.datetime.now().isoformat(), 'platform': 'android', 'qqMessagesSentByVerifier': 0, 'modelInferenceCallsByVerifier': 0, 'routes': {}}
try:
    for path in ['/api/status', '/api/sessions?limit=100', '/api/chats', '/api/memory-files', '/api/usage/stats?range=7', '/api/skills', '/api/tools', '/api/tools/availability', '/api/config', '/api/onebot/groups', '/api/onebot/friends', '/api/android/runtime', '/api/android/capabilities', '/api/android/media']:
        code, data = get(path)
        assert code == 200, (path, code)
        report['routes'][path] = {'status': code, 'arrayCounts': {k: len(v) for k, v in data.items() if isinstance(v, list)}}
        if path == '/api/android/runtime':
            report['runtime'] = {k: data.get(k) for k in ['appVersion', 'coreRunning', 'onebotConnected', 'automaticRepliesEnabled', 'telemetryEnabled', 'consolePort']}
            assert data['onebotConnected'] and not data['automaticRepliesEnabled'] and not data['telemetryEnabled']
        if path == '/api/config':
            bot_name = data.get('persona', {}).get('botName', '')
            assert not data.get('api', {}).get('apiKey'), 'API response leaked a key'
            assert data.get('allowAllWhenEmpty') is False
            assert not data.get('allow', {}).get('groups') and not data.get('allow', {}).get('private')
    report['emptyAllowListResumeStatus'] = get('/api/android/replies', body={'enabled': True})[0]
    assert report['emptyAllowListResumeStatus'] == 409
    # Round-trip an unchanged setting through the actual config writer with the live key in memory.
    assert get('/api/config', body={'persona': {'botName': bot_name}})[0] == 200
    assert get('/api/config')[1]['persona']['botName'] == bot_name
    report['unchangedConfigurationRoundTrip'] = True
    report['invalidToken'] = {path: get(path, invalid=True)[0] for path in ['/api/status', '/api/android/runtime']}
    assert all(code == 401 for code in report['invalidToken'].values())
    report['foreignOrigin'] = {path: get(path, origin='https://untrusted.invalid')[0] for path in ['/api/status', '/api/android/runtime']}
    assert all(code == 403 for code in report['foreignOrigin'].values())
    request = urllib.request.Request(base + '/api/events', headers={'x-console-token': token})
    with urllib.request.urlopen(request, timeout=20) as response:
        report['sseHello'] = response.readline().decode().strip() == 'event: hello'
    assert report['sseHello']
    link = json.loads(adb('exec-out', 'run-as', 'cn.qqagent.android', 'cat', 'files/runtime/state/protocol-link.json'))
    assert re.fullmatch(r'\d{5,15}', link['userId'])
    cfg = json.loads(adb('exec-out', 'run-as', 'cn.qqagent.android', 'cat', f"files/runtime/state/data-account-{link['userId']}/config.json"))
    def has_secret(value):
        if isinstance(value, dict):
            return any((re.search(r'api.?key|access.?token|secret|password|cookie|^token$|providerKeys', key, re.I) and bool(child)) or has_secret(child) for key, child in value.items())
        return isinstance(value, list) and any(has_secret(child) for child in value)
    report['configurationContainsSecrets'] = has_secret(cfg)
    assert not report['configurationContainsSecrets']
    report['credentialCiphertextBytes'] = len(adb('exec-out', 'run-as', 'cn.qqagent.android', 'cat', 'files/credentials/model-settings.enc'))
    report['passed'] = True
finally:
    try: adb('forward', '--remove', f'tcp:{port}')
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired): pass
    target = REPORT if report.get('passed') else REPORT.with_name(REPORT.stem + '-failed-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.json')
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
