"""Tests for release metadata generation and source allow-lists."""
import json
from pathlib import Path
import tempfile
import unittest

from update_manifest import parse_tag, release_note_bullets, source_urls, validate_manifest


class UpdateManifestTest(unittest.TestCase):
    def test_tag_parser_supports_only_stable_and_preview(self):
        self.assertEqual(('stable', '0.3.0'), parse_tag('v0.3.0'))
        self.assertEqual(('preview', '0.3.0-preview.1'), parse_tag('v0.3.0-preview.1'))
        for tag in ['0.3.0', 'v0.3.0-preview', 'v0.3.0-preview.x', 'v0.3.0-rc.1']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                parse_tag(tag)

    def test_source_urls_are_https_and_known(self):
        urls = source_urls('v0.3.0-preview.1')
        self.assertEqual(6, len(urls))
        self.assertTrue(all(value.startswith('https://') for value in urls.values()))
        self.assertTrue(all(value.endswith('/ScaleMate.apk') for value in urls.values()))

    def test_manifest_validation_rejects_digest_size_and_channel_mismatch(self):
        digest = 'a' * 64
        manifest = {
            'schemaVersion': 1,
            'channel': 'preview',
            'tag': 'v0.3.0-preview.1',
            'versionName': '0.3.0-preview.1',
            'versionCode': 7,
            'publishedAt': '2026-10-18T00:00:00Z',
            'highlights': ['changes'],
            'knownIssues': ['preview may be unstable'],
            'apk': {'fileName': 'ScaleMate.apk', 'sha256': digest, 'sizeBytes': 123},
            'sourceUrls': source_urls('v0.3.0-preview.1'),
        }
        validate_manifest(manifest, manifest['tag'], manifest['versionName'], 7, digest, 123)
        for field, value in [('channel', 'stable'), ('tag', 'v0.3.0'), ('versionCode', 8)]:
            broken = json.loads(json.dumps(manifest))
            broken[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_manifest(broken, manifest['tag'], manifest['versionName'], 7, digest, 123)
        broken = json.loads(json.dumps(manifest))
        broken['apk']['sizeBytes'] = 124
        with self.assertRaises(ValueError):
            validate_manifest(broken, manifest['tag'], manifest['versionName'], 7, digest, 123)

    def test_release_notes_take_nonempty_bullets_only(self):
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / 'notes.md'
            notes.write_text('# Release\n\n- first\n  - nested\n* second\n\n', encoding='utf-8')
            self.assertEqual(['first', 'nested', 'second'], release_note_bullets(notes))


if __name__ == '__main__':
    unittest.main()
