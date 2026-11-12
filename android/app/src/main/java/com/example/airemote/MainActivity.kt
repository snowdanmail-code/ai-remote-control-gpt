package com.example.airemote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowInsets
import android.widget.*

/** Phone-only operator console: read-only screen signal, local OCR and persistent STOP. */
class MainActivity : Activity() {
    private val projectionRequest = 501
    private val ocrConsensus = OcrDiagnosticConsensus()
    private val identityGuard = TableHandIdentityGuard()
    private val notificationRequest = 502
    private val incidentExportRequest = 503
    private val layoutImportRequest = 504
    private val spectatorExportRequest = 505
    private val engineeringExportRequest = 506
    private val playerEngineeringExportRequest = 507
    private lateinit var cloudStatus: TextView
    private val cloudControls = mutableListOf<Button>()
    private val cloudHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wasAuthorizing = false
    private val cloudUiTick = object : Runnable {
        override fun run() {
            val authorizing = ChatGptCloudBridge.isAuthorizing()
            cloudControls.forEach { it.isEnabled = !ChatGptCloudBridge.isBusy() }
            if (::cloudStatus.isInitialized && (authorizing || wasAuthorizing))
                cloudStatus.text = ChatGptCloudBridge.statusDetail(this@MainActivity)
            if (::status.isInitialized && status.text.toString().startsWith("Захват запускается")) {
                status.text = if (FrameStore.isCapturing)
                    "Захват активен. Изображений: ${FrameStore.receivedImages}. ${FrameStore.captureError}"
                else if (FrameStore.captureError.isNotBlank())
                    "Захват не запущен: ${FrameStore.captureError}"
                else status.text
            }
            if (::preview.isInitialized && OperatorStopStore.isStopped(this@MainActivity))
                preview.setImageDrawable(null)
            wasAuthorizing = authorizing
            cloudHandler.postDelayed(this, 400)
        }
    }
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var preview: ImageView
    private lateinit var qualitySpinner: Spinner
    private lateinit var arm: Button
    private lateinit var handStateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppVisibilityGate.onActivityVisible()
        // Persistent per-hand learning is local and available without Windows or cloud.
        OnDevicePostHandLearning.initialize(applicationContext)
        OnDeviceAutonomousRuntime.ensureGptOnlyStrategy()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Android 15+/target 35 draw edge-to-edge: keep emergency STOP below status icons.
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left + 12, bars.top + 8, bars.right + 12, bars.bottom + 8)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(12, insets.systemWindowInsetTop + 8, 12, insets.systemWindowInsetBottom + 8)
            }
            insets
        }
        // Permanent laboratory attribution at the top, immediately below system insets.
        // Keep it compact so the emergency STOP remains visible without scrolling.
        val laboratoryCaption = TextView(this).apply {
            text = "Разработано в лаборатории Сноудена"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(64, 73, 88))
            setPadding(0, 0, 0, 4)
        }
        root.addView(laboratoryCaption, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val stop = Button(this).apply {
            text = "СТОП — ПРЕКРАТИТЬ ВСЕ ДЕЙСТВИЯ"
            textSize = 18f
            setBackgroundColor(Color.rgb(160, 25, 25))
            setTextColor(Color.WHITE)
            setOnClickListener { emergencyStop() }
        }
        root.addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        arm = Button(this).apply {
            text = "РАЗРЕШИТЬ НОВУЮ СЕССИЮ"
            setOnClickListener {
                if (OperatorStopStore.armFromOperator(this@MainActivity)) {
                    status.text = "Сессия разрешена. Для наблюдения нажмите «Начать захват»."
                    updateArmStatus()
                } else status.text = "Не удалось сохранить разрешение: STOP остаётся включён"
            }
        }
        // ARM is a recovery function. Keep it in Advanced settings, not on the main screen.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 22)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        // Only essential actions appear on the main screen; advanced tools are retained
        // without creating a forest of diagnostic buttons for the operator.
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        val primaryActions = setOf(
            "ВОЙТИ ЧЕРЕЗ CHATGPT",
            "ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ ОБЛАЧНОГО ИИ",
            "АНАЛИЗ ИГРЫ В CHATGPT — ТЕСТ",
            "НАЧАТЬ ЗАХВАТ ЭКРАНА",
            "ВКЛЮЧИТЬ АВТОНОМНОГО ИИ-ИГРОКА (ПРОВЕРЕННЫЕ ХОДЫ)"
        )
        fun label(text: String) {
            val target = if (text.startsWith("AI Remote Control v")) content else advanced
            target.addView(TextView(this).apply { this.text = text; textSize = 16f })
        }
        fun button(text: String, action: () -> Unit) {
            val target = if (text in primaryActions) content else advanced
            val control = Button(this).apply { this.text = text; setOnClickListener { action() } }
            if (text in setOf("ВОЙТИ ЧЕРЕЗ CHATGPT", "ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ ОБЛАЧНОГО ИИ", "СПРОСИТЬ ОБЛАЧНОГО ИИ — ТЕСТ",
                "АНАЛИЗ ИГРЫ В CHATGPT — ТЕСТ")) cloudControls.add(control)
            target.addView(control)
        }
        advanced.addView(arm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        label("AI Remote Control v${packageManager.getPackageInfo(packageName, 0).versionName} — Android + ChatGPT")
        label("Захват и OCR локально. Windows-сервер и его настройки удалены. " +
            "Основная стратегия — ChatGPT; без подтверждённого ответа автоматический ход запрещён. Подключение ChatGPT проверяется через защищённую вкладку OpenAI. " +
            "Исполнитель Android-касаний доступен после разрешения оператора, проверки игрового состояния и кнопок.")
        label("ОБЛАЧНЫЙ ИИ / CHATGPT")
        label("Вход на официальном сайте OpenAI во вкладке приложения: пароль и 2FA не передаются приложению. " +
            "Разрешение ChatGPT на работу модели запрашивается отдельно. " +
            "Авторизация не даёт доступа к истории переписок и не разрешает игровых касаний.")
        cloudStatus = TextView(this).apply {
            textSize = 16f
            text = ChatGptCloudBridge.connectionLabel(this@MainActivity)
            content.addView(this)
        }
        button("ВОЙТИ ЧЕРЕЗ CHATGPT") {
            cloudStatus.text = "Запускается официальный вход в ChatGPT…"
            ChatGptCloudBridge.startSignIn(this@MainActivity) { message ->
                cloudStatus.text = message
            }
        }
        button("ПРОВЕРИТЬ СЕТЬ OPENAI (БЕЗ ВХОДА)") {
            cloudStatus.text = "Проверяю DNS и HTTPS из приложения…"
            ChatGptCloudBridge.diagnoseOpenAiNetwork { message ->
                runOnUiThread { cloudStatus.text = message }
            }
        }
        button("ПРОВЕРИТЬ ПОДКЛЮЧЕНИЕ ОБЛАЧНОГО ИИ") {
            cloudStatus.text = "Проверяю модель и выполняю тестовый запрос…"
            ChatGptCloudBridge.testInference(this@MainActivity) { message ->
                runOnUiThread { cloudStatus.text = message }
            }
        }
        button("АНАЛИЗ ИГРЫ В CHATGPT — ТЕСТ") {
            // Only the operator can request the latest sanitized context.
            // No live control or automatic inference uses this answer.
            if (OperatorStopStore.isStopped(this@MainActivity)) {
                cloudStatus.text = "STOP: сначала разрешите новую сессию"
            } else if (!ChatGptCloudBridge.connected(this@MainActivity)) {
                cloudStatus.text = "Нет проверенного доступа к модели; проверьте ChatGPT."
            } else {
                val packet = OnDevicePlayerContextBuffer.memory.latest()
                val prompt = packet?.advisorPrompt(SystemClock.elapsedRealtime())
                if (prompt == null || prompt.length > 1600) {
                    cloudStatus.text = "Нет недавнего кадра. Включите захват и откройте стол."
                } else {
                    cloudStatus.text = "Передаю в ИИ структурированный контекст без скриншота…"
                    ChatGptCloudBridge.askPrompt(this@MainActivity, prompt) { message ->
                        runOnUiThread { cloudStatus.text = message }
                    }
                }
            }
        }
        label("Тест общения: введи вопрос ИИ. Не отправляй пароли, личные данные или имена игроков.")
        val cloudQuestion = EditText(this).apply {
            hint = "Например: как оценить pot odds в Texas Hold'em?"
            setText("Объясни в двух предложениях, как вычисляются pot odds в Texas Hold'em.")
            filters = arrayOf(android.text.InputFilter.LengthFilter(1600))
            advanced.addView(this)
        }
        button("СПРОСИТЬ ОБЛАЧНОГО ИИ — ТЕСТ") {
            cloudStatus.text = "Ожидаю ответ облачной модели…"
            ChatGptCloudBridge.askPrompt(this@MainActivity, cloudQuestion.text.toString()) { message ->
                runOnUiThread { cloudStatus.text = message }
            }
        }
        button("СОСТОЯНИЕ CHATGPT") {
            cloudStatus.text = ChatGptCloudBridge.statusDetail(this@MainActivity)
        }
        button("ВЫЙТИ ИЗ CHATGPT") {
            cloudStatus.text = "Отзываю сеанс…"
            ChatGptCloudBridge.signOut(this@MainActivity) { message ->
                runOnUiThread { cloudStatus.text = message }
            }
        }
        label("Облачный ИИ подключается для испытаний и рекомендаций. " +
            "Реальные касания в 1win не разрешаются результатом тестового запроса.")
        status = TextView(this).apply { textSize = 16f; text = "Захват выключен"; content.addView(this) }
        handStateText = TextView(this).apply { textSize = 15f; advanced.addView(this) }
        label("Режим по умолчанию — ИГРОК. Наблюдатель считывает только общие " +
            "карты и никогда не включает автоматические игровые касания.")
        button("ВЫБРАТЬ РЕЖИМ НАБЛЮДАТЕЛЯ (БЕЗ КАСАНИЙ)") {
            selectCaptureRole(CaptureRole.SPECTATOR)
        }
        button("ВЕРНУТЬ РЕЖИМ ИГРОКА (БЕЗ ИЗМЕНЕНИЯ СТРАТЕГИИ)") {
            selectCaptureRole(CaptureRole.PLAYER)
        }
        refreshHandState()
        label("Фоновое OCR обрабатывает кадры захвата локально. Данные диагностики не подтверждают стол/раздачу и не разрешают касаний.")
        label("Калибровка: только из вручную размеченного кадра оригинального приложения. " +
            "Никакие координаты по умолчанию не устанавливаются.")
        button("ЗАГРУЗИТЬ КАЛИБРОВКУ РЕАЛЬНОГО КАДРА (JSON)") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, layoutImportRequest)
        }
        button("ОЧИСТИТЬ КАЛИБРОВКУ") {
            OnDeviceRealFrameRegions.clear()
            status.text = "Калибровка удалена, распознавание мест сброшено"
        }
        button("ДИАГНОСТИКА МЕСТ ИГРОКОВ") {
            status.text = FrameStore.lastSeatVisual?.summary()
                ?: "Нет наблюдений. Для распознавания мест необходима калибровка " +
                   "на размеченных кадрах настоящего интерфейса. Автоматические касания запрещены."
        }
        button("ПОКАЗАТЬ НАБЛЮДЕНИЯ ПО ОБЛАСТЯМ") {
            val report = FrameStore.lastRealFrameRegions
            status.text = report?.let {
                "Области реального кадра: ${it.status}; ${it.reason}. " +
                    "Измерений: ${it.fields.size}. " +
                    "Это НЕ подтверждение действий и НЕ разрешение касаний."
            } ?: "Наблюдений нет; профиль загружен: ${OnDeviceRealFrameRegions.configured()}"
        }
        button("ДИАГНОСТИКА НАЧАЛА И ОКОНЧАНИЯ ХОДА") {
            status.text = FrameStore.lastHeroTurnEpisode?.summary()
                ?: "Нет двух подтверждённых кадров для анализа границ хода."
        }
        button("ПРОВЕРКА РЕАЛЬНЫХ КНОПОК (ДВА КАДРА)") {
            val root = LiveForegroundProbe.snapshot()
            val seen = OnDevicePlayerNativeControls.snapshot()
            val native = if (root != null && seen != null &&
                PlayerNativeControlEvidence.verify(seen, root.packageName,
                    root.windowGeneration, SystemClock.elapsedRealtime(),
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels))
                "Android видит точные элементы: " +
                    seen.buttons.map { it.action.name }.joinToString(", ") +
                    ". Это независимые UI-кнопки, но ещё НЕ подтверждение раздачи."
            else "Android не дал пригодных подтверждённых кнопок из дерева UI. " +
                "Возможно, интерфейс нарисован Canvas/WebGL."
            status.text = native + "\\n" +
                (FrameStore.lastActionStrip?.summary()
                    ?: "Согласованных OCR-кнопок пока нет.") +
                "\\nНикаких касаний без подтверждённого хода."
        }
        button("ПОКАЗАТЬ ФОНОВОЕ OCR") {
            status.text = FrameStore.lastAutomaticOcr?.summary()
                ?: "Сейчас нет актуального OCR (окно оператора сбрасывает игровое изображение). " +
                    "Последняя историческая диагностика, НЕ разрешение на действия: " +
                    OnDeviceAutonomousRuntime.diagnostic()
        }
        button("ИИ-ИГРОК — ДИАГНОСТИКА ГОТОВНОСТИ") {
            if (CaptureObservationMode.isSpectator()) {
                status.text = "Выбран наблюдатель. Для проверки игрока переключите режим " +
                    "только после STOP и остановки захвата."
            } else {
                refreshHandState()
                status.text = PlayerEngineeringReport.brief(this@MainActivity) +
                    "\\n" + PlayerVisionJournal.summary(this@MainActivity)
            }
        }
        button("ИИ-ИГРОК — ПОКАЗАТЬ ГОТОВУЮ КОМБИНАЦИЮ") {
            val now = SystemClock.elapsedRealtime()
            val previewCards = FrameStore.cardConsensus.current(now)
            val evaluation = if (CaptureObservationMode.isSpectator()) null
                else PlayerHandEvaluator.inspect(previewCards)
            status.text = if (evaluation == null) {
                "Нет свежей, дважды согласованной комбинации собственных карт и стола."
            } else {
                "Локальная оценка: ${evaluation.stage}; собственные карты " +
                    "${evaluation.hole.joinToString(" ")}; стол " +
                    "${evaluation.board.joinToString(" ")}; " +
                    "готовая комбинация: ${evaluation.bestMadeHand ?: "до флопа не вычисляется"}. " +
                    "Это НЕ equity и НЕ разрешение на игровые действия."
            }
        }
        button("ИИ-ИГРОК — ЭКСПОРТ ДИАГНОСТИКИ (JSON)") {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "T042-player-readiness.json")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, playerEngineeringExportRequest)
        }
        button("НАБЛЮДАТЕЛЬ — СТАТИСТИКА РАСПОЗНАВАНИЯ") {
            if (!CaptureObservationMode.isSpectator()) {
                status.text = "Статистика только для режима наблюдателя. Режим игрока не изменён."
            } else {
                refreshHandState()
                status.text = SpectatorLearningJournal.summary(this@MainActivity) +
                    "\nОблачный лимит: 10% короткого периода и 10% недели. " +
                    "Автооблако выключено, пока нет подтверждённой телеметрии лимитов. " +
                    "Платные кредиты не используются."
            }
        }
        button("ИНЖЕНЕРНЫЙ АНАЛИЗ — КАЧЕСТВО И ПРИЧИНЫ ОШИБОК") {
            status.text = SpectatorEngineeringReport.brief(this@MainActivity)
        }
        button("ЭКСПОРТ ПРОФЕССИОНАЛЬНОГО ОТЧЁТА T042 (JSON)") {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "T042-engineering-report.json")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, engineeringExportRequest)
        }
        label("Отчёт содержит метрики распознавания, гипотезы причин ошибок, приоритеты P1/P2, " +
            "план проверок, события и идентификатор версии. Без кадров, OCR-текста и имён игроков. " +
            "Автоматической отправки данных пока нет.")
        button("НАБЛЮДАТЕЛЬ — ПОДТВЕРДИТЬ ИЛИ ИСПРАВИТЬ КАРТЫ") {
            reviewSpectatorExample()
        }
        button("НАБЛЮДАТЕЛЬ — РАЗМЕТИТЬ НЕРАСПОЗНАННЫЕ КАРТЫ") {
            labelUnrecognizedSpectatorBoard()
        }
        button("НАБЛЮДАТЕЛЬ — ЭКСПОРТ ПРИМЕРОВ JSON") {
            if (!CaptureObservationMode.isSpectator()) {
                status.text = "Сначала выберите режим наблюдателя при остановленном захвате."
            } else {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "t042-spectator-observations.json")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, spectatorExportRequest)
            }
        }
        label("AUTO: режим виртуальных фишек. Без внешнего подтверждения раздачи доступен только " +
            "ограниченный бесплатный CHECK при точной кнопке Android и свежих картах. " +
            "CALL, FOLD, BET, RAISE НЕ выполняются. " +
            "Полный ИИ-игрок потребует подтверждённого источника игровых данных.")
        button("РАЗРЕШИТЬ КАСАНИЯ ДЛЯ ПОДТВЕРЖДЁННОГО ПРИЛОЖЕНИЯ") {
            requestAutomationAuthorization()
        }
        button("ОТОЗВАТЬ РАЗРЕШЕНИЕ АВТОМАТИЗАЦИИ") {
            OperatorStopStore.stop(this@MainActivity)
            updateArmStatus()
            status.text = "Разрешение отозвано, STOP активирован. Для новой сессии нужен ARM."
        }
        button("ПОКАЗАТЬ РАЗРЕШЕНИЯ И АВТО-РЕЖИМ") {
            val pkg = LiveGamePermissionGate.packageName() ?: "не назначен"
            status.text = "Касания разрешены: ${LiveGamePermissionGate.permitted()}. " +
                "Целевое приложение: $pkg. " +
                "Accessibility: ${LiveForegroundProbe.connected()}. " +
                "Захват: ${FrameStore.isCapturing}. " +
                "AUTO: ${OnDeviceAutonomousRuntime.status().state}. " +
                OnDeviceAutonomousRuntime.readiness(this@MainActivity)
        }
        button("ПРОВЕРИТЬ ГОТОВНОСТЬ АВТО-РЕЖИМА") {
            val state = OnDeviceAutonomousRuntime.status()
            status.text = "AUTO: ${state.state} — ${state.explanation}. " +
                "Реальные пробные CHECK: ${LocalPlayerCheckPilot.status().checkCount}; " +
                "состояние: ${LocalPlayerCheckPilot.status().state}. " +
                "Готовность: ${OnDeviceAutonomousRuntime.readiness(this@MainActivity)}"
        }
        button("ВКЛЮЧИТЬ АВТОНОМНОГО ИИ-ИГРОКА (ПРОВЕРЕННЫЕ ХОДЫ)") {
            if (CaptureObservationMode.isSpectator()) {
                status.text = "Режим НАБЛЮДАТЕЛЯ: AUTO заблокирован; только чтение карт."
                return@button
            }
            val enabled = OnDeviceAutonomousRuntime.startIfAuthorized(this@MainActivity)
            status.text = if (enabled) {
                OnDeviceAutonomousRuntime.status().explanation + " STOP доступен всегда."
            } else "AUTO заблокирован: ${OnDeviceAutonomousRuntime.readiness(this@MainActivity)}"
        }
        label("Диагностика ошибок: локальный журнал не содержит скриншотов, OCR-текст, имена игроков или ключи API.")
        button("ПОКАЗАТЬ СОСТОЯНИЕ ЖУРНАЛА") {
            status.text = OnDeviceIncidentRecorder.shortStatus()
        }
        button("ЭКСПОРТ ОТЧЁТА ОБ ОШИБКАХ (ВРУЧНУЮ)") {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "incident-report-v2.14.41.json")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, incidentExportRequest)
        }
        button("НАСТРОИТЬ СЛУЖБУ STOP И УПРАВЛЕНИЯ") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("НАЧАТЬ ЗАХВАТ ЭКРАНА") { requestCapture() }
        button("ОСТАНОВИТЬ ЗАХВАТ И УДАЛИТЬ КАДР") { stopCapture() }
        button("ПОКАЗАТЬ ПОСЛЕДНИЙ КАДР") { refreshFrame() }
        button("ПРОВЕРИТЬ КАЧЕСТВО КАДРА") { inspectFrameOnPhone() }
        button("РАСПОЗНАТЬ ТЕКСТ НА КАДРЕ") { recognizeTextOnPhone() }
        label("Качество изображения:")
        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Баланс · 960px", "Детали · 1280px", "Точное · 1536px"))
            setSelection((if (CaptureObservationMode.isSpectator())
                CaptureOptions.spectatorProfile else CaptureOptions.profile).coerceIn(0, 2))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?,
                                            position: Int, id: Long) {
                    if (CaptureObservationMode.isSpectator()) CaptureOptions.spectatorProfile = position
                    else CaptureOptions.profile = position
                }
            }
            advanced.addView(this)
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                if (CaptureObservationMode.isSpectator())
                    (resources.displayMetrics.heightPixels * 0.65f).toInt() else 420)
            scaleType = ImageView.ScaleType.FIT_CENTER
            advanced.addView(this)
        }
        result = TextView(this).apply { textSize = 15f; advanced.addView(this) }
        label("Во время наблюдения не открывайте пароли и платёжные данные. " +
            "При блокировке экрана захват может завершиться.")
        val advancedToggle = Button(this).apply {
            text = "НАСТРОЙКИ И ОТЧЁТ ▾"
            setOnClickListener {
                val show = advanced.visibility != android.view.View.VISIBLE
                advanced.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                text = if (show) "СКРЫТЬ НАСТРОЙКИ ▴" else "НАСТРОЙКИ И ОТЧЁТ ▾"
            }
        }
        content.addView(advancedToggle)
        content.addView(advanced)
        updateArmStatus()
    }

    /**
     * One explicit, in-app operator authorization. The app must have seen the
     * intended external package in Accessibility within the last 20 seconds.
     * A virtual-chip label is not itself evidence of platform permission.
     */
    private fun requestAutomationAuthorization() {
        if (CaptureObservationMode.isSpectator()) {
            status.text = "Наблюдатель: запрет игровых касаний. Перейдите в режим игрока только после STOP."
            return
        }
        val seen = LiveForegroundProbe.lastExternalPackage()
        val target = seen?.packageName ?: "не определён"
        val message = "Последнее наблюдаемое приложение: $target.\n\n" +
            "Режим проекта: только виртуальные фишки, без пополнений и вывода.\n" +
            "Разрешайте управление, только если выбран именно 1win и правила " +
            "этой платформы допускают автоматизацию.\n\n" +
            "После включения STOP остаётся доступным. Разрешение не заменяет " +
            "проверку карт, хода, размера ставки и точной кнопки. " +
            "Если здесь не 1win, сначала откройте 1win и вернитесь сюда за 20 секунд."
        AlertDialog.Builder(this)
            .setTitle("Разрешить автоматизацию")
            .setMessage(message)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Разрешаю управление") { _, _ ->
                val recent = LiveForegroundProbe.lastExternalPackage()
                val request = OperatorAutomationAuthRequest(
                    observedPackage = recent?.packageName,
                    observedAtElapsedMs = recent?.atMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    ownPackage = packageName,
                    accessibilityConnected = LiveForegroundProbe.connected(),
                    stopped = OperatorStopStore.isStopped(this),
                    operatorConfirmedVirtualOnly = true,
                    operatorConfirmedPlatformAllowsAutomation = true
                )
                val result = OperatorAutomationAuthorization.assess(request)
                // Prevent a switch between showing the dialog and approving a new target.
                if (recent == null || result != OperatorAutomationAuthStatus.VALID ||
                    recent?.packageName != seen?.packageName ||
                    recent?.windowGeneration != seen?.windowGeneration) {
                    LiveGamePermissionGate.revoke()
                    status.text = "Разрешение не выдано: $result. Откройте игру и повторите."
                } else {
                    LiveGamePermissionGate.grantForVerifiedEnvironment(
                        requireNotNull(recent.packageName), platformExplicitlyPermits = true)
                    status.text = if (LiveGamePermissionGate.permitted())
                        "Транспорт касаний разрешён для ${recent.packageName}. " +
                            "Теперь включите AUTO после запуска захвата. " +
                            "До подтверждения каждого хода касаний не будет."
                    else "Разрешение не удалось включить"
                }
            }.show()
    }

    /** Human feedback is the ONLY source of ground-truth labels. */
    private fun labelUnrecognizedSpectatorBoard() {
        if (!CaptureObservationMode.isSpectator()) {
            status.text = "Ручная разметка доступна только в режиме наблюдателя."
            return
        }
        val now = SystemClock.elapsedRealtime()
        val frame = FrameStore.previewForOperator(now, OperatorStopStore.isStopped(this))
        if (frame == null || now < frame.capturedAtMs ||
            now - frame.capturedAtMs > 60_000L) {
            status.text = "Нет доступного кадра за последнюю минуту. " +
                "Вернитесь к столу, дождитесь общих карт, потом откройте просмотр."
            return
        }
        val entered = EditText(this).apply {
            hint = "Пример: Ah Td 3c"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        AlertDialog.Builder(this)
            .setTitle("Разметить общие карты последнего кадра")
            .setMessage("Введите только карты, которые вы действительно видели на столе: " +
                "3, 4 или 5. В формате Ah Td 3c. " +
                "Изображение не сохраняется, стратегия игрока не меняется.")
            .setView(entered)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить пример") { _, _ ->
                val labels = SpectatorBoardLabels.parse(entered.text.toString())
                status.text = if (labels == null) {
                    "Неверный формат. Нужны 3–5 разных карт: например, Ah Td 3c."
                } else if (SpectatorLearningJournal.operatorLabelUnrecognized(this, frame, labels)) {
                    "Ручная разметка неопознанного кадра сохранена локально. " +
                        "Распознаватель пока не изменён."
                } else "Не удалось сохранить разметку: проверьте режим и захват."
            }.show()
    }

    private fun reviewSpectatorExample() {
        if (!CaptureObservationMode.isSpectator()) {
            status.text = "Подтверждение открытых карт доступно только наблюдателю."
            return
        }
        val sample = FrameStore.spectatorBoardForPreview
        val now = SystemClock.elapsedRealtime()
        if (sample == null || sample.captureEpoch != FrameStore.captureEpoch ||
            now < sample.capturedAtMs || now - sample.capturedAtMs > 60_000L) {
            status.text = "Нет свежего исторического результата за последнюю минуту. " +
                "Откройте стол и дождитесь флопа, затем вернитесь сюда."
            return
        }
        val observed = sample.cards.joinToString(" ")
        AlertDialog.Builder(this)
            .setTitle("Проверка ОБЩИХ карт наблюдателем")
            .setMessage("Последние предполагаемые карты: $observed. " +
                "Сравните с тем, что действительно было открыто на столе. " +
                "Совпадение двух кадров ещё не доказывает правильность.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Да, верно") { _, _ ->
                val ok = SpectatorLearningJournal.operatorFeedback(this, sample, sample.cards)
                status.text = if (ok) "Подтверждение сохранено в локальном журнале." +
                    " Алгоритм игрока не изменён."
                else "Не удалось сохранить подтверждение. Проверьте режим и журнал."
            }
            .setNeutralButton("Исправить") { _, _ ->
                val entered = EditText(this).apply {
                    setSingleLine(true)
                    hint = "Например: Ah Td 3c"
                    setText(observed)
                    filters = arrayOf(android.text.InputFilter.LengthFilter(64))
                }
                AlertDialog.Builder(this)
                    .setTitle("Ввести правильные общие карты")
                    .setMessage("Используйте Ah Td 3c, например. " +
                        "Число карт должно совпадать с исходным результатом: 3, 4 или 5. " +
                        "Исправление хранится локально и не меняет алгоритм.")
                    .setView(entered)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Сохранить исправление") { _, _ ->
                        val corrected = SpectatorBoardLabels.parse(entered.text.toString())
                        status.text = if (corrected == null ||
                            corrected.size != sample.cards.size) {
                            "Не сохранено: формат Ah Td 3c, без повторов, правильное число карт."
                        } else if (SpectatorLearningJournal.operatorFeedback(this, sample, corrected)) {
                            "Исправленный пример сохранён только для последующего анализа."
                        } else "Ошибка сохранения исправленного примера."
                    }.show()
            }.show()
    }

    private fun updateArmStatus() {
        if (::arm.isInitialized) arm.isEnabled = OperatorStopStore.isStopped(this)
    }

    private fun refreshHandState() {
        if (!::handStateText.isInitialized) return
        if (CaptureObservationMode.isSpectator()) {
            val live = FrameStore.spectatorBoard
            val observed = live ?: FrameStore.spectatorBoardForPreview
            handStateText.text = if (observed == null) {
                "РЕЖИМ НАБЛЮДАТЕЛЯ: ждём общие карты флопа, тёрна или ривера. " +
                    "Две карты игрока не требуются. Игровые касания отключены."
            } else {
                "РЕЖИМ НАБЛЮДАТЕЛЯ: " +
                    (if (live == null) "последние исторические карты: " else "общие карты: ") +
                    observed.cards.joinToString(" ") +
                    ". Подтверждено двумя кадрами; не подтверждает раздачу или игру. " +
                    "Никакие игровые касания не разрешены."
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val state = OnDeviceHandState.snapshot(now)
        val cards = FrameStore.cardConsensus.current(now)
        val last = cards ?: FrameStore.lastConfirmedForPreview
        val recognized = if (last == null) {
            "Карты: пока нет 2 согласованных результатов"
        } else {
            val note = if (cards == null) "ПОСЛЕДНИЙ, УЖЕ НЕСВЕЖИЙ РЕЗУЛЬТАТ" else "Локально распознано"
            "$note: ${last.hole.joinToString(" ")} | " +
                "стол: ${if (last.community.isEmpty()) "пусто" else last.community.joinToString(" ")}. " +
                "Источник приложения не подтверждён"
        }
        val probable = OnDeviceStageBridge.lastAssessment
        val probableText = if (probable != null && !probable.stageConfirmed) {
            "Локальный признак фазы: ${probable.probableStage} (НЕ ПОДТВЕРЖДЕНО). " +
                probable.reason
        } else "Локальный признак фазы пока не определён"
        val phase = if (state.trustworthyNow) {
            "Фаза раздачи: ${state.stage} · № ${state.handSerial} (подтверждено)"
        } else {
            "Фаза раздачи: НЕ ПОДТВЕРЖДЕНА. Игровые команды отключены"
        }
        val latestTurn = FrameStore.lastTurnVisual
        val stableTurn = FrameStore.turnConsensus.current(now)
        val turnText = if (stableTurn != null) {
            val words = when (stableTurn.state) {
                TurnDisplayState.READY -> "ПРИЗНАК НАШЕГО ХОДА (яркие кнопки)"
                TurnDisplayState.PREACTION -> "ПРЕДВАРИТЕЛЬНЫЙ ВЫБОР (не ход)"
                TurnDisplayState.BET_SIZING -> "ВЫБОР РАЗМЕРА СТАВКИ"
                TurnDisplayState.WAITING -> "НЕТ АКТИВНЫХ КНОПОК"
                TurnDisplayState.UNKNOWN -> "НЕИЗВЕСТНО"
            }
            "Кнопки: $words. Два кадра совпали, но источник и точные действия НЕ подтверждены."
        } else if (latestTurn != null) {
            "Кнопки: ${latestTurn.reading.state} (не подтверждено / устарело)"
        } else "Кнопки: ожидаем распознавание"
        val readiness = GameReadinessStatus.inspect(GameReadinessInput(
            stopped = OperatorStopStore.isStopped(this),
            captureActive = FrameStore.isCapturing,
            appVerified = false, // no trusted foreground app binding yet
            tableVerified = false, // no verified table identity yet
            cardEpoch = cards?.captureEpoch ?: -1L,
            turnEpoch = stableTurn?.captureEpoch ?: -1L,
            currentEpoch = FrameStore.captureEpoch,
            cardObservedAtMs = cards?.capturedAtMs ?: -1L,
            turnObservedAtMs = stableTurn?.capturedAtMs ?: -1L,
            nowMs = now,
            cardsConfirmed = cards != null,
            handStageConfirmed = state.trustworthyNow,
            ourTurnConfirmed = stableTurn?.state == TurnDisplayState.READY &&
                stableTurn.sourceVerified,
            exactActionLabelsConfirmed = false, // color != precise button label
            strategyConnected = OnDeviceAutonomousRuntime.strategyInstalled() // cloud sign-in is independent from gesture permission
        ))
        val lastExternal = LiveForegroundProbe.lastExternalPackage()?.packageName ?: "не установлен"
        val origin = FrameStore.windowOrigin
        val originText = if (origin?.safeForLocalDiagnostics == true)
            "Активное внешнее окно: ${origin.observedPackage} (2 кадра, ТОЛЬКО диагностика; стол не установлен)"
        else "Источник кадра НЕ определён: ${origin?.reason ?: "требуется служба Accessibility"}"
        handStateText.text = "$recognized\n$phase\n$probableText\n$turnText\n" +
            "$originText\nПроверка готовности: $readiness (не разрешение на касание).\n" +
            "Служба жестов: ${if (LiveForegroundProbe.connected()) "включена" else "не включена"}. " +
            "Последний внешний пакет: $lastExternal (не подтверждён). " +
            "Внешние нажатия заблокированы до проверки условий."
    }

    private fun selectCaptureRole(target: CaptureRole) {
        if (FrameStore.isCapturing || !OnDeviceCaptureLifecycle.gate.mayRequestNewCapture()) {
            status.text = "Сначала остановите захват экрана. Режим нельзя менять посреди сеанса."
            return
        }
        // Explicit transition: revoke any existing action permission BEFORE switching.
        // The operator must separately ARM the next read-only capture.
        OperatorStopStore.stop(this)
        OnDeviceAutonomousRuntime.stop("Смена роли захвата: STOP")
        OnDeviceLiveActionControl.stop()
        LiveGamePermissionGate.revoke()
        FrameStore.clear()
        ocrConsensus.reset()
        identityGuard.reset()
        OnDeviceHandState.reset("Смена режима захвата")
        OnDeviceStageBridge.reset("Смена режима захвата")
        val selected = CaptureObservationMode.select(
            target, FrameStore.isCapturing, OperatorStopStore.isStopped(this))
        updateArmStatus()
        refreshHandState()
        qualitySpinner.setSelection((if (CaptureObservationMode.isSpectator())
            CaptureOptions.spectatorProfile else CaptureOptions.profile).coerceIn(0, 2))
        preview.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (CaptureObservationMode.isSpectator())
                (resources.displayMetrics.heightPixels * 0.65f).toInt() else 420)
        preview.setImageDrawable(null)
        result.text = ""
        status.text = if (selected) {
            if (target == CaptureRole.SPECTATOR)
                "НАБЛЮДАТЕЛЬ выбран. Только общие карты, без касаний. " +
                    "Для захвата нажмите «Разрешить новую сессию», затем «Начать захват экрана»."
            else "ИГРОК выбран: исходный распознаватель и стратегия сохранены. " +
                "STOP активен, для новой сессии потребуется отдельное разрешение."
        } else "Не удалось сменить режим: STOP или захват активен."
    }

    private fun requestCapture() {
        if (!OnDeviceCaptureLifecycle.gate.mayRequestNewCapture()) {
            status.text = "Новый захват недоступен: завершите предыдущую сессию"
            return
        }
        if (OperatorStopStore.isStopped(this)) { status.text = "СТОП: сначала разрешите новую сессию"; return }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
        } else showCaptureDialog()
    }

    private fun showCaptureDialog() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Android Activity result used to keep minimum dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == layoutImportRequest) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val bytes = contentResolver.openInputStream(requireNotNull(data.data))?.use { stream ->
                        // Bounded streaming: compatible with minSdk 29 and hostile file sizes.
                        val out = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            if (out.size() + n > 32 * 1024) error("Слишком большой файл")
                            out.write(chunk, 0, n)
                        }
                        out.toByteArray()
                    } ?: error("Файл недоступен")
                    val profile = OnDeviceLayoutImport.parse(bytes)
                    val applied = OnDeviceRealFrameRegions.configure(profile)
                    status.text = if (applied)
                        "Калибровка загружена: ${profile.layoutKey}, ${profile.frameWidth}×${profile.frameHeight}. " +
                            "Области: ${profile.zones.size}. Кнопки: ${OnDeviceActionStripDiagnostics.configured()}. " +
                            "Калибровка не подтверждает личность, раздачу и не разрешает касаний."
                    else "Калибровка отклонена"
                } catch (_: Exception) {
                    OnDeviceRealFrameRegions.clear()
                    status.text = "Файл калибровки отклонён. Проверьте схему, пакет и координаты."
                }
            }
            return
        }
        if (requestCode == playerEngineeringExportRequest) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val payload = PlayerEngineeringReport.export(this)
                        .toByteArray(Charsets.UTF_8)
                    contentResolver.openOutputStream(requireNotNull(data.data), "w")
                        ?.use { it.write(payload) }
                        ?: error("Не удалось открыть файл")
                    status.text = "Отчёт ИИ-игрока сохранён (${payload.size} байт). " +
                        "Он содержит причины блокировки и статистику, но не скриншоты."
                } catch (_: Exception) {
                    status.text = "Ошибка экспорта отчёта ИИ-игрока. Данные приложения не удалены."
                }
            }
            return
        }
        if (requestCode == engineeringExportRequest) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val bytes = SpectatorEngineeringReport.export(this)
                        .toByteArray(Charsets.UTF_8)
                    contentResolver.openOutputStream(requireNotNull(data.data), "w")
                        ?.use { it.write(bytes) }
                        ?: error("Документ недоступен")
                    status.text = "Инженерный пакет T042 сохранён (${bytes.size} байт). " +
                        "Можно передать JSON для анализа проблем и задач GitHub."
                } catch (_: Exception) {
                    status.text = "Не удалось экспортировать инженерный отчёт. " +
                        "Журналы на устройстве сохранены."
                }
            }
            return
        }
        if (requestCode == spectatorExportRequest) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val payload = SpectatorLearningJournal.exportJson(this)
                        .toByteArray(Charsets.UTF_8)
                    contentResolver.openOutputStream(requireNotNull(data.data), "w")
                        ?.use { it.write(payload) }
                        ?: error("Файл для экспорта не открылся")
                    status.text = "Примеры наблюдателя сохранены (${payload.size} байт). " +
                        "Можно прикрепить JSON в чат для анализа ошибок распознавания."
                } catch (_: Exception) {
                    status.text = "Не удалось экспортировать журнал. Локальные данные сохранены."
                }
            }
            return
        }
        if (requestCode == incidentExportRequest) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val uri = requireNotNull(data.data)
                    val bytes = OnDeviceIncidentRecorder.exportReport(this).toByteArray(Charsets.UTF_8)
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: error("Документ не открыт")
                    status.text = "Отчёт сохранён (${bytes.size} байт). Прикрепите его к чату для доработки."
                } catch (_: Exception) {
                    status.text = "Не удалось сохранить отчёт — журнал остался на телефоне"
                }
            }
            return
        }
        if (requestCode != projectionRequest) return
        if (resultCode != RESULT_OK || data == null) { status.text = "Разрешение захвата отменено"; return }
        if (OperatorStopStore.isStopped(this)) { status.text = "Сессия остановлена"; return }
        // Do NOT stopService() and immediately startForegroundService(): Android can
        // deliver both commands to the SAME service before its old onDestroy().
        if (!OnDeviceCaptureLifecycle.gate.mayRequestNewCapture()) {
            status.text = "Предыдущий захват ещё не завершён. Новый не запускается"
            return
        }
        FrameStore.clear()
        ocrConsensus.reset()
        identityGuard.reset()
        OnDeviceHandState.reset("Новый захват: фаза не установлена")
        OnDeviceStageBridge.reset("Новый захват")
        refreshHandState()
        val request = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        startForegroundService(request)
        status.text = "Захват запускается. Перейдите в разрешённую тестовую среду; вернитесь для просмотра кадра."
    }

    override fun onRequestPermissionsResult(req: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, permissions, results)
        if (req == notificationRequest) showCaptureDialog()
    }

    private fun stopCapture() {
        OnDeviceCaptureLifecycle.gate.requestStop()
        OnDeviceAutonomousRuntime.stop("Оператор остановил захват")
        OnDeviceLiveActionControl.stop()
        stopService(Intent(this, CaptureService::class.java))
        FrameStore.clear()
        ocrConsensus.reset()
        identityGuard.reset()
        OnDeviceHandState.reset("Захват остановлен")
        OnDeviceStageBridge.reset("Захват остановлен")
        refreshHandState()
        preview.setImageDrawable(null)
        result.text = ""
        status.text = "Захват остановлен, последний кадр удалён"
    }

    private fun emergencyStop() {
        OperatorStopStore.stop(this)
        OnDeviceRealFrameRegions.clear()
        OnDeviceCaptureLifecycle.gate.requestStop()
        // Persist STOP before stopping the service; no acknowledgement dialog.
        stopService(Intent(this, CaptureService::class.java))
        FrameStore.clear()
        ocrConsensus.reset()
        identityGuard.reset()
        OnDeviceHandState.reset("Аварийный STOP")
        OnDeviceStageBridge.reset("Аварийный STOP")
        refreshHandState()
        preview.setImageDrawable(null)
        result.text = ""
        status.text = "АВАРИЙНЫЙ STOP — захват и локальная обработка выключены"
        updateArmStatus()
    }

    private fun refreshFrame() {
        refreshHandState()
        val now = SystemClock.elapsedRealtime()
        val frame = FrameStore.previewForOperator(now, OperatorStopStore.isStopped(this))
        if (frame == null) {
            preview.setImageDrawable(null)
            val reason = when {
                OperatorStopStore.isStopped(this) -> "STOP включён: просмотр скрыт"
                !FrameStore.isCapturing -> "Служба захвата остановлена"
                FrameStore.receivedImages == 0L ->
                    "MediaProjection пока не передал изображения"
                FrameStore.latest == null ->
                    "Изображения поступают, но текущий JPEG отклонён проверкой окна/поверхности"
                else -> "Последний JPEG недоступен для просмотра или устарел"
            }
            status.text = "Нет снимка. $reason. Захват: ${FrameStore.isCapturing}; " +
                "получено изображений: ${FrameStore.receivedImages}; " +
                "текущий JPEG: ${FrameStore.latest?.seq ?: "нет"}. ${FrameStore.captureError}"
            return
        }
        preview.setImageBitmap(BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size))
        val age = now - frame.capturedAtMs
        status.text = "Последний снимок — ТОЛЬКО ПРОСМОТР, не текущее игровое состояние. " +
            (if (CaptureObservationMode.isSpectator())
                "НАБЛЮДАТЕЛЬ: JPEG просмотра ${frame.width}×${frame.height}; " +
                    "открытые общие карты читаются отдельно из полного экрана. "
            else "ИГРОК: ML Kit OCR получает JPEG ${frame.width}×${frame.height}; " +
                    "карты читаются прежним алгоритмом 540×1170. ") +
            "JPEG ${frame.jpeg.size / 1024} КиБ · возраст ${age} мс"
    }

    private fun inspectFrameOnPhone() {
        val frame = FrameStore.latest
        val health = FrameStore.localFrameHealth
        val age = frame?.let { SystemClock.elapsedRealtime() - it.capturedAtMs }
        if (health == null || age == null) { status.text = "Свежий кадр отсутствует"; return }
        val usable = health.usable && age in 0L..8000L && !OperatorStopStore.isStopped(this)
        status.text = "${if (usable) "Кадр различим" else "Кадр непригоден"}; " +
            "причина: ${if (age > 8000L) "stale_frame" else health.reason}; " +
            "яркость ${health.meanLuma}, контраст ${health.contrast}, " +
            "тёмных ${health.darkPercent}%, возраст ${age} мс"
    }

    private fun recognizeTextOnPhone() {
        if (OperatorStopStore.isStopped(this)) { status.text = "STOP: распознавание выключено"; return }
        val frame = FrameStore.latest
        val health = FrameStore.localFrameHealth
        if (frame == null || health?.usable != true ||
            SystemClock.elapsedRealtime() - frame.capturedAtMs !in 0L..8000L) {
            status.text = "Нужен свежий различимый кадр"
            return
        }
        val epoch = FrameStore.captureEpoch
        val originAtRequest = FrameStore.windowOrigin
        val ticket = OcrFrameProvenance.ticket(frame, originAtRequest)
        if (OcrFrameProvenance.check(ticket, frame, originAtRequest, epoch,
                SystemClock.elapsedRealtime(), OperatorStopStore.isStopped(this)) != OcrFrameCheck.VALID_DIAGNOSTIC) {
            ocrConsensus.reset()
            identityGuard.reset()
            status.text = "OCR отменено: кадр и поколение окна не совпадают"
            return
        }
        status.text = "Локальное OCR на Android…"
        OnDeviceTextRecognition.inspectStructured(frame.jpeg.copyOf()) { response ->
            runOnUiThread {
                // Race-safe final check: a same-package modal changes windowGeneration.
                val check = OcrFrameProvenance.check(ticket, FrameStore.latest,
                    FrameStore.windowOrigin, FrameStore.captureEpoch,
                    SystemClock.elapsedRealtime(), OperatorStopStore.isStopped(this))
                if (check != OcrFrameCheck.VALID_DIAGNOSTIC) {
                    ocrConsensus.reset()
                    identityGuard.reset()
                    result.text = ""
                    status.text = "OCR отменено: STOP, смена окна или устаревший кадр"
                } else result.text = response.fold(
                    onSuccess = {
                        val agreement = ocrConsensus.observe(ticket, FrameTextEvidence.parse(it.lines))
                        // No independently authenticated table+hand producer is wired here.
                        // Never infer a table ID from the observed Android package, cards or OCR.
                        val binding = identityGuard.inspect(null, ticket, agreement,
                            SystemClock.elapsedRealtime(), OperatorStopStore.isStopped(this))
                        it.summary() + "\n\nПроверка двух кадров: ${agreement.reason}" +
                            "\nПривязка к столу/раздаче: ${binding.reason}"
                    },
                    onFailure = {
                        ocrConsensus.reset()
                        identityGuard.reset()
                        "OCR ошибка: ${it.message}"
                    })
            }
        }
    }

    override fun onStart() {
        // Called before the Activity becomes visible; block new capture publications.
        AppVisibilityGate.onActivityVisible()
        super.onStart()
    }

    override fun onStop() {
        // A new background generation allows read-only capture in another app.
        // This does NOT establish that the other app is the intended poker table.
        AppVisibilityGate.onActivityHidden()
        super.onStop()
    }

    override fun onPause() {
        cloudHandler.removeCallbacks(cloudUiTick)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        cloudHandler.removeCallbacks(cloudUiTick)
        cloudHandler.post(cloudUiTick)
        if (::cloudStatus.isInitialized) cloudStatus.text = ChatGptCloudBridge.statusDetail(this)
        // Never leave a previously displayed external screenshot in the operator UI.
        if (::preview.isInitialized) preview.setImageDrawable(null)
        updateArmStatus()
    }
}
