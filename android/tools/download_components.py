"""Download the four locked, unmodified runtime archives; never download account data."""
import argparse
import hashlib
import json
import pathlib
import shutil
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('runtime/components'))
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
manifest = root / 'android/app/src/main/assets/runtime/component-manifest.json'
pins = json.loads(manifest.read_text(encoding='utf-8'))
destination = args.output.resolve()
destination.mkdir(parents=True, exist_ok=True)

def verified(file, pin):
    if not file.is_file() or file.stat().st_size != pin['size']:
        return False
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == pin['sha256']

for pin in pins:
    name = pin['filename']
    if pathlib.PurePath(name).name != name or not pin['url'].startswith('https://'):
        raise ValueError('Invalid component manifest')
    target = destination / name
    if verified(target, pin):
        print('Already verified:', pin['id'], flush=True)
        continue
    partial = destination / (name + '.partial')
    print('Downloading:', pin['id'], pin['version'], flush=True)
    request = urllib.request.Request(pin['url'], headers={'User-Agent': 'QQ-Agent-component-downloader/0.5.2'})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open('wb') as output:
        if not response.geturl().startswith('https://'):
            raise ValueError('Component redirected outside HTTPS')
        total = 0
        while block := response.read(1024 * 1024):
            total += len(block)
            if total > pin['size']:
                raise ValueError('Component exceeds pinned size: ' + name)
            output.write(block)
    if not verified(partial, pin):
        raise ValueError('Component size or SHA-256 mismatch: ' + name)
    partial.replace(target)
    print('Verified:', pin['id'], flush=True)
shutil.copyfile(manifest, destination / manifest.name)
print('Ready for stage_components.py:', destination)
