package com.screentranslator.search

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.screentranslator.core.*

/** The viewport is local to this View, below system insets and outside the action panel. */
@android.annotation.SuppressLint("ViewConstructor")
class SelectionView(context: Context, bitmap: Bitmap, initial: Box?, private val onSelection: (Box, Boolean) -> Unit) : View(context) {
    private var bitmap: Bitmap? = bitmap
    private var selected = initial
    private var mapper: CoordinateMapper? = null
    private val imageRect = RectF()
    private val selectionRect = RectF()
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(168, 199, 250); style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border.color }
    private val density = resources.displayMetrics.density
    private var anchorX = 0f
    private var anchorY = 0f
    private var original = Box(0f, 0f, 0f, 0f)
    private var handle = SelectionHandle.NEW
    init { isClickable = true; contentDescription = "Выделение области. Проведите пальцем, затем перемещайте рамку или её края." }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val image = bitmap ?: return
        if (w <= 0 || h <= 0) return
        mapper = CoordinateMapper(image.width, image.height, Box(0f, 0f, w.toFloat(), h.toFloat()))
        val b = mapper!!.imageBounds; imageRect.set(b.left, b.top, b.right, b.bottom)
        updateRectangle()
    }
    private fun updateRectangle() {
        val mapped = selected?.let { mapper?.map(it) }
        if (mapped != null) selectionRect.set(mapped.left, mapped.top, mapped.right, mapped.bottom)
        else selectionRect.setEmpty()
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 19, 24))
        bitmap?.let { canvas.drawBitmap(it, null, imageRect, picture) }
        if (selected == null) { canvas.drawRect(imageRect, shade); return }
        val r = selectionRect
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, r.top, shade)
        canvas.drawRect(imageRect.left, r.bottom, imageRect.right, imageRect.bottom, shade)
        canvas.drawRect(imageRect.left, r.top, r.left, r.bottom, shade)
        canvas.drawRect(r.right, r.top, imageRect.right, r.bottom, shade)
        canvas.drawRect(r, border)
        canvas.drawCircle(r.left, r.top, 5 * density, dot); canvas.drawCircle(r.right, r.top, 5 * density, dot)
        canvas.drawCircle(r.left, r.bottom, 5 * density, dot); canvas.drawCircle(r.right, r.bottom, 5 * density, dot)
        canvas.drawCircle(r.centerX(), r.top, 4 * density, dot); canvas.drawCircle(r.centerX(), r.bottom, 4 * density, dot)
        canvas.drawCircle(r.left, r.centerY(), 4 * density, dot); canvas.drawCircle(r.right, r.centerY(), 4 * density, dot)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val transform = mapper ?: return false
        val bounds = transform.imageBounds
        val x = event.x.coerceIn(bounds.left, bounds.right)
        val y = event.y.coerceIn(bounds.top, bounds.bottom)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                anchorX = x; anchorY = y
                val current = selected?.let(transform::map)
                original = current ?: Box(x, y, x, y)
                handle = SelectionGeometry.hit(current, x, y, 16 * density)
                if (handle == SelectionHandle.NEW) { selected = transform.unmap(Box(x, y, x, y)); updateRectangle() }
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val box = when (handle) {
                    SelectionHandle.NEW -> SelectionGeometry.rectangle(anchorX, anchorY, x, y, bounds)
                    SelectionHandle.MOVE -> SelectionGeometry.move(original, x - anchorX, y - anchorY, bounds)
                    else -> SelectionGeometry.resize(original, handle, x, y, bounds)
                }
                selected = transform.unmap(box)
                updateRectangle()
                onSelection(selected!!, box.width >= 24 * density && box.height >= 24 * density)
                if (event.actionMasked == MotionEvent.ACTION_UP) { parent?.requestDisallowInterceptTouchEvent(false); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun clearFrame() { bitmap = null; invalidate() }
}
