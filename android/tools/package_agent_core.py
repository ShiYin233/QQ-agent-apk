"""Bundle tracked original core and exact pure-JS production dependencies only."""
import hashlib, json, pathlib, subprocess, zipfile

repo=pathlib.Path(__file__).resolve().parents[2]
output=repo/'android/app/src/main/assets/runtime/agent-core.zip'
tracked=subprocess.check_output(['git','ls-files','-z','src','ui','assets','package.json','package-lock.json','LICENSE'], cwd=repo)
files=[pathlib.PurePosixPath(x.decode('utf-8')) for x in tracked.split(b'\0') if x]
for module in ['js-yaml','argparse','undici','ws']:
    base=repo/'node_modules'/module
    files.extend(pathlib.PurePosixPath(p.relative_to(repo).as_posix()) for p in base.rglob('*') if p.is_file())
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
records=[]
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for relative in sorted(set(files)):
        source=repo.joinpath(*relative.parts)
        if source.is_symlink():raise ValueError('Unexpected core symlink')
        raw=source.read_bytes()
        info=zipfile.ZipInfo(relative.as_posix(),date_time=(2026,1,1,0,0,0))
        info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=(0o100644 << 16)
        archive.writestr(info,raw)
        records.append({'path':relative.as_posix(),'size':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
    archive.writestr('qqagent-source-manifest.json',json.dumps({'sourceCommit':commit,'files':records},ensure_ascii=False,indent=2))
pin={'id':'agent-core','version':'0.4.0','sourceCommit':commit,'filename':output.name,'size':output.stat().st_size,
     'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'installedBytes':sum(x['size'] for x in records),
     'productionDependencies':{'js-yaml':'5.4.1','argparse':'2.0.1','undici':'6.28.0','ws':'8.21.3'}}
output.with_name('agent-core.json').write_text(json.dumps(pin,indent=2),encoding='utf-8')
print(json.dumps(pin,indent=2))
