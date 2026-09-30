package com.aucai.aicamera.ui

import android.content.Context
import com.aucai.aicamera.core.Check
import com.aucai.aicamera.core.CloudReview
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the camera saw and checked when a photo was taken. */
data class PhotoReview(
    val scene: String,
    /** Why the camera framed the photo the way it did; empty when it was not cropped. */
    val reason: String,
    val checks: List<Check>,
    val tips: List<String>,
    val blurry: Boolean = false,
    /** The cloud model's review, once asked for in the gallery. */
    val ai: CloudReview? = null,
)

/** Keeps a review per photo (keyed by file name) in a small JSON file in app storage. */
class ReviewStore(context: Context) {

    private val file = File(context.filesDir, "reviews.json")

    @Synchronized
    fun get(name: String): PhotoReview? = load().optJSONObject(name)?.let { decode(it) }

    @Synchronized
    fun put(name: String, review: PhotoReview) {
        val all = load()
        all.put(name, encode(review))
        save(all)
    }

    @Synchronized
    fun markBlurry(name: String) {
        val all = load()
        val r = all.optJSONObject(name) ?: return
        r.put("blurry", true)
        save(all)
    }

    @Synchronized
    fun putAi(name: String, review: CloudReview) {
        val all = load()
        val r = all.optJSONObject(name) ?: encode(PhotoReview("", "", emptyList(), emptyList()))
        r.put("ai", JSONObject().put("good", review.good).put("improve", review.improve).put("next", review.nextTime))
        all.put(name, r)
        save(all)
    }

    @Synchronized
    fun remove(name: String) {
        val all = load()
        all.remove(name)
        save(all)
    }

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(all: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(all.toString())
        tmp.renameTo(file)
    }

    private fun encode(r: PhotoReview) = JSONObject().apply {
        put("scene", r.scene)
        put("reason", r.reason)
        put("blurry", r.blurry)
        put("checks", JSONArray().apply { r.checks.forEach { put(JSONObject().put("ok", it.ok).put("t", it.text)) } })
        put("tips", JSONArray(r.tips))
    }

    private fun decode(o: JSONObject): PhotoReview? = try {
        val checks = o.getJSONArray("checks")
        val tips = o.getJSONArray("tips")
        PhotoReview(
            scene = o.optString("scene"),
            reason = o.optString("reason"),
            checks = (0 until checks.length()).map {
                val c = checks.getJSONObject(it)
                Check(c.getBoolean("ok"), c.getString("t"))
            },
            tips = (0 until tips.length()).map { tips.getString(it) },
            blurry = o.optBoolean("blurry"),
            ai = o.optJSONObject("ai")?.let { CloudReview(it.optString("good"), it.optString("improve"), it.optString("next")) },
        )
    } catch (e: Exception) {
        null
    }
}
