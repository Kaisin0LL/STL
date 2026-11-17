package com.screentranslator.engine

import android.graphics.Bitmap
import com.screentranslator.core.*
import com.screentranslator.gemini.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Shared batch interface; provider selection is explicit and never changes on failure. */
class TranslationPipeline(val mode: AppMode, val pair: LanguagePair, private val cache: TranslationCache, private val gemini: GeminiApiClient?) : AutoCloseable {
    private val local = if (mode == AppMode.LOCAL) MlTranslationEngine(pair) else null
    val needsImageOcr get() = mode == AppMode.GEMINI && pair.source !in Languages.sourceCodes
    suspend fun translateBlocks(blocks: List<RecognizedBlock>): List<TranslatedBlock> = withContext(Dispatchers.Default) {
        if (blocks.isEmpty()) return@withContext emptyList()
        val keys = blocks.map { TranslationKey(it.text, pair, mode) }
        if (mode == AppMode.LOCAL) return@withContext blocks.mapIndexed { i, block ->
            ensureActive()
            TranslatedBlock(block, cache.get(keys[i]) ?: local!!.translate(block.text).also { cache.put(keys[i], it) })
        }
        val saved = keys.map(cache::get)
        if (saved.all { it != null }) return@withContext blocks.mapIndexed { i, block -> TranslatedBlock(block, saved[i]!!) }
        val translated = GeminiTranslationEngine(requireNotNull(gemini)).translate(blocks.mapIndexed { i, b -> TextBlockInput("block_${i + 1}", b.text) }, pair)
        blocks.mapIndexed { i, block -> TranslatedBlock(block, translated.getValue("block_${i + 1}").also { cache.put(keys[i], it) }) }
    }
    suspend fun translateImage(bitmap: Bitmap): List<TranslatedBlock> {
        check(needsImageOcr)
        val image = GeminiImages.encode(bitmap)
        val candidate = requireNotNull(gemini).generate(GeminiProtocol.visionTranslation(image, pair))
        return withContext(Dispatchers.Default) { GeminiProtocol.vision(GeminiProtocol.text(candidate), image).map { TranslatedBlock(RecognizedBlock(it.text, it.bounds), it.translation) } }
    }
    override fun close() { local?.close() }
}
class GeminiTranslationEngine(private val api: GeminiApiClient) {
    suspend fun translate(blocks: List<TextBlockInput>, pair: LanguagePair): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val batch = mutableListOf<TextBlockInput>()
        var chars = 0
        suspend fun flush() {
            if (batch.isEmpty()) return
            val candidate = api.generate(GeminiProtocol.translation(batch, pair))
            result.putAll(GeminiProtocol.translations(GeminiProtocol.text(candidate), batch.map { it.id }))
            batch.clear(); chars = 0
        }
        for (block in blocks) {
            if (block.text.length > 12_000) throw GeminiException(GeminiFailure.TOO_LARGE)
            if (batch.size >= 64 || chars + block.text.length > 12_000) flush()
            batch.add(block); chars += block.text.length
        }
        flush()
        return result
    }
}
class GeminiSearchEngine(private val api: GeminiApiClient) {
    suspend fun search(query: String, bitmap: Bitmap? = null): GroundedAnswer {
        val candidate = api.generate(GeminiProtocol.search(query, bitmap?.let { GeminiImages.encode(it) }))
        return withContext(Dispatchers.Default) { GroundingResponseMapper.map(candidate) }
    }
}
