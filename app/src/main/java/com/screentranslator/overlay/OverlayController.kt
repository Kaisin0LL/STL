package com.screentranslator.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.*
import com.screentranslator.capture.CapturedFrame
import com.screentranslator.core.SessionState
import com.screentranslator.core.Phase
import com.screentranslator.core.FloatingButtonGesture
import com.screentranslator.core.ButtonRelease
import com.screentranslator.data.Settings
import com.screentranslator.engine.TranslatedBlock
import kotlin.math.max

class OverlayController(private val context: Context, settings: Settings, private val onTap: () -> Unit, private val onSearch: () -> Unit,
    private val onPosition: (Float, Float) -> Unit) : AutoCloseable {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val dp = context.resources.displayMetrics.density
    private val size = (56 * dp).toInt()
    private var fractionX = settings.x
    private var fractionY = settings.y
    private var result: View? = null
    private var detail: View? = null
    private var frame: CapturedFrame? = null
    private fun translateLabel() = android.text.SpannableString("文\nGoogle").apply {
        setSpan(android.text.style.AbsoluteSizeSpan(10, true), 2, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private val button = TextView(context).apply {
        text = translateLabel(); textSize = 24f; gravity = Gravity.CENTER
        setTextColor(Color.rgb(168, 199, 250))
        background = GradientDrawable().apply { setColor(Color.rgb(28, 34, 45)); cornerRadius = 28 * dp; setStroke((2 * dp).toInt(), Color.rgb(168, 199, 250)) }
        elevation = 8 * dp
        contentDescription = "Перевести экран с Google Translate"
        setOnClickListener { onTap() }
        setOnLongClickListener { onSearch(); true }
    }
    private val buttonParams = params(size, size)
    init { position(); wm.addView(button, buttonParams); installDrag() }
    // Coordinates are physical display coordinates, independent of the UI text direction.
    @android.annotation.SuppressLint("RtlHardcoded")
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) { setFitInsetsTypes(0); layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    }
    @Suppress("DEPRECATION")
    fun displayBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else {
        val metrics = android.util.DisplayMetrics(); wm.defaultDisplay.getRealMetrics(metrics); Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
    private fun safeBounds(): Rect {
        val bounds = displayBounds()
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            bounds.set(bounds.left + insets.left, bounds.top + insets.top, bounds.right - insets.right, bounds.bottom - insets.bottom)
        } else bounds.inset(0, (28 * dp).toInt())
        return bounds
    }
    private fun position() {
        val bounds = safeBounds()
        buttonParams.x = bounds.left + (fractionX.coerceIn(0f, 1f) * max(0, bounds.width() - size)).toInt()
        buttonParams.y = bounds.top + (fractionY.coerceIn(0f, 1f) * max(0, bounds.height() - size)).toInt()
    }
    private var cancelGesture: (() -> Unit)? = null
    // Standard performClick/performLongClick preserve accessibility actions. Drag cancels the timer.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        val gesture = FloatingButtonGesture(ViewConfiguration.get(context).scaledTouchSlop)
        val longPress = Runnable {
            if (gesture.longPress()) button.performLongClick()
        }
        cancelGesture = { gesture.cancel(); button.removeCallbacks(longPress) }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = buttonParams.x; startY = buttonParams.y
                    gesture.down()
                    button.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    gesture.move(dx, dy)
                    if (gesture.dragging) {
                        button.removeCallbacks(longPress)
                        val b = safeBounds()
                        buttonParams.x = (startX + dx.toInt()).coerceIn(b.left, max(b.left, b.right - size))
                        buttonParams.y = (startY + dy.toInt()).coerceIn(b.top, max(b.top, b.bottom - size))
                        wm.updateViewLayout(button, buttonParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    button.removeCallbacks(longPress)
                    val released = gesture.release()
                    if (released == ButtonRelease.TAP) view.performClick()
                    else if (released == ButtonRelease.DRAG) {
                        val b = safeBounds()
                        fractionX = (buttonParams.x - b.left).toFloat() / max(1, b.width() - size)
                        fractionY = (buttonParams.y - b.top).toFloat() / max(1, b.height() - size)
                        onPosition(fractionX, fractionY)
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelGesture?.invoke() }
            }
            true
        }
    }
    fun update(state: SessionState) {
        button.text = if (state.busy) "…" else if (state.phase == Phase.SHOWING) "×" else translateLabel()
        button.contentDescription = if (state.busy) "Отменить перевод" else if (state.phase == Phase.SHOWING) "Скрыть перевод" else "Перевести экран с Google Translate. Удерживайте для поиска на экране"
    }
    fun hideForCapture() { hideResult(); button.visibility = View.INVISIBLE }
    fun hideButton() { button.visibility = View.INVISIBLE }
    fun showButton() { button.visibility = View.VISIBLE }
    fun show(frame: CapturedFrame, blocks: List<TranslatedBlock>) {
        hideResult()
        this.frame = frame
        val bounds = displayBounds()
        val view = TranslationOverlayView(context, frame, blocks, bounds.width(), bounds.height(), ::showFullText)
        result = view
        wm.addView(view, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        raiseButton()
    }
    private fun raiseButton() {
        if (button.isAttachedToWindow) wm.removeViewImmediate(button)
        wm.addView(button, buttonParams)
    }
    private fun showFullText(text: String) {
        remove(detail); detail = null
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(19, 23, 30))
            val b = safeBounds(); val display = displayBounds()
            setPadding((20 * dp).toInt() + b.left, b.top + (12 * dp).toInt(), (20 * dp).toInt(), display.bottom - b.bottom + (16 * dp).toInt())
        }
        layout.addView(Button(context).apply { this.text = "Назад к снимку"; setOnClickListener { remove(detail); detail = null } })
        layout.addView(TextView(context).apply { setText(com.screentranslator.R.string.google_attribution); setTextColor(Color.LTGRAY); textSize = 12f })
        val scroll = ScrollView(context)
        scroll.addView(TextView(context).apply { this.text = text; textSize = 20f; setTextColor(Color.WHITE); setPadding(0, (20 * dp).toInt(), 0, (72 * dp).toInt()) })
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        detail = layout
        wm.addView(layout, params(-1, -1)); raiseButton()
    }
    fun hideResult() {
        remove(detail); detail = null
        remove(result); result = null
        frame?.bitmap?.recycle(); frame = null
    }
    fun onConfigurationChanged() { hideResult(); position(); if (button.isAttachedToWindow) wm.updateViewLayout(button, buttonParams) }
    private fun remove(view: View?) {
        if (view?.isAttachedToWindow == true) runCatching { wm.removeViewImmediate(view) }
    }
    override fun close() { cancelGesture?.invoke(); hideResult(); remove(button) }
}
