package com.screentranslator.core

import kotlin.math.min

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}
/** Frame is already in current display orientation. No second rotation of OCR coordinates. */
data class FrameGeometry(val width: Int, val height: Int, val rotation: Int, val generation: Long)
class CoordinateMapper(private val frameWidth: Int, private val frameHeight: Int, private val viewport: Box) {
    init { require(frameWidth > 0 && frameHeight > 0 && viewport.width > 0 && viewport.height > 0) }
    val scale = min(viewport.width / frameWidth, viewport.height / frameHeight)
    val offsetX = viewport.left + (viewport.width - frameWidth * scale) / 2
    val offsetY = viewport.top + (viewport.height - frameHeight * scale) / 2
    val imageBounds get() = Box(offsetX, offsetY, offsetX + frameWidth * scale, offsetY + frameHeight * scale)
    fun unmap(box: Box) = Box(
        ((box.left - offsetX) / scale).coerceIn(0f, frameWidth.toFloat()),
        ((box.top - offsetY) / scale).coerceIn(0f, frameHeight.toFloat()),
        ((box.right - offsetX) / scale).coerceIn(0f, frameWidth.toFloat()),
        ((box.bottom - offsetY) / scale).coerceIn(0f, frameHeight.toFloat())
    )
    fun map(box: Box) = Box(
        (offsetX + box.left * scale).coerceIn(viewport.left, viewport.right),
        (offsetY + box.top * scale).coerceIn(viewport.top, viewport.bottom),
        (offsetX + box.right * scale).coerceIn(viewport.left, viewport.right),
        (offsetY + box.bottom * scale).coerceIn(viewport.top, viewport.bottom)
    )
}
