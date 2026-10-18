"""Merge one release into a signed update index without dropping history."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--previous', type=Path)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    current = json.loads(args.release.read_text(encoding='utf-8'))
    if current.get('schemaVersion') != 1 or len(current.get('releases', [])) != 1:
        raise SystemExit('New release index must contain exactly one release')
    new_entry = current['releases'][0]
    previous = []
    if args.previous and args.previous.exists():
        old = json.loads(args.previous.read_text(encoding='utf-8'))
        if old.get('schemaVersion') != 1:
            raise SystemExit('Previous index schema is unsupported')
        previous = [
            entry for entry in old.get('releases', [])
            if entry.get('tag') != new_entry.get('tag')
        ]
    entries = previous + [new_entry]
    entries.sort(key=lambda entry: entry.get('versionCode', 0), reverse=True)
    codes = [entry.get('versionCode') for entry in entries]
    tags = [entry.get('tag') for entry in entries]
    if len(codes) != len(set(codes)) or len(tags) != len(set(tags)):
        raise SystemExit('Duplicate versionCode or tag in update index')
    current['releases'] = entries[:20]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(current, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
