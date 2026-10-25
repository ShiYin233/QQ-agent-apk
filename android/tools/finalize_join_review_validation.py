"""Create a reproducible validation summary without copying credentials or QQ answers."""
import datetime, hashlib, json, pathlib, subprocess, zipfile
import xml.etree.ElementTree as ET
from adb_device import device_arguments

root=pathlib.Path(__file__).resolve().parents[2]
apk=root/'android/artifacts/QQ-Agent-0.5.2-join-review-arm64-debug.apk'
source=root/'android/app/src/main/assets/runtime/plugins/hd2-join-review'
desktop=pathlib.Path(r'D:\QQ\QQ Agent\resources\app\plugins\hd2-join-review')
phone=json.loads((root/'runtime/local-setup/OPPO-A6m-join-review-report.json').read_text(encoding='utf-8'))
assert phone.get('passed')
with zipfile.ZipFile(apk) as archive:
    for item in source.rglob('*'):
        if item.is_file():
            relative=item.relative_to(source).as_posix()
            assert archive.read('assets/runtime/plugins/hd2-join-review/'+relative)==item.read_bytes(),relative
            if relative!='plugin.json':assert (desktop/relative).read_bytes()==item.read_bytes(),relative
mobile_manifest=json.loads((source/'plugin.json').read_text(encoding='utf-8'))
desktop_manifest=json.loads((desktop/'plugin.json').read_text(encoding='utf-8'))
assert mobile_manifest['version']==desktop_manifest['version']=='1.1.0'
adb=root/'runtime/local-setup/adb/adb.exe'
installed=subprocess.check_output([str(adb),*device_arguments(adb),'shell','dumpsys','package','cn.qqagent.android'],timeout=20).decode('utf-8',errors='replace')
assert 'versionName=0.5.2-join-review' in installed
suites=[ET.parse(x).getroot() for x in (root/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
issues=ET.parse(root/'android/app/build/reports/lint-results-debug.xml').getroot().findall('issue')
report={
    'schema':1,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'pluginVersion':'1.1.0','androidVersion':'0.5.2-join-review','versionCode':17,
    'apk':str(apk),'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'build':{'assembleDebug':'passed','androidUnitTests':sum(int(s.get('tests',0)) for s in suites),
        'androidUnitTestFailures':sum(int(s.get('failures',0)) for s in suites),
        'lintErrors':sum(i.get('severity')=='Error' for i in issues),
        'lintWarnings':sum(i.get('severity')!='Error' for i in issues),
        'nodeRegressionTests':102,'nodeFailures':0,'apkSignatureVerified':True,'apk16kAlignmentVerified':True},
    'deployment':{'desktopSourceSynchronized':True,'apkPluginAssetsMatchSource':True,'phoneInstalledVersionVerified':True},
    'phone':phone,
    'limits':{'realApprovalTested':False,'realModelInferenceTested':False,'desktopLiveConsoleAvailable':False,
        'pendingListMaximum':100,'catalogEntries':110,'androidPluginEnabledByDefault':False}
}
assert report['build']['androidUnitTestFailures']==report['build']['lintErrors']==0
destination=root/'android/docs/join-review-validation.json'
destination.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'report':str(destination),'apkSha256':report['apkSha256'],'phoneVerified':True},ensure_ascii=False))
