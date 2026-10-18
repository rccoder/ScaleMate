"""Validate the signed release index against the current release and sources."""
import argparse
import json
from pathlib import Path
import re

from update_manifest import SOURCE_HOSTS, parse_tag, sha256_bytes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--index', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--version-code', required=True, type=int)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--apk-size', required=True, type=int)
    args = parser.parse_args()
    index = json.loads(args.index.read_text(encoding='utf-8'))
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    if index.get('schemaVersion') != 1:
        raise SystemExit('Unsupported index schema')
    entries = index.get('releases') or []
    matches = [entry for entry in entries if entry.get('tag') == args.tag]
    if len(matches) != 1:
        raise SystemExit('Current tag must appear exactly once')
    entry = matches[0]
    channel, version = parse_tag(args.tag)
    if entry.get('versionName') != version or entry.get('channel') != channel:
        raise SystemExit('Index tag/version/channel mismatch')
    if entry.get('versionCode') != args.version_code:
        raise SystemExit('Index versionCode mismatch')
    if entry.get('apkSha256') != args.apk_sha256 or entry.get('apkSizeBytes') != args.apk_size:
        raise SystemExit('Index APK digest/size mismatch')
    if entry.get('manifestSha256') != sha256_bytes(args.manifest.read_bytes()):
        raise SystemExit('Index manifest digest mismatch')
    for other in entries:
        if other.get('versionCode') == args.version_code and other is not entry:
            raise SystemExit('Duplicate versionCode in index')
    urls = manifest.get('sourceUrls') or {}
    if set(urls) != set(SOURCE_HOSTS):
        raise SystemExit('Manifest source ID set changed')
    for source, value in urls.items():
        expected = SOURCE_HOSTS[source]
        host = value.split('/', 3)[2]
        if not value.startswith('https://') or host != expected:
            raise SystemExit(f'Invalid source URL: {source}')
        if not value.endswith('/ScaleMate.apk'):
            raise SystemExit(f'Unexpected asset for {source}')
    if entry.get('apkSha256') != manifest.get('apk', {}).get('sha256'):
        raise SystemExit('Index and manifest APK digest differ')
    print(f'Update index checks passed: {args.tag} / {len(entries)} release(s)')


if __name__ == '__main__':
    main()
