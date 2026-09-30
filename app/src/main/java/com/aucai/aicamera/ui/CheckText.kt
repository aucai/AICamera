package com.aucai.aicamera.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.aucai.aicamera.R
import com.aucai.aicamera.core.Check

/** Renders checks as "✓ 水平   ✗ 头顶被切 …" with green ticks and red crosses. */
object CheckText {
    fun format(context: Context, checks: List<Check>): CharSequence {
        val good = ContextCompat.getColor(context, R.color.good)
        val bad = ContextCompat.getColor(context, R.color.bad)
        val out = SpannableStringBuilder()
        for ((i, c) in checks.withIndex()) {
            if (i > 0) out.append("   ")
            out.append(if (c.ok) "✓ " else "✗ ", ForegroundColorSpan(if (c.ok) good else bad), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(c.text)
        }
        return out
    }

    fun passed(checks: List<Check>) = checks.count { it.ok } to checks.size
}
