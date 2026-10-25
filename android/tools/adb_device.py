"""Select the known OPPO test model without touching another connected USB device."""
import re, subprocess
def device_arguments(adb_path):
    raw = subprocess.check_output([str(adb_path), 'devices', '-l'], text=True, timeout=10)
    devices = [line for line in raw.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == 'device']
    matches = [line for line in devices if re.search(r'\bmodel:PLT140\b', line)]
    if len(matches) == 1: return ['-s', matches[0].split()[0]]
    if len(devices) == 1: return ['-s', devices[0].split()[0]]
    raise RuntimeError('Cannot uniquely select the OPPO test phone')
