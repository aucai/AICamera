package com.aucai.aicamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Shutter button whose outer ring shows how many checks the shot passes (e.g. 4/5). */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Passed / total checks, or null before the first analysis. */
    var checks: Pair<Int, Int>? = null
        set(value) {
            if (field != value) {
                field = value
                contentDescription = if (value == null) "拍照" else "拍照，${value.first}/${value.second} 项达标"
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
        val c = checks
        if (c != null && c.second > 0) {
            ring.color = colorFor(c.first, c.second)
            canvas.drawArc(arc, -90f, 360f * c.first / c.second, false, ring)
        }
        val ir = r - 9 * dp
        canvas.drawCircle(cx, cy, if (isPressed) ir * 0.92f else ir, inner)
        if (c != null && c.second > 0) {
            text.textSize = ir * 0.5f
            canvas.drawText("${c.first}/${c.second}", cx, cy - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    companion object {
        fun colorFor(passed: Int, total: Int): Int = when {
            passed >= total -> Color.parseColor("#34C77B")
            passed >= total - 1 -> Color.parseColor("#FFC940")
            else -> Color.parseColor("#FF5A4E")
        }
    }
}
