"""T043 static isolation contracts; functional cases run in JVM and Android Gradle tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'android/app/src/main/java/com/example/airemote'


class T043CanonicalIsolation(unittest.TestCase):
    def test_new_model_has_no_platform_dependencies(self):
        src = '\n'.join((MAIN / name).read_text() for name in
                        ('CanonicalPokerModel.kt', 'PokerCanonicalIdentity.kt'))
        for forbidden in ('import android.', 'import androidx.', 'import okhttp3.',
                          'import org.json.', 'AccessibilityService', 'dispatchGesture',
                          'OperatorStopStore', 'ChatGptCloudBridge', 'FrameStore'):
            self.assertNotIn(forbidden, src)

    def test_adapter_has_no_ui_coordinate_or_live_tap_fields(self):
        src = (MAIN / 'CanonicalPokerModel.kt').read_text()
        for forbidden in ('screenX:', 'screenY:', 'pixelColor:', 'clickX:', 'clickY:'):
            self.assertNotIn(forbidden, src)
        self.assertIn('interface PokerSourceAdapter<in T>', src)
        self.assertIn('object PokerSourceAdapterBoundary', src)

    def test_json_is_an_isolated_boundary(self):
        src = (MAIN / 'CanonicalPokerJson.kt').read_text()
        self.assertIn('fun encodeObservation', src)
        self.assertIn('fun decodeObservation', src)
        self.assertIn('fun encodeVerifiedHand', src)
        self.assertIn('fun decodeVerifiedHand', src)

    def test_two_synthetic_sources_and_all_table_sizes(self):
        src = (ROOT / 'tools/CanonicalPokerScenarios.kt').read_text()
        self.assertIn('AlphaFixtureAdapter', src)
        self.assertIn('BetaFixtureAdapter', src)
        self.assertIn('for (n in 2..9)', src)

    def test_no_migration_or_production_trust_bridge_in_new_files(self):
        src = '\n'.join((MAIN / f).read_text() for f in
                        ('CanonicalPokerModel.kt', 'CanonicalPokerJson.kt',
                         'PokerCanonicalIdentity.kt'))
        for forbidden in ('SQLiteOpenHelper', 'OnDeviceVerifiedHandCompletion',
                          'OnDeviceAutonomousRuntime', 'HttpURLConnection', 'FTPClient'):
            self.assertNotIn(forbidden, src)


    def test_ruleset_and_event_fields_are_mandatory_and_serialized(self):
        model = (MAIN / 'CanonicalPokerModel.kt').read_text()
        codec = (MAIN / 'CanonicalPokerJson.kt').read_text()
        self.assertIn('val rulesetId: String,', model)
        self.assertIn('val eventId: String', model)
        self.assertIn('actions.map { it.eventId }.distinct()', model)
        for field in ('rulesetId', 'eventId', 'handRecordId', 'verificationPolicyId'):
            self.assertIn('requiredString("' + field + '")', codec)
            self.assertIn('"' + field + '"', codec)

    def test_proof_policy_rejects_unverified_without_external_verifier(self):
        gate = (MAIN / 'PokerCanonicalIdentity.kt').read_text()
        self.assertIn('fun interface PokerHandProofVerifier', gate)
        self.assertIn('MISSING_VERIFIER', gate)
        self.assertIn('UNSUPPORTED_POLICY', gate)
        self.assertIn('UNSUPPORTED_SCHEMA', gate)
        self.assertIn('UNSUPPORTED_RULESET', gate)
        self.assertIn('verifier.verifyCompleteRecord(record)', gate)

    def test_stable_event_hand_identity_and_import_conflict_exist(self):
        src = (MAIN / 'PokerCanonicalIdentity.kt').read_text()
        self.assertIn('fun observation(source: PokerSourceIdentity', src)
        self.assertIn('fun event(source: PokerSourceIdentity', src)
        self.assertIn('fun hand(source: PokerSourceIdentity)', src)
        self.assertIn('toByteArray(Charsets.UTF_8)', src)
        self.assertIn('PokerImportStatus.CONFLICT', src)


if __name__ == '__main__':
    unittest.main()
