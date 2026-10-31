"""Decode bounded data-only evidence from a downloaded GitHub job log."""
import argparse
import base64
import gzip
import hashlib
import io
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument('log', type=Path)
parser.add_argument('destination', type=Path)
args = parser.parse_args()
text = args.log.read_text(encoding='utf-8-sig')
args.destination.mkdir(parents=True, exist_ok=True)
stage = 'launcher-no-target'
chunks = []
for line in text.splitlines():
    match = re.search(r'TRACE_STAGE ([a-z-]+)$', line)
    if match:
        stage = match.group(1)
    match = re.search(r'TRACE_CHUNK ([A-Za-z0-9+/=]+)$', line)
    if match:
        chunks.append(match.group(1))
    if line.endswith('TRACE_GZIP_BASE64_END'):
        packed = base64.b64decode(''.join(chunks), validate=True)
        with gzip.GzipFile(fileobj=io.BytesIO(packed)) as stream:
            data = stream.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024:
            raise ValueError('Trace exceeds decode limit')
        events = json.loads(data)
        path = args.destination / (stage + '.json')
        path.write_bytes(data)
        print(json.dumps({'file': str(path), 'events': len(events), 'sha256': hashlib.sha256(data).hexdigest()}))
        chunks = []
screen = re.findall(r'SCREEN_CHUNK ([A-Za-z0-9+/=]+)', text)
if screen:
    data = base64.b64decode(''.join(screen), validate=True)
    if len(data) > 8 * 1024 * 1024 or not data.startswith(b'\xff\xd8\xff'):
        raise ValueError('Unexpected screenshot content')
    (args.destination / 'after-injection.jpg').write_bytes(data)
artifacts = []
for line in text.splitlines():
    match = re.search(r'(\{"File":.*\})$', line)
    if match:
        artifacts.append(json.loads(match.group(1)))
(args.destination / 'build-hashes.json').write_text(json.dumps(artifacts, indent=2), encoding='utf-8')
