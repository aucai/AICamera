package com.aucai.aicamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Shutter button; its ring turns yellow when the composition assistant says the shot is framed. */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var ready = false
        set(value) {
            if (field != value) {
                field = value
                contentDescription = if (value) "拍照，构图已完成" else "拍照"
                invalidate()
            }
        }

    private val dp = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * dp
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "拍照"
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - ring.strokeWidth
        ring.color = if (ready) READY else Color.WHITE
        canvas.drawCircle(cx, cy, r, ring)
        val ir = r - 8 * dp
        canvas.drawCircle(cx, cy, if (isPressed) ir * 0.9f else ir, inner)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    companion object {
        val READY = Color.parseColor("#FFC940")

        fun colorFor(passed: Int, total: Int): Int = when {
            passed >= total -> Color.parseColor("#34C77B")
            passed >= total - 1 -> Color.parseColor("#FFC940")
            else -> Color.parseColor("#FF5A4E")
        }
    }
}
