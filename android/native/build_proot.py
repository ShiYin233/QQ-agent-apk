"""Reproducible Windows-hosted Android ARM64 PRoot build from verified sources.

The executable statically links LGPL3+ talloc and BSD android-shmem and is
distributed under GPL3+ with the exact source archives and this build adapter.
Uses no Termux prefix, no Termux runtime, and does not disable TLS validation.
"""
import concurrent.futures
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import argparse

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work-dir', type=pathlib.Path, required=True, help='Directory containing the three extracted, verified source trees')
parser.add_argument('--ndk', type=pathlib.Path, required=True, help='Android NDK r23.1.7779620 (Windows host)')
args=parser.parse_args()
BASE=args.work_dir.resolve()
SRC=BASE/'proot-5.1.107.96'/'src'
TALLOC=BASE/'talloc-2.5.0'
SHMEM=BASE/'libandroid-shmem-0.7'
NDK=args.ndk.resolve()
require_version=(NDK/'source.properties').read_text()
if '23.1.7779620' not in require_version:
    raise ValueError('This build adapter is validated with NDK r23.1.7779620 only')
BIN=NDK/'toolchains/llvm/prebuilt/windows-x86_64/bin'
OUT=BASE/'out-arm64'
OUT.mkdir(exist_ok=True)
INC=OUT/'include'
INC.mkdir(exist_ok=True)
(INC/'sys').mkdir(exist_ok=True)
shutil.copyfile(TALLOC/'talloc.h',INC/'talloc.h')
shutil.copyfile(SHMEM/'shm.h',INC/'sys/shm.h')
(INC/'replace.h').write_text('''/* Bionic adapter: libc supplies the upstream portability APIs. */
#pragma once
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdarg.h>
#include <errno.h>
#include <limits.h>
#include <unistd.h>
#include <time.h>
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define MIN(a,b) ((a)<(b)?(a):(b))
/* Explicit wipe equivalent for Bionic API31, which predates the C23 API. */
static inline void *qqagent_memset_explicit(void *dest, int value, size_t len) {
    volatile unsigned char *p = (volatile unsigned char *)dest;
    while (len--) *p++ = (unsigned char)value;
    return dest;
}
#define memset_explicit qqagent_memset_explicit
''',encoding='utf-8')
(INC/'build.h').write_text('''#pragma once
#define VERSION "5.1.107.96-qqagent"
#define HAVE_PROCESS_VM 1
#define HAVE_SECCOMP_FILTER 1
''',encoding='utf-8')
FLAGS=['--target=aarch64-linux-android31','-O2','-fPIE','-D_GNU_SOURCE','-D_FILE_OFFSET_BITS=64','-DARG_MAX=131072','-DWITH_LIBANDROID_SHMEM','-DPROOT_UNBUNDLE_LOADER="/qqagent-runtime-loader"', '-I'+str(INC),'-I'+str(SRC)]
# Android shmem upstream expects a build-provided _PATH_TMP. Use the app's
# explicitly supplied TMPDIR instead of baking in any application package path.
shmem_text=(SHMEM/'shmem.c').read_text()
shmem_text=shmem_text.replace('#define ASHV_KEY_SYMLINK_PATH _PATH_TMP "ashv_key_%d"','#define ASHV_KEY_SYMLINK_PATH "%s/ashv_key_%d"')
shmem_text=shmem_text.replace('sprintf(symlink_path, ASHV_KEY_SYMLINK_PATH, key);','snprintf(symlink_path, sizeof(symlink_path), ASHV_KEY_SYMLINK_PATH, getenv("TMPDIR") ?: "/data/local/tmp", key);')
(OUT/'shmem.c').write_text(shmem_text)
shutil.copyfile(SHMEM/'shm.h',OUT/'shm.h')
def run(args):
    p=subprocess.run([str(BIN/'clang.exe'),*args],capture_output=True,text=True,encoding='utf-8',errors='replace')
    if p.returncode:
        raise RuntimeError(p.stdout+p.stderr)
    return p
def compile(source, extra=()):
    obj=OUT/(re.sub(r'[^a-zA-Z0-9]','_',str(source.relative_to(BASE)))+'.o')
    run([*FLAGS,*extra,'-c',str(source),'-o',str(obj)])
    return obj

# Build both freestanding guest loaders, following upstream GNUmakefile flags.
loader_objs=[]
for bits, target, address in [('64','aarch64-linux-android31','0x2000000000'),('32','armv7a-linux-androideabi31','0x20000000')]:
    objects=[]
    for name in ['loader.c','assembly.S']:
        obj=OUT/('loader'+bits+'-'+name.replace('.','_')+'.o')
        run(['--target='+target,'-O2','-fPIC','-ffreestanding','-fno-stack-protector','-D_GNU_SOURCE','-I'+str(SRC),'-c',str(SRC/'loader'/name),'-o',str(obj)])
        objects.append(obj)
    loader=OUT/('loader'+bits)
    run(['--target='+target,'-static','-nostdlib','-Wl,--build-id=none,-Ttext='+address+',-z,noexecstack,-z,max-page-size=16384','-o',str(loader),*[str(o) for o in objects]])
    subprocess.run([str(BIN/'llvm-strip.exe'),str(loader)],check=True)
    if bits=='64':
        # Use the unstripped linked image for absolute symbol positions below.
        raw=OUT/'loader64-symbols'
        run(['--target='+target,'-static','-nostdlib','-Wl,--build-id=none,-Ttext='+address+',-z,noexecstack,-z,max-page-size=16384','-o',str(raw),*[str(o) for o in objects]])
        symbols=subprocess.check_output([str(BIN/'llvm-nm.exe'),str(raw)],text=True)
        values={m.group(2):int(m.group(1),16) for m in re.finditer(r'^([0-9a-fA-F]+)\s+\w\s+(\S+)$',symbols,re.M)}
        (OUT/'loader-info.c').write_text('#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround='+str(values['pokedata_workaround']-values['_start'])+';\n')
    print('Built loader',bits,flush=True)

mf=(SRC/'GNUmakefile').read_text()
section=mf.split('OBJECTS += \\',1)[1].split('define define_from_arch.h',1)[0]
source_files=[SRC/(p[:-2]+'.c') for p in re.findall(r'([\w/-]+\.o)',section)]
source_files += [TALLOC/'talloc.c',OUT/'shmem.c',OUT/'loader-info.c']
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    objects=list(pool.map(compile,source_files))
exe=OUT/'proot'
run([*FLAGS,'-pie','-Wl,-z,noexecstack,-z,max-page-size=16384','-o',str(exe),*[str(o) for o in objects],'-llog','-landroid'])
subprocess.run([str(BIN/'llvm-strip.exe'),str(exe)],check=True)
records=[]
for name in ['proot','loader64','loader32']:
    p=OUT/name
    records.append({'file':name,'size':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(OUT/'native-artifacts.json').write_text(json.dumps(records,indent=2))
print(json.dumps(records,indent=2),flush=True)
