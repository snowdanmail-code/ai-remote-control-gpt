#!/usr/bin/env python3
"""Fail-closed APK update-compatibility gate. No secrets or keystores are read.

Offline verification for APK already built and signed by Android Gradle Plugin.
To assert in-place update compatibility, an independently verified baseline
package name, signing certificate fingerprint, and version code are required.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

PACKAGE_RE = re.compile(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'")
CERT_RE = re.compile(r"Signer\s+#\d+\s+certificate\s+SHA-256\s+digest:\s*([0-9a-fA-F: ]{64,})", re.I)
CERTHEX = re.compile(r"^[0-9a-f]{64}$")


def normalize_fingerprint(value):
    value = re.sub(r"[:\s]", "", value).lower()
    if not CERTHEX.fullmatch(value):
        raise ValueError("invalid fingerprint; expected 64 hex characters")
    return value


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, check=False, timeout=30)
    if result.returncode != 0:
        raise ValueError("Android tool failed: " + Path(args[0]).name)
    return result.stdout + result.stderr


def parse_package(dump, require_version=True):
    m = PACKAGE_RE.search(dump)
    if m:
        return {'package': m.group(1), 'version_code': int(m.group(2)), 'version_name': m.group(3)}
    # Instrumentation APKs do not need app update-version metadata. Only their
    # package identity may use this path; release/target inspection stays strict.
    if not require_version:
        m = re.search(r"^package: name='([^']+)'(?: |$)", dump, re.M)
        if m:
            return {'package': m.group(1), 'version_code': None, 'version_name': None}
    raise ValueError('aapt did not report package/version metadata')


def inspect(apk, aapt, apksigner, require_version=True):
    if not apk.is_file() or apk.stat().st_size < 512:
        raise ValueError("missing/empty APK")
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None or 'AndroidManifest.xml' not in z.namelist():
            raise ValueError('corrupt APK / missing AndroidManifest.xml')
        if not any(n == 'classes.dex' or re.fullmatch(r'classes\d+\.dex', n) for n in z.namelist()):
            raise ValueError('APK missing DEX bytecode')
    dump = command([aapt, 'dump', 'badging', str(apk)])
    metadata = parse_package(dump, require_version=require_version)
    sig = command([apksigner, 'verify', '--verbose', '--print-certs', str(apk)])
    match = CERT_RE.findall(sig)
    if len(match) != 1:
        raise ValueError('expected exactly one signer certificate')
    cert = normalize_fingerprint(match[0])
    return {**metadata, 'signer_sha256': cert,
            'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'size_bytes': apk.stat().st_size}


def verify(actual, baseline):
    if actual['package'] != baseline['package']:
        raise ValueError('PACKAGE_MISMATCH: cannot upgrade in place')
    if actual['signer_sha256'] != normalize_fingerprint(baseline['signer_sha256']):
        raise ValueError('SIGNATURE_MISMATCH: cannot upgrade in place')
    if actual['version_code'] <= int(baseline['version_code']):
        raise ValueError('VERSION_NOT_INCREASED: Android update will be rejected')
    if not actual['version_name']:
        raise ValueError('VERSION_NAME_MISSING')
    return {'status': 'VERIFIED_FOR_IN_PLACE_UPDATE', **actual, 'baseline_version_code': int(baseline['version_code'])}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--aapt', default=shutil.which('aapt'))
    p.add_argument('--apksigner', default=shutil.which('apksigner'))
    p.add_argument('--baseline-apk', type=Path, help='most reliable: actual previously installed/signed APK')
    p.add_argument('--baseline-metadata', type=Path, help='metadata extracted from exact prior signed APK bytes')
    p.add_argument('--baseline-package', help='use only if verified from prior installed APK')
    p.add_argument('--baseline-cert-sha256', help='use only if verified from prior installed APK')
    p.add_argument('--baseline-version-code', type=int)
    p.add_argument('--report', type=Path)
    args = p.parse_args()
    try:
        if not args.aapt or not args.apksigner:
            raise ValueError('Android SDK aapt/apksigner missing; cannot verify APK')
        candidate = inspect(args.candidate, args.aapt, args.apksigner)
        if args.baseline_apk:
            baseline = inspect(args.baseline_apk, args.aapt, args.apksigner)
        elif args.baseline_metadata:
            baseline = json.loads(args.baseline_metadata.read_text(encoding='utf-8'))
            if 'source_apk_sha256' not in baseline or not CERTHEX.fullmatch(baseline['source_apk_sha256']):
                raise ValueError('baseline file missing original APK checksum')
        elif (args.baseline_package and args.baseline_cert_sha256 and args.baseline_version_code is not None):
            baseline = {'package': args.baseline_package,
                        'signer_sha256': args.baseline_cert_sha256,
                        'version_code': args.baseline_version_code}
        else:
            raise ValueError('BASELINE_REQUIRED: provide verified prior APK or its signed metadata')
        result = verify(candidate, baseline)
        print('Upgrade compatibility: VERIFIED')
        print('Package:', result['package'])
        print('Version:', result['version_code'])
        print('SHA-256:', result['apk_sha256'])
        print('Certificate SHA-256:', result['signer_sha256'])
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    except (ValueError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        print('NOT VERIFIED:', str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
