package com.screentranslator.search

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screentranslator.core.SearchQuery
import com.screentranslator.ui.MainActivity

class SearchActivity : ComponentActivity() {
    private var session: ScreenSearchSession? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = SearchSessions.current?.takeIf { it.id == intent.getLongExtra("session_id", -1) }
        session?.attach()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFA8C7FA), background = Color(0xFF101318), surface = Color(0xFF191D24))) {
                val active = session
                if (active == null) {
                    Scaffold { padding ->
                        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Снимок больше недоступен", style = MaterialTheme.typography.headlineSmall)
                            Text("Запустите переводчик с новым системным согласием, затем откройте нужное приложение и удерживайте плавающую кнопку.")
                            Button(onClick = { startActivity(Intent(this@SearchActivity, MainActivity::class.java)); finishAndRemoveTask() }) { Text("Открыть переводчик") }
                            TextButton(onClick = { finishAndRemoveTask() }) { Text("Закрыть") }
                        }
                    }
                } else SearchScreen(active)
            }
        }
    }
    @Composable
    private fun SearchScreen(active: ScreenSearchSession) {
        val state by active.ui.collectAsStateWithLifecycle()
        LaunchedEffect(state.page) { if (state.page == SearchPage.CLOSED) finishAndRemoveTask() }
        BackHandler {
            if (state.page == SearchPage.SELECT) close() else active.backToSelection()
        }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Поиск на экране", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = ::close) { Text("Закрыть") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (state.page) {
                    SearchPage.SELECT -> {
                        Text(state.message, style = MaterialTheme.typography.bodySmall)
                        state.screenshot?.let { bitmap ->
                            AndroidView(factory = { context -> SelectionView(context, bitmap, state.selection, active::select) },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                onRelease = { it.clearFrame() }, update = { it.isEnabled = !state.busy })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { active.recognize() }, enabled = state.validSelection && !state.busy, modifier = Modifier.weight(1f)) { Text("Искать текст") }
                            OutlinedButton(onClick = active::preview, enabled = state.validSelection && !state.busy, modifier = Modifier.weight(1f)) { Text("Поиск по изображению") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { active.recognize(translate = true) }, enabled = state.validSelection && !state.busy, modifier = Modifier.weight(1f)) { Text("Перевести") }
                            TextButton(onClick = active::manualQuery, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Ввести запрос") }
                        }
                        Text("Перевод: Google Translate", style = MaterialTheme.typography.labelSmall)
                    }
                    SearchPage.EDITOR -> {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.message)
                            OutlinedTextField(value = state.text, onValueChange = active::edit, label = { Text("Поисковый запрос") },
                                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { openSearch() }))
                            Text("Запрос будет передан Google через внешнее приложение только после нажатия «Искать в Google».", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = ::openSearch, enabled = SearchQuery.googleUrl(state.text) != null, modifier = Modifier.fillMaxWidth()) { Text("Искать в Google") }
                            TextButton(onClick = active::backToSelection) { Text("К выделению") }
                        }
                    }
                    SearchPage.PREVIEW -> {
                        state.preview?.let { Image(it.asImageBitmap(), "Выбранный фрагмент без рамки и кнопок", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit) }
                        Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.message, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { active.export(save = false, onShareReady = ::share) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Передать в приложение…") }
                            OutlinedButton(onClick = { active.export(save = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Сохранить фрагмент") }
                            TextButton(onClick = active::backToSelection, enabled = !state.busy) { Text("К выделению") }
                        }
                    }
                    SearchPage.CLOSED -> Unit
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        state.translation?.let { translation ->
            AlertDialog(onDismissRequest = active::dismissTranslation,
                confirmButton = { TextButton(onClick = active::dismissTranslation) { Text("Готово") } },
                title = { Text("Перевод · Google Translate") },
                text = { Text(translation, Modifier.verticalScroll(rememberScrollState())) })
        }
    }
    private fun openSearch() {
        val active = session ?: return
        val url = SearchQuery.googleUrl(active.ui.value.text) ?: run { active.message("Введите непустой запрос"); return }
        val view = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        if (view.resolveActivity(packageManager) == null) { active.message("Не найден браузер. Установите браузер и повторите поиск."); return }
        external(view, "Не удалось открыть браузер. Установите браузер и повторите попытку.")
    }
    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "Выбранный фрагмент", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
            session?.message("Нет приложений для передачи PNG. Сохраните фрагмент и откройте его в Google или Chrome через Lens, если эта функция доступна.")
            return
        }
        external(Intent.createChooser(send, "Передать фрагмент в приложение"), "Не удалось открыть выбор приложений. Можно сохранить фрагмент.")
    }
    private fun external(intent: Intent, error: String) {
        val active = session ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { active.message("Вернитесь в приложение и повторите действие"); return }
        // No full-screen WindowManager overlay exists here. Launch from a visible Activity on an explicit tap.
        active.enteringExternal()
        try { startActivity(intent); close() }
        catch (_: ActivityNotFoundException) { active.externalFailed(error) }
        catch (_: SecurityException) { active.externalFailed(error) }
    }
    private fun close() { session?.close(); finishAndRemoveTask() }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) { session?.close(); finishAndRemoveTask() }
    }
    override fun onDestroy() { if (isFinishing) session?.close(); session = null; super.onDestroy() }
}
