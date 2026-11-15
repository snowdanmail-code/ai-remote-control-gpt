package com.example.airemote

import android.content.Context
import android.graphics.Bitmap
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
        "rus" to "65254c36df8274cee0a2c32e9632f3c2c3c2c0032314bf0a99a4c23309e862da7c038ed85745d58697a814f44c656f22ef5a7815ae2bec33f80de1de8e1ea9dd",
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

    private fun readLines(
        tess: TessBaseAPI, bitmap: Bitmap,
        frameWidth: Int = bitmap.width, frameHeight: Int = bitmap.height,
        offsetX: Int = 0, offsetY: Int = 0
    ): List<PositionedOcrLine> {
        try {
            tess.setImage(bitmap)
            tess.getUTF8Text()
            val iterator = tess.getResultIterator() ?: return emptyList()
            try {
                val lines = mutableListOf<PositionedOcrLine>()
                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                    if (text.isEmpty() || text.length > 90 || iterator.confidence(level) < 38f)
                        continue
                    val box = iterator.getBoundingRect(level)
                    val normalized = NormalizedTextBox(
                        ((box.left + offsetX) * 1000L / frameWidth).toInt(),
                        ((box.top + offsetY) * 1000L / frameHeight).toInt(),
                        ((box.right + offsetX) * 1000L / frameWidth).toInt(),
                        ((box.bottom + offsetY) * 1000L / frameHeight).toInt())
                    if (normalized.valid()) lines.add(PositionedOcrLine(text, normalized))
                } while (lines.size < 120 && iterator.next(level))
                return lines
            } finally { iterator.delete() }
        } finally { tess.clear() }
    }

    private fun recoverBuyInButtons(
        tess: TessBaseAPI, bitmap: Bitmap,
        primary: List<PositionedOcrLine>
    ): List<PositionedOcrLine> {
        if (bitmap.width >= bitmap.height) return primary
        val regions = OneWinCashLobbyParser.missingBuyInRegions(primary)
        if (regions.isEmpty()) return primary
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        try {
            var lines = primary
            for (region in regions) {
                val left = region.left * bitmap.width / 1000
                val top = region.top * bitmap.height / 1000
                val width = region.right * bitmap.width / 1000 - left
                val height = region.bottom * bitmap.height / 1000 - top
                if (width <= 0 || height <= 0) continue
                val button = Bitmap.createBitmap(bitmap, left, top, width, height)
                try {
                    val recovered = readLines(tess, button, bitmap.width, bitmap.height, left, top)
                    lines = OneWinCashLobbyParser.mergeBuyInRegion(lines, region, recovered)
                } finally { button.recycle() }
            }
            return lines
        } finally {
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        }
    }

    fun inspectStructured(context: Context, jpeg: ByteArray,
                          recoverLobbyButtons: Boolean = false,
                          callback: (Result<LocalOcrSnapshot>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                val started = SystemClock.elapsedRealtime()
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: error("Cannot decode cash lobby screenshot")
                try {
                    val tess = engineFor(app)
                    val primary = readLines(tess, bitmap)
                    val lines = if (recoverLobbyButtons) recoverBuyInButtons(tess, bitmap, primary)
                        else primary
                    LocalOcrSnapshot(lines, SystemClock.elapsedRealtime() - started)
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
