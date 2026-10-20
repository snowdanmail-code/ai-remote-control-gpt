#!/usr/bin/env python3
"""Bounded OS-only boot progress; no account data, UI captures or logcat contents."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--budget-seconds',type=int,default=900);p.add_argument('--interval-seconds',type=int,default=15)
    p.add_argument('--serial',default='emulator-5554');p.add_argument('--stop-pid-file')
    a=p.parse_args()
    if a.stop_pid_file:
        f=Path(a.stop_pid_file)
        if f.exists():
            pid=int(f.read_text().strip())
            try:
                if b'monitor_t042_boot.py' in Path('/proc',str(pid),'cmdline').read_bytes():os.kill(pid,signal.SIGTERM)
            except (FileNotFoundError,ProcessLookupError):pass
            f.unlink(missing_ok=True)
        return 0
    adb=shutil.which('adb')
    if not adb:
        print(json.dumps({'status':'NOT_STARTED_ADB_MISSING','budget_seconds':a.budget_seconds}),flush=True);return 2
    start=time.monotonic()
    while time.monotonic()-start<a.budget_seconds:
        result={'elapsed_seconds':round(time.monotonic()-start,1)}
        for name,key in [('boot_completed','sys.boot_completed'),('sdk','ro.build.version.sdk'),('boot_animation','init.svc.bootanim')]:
            try:
                r=subprocess.run([adb,'-s',a.serial,'shell','getprop',key],capture_output=True,text=True,timeout=5)
                result[name]=r.stdout.strip()[:40] if r.returncode==0 else 'DEVICE_NOT_READY'
            except subprocess.TimeoutExpired:result[name]='COMMAND_TIMEOUT'
        print(json.dumps(result),flush=True)
        if result['boot_completed']=='1':return 0
        time.sleep(min(a.interval_seconds,max(0,a.budget_seconds-(time.monotonic()-start))))
    print(json.dumps({'status':'BOOT_BUDGET_EXHAUSTED','elapsed_seconds':round(time.monotonic()-start,1)}),flush=True)
    return 2

if __name__=='__main__':raise SystemExit(main())
