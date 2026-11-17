package com.screentranslator.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Operation { TRANSLATE, SEARCH }
enum class Phase { STOPPED, READY, CAPTURING, SELECTING, RECOGNIZING, TRANSLATING, SHOWING, EDITING_QUERY, PREVIEWING_IMAGE, EXTERNAL, ERROR }
data class SessionState(val phase: Phase = Phase.STOPPED, val active: Boolean = false, val message: String = "Готово", val operation: Operation = Operation.TRANSLATE) {
    val busy get() = phase in setOf(Phase.CAPTURING, Phase.RECOGNIZING, Phase.TRANSLATING)
}
/** Main-thread state machine. Every new action invalidates uncancellable SDK results. */
class SessionCoordinator {
    private var generation = 0L
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    fun start() { generation++; mutable.value = SessionState(Phase.READY, true, "Работает") }
    fun begin(operation: Operation = Operation.TRANSLATE): Long {
        check(mutable.value.active)
        generation++
        mutable.value = SessionState(Phase.CAPTURING, true, "Получение снимка…", operation)
        return generation
    }
    fun accepts(token: Long) = mutable.value.active && token == generation
    /** A new selection action supersedes any previous OCR, editing or export operation. */
    fun searchAction(phase: Phase): Long {
        check(mutable.value.active && mutable.value.operation == Operation.SEARCH)
        require(phase in setOf(Phase.SELECTING, Phase.RECOGNIZING, Phase.PREVIEWING_IMAGE, Phase.EDITING_QUERY, Phase.EXTERNAL))
        generation++; setPhase(phase)
        return generation
    }
    fun advance(token: Long, phase: Phase): Boolean {
        if (!accepts(token)) return false
        val next = if (mutable.value.operation == Operation.TRANSLATE) when (mutable.value.phase) {
            Phase.CAPTURING -> setOf(Phase.RECOGNIZING)
            Phase.RECOGNIZING -> setOf(Phase.TRANSLATING)
            Phase.TRANSLATING -> setOf(Phase.SHOWING)
            else -> emptySet()
        } else when (mutable.value.phase) {
            Phase.CAPTURING -> setOf(Phase.SELECTING)
            Phase.SELECTING -> setOf(Phase.RECOGNIZING, Phase.PREVIEWING_IMAGE)
            Phase.RECOGNIZING -> setOf(Phase.EDITING_QUERY, Phase.TRANSLATING)
            Phase.TRANSLATING -> setOf(Phase.SELECTING)
            Phase.EDITING_QUERY, Phase.PREVIEWING_IMAGE -> setOf(Phase.EXTERNAL)
            else -> emptySet()
        }
        if (phase !in next) return false
        setPhase(phase); return true
    }
    private fun setPhase(phase: Phase) {
        mutable.value = mutable.value.copy(phase = phase, message = when (phase) {
            Phase.SELECTING -> "Выберите область"
            Phase.RECOGNIZING -> "Распознавание…"
            Phase.TRANSLATING -> "Перевод…"
            Phase.SHOWING -> "Перевод открыт"
            Phase.EDITING_QUERY -> "Проверьте поисковый запрос"
            Phase.PREVIEWING_IMAGE -> "Предпросмотр изображения"
            Phase.EXTERNAL -> "Переход во внешнее приложение"
            else -> "Работает"
        })
    }
    fun fail(token: Long, message: String) {
        if (accepts(token)) { generation++; mutable.value = mutable.value.copy(phase = Phase.ERROR, message = message) }
    }
    fun ready(message: String = "Работает") {
        generation++
        if (mutable.value.active) mutable.value = SessionState(Phase.READY, true, message)
    }
    fun stop(message: String = "Остановлено") { generation++; mutable.value = SessionState(message = message) }
}
object Session { val coordinator = SessionCoordinator() }

data class TranslationKey(val text: String, val pair: LanguagePair)
class TranslationCache(private val capacity: Int = 128) {
    init { require(capacity > 0) }
    private val values = object : LinkedHashMap<TranslationKey, String>(capacity, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationKey, String>?) = size > capacity
    }
    @Synchronized fun get(key: TranslationKey) = values[key]
    @Synchronized fun put(key: TranslationKey, value: String) { values[key] = value }
    @Synchronized fun clear() = values.clear()
}
