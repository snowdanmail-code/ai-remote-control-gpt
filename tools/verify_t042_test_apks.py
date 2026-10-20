#!/usr/bin/env python3
"""Require the original signer on BOTH target and instrumentation APK, before installation."""
import argparse
import json
import sys
from pathlib import Path
from verify_android_upgrade import inspect, normalize_fingerprint


def verify_pair(target, test, baseline):
    expected=normalize_fingerprint(baseline['signer_sha256'])
    if target['package'] != baseline['package'] or test['package'] != baseline['package']+'.test':
        raise ValueError('INSTRUMENTATION_PACKAGE_MISMATCH')
    if target['signer_sha256'] != expected or test['signer_sha256'] != expected:
        raise ValueError('INSTRUMENTATION_SIGNER_MISMATCH: do not generate or substitute a debug key')
    if target['version_code'] != int(baseline['version_code']):
        raise ValueError('INSTRUMENTATION_CHECKPOINT_VERSION_MISMATCH')
    return {'status':'ORIGINAL_SIGNER_ON_TARGET_AND_TEST_APK','target':target,'test':test}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ['target','test','baseline','aapt','apksigner','report']:p.add_argument('--'+flag,required=True)
    a=p.parse_args()
    try:
        baseline=json.loads(Path(a.baseline).read_text())
        result=verify_pair(inspect(Path(a.target),a.aapt,a.apksigner),inspect(Path(a.test),a.aapt,a.apksigner,require_version=False),baseline)
        Path(a.report).parent.mkdir(parents=True,exist_ok=True)
        Path(a.report).write_text(json.dumps(result,indent=2)+'\n')
        print(result['status'])
    except Exception as e:
        print('BLOCKED:',type(e).__name__,str(e),file=sys.stderr);return 2
    return 0

if __name__=='__main__':sys.exit(main())
