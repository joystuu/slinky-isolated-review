"""Provision official game runtime in the disposable runner, never on the host."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import urllib.request
import zipfile

if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
    raise SystemExit('Disposable GitHub runner required')

root = Path(os.environ['RUNNER_TEMP']) / 'slinky-game'
root.mkdir()
source = Path(__file__).resolve().parent.parent / 'Slinky-source'

def download(url, dest, sha1):
    if not url.startswith(('https://piston-meta.mojang.com/', 'https://launchermeta.mojang.com/', 'https://libraries.minecraft.net/', 'https://resources.download.minecraft.net/')):
        raise ValueError('Unexpected download origin')
    data = urllib.request.urlopen(url, timeout=60).read()
    if hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError('Official metadata hash mismatch: ' + url)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    return data

metadata = json.loads(download(
    'https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json',
    root / 'version.json', 'd546f1707a3f2b7d034eece5ea2e311eda875787'))
natives = root / 'natives'
natives.mkdir()
for library in metadata['libraries']:
    if 'windows' not in library.get('natives', {}):
        continue
    allowed = 'rules' not in library
    for rule in library.get('rules', []):
        if 'os' not in rule or rule['os'].get('name') == 'windows':
            allowed = rule['action'] == 'allow'
    if not allowed:
        continue
    classifier = library['natives']['windows'].replace('${arch}', '64')
    item = library['downloads']['classifiers'][classifier]
    archive = root / 'native-downloads' / Path(item['path']).name
    download(item['url'], archive, item['sha1'])
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            if member.filename.lower().endswith('.dll'):
                if '/' in member.filename or '\\' in member.filename:
                    raise ValueError('Unexpected native archive path')
                (natives / member.filename).write_bytes(z.read(member))
    print('VERIFIED_NATIVE', library['name'], item['sha1'], flush=True)

index = metadata['assetIndex']
asset_data = json.loads(download(index['url'], root / 'assets' / 'indexes' / (index['id'] + '.json'), index['sha1']))
hashes = set(item['hash'] for item in asset_data['objects'].values())
def asset(h):
    download('https://resources.download.minecraft.net/' + h[:2] + '/' + h, root / 'assets' / 'objects' / h[:2] / h, h)
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
    list(executor.map(asset, sorted(hashes)))
print('VERIFIED_ASSETS', len(hashes), flush=True)
(root / 'game').mkdir()
(root / 'game' / 'options.txt').write_text('fullscreen:false\nmaxFps:30\nrenderDistance:2\n', encoding='utf-8')
classpath = [source / 'deps' / 'minecraft-1.8.9-official.jar'] + sorted((source / 'deps' / 'libraries').glob('*.jar'))
args = ['-Xmx1G', '-Djava.library.path=' + str(natives),
        '-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true',
        '-cp', ';'.join(map(str, classpath)), metadata['mainClass'],
        '--username', 'IsolatedReview', '--version', '1.8.9', '--gameDir', str(root / 'game'),
        '--assetsDir', str(root / 'assets'), '--assetIndex', index['id'],
        '--uuid', '00000000000000000000000000000001', '--accessToken', '0',
        '--userProperties', '{}', '--userType', 'legacy', '--width', '854', '--height', '480']
(root / 'launch-arguments.json').write_text(json.dumps(args), encoding='utf-8')
print('GAME_PREPARED_NO_PERSONAL_ACCOUNT', flush=True)
