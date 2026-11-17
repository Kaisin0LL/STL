package com.screentranslator.core

import kotlin.math.*

data class PixelCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}
enum class SelectionHandle { NEW, MOVE, NW, N, NE, E, SE, S, SW, W }
object SelectionGeometry {
    fun rectangle(x1: Float, y1: Float, x2: Float, y2: Float, bounds: Box) = Box(
        min(x1, x2).coerceIn(bounds.left, bounds.right), min(y1, y2).coerceIn(bounds.top, bounds.bottom),
        max(x1, x2).coerceIn(bounds.left, bounds.right), max(y1, y2).coerceIn(bounds.top, bounds.bottom)
    )
    fun move(box: Box, dx: Float, dy: Float, bounds: Box): Box {
        val x = dx.coerceIn(bounds.left - box.left, bounds.right - box.right)
        val y = dy.coerceIn(bounds.top - box.top, bounds.bottom - box.bottom)
        return Box(box.left + x, box.top + y, box.right + x, box.bottom + y)
    }
    fun resize(box: Box, handle: SelectionHandle, x: Float, y: Float, bounds: Box): Box {
        val left = if (handle in setOf(SelectionHandle.W, SelectionHandle.NW, SelectionHandle.SW)) x else box.left
        val right = if (handle in setOf(SelectionHandle.E, SelectionHandle.NE, SelectionHandle.SE)) x else box.right
        val top = if (handle in setOf(SelectionHandle.N, SelectionHandle.NW, SelectionHandle.NE)) y else box.top
        val bottom = if (handle in setOf(SelectionHandle.S, SelectionHandle.SW, SelectionHandle.SE)) y else box.bottom
        return rectangle(left, top, right, bottom, bounds)
    }
    fun hit(box: Box?, x: Float, y: Float, tolerance: Float): SelectionHandle {
        if (box == null) return SelectionHandle.NEW
        val l = abs(x - box.left) <= tolerance; val r = abs(x - box.right) <= tolerance
        val t = abs(y - box.top) <= tolerance; val b = abs(y - box.bottom) <= tolerance
        return when {
            l && t -> SelectionHandle.NW; r && t -> SelectionHandle.NE
            l && b -> SelectionHandle.SW; r && b -> SelectionHandle.SE
            x in box.left..box.right && t -> SelectionHandle.N
            x in box.left..box.right && b -> SelectionHandle.S
            y in box.top..box.bottom && l -> SelectionHandle.W
            y in box.top..box.bottom && r -> SelectionHandle.E
            x in box.left..box.right && y in box.top..box.bottom -> SelectionHandle.MOVE
            else -> SelectionHandle.NEW
        }
    }
    /** Outward rounding includes the selected edge pixels, always within the original bitmap. */
    fun crop(box: Box, width: Int, height: Int, minimum: Int = 12): PixelCrop? {
        if (width <= 0 || height <= 0 || minimum <= 0 || listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() }) return null
        val b = rectangle(box.left, box.top, box.right, box.bottom, Box(0f, 0f, width.toFloat(), height.toFloat()))
        val crop = PixelCrop(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
        return crop.takeIf { it.width >= minimum && it.height >= minimum }
    }
}
