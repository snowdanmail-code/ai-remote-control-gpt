# ТЗ №042 — первый защищённый запуск, 21.10.2026 МСК

Вердикт: BLOCKED_SIGNING_SECRET_FORMAT.

PR №1 успешно слит в main после явного разрешения владельца; merge commit 5a0a99f5762a228a7f242cffa240f0a4b8e3e9a1. История сохранена.

Фактически через GitHub UI подтверждено наличие всех четырёх environment secrets в android-signing. Значения не читались и не выводились. Запущен workflow android.yml на t042-checkpoint, commit 08de1871aecda6421db09264801e32d9e1d436a0, promote_t042=true.

Run: https://github.com/snowdanmail-code/ai-remote-control-gpt/actions/runs/39511368389
Job: 118932929887.

FAIL: Restore only PRIOR signing identity from protected secrets — base64: invalid input. Значение EXISTING_APK_KEYSTORE_B64 не декодируется штатным base64. Это не доказательство неправильного сертификата или отсутствия оригинального backup; до keytool выполнение не дошло. Остальные значения также пока не проверены криптографически.

SDK/Gradle/16 Android instrumentation/сборка/публикация: SKIPPED вследствие отказа preflight, не PASS. Новый APK не создан. Временный файл ключа удалён шагом always, cleanup PASS. Новых ключей и изменения production-кода не было. Реальный OAuth Samsung NOT_RUN.

Необходимое действие: заменить только EXISTING_APK_KEYSTORE_B64 на чистый Base64 байтов debug.keystore из прежнего backup, без имени файла, PowerShell-команды, кавычек или ZIP. Не публиковать значение в чате/репозитории. После исправления повторить только этот защищённый workflow и проверить точный прежний сертификат, затем Android runtime. Настройку прочих secrets перепроверять по фактической ошибке следующего preflight, не менять ключ.
