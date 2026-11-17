package com.screentranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screentranslator.core.*
import com.screentranslator.data.*
import com.screentranslator.engine.MlTranslationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val session = Session.coordinator.state
    private val mutableStatus = MutableStateFlow("Готово")
    val status = mutableStatus.asStateFlow()
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()
    private var job: Job? = null
    private val mutablePrepared = MutableStateFlow<LanguagePair?>(null)
    val prepared = mutablePrepared.asStateFlow()
    val requestingPermission = MutableStateFlow(false)
    init { viewModelScope.launch { session.collect { if (!it.active && it.message != "Готово") message(it.message) } } }
    fun message(text: String) { mutableStatus.value = text }
    fun setPair(pair: LanguagePair) {
        job?.cancel(); mutablePrepared.value = null
        viewModelScope.launch { repository.setPair(pair) }
    }
    fun setMobile(value: Boolean) { viewModelScope.launch { repository.setMobile(value) } }
    fun consumePrepared() { mutablePrepared.value = null }
    fun prepare() {
        if (mutableLoading.value || requestingPermission.value) return
        val selected = settings.value
        if (!Languages.supports(selected.pair)) { message("Недоступное направление перевода"); return }
        job = viewModelScope.launch {
            mutableLoading.value = true; message("Загрузка модели… Нужен Wi-Fi или разрешение мобильной сети")
            try {
                withTimeout(180_000) {
                    MlTranslationEngine(selected.pair).use { it.prepare(selected.mobileDownload) }
                }
                message("Модели готовы"); requestingPermission.value = true; mutablePrepared.value = selected.pair
            } catch (_: TimeoutCancellationException) {
                message("Модель не загружена. Проверьте интернет/Wi-Fi и повторите запуск.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message("Ошибка загрузки модели. Проверьте интернет и повторите запуск.") }
            finally { mutableLoading.value = false }
        }
    }
}
