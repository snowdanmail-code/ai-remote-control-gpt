package com.example.airemote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.io.ByteArrayOutputStream

/** Read-only on-device capture. No HTTP client, server configuration, or outside-app gestures. */
class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.example.airemote.START_CAPTURE"
        const val ACTION_STOP = "com.example.airemote.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "resultData"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    @Volatile private var closing = false
    private var lastCheck = 0L
    private var lastEncode = 0L
    private var lastCardInspectionAt = 0L
    private var cardInspectionSeq = 0L
    private var windowFrameSeq = 0L
    private lateinit var windowTracker: WindowOriginTracker
    private fun windowStillMatches(origin: WindowOriginAssessment,
                                   initial: RootWindowEvidence?): Boolean {
        val current = LiveForegroundProbe.snapshot()?.let {
            RootWindowEvidence(it.packageName, it.atMs, it.windowGeneration)
        }
        return windowTracker.stillSameRoot(
            origin, initial, current, SystemClock.elapsedRealtime())
    }
    private var lastSignature: ByteArray? = null
    private var lastProfile = -1
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (closing) return
            OnDeviceCaptureLifecycle.gate.requestStop()
            OnDeviceAutonomousRuntime.stop("MediaProjection остановлен")
            OnDeviceLiveActionControl.stop()
            FrameStore.clear()
            OnDeviceAutomaticOcr.reset()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowTracker = WindowOriginTracker(applicationContext.packageName)
        OnDeviceVerifiedHandCompletion.initialize(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Notification STOP is a durable stop, not merely a pause.
                OperatorStopStore.stop(this)
                OnDeviceCaptureLifecycle.gate.requestStop()
                OnDeviceAutonomousRuntime.stop("STOP захвата")
                OnDeviceLiveActionControl.stop()
                FrameStore.clear() // Clear immediately, not only in onDestroy().
                OnDeviceAutomaticOcr.reset()
                stopSelf()
            }
            ACTION_START -> {
                if (OperatorStopStore.isStopped(this)) {
                    FrameStore.captureError = "STOP: сначала разрешите новую сессию"
                    // startForegroundService must not remain a zombie when STOP won.
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                // A STOPPING/ACTIVE service may not swallow a new consent token.
                // Never try to restart a previous MediaProjection instance.
                if (!OnDeviceCaptureLifecycle.gate.beginStart()) {
                    FrameStore.captureError = "Новый захват запрещён: предыдущий сеанс ещё активен или завершается"
                    return START_NOT_STICKY
                }
                startCapture(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val requestedEpoch = FrameStore.captureEpoch
        try {
            showNotification()
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_DATA)
            require(code == Activity.RESULT_OK && data != null) { "Захват не разрешён" }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            requireNotNull(projection)
            projection!!.registerCallback(callback, Handler(Looper.getMainLooper()))
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            require(width > 0 && height > 0)
            worker = HandlerThread("OnPhoneCapture").apply { start() }
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ images ->
                val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    FrameStore.noteImage(requestedEpoch)
                    val now = SystemClock.elapsedRealtime()
                    val visibilityTicket = AppVisibilityGate.acquireTicket()
                    val captureEpoch = FrameStore.captureEpoch
                    if (visibilityTicket == null) {
                        FrameStore.captureError = "Кадры поступают; экран оператора видим — игровая обработка приостановлена"
                    }
                    if (!closing && visibilityTicket != null &&
                        !OperatorStopStore.isStopped(applicationContext) && now - lastCheck >= 300L) {
                        lastCheck = now
                        windowFrameSeq++
                        val rootEvidence = LiveForegroundProbe.snapshot()?.let {
                            RootWindowEvidence(it.packageName, it.atMs, it.windowGeneration)
                        }
                        val origin = windowTracker.observe(CapturedWindowFrame(
                            captureEpoch, windowFrameSeq, now, rootEvidence))
                        // Publication of the new window is ordered with STOP/clear().
                        // Never reintroduce a window from a destroyed capture epoch.
                        if (!FrameStore.observeWindow(captureEpoch, origin))
                            return@setOnImageAvailableListener
                        if (!origin.safeForLocalDiagnostics) {
                            FrameStore.captureError = if (!LiveForegroundProbe.connected())
                                "Кадры поступают; служба Accessibility не подключена"
                            else "Кадры поступают; ожидаем стабильное внешнее окно"
                            // observeWindow has already erased stale visual evidence.
                            // The new external window must start with a fresh visual signature.
                            lastSignature = null
                            lastEncode = 0L
                            lastProfile = -1
                            lastCardInspectionAt = 0L
                            OnDeviceStageBridge.reset("Смена / неопределённость корневого окна")
                        }
                        val plane = image.planes[0]
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                        try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            val screen = Bitmap.createBitmap(padded, 0, 0, width, height)
                            try {
                                // Real 1win recordings show that history/statistics drawers
                                // leave part of the table visible behind a dark-blue panel.
                                // Block all read-only evidence until the whole observed
                                // surface looks like the measured layout again. A table-like
                                // color pattern is NEVER table identity or tap permission.
                                val surface = RecordedSurfaceGate.inspect(width, height) { x, y ->
                                    screen.getPixel(x, y)
                                }
                                if (surface.status != RecordedSurfaceStatus.TABLE_LIKE) {
                                    FrameStore.captureError = "Кадры поступают; экран не прошёл фильтр игрового стола"
                                    val invalidated = FrameStore.invalidateOccludedSurface(captureEpoch, origin)
                                    if (invalidated) {
                                        OnDeviceAutomaticOcr.reset()
                                        OnDeviceStageBridge.reset("Panel or unknown recorded game surface")
                                    }
                                    lastSignature = null
                                    lastEncode = 0L
                                    lastProfile = -1
                                    lastCardInspectionAt = 0L
                                    return@setOnImageAvailableListener
                                }
                                val sig = FrameDifference.signature(screen)
                                val frameHealth = OnDeviceFrameHealthAnalyzer.assess(
                                    sig, now, now, OperatorStopStore.isStopped(applicationContext), true)
                                // Independent local card recognition, not tied to JPEG upload
                                // or the 8-second forced JPEG refresh. No network requests.
                                if (origin.safeForLocalDiagnostics && frameHealth.usable &&
                                    now - lastCardInspectionAt >= 1_350L &&
                                    !OperatorStopStore.isStopped(applicationContext) &&
                                    AppVisibilityGate.canPublish(visibilityTicket) &&
                                    captureEpoch == FrameStore.captureEpoch) {
                                    lastCardInspectionAt = now
                                    if (CaptureObservationMode.isSpectator()) {
                                        // Read ONLY exposed community cards while spectating.
                                        // Player-card consensus, stage, hero turn and gestures
                                        // are intentionally NOT called in this branch.
                                        val board = OnDeviceCardReader.inspectSpectator(screen)
                                        var publishedSequence = -1L
                                        var publishedStable: StableSpectatorBoard? = null
                                        val published = FrameStore.commitIfCurrent(captureEpoch, origin, {
                                            CaptureObservationMode.isSpectator() && !closing &&
                                                !OperatorStopStore.isStopped(applicationContext) &&
                                                AppVisibilityGate.canPublish(visibilityTicket) &&
                                                windowStillMatches(origin, rootEvidence)
                                        }) {
                                            cardInspectionSeq += 1L
                                            val stable = FrameStore.spectatorConsensus.accept(
                                                board, captureEpoch, cardInspectionSeq, now,
                                                SystemClock.elapsedRealtime(), origin.observedPackage)
                                            FrameStore.spectatorBoard = stable
                                            if (stable != null) FrameStore.spectatorBoardForPreview = stable
                                            publishedSequence = cardInspectionSeq
                                            publishedStable = stable
                                        }
                                        // Write small, bounded training diagnostics OUTSIDE
                                        // the capture/STOP publication lock. Never export
                                        // screen images, player details or cloud credentials.
                                        if (published && publishedSequence > 0L &&
                                            captureEpoch == FrameStore.captureEpoch &&
                                            CaptureObservationMode.isSpectator() &&
                                            !OperatorStopStore.isStopped(applicationContext)) {
                                            SpectatorLearningJournal.observe(applicationContext,
                                                board, publishedStable, now, publishedSequence)
                                        }
                                    } else {
                                    val visual = OnDeviceCardReader.inspectAll(screen)
                                    val cards = visual.cards
                                    FrameStore.commitIfCurrent(captureEpoch, origin, {
                                        !closing && !OperatorStopStore.isStopped(applicationContext) &&
                                            AppVisibilityGate.canPublish(visibilityTicket) &&
                                            windowStillMatches(origin, rootEvidence)
                                    }) {
                                        cardInspectionSeq += 1L
                                        val signal = CardVisionSignal(now, captureEpoch, cardInspectionSeq, cards)
                                        FrameStore.lastCards = signal
                                        val stable = FrameStore.cardConsensus.accept(
                                            signal, SystemClock.elapsedRealtime())
                                        FrameStore.confirmedCards = stable
                                        if (stable != null) FrameStore.lastConfirmedForPreview = stable
                                        // Combine observed cards with hand-stage logic on the
                                        // capture worker. Source proof is *intentionally null*:
                                        // a captured image alone is NOT evidence of 1win or a
                                        // table identifier. The UI can show a probable stage,
                                        // but the authoritative tracker cannot approve actions.
                                        OnDeviceStageBridge.lastAssessment =
                                            OnDeviceStageBridge.engine.observe(
                                                stable, null, captureEpoch,
                                                SystemClock.elapsedRealtime(),
                                                OperatorStopStore.isStopped(applicationContext))
                                        // The action strip is analysed from the SAME raster as cards.
                                        // Pixel pattern is not proof of the foreground app or button labels.
                                        val turnSignal = TurnVisualSignal(
                                            now, captureEpoch, cardInspectionSeq, false, visual.turn)
                                        FrameStore.lastTurnVisual = turnSignal
                                        FrameStore.confirmedTurnVisual = FrameStore.turnConsensus.accept(
                                            turnSignal, SystemClock.elapsedRealtime())
                                        // Independent read-only visual turn lifecycle.
                                        // It never supplies a hand ID, legal action or gesture.
                                        FrameStore.lastHeroTurnEpisode = FrameStore.heroTurnLifecycle.inspect(
                                            turnSignal, origin.observedPackage,
                                            origin.windowGeneration, origin.safeForLocalDiagnostics,
                                            SystemClock.elapsedRealtime(),
                                            OperatorStopStore.isStopped(applicationContext))
                                    }
                                    }
                                }
                                val profile = if (CaptureObservationMode.isSpectator())
                                    CaptureOptions.spectatorProfile.coerceIn(0, 2)
                                else CaptureOptions.profile.coerceIn(0, 2)
                                val refresh = (now - lastEncode >= 8000L) || profile != lastProfile
                                // New JPEG must not invalidate the in-flight ML Kit ticket
                                // every 300 ms. Raw capture and card/turn inspection continue.
                                val jpegCadenceReady = CapturePreviewCadence.mayReplace(
                                    now, lastEncode, profile != lastProfile)
                                if (origin.safeForLocalDiagnostics && jpegCadenceReady &&
                                    (refresh || FrameDifference.changed(lastSignature, sig))) {
                                    val longEdge = CaptureOptions.longEdges[profile]
                                    val scale = minOf(1.0, longEdge.toDouble() / maxOf(width, height))
                                    val w = maxOf(1, (width * scale).toInt())
                                    val h = maxOf(1, (height * scale).toInt())
                                    val smaller = Bitmap.createScaledBitmap(screen, w, h, true)
                                    try {
                                        val jpeg = ByteArrayOutputStream().use { out ->
                                            smaller.compress(Bitmap.CompressFormat.JPEG,
                                                CaptureOptions.qualities[profile], out)
                                            out.toByteArray()
                                        }
                                        if (jpeg.size <= 650_000 && FrameStore.commitIfCurrent(
                                                captureEpoch, origin, {
                                                    !closing && !OperatorStopStore.isStopped(applicationContext) &&
                                                        AppVisibilityGate.canPublish(visibilityTicket) &&
                                                        windowStillMatches(origin, rootEvidence)
                                                }) {
                                            val seq = FrameStore.frameSequence + 1L
                                            FrameStore.lastAutomaticOcr = null
                                            FrameStore.lastActionStrip = null
                                            FrameStore.latest = FrameInfo(jpeg, seq, w, h,
                                                CaptureOptions.qualities[profile], profile, now,
                                                captureEpoch, origin.observedPackage, origin.windowGeneration)
                                            FrameStore.rememberOperatorPreview(requireNotNull(FrameStore.latest))
                                            FrameStore.captureError = ""
                                            FrameStore.localFrameHealth = frameHealth
                                            FrameStore.frameSequence = seq
                                            lastSignature = sig
                                            lastEncode = now
                                            lastProfile = profile
                                        }) {
                                            // This read-only pipeline consumes only the committed JPEG.
                                            if (!CaptureObservationMode.isSpectator()) {
                                                // Existing player OCR is unchanged and is not
                                                // allowed to consume spectator-only board evidence.
                                                FrameStore.latest?.let { committed ->
                                                    OnDeviceAutomaticOcr.offer(applicationContext,
                                                        committed, origin) {
                                                            !closing && windowStillMatches(origin, rootEvidence)
                                                        }
                                                }
                                            } else {
                                                // Spectator-only public numeric hints; no
                                                // AutomaticOcr assembler and no player actions.
                                                FrameStore.latest?.let { committed ->
                                                    OnDeviceSpectatorOcr.offer(applicationContext,
                                                        committed, origin) {
                                                            !closing && windowStillMatches(origin, rootEvidence)
                                                        }
                                                }
                                            }
                                        } else if (jpeg.size > 650_000) {
                                            FrameStore.captureError = "Кадр превышает лимит размера"
                                        }
                                    } finally { if (smaller !== screen) smaller.recycle() }
                                }
                            } finally { screen.recycle() }
                        } finally { padded.recycle() }
                    }
                } catch (error: Exception) {
                    FrameStore.captureError = "Ошибка кадра: ${error.message}"
                    OnDeviceIncidentRecorder.record(applicationContext, IncidentCode.CAPTURE_FRAME_ERROR,
                        IncidentSeverity.ERROR, captureEpoch = FrameStore.captureEpoch)
                } finally { image.close() }
            }, Handler(worker!!.looper))
            display = projection!!.createVirtualDisplay("AI Remote Control", width, height,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, Handler(worker!!.looper))
            // Starting screen capture never re-arms the independent gesture gate.
            // Only the explicit operator ARM control may do that.
            require(!OperatorStopStore.isStopped(applicationContext) &&
                OnDeviceCaptureLifecycle.gate.markActive() &&
                FrameStore.beginActive(requestedEpoch)) {
                "Захват прерван до завершения запуска или после STOP"
            }
            FrameStore.captureError = ""
            OnDeviceIncidentRecorder.record(applicationContext, IncidentCode.CAPTURE_STARTED,
                captureEpoch = requestedEpoch)
        } catch (error: Exception) {
            OnDeviceCaptureLifecycle.gate.requestStop()
            OnDeviceLiveActionControl.stop()
            FrameStore.captureError = error.message ?: "Ошибка MediaProjection"
            OnDeviceIncidentRecorder.record(applicationContext, IncidentCode.CAPTURE_PERMISSION_ERROR,
                IncidentSeverity.ERROR, captureEpoch = requestedEpoch)
            stopSelf()
        }
    }

    private fun showNotification() {
        val channel = "capture_visible"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel, "Захват экрана", NotificationManager.IMPORTANCE_LOW))
        val stop = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 5, stop,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel)
            .setContentTitle("AI Remote Control — захват включён")
            .setContentText("Локальное наблюдение. STOP удаляет кадры")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "STOP", pending)
            .build()
        startForeground(11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        OnDeviceAutonomousRuntime.stop("Захват завершён")
        OnDeviceIncidentRecorder.record(applicationContext, IncidentCode.CAPTURE_STOPPED)
        // Release the old projection completely before another start may begin.
        OnDeviceCaptureLifecycle.gate.requestStop()
        OnDeviceLiveActionControl.stop()
        closing = true
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.unregisterCallback(callback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        worker?.quitSafely()
        FrameStore.clear()
        OnDeviceAutomaticOcr.reset()
        OnDeviceHandState.reset("Захват завершён")
        OnDeviceStageBridge.reset("Захват завершён")
        OnDeviceVerifiedHandCompletion.resetTransientEvidence()
        windowTracker.reset()
        OnDeviceCaptureLifecycle.gate.completeStop()
        super.onDestroy()
    }
}
