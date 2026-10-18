"""Validate release metadata and optionally a signed APK; never reads signing secrets."""
import argparse
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def metadata(root, tag=None):
    source = (root / 'app/build.gradle.kts').read_text()
    version = re.search(r'versionName = "([^"]+)"', source).group(1)
    code = int(re.search(r'versionCode = (\d+)', source).group(1))
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or code <= 0:
        raise ValueError('Release requires X.Y.Z versionName and positive versionCode')
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--certificate-output', type=Path)
    args = parser.parse_args()
    version, code = metadata(ROOT, args.tag)
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
    print(f'Release checks passed: v{version} / versionCode {code}' + ('; APK signature and package verified' if args.apk else ''))


if __name__ == '__main__':
    main()
