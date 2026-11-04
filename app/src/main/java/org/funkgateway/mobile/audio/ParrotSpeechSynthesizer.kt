package org.funkgateway.mobile.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Erzeugt eine Sprachansage als Audiodatei.
 * Es wird kein Mikrofon benötigt.
 */
class ParrotSpeechSynthesizer(
    private val context: Context
) {
    fun synthesize(
        text: String,
        timeoutMillis: Long = 15000L
    ): File? {
        if (text.isBlank()) return null

        val initialized = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var ready = false
        var successful = false

        var engine: TextToSpeech? = null

        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                initialized.countDown()
            }

            if (!initialized.await(5, TimeUnit.SECONDS) || !ready) {
                Log.w("ParrotTTS", "TTS nicht verfügbar")
                return null
            }

            val tts = engine ?: return null
            val languageResult = tts.setLanguage(Locale.GERMAN)

            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w("ParrotTTS", "Deutsche TTS-Stimme fehlt")
                return null
            }

            val output = File(
                context.cacheDir,
                "parrot_tts_${UUID.randomUUID()}.wav"
            )

            val utteranceId = UUID.randomUUID().toString()

            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(id: String) = Unit

                    override fun onDone(id: String) {
                        if (id == utteranceId) {
                            successful = true
                            completed.countDown()
                        }
                    }

                    override fun onError(id: String) {
                        if (id == utteranceId) {
                            completed.countDown()
                        }
                    }
                }
            )

            val result = tts.synthesizeToFile(
                text.take(1000),
                null,
                output,
                utteranceId
            )

            if (result != TextToSpeech.SUCCESS) {
                output.delete()
                return null
            }

            if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS) ||
                !successful || !output.exists()
            ) {
                output.delete()
                return null
            }

            return output
        } finally {
            engine?.stop()
            engine?.shutdown()
        }
    }
}
