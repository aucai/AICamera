package com.aucai.aicamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aucai.aicamera.core.GridMode
import com.aucai.aicamera.core.GuidanceFrame
import com.aucai.aicamera.core.LevelState
import com.aucai.aicamera.core.Mode
import com.aucai.aicamera.core.PoseIdx
import com.aucai.aicamera.core.SubjectKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the guidance layer over the camera preview. Assumes the preview is shown
 * FIT_CENTER with the same aspect ratio as the analysed frames.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var mode = Mode.SMART
        set(value) { field = value; invalidate() }
    var grid = GridMode.THIRDS
        set(value) { field = value; invalidate() }
    var level: LevelState? = null
        set(value) { field = value; invalidate() }

    private var frame: GuidanceFrame? = null
    private var frameW = 3
    private var frameH = 4

    private val dp = resources.displayMetrics.density
    private val accent = Color.parseColor("#FFC940")
    private val good = Color.parseColor("#34C77B")

    private val gridPaint = stroke(Color.argb(110, 255, 255, 255), 1f)
    private val levelPaint = stroke(Color.WHITE, 2.5f).apply { strokeCap = Paint.Cap.ROUND }
    private val targetPaint = stroke(accent, 2.5f)
    private val arrowPaint = stroke(accent, 2.5f).apply {
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
    }
    private val arrowHeadPaint = stroke(accent, 2.5f).apply { strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val bonePaint = stroke(Color.WHITE, 3f).apply { strokeCap = Paint.Cap.ROUND }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boxPaint = stroke(Color.argb(220, 255, 255, 255), 1.5f)
    private val horizonPaint = stroke(Color.argb(200, 255, 255, 255), 1.5f).apply {
        pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 14, 14, 16) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * dp
    }
    private val zebraPaint = Paint().apply { color = Color.argb(120, 255, 90, 78) }
    private val histBg = Paint().apply { color = Color.argb(150, 14, 14, 16) }
    private val histBar = Paint().apply { color = Color.argb(220, 255, 255, 255) }

    private val image = RectF()

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

    private fun x(n: Float) = image.left + n * image.width()
    private fun y(n: Float) = image.top + n * image.height()

    override fun onDraw(canvas: Canvas) {
        computeImageRect()
        drawGrid(canvas)
        val f = frame
        if (mode == Mode.LIGHT) f?.lighting?.let { drawLighting(canvas, it) }
        if (mode == Mode.SMART || mode == Mode.COMPOSITION) {
            drawLevel(canvas)
            f?.let { drawComposition(canvas, it) }
        }
        if (mode == Mode.POSE) f?.let { drawSkeleton(canvas, it) }
    }

    private fun drawGrid(canvas: Canvas) {
        val lines = when (grid) {
            GridMode.THIRDS -> listOf(1f / 3f, 2f / 3f)
            GridMode.GOLDEN -> listOf(0.382f, 0.618f)
            GridMode.CENTER -> listOf(0.5f)
            GridMode.OFF -> return
        }
        for (p in lines) {
            canvas.drawLine(x(p), image.top, x(p), image.bottom, gridPaint)
            canvas.drawLine(image.left, y(p), image.right, y(p), gridPaint)
        }
    }

    private fun drawLevel(canvas: Canvas) {
        val lv = level ?: return
        val cx = image.centerX()
        val cy = image.centerY()
        if (lv.flat) {
            // Bubble level: like a spirit level, the bubble drifts towards the raised edge.
            val ok = abs(lv.tiltX) < 3f && abs(lv.tiltY) < 3f
            levelPaint.color = if (ok) good else Color.WHITE
            val r = 28 * dp
            canvas.drawCircle(cx, cy, r, levelPaint)
            val bx = cx + (lv.tiltX / 20f).coerceIn(-1f, 1f) * r * 2
            val by = cy - (lv.tiltY / 20f).coerceIn(-1f, 1f) * r * 2
            jointPaint.color = levelPaint.color
            canvas.drawCircle(bx, by, 6 * dp, jointPaint)
            jointPaint.color = Color.WHITE
            return
        }
        val ok = abs(lv.rollDeg) <= 2.5f
        levelPaint.color = if (ok) good else Color.WHITE
        // The horizon appears rotated opposite to the phone.
        val a = Math.toRadians(-lv.rollDeg.toDouble())
        val half = image.width() * 0.18f
        val gap = 24 * dp
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        canvas.drawLine(cx - dx * (half + gap), cy - dy * (half + gap), cx - dx * gap, cy - dy * gap, levelPaint)
        canvas.drawLine(cx + dx * gap, cy + dy * gap, cx + dx * (half + gap), cy + dy * (half + gap), levelPaint)
        // Fixed reference ticks.
        canvas.drawLine(cx - half - gap - 12 * dp, cy, cx - half - gap - 2 * dp, cy, gridPaint)
        canvas.drawLine(cx + half + gap + 2 * dp, cy, cx + half + gap + 12 * dp, cy, gridPaint)
    }

    private fun drawComposition(canvas: Canvas, f: GuidanceFrame) {
        val c = f.composition
        val anchor = c.anchor ?: return
        val subject = c.subject
        val color = if (c.aligned) good else accent
        targetPaint.color = color
        arrowPaint.color = color
        arrowHeadPaint.color = color
        dotPaint.color = color

        val ax = x(anchor.x)
        val ay = y(anchor.y)

        // Show what was recognised, so it is clear what the advice is about.
        when (subject?.kind) {
            SubjectKind.OBJECT -> subject.box?.let { b ->
                val r = RectF(x(b.left), y(b.top), x(b.right), y(b.bottom))
                canvas.drawRoundRect(r, 8 * dp, 8 * dp, boxPaint)
                drawLabel(canvas, subject.label, r.left, r.top)
            }
            SubjectKind.HORIZON -> {
                canvas.drawLine(image.left, ay, image.right, ay, horizonPaint)
                drawLabel(canvas, subject.label, image.left + 8 * dp, ay)
            }
            else -> Unit
        }
        if (subject?.kind != SubjectKind.HORIZON) canvas.drawCircle(ax, ay, 6 * dp, dotPaint)

        val targetX = c.targetX
        val targetY = c.targetY
        if (targetX == null && targetY == null) return
        val tx: Float
        val ty: Float
        when {
            targetX != null && targetY != null -> {
                tx = x(targetX)
                ty = y(targetY)
                canvas.drawCircle(tx, ty, 18 * dp, targetPaint)
                canvas.drawCircle(tx, ty, 3 * dp, dotPaint)
            }
            targetX != null -> {
                // Full-body shot: the whole vertical line is the target, fixed on screen.
                tx = x(targetX)
                ty = ay
                canvas.drawLine(tx, image.top, tx, image.bottom, targetPaint)
            }
            else -> {
                // Horizon: the whole horizontal line is the target.
                tx = ax
                ty = y(targetY!!)
                canvas.drawLine(image.left, ty, image.right, ty, targetPaint)
            }
        }
        if (c.aligned) return

        // Dashed arrow from the subject towards the target.
        val len = hypot(tx - ax, ty - ay)
        if (len < 30 * dp) return
        val ux = (tx - ax) / len
        val uy = (ty - ay) / len
        val stop = if (targetX != null && targetY != null) 22 * dp else 6 * dp
        val sx = ax + ux * 12 * dp
        val sy = ay + uy * 12 * dp
        val ex = tx - ux * stop
        val ey = ty - uy * stop
        canvas.drawLine(sx, sy, ex, ey, arrowPaint)
        val ang = atan2(uy, ux)
        val head = 10 * dp
        for (s in listOf(-1, 1)) {
            val a = ang + Math.PI.toFloat() + s * 0.5f
            canvas.drawLine(ex, ey, ex + cos(a) * head, ey + sin(a) * head, arrowHeadPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, left: Float, bottom: Float) {
        val pad = 6 * dp
        val w = labelText.measureText(text) + pad * 2
        val h = labelText.textSize + pad
        val top = (bottom - h).coerceAtLeast(image.top)
        canvas.drawRoundRect(left, top, left + w, top + h, 6 * dp, 6 * dp, labelBg)
        canvas.drawText(text, left + pad, top + h - pad * 0.9f, labelText)
    }

    private fun drawSkeleton(canvas: Canvas, f: GuidanceFrame) {
        val pose = f.pose ?: return
        for ((a, b) in PoseIdx.BONES) {
            if (!pose.visible(a) || !pose.visible(b)) continue
            val pa = pose.landmarks[a]
            val pb = pose.landmarks[b]
            canvas.drawLine(x(pa.x), y(pa.y), x(pb.x), y(pb.y), bonePaint)
        }
        val joints = PoseIdx.BONES.flatMap { listOf(it.first, it.second) }.toSet()
        for (i in joints) {
            if (!pose.visible(i)) continue
            val p = pose.landmarks[i]
            canvas.drawCircle(x(p.x), y(p.y), 4 * dp, jointPaint)
        }
        if (pose.visible(PoseIdx.NOSE)) {
            val n = pose.landmarks[PoseIdx.NOSE]
            canvas.drawCircle(x(n.x), y(n.y), 4 * dp, jointPaint)
        }
    }

    private fun drawLighting(canvas: Canvas, l: com.aucai.aicamera.core.LightingResult) {
        // Blown-out cells get red diagonal stripes.
        val cw = image.width() / l.gridWidth
        val ch = image.height() / l.gridHeight
        val stripe = 7 * dp
        for (j in 0 until l.gridHeight) {
            for (i in 0 until l.gridWidth) {
                if (!l.clipped[j * l.gridWidth + i]) continue
                val left = image.left + i * cw
                val top = image.top + j * ch
                canvas.save()
                canvas.clipRect(left, top, left + cw, top + ch)
                var s = -ch
                while (s < cw) {
                    canvas.drawRect(left + s, top, left + s + stripe / 2, top + ch, zebraPaint)
                    s += stripe
                }
                canvas.restore()
            }
        }

        // Histogram in the bottom-left corner of the image.
        val hw = 110 * dp
        val hh = 44 * dp
        val pad = 8 * dp
        val left = image.left + 12 * dp
        val bottom = image.bottom - 12 * dp
        canvas.drawRoundRect(left, bottom - hh - pad * 2, left + hw + pad * 2, bottom, 8 * dp, 8 * dp, histBg)
        val bw = hw / l.histogram.size
        for (i in l.histogram.indices) {
            val h = l.histogram[i] * hh
            val bx = left + pad + i * bw
            canvas.drawRect(bx, bottom - pad - h, bx + bw * 0.8f, bottom - pad, histBar)
        }
    }

    private fun stroke(c: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeWidth = widthDp * resources.displayMetrics.density
    }
}
