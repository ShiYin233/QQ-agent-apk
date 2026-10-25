"""Provision a test key without creating a plaintext PC file or using a key argument."""
import getpass
import hashlib
import json
import pathlib
import subprocess
import tempfile
import uuid
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ADB = r'D:\QQ\QQ Agent apk\runtime\local-setup\adb\adb.exe'
APP = 'cn.qqagent.android'
BASE = 'https://discovery-api.intern-ai.org.cn/v1'

def adb(*args):
    result = subprocess.run([ADB, '-d', *args], capture_output=True, check=True)
    return result.stdout

public = serialization.load_der_public_key(adb('exec-out', 'run-as', APP, 'cat', 'files/model-provision-public.der'))
key = getpass.getpass('Test API key (input hidden): ')
plain = json.dumps({'baseUrl': BASE, 'apiKey': key}, separators=(',', ':')).encode()
encrypted = public.encrypt(plain, padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
del key, plain
name = 'qqagent-model-' + uuid.uuid4().hex + '.bin'
remote = '/data/local/tmp/' + name
local = pathlib.Path(tempfile.gettempdir()) / name
try:
    local.write_bytes(encrypted)
    adb('push', str(local), remote)
    # The device shell opens the transport file; run-as writes the application-private destination.
    adb('shell', f"cat {remote} | run-as {APP} sh -c 'cat > files/model-provision.bin'")
    actual = adb('exec-out', 'run-as', APP, 'sha256sum', 'files/model-provision.bin').decode().split()[0]
    if actual != hashlib.sha256(encrypted).hexdigest():
        raise RuntimeError('Encrypted credential transfer verification failed')
    print('Encrypted test credential transferred and hash verified. No plaintext PC file created.')
finally:
    if local.exists():
        local.unlink()
    adb('shell', 'rm', remote)
