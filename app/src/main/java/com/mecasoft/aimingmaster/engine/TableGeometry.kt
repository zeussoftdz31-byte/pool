package com.mecasoft.aimingmaster.engine

import android.graphics.RectF

class TableGeometry(
    val playRect: RectF,
    val innerRect: RectF,
    val ballRadius: Float,
    val pocketRadius: Float,
    val edgeMargin: Float,
    val midMargin: Float,
    val screenWidth: Float,
    val screenHeight: Float
) {
    fun isPocketZone(hitX: Float, hitY: Float): Boolean {
        // Vertical cushions (left or right edges)
        if (hitX <= playRect.left + 2f || hitX >= playRect.right - 2f) {
            if (hitY < playRect.top + edgeMargin || hitY > playRect.bottom - edgeMargin) {
                return true
            }
        }
        // Horizontal cushions (top or bottom edges)
        if (hitY <= playRect.top + 2f || hitY >= playRect.bottom - 2f) {
            // Corner pockets
            if (hitX < playRect.left + edgeMargin || hitX > playRect.right - edgeMargin) {
                return true
            }
            // Center pockets
            val centerX = playRect.centerX()
            if (hitX >= centerX - midMargin && hitX <= centerX + midMargin) {
                return true
            }
        }
        return false
    }

    companion object {
        fun compute(
            screenW: Float,
            screenH: Float,
            zoom: Float = 1.0f,
            offsetX: Float = 0f,
            offsetY: Float = 0f
        ): TableGeometry {
            val isLandscape = screenW > screenH
            val baseW = if (isLandscape) screenW else screenH
            val baseH = if (isLandscape) screenH else screenW

            val rawTableTop = 0.237037f * baseH
            val rawTableBottom = 0.912963f * baseH
            val tableH = (rawTableBottom - rawTableTop) * zoom
            val tableW = tableH * 2.0f

            val centerX = (baseW / 2.0f) + offsetX
            val centerY = ((rawTableTop + rawTableBottom) / 2.0f) + offsetY

            val left = centerX - (tableW / 2.0f)
            val right = centerX + (tableW / 2.0f)
            val top = centerY - (tableH / 2.0f)
            val bottom = centerY + (tableH / 2.0f)

            val playRect = RectF(left, top, right, bottom)
            val ballRadius = 0.03f * tableH
            val pocketRadius = ballRadius * (12f / 11f)
            val edgeMargin = 0.0739726f * tableH
            val midMargin = 0.063f * tableH

            val innerRect = RectF(
                playRect.left + ballRadius,
                playRect.top + ballRadius,
                playRect.right - ballRadius,
                playRect.bottom - ballRadius
            )

            return TableGeometry(
                playRect = playRect,
                innerRect = innerRect,
                ballRadius = ballRadius,
                pocketRadius = pocketRadius,
                edgeMargin = edgeMargin,
                midMargin = midMargin,
                screenWidth = screenW,
                screenHeight = screenH
            )
        }
    }
}
