package com.screentranslator.search

import android.graphics.Bitmap
import android.net.Uri
import com.screentranslator.capture.CapturedFrame
import com.screentranslator.core.*
import com.screentranslator.engine.*
import com.screentranslator.gemini.*
import com.screentranslator.data.SecretUnavailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SearchPage { SELECT, EDITOR, PREVIEW, RESULT, CLOSED }
data class SearchUi(
    val page: SearchPage = SearchPage.SELECT,
    val screenshot: Bitmap? = null,
    val selection: Box? = null,
    val validSelection: Boolean = false,
    val preview: Bitmap? = null,
    val text: String = "",
    val translation: String? = null,
    val answer: GroundedAnswer? = null,
    val imageQuery: String = "Что изображено? Найди подтверждающую информацию и укажи неопределённость.",
    val searchQuery: String = "",
    val message: String = "Проведите пальцем по нужной области",
    val busy: Boolean = false
)
/** Process-local handoff. Intents carry only an id, never pixels or recognized text. */
object SearchSessions {
    var current: ScreenSearchSession? = null
        private set
    fun install(session: ScreenSearchSession) { current?.dispose(); current = session }
    fun clear(session: ScreenSearchSession) { if (current === session) current = null }
}

class ScreenSearchSession(
    val id: Long, frame: CapturedFrame, parent: CoroutineScope,
    private val coordinator: SessionCoordinator,
    private val ocr: OcrEngine, private val translator: TranslationPipeline,
    private val geminiSearch: GeminiSearchEngine?,
    private val files: SearchFiles,
    private val onAttached: () -> Unit,
    private val onClose: () -> Unit
) {
    val mode get() = translator.mode
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow(SearchUi(screenshot = frame.bitmap))
    val ui = mutable.asStateFlow()
    private var task: Job? = null
    private var disposed = false
    var attached = false
        private set
    private fun available() = !disposed && coordinator.state.value.active && coordinator.state.value.operation == Operation.SEARCH
    fun attach() { if (available()) { attached = true; onAttached() } }
    fun select(box: Box, valid: Boolean) {
        if (!disposed && !mutable.value.busy) mutable.value = mutable.value.copy(selection = box, validSelection = valid,
            message = if (valid) "Перемещайте рамку или тяните за края" else "Область слишком мала. Увеличьте рамку.")
    }
    fun edit(text: String) { if (!disposed) mutable.value = mutable.value.copy(text = text) }
    fun editImageQuery(text: String) { if (!disposed) mutable.value = mutable.value.copy(imageQuery = text) }
    fun message(message: String) { if (!disposed) mutable.value = mutable.value.copy(message = message) }
    fun dismissTranslation() { mutable.value = mutable.value.copy(translation = null) }
    fun backToSelection() {
        if (!available()) { dispose(); return }
        task?.cancel(); coordinator.searchAction(Phase.SELECTING)
        mutable.value = mutable.value.copy(page = SearchPage.SELECT, preview = null, text = "", busy = false, translation = null, answer = null, message = "Выберите действие для выделенной области")
    }
    private fun selectedInput(): Pair<Bitmap, PixelCrop>? {
        val state = mutable.value
        val image = state.screenshot ?: return null
        val crop = state.selection?.let { SelectionGeometry.crop(it, image.width, image.height) }
        if (!state.validSelection || crop == null) { message("Область слишком мала. Выделите больший фрагмент."); return null }
        return image to crop
    }
    private suspend fun crop(input: Pair<Bitmap, PixelCrop>): Bitmap = withContext(Dispatchers.Default) {
        val (image, rect) = input
        Bitmap.createBitmap(image, rect.left, rect.top, rect.width, rect.height)
    }
    fun recognize(translate: Boolean = false) {
        if (!available() || mutable.value.busy) return
        val input = selectedInput() ?: return
        task?.cancel()
        val token = coordinator.searchAction(Phase.RECOGNIZING)
        mutable.value = mutable.value.copy(busy = true, message = "Распознавание выделенной области…", translation = null)
        task = scope.launch {
            try {
                val selected = crop(input)
                // ML Kit already preserves line order inside a paragraph; order paragraphs top-to-bottom, left-to-right.
                val imageTranslations = if (translator.needsImageOcr) {
                    coordinator.message(token, "Считываю изображение через Gemini")
                    translator.translateImage(selected)
                } else null
                val blocks = (imageTranslations?.map { it.original } ?: ocr.recognize(selected)).sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                ensureActive()
                if (!coordinator.accepts(token)) return@launch
                if (translate) {
                    if (blocks.isEmpty()) { fail(token, "Текст не найден. Попробуйте другую область."); return@launch }
                    if (!coordinator.advance(token, Phase.TRANSLATING)) return@launch
                    val status = if (mode == AppMode.GEMINI) "Перевожу через Gemini" else "Перевожу текст"
                    mutable.value = mutable.value.copy(message = status); coordinator.message(token, status)
                    val translated = (imageTranslations ?: translator.translateBlocks(blocks)).joinToString("\n\n") { it.translation }
                    if (coordinator.advance(token, Phase.SELECTING)) mutable.value = mutable.value.copy(busy = false, translation = translated, message = "Перевод готов")
                } else if (coordinator.advance(token, Phase.EDITING_QUERY)) {
                    mutable.value = mutable.value.copy(page = SearchPage.EDITOR, busy = false,
                        text = blocks.joinToString("\n\n") { it.text },
                        message = if (blocks.isEmpty()) "Текст не найден. Введите запрос вручную." else "Проверьте и при необходимости исправьте запрос")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (coordinator.accepts(token)) {
                    if (!translate) mutable.value = mutable.value.copy(page = SearchPage.EDITOR, text = "")
                    fail(token, safeError(e, if (translate) "Не удалось перевести. Повторите попытку." else "Не удалось распознать текст. Введите запрос вручную или повторите выделение."))
                }
            }
        }
    }
    fun manualQuery() {
        if (!available()) { dispose(); return }
        task?.cancel(); coordinator.searchAction(Phase.EDITING_QUERY)
        mutable.value = mutable.value.copy(page = SearchPage.EDITOR, busy = false, message = "Введите поисковый запрос")
    }
    fun preview() {
        if (!available() || mutable.value.busy) return
        val input = selectedInput() ?: return
        val token = coordinator.searchAction(Phase.PREVIEWING_IMAGE)
        mutable.value = mutable.value.copy(busy = true, message = "Подготовка фрагмента…")
        task = scope.launch {
            try {
                val image = crop(input)
                if (coordinator.accepts(token)) mutable.value = mutable.value.copy(page = SearchPage.PREVIEW, preview = image, busy = false,
                    message = if (mode == AppMode.GEMINI) "Введите запрос для Gemini или передайте фрагмент во внешнее приложение." else "Будет передан только этот фрагмент. Выберите внешнее приложение для поиска.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(token, "Не удалось подготовить фрагмент. Повторите выделение.") }
        }
    }
    fun export(save: Boolean, onShareReady: (Uri) -> Unit = {}) {
        if (!available() || mutable.value.busy) return
        val image = mutable.value.preview ?: return
        val token = coordinator.searchAction(Phase.PREVIEWING_IMAGE)
        mutable.value = mutable.value.copy(busy = true, message = if (save) "Сохранение…" else "Подготовка передачи…")
        task = scope.launch {
            try {
                val uri = if (save) files.save(image) else files.sharePng(image)
                if (coordinator.accepts(token)) {
                    mutable.value = mutable.value.copy(busy = false, message = if (save)
                        "Сохранено в Pictures/ScreenTranslator. Откройте Google или Chrome, нажмите значок Lens, если он доступен, и выберите сохранённое изображение."
                    else "Выберите приложение. Системная передача сама по себе не выполняет поиск.")
                    if (!save) onShareReady(uri)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(token, "Не удалось записать фрагмент. Проверьте свободное место и повторите попытку.") }
        }
    }
    fun searchWithGemini(image: Boolean) {
        if (!available() || mutable.value.busy || geminiSearch == null) return
        val query = (if (image) mutable.value.imageQuery else mutable.value.text).trim()
        if (query.isEmpty()) { message("Введите непустой запрос"); return }
        val bitmap = if (image) mutable.value.preview ?: return else null
        val token = coordinator.searchAction(Phase.SEARCHING)
        task?.cancel()
        mutable.value = mutable.value.copy(busy = true, message = "Ищу информацию", searchQuery = query)
        task = scope.launch {
            try {
                val answer = geminiSearch.search(query, bitmap)
                if (coordinator.advance(token, Phase.SEARCH_RESULT)) mutable.value = mutable.value.copy(page = SearchPage.RESULT, answer = answer, busy = false, message = "Готово")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(token, safeError(e, "Не удалось выполнить поиск. Повторите попытку.")) }
        }
    }
    private fun safeError(error: Exception, fallback: String) = when (error) {
        is GeminiException -> error.message!!
        is SecretUnavailable -> "Не удалось расшифровать ключ. Сохраните его заново."
        else -> fallback
    }
    fun enteringExternal() { if (available()) coordinator.searchAction(Phase.EXTERNAL) }
    fun externalFailed(message: String) {
        if (!available()) { dispose(); return }
        coordinator.searchAction(when (mutable.value.page) { SearchPage.EDITOR -> Phase.EDITING_QUERY; SearchPage.RESULT -> Phase.SEARCH_RESULT; else -> Phase.PREVIEWING_IMAGE })
        message(message)
    }
    private fun fail(token: Long, message: String) {
        if (!coordinator.accepts(token)) return
        coordinator.fail(token, message); mutable.value = mutable.value.copy(busy = false, message = message)
    }
    fun close() { if (!disposed) { dispose(); onClose() } }
    fun dispose() {
        if (disposed) return
        disposed = true; scope.cancel()
        // Drop UI/frame references instead of recycling a bitmap still held by a View/RenderThread or ML Kit.
        // A cancelled SDK task retains only its local input until it completes, then GC can reclaim it safely.
        mutable.value = SearchUi(page = SearchPage.CLOSED)
        SearchSessions.clear(this)
    }
}
