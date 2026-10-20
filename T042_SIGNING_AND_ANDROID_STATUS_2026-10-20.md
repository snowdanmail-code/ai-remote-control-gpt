# ТЗ №042 — восстановленная подпись и статус Android-проверок

**Дата:** 20.10.2026, Europe/Moscow. **Вердикт: BLOCKED.**

Прежний закрытый ключ **найден, извлечён и независимо проверен**. Предположение старого отчёта об окончательной утрате неверно и больше не является блокировкой. Защищённый CI подготовлен в исходниках, но **не развёрнут и не запускался**: неизвестен адрес репозитория проекта. Android SDK/эмулятор после очистки среды отсутствуют, а попытка загрузить Gradle заблокирована сетевой политикой. **APK v2.14.42 не создан и не опубликован. Реальный OAuth Samsung не проверен.**

## 1. Какая база использована

Восстановлены существующие файлы проекта, без создания нового Android-проекта:

- `AI-Remote-Control-GPT-T042-Protected-CI-Checkpoint.zip`, SHA256 `5fd6c4acff511259b4305cf74def4bd852b0e5dd30bb5974037a5065e34ce8cd` — актуальная база изменений.
- Предыдущий `AI-Remote-Control-GPT-T042-Source-Checkpoint.zip`, SHA256 `a7523cac7c066b7427b22be0dea6a4fc08a5a1ababefc61246aea0c9e3fffbde`.
- Настоящий `AI-Remote-Control-GPT-v2.14.41.apk`, SHA256 `2c319dc7a30a4cd34583aeab5fe9691c4795f67ee3e1da7d11705cbafd4697f5`.
- Полностью прочитан `T042_SIGNING_RECOVERY_REPORT_2026-10-19.md`. Его вывод о найденном ключе перепроверен, а не просто принят как новый PASS.
- Использован существующий частный резервный архив v2.14.35. В источники/patch/evidence он не добавлен; новую копию ключа для выдачи не создавали.

CRC восстановленных архивов PASS. Старые файлы в Library не изменялись и не удалялись. Текущие исходники остаются versionCode75/v2.14.41; promotion в v2.14.42 не выполнялся.

## 2. Фактическая проверка прежней signing identity

Источник — существующий архив `/AI-Remote-Control-GPT-v2.14.35-debug-signing-backup.zip` в Library пользователя.

1. CRC резервной копии PASS. Извлечён ровно прежний keystore (2618 байт) в отдельный временный каталог с правами 0700; файл — 0600.
2. `keytool -list -v` с параметрами из частного README архива: `PrivateKeyEntry` PASS. Значения пароля и alias в отчёт/журналы не выводились.
3. SHA256 сертификата: `511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c`. Совпадает с требуемой прежней подписью.
4. Существующий private key реально подписал безличные синтетические данные; проверка подписи публичным ключом сертификата PASS.
5. Синтетический JAR подписан тем же ключом через `java -m jdk.jartool/sun.security.tools.jarsigner.Main`; verify PASS. В этой среде модуль jdk.jartool присутствует, хотя отдельного launcher `jarsigner` нет. Это не новый APK и не доказательство Android runtime/OAuth.
6. SHA256 настоящего v2.14.41 совпал. Независимый offline Python-verifier прочитал APK Signature Scheme v2, проверил подпись RSA/SHA256, публичный ключ сертификата и chunked content digest; отпечаток равен сертификату keystore. Негативная проверка: изменение байта APK отвергается.
7. В первой версии вспомогательного verifier сработала слишком строгая проверка отсутствия дополнительных подписанных нулевых байтов. В старом APK имеется подписанное 4-байтное нулевое расширение; verifier скорректирован, подпись проверяется над всем signed data без удаления этих байтов. Первоначальный FAILED и финальный PASS сохранены в evidence. Это ошибка вспомогательного parser, не диагноз повреждённого APK.
8. **apksigner/aapt в текущей среде отсутствуют и не запускались.** Независимый verifier не подменяет обязательный SDK release gate. Предыдущий SDK PASS относится к прежнему отчёту, а не новой проверке.
9. Временный извлечённый ключ и синтетический JAR удалены. Удалена только временная материализованная копия backup ZIP; исходный частный архив Library сохранён. Новый ключ не генерировался, новый APK не подписывался.

Прежний сертификат можно использовать для будущего совместимого обновления. Это не означает, что удалённый pipeline уже восстановлен или что OAuth работает.

## 3. Что установлено о прежнем CI

GitHub connector снова показывает только private `snowdanmail-code/snowdanmail-code`. Его принадлежность Android-проекту не установлена. Репозиторий не изменялся; никаких secrets, workflow или APK туда не отправляли.

В доступных исходниках нет конкретного адреса проектного репозитория/Actions run. `private238` — прежний локальный каталог, а не доказанный адрес CI. После очистки он и runtime238 отсутствуют. Наличие `.github/workflows/android.yml` и имён secrets — подготовка pipeline, а не история его исполнения. Достоверной ссылки на прежний Actions run нет.

**Точный недостающий несекретный указатель:** `owner/repository` проекта либо URL существующего Actions/CI run, где ранее выполнялась его подпись. Путь резервной копии больше спрашивать не нужно: архив найден. Ключ/пароль/token/2FA в чат не нужны.

## 4. Актуальный workflow и внесённые изменения

Protected-CI checkpoint УЖЕ содержал manual-only dispatch, Environment `android-signing`, проверку прежнего PrivateKeyEntry/сертификата, API36/KVM instrumentation gate, baseline v2.14.41 и `if: always()` удаления временного keystore. Замечания о workflow из оригинального InApp ZIP к этой ветке механически не применялись.

Дополнительно подготовлено:

- `.github/workflows/android.yml`: до установки собираются target и instrumentation APK с существующим ключом; обе подписи проверяются через `aapt`/`apksigner`. Новый default debug signer тестового APK запрещён так же, как нового target APK.
- `tools/verify_t042_test_apks.py`: проверяет оба signer SHA256, пакеты `.dev` / `.dev.test` и versionCode75 проверяемого checkpoint. Любое несовпадение блокирует дальнейший этап. Это не генератор ключей и не обход Android signature checks.
- `tools/verify_t042_android_results.py`: Gradle exit0 недостаточен. Обязательны все 9 конкретных Android-методов, отсутствие failure/error/skipped/notrun и непустые XML. Только после успешной проверки разрешена staging версии76. Никакие новые Android PASS из синтетических XML-тестов не выводятся.
- Бюджет загрузки API36 увеличен до **900 секунд**. `tools/monitor_t042_boot.py` пишет раз в 15 секунд только elapsed, boot completion, boot animation и API level — без UI, account data и logcat. Монитор ограничен по времени и останавливается при cleanup.
- Старые connected XML в временной CI build-директории удаляются перед прогоном, чтобы gate не принял предыдущий отчёт за новый.
- В конце сохраняются только явно перечисленные XML/JSON/boot-progress evidence на success/failure. APK не попадает в диагностический artifact. Ключ по-прежнему удаляется в `always()`, также завершается монитор.
- `AndroidOAuthBrowserTest.androidKeystoreEncryptsAndRotatesSyntheticRefreshRecord`: исправлен устаревший fixture. `saveRecord` в Т042 сохраняет регистрацию и требует `client_id`/`sub`, а старый fixture их не содержал. Добавлены синтетические identity-поля и очистка созданной тестовой registration. Это исправление тестовых данных, не ослабление storage-проверок. **Android-компиляция и выполнение изменения пока NOT_RUN.**
- Добавлены 16 Python-тестов signer pair и Android XML gate: проверяют отказ при новом ключе target/test, неверных пакетах/версии, пустом/отсутствующем/пропущенном/неуспешном или неполном Android-отчёте.

Оригинальные 67 файлов `android/app/src/main/` побайтно не изменились относительно Protected-CI checkpoint. Минимальный UI, STOP, OCR, AccessibilityService, алгоритмы и operator/action restrictions сохранены. Игровых действий не выполнялось.

## 5. Попытка расширенной Android-проверки: реальный блокирующий фактор

Предыдущий boot budget180 не доказывал невозможность загрузки. Эта сессия не повторяет такой вывод.

После автоматической очистки нет прежних SDK, Gradle, ADB, emulator, Kotlin CLI и javac. В стандартных местах и относящихся к проекту каталогах runtime238/private238/airemote042-ci инструменты не найдены; точных резервных архивов toolchain в Library не найдено. Нерелевантные пользовательские данные/ключи не сканировались.

Попытка восстановить Gradle из официального distribution URL завершилась сообщением среды: `Network access to https://services.gradle.org:443 was blocked by policy.` Это отказ сетевой политики Work, не сетевой дефект Samsung, не Gradle compilation error и не отказ OAuth. Загрузка SDK с Google после этого не выдаётся за выполненную или явно отклонённую: она не запускалась. Ограничение не обходили через другой DNS, proxy, mirror или туннель.

Локальный bounded boot monitor запущен с бюджетом900 и остановился на preflight: `NOT_STARTED_ADB_MISSING`. **Расширенный boot не был начат**, 900 секунд ожидания не выдаются за реально истекшие. Нового Android boot timeout или Android PASS нет. В текущем CPU нет VMX/SVM, `/dev/kvm` отсутствует; software boot требует восстановленных инструментов. Увеличенный бюджет уже подготовлен для доступного SDK/CI runner; платные ресурсы не запускались.

Для запуска локально нужен разрешённый доступ к toolchain/dependency downloads либо заранее подготовленная Android-среда. Для GitHub CI нужен адрес проектного репозитория и доступ к его protected environment. Эти блокировки невозможно устранить созданием другого signing key.

## 6. Результаты именно этой сессии

| Проверка | Статус | Предел подтверждённого результата |
|---|---|---|
| CRC входных архивов / SHA v2.14.41 | PASS | Прежние реальные файлы |
| keytool PrivateKeyEntry и certificate SHA256 | PASS | Реальный существующий ключ |
| Private key sign/verify и synthetic JAR | PASS | Не APK и не авторизация |
| Независимая Python APK v2 crypto/content проверка | PASS | Старый v2.14.41; не SDK apksigner gate |
| Негативная проверка повреждённого APK | PASS | Изменённый байт отвергнут |
| Python baseline Protected-CI | PASS | 303/303 |
| Python после изменений CI gate | PASS | 319/319, 0 failures/errors/skips |
| YAML и порядок блокирующих шагов | PASS | Статическая проверка, не Actions run |
| Защита от нового signer test APK | PASS на metadata unit tests | Реальные APK ещё не собраны |
| Android XML proof gate | PASS на synthetic unit tests | Не доказывает исполнения Android |
| :app:testDebugUnitTest | NOT_RUN | Gradle/SDK отсутствуют, download blocked |
| :app:assembleDebug | NOT_RUN | Новый APK не создавался |
| :app:compileDebugAndroidTestKotlin | NOT_RUN | Fixture edit пока не скомпилирован |
| :app:connectedDebugAndroidTest | NOT_RUN | Нет Android runtime/ADB |
| Kotlin/JVM scenarios | NOT_RUN | Kotlin CLI отсутствует |
| Android16 расширенный boot | NOT_STARTED | Preflight ADB missing; не timeout900 |
| Настройка/запуск удалённого Actions | NOT_RUN | Нет доказанного адреса проектного repo |
| Реальный OAuth / refresh / revocation / response.completed Samsung | NOT_RUN | Пользовательские credentials не использовались |
| APK v2.14.42 | NOT_CREATED / NOT_PUBLISHED | VersionCode checkpoint остаётся75 |

148 JUnit / 297 Python / 46 JVM из первоначального Т042 — исторические результаты. Они не переименованы в новые PASS этого запуска.

## 7. Следующий конкретный шаг и статус выпуска

Нужна ссылка на **репозиторий проекта или прежний Actions/CI run**, без секретов. После установления правильного места можно переносить подготовленный workflow и настраивать protected secrets из уже найденной резервной копии. Служебный профильный репозиторий использовать молча нельзя. До SDK/Android gate новый APK не выпускается.

Тестовые APK должны подписываться существующим ключом вместе с target; если Android Gradle Plugin подставит другой debug key, новый gate остановит установку/публикацию. Фактическое совпадение signer двух новых APK пока не подтверждено — сборки не было.

После прохождения Android/подписи/совместимости может появиться один кандидат для device test. Даже это не будет доказательством входа на Samsung с 2FA: нужны отдельные реальные token exchange, сохранение сеанса и непустой завершённый ответ модели.

**BLOCKED: SIGNING_IDENTITY_RECOVERED; PROJECT_CI_POINTER_UNKNOWN; ANDROID_TOOLCHAIN_UNAVAILABLE; DOWNLOAD_BLOCKED_BY_ENVIRONMENT_POLICY.**

## Материалы и воспроизводимость

В evidence находятся безопасные JSON проверок, реальные Python-журналы baseline/final, boot-preflight JSONL, файл исходного отчёта другой ветки, независимый verifier старого APK и этот отчёт. Ключи, backup ZIP, пароли и синтетический подписанный JAR не включены.

В source checkpoint сохраняются все прежние исходные файлы, новая CI-конфигурация, gate scripts и fixture fix; patch строится относительно точного Protected-CI ZIP. Checksum-файл относится только к действительно созданным deliverables.

Параметр `emulator-boot-timeout` сверен с исходником action: https://github.com/ReactiveCircus/android-emulator-runner/blob/main/action.yml . У action документировано значение в секундах; изменение до900 — конфигурация будущего прогона, а не утверждение о выполнении.
