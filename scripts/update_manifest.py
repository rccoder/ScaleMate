"""Validate and describe update metadata; secrets are read only from explicit paths."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TAG_RE = re.compile(r'^v(\d+\.\d+\.\d+)(-preview\.(\d+))?$')
SOURCE_HOSTS = {
    'github-official': 'github.com',
    'gh-proxy-com': 'gh-proxy.com',
    'ghfast-top': 'ghfast.top',
    'ghproxy-net': 'ghproxy.net',
    'gh-llkk-cc': 'gh.llkk.cc',
    'gh-proxy-org': 'gh-proxy.org',
}


def parse_tag(tag):
    match = TAG_RE.fullmatch(tag)
    if not match:
        raise ValueError('Tag must be vX.Y.Z or vX.Y.Z-preview.N')
    channel = 'preview' if match.group(2) else 'stable'
    return channel, tag[1:]


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def release_note_bullets(path):
    text = Path(path).read_text(encoding='utf-8')
    bullets = []
    for line in text.splitlines():
        value = line.strip()
        if value.startswith(('- ', '* ')):
            bullets.append(value[2:].strip())
    return bullets


def source_urls(tag):
    repo = f'https://github.com/rccoder/ScaleMate/releases/download/{tag}/ScaleMate.apk'
    return {
        'github-official': repo,
        'gh-proxy-com': f'https://gh-proxy.com/{repo}',
        'ghfast-top': f'https://ghfast.top/{repo}',
        'ghproxy-net': f'https://ghproxy.net/{repo}',
        'gh-llkk-cc': f'https://gh.llkk.cc/{repo}',
        'gh-proxy-org': f'https://gh-proxy.org/{repo}',
    }


def validate_urls(urls):
    if not urls:
        raise ValueError('sourceUrls must not be empty')
    if set(urls) - set(SOURCE_HOSTS):
        raise ValueError('Unknown source id')
    for source, value in urls.items():
        if not value.startswith('https://'):
            raise ValueError(f'{source} must use HTTPS')
        host = value.split('/', 3)[2].lower()
        if source == 'github-official':
            if host != 'github.com':
                raise ValueError('github-official must use github.com')
        elif host != SOURCE_HOSTS[source]:
            raise ValueError(f'{source} host differs from allow-list')
        if not value.endswith('/ScaleMate.apk'):
            raise ValueError(f'{source} must point to ScaleMate.apk')


def validate_manifest(manifest, tag, version_name, version_code, apk_sha, apk_size):
    channel, parsed_version = parse_tag(tag)
    if manifest.get('schemaVersion') != 1:
        raise ValueError('Unsupported manifest schemaVersion')
    if manifest.get('channel') != channel or manifest.get('tag') != tag:
        raise ValueError('Manifest channel or tag differs')
    if manifest.get('versionName') != version_name or parsed_version != version_name:
        raise ValueError('Manifest versionName differs from source/tag')
    if manifest.get('versionCode') != version_code:
        raise ValueError('Manifest versionCode differs from source')
    apk = manifest.get('apk') or {}
    if apk.get('fileName') != 'ScaleMate.apk':
        raise ValueError('Unexpected APK file name')
    if apk.get('sha256') != apk_sha or apk.get('sizeBytes') != apk_size:
        raise ValueError('Manifest APK digest/size differs from file')
    if channel == 'preview' and not manifest.get('knownIssues'):
        raise ValueError('Preview manifest requires knownIssues')
    validate_urls(manifest.get('sourceUrls') or {})
    return channel


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)

    generate = sub.add_parser('generate')
    generate.add_argument('--tag', required=True)
    generate.add_argument('--version-name', required=True)
    generate.add_argument('--version-code', required=True, type=int)
    generate.add_argument('--apk', required=True, type=Path)
    generate.add_argument('--notes', required=True, type=Path)
    generate.add_argument('--output-dir', required=True, type=Path)
    generate.add_argument('--known-issues', nargs='*', default=[])
    generate.add_argument('--published-at')
    generate.add_argument('--source-urls', type=Path)

    verify = sub.add_parser('verify')
    verify.add_argument('--tag', required=True)
    verify.add_argument('--manifest', required=True, type=Path)
    verify.add_argument('--apk', required=True, type=Path)

    args = parser.parse_args()
    if args.command == 'generate':
        channel, version_name = parse_tag(args.tag)
        if args.version_name != version_name:
            raise SystemExit('--version-name must match the tag')
        apk_sha = sha256_file(args.apk)
        apk_size = args.apk.stat().st_size
        if channel == 'preview' and not args.known_issues:
            args.known_issues = ['预览版可能不稳定；安装前请确认仍可接受后续测试风险。']
        published_at = args.published_at or __import__('datetime').datetime.now(
            __import__('datetime').timezone.utc
        ).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
        bullets = release_note_bullets(args.notes)
        source_map = source_urls(args.tag)
        if args.source_urls:
            source_map = json.loads(args.source_urls.read_text(encoding='utf-8'))
        manifest = {
            'schemaVersion': 1,
            'channel': channel,
            'tag': args.tag,
            'versionName': args.version_name,
            'versionCode': args.version_code,
            'publishedAt': published_at,
            'highlights': bullets[:5] or [f'称伴 {args.version_name}'],
            'knownIssues': args.known_issues[:10],
            'apk': {
                'fileName': 'ScaleMate.apk',
                'sha256': apk_sha,
                'sizeBytes': apk_size,
            },
            'sourceUrls': source_map,
        }
        args.output_dir.mkdir(parents=True, exist_ok=True)
        manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
        manifest_path = args.output_dir / 'update.json'
        manifest_path.write_bytes(manifest_bytes)
        validate_manifest(manifest, args.tag, args.version_name, args.version_code, apk_sha, apk_size)
        print(json.dumps({
            'manifest': str(manifest_path),
            'channel': channel,
            'sha256': apk_sha,
            'sizeBytes': apk_size,
        }, ensure_ascii=False))
    else:
        try:
            manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
            source = (ROOT / 'app/build.gradle.kts').read_text()
            version_name = re.search(r'versionName = "([^"]+)"', source).group(1)
            version_code = int(re.search(r'versionCode = (\d+)', source).group(1))
            validate_manifest(
                manifest,
                args.tag,
                version_name,
                version_code,
                sha256_file(args.apk),
                args.apk.stat().st_size,
            )
        except (OSError, ValueError, json.JSONDecodeError) as error:
            print(f'Update manifest check failed: {error}', file=sys.stderr)
            raise SystemExit(1)
        print(f'Update manifest checks passed: {args.tag}')


if __name__ == '__main__':
    main()
