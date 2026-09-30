package com.aucai.aicamera.ui

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads tips aloud in Chinese, without repeating itself or talking over itself. */
class Speaker(context: Context) {

    private var ready = false
    private var lastText: String? = null
    private var lastAt = 0L

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    val available get() = ready

    /** Speaks [text] unless it was the last thing said or something was said very recently. */
    fun say(text: String, force: Boolean = false) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        if (!force && (text == lastText || now - lastAt < 3500)) return
        lastText = text
        lastAt = now
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tip")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
