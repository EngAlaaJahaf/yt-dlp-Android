package com.ytdlpgui.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ytdlpgui.R

/**
 * حلقة العد التنازلي لشاشة البداية (مطابقة للموكب):
 * مسار رمادي خفيف + قوس تدرج أحمر + رقم العد في المنتصف.
 */
class ClipCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x22000000
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.vidmate_red)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.vidmate_red)
        isFakeBoldText = true
    }

    private val rect = RectF()

    /** من 0 (بداية) إلى 1 (انتهاء) */
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var countText = "3"
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val stroke = 4f * resources.displayMetrics.density
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        textPaint.textSize = 19f * resources.displayMetrics.density

        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) - stroke) / 2f - 2f

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        canvas.drawArc(rect, -90f, -360f * progress, false, arcPaint)

        val textY = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(countText, cx, textY, textPaint)
    }
}
