# AI Remote Control GPT v2.14.35 — Cloud ChatGPT Integration (SOURCE RELEASE)

Дата: 18 октября 2026. База: v2.14.34. APK **не собран**; эксплуатационные проверки на Android и фактический OAuth не проводились.

## Что добавлено

- Реальная кнопка **ВОЙТИ ЧЕРЕЗ CHATGPT** в Android `MainActivity`.
- Официальный **Sign in with ChatGPT** для локального open-source клиента: браузер, OAuth 2.0 Authorization Code + PKCE, `dynamic_agent_client` при первом входе, стабильный `ext_agent_host_id` `urn:uuid:...`, случайные state/nonce/PKCE, HTTP loopback callback **только на 127.0.0.1**, краткоживущий callback listener.
- Код обмена отправляется только на `https://auth.openai.com/api/accounts/oauth/token` с `resource=https://api.openai.com/v1`; приложение не хранит паролей и не получает переписок ChatGPT.
- Проверка ID token: JWKS OpenAI, подпись RS256, `iss`, `aud`, `azp`, `exp`, `iat`, `nonce`, `sub`.
- Локальное хранение access/refresh/ID token в AES-256-GCM через Android Keystore; приватные данные не выводятся в журналы.
- Автоматическое обновление rotating refresh token на специальном сетевом потоке, взаимное исключение обновлений.
- Кнопки **ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ ОБЛАЧНОГО ИИ**, **СПРОСИТЬ ОБЛАЧНОГО ИИ — ТЕСТ**, **СОСТОЯНИЕ CHATGPT**, **ВЫЙТИ ИЗ CHATGPT**.
- Тест `GET /v1/models` + `POST /v1/responses`, поток SSE до `response.completed`, `store:false`, `stream:true`, допустимый список `input`; без неподдерживаемых параметров предварительного API.
- Отмена/истечение авторизации, обработка ошибок HTTPS, отзыв refresh token при выходе (результат отзыва показывается честно).
- Интернет разрешён только для нового ChatGPT-клиента. Android MediaProjection, OCR, локальная стратегия, память и служба жестов не делают запросы к ChatGPT.

## Установка и испытания (требуется собранный APK)

1. Соберите Android `:app:assembleDebug` через GitHub Actions (`.github/workflows/android.yml`) либо Android Studio с JDK 17 и Android SDK 35.
2. Установите debug APK на Android 10+ (minSdk 29); оригинальное приложение 1win остаётся отдельным.
3. Откройте **AI Remote Control → ВОЙТИ ЧЕРЕЗ CHATGPT**. В браузере выберите аккаунт и разрешите использование подписки для облачных запросов.
4. Вернитесь в приложение → **ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ ОБЛАЧНОГО ИИ**: дождитесь отображения имени модели и ответа.
5. **СПРОСИТЬ ОБЛАЧНОГО ИИ — ТЕСТ**: задайте вопрос (до 1600 символов), проверьте ответ и отсутствие аварийных сбоев.
6. Проверьте **ВЫЙТИ ИЗ CHATGPT** и повторный вход. При потере сети убедитесь, что приложение не нажимает игровую кнопку.

**Ограничения:** OAuth flow не испытан с реальным сервером OpenAI и конкретным аккаунтом; доступ к ChatGPT plan usage определяется самим OpenAI и фактическими разрешениями пользователя. Эта версия предоставляет облачную авторизацию и диалоговую проверку, **но не связывает ответ LLM с контроллером касаний**: нет достоверного источника ID настоящих раздач/очередности хода и подтверждения исполнения команд. Без этого автоматическая игра остаётся заблокированной. Серверный доступ ChatGPT не означает доступ к прошлым диалогам, памяти или другим функциям ChatGPT.

## Разделение компонентов

- `ChatGptAuthProtocol.kt`: чистая Kotlin логика PKCE, параметров OAuth и валидации callback.
- `ChatGptCloudBridge.kt`: Android Keystore, локальный loopback, HTTPS, JWKS/JWT, refresh, SSE.
- `MainActivity.kt`: только пользовательские кнопки, отображение ответа.
- `VerifiedLocalPokerStrategy.kt`, `AutonomousAndroidRuntime.kt`, `LiveInputCoordinator`: сохранены; облачное подключение не может обойти проверку действий или STOP.

## Проверки

- `python -m unittest discover -s tools -p 'test_*.py' -q`: 250 тестов, 0 ошибок (исходники).
- Чистая Kotlin-модель OAuth: `tools/ChatGptAuthScenarios.kt` — 30 сценариев.
- Изолированная компиляция Android ChatGptCloudBridge со строгими Kotlin stubs: успешна; **не равна сборке Android SDK**.
- Legacy Kotlin/JVM сценарии были прогнаны ранее, полная цепочка прервалась по тайм-ауту; последние три набора отдельно завершены успешно (44 + 30 + 25).
- Android Gradle, реальная установка, браузер OAuth, JWKS с сети, SSE с реальным аккаунтом **не проверялись**.

Официальная документация:
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations

GitHub Actions не запускались: подключённый аккаунт даёт доступ только к отдельному служебному репозиторию профиля, запись в ветку сборки не была разрешена. Требуется отдельный репозиторий Android-проекта или локальная Android Studio для выпуска APK.
