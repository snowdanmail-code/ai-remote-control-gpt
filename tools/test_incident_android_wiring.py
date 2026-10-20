"""Checks telemetry is actually wired to Android, not only a standalone JVM exercise."""
import re
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / 'android/app/src/main/java/com/example/airemote'


def src(name):
    return (SRC / f'{name}.kt').read_text(encoding='utf-8')


class AndroidIncidentWiringTest(unittest.TestCase):
    def test_recorder_is_local(self):
        code = src('OnDeviceIncidentRecorder')
        self.assertIn('context.applicationContext.filesDir', code)
        self.assertIn('private const val MAX_FILE_BYTES = 160_000L', code)
        self.assertIn('reportJson', code)
        self.assertNotIn('HttpURLConnection', code)
        self.assertNotIn('accessToken', code)

    def test_stop_recorded(self):
        self.assertIn('IncidentCode.OPERATOR_STOP', src('OperatorStopStore'))
        self.assertIn('IncidentCode.OPERATOR_STOP', src('AutonomousActionLoop'))
        self.assertIn('IncidentCode.COMMAND_UNCERTAIN', src('AutonomousActionLoop'))

    def test_only_os_dispatch_is_dispatched(self):
        self.assertNotIn('IncidentCode.COMMAND_DISPATCHED', src('AutonomousActionLoop'))
        self.assertIn('IncidentCode.COMMAND_REQUESTED', src('AutonomousActionLoop'))
        self.assertIn('IncidentCode.COMMAND_DISPATCHED', src('LiveGestureAccessibilityService'))
        self.assertIn('IncidentCode.GESTURE_COMPLETED_OS', src('LiveGestureAccessibilityService'))
        self.assertIn('command.basedOnFrame', src('LiveGestureAccessibilityService'))
        self.assertNotIn('command.frameSequence', src('LiveGestureAccessibilityService'))

    def test_capture_errors_recorded(self):
        code = src('CaptureService')
        for event in ('CAPTURE_STARTED', 'CAPTURE_STOPPED', 'CAPTURE_PERMISSION_ERROR',
                      'CAPTURE_FRAME_ERROR'):
            self.assertIn(f'IncidentCode.{event}', code)

    def test_ocr_wired(self):
        code = src('OnDeviceAutomaticOcr')
        for event in ('OCR_STALE', 'OCR_CONFLICT', 'OCR_ENGINE_ERROR'):
            self.assertIn(f'IncidentCode.{event}', code)

    def test_export_is_user_initiated(self):
        code = src('MainActivity')
        self.assertIn('Intent.ACTION_CREATE_DOCUMENT', code)
        self.assertIn('OnDeviceIncidentRecorder.exportReport(this)', code)
        self.assertIn('incidentExportRequest', code)
        self.assertNotIn('ACTION_SENDTO', code)

    def test_no_network_permission(self):
        manifest = (SRC.parents[3] / 'AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET', manifest)

    def test_stop_is_nonblocking_even_with_slow_strategy(self):
        loop = src('AutonomousActionLoop')
        runtime = src('AutonomousAndroidRuntime')
        stop = src('OperatorStopStore')
        self.assertIn('fun requestAbort()', loop)
        self.assertIn('if (abortRequested) return stop(', loop)
        self.assertIn('fun stop(reason: String = "STOP"):', runtime)
        self.assertNotIn('@Synchronized fun stop(reason: String = "STOP"):', runtime)
        self.assertLess(stop.index('LiveGamePermissionGate.revoke()'),
                        stop.index('OnDeviceAutonomousRuntime.stop("Аварийный STOP")'))

    def test_deadline_check_after_model(self):
        code = src('AutonomousActionLoop')
        self.assertLess(code.index('strategy.decide(obs)'),
                        code.index('DecisionDeadlineGate.assess('))
        self.assertLess(code.index('DecisionDeadlineGate.assess('),
                        code.index('transport.submit(commandId'))


if __name__ == '__main__':
    unittest.main()
