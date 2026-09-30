package com.aucai.aicamera

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.aucai.aicamera.databinding.ActivityGalleryBinding
import com.aucai.aicamera.ui.ReviewStore
import com.aucai.aicamera.cloud.CloudException
import com.aucai.aicamera.cloud.CloudSettings
import com.aucai.aicamera.cloud.VisionClient
import com.aucai.aicamera.core.CloudPrompts
import com.aucai.aicamera.core.CloudReview
import com.aucai.aicamera.ui.CheckText
import com.aucai.aicamera.ui.CloudSettingsDialog
import com.aucai.aicamera.ui.ShutterButton
import java.util.concurrent.Executors

/** Browse the photos taken with this app, with the review the camera made of each one. */
class GalleryActivity : AppCompatActivity() {

    data class Photo(val id: Long, val name: String) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var reviews: ReviewStore
    private val loader = Executors.newFixedThreadPool(2)
    private val photos = ArrayList<Photo>()
    private val adapter = PhotoAdapter()
    private var pendingDelete: Photo? = null
    private var aiLoading = false

    private val deleteRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val photo = pendingDelete ?: return@registerForActivityResult
            pendingDelete = null
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            // Android 11+ deletes on confirmation; Android 10 only grants access, so delete now.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                runCatching { contentResolver.delete(photo.uri, null, null) }
            }
            onDeleted(photo)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        reviews = ReviewStore(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.topBar.setPadding(bars.left + dp(4), bars.top, bars.right + dp(16), 0)
            binding.bottomPanel.setPadding(bars.left + dp(16), dp(14), bars.right + dp(16), bars.bottom + dp(14))
            insets
        }

        binding.pager.adapter = adapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = showReview()
        })
        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { current()?.let { share(it) } }
        binding.btnDelete.setOnClickListener { current()?.let { confirmDelete(it) } }
        binding.btnAiReview.setOnClickListener { current()?.let { askAiReview(it) } }

        loadPhotos()
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
    }

    private fun current(): Photo? = photos.getOrNull(binding.pager.currentItem)

    private fun loadPhotos() {
        loader.execute {
            val list = ArrayList<Photo>()
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("${Environment.DIRECTORY_PICTURES}/AICamera%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext()) list += Photo(c.getLong(0), c.getString(1) ?: "")
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                photos.clear()
                photos.addAll(list)
                adapter.notifyDataSetChanged()
                showReview()
            }
        }
    }

    private fun showReview() {
        val photo = current()
        binding.empty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        binding.bottomPanel.visibility = if (photo == null) View.GONE else View.VISIBLE
        binding.counter.text = if (photo == null) "" else "${binding.pager.currentItem + 1} / ${photos.size}"
        if (photo == null) return

        val review = reviews.get(photo.name)
        if (review != null && review.checks.isEmpty() && review.ai != null) {
            // Only an AI review (photo from before reviews were recorded).
            binding.score.text = "AI"
            binding.score.background.mutate().setTint(getColor(R.color.accent))
            binding.verdict.text = "AI 点评"
            binding.scene.text = ""
            binding.pills.visibility = View.GONE
            binding.tips.visibility = View.VISIBLE
            binding.tips.text = aiLines(review.ai).joinToString("\n")
            return
        }
        if (review == null) {
            binding.score.text = "—"
            binding.score.background.mutate().setTint(getColor(R.color.text_secondary))
            binding.verdict.text = "没有点评"
            binding.scene.text = "没有拍摄时的分析记录，可以点「AI 点评」让 AI 看看"
            binding.pills.visibility = View.GONE
            binding.tips.visibility = View.GONE
            return
        }
        val (passed, total) = CheckText.passed(review.checks)
        binding.score.text = "$passed/$total"
        binding.score.background.mutate().setTint(ShutterButton.colorFor(passed, total))
        binding.verdict.text = when {
            passed == total -> "每一项都达标"
            passed >= total - 1 -> "差一点就完美"
            else -> "有 ${total - passed} 项可以改进"
        }
        binding.scene.text = "AI 识别：${review.scene}"
        binding.pills.visibility = View.VISIBLE
        binding.pills.text = CheckText.format(this, review.checks)
        val lines = ArrayList<String>()
        review.ai?.let { lines += aiLines(it) }
        if (review.reason.isNotEmpty()) lines += "取景：${review.reason}"
        if (review.blurry) lines += "照片可能有点糊，下次拿稳手机，或先点一下主体对焦"
        lines += review.tips
        binding.tips.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.tips.text = lines.take(6).joinToString("\n") { if (it.startsWith("AI ")) it else "· $it" }
    }

    private fun aiLines(ai: CloudReview) = listOfNotNull(
        ai.good.ifBlank { null }?.let { "AI 👍 $it" },
        ai.improve.ifBlank { null }?.let { "AI ✎ $it" },
        ai.nextTime.ifBlank { null }?.let { "AI → 下次：$it" },
    )

    /** Sends the photo to the cloud model for a short review and keeps the answer with the photo. */
    private fun askAiReview(photo: Photo) {
        val config = CloudSettings.load(this)
        if (!config.ready) {
            CloudSettingsDialog.show(this) { askAiReview(photo) }
            return
        }
        if (aiLoading) return
        aiLoading = true
        binding.btnAiReview.text = "点评中…"
        val scene = reviews.get(photo.name)?.scene?.ifEmpty { null } ?: "未知"
        loader.execute {
            val result = try {
                val bmp = decode(photo.uri, 1280) ?: throw CloudException("读取照片失败")
                val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                val text = VisionClient(config).ask(CloudPrompts.SYSTEM, CloudPrompts.reviewRequest(scene), soft)
                CloudPrompts.parseReview(text)?.let { Result.success(it) }
                    ?: Result.failure(CloudException("AI 的回答格式不对，再试一次"))
            } catch (e: CloudException) {
                Result.failure(e)
            }
            runOnUiThread {
                aiLoading = false
                if (isDestroyed) return@runOnUiThread
                binding.btnAiReview.text = getString(R.string.ai_review)
                result.onSuccess {
                    reviews.putAi(photo.name, it)
                    if (current() == photo) showReview()
                }.onFailure {
                    Toast.makeText(this, "AI 点评失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun share(photo: Photo) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, photo.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "分享照片"))
    }

    private fun confirmDelete(photo: Photo) {
        AlertDialog.Builder(this)
            .setMessage("删除这张照片？")
            .setPositiveButton("删除") { _, _ -> delete(photo) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun delete(photo: Photo) {
        try {
            contentResolver.delete(photo.uri, null, null)
            onDeleted(photo)
        } catch (e: SecurityException) {
            // Photos from an earlier install belong to "another app" now; ask the system for consent.
            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(contentResolver, listOf(photo.uri)).intentSender
            } else {
                (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            }
            if (sender == null) {
                Toast.makeText(this, "没有权限删除这张照片", Toast.LENGTH_SHORT).show()
                return
            }
            pendingDelete = photo
            deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    private fun onDeleted(photo: Photo) {
        val index = photos.indexOf(photo)
        if (index < 0) return
        photos.removeAt(index)
        adapter.notifyItemRemoved(index)
        reviews.remove(photo.name)
        binding.pager.post { showReview() }
    }

    private fun togglePanels() {
        val show = binding.topBar.visibility != View.VISIBLE
        binding.topBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomPanel.visibility = if (show && photos.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun decode(uri: Uri, maxSide: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxSide.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
    } catch (_: Exception) {
        null
    }

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.Holder>() {

        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { togglePanels() }
            }
            return Holder(image)
        }

        override fun getItemCount() = photos.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val photo = photos[position]
            holder.image.setImageDrawable(null)
            holder.image.tag = photo.id
            val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            loader.execute {
                val bmp = decode(photo.uri, maxSide)
                holder.image.post { if (holder.image.tag == photo.id) holder.image.setImageBitmap(bmp) }
            }
        }
    }
}
