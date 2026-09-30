package com.aucai.aicamera.core

enum class TipCategory(val label: String) {
    LEVEL("水平"),
    COMPOSITION("构图"),
    POSE("姿势"),
    LIGHT("光线"),
}

/** [penalty] is how many points the tip takes off the shot score. */
enum class Severity(val penalty: Int) {
    INFO(0),
    SUGGEST(8),
    WARNING(18),
}

/** A one-tap fix the UI can offer next to a tip. */
enum class TipAction(val label: String) {
    METER_SUBJECT("一键提亮"),
    EXPOSURE_UP("提高曝光"),
    EXPOSURE_DOWN("降低曝光"),
}

data class Tip(
    val id: String,
    val category: TipCategory,
    val severity: Severity,
    val text: String,
    val action: TipAction? = null,
)

/**
 * Keeps tips from flickering: a tip must be reported continuously for [showAfterMs]
 * before it appears, and stays visible until it has been absent for [hideAfterMs].
 */
class TipStabilizer(
    private val showAfterMs: Long = 450,
    private val hideAfterMs: Long = 900,
) {
    private class Entry(var tip: Tip, val firstSeen: Long, var lastSeen: Long)

    private val entries = LinkedHashMap<String, Entry>()

    fun update(nowMs: Long, tips: List<Tip>): List<Tip> {
        for (tip in tips) {
            val e = entries[tip.id]
            if (e == null) {
                entries[tip.id] = Entry(tip, nowMs, nowMs)
            } else {
                e.tip = tip
                e.lastSeen = nowMs
            }
        }
        entries.values.removeAll { nowMs - it.lastSeen > hideAfterMs }
        return entries.values
            .filter { nowMs - it.firstSeen >= showAfterMs }
            .map { it.tip }
            .sortedWith(compareByDescending<Tip> { it.severity.ordinal }.thenBy { it.category.ordinal })
    }

    fun reset() = entries.clear()
}

object ShotScore {
    /** 100 minus the penalty of every visible tip, never below 20. */
    fun of(tips: List<Tip>): Int = (100 - tips.sumOf { it.severity.penalty }).coerceIn(20, 100)
}
