package com.aucai.aicamera.core

import kotlin.math.abs

/** Rule-based posing advice from body landmarks. */
object PoseCoach {

    fun analyze(pose: PoseFrame?): List<Tip> {
        if (pose == null || !pose.hasShoulders) return emptyList()
        val tips = ArrayList<Tip>()
        val a = pose.aspect
        val ls = pose.pt(PoseIdx.LEFT_SHOULDER)
        val rs = pose.pt(PoseIdx.RIGHT_SHOULDER)
        val shot = pose.shotType

        if (abs(lineTiltDeg(rs, ls, a)) > 10f) {
            tips += Tip("pose.shoulders", TipCategory.POSE, Severity.SUGGEST, "肩膀放平一点")
        }

        if (pose.hasHips && isSquareToCamera(pose)) {
            tips += Tip("pose.turn", TipCategory.POSE, Severity.SUGGEST, "身体侧转30°左右，会更显瘦")
        }

        if (shot != ShotType.CLOSE_UP && armGlued(pose, left = true) && armGlued(pose, left = false)) {
            tips += Tip("pose.arms", TipCategory.POSE, Severity.SUGGEST, "手臂离开身体一点，或者叉腰，更显瘦")
        }

        if (shot == ShotType.FULL_BODY && legsStiff(pose)) {
            tips += Tip("pose.legs", TipCategory.POSE, Severity.INFO, "重心放在一条腿上，另一条腿微微弯曲更自然")
        }
        return tips
    }

    /** Shoulders wide relative to the torso and at equal depth → facing the camera straight on. */
    fun isSquareToCamera(pose: PoseFrame): Boolean {
        val a = pose.aspect
        val ls = pose.landmarks[PoseIdx.LEFT_SHOULDER]
        val rs = pose.landmarks[PoseIdx.RIGHT_SHOULDER]
        val shoulderW = distance(pose.pt(PoseIdx.LEFT_SHOULDER), pose.pt(PoseIdx.RIGHT_SHOULDER), a)
        val torso = distance(pose.shoulderMid, pose.hipMid, a)
        if (torso < 1e-3f) return false
        // MediaPipe z uses roughly the same scale as x, so bring it into y units like x.
        val depthGap = abs(ls.z - rs.z) * a
        // Depth is the main signal; the width ratio only rules out side-on poses where z is unreliable.
        return shoulderW / torso > 0.55f && depthGap < 0.3f * shoulderW
    }

    private fun armGlued(pose: PoseFrame, left: Boolean): Boolean {
        val s = if (left) PoseIdx.LEFT_SHOULDER else PoseIdx.RIGHT_SHOULDER
        val e = if (left) PoseIdx.LEFT_ELBOW else PoseIdx.RIGHT_ELBOW
        val w = if (left) PoseIdx.LEFT_WRIST else PoseIdx.RIGHT_WRIST
        val h = if (left) PoseIdx.LEFT_HIP else PoseIdx.RIGHT_HIP
        if (!(pose.visible(e) && pose.visible(w) && pose.visible(h))) return false
        val a = pose.aspect
        val armToBody = jointAngleDeg(pose.pt(e), pose.pt(s), pose.pt(h), a)
        val elbow = jointAngleDeg(pose.pt(s), pose.pt(e), pose.pt(w), a)
        return armToBody < 15f && elbow > 155f
    }

    private fun legsStiff(pose: PoseFrame): Boolean {
        val a = pose.aspect
        val knees = listOf(
            Triple(PoseIdx.LEFT_HIP, PoseIdx.LEFT_KNEE, PoseIdx.LEFT_ANKLE),
            Triple(PoseIdx.RIGHT_HIP, PoseIdx.RIGHT_KNEE, PoseIdx.RIGHT_ANKLE),
        )
        val straight = knees.all { (h, k, an) ->
            pose.visible(k) && jointAngleDeg(pose.pt(h), pose.pt(k), pose.pt(an), a) > 172f
        }
        val hipsLevel = abs(lineTiltDeg(pose.pt(PoseIdx.RIGHT_HIP), pose.pt(PoseIdx.LEFT_HIP), a)) < 4f
        return straight && hipsLevel
    }
}
