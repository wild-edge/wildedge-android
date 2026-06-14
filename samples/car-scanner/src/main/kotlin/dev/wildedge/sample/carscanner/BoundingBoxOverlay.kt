package dev.wildedge.sample.carscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#00E676")
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        textSize = 28f
        isAntiAlias = true
    }

    var boxes: List<Pair<BoundingBox, String>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((box, label) in boxes) {
            val left = (box.x * width).toFloat()
            val top = (box.y * height).toFloat()
            val right = ((box.x + box.width) * width).toFloat()
            val bottom = ((box.y + box.height) * height).toFloat()
            canvas.drawRect(left, top, right, bottom, paint)
            canvas.drawText(label, left + 4f, (top - 6f).coerceAtLeast(textPaint.textSize), textPaint)
        }
    }
}
