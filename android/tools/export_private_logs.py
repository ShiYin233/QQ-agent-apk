"""Export complete raw QQ logs only when their local retention is explicitly authorized."""
import argparse, datetime, pathlib, re, subprocess
parser = argparse.ArgumentParser()
parser.add_argument('--authorized-private-logs', action='store_true')
args = parser.parse_args()
if not args.authorized_private_logs: parser.error('Explicit authorization to retain complete private QQ logs is required')
root = pathlib.Path(__file__).resolve().parents[2]
adb = root / 'runtime/local-setup/adb/adb.exe'
directory = root / 'runtime/local-setup/private-qq-logs'
directory.mkdir(parents=True, exist_ok=True)
def read(*arguments):
    return subprocess.run([str(adb), '-d', 'exec-out', 'run-as', 'cn.qqagent.android', *arguments], capture_output=True, check=True).stdout
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
(directory / f'qq-session-{stamp}.log').write_bytes(read('cat', 'files/runtime/qq-session.log'))
names = read('ls', '-1', 'files/runtime/session-logs').decode().splitlines()
count = 0
for name in names:
    if re.fullmatch(r'qq-session-\d+\.log', name):
        (directory / name).write_bytes(read('cat', f'files/runtime/session-logs/{name}')); count += 1
print(f'Complete private QQ log saved; {count} archived sessions synchronized. No chat content or tokens printed.')
