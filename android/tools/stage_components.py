"""Development only: stream locked archives to the debug app's private downloads.

The Android app, not ADB or the host, verifies and extracts these archives.
Requires USB debugging and a debuggable build; no shared QQ directories are used.
"""
import argparse
import hashlib
import json
import pathlib
import re
import shlex
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--components', type=pathlib.Path, required=True)
parser.add_argument('--package', default='cn.qqagent.android')
parser.add_argument('--serial', help='ADB device serial; required when multiple USB devices are connected')
parser.add_argument('--ids', nargs='+', default=['debian', 'node'])
args = parser.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9_.]+', args.package):
    raise ValueError('Invalid application id')
pins = json.loads((args.components / 'component-manifest.json').read_text(encoding='utf-8'))
adb = [args.adb, '-s', args.serial] if args.serial else [args.adb, '-d']
subprocess.run(adb + ['shell', 'run-as', args.package, 'mkdir', '-p', 'files/component-downloads'], check=True)
scratch = '/data/local/tmp/qqagent-stage-' + uuid.uuid4().hex
subprocess.run(adb + ['shell', 'mkdir', '-p', scratch], check=True)
for pin in pins:
    if pin['id'] not in args.ids:
        continue
    name = pin['filename']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', name):
        raise ValueError('Invalid pinned filename')
    source = args.components / name
    with source.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if source.stat().st_size != pin['size'] or digest != pin['sha256']:
        raise ValueError('Host archive verification failed: ' + name)
    command = shlex.quote('cat > files/component-downloads/' + name)
    print('Staging', name, source.stat().st_size, flush=True)
    temporary = scratch + '/' + name
    subprocess.run(adb + ['push', str(source), temporary], check=True, capture_output=True)
    # The shell reads its own temporary file and streams to the app UID;
    # no SELinux permission for apps reading /data/local/tmp is required.
    remote_command = 'cat ' + shlex.quote(temporary) + ' | run-as ' + args.package + ' sh -c ' + command
    subprocess.run(adb + ['shell', '-T', remote_command], check=True)
    remote = subprocess.run(adb + ['shell', '-T', 'run-as', args.package, 'sha256sum', 'files/component-downloads/' + name],
                            check=True, capture_output=True, text=True)
    if remote.stdout.split()[0] != digest:
        raise ValueError('Device staging hash mismatch: ' + name)
    subprocess.run(adb + ['shell', 'rm', temporary], check=True)
    print('Staged', pin['id'], flush=True)
subprocess.run(adb + ['shell', 'rmdir', scratch], check=True)
