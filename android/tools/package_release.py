"""Package tracked source and the validated APK for GitHub Packages and Releases."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('release/android'))
parser.add_argument('--github-output', type=pathlib.Path)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
gradle = (root / 'android/app/build.gradle.kts').read_text(encoding='utf-8')
version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-z0-9-]+)?', version):
    raise ValueError('Invalid Android version')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
report = json.loads((root / 'android/docs/build-validation.json').read_text(encoding='utf-8'))
if report['appVersion'] != version:
    raise ValueError('APK validation report does not match the current version')
apk_name = f'QQ-Agent-{version}-arm64-debug.apk'
apk = root / 'android/artifacts' / apk_name
if hashlib.sha256(apk.read_bytes()).hexdigest() != report['artifact']['sha256']:
    raise ValueError('APK does not match the validated SHA-256')
shutil.copyfile(apk, output / apk_name)

source_name = f'QQ-Agent-{version}-source.zip'
prefix = f'QQ-Agent-{version}/'
records = []
entries = subprocess.check_output(['git', 'ls-tree', '-rz', '--full-tree', 'HEAD'], cwd=root).split(b'\0')
with zipfile.ZipFile(output / source_name, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for entry in filter(None, entries):
        info, raw_path = entry.split(b'\t', 1)
        mode, kind, blob = info.decode().split()
        path = raw_path.decode('utf-8')
        # Build caches, credentials and personal runtime are excluded even if tracked accidentally.
        parts = pathlib.PurePosixPath(path).parts
        if path.startswith('android/artifacts/'):
            continue
        if parts[0] in ['runtime', 'data', 'node_modules', 'release'] or any(part in ['.local-signing', '__pycache__', '.gradle', 'node_modules'] for part in parts):
            raise ValueError('Private or generated directory is tracked: ' + path)
        if path.endswith(('.enc', '.keystore', '.jks', '.pyc', '.log')) or pathlib.PurePosixPath(path).name in ['.env', '.env.local', 'local.properties']:
            raise ValueError('Private or generated file is tracked: ' + path)
        if kind != 'blob' or mode == '120000':
            raise ValueError('Unsupported source entry: ' + path)
        raw = subprocess.check_output(['git', 'cat-file', 'blob', blob], cwd=root)
        item = zipfile.ZipInfo(prefix + path, date_time=(2026, 1, 1, 0, 0, 0))
        item.compress_type = zipfile.ZIP_DEFLATED
        item.external_attr = int(mode, 8) << 16
        archive.writestr(item, raw)
        records.append({'path': path, 'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
    manifest = {'schema': 1, 'version': version, 'sourceCommit': commit, 'files': records,
                'excluded': ['android/artifacts (APKs and historical artifacts)', 'untracked local files'],
                'buildInstructions': 'android/README.md', 'quickStart': 'QUICKSTART.md'}
    item = zipfile.ZipInfo(prefix + 'SOURCE-MANIFEST.json', date_time=(2026, 1, 1, 0, 0, 0))
    item.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(item, json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8'))

repository = os.environ.get('GITHUB_REPOSITORY', 'ShiYin233/QQ-agent-apk')
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
    raise ValueError('Invalid repository')
owner, name = repository.split('/')
registry = f'https://maven.pkg.github.com/{owner.lower()}/{name}'
namespace = 'http://maven.apache.org/POM/4.0.0'
ET.register_namespace('', namespace)
pom = ET.Element('{' + namespace + '}project')
def element(parent, name, value=None):
    result = ET.SubElement(parent, '{' + namespace + '}' + name)
    if value is not None:
        result.text = value
    return result
for name, value in [('modelVersion', '4.0.0'), ('groupId', 'cn.qqagent.android'), ('artifactId', 'qq-agent-apk'),
                    ('version', version), ('packaging', 'zip'), ('name', 'QQ Agent Android source and APK'),
                    ('description', 'Debug validation build. Main artifact: tracked source ZIP. Attached artifact: ARM64 APK.'),
                    ('url', f'https://github.com/{repository}')]:
    element(pom, name, value)
distribution = element(pom, 'distributionManagement')
repo = element(distribution, 'repository')
element(repo, 'id', 'github')
element(repo, 'url', registry)
scm = element(pom, 'scm')
element(scm, 'url', f'https://github.com/{repository}')
element(scm, 'tag', commit)
ET.indent(pom)
ET.ElementTree(pom).write(output / 'package.pom', encoding='utf-8', xml_declaration=True)

checksums = []
for name in [source_name, apk_name]:
    checksums.append(hashlib.sha256((output / name).read_bytes()).hexdigest() + '  ' + name)
(output / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
notes = f'''QQ Agent Android **{version}** · 调试验证版

- 源码提交：`{commit}`，源码 ZIP 附 `SOURCE-MANIFEST.json`。
- 附件包含源码 ZIP、ARM64 APK 和 SHA-256 摘要；源码不包含个人 Key、登录态、聊天记录、签名密钥或构建缓存。
- 源码 ZIP 不重复包含历史 APK；Android 所需的内置原生组件、原 Agent 核心归档、订阅 SDK、构建脚本和许可证均保留。
- 要求 Android 12+ / ARM64；全新设备仍需导入四个运行组件并完成第一关实机联调。
- API Key 为推荐连接方式；订阅模型真实调用、真实入群批准、跨机型和全天后台仍待验收。
- 现有验证：{report['hostValidation']['androidTests']} 项安卓测试、{report['hostValidation']['nodeTests']} 项 Node 测试、lint 无错误。此次仅打包发布，没有新增实机操作。

[快速开始](https://github.com/{repository}/blob/{commit}/QUICKSTART.md) · [构建说明](https://github.com/{repository}/blob/{commit}/android/README.md)

Packages Maven 坐标：`cn.qqagent.android:qq-agent-apk:{version}`。主文件为源码 ZIP，`android-arm64` 分类附件为 APK；Packages 下载需 GitHub 认证，浏览器直接下载请使用本 Release 的附件。
'''
(output / 'release-notes.md').write_text(notes, encoding='utf-8')
values = {'version': version, 'tag': 'android-v' + version.split('-')[0], 'source': source_name,
          'apk': apk_name, 'repository_url': registry, 'commit': commit, 'source_files': str(len(records))}
(output / 'bundle.json').write_text(json.dumps(values, indent=2) + '\n', encoding='utf-8')
if args.github_output:
    with args.github_output.open('a', encoding='utf-8') as stream:
        stream.write(''.join(key + '=' + value + '\n' for key, value in values.items()))
print(json.dumps(values))
