package com.aucai.aicamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Shutter button whose outer ring shows the live shot score (red → yellow → green). */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 0..100, or null when there is nothing to score yet. */
    var score: Int? = null
        set(value) {
            if (field != value) {
                field = value
                contentDescription = if (value == null) "拍照" else "拍照，当前评分$value"
                invalidate()
            }
        }

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * dp
        color = Color.parseColor("#3A3A40")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E0E10")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arc = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "拍照"
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - ring.strokeWidth
        arc.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, track)
        val s = score
        if (s != null) {
            ring.color = colorFor(s)
            canvas.drawArc(arc, -90f, 360f * s / 100f, false, ring)
        }
        val ir = r - 9 * dp
        canvas.drawCircle(cx, cy, if (isPressed) ir * 0.92f else ir, inner)
        if (s != null) {
            text.textSize = ir * 0.62f
            canvas.drawText(s.toString(), cx, cy - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    companion object {
        fun colorFor(score: Int): Int = when {
            score >= 80 -> Color.parseColor("#34C77B")
            score >= 60 -> Color.parseColor("#FFC940")
            else -> Color.parseColor("#FF5A4E")
        }
    }
}
