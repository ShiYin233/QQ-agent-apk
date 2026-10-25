import argparse, hashlib, json, pathlib, subprocess, tempfile, uuid
parser=argparse.ArgumentParser()
parser.add_argument('--friend',required=True)
parser.add_argument('--group',required=True)
parser.add_argument('--authorized',action='store_true',required=True)
args=parser.parse_args()
assert args.friend.isdecimal() and args.group.isdecimal()
adb=r'D:\QQ\QQ Agent apk\runtime\local-setup\adb\adb.exe'
app='cn.qqagent.android'
def command(*parts):
    return subprocess.run([adb,'-d',*parts],capture_output=True,check=True).stdout
name='qqagent-targets-'+uuid.uuid4().hex+'.json'
local=pathlib.Path(tempfile.gettempdir())/name
remote='/data/local/tmp/'+name
content=json.dumps({'friend':args.friend,'group':args.group,'authorized':args.authorized,'runId':str(uuid.uuid4())}).encode()
try:
    local.write_bytes(content)
    command('push',str(local),remote)
    command('shell',f"cat {remote} | run-as {app} sh -c 'cat > files/messaging-test-targets.json'")
    actual=command('exec-out','run-as',app,'sha256sum','files/messaging-test-targets.json').decode().split()[0]
    assert actual==hashlib.sha256(content).hexdigest()
    print('The two explicitly authorized test targets were transferred and verified. Identifiers omitted.')
finally:
    if local.exists():local.unlink()
    command('shell','rm',remote)
