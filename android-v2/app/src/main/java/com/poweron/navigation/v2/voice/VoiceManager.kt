package com.poweron.navigation.v2.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceManager(
    context: Context
) : TextToSpeech.OnInitListener {

    private val textToSpeech = TextToSpeech(context, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            return
        }

        val turkish = Locale("tr", "TR")
        val german = Locale.GERMANY

        ready =
            textToSpeech.setLanguage(turkish) >=
                TextToSpeech.LANG_AVAILABLE ||
                textToSpeech.setLanguage(german) >=
                TextToSpeech.LANG_AVAILABLE
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) {
            return
        }

        textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "poweron-navigation"
        )
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }
}
