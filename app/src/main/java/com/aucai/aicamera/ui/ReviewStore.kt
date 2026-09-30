package com.aucai.aicamera.ui

import android.content.Context
import com.aucai.aicamera.core.ScoreItem
import com.aucai.aicamera.core.TipCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the camera thought of a photo when it was taken. */
data class PhotoReview(
    val total: Int,
    val scene: String,
    val items: List<ScoreItem>,
    val tips: List<String>,
    val blurry: Boolean = false,
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
        put("total", r.total)
        put("scene", r.scene)
        put("blurry", r.blurry)
        put("items", JSONArray().apply {
            r.items.forEach {
                put(JSONObject().put("c", it.category.name).put("p", it.points).put("m", it.max).put("n", it.note))
            }
        })
        put("tips", JSONArray(r.tips))
    }

    private fun decode(o: JSONObject): PhotoReview? = try {
        val items = o.getJSONArray("items")
        val tips = o.getJSONArray("tips")
        PhotoReview(
            total = o.getInt("total"),
            scene = o.optString("scene"),
            items = (0 until items.length()).map {
                val i = items.getJSONObject(it)
                ScoreItem(TipCategory.valueOf(i.getString("c")), i.getInt("p"), i.getInt("m"), i.getString("n"))
            },
            tips = (0 until tips.length()).map { tips.getString(it) },
            blurry = o.optBoolean("blurry"),
        )
    } catch (e: Exception) {
        null
    }
}
