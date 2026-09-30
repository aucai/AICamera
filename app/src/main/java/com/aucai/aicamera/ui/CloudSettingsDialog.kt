package com.aucai.aicamera.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.aucai.aicamera.R
import com.aucai.aicamera.cloud.CloudConfig
import com.aucai.aicamera.cloud.CloudException
import com.aucai.aicamera.cloud.CloudPreset
import com.aucai.aicamera.cloud.CloudSettings
import com.aucai.aicamera.cloud.VisionClient
import com.aucai.aicamera.databinding.DialogCloudBinding

/** Pick the cloud service, model and API key; "测试" sends a tiny request to check them. */
object CloudSettingsDialog {

    fun show(activity: Activity, onSaved: () -> Unit = {}) {
        val b = DialogCloudBinding.inflate(activity.layoutInflater)
        val saved = CloudSettings.load(activity)
        var preset = saved.preset

        fun fill(p: CloudPreset, keepFields: Boolean) {
            preset = p
            if (!keepFields) {
                b.baseUrl.setText(p.baseUrl)
                b.model.setText(p.model)
            }
            b.keyHelp.text = p.keyHelp
        }

        b.baseUrl.setText(saved.baseUrl)
        b.model.setText(saved.model)
        b.apiKey.setText(saved.apiKey)
        b.presets.check(
            when (saved.preset) {
                CloudPreset.QWEN -> R.id.presetQwen
                CloudPreset.DOUBAO -> R.id.presetDoubao
                CloudPreset.CUSTOM -> R.id.presetCustom
            }
        )
        fill(saved.preset, keepFields = true)
        b.presets.setOnCheckedChangeListener { _, id ->
            fill(
                when (id) {
                    R.id.presetDoubao -> CloudPreset.DOUBAO
                    R.id.presetCustom -> CloudPreset.CUSTOM
                    else -> CloudPreset.QWEN
                },
                keepFields = false,
            )
        }

        fun current() = CloudConfig(preset, b.baseUrl.text.toString().trim(), b.model.text.toString().trim(), b.apiKey.text.toString().trim())

        val dialog = AlertDialog.Builder(activity)
            .setTitle("云端 AI 设置")
            .setView(b.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = current()
                if (!c.ready) {
                    Toast.makeText(activity, "接口地址、模型名和 API Key 都要填", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                CloudSettings.save(activity, c)
                dialog.dismiss()
                onSaved()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val c = current()
                if (!c.ready) {
                    Toast.makeText(activity, "先把三项都填上", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(activity, "测试中…", Toast.LENGTH_SHORT).show()
                Thread {
                    val probe = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                    val result = try {
                        VisionClient(c).ask("你是测试助手。", "这张图是什么颜色？只回答颜色。", probe, maxTokens = 20)
                        "连接成功，可以保存了"
                    } catch (e: CloudException) {
                        "失败：${e.message}"
                    }
                    activity.runOnUiThread { Toast.makeText(activity, result, Toast.LENGTH_LONG).show() }
                }.start()
            }
        }
        dialog.show()
    }
}
