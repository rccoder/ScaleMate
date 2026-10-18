"""Tests for update source parsing and URL allow-list behavior."""
import hashlib
import io
import unittest

from check_update_sources import SOURCES, expected_sum, sha256_stream, validate_sources


class UpdateSourcesTest(unittest.TestCase):
    def test_checksum_requires_one_matching_asset(self):
        digest = 'a' * 64
        self.assertEqual(digest, expected_sum(f'{digest}  ScaleMate.apk\n', 'ScaleMate.apk'))
        self.assertEqual(digest, expected_sum(f'{"A" * 64}  ScaleMate.apk\n', 'ScaleMate.apk'))
        for value in ['', f'{digest}  Other.apk\n',
                      f'{digest}  ScaleMate.apk\n{digest}  ScaleMate.apk\n']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                expected_sum(value, 'ScaleMate.apk')

    def test_every_source_uses_https_and_a_host(self):
        validate_sources(SOURCES)
        for url in ['http://gh-proxy.com/{tag}/{asset}', 'https:///{tag}/{asset}', 'not-a-url']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_sources({'invalid': url})

    def test_sha256_stream_hashes_from_the_start(self):
        payload = b'ScaleMate update' * 1024
        self.assertEqual(hashlib.sha256(payload).hexdigest(), sha256_stream(io.BytesIO(payload)))


if __name__ == '__main__':
    unittest.main()
