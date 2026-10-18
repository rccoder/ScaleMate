"""Select update publication semantics from a release tag."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
TAG_RE = re.compile(r'^v(\d+\.\d+\.\d+)(-preview\.(\d+))?$')


def source_version(root=ROOT):
    source = (Path(root) / 'app/build.gradle.kts').read_text()
    version = re.search(r'versionName = "([^"]+)"', source).group(1)
    code = int(re.search(r'versionCode = (\d+)', source).group(1))
    return version, code


def describe(tag):
    match = TAG_RE.fullmatch(tag)
    if not match:
        raise ValueError('Tag must be vX.Y.Z or vX.Y.Z-preview.N')
    version = tag[1:]
    channel = 'preview' if match.group(2) else 'stable'
    return {
        'tag': tag,
        'versionName': version,
        'versionCode': None,
        'channel': channel,
        'prerelease': channel == 'preview',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        value = describe(args.tag)
        version, code = source_version()
        if version != value['versionName']:
            raise ValueError(f'Tag must match source version {version}')
        value['versionCode'] = code
        args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(value, ensure_ascii=False))


if __name__ == '__main__':
    main()
