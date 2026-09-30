package com.aucai.aicamera.cloud

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Ready-made settings for OpenAI-compatible vision APIs that work in mainland China. */
enum class CloudPreset(val label: String, val baseUrl: String, val model: String, val keyHelp: String) {
    QWEN(
        "通义千问（阿里云百炼）",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.7-plus",
        "在阿里云百炼控制台的「API-KEY」页面创建，形如 sk-…",
    ),
    DOUBAO(
        "豆包（火山方舟）",
        "https://ark.cn-beijing.volces.com/api/v3",
        "",
        "在火山方舟控制台创建 API Key；模型填控制台里视觉理解模型的 Model ID",
    ),
    CUSTOM("其他 OpenAI 兼容接口", "", "", "填写服务商提供的接口地址、模型名和 Key"),
}

data class CloudConfig(val preset: CloudPreset, val baseUrl: String, val model: String, val apiKey: String) {
    val ready get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

/** Stored on this phone only (plain app preferences; this is a personal-use app). */
object CloudSettings {
    private const val PREFS = "cloud"

    fun load(context: Context): CloudConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val preset = CloudPreset.entries.getOrNull(p.getInt("preset", 0)) ?: CloudPreset.QWEN
        return CloudConfig(
            preset,
            p.getString("baseUrl", null) ?: preset.baseUrl,
            p.getString("model", null) ?: preset.model,
            p.getString("apiKey", "") ?: "",
        )
    }

    fun save(context: Context, c: CloudConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("preset", c.preset.ordinal)
            .putString("baseUrl", c.baseUrl.trim().trimEnd('/'))
            .putString("model", c.model.trim())
            .putString("apiKey", c.apiKey.trim())
            .apply()
    }
}

class CloudException(message: String) : Exception(message)

/** Sends one image plus instructions to an OpenAI-compatible chat API. Blocking: call off the main thread. */
class VisionClient(private val config: CloudConfig) {

    fun ask(system: String, prompt: String, image: Bitmap, maxSide: Int = 1024, maxTokens: Int = 800): String {
        val jpeg = encode(image, maxSide)
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val body = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                        .put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                    .put(JSONObject().put("type", "text").put("text", prompt)))))
            // Answers are short; skip the slow "thinking" pass on models that have one.
            val host = url.host
            if (host.contains("aliyuncs")) put("enable_thinking", false)
            if (host.contains("volces")) put("thinking", JSONObject().put("type", "disabled"))
        }

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw CloudException(describeError(code, text))
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").opt("content")
            return when (content) {
                is String -> content
                // Some APIs return a list of parts.
                is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text") ?: "" }
                else -> throw CloudException("云端返回的内容看不懂")
            }
        } catch (e: CloudException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw CloudException("网络超时，稍后再试")
        } catch (e: UnknownHostException) {
            throw CloudException("连不上云端，检查网络和接口地址")
        } catch (e: Exception) {
            throw CloudException("请求失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            ?: runCatching { JSONObject(body).optString("message") }.getOrNull()
            ?: ""
        return when (code) {
            401, 403 -> "API Key 不对或没有权限"
            404 -> "接口地址或模型名不对"
            429 -> "请求太频繁，或者额度用完了"
            else -> "云端出错（$code）${if (msg.isNotBlank()) "：$msg" else ""}"
        }
    }

    private fun encode(image: Bitmap, maxSide: Int): ByteArray {
        val scale = maxSide.toFloat() / maxOf(image.width, image.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(image, (image.width * scale).toInt(), (image.height * scale).toInt(), true)
        } else image
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }
}
