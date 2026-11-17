package com.screentranslator.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.screentranslator.core.LanguagePair
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.io.IOException

private val Context.settingsStore by preferencesDataStore("settings")
data class Settings(val pair: LanguagePair = LanguagePair(), val mobileDownload: Boolean = false, val x: Float = .85f, val y: Float = .35f)
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val source = stringPreferencesKey("source")
    private val target = stringPreferencesKey("target")
    private val mobile = booleanPreferencesKey("mobile")
    private val x = floatPreferencesKey("button_x")
    private val y = floatPreferencesKey("button_y")
    val settings = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { Settings(LanguagePair(it[source] ?: "en", it[target] ?: "ru"), it[mobile] ?: false, it[x] ?: .85f, it[y] ?: .35f) }
    suspend fun setPair(pair: LanguagePair) { store.edit { it[source] = pair.source; it[target] = pair.target } }
    suspend fun setMobile(enabled: Boolean) { store.edit { it[mobile] = enabled } }
    suspend fun setPosition(px: Float, py: Float) { store.edit { it[x] = px; it[y] = py } }
}
