package com.example.airemote

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * T051 — real OS Accessibility tap test, but ONLY to this app's CHECK button.
 * The GPT reply and hand are deliberately synthetic and clearly labelled.
 * This Activity never reads 1win, never places a wager, and has no live
 * authority over the actual player pipeline.
 */
class T051ActionLabActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private lateinit var checkTarget: Button
    private lateinit var status: TextView

    private val refresh = object : Runnable {
        override fun run() {
            val machine = T051LabSession.machine
            machine.tick(SystemClock.elapsedRealtime())
            if (::output.isInitialized) {
                val report = machine.report()
                val text = report.events.joinToString("\n") +
                    "\n\nСТАТУС: ${report.phase}\nПРИЧИНА: ${report.reason}"
                if (output.text.toString() != text) output.text = text
            }
            if (::status.isInitialized) {
                val ready = OperatorStopStore.isStopped(this@T051ActionLabActivity) &&
                    !FrameStore.isCapturing
                status.text = if (ready)
                    "ТЕСТОВЫЙ РЕЖИМ: STOP активен, захват экрана выключен. " +
                        "Все касания только по кнопке этого экрана."
                else "БЛОКИРОВКА: включите STOP и остановите захват перед испытаниями."
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 8, 18, 12)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout())
                view.setPadding(18 + bars.left, 8 + bars.top, 18 + bars.right,
                    12 + bars.bottom)
            }
            insets
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        scroll.requestApplyInsets()
        fun heading(message: String, size: Float) {
            root.addView(TextView(this).apply {
                text = message
                textSize = size
                setTextColor(Color.rgb(20, 45, 71))
                setPadding(0, 6, 0, 8)
            })
        }
        fun button(text: String, click: () -> Unit): Button {
            val b = Button(this).apply { this.text = text; setOnClickListener { click() } }
            root.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return b
        }
        heading("ЛАБОРАТОРИЯ T051 · тест действий ИИ", 19f)
        heading("Это отдельный тестовый экран. Карты и ответ модели заданы сценарием; " +
            "реальный Android-жест будет направлен только на кнопку ниже. " +
            "Никаких ставок, 1win и сетевых запросов GPT.", 14f)
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(170, 45, 25))
            root.addView(this)
        }
        button("ТЕСТ 1 — допустимый CHECK + НАСТОЯЩЕЕ Android-касание") {
            startScenario(T051LabScenario.CHECK_OK)
        }
        button("ТЕСТ 2 — запретить недопустимый RAISE") {
            startScenario(T051LabScenario.RAISE_REJECTED)
        }
        button("ТЕСТ 3 — Android-жест без подтверждения приложения") {
            startScenario(T051LabScenario.MISSING_RECEIPT)
        }
        checkTarget = Button(this).apply {
            text = "CHECK · ЛАБОРАТОРНАЯ КНОПКА"
            textSize = 18f
            setBackgroundColor(Color.rgb(30, 115, 79))
            setTextColor(Color.WHITE)
            minHeight = (72f * resources.displayMetrics.density).toInt()
            setOnClickListener {
                val receipt = T051LabSession.buttonPressed(applicationContext)
                if (!receipt && T051LabSession.machine.currentPhase() == T051LabPhase.IDLE)
                    status.text = "Тест не запущен: ручное нажатие не засчитывается."
            }
        }
        root.addView(checkTarget, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        button("STOP — ПРЕКРАТИТЬ ТЕСТ И ЗАПРЕТИТЬ ДЕЙСТВИЯ") {
            OperatorStopStore.stop(applicationContext)
            T051LabSession.machine.stop()
        }
        button("НАЗАД К ЖУРНАЛУ") { finish() }
        output = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(40, 46, 54))
            setPadding(6, 12, 6, 20)
            text = "Нажмите тест. События будут появляться здесь."
            root.addView(this)
        }
    }

    private fun startScenario(scenario: T051LabScenario) {
        if (!OperatorStopStore.isStopped(this)) {
            status.text = "ЗАПРЕЩЕНО: сначала включите STOP в главном окне."
            return
        }
        if (FrameStore.isCapturing) {
            status.text = "ЗАПРЕЩЕНО: сначала остановите захват экрана."
            return
        }
        val machine = T051LabSession.machine
        val now = SystemClock.elapsedRealtime()
        val ticket = machine.start(scenario, now)
        val decision = T051LabContractFixture.evaluate(now, scenario)
        if (!machine.review(ticket, decision.accepted, decision.reason)) return
        // Only our own onscreen test Button is an allowed destination.
        val rect = Rect()
        if (!checkTarget.isShown || !checkTarget.getGlobalVisibleRect(rect) ||
            !T051LabSession.register(ticket, rect)) {
            machine.reject(ticket, "INVALID_TARGET")
            return
        }
        val service = LiveForegroundProbe.activeService()
        if (service == null) {
            machine.reject(ticket, "NO_ACCESSIBILITY")
            return
        }
        if (!machine.dispatched(ticket)) return
        // System-level Android dispatchGesture; NOT View.performClick().
        service.dispatchT051LabGesture(ticket, rect.centerX(), rect.centerY()) { completed ->
            machine.gestureResult(ticket, completed)
        }
    }

    override fun onResume() {
        super.onResume()
        T051LabSession.onVisible()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        T051LabSession.onHidden()
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
