package com.example.airemote

/**
 * Read-only provenance diagnostic for captured frames. A stable external Android
 * package is NOT proof that the package is 1win or that a particular poker table
 * is visible. It cannot grant game-action authority.
 *
 * The active-window root is sampled independently by Accessibility; a UI event's
 * package label is deliberately not a substitute for the active root.
 */
data class RootWindowEvidence(
    val packageName: String,
    val sampledAtMs: Long,
    val windowGeneration: Long
)

data class CapturedWindowFrame(
    val captureEpoch: Long,
    val localFrameSequence: Long,
    val capturedAtMs: Long,
    val root: RootWindowEvidence?
)

enum class WindowOriginStatus {
    UNKNOWN, EXTERNAL_PACKAGE_STABLE
}

data class WindowOriginAssessment(
    val status: WindowOriginStatus,
    val observedPackage: String?,
    val captureEpoch: Long,
    val localFrameSequence: Long,
    val reason: String,
    /** Generation of independently sampled active Accessibility root, not a table ID. */
    val windowGeneration: Long? = null
) {
    val safeForLocalDiagnostics: Boolean get() = status == WindowOriginStatus.EXTERNAL_PACKAGE_STABLE
}

class WindowOriginTracker(
    private val ownPackageName: String,
    private val maximumRootAgeMs: Long = 700L,
    private val maximumConfirmationGapMs: Long = 1_800L
) {
    private var previous: CapturedWindowFrame? = null
    private var count = 0
    private val packageFormat = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    @Synchronized fun reset() { previous = null; count = 0 }

    /** Re-check the active root after expensive image processing, before publication. */
    @Synchronized fun stillSameRoot(
        assessment: WindowOriginAssessment,
        initialRoot: RootWindowEvidence?,
        currentRoot: RootWindowEvidence?,
        nowMs: Long
    ): Boolean = assessment.safeForLocalDiagnostics &&
        initialRoot != null && currentRoot != null &&
        initialRoot.packageName == assessment.observedPackage &&
        initialRoot.windowGeneration == assessment.windowGeneration &&
        currentRoot.packageName == initialRoot.packageName &&
        currentRoot.windowGeneration == initialRoot.windowGeneration &&
        currentRoot.sampledAtMs >= initialRoot.sampledAtMs &&
        currentRoot.sampledAtMs <= nowMs &&
        nowMs - currentRoot.sampledAtMs <= maximumRootAgeMs

    @Synchronized fun observe(frame: CapturedWindowFrame): WindowOriginAssessment {
        val root = frame.root
        val valid = root != null &&
            packageFormat.matches(root.packageName) &&
            root.packageName != ownPackageName &&
            root.packageName != "com.android.systemui" &&
            !root.packageName.contains("permissioncontroller", ignoreCase = true) &&
            frame.captureEpoch >= 0L && frame.localFrameSequence > 0L &&
            root.windowGeneration >= 0L && root.sampledAtMs >= 0L &&
            frame.capturedAtMs >= root.sampledAtMs &&
            frame.capturedAtMs - root.sampledAtMs <= maximumRootAgeMs
        if (!valid) {
            reset()
            return WindowOriginAssessment(WindowOriginStatus.UNKNOWN, root?.packageName,
                frame.captureEpoch, frame.localFrameSequence, "Нет свежего достоверного корневого окна")
        }
        val old = previous
        if (old != null && (frame.captureEpoch != old.captureEpoch ||
                frame.localFrameSequence <= old.localFrameSequence ||
                frame.capturedAtMs <= old.capturedAtMs)) {
            reset()
            // Do not use this frame to confirm a new identity after a duplicate/reorder.
            return WindowOriginAssessment(WindowOriginStatus.UNKNOWN, root!!.packageName,
                frame.captureEpoch, frame.localFrameSequence, "Смена захвата или повторный кадр")
        }
        val same = old != null && old.root != null &&
            old.root.packageName == root!!.packageName &&
            old.root.windowGeneration == root.windowGeneration &&
            frame.capturedAtMs - old.capturedAtMs <= maximumConfirmationGapMs
        count = if (same) (count + 1).coerceAtMost(2) else 1
        previous = frame
        return if (count == 2) WindowOriginAssessment(WindowOriginStatus.EXTERNAL_PACKAGE_STABLE,
            root!!.packageName, frame.captureEpoch, frame.localFrameSequence,
            "2 последовательных кадра с одним корневым пакетом (стол НЕ подтверждён)", root.windowGeneration)
        else WindowOriginAssessment(WindowOriginStatus.UNKNOWN, root!!.packageName,
            frame.captureEpoch, frame.localFrameSequence, "Ожидание второго независимого кадра")
    }
}
