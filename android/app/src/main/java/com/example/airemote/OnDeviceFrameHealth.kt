package com.example.airemote

/** Cheap on-phone screen-signal validation. Not a card reader, OCR, or playing decision.
 *  Signature is the existing 48x96 luminance sample, so no additional frame decoding.
 */
data class OnDeviceFrameHealth(
    val usable: Boolean,
    val reason: String,
    val meanLuma: Int,
    val contrast: Int,
    val darkPercent: Int,
    val observedAtMs: Long
)

object OnDeviceFrameHealthAnalyzer {
    fun assess(sample: ByteArray?, observedAtMs: Long, nowMs: Long,
               stopped: Boolean, capturing: Boolean): OnDeviceFrameHealth {
        fun reject(why: String) = OnDeviceFrameHealth(false, why, 0, 0, 100, observedAtMs)
        if (stopped) return reject("operator_stop")
        if (!capturing) return reject("capture_not_active")
        if (sample == null || sample.size < 100) return reject("sample_missing")
        if (observedAtMs > nowMs + 250L || nowMs - observedAtMs > 2_000L)
            return reject("stale_frame")
        var sum = 0L
        var minimum = 255
        var maximum = 0
        var dark = 0
        sample.forEach {
            val value = it.toInt() and 255
            sum += value
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
            if (value < 18) dark++
        }
        val mean = (sum / sample.size).toInt()
        val contrast = maximum - minimum
        val darkPercent = (dark * 100 / sample.size)
        val reason = when {
            mean < 11 || darkPercent > 97 -> "near_black"
            contrast < 14 -> "low_contrast"
            else -> "signal_visible"
        }
        return OnDeviceFrameHealth(reason == "signal_visible", reason,
            mean, contrast, darkPercent, observedAtMs)
    }
}
