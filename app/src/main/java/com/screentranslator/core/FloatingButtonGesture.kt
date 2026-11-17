package com.screentranslator.core

import kotlin.math.abs

enum class ButtonRelease { TAP, DRAG, NONE }
/** Timer is supplied by the View; movement permanently disarms long press for this gesture. */
class FloatingButtonGesture(private val slop: Int) {
    private var pressed = false
    var dragging = false
        private set
    private var held = false
    fun down() { pressed = true; dragging = false; held = false }
    fun move(dx: Float, dy: Float) {
        if (pressed && !held && (abs(dx) > slop || abs(dy) > slop)) dragging = true
    }
    fun longPress(): Boolean {
        if (!pressed || dragging || held) return false
        held = true; return true
    }
    fun release(): ButtonRelease {
        val result = when { !pressed || held -> ButtonRelease.NONE; dragging -> ButtonRelease.DRAG; else -> ButtonRelease.TAP }
        pressed = false; return result
    }
    fun cancel() { pressed = false }
}
