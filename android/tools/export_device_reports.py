"""Private local evidence export, including real gate/service/media reports."""
import pathlib, subprocess
root = pathlib.Path(__file__).resolve().parents[2]
adb = root / 'runtime/local-setup/adb/adb.exe'
directory = root / 'runtime/local-setup'
names = ['gate-report.json', 'foreground-service-report.json', 'runtime-report.json', 'model-connectivity-report.json', 'contacts-report.json', 'messaging-report.json', 'media-runtime-report.json']
for name in names:
    result = subprocess.run([str(adb), '-d', 'exec-out', 'run-as', 'cn.qqagent.android', 'cat', f'files/{name}'], capture_output=True, check=True)
    import json
    data = json.loads(result.stdout)
    (directory / f'OPPO-A6m-{name}').write_bytes(result.stdout)
    print(f'Saved {name}; content kept in ignored local evidence directory.')
for name in ['debian-packages-with-media.tsv', 'debian-media-archives.sha256']:
    data = subprocess.run([str(adb), '-d', 'exec-out', 'run-as', 'cn.qqagent.android', 'cat', f'files/runtime/state/{name}'], capture_output=True, check=True).stdout
    (directory / name).write_bytes(data)
    print(f'Saved component audit {name}.')
