"""Build/evidence summary with explicit scope; raw reports remain private and ignored."""
import datetime, hashlib, json, pathlib, re, shutil, subprocess, xml.etree.ElementTree as ET
root = pathlib.Path(__file__).resolve().parents[2]
android = root / 'android'
local = root / 'runtime/local-setup'
def load(name): return json.loads((local / name).read_text(encoding='utf-8-sig'))
gate = load('OPPO-A6m-gate-report.json')
console = load('OPPO-A6m-native-console-report.json')
pages = load('OPPO-A6m-native-pages-report.json')
media = load('OPPO-A6m-media-runtime-report.json')
screen = load('OPPO-A6m-screen-off-report.json')
foreground = load('OPPO-A6m-foreground-service-report.json')
background_settings = load('OPPO-A6m-background-settings-report.json')
assert gate['gatePassed'] and console['passed'] and pages['passed'] and media['passed']
apk = android / 'app/build/outputs/apk/debug/app-debug.apk'
version = json.loads((apk.parent / 'output-metadata.json').read_text(encoding='utf-8'))['elements'][0]['versionName']
assert foreground['appVersion'] == version and console['runtime']['appVersion'] == version
assert screen.get('appVersion') == version
assert background_settings['appVersion'] == version and background_settings['oppoFullyAllowBackground']
filename = f'QQ-Agent-{version}-arm64-debug.apk'
artifact = android / 'artifacts' / filename
artifact.parent.mkdir(exist_ok=True)
shutil.copyfile(apk, artifact)
digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
artifact.with_suffix('.apk.sha256').write_text(f'{digest}  {filename}\n', encoding='ascii')
suites = []
for file in (android / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(file).getroot()
    suites.append({key: suite.get(key) for key in ['name', 'tests', 'failures', 'errors', 'skipped']})
lint = ET.parse(android / 'app/build/reports/lint-results-debug.xml').getroot()
issues = [{'id': issue.get('id'), 'severity': issue.get('severity')} for issue in lint.findall('issue')]
assert not any(issue['severity'] in ['Error','Fatal'] for issue in issues)
assert all(suite['failures'] == '0' and suite['errors'] == '0' for suite in suites)
summary = {
    'schema': 2, 'timestamp': datetime.datetime.now().isoformat(), 'appVersion': version,
    'applicationId': 'cn.qqagent.android', 'originalCoreCommit': 'e2dc5bbeec4f2b5bde884676cd4917681da84d36',
    'firstGate': 'PASSED_ON_DEVICE', 'firstGateEvidenceAppVersion':gate.get('appVersion'),
    'firstGateStages': [{key: result[key] for key in ['stage','outcome']} for result in gate['results']],
    'device': {'name': 'OPPO A6m 5G', 'model': 'PLT140', 'api': 35, 'pageSize': 4096, 'abi': 'arm64-v8a', 'usbConnected': True},
    'sdk': {'min': 31, 'compile': 36, 'target': 28, 'buildTools': '36.0.0'},
    'build': {'assembleDebug':'PASSED', 'testDebugUnitTest':'PASSED', 'lintDebug':'PASSED_WITH_WARNINGS',
              'jdk':'Oracle 21.0.2', 'gradle':'8.13', 'agp':'8.13.2', 'kotlin':'2.2.21', 'lintIssues':issues},
    'unitTests': suites, 'adapterTests': {'dsmlPassed':7, 'androidPolicyPassed':3},
    'apk': {'filename':filename, 'bytes':artifact.stat().st_size, 'sha256':digest, 'signature':'Verified Android local debug signing; not release signing',
            'certificateSha256':'d343b5a0fdaedbea9c6777b90b53eb75c4752187a5d09bb4f25ae5d6e87ec955',
            'zipalign16k':'PASSED; not proof of 16 KB runtime compatibility'},
    'nativeConsole': {'pages':pages['pages'], 'authenticatedRoutes':len(console['routes']), 'invalidToken':console['invalidToken'],
                      'foreignOrigin':console['foreignOrigin'], 'sseHello':console['sseHello'], 'emptyAllowListResumeStatus':console['emptyAllowListResumeStatus'],
                      'configurationContainsSecrets':console['configurationContainsSecrets'], 'automaticRepliesEnabled':False, 'telemetryEnabled':False,
                      'scope':'Basic navigation/read and selected operations; desktop parity and empty-profile details not fully covered'},
    'media':media['details'],
    'background': {'notificationEnabled':foreground['notificationsEnabled'], 'qqRecoverySeconds':load('OPPO-A6m-foreground-recovery-report.json')['recoverySeconds'], 'qqRecoveryAppVersion':'0.3.0-native-runtime-service',
                   'screenOffPassed':screen['passed'], 'screenOffStatus':screen['status'], 'screenOffAppVersion':screen.get('appVersion'),
                   'screenOffSeconds':screen.get('durationSeconds'), 'screenOffSamples':len(screen['samples']),
                   'backgroundSetting':'OPPO completely allow background activity; selected in device UI after connected screen-off timeout',
                   'nativeSettingsEntryVerified':background_settings['nativeSettingsEntryVerified'],
                   'oppoSettingVerifiedAfterUpdate':background_settings['oppoFullyAllowBackground'],
                   'usbConnectedAtStart':True, 'usbConnectedAtEnd':screen.get('deviceConnectedAtFailure', screen['passed']),
                   'twentyFourHourPassed':False, 'dozePassed':False, 'rebootRestore':'NOT_RUN'},
    'remaining':['Complete in-app first initialization/download UI','Full desktop page parity','Account switching','Community login/captcha and real install verification',
                 'Encrypted backup/restore','Component rollback','Release signing and redistribution audit','Real QQ media/model regression','24-hour/network/reboot/multi-ROM/16KB/battery validation'],
    'rawEvidencePolicy':'Raw QQ logs, device identifiers, credentials and full phone reports kept only in ignored local private evidence; excluded from source and APK'
}
(android / 'docs/build-validation.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
progress = f'''# QQ Agent 本地进度（2026-10-06）

手机已安装 {version}。OPPO A6m 5G / Android 15 / ARM64 / 4 KB 页。

- 第一关七项已通过：初始化、登录、群好友列表、私聊和群聊双向收发、原 Agent 模型 pong 回复。
- 八个原生页面已逐页读取验证；正式账号为空名单，自动回复暂停。需要运行时可在“协议管理”选择保存允许回复的聊天，再点击启用。
- 通知已开启，前台服务维持 QQ 和核心，包含暂停/恢复/停止；QQ 受控退出后约 30.8 秒恢复。
- 图片压缩、GIF 转视频、原 VideoReader 抽帧通过；不等于真实 QQ 媒体和视觉模型回归已完成。
- 密钥加密保存在手机，核心配置不含密钥；原遥测默认关闭。
- 原先“智能优化后台运行”时，USB 连通仍出现熄屏接口超时。现已在 OPPO 耗电管理选择“完全允许后台行为”；新版设置页提供入口。最新短时熄屏检查：{screen['status']}，时长 {screen.get('durationSeconds', '未完成')} 秒。只覆盖 USB 连接下的短时熄屏，不能代替 24 小时、Doze、断网和重启验收。

完整 QQ 日志在 private-qq-logs，保持当前用户私有访问权限并由 Git 忽略。可运行 android/tools/export_private_logs.py --authorized-private-logs 再次同步。
原报告、原生页面截图、媒体依赖清单和构建日志均在本目录。当前 APK 在 android/artifacts，源码验证摘要见 android/docs/build-validation.json。

仍待完成：应用内首次下载、全部桌面页面操作对齐、账号切换、社区登录/验证码、加密备份、回滚、正式签名与许可审查、24 小时及多设备验收。无需重复有限收发测试或再次发送模型测试请求。
'''
(local / '安装进度与手机准备.md').write_text(progress, encoding='utf-8')
print(json.dumps({'artifact':filename, 'sha256':digest, 'bytes':artifact.stat().st_size, 'androidUnitTests':sum(int(suite['tests']) for suite in suites), 'lintErrors':0, 'lintWarnings':len(issues)}, indent=2))
