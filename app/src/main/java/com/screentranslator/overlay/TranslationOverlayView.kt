package com.screentranslator.overlay

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withClip
import com.screentranslator.R
import com.screentranslator.capture.CapturedFrame
import com.screentranslator.core.*
import com.screentranslator.engine.TranslatedBlock
import kotlin.math.max
import kotlin.math.min

/** Programmatic-only view. The controller owns its bitmap until this view is removed. */
@android.annotation.SuppressLint("ViewConstructor")
class TranslationOverlayView(context: Context, private val frame: CapturedFrame,
    private val blocks: List<TranslatedBlock>, private val displayWidth: Int, private val displayHeight: Int,
    private val onBlock: (String) -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val background = Paint().apply { color = Color.rgb(19, 23, 30) }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12 * density }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val location = IntArray(2)
    private var layoutKey: List<Int>? = null
    private var bitmapBounds = RectF()
    private var hitBoxes = emptyList<Pair<RectF, String>>()
    private data class Label(val bounds: RectF, val padding: Float, val layout: StaticLayout, val overflow: RectF?)
    private var labels = emptyList<Label>()
    private var footerBounds = RectF()
    private val footer = context.getString(R.string.google_attribution)
    init { contentDescription = "Переведённый снимок. Нажмите блок для полного текста, плавающую кнопку для закрытия."; isClickable = true }
    private fun prepareLayout() {
        val key = listOf(width, height, location[0], location[1])
        if (layoutKey == key) return
        layoutKey = key
        val mapper = CoordinateMapper(frame.geometry.width, frame.geometry.height,
            Box(-location[0].toFloat(), -location[1].toFloat(), displayWidth - location[0].toFloat(), displayHeight - location[1].toFloat()))
        bitmapBounds = mapper.map(Box(0f, 0f, frame.geometry.width.toFloat(), frame.geometry.height.toFloat())).rect()
        hitBoxes = blocks.map { mapper.map(it.original.bounds).rect() to it.translation }
        val drawn = mutableListOf<RectF>()
        labels = hitBoxes.mapNotNull { (rect, translation) ->
            if (rect.width() < 2 || rect.height() < 2 || drawn.any { RectF.intersects(it, rect) }) return@mapNotNull null
            drawn.add(rect)
            val pad = min(3 * density, min(rect.width(), rect.height()) / 8)
            val textWidth = max(1, (rect.width() - 2 * pad).toInt())
            val textHeight = max(1f, rect.height() - 2 * pad)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            var size = min(22 * density, max(10 * density, rect.height() / 2))
            var layout: StaticLayout
            while (true) {
                paint.textSize = size
                layout = StaticLayout.Builder.obtain(translation, 0, translation.length, paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
                if (layout.height <= textHeight || size <= 10 * density) break
                size = max(10 * density, size - density)
            }
            val mark = if (layout.height > textHeight) RectF(max(rect.left, rect.right - 18 * density), max(rect.top, rect.bottom - 12 * density), rect.right, rect.bottom) else null
            Label(rect, pad, layout, mark)
        }
        val footerWidth = captionPaint.measureText(footer) + 32 * density
        footerBounds = RectF(12 * density, height - 76 * density, min(width - 12 * density, footerWidth), height - 44 * density)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(location); prepareLayout()
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(frame.bitmap, null, bitmapBounds, bitmapPaint)
        hitBoxes.forEach { canvas.drawRect(it.first, background) }
        // Do not grow labels into their neighbours. Every OCR block remains available by tapping.
        labels.forEach { label ->
            canvas.withClip(label.bounds) {
                translate(label.bounds.left + label.padding, label.bounds.top + label.padding)
                label.layout.draw(this)
            }
            label.overflow?.let { mark ->
                canvas.withClip(label.bounds) {
                    drawRect(mark, background); drawText("…", mark.left + 3 * density, mark.bottom - density, captionPaint)
                }
            }
        }
        canvas.drawRoundRect(footerBounds, 8 * density, 8 * density, background)
        canvas.drawText(footer, footerBounds.left + 8 * density, footerBounds.top + 21 * density, captionPaint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            val hits = hitBoxes.filter { it.first.contains(event.x, event.y) }
            onBlock(if (hits.isNotEmpty()) hits.joinToString("\n\n") { it.second } else blocks.joinToString("\n\n") { it.translation })
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun Box.rect() = RectF(left, top, right, bottom)
}
