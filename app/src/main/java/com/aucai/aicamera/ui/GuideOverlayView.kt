package com.aucai.aicamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.aucai.aicamera.core.AimPhase
import com.aucai.aicamera.core.AimState
import com.aucai.aicamera.core.GuidanceFrame
import com.aucai.aicamera.core.LevelState
import com.aucai.aicamera.core.RectN
import com.aucai.aicamera.core.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The guidance layer over the preview, kept deliberately sparse like a phone maker's camera:
 * a fixed centre ring with a target dot to aim at, two circles for the tilt angle, and a level
 * line that only shows up when the phone is nearly level. Assumes a FIT_CENTER preview with the
 * same aspect ratio as the analysed frames.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var gridOn = true
        set(value) { field = value; invalidate() }
    var level: LevelState? = null
        set(value) { field = value; invalidate() }

    /** Projects a direction in space onto the view with the latest phone orientation (null = can't). */
    var projector: ((FloatArray) -> Vec2?)? = null

    /** A recommended framing that is not tied to a tracked subject (drawn as is). */
    var staticFrame: RectN? = null
        set(value) { field = value; invalidate() }

    private var frame: GuidanceFrame? = null
    private var frameW = 3
    private var frameH = 4
    private var levelOkSince = 0L

    private val dp = resources.displayMetrics.density
    private val yellow = Color.parseColor("#FFC940")
    private val ringRadius = 30 * dp

    private val gridPaint = stroke(Color.argb(90, 255, 255, 255), 1f)
    private val ringPaint = stroke(Color.WHITE, 2f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val levelPaint = stroke(Color.WHITE, 2f).apply { strokeCap = Paint.Cap.ROUND }
    private val angleFixed = stroke(Color.WHITE, 2f)
    private val angleMoving = stroke(yellow, 2.5f)
    private val shadow = Color.argb(120, 0, 0, 0)
    private val framePaint = stroke(yellow, 2f).apply {
        pathEffect = DashPathEffect(floatArrayOf(10 * dp, 7 * dp), 0f)
    }

    private val image = RectF()
    private val arrow = Path()

    init {
        // Soft shadows keep thin white lines readable on bright scenes.
        for (p in listOf(ringPaint, dotPaint, arrowPaint, levelPaint, angleFixed, angleMoving, framePaint)) {
            p.setShadowLayer(3 * dp, 0f, 0f, shadow)
        }
    }

    fun update(frame: GuidanceFrame, width: Int, height: Int) {
        this.frame = frame
        frameW = width
        frameH = height
        invalidate()
    }

    fun clear() {
        frame = null
        invalidate()
    }

    /** Converts a display-normalized point to view pixels. */
    fun toView(nx: Float, ny: Float): Pair<Float, Float> {
        computeImageRect()
        return image.left + nx * image.width() to image.top + ny * image.height()
    }

    private fun computeImageRect() {
        val scale = min(width.toFloat() / frameW, height.toFloat() / frameH)
        val w = frameW * scale
        val h = frameH * scale
        image.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        computeImageRect()
        if (gridOn) drawGrid(canvas)
        val aim = frame?.composition?.aim
        when (aim?.phase) {
            AimPhase.ANGLE -> drawAngle(canvas, aim)
            AimPhase.GUIDE, AimPhase.HOLD, AimPhase.ZOOM, AimPhase.DONE -> drawAim(canvas, aim)
            else -> Unit
        }
        staticFrame?.let { r ->
            canvas.drawRect(
                image.left + r.left * image.width(), image.top + r.top * image.height(),
                image.left + r.right * image.width(), image.top + r.bottom * image.height(), framePaint,
            )
        }
        drawLevel(canvas, ringVisible = aim != null && aim.phase != AimPhase.IDLE)
    }

    private fun drawGrid(canvas: Canvas) {
        for (p in listOf(1f / 3f, 2f / 3f)) {
            val x = image.left + p * image.width()
            val y = image.top + p * image.height()
            canvas.drawLine(x, image.top, x, image.bottom, gridPaint)
            canvas.drawLine(image.left, y, image.right, y, gridPaint)
        }
    }

    /** Fixed ring in the middle; the dot is fixed to the scene. Bring the dot into the ring. */
    private fun drawAim(canvas: Canvas, aim: AimState) {
        val cx = image.centerX()
        val cy = image.centerY()
        val aligned = aim.phase != AimPhase.GUIDE
        ringPaint.color = if (aligned) yellow else Color.WHITE
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        // Pinned in space: redraw from the live orientation, not the (slower) last analysed frame.
        val world = aim.world
        val live = if (world != null) projector?.invoke(world) else null
        val t = live ?: aim.target ?: return
        val tx = image.left + t.x * image.width()
        val ty = image.top + t.y * image.height()
        // The cloud model's framing, fixed to the scene around the target.
        aim.view?.let { v ->
            val hw = v.x * image.width() / 2f
            val hh = v.y * image.height() / 2f
            canvas.drawRect(tx - hw, ty - hh, tx + hw, ty + hh, framePaint)
        }
        if (image.contains(tx, ty)) {
            dotPaint.color = if (aligned) yellow else Color.WHITE
            canvas.drawCircle(tx, ty, 7 * dp, dotPaint)
        } else {
            // Off screen: an arrow on the edge pointing where to turn.
            val ang = atan2(ty - cy, tx - cx)
            val reach = min(image.width(), image.height()) / 2f - 28 * dp
            val ax = cx + cos(ang) * reach
            val ay = cy + sin(ang) * reach
            val s = 12 * dp
            arrow.reset()
            arrow.moveTo(ax + cos(ang) * s, ay + sin(ang) * s)
            arrow.lineTo(ax + cos(ang + 2.4f) * s, ay + sin(ang + 2.4f) * s)
            arrow.lineTo(ax + cos(ang - 2.4f) * s, ay + sin(ang - 2.4f) * s)
            arrow.close()
            canvas.drawPath(arrow, arrowPaint)
        }
    }

    /** Two circles: the white one is fixed, the yellow one moves with the tilt. Make them meet. */
    private fun drawAngle(canvas: Canvas, aim: AimState) {
        val g = aim.angle ?: return
        val cx = image.centerX()
        val cy = image.centerY()
        canvas.drawCircle(cx, cy, ringRadius, angleFixed)
        val off = (g.offsetDeg * 5 * dp).coerceIn(-image.height() / 3f, image.height() / 3f)
        canvas.drawCircle(cx, cy + off, ringRadius * 0.75f, angleMoving)
    }

    /**
     * Like the iPhone level: shows up only when the phone is within 10° of level, turns yellow
     * once level and then fades away.
     */
    private fun drawLevel(canvas: Canvas, ringVisible: Boolean) {
        val lv = level ?: return
        if (lv.flat) return
        val roll = abs(lv.rollDeg)
        if (roll >= 10f) {
            levelOkSince = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        val ok = roll <= 1f
        if (ok) {
            if (levelOkSince == 0L) levelOkSince = now
            if (now - levelOkSince > 900) return
        } else {
            levelOkSince = 0L
        }
        levelPaint.color = if (ok) yellow else Color.WHITE
        val cx = image.centerX()
        val cy = image.centerY()
        val a = Math.toRadians(-lv.rollDeg.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val gap = if (ringVisible) ringRadius + 10 * dp else 14 * dp
        val len = image.width() * 0.16f
        canvas.drawLine(cx - dx * (gap + len), cy - dy * (gap + len), cx - dx * gap, cy - dy * gap, levelPaint)
        canvas.drawLine(cx + dx * gap, cy + dy * gap, cx + dx * (gap + len), cy + dy * (gap + len), levelPaint)
        if (!ok) {
            // Fixed reference ticks the line has to meet.
            canvas.drawLine(cx - gap - len - 14 * dp, cy, cx - gap - len - 4 * dp, cy, levelPaint)
            canvas.drawLine(cx + gap + len + 4 * dp, cy, cx + gap + len + 14 * dp, cy, levelPaint)
        }
        // Keep redrawing while the yellow line is fading out.
        if (ok) postInvalidateDelayed(100)
    }

    private fun stroke(c: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeWidth = widthDp * resources.displayMetrics.density
    }
}
