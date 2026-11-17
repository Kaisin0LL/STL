package com.screentranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screentranslator.core.*
import com.screentranslator.data.*
import com.screentranslator.engine.MlTranslationEngine
import com.screentranslator.gemini.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val keys = ApiKeys.get(application)
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val session = Session.coordinator.state
    private val mutableStatus = MutableStateFlow("Готово")
    val status = mutableStatus.asStateFlow()
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()
    private var job: Job? = null
    private var keyJob: Job? = null
    private var keyGeneration = 0L
    private val mutablePrepared = MutableStateFlow<LanguagePair?>(null)
    val prepared = mutablePrepared.asStateFlow()
    val requestingPermission = MutableStateFlow(false)
    private val mutableKeyStatus = MutableStateFlow("Ключ не задан")
    val keyStatus = mutableKeyStatus.asStateFlow()
    private val mutableKeyBusy = MutableStateFlow(false)
    val keyBusy = mutableKeyBusy.asStateFlow()
    init {
        viewModelScope.launch { session.collect { if (!it.active && it.message != "Готово") message(it.message) } }
        viewModelScope.launch { refreshKeyStatus() }
    }
    private suspend fun refreshKeyStatus() {
        val generation = keyGeneration
        val status = when (keys.status()) {
            KeyStatus.MISSING -> "Ключ не задан"
            KeyStatus.SAVED -> "Ключ сохранён"
            KeyStatus.UNREADABLE -> "Ключ не удаётся расшифровать. Сохраните его заново."
        }
        if (generation == keyGeneration) mutableKeyStatus.value = status
    }
    fun message(text: String) { mutableStatus.value = text }
    private fun invalidate() {
        job?.cancel(); keyJob?.cancel(); keyGeneration++; mutableKeyBusy.value = false
        mutablePrepared.value = null; requestingPermission.value = false
        Session.invalidateConfiguration()
    }
    fun setPair(pair: LanguagePair) { invalidate(); viewModelScope.launch { repository.setPair(pair) } }
    fun setMode(mode: AppMode) { invalidate(); viewModelScope.launch { repository.setMode(mode) } }
    fun setMobile(value: Boolean) { viewModelScope.launch { repository.setMobile(value) } }
    fun saveKey(value: String, onSaved: () -> Unit) {
        invalidate()
        val generation = keyGeneration
        keyJob = viewModelScope.launch {
            mutableKeyBusy.value = true
            try { keys.save(value.trim()); repository.setMode(AppMode.GEMINI); mutableKeyStatus.value = "Ключ сохранён"; onSaved() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableKeyStatus.value = "Не удалось сохранить ключ. Проверьте поле и свободное место." }
            finally { if (generation == keyGeneration) mutableKeyBusy.value = false }
        }
    }
    fun deleteKey() {
        invalidate()
        val generation = keyGeneration
        keyJob = viewModelScope.launch {
            mutableKeyBusy.value = true
            try { keys.delete(); mutableKeyStatus.value = "Ключ не задан" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableKeyStatus.value = "Не удалось удалить ключ. Повторите попытку." }
            finally { if (generation == keyGeneration) mutableKeyBusy.value = false }
        }
    }
    fun verifyKey() {
        keyJob?.cancel()
        val generation = ++keyGeneration
        keyJob = viewModelScope.launch {
            mutableKeyBusy.value = true; mutableKeyStatus.value = "Проверка подключения…"
            try { GeminiApiClient(keys::read).use { it.generate(GeminiProtocol.connectionCheck()) }; mutableKeyStatus.value = "Подключение проверено" }
            catch (e: CancellationException) { throw e }
            catch (e: GeminiException) { mutableKeyStatus.value = e.message!! }
            catch (_: SecretUnavailable) { mutableKeyStatus.value = "Не удалось расшифровать ключ. Сохраните его заново." }
            catch (_: Exception) { mutableKeyStatus.value = "Не удалось проверить подключение. Повторите попытку." }
            finally { if (generation == keyGeneration) mutableKeyBusy.value = false }
        }
    }
    fun consumePrepared() { mutablePrepared.value = null }
    fun prepare() {
        if (mutableLoading.value || requestingPermission.value || mutableKeyBusy.value) return
        val selected = settings.value
        if (!Languages.supports(selected.pair, selected.mode)) { message("Недоступное направление перевода"); return }
        job = viewModelScope.launch {
            mutableLoading.value = true
            try {
                if (selected.mode == AppMode.LOCAL) {
                    message("Загрузка модели… Нужен Wi-Fi или разрешение мобильной сети")
                    withTimeout(180_000) { MlTranslationEngine(selected.pair).use { it.prepare(selected.mobileDownload) } }
                } else {
                    when (keys.status()) {
                        KeyStatus.MISSING -> throw GeminiException(GeminiFailure.MISSING_KEY)
                        KeyStatus.UNREADABLE -> throw SecretUnavailable()
                        KeyStatus.SAVED -> Unit
                    }
                }
                message("Готово"); requestingPermission.value = true; mutablePrepared.value = selected.pair
            } catch (_: TimeoutCancellationException) { message("Модель не загружена. Проверьте интернет/Wi-Fi и повторите запуск.") }
            catch (e: CancellationException) { throw e }
            catch (e: GeminiException) { message(e.message!!) }
            catch (_: SecretUnavailable) { message("Не удалось расшифровать ключ. Сохраните его заново.") }
            catch (_: Exception) { message("Ошибка подготовки переводчика. Проверьте настройки и повторите запуск.") }
            finally { mutableLoading.value = false }
        }
    }
}
