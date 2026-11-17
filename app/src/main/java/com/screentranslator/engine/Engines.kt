package com.screentranslator.engine

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.screentranslator.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class RecognizedBlock(val text: String, val bounds: Box)
data class TranslatedBlock(val original: RecognizedBlock, val translation: String)
interface OcrEngine : AutoCloseable { suspend fun recognize(bitmap: Bitmap): List<RecognizedBlock> }
interface TranslationEngine : AutoCloseable {
    suspend fun prepare(allowMobile: Boolean)
    suspend fun translate(text: String): String
}
class LatinOcrEngine : OcrEngine {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    override suspend fun recognize(bitmap: Bitmap): List<RecognizedBlock> = withContext(Dispatchers.Default) {
        // ML Kit may still read this bitmap after coroutine cancellation. Wait for ownership to return.
        val result = withContext(NonCancellable) { client.process(InputImage.fromBitmap(bitmap, 0)).await() }
        result.textBlocks.mapNotNull { block ->
            block.boundingBox?.takeIf { it.width() > 0 && it.height() > 0 && block.text.isNotBlank() }?.let {
                RecognizedBlock(block.text, Box(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()))
            }
        }
    }
    override fun close() = client.close()
}
class MlTranslationEngine(private val pair: LanguagePair) : TranslationEngine {
    init { require(Languages.supports(pair)) { "Недоступное направление перевода" } }
    private val client = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(pair.source).setTargetLanguage(pair.target).build())
    override suspend fun prepare(allowMobile: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (!allowMobile) requireWifi() }.build()
        client.downloadModelIfNeeded(conditions).await()
    }
    override suspend fun translate(text: String) = client.translate(text).await()
    override fun close() = client.close()
}
