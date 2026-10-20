# ТЗ №042 — проверка CI и оставшийся доступ

Статус: BLOCKED, новый Android runtime и APK не запускались.

Проверена ветка t042-checkpoint на исходной точке f506a4b87f5dab734dd27151d11f784196b44bf7. Прежний успешный unit CI: https://github.com/snowdanmail-code/ai-remote-control-gpt/actions/runs/39501499775 . Это исторический PASS, не доказательство авторизации.

Изменения CI: восстановление существующего ключа и проверка точного сертификата перенесены перед установкой SDK и тестами; для Gradle после восстановления заданы только ссылки на защищённые secrets. Отключено сохранение checkout credentials. Добавлен безопасный JSON о проверке сертификата, создаваемый только после фактического успешного keytool, без имён/паролей/ключей. Барьеры 16 Android-тестов, обеих APK-подписей, API36/KVM, версии и целостности сохранены.

Проверки изменения: YAML и порядок шагов PASS; 22 целевых Python-теста release/signature/execution gate PASS (0.019 s). Это тесты барьера, не 16 Android instrumentation. Новых Gradle/SDK/Android запусков не было.

Подпись: прежний backup найден и проверен на предыдущем этапе; ожидаемый SHA-256 511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c. Его наличие и соответствие именно в текущих Actions Secrets НЕ ПРОВЕРЕНЫ. Доступное подключение не предоставляет list/set secrets, environment administration или workflow dispatch. Отсутствие таких операций не доказывает отсутствия самих secrets. Новый ключ не создавался; backup не загружался в репозиторий.

Следующий необходимый доступ: защищённый GitHub UI Settings → Environments → android-signing. Там должны быть EXISTING_APK_KEYSTORE_B64, EXISTING_APK_STORE_PASSWORD, EXISTING_APK_KEY_ALIAS, EXISTING_APK_KEY_PASSWORD из прежнего backup. Значения не передавать в чат. После настройки нужен ручной запуск android.yml на t042-checkpoint, promote_t042=true. Если workflow не виден до появления файла в main, сначала завершить PR №1, сохранив его историю; в этом этапе main не менялся.

Статусы: оригинальный backup ранее VERIFIED; текущая подпись CI UNVERIFIED; 16 instrumentation NOT_RUN; v2.14.42 NOT_CREATED; Samsung OAuth NOT_RUN. Исходный code75 и production-модули не изменялись. Коммит [skip ci] предотвращает ненужный повтор unit CI.
