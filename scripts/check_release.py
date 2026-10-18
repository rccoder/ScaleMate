"""Validate release metadata and optionally a signed APK; never reads signing secrets."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def metadata(root, tag=None):
    source = (root / 'app/build.gradle.kts').read_text()
    version = re.search(r'versionName = "([^"]+)"', source).group(1)
    code = int(re.search(r'versionCode = (\d+)', source).group(1))
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-preview\.\d+)?', version) or code <= 0:
        raise ValueError('Release requires X.Y.Z or X.Y.Z-preview.N versionName and positive versionCode')
    expected_tag = 'v' + version
    if tag is not None and tag != expected_tag:
        raise ValueError(f'Tag must match versionName: {expected_tag}')
    notes = root / 'docs/releases' / f'{expected_tag}.md'
    if not notes.is_file() or not notes.read_text().strip():
        raise ValueError(f'Missing or empty release notes: {notes.relative_to(root)}')
    return version, code


def validate_package(badging, certificate, expected_certificate, version, code):
    package = re.search(r'^package: (.+)$', badging, re.MULTILINE)
    fields = dict(re.findall(r"(\w+)='([^']*)'", package.group(1))) if package else {}
    if fields.get('name') != 'app.scalemate':
        raise ValueError('Release package must be app.scalemate')
    if fields.get('versionName') != version or fields.get('versionCode') != str(code):
        raise ValueError('APK versionName/versionCode differs from source')
    if re.search(r'^application-debuggable\b', badging, re.MULTILINE):
        raise ValueError('Release must not be debuggable')
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', certificate, re.MULTILINE)
    expected = expected_certificate.strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected) or [d.lower() for d in digests] != [expected]:
        raise ValueError('APK must have exactly the fixed release signer')


def latest_published_code(timeout=20, exclude_tag=None):
    url = 'https://api.github.com/repos/rccoder/ScaleMate/releases?per_page=100'
    request = Request(url, headers={
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'ScaleMate-Release-Check/1',
    })
    with urlopen(request, timeout=timeout) as response:
        releases = json.load(response)
    if not isinstance(releases, list):
        raise ValueError('GitHub Releases response is not a list')
    codes = []
    for release in releases:
        tag = str(release.get('tag_name', ''))
        if tag == exclude_tag or release.get('draft'):
            continue
        match = re.fullmatch(r'v\d+\.\d+\.\d+(?:-preview\.\d+)?', tag)
        if not match:
            continue
        body = release.get('body') or ''
        found = re.search(r'versionCode\D{0,20}(\d+)', body, re.IGNORECASE)
        if found:
            codes.append(int(found.group(1)))
    return max(codes) if codes else None


def checked_in_release_codes(root=ROOT, exclude_tag=None):
    """Require readable history for every known release except the pending tag."""
    result = {}
    root = Path(root)
    releases = root / 'docs/releases'
    if not releases.is_dir():
        return result
    tags = set()
    for notes in releases.glob('v*.md'):
        tags.add(notes.stem)
    try:
        shallow = subprocess.check_output(
            ['git', '-C', str(root), 'rev-parse', '--is-shallow-repository'],
            text=True, stderr=subprocess.DEVNULL,
        ).strip()
        if shallow == 'true':
            raise ValueError('Release history is shallow; fetch full history and tags first')
        tags.update(subprocess.check_output(
            ['git', '-C', str(root), 'tag', '--list', 'v*'],
            text=True,
            stderr=subprocess.DEVNULL,
        ).splitlines())
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError('Could not inspect local release tags') from error
    for tag in sorted(tags):
        if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-preview\.\d+)?', tag):
            continue
        if tag == exclude_tag:
            continue
        try:
            source = subprocess.check_output(
                ['git', '-C', str(root), 'show', f'{tag}:app/build.gradle.kts'],
                text=True,
                stderr=subprocess.DEVNULL,
            )
        except (OSError, subprocess.CalledProcessError) as error:
            raise ValueError(f'Missing release history for {tag}; fetch full history and tags first') from error
        name = re.search(r'versionName = "([^"]+)"', source)
        code = re.search(r'versionCode = (\d+)', source)
        if not name or not code or tag != f'v{name.group(1)}':
            raise ValueError(f'Invalid version metadata in historical tag {tag}')
        result[name.group(1)] = int(code.group(1))
    return result


PREVIOUS_CODE_HELP = (
    'Version codes must increase across both stable and preview releases. '
    'Use --check-previous-code after committing the release version and before '
    'creating its tag.'
)


def validate_next_code(code, published_code):
    if published_code is not None and code <= published_code:
        raise ValueError(
            f'versionCode {code} must be greater than all published channels '
            f'(latest recorded {published_code})'
        )


def previous_release_code(root=ROOT, tag=None):
    """Return the largest versionCode from releases older than the current tag."""
    current_tag = tag or ('v' + metadata(root)[0])
    current_version = current_tag.removeprefix('v')
    codes = [
        code
        for version, code in checked_in_release_codes(root, exclude_tag=current_tag).items()
        if version != current_version
    ]
    return max(codes) if codes else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--certificate-output', type=Path)
    parser.add_argument(
        '--check-previous-code',
        action='store_true',
        help=PREVIOUS_CODE_HELP,
    )
    parser.add_argument(
        '--check-remote-code',
        action='store_true',
        help='Check Git history and also query published Release metadata.',
    )
    args = parser.parse_args()
    version, code = metadata(ROOT, args.tag)
    if args.check_previous_code or args.check_remote_code:
        try:
            previous = previous_release_code(ROOT, args.tag)
            if previous is None:
                raise ValueError('No tagged release history was found in Git')
            validate_next_code(code, previous)
            # Also check public metadata for tags that may not exist in a shallow
            # checkout. Release bodies predate this field, so it is best effort.
            if args.check_remote_code:
                remote = latest_published_code(exclude_tag=args.tag or f'v{version}')
                if remote is not None:
                    validate_next_code(code, remote)
        except (HTTPError, URLError, TimeoutError, ValueError, OSError) as error:
            parser.error(f'Could not verify published versionCode: {error}')
    if args.apk:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        build_tools = args.build_tools or (Path(sdk) / 'build-tools/36.0.0' if sdk else None)
        if build_tools is None:
            parser.error('Use --build-tools or set ANDROID_HOME for APK verification')
        certificate = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)], text=True)
        badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
        validate_package(badging, certificate, (ROOT / 'gradle/release-certificate.sha256').read_text(), version, code)
        if args.certificate_output:
            args.certificate_output.write_text(certificate)
    elif args.certificate_output:
        parser.error('--certificate-output requires --apk')
    channel = 'preview' if re.search(r'-preview\.\d+$', version) else 'stable'
    print(f'Release checks passed: v{version} / versionCode {code} / {channel}' + ('; APK signature and package verified' if args.apk else ''))


if __name__ == '__main__':
    main()
