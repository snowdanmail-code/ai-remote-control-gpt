package com.example.airemote

/** Public, non-secret OAuth/inference diagnostics. Codes never contain credentials or URLs. */
enum class ChatGptCloudStep(val code: String, val label: String) {
    PREFLIGHT("AUTH_PREFLIGHT", "проверка сети перед входом"),
    BROWSER("AUTH_BROWSER", "открытие браузера"),
    CALLBACK("AUTH_CALLBACK", "обратный вызов браузера"),
    TOKEN("AUTH_TOKEN", "обмен кода на токены"),
    REFRESH("AUTH_REFRESH", "обновление токенов"),
    IDENTITY("AUTH_IDENTITY", "проверка удостоверения OpenAI"),
    STORE("AUTH_STORE", "защищённое сохранение сеанса"),
    SESSION("CLOUD_SESSION", "проверка сеанса"),
    MODELS("CLOUD_MODELS", "загрузка списка моделей"),
    RESPONSES("CLOUD_RESPONSE", "запрос к облачной модели")
}

enum class ChatGptCloudProblem { NO_SESSION, MISSING_PERMISSION, NO_MODELS_ARRAY,
    NO_VISIBLE_MODEL, NO_REFRESH_TOKEN, REFRESH_REAUTH_REQUIRED, BAD_REFRESH_RESPONSE, BAD_TOKEN_RESPONSE,
    BAD_CALLBACK, BAD_IDENTITY, BAD_STORAGE, INCOMPLETE_STREAM, INFERENCE_FAILED,
    MALFORMED_ID_TOKEN, UNSUPPORTED_ID_ALGORITHM, INVALID_JWKS_RESPONSE, MISSING_SIGNING_KEY,
    INVALID_SIGNATURE, INVALID_ISSUER, INVALID_AUDIENCE, INVALID_ID_TIME, INVALID_NONCE, INVALID_SUBJECT,
    EMPTY_RESPONSE, NETWORK, DNS_FAILURE, CONNECT_FAILURE, TLS_FAILURE, TRANSPORT_FAILURE, TIMEOUT, OTHER }

/** A safely classified error. Never place bearer tokens, responses, or OAuth URLs here. */
object ChatGptCloudDiagnostics {
    fun description(step: ChatGptCloudStep, problem: ChatGptCloudProblem,
                    httpStatus: Int? = null): String {
        val marker = if (httpStatus == null) "${step.code}_${problem.name}" else "${step.code}_HTTP_$httpStatus"
        val advice = if (httpStatus != null) when (httpStatus) {
            400 -> "Сервер отклонил параметры запроса. Повторите вход; при повторении нужен анализ кода OAuth."
            401 -> "Сеанс недействителен или истёк. Попробуйте повторный вход."
            403 -> "Недостаточно разрешений для использования ChatGPT. Проверьте выданное согласие."
            429 -> "Достигнут лимит запросов. Повторите позже."
            500, 502, 503, 504, 520, 521, 522, 523, 524 -> "Сервер временно недоступен или вернул ошибку. Повторите позже."
            else -> "Сервер вернул HTTP $httpStatus; проверьте доступность API и права аккаунта."
        } else when (problem) {
            ChatGptCloudProblem.NO_SESSION -> "Приложение ещё не сохранило подтверждённый сеанс. Дождитесь результата входа в приложении."
            ChatGptCloudProblem.MISSING_PERMISSION -> "Вход выполнен, но разрешение ChatGPT на облачные запросы не выдано. Повторите вход и проверьте согласие."
            ChatGptCloudProblem.NO_MODELS_ARRAY -> "API вернул ответ без ожидаемого списка моделей. Необходимо проверить совместимость клиента с ответом API."
            ChatGptCloudProblem.NO_VISIBLE_MODEL -> "Список получен, но нет доступной модели для запросов. Проверьте права аккаунта и настройки доступа."
            ChatGptCloudProblem.NO_REFRESH_TOKEN -> "Сеанс истёк, токен обновления отсутствует. Повторите вход."
            ChatGptCloudProblem.BAD_REFRESH_RESPONSE -> "Не удалось безопасно обновить токены. Повторите вход."
            ChatGptCloudProblem.BAD_TOKEN_RESPONSE -> "Ответ сервера не содержит нужных токенов. Повторите вход."
            ChatGptCloudProblem.BAD_CALLBACK -> "Не удалось подтвердить ответ браузера (state/client_id/код). Начните вход заново."
            ChatGptCloudProblem.BAD_IDENTITY -> "Неизвестная ошибка проверки подписи или полей удостоверения; сеанс не используется."
            ChatGptCloudProblem.MALFORMED_ID_TOKEN -> "Формат ID-токена не соответствует JWT. Сеанс отклонён."
            ChatGptCloudProblem.UNSUPPORTED_ID_ALGORITHM -> "Алгоритм подписи ID-токена не соответствует ожидаемому RS256."
            ChatGptCloudProblem.INVALID_JWKS_RESPONSE -> "Ключи подписи OpenAI имеют неожиданный формат. Сеанс отклонён."
            ChatGptCloudProblem.MISSING_SIGNING_KEY -> "В опубликованных ключах OpenAI не найден ключ подписи токена."
            ChatGptCloudProblem.INVALID_SIGNATURE -> "Криптографическая подпись ID-токена не подтверждена."
            ChatGptCloudProblem.INVALID_ISSUER -> "Не совпал издатель удостоверения OpenAI."
            ChatGptCloudProblem.INVALID_AUDIENCE -> "Удостоверение выдано для другого OAuth-клиента."
            ChatGptCloudProblem.INVALID_ID_TIME -> "Срок действия удостоверения или часы устройства не прошли проверку."
            ChatGptCloudProblem.INVALID_NONCE -> "Не совпала привязка удостоверения к текущей попытке входа."
            ChatGptCloudProblem.INVALID_SUBJECT -> "Удостоверение не содержит подтверждённый идентификатор аккаунта."
            ChatGptCloudProblem.REFRESH_REAUTH_REQUIRED -> "Исход обновления токена не подтверждён. Повторное использование запрещено; выполните новый вход."
            ChatGptCloudProblem.BAD_STORAGE -> "Не удалось сохранить защищённый сеанс Android."
            ChatGptCloudProblem.INCOMPLETE_STREAM -> "Поток ответа оборвался до подтверждения завершения."
            ChatGptCloudProblem.INFERENCE_FAILED -> "Облачный сервер сообщил об ошибке при генерации ответа."
            ChatGptCloudProblem.EMPTY_RESPONSE -> "Модель завершила запрос без текста; подключение не подтверждено."
            ChatGptCloudProblem.NETWORK -> "Не удалось подключиться к серверу. Проверьте интернет и DNS."
            ChatGptCloudProblem.DNS_FAILURE -> "Android-приложение не может найти адрес OpenAI через DNS. Проверьте частный DNS, VPN или смените сеть."
            ChatGptCloudProblem.CONNECT_FAILURE -> "DNS доступен, но Android-приложение не может установить соединение с OpenAI. Проверьте фильтрацию сети, VPN и доступ приложения к интернету."
            ChatGptCloudProblem.TLS_FAILURE -> "HTTPS-соединение не прошло проверку сертификата или TLS. Проверьте дату устройства и сетевые фильтры; не отключайте проверку сертификатов."
            ChatGptCloudProblem.TRANSPORT_FAILURE -> "Сетевое соединение оборвалось. Попробуйте другую сеть и повторите вход с новым кодом."
            ChatGptCloudProblem.TIMEOUT -> "Превышено время ожидания. Попробуйте ещё раз."
            ChatGptCloudProblem.OTHER -> "Неожиданная ошибка на этом этапе; для диагностики потребуется новый код ошибки."
        }
        return "${step.label}: $marker. $advice"
    }
}
