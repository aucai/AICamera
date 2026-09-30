package com.aucai.aicamera.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aucai.aicamera.R
import com.aucai.aicamera.core.ScoreItem
import com.aucai.aicamera.core.TipCategory

/** Fills the four "构图 28/35 / 位置很好" pills. */
object ScorePills {

    val ORDER = listOf(TipCategory.COMPOSITION, TipCategory.LIGHT, TipCategory.POSE, TipCategory.LEVEL)

    fun render(context: Context, views: List<TextView>, items: List<ScoreItem>) {
        val byCategory = items.associateBy { it.category }
        for ((view, category) in views.zip(ORDER)) {
            val item = byCategory[category]
            val applicable = item != null && item.applicable
            val head = if (applicable) "${category.label} ${item!!.points}/${item.max}" else "${category.label} —"
            val color = when {
                !applicable -> ContextCompat.getColor(context, R.color.text_secondary)
                item!!.points >= item.max * 0.8f -> ContextCompat.getColor(context, R.color.good)
                item.points >= item.max * 0.5f -> ContextCompat.getColor(context, R.color.accent)
                else -> ContextCompat.getColor(context, R.color.bad)
            }
            view.text = SpannableStringBuilder()
                .append(head, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append("\n")
                .append(if (applicable) item!!.note else "无法判断", RelativeSizeSpan(0.85f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun verdict(total: Int) = when {
        total >= 85 -> "拍得很好"
        total >= 70 -> "拍得不错"
        total >= 50 -> "还可以更好"
        else -> "问题比较多"
    }
}
