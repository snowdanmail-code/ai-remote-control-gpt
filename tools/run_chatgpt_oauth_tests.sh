#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null
command -v kotlin >/dev/null
mkdir -p .build-jvm
kotlinc android/app/src/main/java/com/example/airemote/ChatGptAuthProtocol.kt android/app/src/main/java/com/example/airemote/ChatGptCloudDiagnostics.kt android/app/src/main/java/com/example/airemote/ChatGptTransportDiagnostics.kt tools/ChatGptAuthScenarios.kt tools/ChatGptCloudDiagnosticScenarios.kt tools/ChatGptTransportScenarios.kt -d .build-jvm/chatgpt-auth.jar
kotlin -classpath .build-jvm/chatgpt-auth.jar com.example.airemote.ChatGptAuthScenarios
kotlin -classpath .build-jvm/chatgpt-auth.jar com.example.airemote.ChatGptCloudDiagnosticScenarios
kotlin -classpath .build-jvm/chatgpt-auth.jar com.example.airemote.ChatGptTransportScenarios
python -m unittest tools.test_chatgpt_cloud_source tools.test_chatgpt_connection_diagnostics_source tools.test_chatgpt_transport_source -v
