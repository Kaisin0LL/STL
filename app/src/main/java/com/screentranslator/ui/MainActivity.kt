package com.screentranslator.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screentranslator.core.*
import com.screentranslator.service.ScreenTranslationService

class MainActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private val projectionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!vm.requestingPermission.value) return@registerForActivityResult
        vm.requestingPermission.value = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, ScreenTranslationService::class.java).apply {
                    action = ScreenTranslationService.START
                    putExtra("consent", result.data)
                    putExtra("source", vm.settings.value.pair.source)
                    putExtra("target", vm.settings.value.pair.target)
                })
                vm.message("Запуск переводчика…")
            } catch (_: Exception) { vm.message("Не удалось запустить захват. Повторите запуск из приложения.") }
        } else vm.message("Нужно разрешение: захват экрана отменён")
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // A denial does not prevent an FGS. Android still lists it in active apps.
        if (vm.requestingPermission.value) requestCapture()
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!vm.requestingPermission.value) return@registerForActivityResult
        if (Settings.canDrawOverlays(this)) requestNotifications()
        else { vm.requestingPermission.value = false; vm.message("Нужно разрешение: включите отображение поверх других приложений и повторите запуск") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFA8C7FA), background = Color(0xFF101318), surface = Color(0xFF191D24))) {
                val settings by vm.settings.collectAsStateWithLifecycle()
                val session by vm.session.collectAsStateWithLifecycle()
                val status by vm.status.collectAsStateWithLifecycle()
                val loading by vm.loading.collectAsStateWithLifecycle()
                val requesting by vm.requestingPermission.collectAsStateWithLifecycle()
                var about by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        vm.prepared.collect { prepared ->
                            if (prepared != null) {
                                vm.consumePrepared()
                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                    vm.message("Нужно разрешение: поверх других приложений")
                                    try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) }
                                    catch (_: Exception) { vm.requestingPermission.value = false; vm.message("Откройте системные настройки и разрешите отображение поверх приложений") }
                                } else requestNotifications()
                            }
                        }
                    }
                }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Text("Screen Translator", style = MaterialTheme.typography.headlineLarge)
                        Text("Перевод экрана одним нажатием", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LanguageMenu("С какого языка", settings.pair.source, Languages.sources, !loading && !requesting && !session.active) { vm.setPair(settings.pair.copy(source = it)) }
                        LanguageMenu("На какой язык", settings.pair.target, Languages.targets, !loading && !requesting && !session.active) { vm.setPair(settings.pair.copy(target = it)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(settings.mobileDownload, { vm.setMobile(it) }, enabled = !loading && !requesting && !session.active)
                            Text("Разрешить загрузку моделей по мобильной сети", Modifier.padding(top = 10.dp))
                        }
                        Text("По умолчанию модели загружаются по Wi-Fi. После загрузки выбранная пара работает без интернета.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            if (session.active) {
                                val stopIntent = Intent(this@MainActivity, ScreenTranslationService::class.java)
                                this@MainActivity.stopService(stopIntent)
                            }
                            else vm.prepare()
                        }, enabled = !loading && !requesting, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(if (session.active) "Остановить переводчик" else "Запустить переводчик")
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (session.active) session.message else status,
                            color = MaterialTheme.colorScheme.primary)
                        Text("В системном окне выберите весь экран. Кнопка «文» делает снимок; следующее нажатие скрывает перевод. Во время обработки нажатие отменяет запрос. Удерживайте кнопку, чтобы выделить область для поиска текста или изображения.")
                        Text("Переводы предоставлены Google Translate", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { about = true }) { Text("О приложении") }
                    }
                }
                if (about) AlertDialog(onDismissRequest = { about = false }, confirmButton = { TextButton(onClick = { about = false }) { Text("Понятно") } },
                    title = { Text("О Screen Translator") }, text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("Полный снимок, OCR и перевод обрабатываются на устройстве. Поиск отправляет исправленный запрос внешнему браузеру только по вашему нажатию. При передаче изображения временно сохраняется только выбранный фрагмент; постоянное сохранение — отдельное действие. Временные PNG доступны получателю до 24 часов. Захват активен до остановки, блокировки или отзыва системой. На защищённых экранах перевод может быть недоступен.\n\nBundled ML Kit Text Recognition v2: латиница. ML Kit Translation: машинный перевод Google; возможны неточности. Исходный русский недоступен, поскольку Latin OCR не распознаёт кириллицу.\n\nKotlin, Coroutines и AndroidX — Apache License 2.0. ML Kit и Google Play services — условия Google. Подробнее: THIRD_PARTY_NOTICES.md в исходниках.")
                            TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, "https://developers.google.com/ml-kit/language/translation/translation-terms".toUri())) }) { Text("Условия ML Kit Translation") }
                        }
                    })
            }
        }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestCapture()
    }
    private fun requestCapture() {
        if (Session.coordinator.state.value.active) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        try { projectionResult.launch(intent) } catch (_: Exception) { vm.requestingPermission.value = false; vm.message("Не удалось открыть системное разрешение на захват") }
    }
}

@Composable
private fun LanguageMenu(label: String, selected: String, languages: List<Language>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(Languages.name(selected)) }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                languages.forEach { language -> DropdownMenuItem(text = { Text(language.name) }, onClick = { expanded = false; onSelect(language.code) }) }
            }
        }
    }
}
