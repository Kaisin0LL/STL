package com.screentranslator.capture

import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.screentranslator.core.FrameGeometry
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException

data class CapturedFrame(val bitmap: Bitmap, val geometry: FrameGeometry)
/** One VirtualDisplay per consent. The surface is attached only for user-requested snapshots. */
class ScreenCaptureManager(
    private val projection: MediaProjection,
    width: Int, height: Int, private val density: Int, rotation: Int,
    private val onResize: (Int, Int) -> Unit,
    private val onStopped: () -> Unit
) : AutoCloseable {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var geometry = FrameGeometry(width, height, rotation, 0)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var pending: CancellableContinuation<CapturedFrame>? = null
    private var afterNanos = 0L
    @Volatile private var closed = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { onStopped() }
        override fun onCapturedContentResize(width: Int, height: Int) { onResize(width, height) }
    }
    init {
        projection.registerCallback(callback, Handler(android.os.Looper.getMainLooper()))
        try {
            reader = makeReader(width, height)
            display = projection.createVirtualDisplay("ScreenTranslator", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler)
        } catch (e: Exception) { close(); throw e }
    }
    private fun makeReader(width: Int, height: Int) = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).also {
        it.setOnImageAvailableListener(listener@{ source ->
            if (closed || source !== reader) return@listener
            var image: Image? = null
            var receiving: CancellableContinuation<CapturedFrame>? = null
            try {
                image = source.acquireLatestImage()
                val request = pending
                if (image != null && request != null && request.isActive && image.timestamp > afterNanos && !closed) {
                    receiving = request
                    pending = null
                    display?.surface = null
                    val bitmap = convert(image)
                    request.resume(CapturedFrame(bitmap, geometry)) { _, frame, _ -> frame.bitmap.recycle() }
                }
            } catch (e: Exception) {
                (receiving ?: pending)?.let { if (it.isActive) it.resumeWithException(e) }; pending = null
                runCatching { display?.surface = null }
            } finally { image?.close() }
        }, handler)
    }
    private fun convert(image: Image): Bitmap {
        val plane = image.planes[0]
        require(plane.pixelStride == 4) { "Неподдерживаемый формат снимка" }
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = createBitmap(paddedWidth, image.height)
        try {
            // Some producers omit padding after the final row; copy pixels row by row in that case.
            val buffer = plane.buffer
            if (buffer.remaining() >= plane.rowStride * image.height) padded.copyPixelsFromBuffer(buffer)
            else {
                val pixels = IntArray(image.width * image.height)
                val base = buffer.position()
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val i = base + y * plane.rowStride + x * plane.pixelStride
                    val r = buffer.get(i).toInt() and 255; val g = buffer.get(i + 1).toInt() and 255
                    val b = buffer.get(i + 2).toInt() and 255; val a = buffer.get(i + 3).toInt() and 255
                    pixels[y * image.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                padded.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            }
            return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { if (it !== padded) padded.recycle() }
        } catch (e: Exception) { padded.recycle(); throw e }
    }
    suspend fun snapshot(): CapturedFrame = withTimeout(5000) {
        suspendCancellableCoroutine { continuation ->
            handler.post {
                if (closed) { continuation.cancel(); return@post }
                pending?.cancel()
                reader?.acquireLatestImage()?.close()
                pending = continuation
                afterNanos = System.nanoTime()
                try { display?.surface = reader?.surface }
                catch (e: Exception) { pending = null; continuation.resumeWithException(e) }
            }
            continuation.invokeOnCancellation {
                handler.post { if (pending === continuation) { pending = null; display?.surface = null } }
            }
        }
    }
    fun resize(width: Int, height: Int, rotation: Int) {
        if (width <= 0 || height <= 0 || closed) return
        handler.post {
            if (closed || (width == geometry.width && height == geometry.height && rotation == geometry.rotation)) return@post
            pending?.cancel(); pending = null
            display?.surface = null
            reader?.close()
            geometry = FrameGeometry(width, height, rotation, geometry.generation + 1)
            reader = makeReader(width, height)
            display?.resize(width, height, density)
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { projection.unregisterCallback(callback) }
        handler.post {
            pending?.cancel(); pending = null
            runCatching { display?.release() }; display = null
            runCatching { reader?.close() }; reader = null
            runCatching { projection.stop() }
            thread.quitSafely()
        }
    }
}
