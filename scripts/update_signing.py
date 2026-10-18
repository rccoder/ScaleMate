"""Generate and verify signatures for update metadata using the release certificate.

Private key material is read only from the existing local signing credentials or
the release keystore restored by CI. No secret is printed.
"""
import argparse
import base64
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def credentials(directory):
    info = json.loads((directory / 'credentials.json').read_text())
    return directory / 'scalemate-release.jks', info


def sign_pkcs1(keystore, info, data):
    with tempfile.TemporaryDirectory() as temp:
        temp = Path(temp)
        p12 = temp / 'release.p12'
        cert = temp / 'certificate.pem'
        payload = temp / 'payload.bin'
        signature = temp / 'signature.bin'
        payload.write_bytes(data)
        signing_env = dict(os.environ, SCALEMATE_SIGN_STORE_PASSWORD=info['storePassword'],
                           SCALEMATE_SIGN_KEY_PASSWORD=info['keyPassword'])
        subprocess.run([
            'keytool', '-importkeystore',
            '-srckeystore', str(keystore),
            '-srcstorepass:env', 'SCALEMATE_SIGN_STORE_PASSWORD',
            '-srcalias', info['alias'],
            '-srckeypass:env', 'SCALEMATE_SIGN_KEY_PASSWORD',
            '-destkeystore', str(p12),
            '-deststoretype', 'PKCS12',
            '-deststorepass', 'temporary-passphrase',
            '-destkeypass', 'temporary-passphrase',
            '-noprompt',
        ], env=signing_env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run([
            'openssl', 'pkcs12', '-in', str(p12),
            '-passin', 'pass:temporary-passphrase',
            '-nocerts', '-nodes', '-out', str(cert),
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run([
            'openssl', 'dgst', '-sha256', '-sign', str(cert),
            '-out', str(signature), str(payload),
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return base64.b64encode(signature.read_bytes())


def sign_with_credentials(keystore, store_password, alias, key_password, data):
    return sign_pkcs1(
        keystore,
        {
            'storePassword': store_password,
            'alias': alias,
            'keyPassword': key_password,
        },
        data,
    )


def export_public_key(keystore, store_password, alias, key_password, output):
    certificate = subprocess.check_output([
        'keytool', '-exportcert', '-rfc', '-keystore', str(keystore),
        '-storepass:env', 'SCALEMATE_SIGN_STORE_PASSWORD', '-alias', alias,
    ], env=dict(os.environ, SCALEMATE_SIGN_STORE_PASSWORD=store_password),
        stderr=subprocess.DEVNULL)
    public_key = subprocess.check_output(
        ['openssl', 'x509', '-pubkey', '-noout'], input=certificate)
    encoded = ''.join(public_key.decode().splitlines()[1:-1])
    configured = re.search(r'UPDATE_PUBLIC_KEY_BASE64.*?MIIB[^"\\]+',
                           (ROOT / 'app/build.gradle.kts').read_text())
    if configured is None or encoded not in configured.group():
        raise ValueError('Release public key differs from Android updater public key')
    output.write_bytes(public_key)


def verify_pkcs1(public_key_pem, data, signature):
    with tempfile.TemporaryDirectory() as temp:
        temp = Path(temp)
        payload = temp / 'payload.bin'
        sig = temp / 'signature.bin'
        payload.write_bytes(data)
        sig.write_bytes(base64.b64decode(signature.strip(), validate=True))
        subprocess.run([
            'openssl', 'dgst', '-sha256', '-verify', str(public_key_pem),
            '-signature', str(sig), str(payload),
        ], check=True, stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    public_key = sub.add_parser('public-key')
    public_key.add_argument('--keystore', type=Path, required=True)
    public_key.add_argument('--store-password', default=os.environ.get('ANDROID_KEYSTORE_PASSWORD'))
    public_key.add_argument('--alias', default=os.environ.get('ANDROID_KEY_ALIAS'))
    public_key.add_argument('--key-password', default=os.environ.get('ANDROID_KEY_PASSWORD'))
    public_key.add_argument('--output', type=Path, required=True)
    sign = sub.add_parser('sign')
    sign.add_argument('--input', type=Path, required=True)
    sign.add_argument('--output', type=Path, required=True)
    sign.add_argument('--signing-dir', type=Path, default=Path.home() / '.local/share/scalemate-signing')
    sign.add_argument('--keystore', type=Path)
    sign.add_argument('--store-password', default=os.environ.get('ANDROID_KEYSTORE_PASSWORD'))
    sign.add_argument('--alias', default=os.environ.get('ANDROID_KEY_ALIAS'))
    sign.add_argument('--key-password', default=os.environ.get('ANDROID_KEY_PASSWORD'))
    verify = sub.add_parser('verify')
    verify.add_argument('--input', type=Path, required=True)
    verify.add_argument('--signature', type=Path, required=True)
    verify.add_argument('--public-key', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'public-key':
        export_public_key(
            args.keystore,
            args.store_password,
            args.alias,
            args.key_password,
            args.output,
        )
    elif args.command == 'sign':
        if args.keystore is not None:
            if not all((args.store_password, args.alias, args.key_password)):
                parser.error('Explicit keystore signing requires store password, alias and key password')
            args.output.write_bytes(sign_with_credentials(
                args.keystore,
                args.store_password,
                args.alias,
                args.key_password,
                args.input.read_bytes(),
            ))
        else:
            keystore, info = credentials(args.signing_dir)
            args.output.write_bytes(sign_pkcs1(keystore, info, args.input.read_bytes()))
    else:
        verify_pkcs1(args.public_key, args.input.read_bytes(), args.signature.read_text())
        print('Update metadata signature verified')


if __name__ == '__main__':
    main()
