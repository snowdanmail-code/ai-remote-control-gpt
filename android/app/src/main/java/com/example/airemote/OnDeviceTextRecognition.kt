package com.example.airemote

import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Offline OCR; the resulting labels are read-only, approximate text detections.
 * They do not verify an application, table, hand, or right to act.
 */
data class LocalOcrSnapshot(val lines: List<PositionedOcrLine>, val durationMs: Long) {
    fun summary(): String {
        val analysis = FrameTextEvidence.parse(lines)
        return "OCR локально: ${lines.size} строк; $durationMs мс\n" +
            lines.take(60).joinToString("\n") { it.text }.take(2300) +
            "\n\nТолько предварительные признаки, БЕЗ разрешения на игровые действия:" +
            "\nКнопки: " + (analysis.actions.joinToString { it.action.name }.ifBlank { "не установлены" }) +
            "\nЧисла: " + (analysis.numbers.joinToString { "${it.kind.name}=${it.amount}" }.ifBlank { "не установлены" }) +
            "\nОтброшено: " + (analysis.rejected.joinToString("; ").take(450).ifBlank { "нет" })
    }
}

object OnDeviceTextRecognition {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun inspectStructured(jpeg: ByteArray, callback: (Result<LocalOcrSnapshot>) -> Unit) {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bitmap == null) {
            callback(Result.failure(IllegalArgumentException("Невозможно прочитать кадр JPEG")))
            return
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val started = SystemClock.elapsedRealtime()
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    if (bitmap.width <= 0 || bitmap.height <= 0) return@mapNotNull null
                    val norm = NormalizedTextBox(
                        (bounds.left * 1000L / bitmap.width).toInt(),
                        (bounds.top * 1000L / bitmap.height).toInt(),
                        (bounds.right * 1000L / bitmap.width).toInt(),
                        (bounds.bottom * 1000L / bitmap.height).toInt())
                    if (!norm.valid()) null else PositionedOcrLine(line.text.trim(), norm)
                }.filter { it.text.isNotEmpty() }.take(100)
                callback(Result.success(LocalOcrSnapshot(lines, SystemClock.elapsedRealtime() - started)))
            }
            .addOnFailureListener { error -> callback(Result.failure(error)) }
            .addOnCompleteListener { bitmap.recycle() }
    }
}
