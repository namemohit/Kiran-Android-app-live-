package com.kiran.wrapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View

data class DetectionResult(
    val box: RectF,
    val label: String,
    val score: Float
)

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
    }

    private var results: List<DetectionResult> = emptyList()
    private val transformMatrix = android.graphics.Matrix()

    fun setTransform(matrix: android.graphics.Matrix) {
        transformMatrix.set(matrix)
        invalidate()
    }

    fun setResults(newResults: List<DetectionResult>) {
        results = newResults
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (results.isEmpty()) return
        
        results.forEach { result ->
            // Map 0.0-1.0 detection to the current view dimensions (1080x1920 logical)
            val rect = RectF(
                result.box.left * width.toFloat(),
                result.box.top * height.toFloat(),
                result.box.right * width.toFloat(),
                result.box.bottom * height.toFloat()
            )

            // No transformation needed as AI capture matches screen space

            canvas.drawRect(rect, boxPaint)
            val label = "${result.label} ${(result.score * 100).toInt()}%"
            canvas.drawText(label, rect.left, rect.top - 10f, textPaint)
        }
    }
}
