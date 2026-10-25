"""Public build evidence, with a separate boundary for real account authorization."""
import datetime,hashlib,json,pathlib,re,subprocess,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[2]
apk=root/'android/artifacts/QQ-Agent-0.5.1-subscription-setup-arm64-debug.apk'
phone=json.loads((root/'runtime/local-setup/OPPO-A6m-subscriptions-report.json').read_text(encoding='utf-8'))
fixtures=json.loads((root/'runtime/local-setup/OPPO-A6m-subscription-sdk-fixtures.json').read_text(encoding='utf-8'))
oauth=json.loads((root/'runtime/local-setup/OPPO-A6m-subscription-oauth-report.json').read_text(encoding='utf-8'))
network=json.loads((root/'runtime/local-setup/OPPO-A6m-subscription-network.json').read_text(encoding='utf-8'))
google=json.loads((root/'runtime/local-setup/OPPO-A6m-google-authorization-report.json').read_text(encoding='utf-8'))
assert phone['passed'] and fixtures['passed'] and oauth['bothNativeOAuthListenersAndCancellationVerified']
suites=[ET.parse(p).getroot() for p in (root/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
tests=sum(int(s.attrib['tests']) for s in suites);assert tests==25 and all(int(s.attrib['failures'])==int(s.attrib['errors'])==0 for s in suites)
node=(root/'runtime/local-setup/subscription-node-tests.log').read_text(encoding='utf-8')
assert re.search(r'tests 52\b',node) and re.search(r'pass 52\b',node) and re.search(r'fail 0\b',node)
lint=ET.parse(root/'android/app/build/reports/lint-results-debug.xml').getroot();assert not any(i.attrib.get('severity') in ['Error','Fatal'] for i in lint.findall('issue'))
assert not subprocess.check_output(['git','diff','--','src','ui','package.json'],cwd=root)
target=root/'android/docs/build-validation.json';previous=json.loads(target.read_text(encoding='utf-8'))
historical=root/'android/docs/build-validation-0.4.4.json'
if previous['appVersion']=='0.4.4-login-mentions' and not historical.exists():historical.write_text(json.dumps(previous,ensure_ascii=False,indent=2),encoding='utf-8')
historical=root/'android/docs/build-validation-0.5.0.json'
if previous['appVersion']=='0.5.0-subscription-accounts' and not historical.exists():historical.write_text(json.dumps(previous,ensure_ascii=False,indent=2),encoding='utf-8')
report={'schema':6,'timestamp':datetime.datetime.now().isoformat(),'appVersion':'0.5.1-subscription-setup',
  'artifact':{'path':apk.relative_to(root).as_posix(),'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signing':'Existing debug certificate; APK v2 verified','alignment':'zipalign -c -P 16 4 passed; does not establish 16KB runtime compatibility'},
  'hostValidation':{'androidTests':tests,'nodeTests':52,'lintErrors':0,'originalCoreUnchanged':True},
  'subscriptionImplementation':{'chatgpt':'Official public registration, identity-checked OAuth and account-specific catalog; Pi 1.0.4 Responses transport','google':'Pinned Pi 0.60.0 with reproducible grant-save hook and independent project setup/retry; no Antigravity login','googleThirdPartyOAuthCurrentlyNotSupportedByProvider':True,'googleAuthorizationSavedBeforeSetup':True,'encryptedCredentials':'Android Keystore AES-256-GCM; app-private authenticated loopback bridge','originalApiPreserved':True,'switchAndDisconnectPauseReplies':True,'loginFailuresHaveSafeStageDiagnostics':True},
  'deviceValidation':phone,'phoneOfflineSdkFixtures':fixtures,'nativeOAuthListenerValidation':oauth,'phoneUnauthenticatedConnectivity':network,
  'realAccountValidation':{'chatgptAuthorized':False,'googleAuthorized':google['googleAuthorizationSaved'],'googleCredentialRestoredAfterCoreReload':google['credentialRestoredAfterCoreReload'],'googleModelReady':False,'googleSetupCode':google['setupCode'],'specificGoogleIneligibilityReasonConfirmed':False,'subscriptionInferenceVerified':False,'qqMessagesSent':0},
  'googleAuthorizationDeviceValidation':google,
  'providerDocumentation':{'antigravityThirdPartyLoginPolicy':'https://www.antigravity.google/docs/faq/','geminiCliThirdPartyOAuthPolicy':'https://geminicli.com/docs/resources/faq/','supportedAntigravitySdkUsesApiKeyOrEnterprise':'https://www.antigravity.google/docs/sdk/overview/'},
  'remaining':['Google project/model setup returned ineligible; specific cause not confirmed, real Gemini subscription inference remains unverified','Real ChatGPT Plus authorization and model pong','Prior Android-port long-duration, compatibility, backup, community and release checks remain outstanding'],
  'historicalEvidence':'0.4.4 and 0.5.0 reports preserved separately; prior API/QQ send results do not establish subscription inference'}
target.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'version':report['appVersion'],'androidTests':tests,'nodeTests':52,'phonePassed':phone['passed'],'googleAuthorizationSaved':True,'realAccountInferenceVerified':False,'sha256':report['artifact']['sha256']},indent=2))
