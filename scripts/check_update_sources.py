"""Download one release APK through configured sources and verify its SHA-256."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


SOURCES = {
    'github-official': 'https://github.com/rccoder/ScaleMate/releases/download/{tag}/{asset}',
    'gh-proxy-com': 'https://gh-proxy.com/https://github.com/rccoder/ScaleMate/releases/download/{tag}/{asset}',
    'ghfast-top': 'https://ghfast.top/https://github.com/rccoder/ScaleMate/releases/download/{tag}/{asset}',
    'ghproxy-net': 'https://ghproxy.net/https://github.com/rccoder/ScaleMate/releases/download/{tag}/{asset}',
    'gh-llkk-cc': 'https://gh.llkk.cc/https://github.com/rccoder/ScaleMate/releases/download/{tag}/{asset}',
    'gh-proxy-org': 'https://gh-proxy.org/https://github.com/rccoder/ScaleMate/releases/download/{tag}/{asset}',
}


def sha256_stream(source):
    digest = hashlib.sha256()
    for chunk in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(chunk)
    return digest.hexdigest()


def expected_sum(text, asset):
    matches = re.findall(r'^([0-9a-fA-F]{64})\s+\*?(\S+)$', text, re.MULTILINE)
    expected = [digest.lower() for digest, name in matches if name == asset]
    if len(expected) != 1:
        raise ValueError(f'SHA256SUMS must contain exactly one entry for {asset}')
    return expected[0]


def validate_sources(sources):
    for source, url in sources.items():
        parsed = urlparse(url)
        if parsed.scheme != 'https' or not parsed.hostname:
            raise ValueError(f'Source {source} must use HTTPS')


def check_source(source, template, tag, asset, expected, timeout):
    if '{tag}' not in template or '{asset}' not in template:
        raise ValueError(f'Source {source} has an invalid URL template')
    url = template.format(tag=tag, asset=asset)
    started = time.monotonic()
    try:
        request = Request(url, headers={'User-Agent': 'ScaleMate-Update-Source-Check/1'})
        with urlopen(request, timeout=min(timeout, 20)) as response, tempfile.TemporaryFile() as output:
            length = response.headers.get('Content-Length')
            while chunk := response.read1(64 * 1024):
                if time.monotonic() - started > timeout:
                    raise TimeoutError(f"Total route deadline exceeded ({timeout}s)")
                output.write(chunk)
            size = output.tell()
            if length and length.isdigit() and int(length) != size:
                raise ValueError(f'Content-Length mismatch: expected {length}, got {size}')
            output.seek(0)
            actual = sha256_stream(output)
        if actual != expected['sha256']:
            raise ValueError(f'SHA-256 mismatch: expected {expected["sha256"]}, got {actual}')
        return {'source': source, 'url': url, 'ok': True, 'sizeBytes': size,
                'elapsedSeconds': round(time.monotonic() - started, 1),
                'contentLength': int(length) if length and length.isdigit() else None}
    except (HTTPError, URLError, TimeoutError, ValueError, OSError) as error:
        return {'source': source, 'url': url, 'ok': False,
                'elapsedSeconds': round(time.monotonic() - started, 1),
                'error': f'{type(error).__name__}: {error}'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--asset', default='ScaleMate.apk')
    parser.add_argument('--sha256sums', required=True, type=Path)
    parser.add_argument('--source', action='append', choices=SOURCES)
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        digest = expected_sum(args.sha256sums.read_text(), args.asset)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    sources = {name: SOURCES[name] for name in (args.source or SOURCES)}
    try:
        validate_sources(sources)
    except ValueError as error:
        parser.error(str(error))
    expected = {'sha256': digest}
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(args.jobs, len(sources)))) as executor:
        results = list(executor.map(
            lambda item: check_source(item[0], item[1], args.tag, args.asset, expected, args.timeout),
            sources.items()))
    sizes = {result['sizeBytes'] for result in results if result['ok']}
    if len(sizes) > 1:
        for result in results:
            if result['ok']:
                result['ok'] = False
                result['error'] = 'successful sources returned different file sizes'
    report = {'tag': args.tag, 'asset': args.asset, 'sha256': digest,
              'sizeBytes': next(iter(sizes)) if len(sizes) == 1 else None, 'sources': results}
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print(text, end='')
    raise SystemExit(0 if all(result['ok'] for result in results) else 1)


if __name__ == '__main__':
    main()
