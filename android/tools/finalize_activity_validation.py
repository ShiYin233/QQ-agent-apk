"""Publish the stopped-runtime repair candidate, retaining earlier evidence as historical."""
import datetime,hashlib,json,pathlib,shutil,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[2];android=root/'android';local=root/'runtime/local-setup'
report=json.loads((local/'OPPO-A6m-activity-settings-report.json').read_text(encoding='utf-8'))
assert report['passed'] and report['settingsSaveLeavesRuntimeStopped'] and report['reopenPreservesPauseAndTier']
apk=android/'app/build/outputs/apk/debug/app-debug.apk'
version=json.loads((apk.parent/'output-metadata.json').read_text())['elements'][0]['versionName']
assert version==report['appVersion']
artifact=android/'artifacts'/f'QQ-Agent-{version}-arm64-debug.apk';shutil.copyfile(apk,artifact)
digest=hashlib.sha256(artifact.read_bytes()).hexdigest();artifact.with_suffix('.apk.sha256').write_text(f'{digest}  {artifact.name}\n',encoding='ascii')
suites=[ET.parse(file).getroot() for file in (android/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert all(s.get('failures')=='0' and s.get('errors')=='0' for s in suites)
issues=ET.parse(android/'app/build/reports/lint-results-debug.xml').getroot().findall('issue')
assert not any(i.get('severity') in ['Error','Fatal'] for i in issues)
validation=android/'docs/build-validation.json'
old=json.loads(validation.read_text(encoding='utf-8'))
if old['appVersion']=='0.4.2-background-settings':
    (android/'docs/build-validation-0.4.2.json').write_text(json.dumps(old,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
summary={'schema':3,'timestamp':datetime.datetime.now().isoformat(),'appVersion':version,'applicationId':'cn.qqagent.android',
 'originalCoreCommit':'e2dc5bbeec4f2b5bde884676cd4917681da84d36','originalCoreSourcesChanged':False,
 'build':{'assembleDebug':'PASSED','androidUnitTests':sum(int(s.get('tests')) for s in suites),'androidUnitTestFailures':0,'nodeRegressionTests':20,'lintErrors':0,'lintWarnings':len(issues)},
 'apk':{'filename':artifact.name,'bytes':artifact.stat().st_size,'sha256':digest,'signing':'Local debug signing; certificate unchanged; signature and alignment checked separately'},
 'incident':{'userReportedAccountRestriction':True,'observedProfile':{'contextTier':4,'contextSliderPosMissing':True,'allowedGroups':4,'peakScheduleEnabled':False,'chatActiveEnabled':False},
 'reason':'Android UI omitted activity settings; legacy core default resolved ordinary group messages to tier 4. No claim about Tencent internal enforcement reason.',
 'rawBackup':'Account profile and QQ logs backed up only in ignored private local evidence'},
 'repair':{'activityEditor':'Native; global/per-group/peak-valley/keywords/history/continued-participation/send-limits; draft save and cancel; usable offline',
 'freshProfileTier':1,'legacyUpgrade':'Persistently disable runtime and replies before restoring services; activity review required before resume',
 'outboundPauseGuard':'OneBot dispatch blocks paused or obsolete queued text/media/poke tasks and rechecks current allow list',
 'peakTimeZone':'Device timezone passed to Node runtime'},
 'deviceValidation':report,'currentRuntime':{'qqRunning':False,'coreRunning':False,'automaticRepliesEnabled':False,'savedGlobalTier':1,'savedRandomPercent':0,'perChatSendLimits':report['perChatSendLimits']},
 'validationBoundary':'Response decisions and send queue tested locally with synthetic events and mocked QQ dispatch. Native Android edit/cancel/save/reopen tested with QQ/core stopped. No real QQ messages or model calls during incident repair.',
 'earlierValidation':'docs/build-validation-0.4.2.json is historical; prior QQ/model and screen-off checks were not rerun on the restricted account.',
 'remaining':['Account restriction not resolved or retested','Real account sending verification remains stopped','Complete original page parity and initialization','Community login, encrypted backup, rollback and release signing','24-hour/network/reboot/multi-ROM/16KB/battery validation']}
validation.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(local/'安装进度与手机准备.md').write_text('''# QQ Agent 修复进度（2026-10-07）

已安装 0.4.3-activity-settings。因用户报告 QQ 封号，QQ 和核心保持停止，自动回复关闭，重开应用不会自动恢复。
原配置为 4 档全响应、允许 4 个群、峰谷关闭；原始配置和记录已保存于 private-qq-logs。
活跃设置支持离线编辑、保存与取消；当前全局保存为 1 档、随机概率 0，未启用回复。发送上限已保存为每个聊天 3 条/分钟、30 条/小时；达到上限拒绝发送。
手机验证了 15% 档草稿、取消保留原配置、保存仍暂停、重开仍暂停。原核心响应规则和发送队列用模拟事件/模拟 QQ 派发验证，未登录 QQ、发送 QQ 消息或请求模型。
暂停时拦截出站，旧队列任务在再次恢复后也不会补发。峰谷使用手机时区。旧版本升级自动关闭运行与回复，须保存活跃设置后才能启用。
修复不能视为封号已解除。之前的实机收发与短时熄屏验收是旧版本历史记录。
安装包位于 android/artifacts，当前验证摘要见 android/docs/build-validation.json，详细说明见 android/docs/activity-settings-repair.md。
''',encoding='utf-8')
print(json.dumps({'artifact':artifact.name,'sha256':digest,'androidUnitTests':summary['build']['androidUnitTests'],'nodeTests':20,'qqMessagesSent':0,'runtimeStopped':True},indent=2))
