package com.example.airemote

import android.content.Context

/** Durable fail-closed operator stop gate shared by Activity, capture and gesture services.
 * A missing preference means STOP. Only an explicit tap on the operator's ARM button permits a session.
 */
object OperatorStopStore {
    private const val PREFS = "operator_control_v1"
    private const val STOPPED = "emergency_stopped"

    fun isStopped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(STOPPED, true)

    /** commit() is synchronous: do not race an in-flight session against an asynchronous write. */
    fun stop(context: Context): Boolean {
        // Revoke dispatch authorization before any possible slow strategy work.
        OnDeviceLiveActionControl.stop()
        LiveGamePermissionGate.revoke()
        LiveForegroundProbe.invalidate()
        OnDeviceAutonomousRuntime.stop("Аварийный STOP")
        // Erase unfinished in-hand evidence but NEVER erase learned SQLite data.
        OnDeviceVerifiedHandCompletion.resetTransientEvidence()
        OnDeviceRealFrameRegions.clear()
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(STOPPED, true).commit()
        OnDeviceIncidentRecorder.record(context, IncidentCode.OPERATOR_STOP, IncidentSeverity.INFO)
        return saved
    }

    fun armFromOperator(context: Context): Boolean {
        // Operator consent must persist before any volatile dispatch gate is armed.
        if (OnDeviceLiveActionControl.guard.hasUnconfirmedAction()) return false
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(STOPPED, false).commit()
        if (!saved || !OnDeviceLiveActionControl.guard.armByOperator()) {
            stop(context)
            return false
        }
        return true
    }
}
