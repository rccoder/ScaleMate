"""Build update.json and the bootstrap releases.json for a release tag."""
import argparse
import datetime
import json
from pathlib import Path

from update_manifest import (
    release_note_bullets,
    sha256_bytes,
    sha256_file,
    source_urls,
    validate_manifest,
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', required=True, type=int)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--notes', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--known-issues', nargs='*', default=[])
    parser.add_argument('--published-at')
    args = parser.parse_args()
    channel, version_name = __import__('update_manifest').parse_tag(args.tag)
    if version_name != args.version_name:
        raise SystemExit('Version name differs from tag')
    published_at = args.published_at or datetime.datetime.now(
        datetime.timezone.utc
    ).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
    digest = sha256_file(args.apk)
    size = args.apk.stat().st_size
    issues = args.known_issues or (
        ['预览版可能不稳定；安装前请确认仍可接受后续测试风险。']
        if channel == 'preview' else []
    )
    bullets = release_note_bullets(args.notes)[:5] or [f'称伴 {args.version_name}']
    urls = source_urls(args.tag)
    manifest = {
        'schemaVersion': 1,
        'channel': channel,
        'tag': args.tag,
        'versionName': args.version_name,
        'versionCode': args.version_code,
        'publishedAt': published_at,
        'highlights': bullets,
        'knownIssues': issues,
        'apk': {
            'fileName': 'ScaleMate.apk',
            'sha256': digest,
            'sizeBytes': size,
        },
        'sourceUrls': urls,
    }
    validate_manifest(
        manifest, args.tag, args.version_name, args.version_code, digest, size
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    manifest_path = args.output_dir / 'update.json'
    manifest_path.write_bytes(manifest_bytes)
    release_url = (
        f'https://github.com/rccoder/ScaleMate/releases/tag/{args.tag}'
    )
    manifest_url = (
        f'https://github.com/rccoder/ScaleMate/releases/download/{args.tag}/update.json'
    )
    signature_url = (
        f'https://github.com/rccoder/ScaleMate/releases/download/{args.tag}/update.json.sig'
    )
    # The bootstrap index points at the just-built metadata. Offline signing
    # produces releases.json.sig over these exact bytes.
    index = {
        'schemaVersion': 1,
        'generatedAt': published_at,
        'releases': [{
            'tag': args.tag,
            'channel': channel,
            'versionName': args.version_name,
            'versionCode': args.version_code,
            'publishedAt': published_at,
            'prerelease': channel == 'preview',
            'releaseUrl': release_url,
            'manifestUrl': manifest_url,
            'manifestSha256': sha256_bytes(manifest_bytes),
            'manifestSignatureUrl': signature_url,
            'apkUrl': urls['github-official'],
            'apkSizeBytes': size,
            'apkSha256': digest,
            'highlights': bullets,
            'knownIssues': issues,
        }],
    }
    (args.output_dir / 'releases.json').write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + '\n'
    )
    print(json.dumps({
        'channel': channel,
        'manifest': str(manifest_path),
        'releasesIndex': str(args.output_dir / 'releases.json'),
        'apkSha256': digest,
        'apkSizeBytes': size,
    }, ensure_ascii=False))


if __name__ == '__main__':
    main()
