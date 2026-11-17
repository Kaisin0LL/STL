package com.screentranslator.overlay

import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import kotlin.math.max

/** Precomputed once off Main per snapshot. onDraw only clips and composites this bitmap. */
object BlurBackgroundRenderer {
    fun prepare(source: Bitmap): Bitmap = if (Build.VERSION.SDK_INT >= 31) runCatching { hardware(source) }.getOrElse { compatible(source) } else compatible(source)
    @RequiresApi(31)
    private fun hardware(source: Bitmap): Bitmap {
        val reader = ImageReader.newInstance(source.width, source.height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val node = RenderNode("blurred-capture")
        try {
            node.setPosition(0, 0, source.width, source.height)
            node.setRenderEffect(RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP))
            val canvas = node.beginRecording(); canvas.drawBitmap(source, 0f, 0f, null); node.endRecording()
            renderer.setSurface(reader.surface); renderer.setContentRoot(node)
            check(renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw() == HardwareRenderer.SYNC_OK)
            val image = reader.acquireNextImage() ?: error("No blur output")
            image.use {
                val buffer = it.hardwareBuffer ?: error("No hardware buffer")
                buffer.use {
                    val wrapped = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) ?: error("No bitmap")
                    return try { requireNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false)) } finally { wrapped.recycle() }
                }
            }
        } finally { renderer.destroy(); node.discardDisplayList(); reader.close() }
    }
    private fun compatible(source: Bitmap): Bitmap {
        val w = max(1, source.width / 4); val h = max(1, source.height / 4)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        try {
            val pixels = IntArray(w * h); small.getPixels(pixels, 0, w, 0, 0, w, h)
            val horizontal = IntArray(pixels.size); val output = IntArray(pixels.size)
            fun pass(input: IntArray, result: IntArray, rows: Int, columns: Int, vertical: Boolean) {
                fun index(row: Int, column: Int) = if (vertical) column * w + row else row * w + column
                for (row in 0 until rows) {
                    var red = 0; var green = 0; var blue = 0
                    for (i in -4..4) { val p = input[index(row, i.coerceIn(0, columns - 1))]; red += p shr 16 and 255; green += p shr 8 and 255; blue += p and 255 }
                    for (column in 0 until columns) {
                        result[index(row, column)] = Color.rgb(red / 9, green / 9, blue / 9)
                        val old = input[index(row, (column - 4).coerceIn(0, columns - 1))]
                        val next = input[index(row, (column + 5).coerceIn(0, columns - 1))]
                        red += (next shr 16 and 255) - (old shr 16 and 255)
                        green += (next shr 8 and 255) - (old shr 8 and 255)
                        blue += (next and 255) - (old and 255)
                    }
                }
            }
            pass(pixels, horizontal, h, w, false); pass(horizontal, output, w, h, true)
            return createBitmap(w, h).apply { setPixels(output, 0, w, 0, 0, w, h) }
        } finally { if (small !== source) small.recycle() }
    }
}
