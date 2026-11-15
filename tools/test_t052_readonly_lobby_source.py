from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android/app/src/main/java/com/example/airemote"

class T052PassiveLobbySourceTests(unittest.TestCase):
    def test_read_only_probe_has_no_gesture_authority(self):
        src = (JAVA / "OnDeviceCashLobbyReadOnlyProbe.kt").read_text()
        for forbidden in ("dispatchGesture", "offerOneWinCashLobbyNavigation",
                          "offerOneWinCashLobbyEntry", "OnDeviceOneWinPreSeatScanner.start",
                          "offerOneWinTableExitNavigation", "armFromOperator"):
            self.assertNotIn(forbidden, src)
        self.assertIn("FrameStore.isCapturing", src)
        self.assertIn("OperatorStopStore.isStopped(context)", src)
        self.assertIn("OnDeviceOneWinCashLobby.reset()", src)

    def test_passive_ocr_never_notifies_selection(self):
        src = (JAVA / "OnDeviceOneWinCashLobby.kt").read_text()
        self.assertIn("allowSelection: Boolean = true", src)
        self.assertIn("if (allowSelection) {", src)
        self.assertIn("confirmedNew?.let { OnDeviceCashLobbySelection.onConfirmed(context, it) }", src)

    def test_capture_can_probe_without_arming_auto_select(self):
        src = (JAVA / "CaptureService.kt").read_text()
        self.assertIn("(choosingLobby || OnDeviceCashLobbyReadOnlyProbe.enabled())", src)
        self.assertIn("allowSelection = choosingLobby", src)
        self.assertIn("OnDeviceCashLobbyReadOnlyProbe.stop()", src)

    def test_global_stop_and_autoselection_do_not_race_passive_probe(self):
        stop = (JAVA / "OperatorStopStore.kt").read_text()
        selector = (JAVA / "OnDeviceCashLobbySelection.kt").read_text()
        self.assertIn("OnDeviceCashLobbyReadOnlyProbe.stop()", stop)
        self.assertIn("if (armed || OnDeviceCashLobbyReadOnlyProbe.enabled()", selector)

    def test_operator_has_three_visible_safe_controls(self):
        src = (JAVA / "MainActivity.kt").read_text()
        for title in (
            "1WIN — ТОЛЬКО OCR СТОЛОВ (БЕЗ НАЖАТИЙ)",
            "1WIN — РЕЗУЛЬТАТ OCR СТОЛОВ",
            "1WIN — ОСТАНОВИТЬ OCR СТОЛОВ"
        ):
            self.assertGreaterEqual(src.count(title), 2)

if __name__ == "__main__":
    unittest.main()
