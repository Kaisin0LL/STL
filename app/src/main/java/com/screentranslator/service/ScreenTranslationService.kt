package com.screentranslator.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.screentranslator.R
import com.screentranslator.capture.*
import com.screentranslator.core.*
import com.screentranslator.data.SettingsRepository
import com.screentranslator.data.ApiKeys
import com.screentranslator.data.SecretUnavailable
import com.screentranslator.gemini.*
import com.screentranslator.overlay.BlurBackgroundRenderer
import com.screentranslator.engine.*
import com.screentranslator.overlay.OverlayController
import com.screentranslator.ui.MainActivity
import com.screentranslator.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ScreenTranslationService : Service() {
    companion object { const val START = "com.screentranslator.START"; const val STOP = "com.screentranslator.STOP"; private const val CHANNEL = "capture" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator = Session.coordinator
    private var capture: ScreenCaptureManager? = null
    private var overlay: OverlayController? = null
    private var ocr: OcrEngine? = null
    private var translator: TranslationPipeline? = null
    private var gemini: GeminiApiClient? = null
    private var mode = AppMode.LOCAL
    private val stopCallback: () -> Unit = { finishSession("Настройки изменены. Запустите переводчик снова.") }
    private val cache = TranslationCache()
    private var request: Job? = null
    private var search: ScreenSearchSession? = null
    private var notice: Toast? = null
    private var started = false
    private var destroyed = false
    private var captureWidth = 0
    private var captureHeight = 0
    private var pair = LanguagePair()
    private var lastRotation = -1
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (capture != null && rotation() != lastRotation) {
                lastRotation = rotation(); invalidateForResize()
                val (width, height) = screenSize()
                captureWidth = width; captureHeight = height
                capture?.resize(width, height, lastRotation); overlay?.onConfigurationChanged()
            }
        }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { finishSession("Захват остановлен при блокировке. Запустите переводчик снова.") }
    }
    override fun onCreate() {
        super.onCreate()
        Session.stopActiveSession = stopCallback
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        scope.launch(Dispatchers.IO) { runCatching { SearchFiles(this@ScreenTranslationService).cleanup() } }
        scope.launch { coordinator.state.collect { overlay?.update(it) } }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishSession("Остановлено"); return START_NOT_STICKY }
        if (intent?.action != START) { finishSession("Готово"); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        try {
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consent", Intent::class.java) else {
                @Suppress("DEPRECATION") intent.getParcelableExtra("consent")
            }
            requireNotNull(consent)
            check(Settings.canDrawOverlays(this)) { "Нужно разрешение на отображение поверх приложений" }
            mode = AppMode.entries.firstOrNull { it.name == intent.getStringExtra("mode") } ?: AppMode.LOCAL
            pair = LanguagePair(intent.getStringExtra("source") ?: "en", intent.getStringExtra("target") ?: "ru")
            require(Languages.supports(pair, mode)) { "Недоступное направление перевода" }
            showNotification() // Must precede getMediaProjection on Android 14+.
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
            val bounds = screenSize()
            lastRotation = rotation()
            captureWidth = bounds.first; captureHeight = bounds.second
            capture = ScreenCaptureManager(projection, captureWidth, captureHeight, resources.configuration.densityDpi, rotation(),
                { width, height -> resize(width, height) }, { finishSession("Система завершила захват. Для новой сессии нажмите «Запустить переводчик».") })
            ocr = LatinOcrEngine()
            if (mode == AppMode.GEMINI) gemini = GeminiApiClient(ApiKeys.get(this)::read)
            translator = TranslationPipeline(mode, pair, cache, gemini)
            scope.launch {
                try {
                    val repository = SettingsRepository(this@ScreenTranslationService)
                    val settings = repository.settings.first()
                    overlay = OverlayController(this@ScreenTranslationService, settings, ::onTap, ::onSearch) { x, y ->
                        scope.launch { runCatching { repository.setPosition(x, y) } }
                    }
                    coordinator.start()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { finishSession("Не удалось создать плавающую кнопку. Проверьте разрешение.") }
            }
        } catch (e: Exception) {
            finishSession(if (e is SecurityException) "Согласие на захват недействительно. Повторите запуск." else "Не удалось запустить переводчик. Проверьте разрешения и направление перевода.")
        }
        return START_NOT_STICKY
    }
    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Перевод экрана", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Screen Translator работает").setContentText("Нажмите плавающую кнопку для перевода экрана")
            .setOngoing(true).setContentIntent(open).addAction(0, "Остановить", stop).build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
    private fun onTap() {
        val state = coordinator.state.value
        if (state.operation == Operation.SEARCH) { invalidate(); return }
        if (state.busy || state.phase == Phase.SHOWING) { invalidate(); return }
        if (!state.active) return
        val token = coordinator.begin()
        request = scope.launch {
            var frame: CapturedFrame? = null
            var blurred: android.graphics.Bitmap? = null
            try {
                notice?.cancel()
                overlay?.hideForCapture()
                // Let WindowManager compose removal before attaching the projection surface.
                delay(180)
                frame = capture?.snapshot() ?: error("Сессия завершена")
                overlay?.showButton()
                if (!coordinator.advance(token, Phase.RECOGNIZING)) return@launch
                val pipeline = translator!!
                coordinator.message(token, "Распознаю текст")
                val blocks = if (pipeline.needsImageOcr) null else ocr!!.recognize(frame.bitmap)
                ensureActive()
                if (blocks != null && blocks.isEmpty()) { coordinator.fail(token, "Не удалось прочитать текст на экране. Повторите снимок или выберите другую область."); showError(); return@launch }
                if (!coordinator.advance(token, Phase.TRANSLATING)) return@launch
                coordinator.message(token, if (mode == AppMode.GEMINI) "Перевожу через Gemini" else "Перевожу текст")
                val translated = if (blocks == null) pipeline.translateImage(frame.bitmap) else pipeline.translateBlocks(blocks)
                if (translated.isEmpty()) { coordinator.fail(token, "Не удалось найти текст на изображении."); showError(); return@launch }
                blurred = withContext(Dispatchers.Default) { BlurBackgroundRenderer.prepare(frame.bitmap) }
                ensureActive()
                if (coordinator.accepts(token)) {
                    overlay!!.show(frame, translated, blurred, mode.attribution + if (pipeline.needsImageOcr) " · координаты ≈" else "")
                    blurred = null
                    frame = null // Ownership moves to the overlay until it is removed.
                    coordinator.advance(token, Phase.SHOWING)
                }
            } catch (_: TimeoutCancellationException) {
                if (coordinator.accepts(token)) { coordinator.fail(token, "Нет свежего кадра. Повторите снимок."); showError() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (coordinator.accepts(token)) {
                    overlay?.hideResult()
                    coordinator.fail(token, when (e) { is GeminiException -> e.message!!; is SecretUnavailable -> "Не удалось расшифровать ключ. Сохраните его заново."; else -> "Не удалось распознать или перевести экран. Повторите снимок." }); showError()
                }
            } finally {
                frame?.bitmap?.recycle(); blurred?.recycle()
                if (coordinator.accepts(token) || !coordinator.state.value.busy) showButtonIfAllowed()
            }
        }
    }
    private fun onSearch() {
        if (!coordinator.state.value.active) {
            // Never reuse old projection consent. The visible launcher provides the existing permission flow.
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        invalidate()
        val token = coordinator.begin(Operation.SEARCH)
        request = scope.launch {
            var frame: CapturedFrame? = null
            try {
                notice?.cancel(); overlay?.hideForCapture(); delay(180)
                frame = capture?.snapshot() ?: error("Сессия завершена")
                if (!coordinator.advance(token, Phase.SELECTING)) return@launch
                val selected = ScreenSearchSession(token, frame, scope, coordinator, ocr!!, translator!!, gemini?.let(::GeminiSearchEngine),
                    SearchFiles(this@ScreenTranslationService), { overlay?.hideButton() }, { invalidate() })
                search = selected; SearchSessions.install(selected); frame = null
                // The user granted SYSTEM_ALERT_WINDOW and initiated this from a visible overlay.
                // Keep that overlay visible until the Activity attaches; browsers/shares launch from the Activity.
                overlay?.showButton(); delay(48)
                if (!coordinator.accepts(token)) return@launch
                startActivity(Intent(this@ScreenTranslationService, SearchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("session_id", selected.id))
                delay(5000)
                if (search === selected && !selected.attached) {
                    invalidate("Не удалось открыть поиск. Повторите долгое нажатие."); showError()
                }
            } catch (_: TimeoutCancellationException) {
                if (coordinator.accepts(token)) { invalidate("Нет свежего кадра. Повторите долгое нажатие."); showError() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (coordinator.accepts(token)) { invalidate("Не удалось открыть поиск. Повторите запуск переводчика."); showError() }
            } finally {
                frame?.bitmap?.recycle()
                if (coordinator.accepts(token) || !coordinator.state.value.busy) showButtonIfAllowed()
            }
        }
    }
    private fun showButtonIfAllowed() { if (search?.attached == true) overlay?.hideButton() else overlay?.showButton() }
    private fun showError() {
        notice?.cancel()
        notice = Toast.makeText(this, coordinator.state.value.message, Toast.LENGTH_LONG).also { it.show() }
    }
    private fun invalidate(message: String = "Работает") {
        coordinator.ready(message); request?.cancel(); request = null
        search?.dispose(); search = null
        overlay?.hideResult(); overlay?.showButton()
    }
    private fun invalidateForResize() {
        val hadSearch = search != null
        invalidate(if (hadSearch) "Размер экрана изменился. Повторите долгое нажатие для нового снимка." else "Работает")
        if (hadSearch) showError()
    }
    private fun resize(width: Int, height: Int) {
        if (destroyed || width <= 0 || height <= 0 || width == captureWidth && height == captureHeight) return
        captureWidth = width; captureHeight = height
        invalidateForResize(); capture?.resize(width, height, rotation()); overlay?.onConfigurationChanged()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateForResize()
        val (width, height) = screenSize(); captureWidth = width; captureHeight = height
        capture?.resize(width, height, rotation()); overlay?.onConfigurationChanged()
    }
    @Suppress("DEPRECATION")
    private fun rotation() = getSystemService(WindowManager::class.java).defaultDisplay.rotation
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    }
    private fun finishSession(message: String) {
        coordinator.stop(message); request?.cancel(); search?.dispose(); gemini?.close(); stopSelf()
    }
    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        if (coordinator.state.value.active) coordinator.stop()
        scope.cancel()
        if (Session.stopActiveSession === stopCallback) Session.stopActiveSession = null
        gemini?.close(); gemini = null
        notice?.cancel(); search?.dispose(); search = null
        overlay?.close(); overlay = null
        capture?.close(); capture = null
        // OCR owns the input until its non-cancellable SDK task settles. Close after that task.
        val pending = scope.coroutineContext[Job]
        val oldOcr = ocr; val oldTranslator = translator
        CoroutineScope(Dispatchers.Default).launch {
            pending?.join()
            runCatching { oldOcr?.close() }; runCatching { oldTranslator?.close() }; cache.clear()
        }
        ocr = null; translator = null
        unregisterReceiver(screenOff)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
