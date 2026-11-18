package com.example.airemote

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Two separate transparent NOT-TOUCHABLE accessibility windows above board
 * and hero cards. Real visual recognition only, no automatic game gestures.
 */
class VisibleCardOverlayController(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var board: TextView? = null
    private var hero: TextView? = null
    private var lastWidth = -1
    private var lastHeight = -1
    private var lastProjection: VisibleCardOverlaySnapshot? = null
    private val density = service.resources.displayMetrics.density
    private fun px(v: Float) = (v * density + 0.5f).toInt()

    private fun makeLabel(): TextView = TextView(service).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        setShadowLayer(1.6f, 0f, 1f, Color.BLACK)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(px(10f), px(5f), px(10f), px(6f))
        maxLines = 5
        background = GradientDrawable().apply {
            setColor(Color.argb(139, 8, 20, 29))
            cornerRadius = px(10f).toFloat()
            setStroke(px(1f), Color.argb(90, 204, 223, 235))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun window(width: Int, height: Int, isBoard: Boolean): WindowManager.LayoutParams {
        val w = (width * 0.78f).toInt()
        val yPosition = (height * if (isBoard) 0.295f else 0.635f).toInt()
        return WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (width * 0.04f).toInt()
            y = yPosition
            title = if (isBoard) "AI: общие карты, отображение" else "AI: мои карты, отображение"
        }
    }

    private fun colored(text: String, confirmed: Boolean): SpannableString {
        val result = SpannableString(text)
        for (i in text.indices) {
            val tint = when (text[i]) {
                '♥', '♦' -> Color.rgb(255, 121, 127)
                '♠', '♣' -> Color.rgb(143, 221, 253)
                else -> null
            }
            if (tint != null) result.setSpan(ForegroundColorSpan(tint),
                i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val label = if (confirmed) "СОВПАЛО НА 2 КАДРАХ" else "ПРЕДВАРИТЕЛЬНО · ПРОВЕРЬ"
        val start = text.indexOf(label)
        if (start >= 0) result.setSpan(
            ForegroundColorSpan(if (confirmed) Color.rgb(138, 249, 176)
                else Color.rgb(255, 222, 132)), start, start + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return result
    }

    fun show(value: VisibleCardOverlaySnapshot, width: Int, height: Int) {
        if (width < 320 || height < 700) { hide(); return }
        try {
            if (board == null || hero == null || lastWidth != width || lastHeight != height) {
                dispose()
                val b = makeLabel()
                val h = makeLabel()
                try {
                    wm.addView(b, window(width, height, true))
                    wm.addView(h, window(width, height, false))
                    board = b
                    hero = h
                    lastWidth = width
                    lastHeight = height
                } catch (_: Exception) {
                    try { wm.removeView(b) } catch (_: Exception) {}
                    try { wm.removeView(h) } catch (_: Exception) {}
                    dispose()
                    return
                }
            }
            board?.visibility = View.VISIBLE
            hero?.visibility = View.VISIBLE
            if (lastProjection == value) return
            board?.text = colored("СТОЛ · " + value.status + "\n" + value.board, value.isConfirmed)
            hero?.text = colored("МОИ КАРТЫ · " + value.status + "\n" + value.hero, value.isConfirmed)
            lastProjection = value
        } catch (_: Exception) { hide() }
    }

    // Keep windows installed but INVISIBLE while the game is lost or STOP is
    // active. Avoid repeated window creation/TYPE_WINDOWS_CHANGED loops.
    fun hide() {
        board?.visibility = View.GONE
        hero?.visibility = View.GONE
        lastProjection = null
    }

    fun dispose() {
        board?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        hero?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        board = null
        hero = null
        lastProjection = null
        lastWidth = -1
        lastHeight = -1
    }
}
