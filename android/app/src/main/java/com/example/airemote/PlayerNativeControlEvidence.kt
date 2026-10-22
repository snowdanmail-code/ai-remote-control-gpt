package com.example.airemote

/**
 * Accessibility-native button evidence, separately from MediaProjection OCR.
 * A game rendered entirely on Canvas may expose NO accessibility nodes:
 * that is a factual limitation, NOT a reason to infer buttons from color.
 */
data class NativeActionButton(
    val action: LiveAction,
    val exactLabel: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val packageName: String, val windowGeneration: Long, val observedAtMs: Long
)

data class NativeControlSnapshot(
    val packageName: String,
    val windowGeneration: Long,
    val observedAtMs: Long,
    val buttons: List<NativeActionButton>,
    val examinedNodes: Int,
    val hitLimit: Boolean
) {
    val authorizesGameAction: Boolean get() = false
    val supportsReliableNodeBasedActions: Boolean
        get() = buttons.isNotEmpty() && !hitLimit
}

object PlayerNativeControlEvidence {
    const val MAX_NODE_AGE_MS = 800L
    const val MAX_SCANNED_NODES = 180

    fun classifyExactLabel(text: String): LiveAction? {
        val label = text.trim().uppercase().replace(Regex("\\s+"), " ")
        return when (label) {
            "FOLD", "СБРОС", "ПАС" -> LiveAction.FOLD
            "CHECK", "ЧЕК", "ПРОПУСТИТЬ" -> LiveAction.CHECK
            "CALL", "КОЛЛ", "УРАВНЯТЬ" -> LiveAction.CALL
            "BET", "СТАВКА", "ПОСТАВИТЬ" -> LiveAction.BET
            "RAISE", "РЕЙЗ", "ПОВЫСИТЬ" -> LiveAction.RAISE
            else -> null
        }
    }

    /** Read-only UI provenance. Must be corroborated by a verified hand/turn
     * and the existing VerifiedDispatchAdmission before any actual gesture. */
    fun verify(snapshot: NativeControlSnapshot?,
               expectedPackage: String?, generation: Long, nowMs: Long,
               screenWidth: Int, screenHeight: Int): Boolean {
        if (snapshot == null || expectedPackage.isNullOrBlank() ||
            snapshot.packageName != expectedPackage ||
            snapshot.windowGeneration != generation ||
            snapshot.hitLimit ||
            snapshot.examinedNodes !in 1..MAX_SCANNED_NODES ||
            snapshot.observedAtMs > nowMs ||
            nowMs - snapshot.observedAtMs > MAX_NODE_AGE_MS ||
            snapshot.buttons.isEmpty()) return false
        return snapshot.buttons.all { b ->
            b.packageName == expectedPackage &&
                b.windowGeneration == generation &&
                b.observedAtMs == snapshot.observedAtMs &&
                classifyExactLabel(b.exactLabel) == b.action &&
                b.left >= 0 && b.top >= 0 &&
                b.right > b.left && b.bottom > b.top &&
                b.right <= screenWidth && b.bottom <= screenHeight &&
                b.top >= (screenHeight * 0.07).toInt() &&
                b.bottom <= (screenHeight * 0.975).toInt()
        } && snapshot.buttons.map { it.action }.size ==
            snapshot.buttons.map { it.action }.toSet().size
    }
}

/** Latest full native-node snapshot is per-window, volatile, not persistent. */
object OnDevicePlayerNativeControls {
    @Volatile private var latest: NativeControlSnapshot? = null
    fun current(nowMs: Long, targetPackage: String?, generation: Long,
                screenWidth: Int, screenHeight: Int): NativeControlSnapshot? {
        val s = latest
        return s?.takeIf { PlayerNativeControlEvidence.verify(it, targetPackage,
            generation, nowMs, screenWidth, screenHeight) }
    }
    fun snapshot(): NativeControlSnapshot? = latest
    @Synchronized fun publish(s: NativeControlSnapshot) { latest = s }
    @Synchronized fun clear() { latest = null }
}
