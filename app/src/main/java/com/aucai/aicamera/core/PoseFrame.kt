package com.aucai.aicamera.core

/** One body landmark in display-normalized coordinates (see Geometry.kt). */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f, val visibility: Float = 1f)

/** MediaPipe Pose landmark indices (33-point model). */
object PoseIdx {
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    /** Pairs drawn as the skeleton overlay. */
    val BONES = listOf(
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST,
        LEFT_SHOULDER to LEFT_HIP, RIGHT_SHOULDER to RIGHT_HIP,
        LEFT_HIP to RIGHT_HIP,
        LEFT_HIP to LEFT_KNEE, LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE, RIGHT_KNEE to RIGHT_ANKLE,
    )
}

enum class ShotType { CLOSE_UP, HALF_BODY, FULL_BODY }

class PoseFrame(val landmarks: List<Landmark>, val aspect: Float) {

    fun pt(i: Int) = Vec2(landmarks[i].x, landmarks[i].y)

    fun visible(i: Int, threshold: Float = 0.5f) = landmarks[i].visibility >= threshold

    /** Visible and actually inside the frame (MediaPipe extrapolates off-frame points). */
    fun inFrame(i: Int, threshold: Float = 0.5f): Boolean {
        val l = landmarks[i]
        return l.visibility >= threshold && l.x in 0f..1f && l.y in 0f..1f
    }

    val shoulderMid get() = Vec2.mid(pt(PoseIdx.LEFT_SHOULDER), pt(PoseIdx.RIGHT_SHOULDER))
    val hipMid get() = Vec2.mid(pt(PoseIdx.LEFT_HIP), pt(PoseIdx.RIGHT_HIP))

    val hasShoulders get() = visible(PoseIdx.LEFT_SHOULDER) && visible(PoseIdx.RIGHT_SHOULDER)
    val hasHips get() = visible(PoseIdx.LEFT_HIP) && visible(PoseIdx.RIGHT_HIP)

    /** Eye midpoint, falling back to the nose. */
    val eyes: Vec2
        get() = if (visible(PoseIdx.LEFT_EYE) && visible(PoseIdx.RIGHT_EYE)) {
            Vec2.mid(pt(PoseIdx.LEFT_EYE), pt(PoseIdx.RIGHT_EYE))
        } else {
            pt(PoseIdx.NOSE)
        }

    val shotType: ShotType
        get() = when {
            inFrame(PoseIdx.LEFT_ANKLE) && inFrame(PoseIdx.RIGHT_ANKLE) -> ShotType.FULL_BODY
            inFrame(PoseIdx.LEFT_HIP) || inFrame(PoseIdx.RIGHT_HIP) -> ShotType.HALF_BODY
            else -> ShotType.CLOSE_UP
        }

    /** Estimated top of the head, from the eye-to-shoulder distance. */
    val headTop: Float?
        get() = if (hasShoulders) eyes.y - 0.55f * (shoulderMid.y - eyes.y) else null

    /** Box around the visible body landmarks (may extend past the frame). */
    fun bodyBounds(): RectN? =
        RectN.around(landmarks.indices.filter { visible(it) }.map { pt(it) })

    /** Head-to-hips box between the shoulders: the person without the background between their arms. */
    fun coreBounds(): RectN? {
        if (!hasShoulders) return null
        val ls = pt(PoseIdx.LEFT_SHOULDER)
        val rs = pt(PoseIdx.RIGHT_SHOULDER)
        val bottom = if (hasHips) hipMid.y else shoulderMid.y + 2f * (shoulderMid.y - eyes.y)
        return RectN(minOf(ls.x, rs.x), headTop ?: eyes.y, maxOf(ls.x, rs.x), bottom)
    }

    /** Box around the face landmarks (nose, eyes, ears), padded to cover the whole face. */
    fun faceBounds(): RectN? {
        val face = (0..10).filter { visible(it) }.map { pt(it) }
        if (face.size < 3) return null
        val r = RectN.around(face) ?: return null
        // Points 0..10 span eyes to mouth; extend up to the forehead and down to the chin.
        // Face width converted to y units so the padding is proportional on both axes.
        val wy = r.width * aspect
        return RectN(r.left - r.width * 0.1f, r.top - wy * 0.5f, r.right + r.width * 0.1f, r.bottom + wy * 0.35f)
    }
}
