package org.funkgateway.mobile.audio

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Vorbereitete TTS-Sprachansage.
 * Die Audiodaten bleiben auch nach einem App-Neustart erhalten.
 */
class ParrotAnnouncementCache(private val context: Context) {
    private var cachedText: String? = null
    private var cachedAudio: ShortArray? = null

    private val storageFile: File
        get() = File(context.filesDir, "parrot_announcement.dat")

    @Synchronized
    fun prepare(text: String): Boolean {
        if (text.isBlank()) return false

        if (getPrepared(text) != null) return true

        val wav = ParrotSpeechSynthesizer(context).synthesize(text)
        val pcm = try {
            wav?.let { ParrotWavDecoder.decode(it) }
        } finally {
            wav?.delete()
        }

        if (pcm == null || pcm.isEmpty()) {
            Log.w("ParrotTTS", "Sprachansage konnte nicht vorbereitet werden")
            return false
        }

        cachedText = text
        cachedAudio = pcm

        try {
            val temp = File(context.filesDir, "parrot_announcement.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(0x50545453)
                out.writeUTF(text)
                out.writeInt(pcm.size)
                for (sample in pcm) out.writeShort(sample.toInt())
            }
            if (!temp.renameTo(storageFile)) {
                throw IllegalStateException("Audiodatei konnte nicht gespeichert werden")
            }
            Log.i("ParrotTTS", "Sprachansage dauerhaft gespeichert")
        } catch (e: Exception) {
            Log.e("ParrotTTS", "Speichern fehlgeschlagen", e)
            return false
        }

        return true
    }

    @Synchronized
    fun getPrepared(text: String): ShortArray? {
        if (cachedText == text) return cachedAudio
        if (!storageFile.exists()) return null

        return try {
            DataInputStream(storageFile.inputStream().buffered()).use { input ->
                if (input.readInt() != 0x50545453) return null
                val storedText = input.readUTF()
                if (storedText != text) return null

                val count = input.readInt()
                if (count !in 1..2880000) return null

                val pcm = ShortArray(count) { input.readShort() }
                cachedText = text
                cachedAudio = pcm

                Log.i("ParrotTTS", "Gespeicherte Sprachansage geladen")
                pcm
            }
        } catch (e: Exception) {
            Log.w("ParrotTTS", "Gespeicherte Sprachansage ungültig", e)
            null
        }
    }

    @Synchronized
    fun clear() {
        cachedText = null
        cachedAudio = null
        storageFile.delete()
    }
}
