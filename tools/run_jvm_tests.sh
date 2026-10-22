#!/usr/bin/env bash
# Pure Kotlin/JVM checks, no Android SDK/APK or real-world gestures.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'kotlinc missing' >&2; exit 2; }
command -v kotlin >/dev/null || { echo 'kotlin runner missing' >&2; exit 2; }
mkdir -p .build-jvm
mapfile -t sources < <(find android/app/src/main/java/com/example/airemote -maxdepth 1 -name '*.kt' -print | sort | while read -r f; do
  if ! grep -Eq '^import (android\.|androidx\.|org\.json|com\.google\.mlkit|okhttp3\.)' "$f"; then echo "$f"; fi
done)
mapfile -t scenarios < <(find tools -maxdepth 1 -name '*Scenarios.kt' -print | sort)
kotlinc -J-Xmx2g "${sources[@]}" "${scenarios[@]}" -d .build-jvm/scenarios.jar
classes=(
CardConsensusScenariosKt
CardStageBridgeScenarios
CardVisionScenariosKt
ForegroundCaptureGateScenariosKt
GameReadinessScenarios
HandStateScenarios
com.example.airemote.LiveDispatchFreshnessScenariosKt
com.example.airemote.AutonomousActionScenariosKt
com.example.airemote.VerifiedLocalPokerStrategyScenarios
com.example.airemote.UniformUnknownEquityScenarios
com.example.airemote.PlayerModelDecisionScenarios
com.example.airemote.ValidatedPlayerStrategyScenarios
com.example.airemote.LocalPlayerCheckScenarios
com.example.airemote.VisualOcrCheckScenarios
com.example.airemote.VirtualChipDisplayScenarios
com.example.airemote.OperatorAutomationAuthorizationScenariosKt
com.example.airemote.IncidentJournalScenarios
com.example.airemote.IncidentJournalFieldsScenarios
com.example.airemote.DeadlineScenarios
com.example.airemote.PokerMathScenarios
com.example.airemote.OpponentAwarenessScenarios
com.example.airemote.SeatVisualScenarios
com.example.airemote.SeatTransitionScenarios
com.example.airemote.RealFrameRegionScenarios
com.example.airemote.ActionControlScenarios
com.example.airemote.HeroTurnLifecycleScenarios
com.example.airemote.VerifiedTurnBindingScenarios
com.example.airemote.AuthenticatedActionEffectsScenarios
com.example.airemote.DispatchAdmissionScenarios
com.example.airemote.AutonomyEndToEndScenarios
com.example.airemote.AuthorizedPlayerFullCycleScenarios
com.example.airemote.TrustedPlayerInputScenarios
com.example.airemote.SignedPokerProviderScenarios
com.example.airemote.ChatGptAuthScenarios
com.example.airemote.ChatGptLoginPreflightScenarios
com.example.airemote.ChatGptCloudDiagnosticScenarios
com.example.airemote.ChatGptIdentityPolicyScenarios
com.example.airemote.ChatGptTokenConnectScenarios
com.example.airemote.RecordedSurfaceScenarios
com.example.airemote.OpponentGateIntegrationScenarios
com.example.airemote.AutomaticOcrEvidenceScenariosKt
com.example.airemote.LiveGameActionGuardScenariosKt
com.example.airemote.LiveTapSafetyScenariosKt
com.example.airemote.PostHandLearningScenariosKt
TurnDisplayScenariosKt
com.example.airemote.VerifiedTurnLifecycleScenariosKt
com.example.airemote.VerifiedHandCompletionGateScenariosKt
CaptureLifecycleScenarios
FrameNumericScenarios
FrameTextScenarios
OcrProvenanceScenarios
OcrDiagnosticScenarios
TableHandIdentityScenarios
FramePublicationScenarios
com.example.airemote.WindowOriginScenarios
)
for cl in "${classes[@]}"; do
  case "$cl" in
    CardVisionScenariosKt) kotlin -classpath .build-jvm/scenarios.jar "$cl" tools/fixtures ;;
    TurnDisplayScenariosKt) kotlin -classpath .build-jvm/scenarios.jar "$cl" tools/fixtures/turn ;;
    *) kotlin -classpath .build-jvm/scenarios.jar "$cl" ;;
  esac
done
