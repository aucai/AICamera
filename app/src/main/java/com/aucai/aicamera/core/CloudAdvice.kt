package com.aucai.aicamera.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a cloud vision model says about the current view.
 * @property crop recommended final framing in this image (display-normalized); null = keep as is.
 */
data class CloudAdvice(
    val scene: String,
    val good: String,
    val problem: String,
    val advice: String,
    val steps: List<String>,
    val pose: String,
    val crop: RectN?,
)

/** A cloud model's review of a photo that was already taken. */
data class CloudReview(val good: String, val improve: String, val nextTime: String)

/** Prompts for the cloud vision model and parsing of its answers. Pure: no Android or network. */
object CloudPrompts {

    const val SYSTEM = "你是一位经验丰富、审美在线的摄影师，正在手机取景时现场指导一位摄影新手。" +
        "你的建议要具体、能马上照做，用简体中文，语气简洁。"

    /**
     * @param context what the camera already knows, e.g. "人像 · 半身 · 逆光", to help the model.
     */
    fun adviceRequest(width: Int, height: Int, context: String, frontCamera: Boolean): String {
        val orientation = if (width >= height) "横拍" else "竖拍"
        val camera = if (frontCamera) "前置摄像头自拍，画面已镜像" else "后置摄像头"
        return """
            这是手机相机当前的取景画面（${width}x${height}，$orientation，$camera）。相机本地识别结果供参考：$context。
            请判断怎么把这张照片拍得更好看，只输出一个 JSON 对象，不要任何其他文字：
            {
              "scene": "一句话说清画面里有什么、光线怎样（不超过30字）",
              "good": "当前画面已经做得好的一点（不超过20字，没有就空字符串）",
              "problem": "当前取景最大的问题（不超过25字）",
              "advice": "最重要的一条改进建议，具体到怎么移动或转动手机、站位、变焦（不超过30字）",
              "steps": ["按顺序的操作步骤，每步不超过20字，最多3步"],
              "pose": "如果画面里有人，给被拍的人一句姿势或表情建议（不超过20字），否则空字符串",
              "crop": [x1, y1, x2, y2]
            }
            crop 是你推荐的最终取景框在这张图里的位置，用 0 到 1000 的相对坐标（左上角 0,0，右下角 1000,1000），
            宽高比尽量和原图一致。当前取景已经很好、或者需要的内容在画面外时，给整张图 [0,0,1000,1000]。
        """.trimIndent()
    }

    fun reviewRequest(context: String): String = """
        这是刚拍好的一张照片。拍摄时相机识别结果供参考：$context。
        请从构图、光线、主体、姿势等方面简短点评，只输出一个 JSON 对象，不要任何其他文字：
        {
          "good": "这张照片最好的地方（不超过30字）",
          "improve": "最值得改进的地方（不超过30字）",
          "nextTime": "下次拍类似场景时可以怎么做（不超过40字）"
        }
    """.trimIndent()

    fun parseAdvice(text: String, width: Int, height: Int): CloudAdvice? {
        val o = extractJson(text) ?: return null
        val steps = o.optJSONArray("steps")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifEmpty { null } } }
            ?: emptyList()
        return CloudAdvice(
            scene = o.optString("scene").trim(),
            good = o.optString("good").trim(),
            problem = o.optString("problem").trim(),
            advice = o.optString("advice").trim(),
            steps = steps.take(3),
            pose = o.optString("pose").trim(),
            crop = o.optJSONArray("crop")?.let { parseBox(it, width, height) },
        )
    }

    fun parseReview(text: String): CloudReview? {
        val o = extractJson(text) ?: return null
        return CloudReview(o.optString("good").trim(), o.optString("improve").trim(), o.optString("nextTime").trim())
    }

    /**
     * Accepts boxes as 0..1000 (asked for, and what Qwen3-VL uses natively), 0..1, or pixels of the
     * sent image. Pixels are only recognisable when a value exceeds 1000, so anything up to 1000 is
     * read as 0..1000.
     * Returns null for a box covering (nearly) the whole image or one that makes no sense.
     */
    fun parseBox(arr: JSONArray, width: Int, height: Int): RectN? {
        if (arr.length() != 4) return null
        val v = FloatArray(4) { arr.optDouble(it, Double.NaN).toFloat() }
        if (v.any { it.isNaN() || it < 0f }) return null
        val max = v.max()
        val (sx, sy) = when {
            max <= 1.0f -> 1f to 1f
            max <= 1000f -> 1000f to 1000f
            else -> width.toFloat() to height.toFloat()
        }
        val r = RectN(v[0] / sx, v[1] / sy, v[2] / sx, v[3] / sy).clamp01()
        if (r.width < 0.1f || r.height < 0.1f) return null
        if (r.width > 0.97f && r.height > 0.97f) return null
        return r
    }

    /** The first {...} object in the text, tolerating ```json fences and chatter around it. */
    fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }
}
