package com.example.airemote

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Optional, read-only mini-console over the game during Samsung diagnostics.
 * Uses the existing Accessibility overlay permission, never requests a new
 * overlay permission, never intercepts taps, and never controls the game.
 */
class OperatorLiveOverlayController(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var label: TextView? = null
    private var previous: String? = null

    fun show(text: String, width: Int, height: Int) {
        if (width < 320 || height < 700) { hide(); return }
        try {
            if (label == null) {
                val density = service.resources.displayMetrics.density
                val view = TextView(service).apply {
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setPadding((8 * density).toInt(), (5 * density).toInt(),
                        (8 * density).toInt(), (5 * density).toInt())
                    maxLines = 3
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.argb(176, 12, 20, 34))
                        cornerRadius = 8f * density
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    contentDescription = null
                }
                val params = WindowManager.LayoutParams(
                    (width * 0.70f).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (width * 0.03f).toInt()
                    y = (height * 0.09f).toInt()
                    title = "AI live diagnostics (read-only)"
                }
                wm.addView(view, params)
                label = view
            }
            label?.visibility = View.VISIBLE
            if (previous != text) {
                label?.text = text
                previous = text
            }
        } catch (_: Exception) { dispose() }
    }

    fun hide() {
        label?.visibility = View.GONE
        previous = null
    }

    fun dispose() {
        label?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        label = null
        previous = null
    }
}
