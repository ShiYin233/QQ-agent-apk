"""Build the public summary from current test results and redacted phone evidence."""
import datetime, hashlib, json, pathlib, re, subprocess, xml.etree.ElementTree as ET
root = pathlib.Path(__file__).resolve().parents[2]
apk = root/'android/artifacts/QQ-Agent-0.4.4-login-mentions-arm64-debug.apk'
phone = json.loads((root/'runtime/local-setup/OPPO-A6m-login-mentions-report.json').read_text(encoding='utf-8'))
assert phone['passed'] and phone['installedVersionVerified']
suites = [ET.parse(p).getroot() for p in (root/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
tests = sum(int(s.attrib['tests']) for s in suites)
assert tests == 25 and all(int(s.attrib['failures']) == int(s.attrib['errors']) == 0 for s in suites)
node = (root/'runtime/local-setup/login-mention-node-tests.log').read_text(encoding='utf-8')
assert re.search(r'tests 25\b',node) and re.search(r'pass 25\b',node) and re.search(r'fail 0\b',node)
lint = ET.parse(root/'android/app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.attrib.get('severity') in ['Error','Fatal'] for i in lint.findall('issue'))
assert not subprocess.check_output(['git','diff','--','src','ui','package.json'],cwd=root)
report = {'schema':4,'timestamp':datetime.datetime.now().isoformat(),'appVersion':'0.4.4-login-mentions',
          'artifact':{'path':apk.relative_to(root).as_posix(),'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
                      'signing':'Existing debug certificate; APK v2 verified','alignment':'zipalign -c -P 16 4 passed; does not establish 16KB runtime compatibility'},
          'hostValidation':{'androidTests':tests,'nodeTests':25,'lintErrors':0,'originalCoreUnchanged':True},
          'diagnosis':{'newGroupAllowed':True,'confirmedAtMeMessages':2,'modelSessionStatuses':['noreply','noreply'],
                       'modelFinishMisidentifiedDisplayedGroupNicknameAsAnotherAccount':True,'noToolSendAttempt':True},
          'repair':{'homeLoginEntry':True,'loginState':'Actual local NapCat CheckLoginStatus',
                    'qrRefresh':'Actual local NapCat RefreshQRcode, automatically sync latest stable PNG content',
                    'mentionIdentity':'Original before-context hook and dynamic prompt; trusted atMe metadata and protocol mid',
                    'nativeNoReplyReasonVisible':True,'allowDraftSurvivesUnrelatedConfigRefresh':True},
          'deviceValidation':phone,
          'remaining':['New-version actual group @ receive/send test','Expired QR refresh end-to-end phone test',
                       'Original full Android-port plan still requires long-duration/background/reboot/backup/community/compatibility/release validation'],
          'historicalEvidence':'Prior current report preserved as build-validation-0.4.3.json; old real-message results do not verify this new version'}
(root/'android/docs/build-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'version':report['appVersion'],'androidTests':tests,'nodeTests':25,'phonePassed':phone['passed'],'sha256':report['artifact']['sha256']},indent=2))
