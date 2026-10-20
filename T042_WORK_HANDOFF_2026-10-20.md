# ТЗ №042 — контрольная точка после GitHub CI
Дата: 2026-10-20 UTC. Вердикт: BLOCKED для выпуска APK; текущий этап unit CI завершён.

## Где сохранена работа
- Приватный репозиторий: https://github.com/snowdanmail-code/ai-remote-control-gpt
- Рабочая ветка: t042-checkpoint; PR: https://github.com/snowdanmail-code/ai-remote-control-gpt/pull/1
- Проверенный код: 82307a6198ffd5ad4363053ded0c341bb3254462
- Фактический успешный CI: https://github.com/snowdanmail-code/ai-remote-control-gpt/actions/runs/39501499775
- Артефакт: t042-unit-evidence-39501499775, ID 12302970191; содержит журналы и JUnit XML/HTML. SHA-256 ZIP по GitHub: 653304ebcea712e6410893f089c16cd86e4aae0e49473193165bf7832d1c3fe7.
- Полные журналы неудачных попыток и итогового успеха сохранены рядом в evidence/T042-20261020/. Старые отчёты и исходники сохранены.

## Выполнено
1. В новый отдельный приватный репозиторий перенесена существующая контрольная точка Protected-CI с ранее подготовленными изменениями CI-Execution. Профильный snowdanmail-code/snowdanmail-code не изменялся.
2. Добавлен независимый CI без подписания и публикации APK. Выпуск по-прежнему требует прежнего сертификата, Android API36, фактического выполнения обязательных тестов, package/version/ZIP/SHA-256 и upgrade-проверки.
3. По реальным ошибкам Actions исправлена установка SDK: исключён удалённый пакет tools; добавлены Pillow 11.3.0 и Kotlin CLI 1.9.24; heap JVM-компилятора увеличен до 2 ГБ. Последний запуск прошёл.
4. Добавлены семь Android HTTPS mock-тестов на production request через инъекцию соединения: GET200→POST, untrusted TLS, DNS в create, потеря соединения до POST, read timeout после POST, 503/disconnect без replay, неверные redirect/PKCE. Используются синтетические данные и временная TLS-идентичность в памяти, не новый ключ подписи APK. Production TLS и allowlist не изменены.
5. Gate требует все 16 Android instrumentation методов без skips (ранее девять). Их компиляция PASS; runtime НЕ запускался.
6. Сравнение с исходным Protected-CI ZIP: все 67 файлов android/app/src/main/ побайтно сохранены. UI, STOP, игровые модули и ограничения оператора не менялись.

## Результаты текущего этапа
| Проверка | Статус | Доказательство |
|---|---|---|
| Python | PASS | 319 тестов, локально и в успешном GitHub CI |
| Kotlin/JVM scenarios | PASS | tools/run_jvm_tests.sh; 46 перечисленных scenario entrypoints; лог Actions |
| Gradle unit tests | PASS | gradle --no-daemon :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin; BUILD SUCCESSFUL in 3m 3s, 30 tasks |
| Компиляция Android instrumentation | PASS | :app:compileDebugAndroidTestKotlin, включая новые TLS-тесты |
| Android16 connectedDebugAndroidTest | NOT_RUN | секреты прежней подписи не подключены к CI; нет подписанной тестовой пары |
| assembleDebug v2.14.42 | NOT_RUN | барьер выпуска не пройден |
| Новый APK, его SHA-256, aapt/apksigner | NOT_CREATED / NOT_RUN | новый APK не создан и не опубликован |
| Реальный Samsung/Starlink OAuth, refresh, ответ модели | NOT_RUN | только последующее испытание владельцем |

Первые запуски были FAIL по настройке CI (SDK tools, Pillow, JVM heap); это не диагноз Samsung. Не засчитывать их пропущенные Android-шаги как PASS.

## Подпись и ограничения
Прежний ключ найден и криптографически проверен на предыдущем этапе; ожидаемый сертификат:
511567f44b5e527b8b388d0ea2a66ad30725a856ef76670c027b88c25007cd2c.
Резервная копия: AI-Remote-Control-GPT-v2.14.35-debug-signing-backup.zip в личных сохранённых файлах владельца. Ключ, пароли и сам backup не добавлялись в GitHub. Новый ключ подписи APK не создавался.
Package остаётся com.example.airemote.dev, исходники versionCode75. Версия76/v2.14.42 повышается только временным release gate после Android runtime PASS.

Подключение GitHub умеет читать/писать исходники и получать Actions-результаты, но не предоставляет операции создания Environment/Actions Secrets или workflow dispatch. Право admin репозитория подтверждено; это ограничение доступных операций, а не просьба повторно дать доступ к исходникам.

## Следующие задачи
1. Через защищённый GitHub-интерфейс настроить android-signing и EXISTING_APK_KEYSTORE_B64, EXISTING_APK_STORE_PASSWORD, EXISTING_APK_KEY_ALIAS, EXISTING_APK_KEY_PASSWORD из прежнего backup. Значения не передавать в чат/репозиторий/логи.
2. При необходимости завершить PR; вручную запустить защищённый android.yml с promote_t042=true на выбранной ветке. Без необходимости не повторять unit CI.
3. Выполнить Android16/KVM runtime, все 16 обязательных тестов. Новые TLS-тесты пока только скомпилированы. Исправлять только фактически найденные дефекты; не снижать gate.
4. Лишь после PASS собрать v2.14.42 старым ключом, проверить обе подписи, versionCode>75, aapt/apksigner/CRC/SHA-256 и обновление, затем выдать один APK.
5. Samsung OAuth и непустой response.completed проверяет владелец. AUTH_TOKEN_DNS_FAILURE на его сети всё ещё не имеет подтверждённой первопричины.

## Официальный протокол
Сверены текущие официальные страницы:
- https://developers.openai.com/siwc/quickstart
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
Публичный dynamic-client/PKCE/loopback-поток документирован. Право использования плана отдельно зависит от допустимости клиента, granted scopes и аккаунта; частный репозиторий и unit PASS этого не доказывают. Никакой реальный OAuth или плановый доступ здесь не подтверждён.

## Режим продолжения
По распоряжению владельца: анализ и подготовка правок далее в обычном чате; Work преимущественно для Actions, Android16 runtime и финального APK. После этого отчётного checkpoint новых сборок и длительных тестов не запускать без необходимости. Коммит отчёта помечен [skip ci].
