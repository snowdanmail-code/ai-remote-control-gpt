package com.example.airemote

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Dedicated offline Russian+English text OCR for 1win Cash Games lobby.
 *
 * ML Kit Latin remains the existing card/button OCR; this recognizer exists
 * solely because the lobby labels ('Кэш-Игры', 'Холдем', 'Бай-ин', 'Ставки')
 * are Cyrillic. No screenshot or recognition request goes to a server.
 */
object OnDeviceCashLobbyTextRecognition {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cash-lobby-ru-en-ocr").apply { isDaemon = true }
    }
    private val hashes = mapOf(
        "rus" to "65254c36df8274cee0a2c32e9632f3c2c0032314bf0a99a4c23309e862da7c038ed85745d58697a814f44c656f22ef5a7815ae2bec33f80de1de8e1ea9dd",
        "eng" to "90aadb5e0c368781f94add808f6b4dd31d8e244c9fecbe6a233070cac9a4fc071fd23a09e4a7e041db8e26880a6debbdf32a6b701c28687b2ed1cac81f4c1d14"
    )
    private val lengths = mapOf("rus" to 3_861_738, "eng" to 4_113_088)
    // Single OCR worker owns this native engine for the entire capture
    // session. Loading rus+eng neural models on every frame is too slow.
    private var engine: TessBaseAPI? = null
    @Volatile private var languagePath: String? = null
    @Volatile var lastStatus: String = "Русско-английский OCR ещё не запускался"
        private set

    private fun sha512(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    /** Installation and SHA verification run ONLY on the OCR worker. */
    @Synchronized private fun ensureModels(context: Context): String {
        languagePath?.let { return it }
        val folder = File(context.filesDir, "cash_lobby_ocr_v1")
        val dest = File(folder, "tessdata")
        check(dest.isDirectory || dest.mkdirs()) { "Cannot create local OCR data directory" }
        for ((lang, hash) in hashes) {
            val target = File(dest, "$lang.traineddata")
            if (!target.exists() || target.length() != lengths.getValue(lang).toLong() ||
                sha512(target.readBytes()) != hash) {
                val bytes = context.assets.open("tessdata/$lang.traineddata").use { it.readBytes() }
                check(bytes.size == lengths.getValue(lang) && sha512(bytes) == hash) {
                    "Bundled OCR model $lang is not authenticated"
                }
                val tmp = File(dest, "$lang.traineddata.part")
                tmp.writeBytes(bytes)
                check(tmp.renameTo(target)) { "Unable to activate OCR model $lang" }
            }
        }
        languagePath = folder.absolutePath
        return folder.absolutePath
    }

    private fun engineFor(context: Context): TessBaseAPI {
        engine?.let { return it }
        val created = TessBaseAPI()
        try {
            check(created.init(ensureModels(context), "rus+eng",
                TessBaseAPI.OEM_LSTM_ONLY)) { "Cyrillic OCR engine failed to initialize" }
            // UI text is scattered across independent narrow columns and rows.
            created.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            engine = created
            return created
        } catch (e: Exception) {
            created.recycle()
            throw e
        }
    }

    fun inspectStructured(context: Context, jpeg: ByteArray,
                          callback: (Result<LocalOcrSnapshot>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                val started = SystemClock.elapsedRealtime()
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: error("Cannot decode cash lobby screenshot")
                try {
                    val tess = engineFor(app)
                    try {
                        tess.setImage(bitmap)
                        tess.getUTF8Text() // initialize result iterator
                        val iterator = tess.getResultIterator()
                        val lines = mutableListOf<PositionedOcrLine>()
                        if (iterator != null) {
                            try {
                                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                                iterator.begin()
                                do {
                                    val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                                    val conf = iterator.confidence(level)
                                    val box = iterator.getBoundingRect(level)
                                    if (text.isNotEmpty() && text.length <= 90 &&
                                        conf >= 38f && bitmap.width > 0 && bitmap.height > 0) {
                                        val normalized = NormalizedTextBox(
                                            (box.left * 1000L / bitmap.width).toInt(),
                                            (box.top * 1000L / bitmap.height).toInt(),
                                            (box.right * 1000L / bitmap.width).toInt(),
                                            (box.bottom * 1000L / bitmap.height).toInt())
                                        if (normalized.valid())
                                            lines.add(PositionedOcrLine(text, normalized))
                                    }
                                } while (lines.size < 120 && iterator.next(level))
                            } finally { iterator.delete() }
                        }
                        LocalOcrSnapshot(lines, SystemClock.elapsedRealtime() - started)
                    } finally {
                        // Keep the engine warm; only drop the per-frame data.
                        tess.clear()
                    }
                } finally { bitmap.recycle() }
            }
            lastStatus = result.fold(
                onSuccess = { "Локальный rus+eng OCR: ${it.lines.size} строк, ${it.durationMs} мс" },
                onFailure = { "Локальный rus+eng OCR не готов: ${it.javaClass.simpleName}; ${it.message?.take(150)}" }
            )
            callback(result)
        }
    }
}
