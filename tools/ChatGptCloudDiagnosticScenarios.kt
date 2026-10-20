package com.example.airemote

object ChatGptCloudDiagnosticScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun verify(actual: String, code: String, fragment: String) {
            check(code in actual) { "Missing $code: $actual" }
            check(fragment in actual) { "Missing $fragment: $actual" }
            check("Bearer " !in actual && "refresh_token=" !in actual)
            n++
        }
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.SESSION, ChatGptCloudProblem.NO_SESSION), "CLOUD_SESSION_NO_SESSION", "не сохранило")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.SESSION, ChatGptCloudProblem.MISSING_PERMISSION), "CLOUD_SESSION_MISSING_PERMISSION", "разрешение")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.CALLBACK, ChatGptCloudProblem.BAD_CALLBACK), "AUTH_CALLBACK_BAD_CALLBACK", "state")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.TOKEN, ChatGptCloudProblem.BAD_TOKEN_RESPONSE), "AUTH_TOKEN_BAD_TOKEN_RESPONSE", "токенов")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.IDENTITY, ChatGptCloudProblem.BAD_IDENTITY), "AUTH_IDENTITY_BAD_IDENTITY", "подписи")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.STORE, ChatGptCloudProblem.BAD_STORAGE), "AUTH_STORE_BAD_STORAGE", "сохранить")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.MODELS, ChatGptCloudProblem.NO_MODELS_ARRAY), "CLOUD_MODELS_NO_MODELS_ARRAY", "списка")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.MODELS, ChatGptCloudProblem.NO_VISIBLE_MODEL), "CLOUD_MODELS_NO_VISIBLE_MODEL", "доступной")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.RESPONSES, ChatGptCloudProblem.INCOMPLETE_STREAM), "CLOUD_RESPONSE_INCOMPLETE_STREAM", "оборвался")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.SESSION, ChatGptCloudProblem.NO_REFRESH_TOKEN), "CLOUD_SESSION_NO_REFRESH_TOKEN", "истёк")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.SESSION, ChatGptCloudProblem.BAD_REFRESH_RESPONSE), "CLOUD_SESSION_BAD_REFRESH_RESPONSE", "обновить")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.RESPONSES, ChatGptCloudProblem.INFERENCE_FAILED), "CLOUD_RESPONSE_INFERENCE_FAILED", "генерации")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.MODELS, ChatGptCloudProblem.NETWORK), "CLOUD_MODELS_NETWORK", "интернет")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.TOKEN, ChatGptCloudProblem.TIMEOUT), "AUTH_TOKEN_TIMEOUT", "время")
        verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.MODELS, ChatGptCloudProblem.OTHER), "CLOUD_MODELS_OTHER", "Неожиданная")
        for (http in listOf(400,401,403,429,500,502,503,520,524,418)) {
            verify(ChatGptCloudDiagnostics.description(ChatGptCloudStep.MODELS, ChatGptCloudProblem.OTHER, http),
                "CLOUD_MODELS_HTTP_$http", "сервер".let { if (http == 520 || http == 500) "Сервер" else if (http == 418) "Сервер" else if (http == 400) "Сервер" else if (http == 401) "Сеанс" else if (http == 403) "разрешений" else if (http == 429) "лимит" else "Сервер" })
        }
        println("CHATGPT_CLOUD_DIAGNOSTICS_OK=$n")
    }
}
