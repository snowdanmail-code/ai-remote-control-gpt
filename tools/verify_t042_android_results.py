#!/usr/bin/env python3
"""A Gradle exit code alone is insufficient: all mandatory Android tests must execute and pass."""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED={
 'com.example.airemote.AndroidOAuthTlsTransportTest':{
  'actualHttpsGetThenTokenPostSendsOneCode','untrustedAndroidTlsSendsNoCodeAndDoesNotRetry',
  'dnsAtConnectionCreationIsBoundedBeforeBody','networkLossBeforePostDoesNotRetry',
  'androidReadTimeoutAfterPostDoesNotReplay','server503AndPostDisconnectNeverReplayCode',
  'mockTokenEndpointRejectsWrongRedirectAndPkce'},
 'com.example.airemote.AndroidOAuthBrowserTest':{
  'browserOwnsCustomTabAndReturnsSyntheticLoopbackCallback',
  'malformedStateAndNonLoopbackCallbacksAreRejected',
  'callbackTimeoutAndNetworkExceptionsRemainClassified',
  'androidKeystoreEncryptsAndRotatesSyntheticRefreshRecord',
  'proofRequiresCompletedNonemptyResponseOnAndroid'},
 'com.example.airemote.AndroidOAuth042StorageTest':{
  'keystoreRotationPersistsOnlySealedCredentials','corruptKeystoreRecordIsBadStorageNotNoSession',
  'logoutRetainsVerifiedIdentityMappingAndClearsSession','pendingRefreshReservationSurvivesStoreRestore'}
}


def verify_reports(directory):
    files=sorted(Path(directory).rglob('TEST-*.xml'))
    if not files:raise ValueError('ANDROID_RESULTS_MISSING')
    seen=set();count=0
    for path in files:
        root=ET.parse(path).getroot()
        for suite in root.iter('testsuite'):
            if any(int(suite.get(k,'0')) for k in ['failures','errors','skipped','disabled']):
                raise ValueError('ANDROID_SUITE_NOT_ALL_PASS')
        for case in root.iter('testcase'):
            if any(case.find(k) is not None for k in ['failure','error','skipped']):
                raise ValueError('ANDROID_TEST_NOT_EXECUTED_OR_FAILED')
            if case.get('status','').lower() in ['notrun','skipped','disabled']:
                raise ValueError('ANDROID_TEST_NOT_EXECUTED')
            seen.add((case.get('classname'),case.get('name')));count+=1
    missing={(c,n) for c,names in REQUIRED.items() for n in names}-seen
    if missing:raise ValueError('MANDATORY_ANDROID_TESTS_MISSING')
    return {'status':'MANDATORY_ANDROID_TESTS_EXECUTED_PASS','tests':count,'mandatory_tests':sum(map(len,REQUIRED.values())),'xml_files':len(files)}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',required=True);p.add_argument('--report',required=True);a=p.parse_args()
    try:
        result=verify_reports(a.directory);Path(a.report).parent.mkdir(parents=True,exist_ok=True)
        Path(a.report).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
    except Exception as e:
        print('BLOCKED:',type(e).__name__,str(e),file=sys.stderr);return 2
    return 0

if __name__=='__main__':sys.exit(main())
