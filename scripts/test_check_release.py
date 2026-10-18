"""Regression tests for release gates, using synthetic non-secret tool output."""
from pathlib import Path
import tempfile
import unittest

from check_release import metadata, validate_package


class ReleaseChecksTest(unittest.TestCase):
    def setUp(self):
        self.badging = "package: name='app.scalemate' versionCode='4' versionName='0.2.0'\nsdkVersion:'34'\n"
        self.digest = 'a' * 64
        self.certificate = 'Signer #1 certificate SHA-256 digest: ' + self.digest + '\n'

    def check(self, badging=None, certificate=None):
        validate_package(self.badging if badging is None else badging,
                         self.certificate if certificate is None else certificate,
                         self.digest, '0.2.0', 4)

    def test_expected_release(self):
        self.check()

    def test_wrong_package_versions_and_debuggable_rejected(self):
        for value in [self.badging.replace('app.scalemate', 'app.scalemate.fooddev'),
                      self.badging.replace("'4'", "'3'"),
                      self.badging.replace('0.2.0', '0.1.1'),
                      self.badging + 'application-debuggable\n', '',
                      "package: name='app.scalemate'\n"]:
            with self.subTest(badging=value), self.assertRaises(ValueError):
                self.check(badging=value)

    def test_wrong_missing_or_extra_signer_rejected(self):
        for value in ['', self.certificate.replace('a' * 64, 'b' * 64),
                      self.certificate + self.certificate.replace('#1', '#2')]:
            with self.subTest(certificate=value), self.assertRaises(ValueError):
                self.check(certificate=value)

    def test_notes_and_tag_required_to_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app').mkdir()
            (root / 'app/build.gradle.kts').write_text('versionName = "0.2.0"\nversionCode = 4')
            with self.assertRaises(ValueError):
                metadata(root, 'v0.2.0')
            notes = root / 'docs/releases/v0.2.0.md'
            notes.parent.mkdir(parents=True)
            notes.write_text(' \n')
            with self.assertRaises(ValueError):
                metadata(root, 'v0.2.0')
            notes.write_text('Release notes')
            self.assertEqual(('0.2.0', 4), metadata(root, 'v0.2.0'))
            for tag in ['v0.1.1', '0.2.0', '../v0.2.0']:
                with self.subTest(tag=tag), self.assertRaises(ValueError):
                    metadata(root, tag)


if __name__ == '__main__':
    unittest.main()
