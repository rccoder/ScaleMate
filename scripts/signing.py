"""Use local signing files without printing credentials or storing them in the repo."""
import argparse
import base64
import json
import os
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('operation', choices=['build', 'upload-secrets'])
parser.add_argument('--directory', type=Path, default=Path.home() / '.local/share/scalemate-signing')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
keystore = args.directory / 'scalemate-release.jks'
credentials = args.directory / 'credentials.json'
info = json.loads(credentials.read_text())
assert keystore.is_file(), 'Missing release keystore'
if args.operation == 'build':
    env = os.environ.copy()
    env.update(ANDROID_KEYSTORE_PATH=str(keystore.resolve()), ANDROID_KEYSTORE_PASSWORD=info['storePassword'],
               ANDROID_KEY_ALIAS=info['alias'], ANDROID_KEY_PASSWORD=info['keyPassword'])
    subprocess.run(['./gradlew', ':app:assembleRelease', '--no-daemon', '--console=plain'], cwd=root, env=env, check=True)
else:
    subprocess.run(['gh', 'api', 'user', '--jq', '.login'], check=True, stdout=subprocess.DEVNULL)
    values = {
        'ANDROID_KEYSTORE_BASE64': base64.b64encode(keystore.read_bytes()).decode(),
        'ANDROID_KEYSTORE_PASSWORD': info['storePassword'],
        'ANDROID_KEY_ALIAS': info['alias'],
        'ANDROID_KEY_PASSWORD': info['keyPassword'],
    }
    for name, value in values.items():
        subprocess.run(['gh', 'secret', 'set', name, '--repo', 'rccoder/ScaleMate'], input=value.encode(), check=True)
        print('Configured ' + name)
