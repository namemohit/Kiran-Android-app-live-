package com.example.broadcastcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A simple overlay view to display the logical center (0,0) reference.
 * Draws a green crosshair at the exact center of the screen.
 */
class CenterReferenceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val crosshairPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 24f
        isAntiAlias = true
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width > 0 && height > 0) {
            // Draw Border
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

            val centerX = width / 2f
            val centerY = height / 2f
            val crosshairSize = 40f

            // Draw Crosshair
            canvas.drawLine(centerX - crosshairSize, centerY, centerX + crosshairSize, centerY, crosshairPaint)
            canvas.drawLine(centerX, centerY - crosshairSize, centerX, centerY + crosshairSize, crosshairPaint)

            // Draw Label
            canvas.drawText("CENTER (0,0)", centerX + 10f, centerY - 10f, textPaint)
        }
    }
}
