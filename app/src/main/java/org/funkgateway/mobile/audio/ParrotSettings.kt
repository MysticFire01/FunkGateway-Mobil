package org.funkgateway.mobile.audio

import android.content.Context

/**
 * Dauerhaft gespeicherte Einstellungen des Mumble-Papageis.
 */
data class ParrotSettings(
    val maxRecordingSeconds: Int = 30,
    val playbackDelayMillis: Long = 2000L,
    val timeoutSignalEnabled: Boolean = true,
    val concertModeEnabled: Boolean = false,
    val timeoutAnnouncementMode: String = "beep",
    val timeoutAnnouncementText: String = DEFAULT_ANNOUNCEMENT
) {
    companion object {
        private const val PREFS = "funkgateway_parrot"

        const val DEFAULT_ANNOUNCEMENT =
            "Achtung, Achtung! Hier spricht die Sandgaleone! " +
            "Deine Redezeit ist abgelaufen. Wer jetzt noch seine " +
            "Lebensgeschichte erzählt, wird für die nächste Woche " +
            "zum Deckschrubben eingeteilt! Arrr!"

        val ANNOUNCEMENT_MODES =
            setOf("off", "beep", "speech", "both")

        fun load(context: Context): ParrotSettings {
            val prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE
            )
            return ParrotSettings(
                maxRecordingSeconds = prefs.getInt(
                    "max_recording_seconds", 30
                ).coerceIn(5, 120),
                playbackDelayMillis = prefs.getLong(
                    "playback_delay_millis", 2000L
                ).coerceIn(0L, 10000L),
                timeoutSignalEnabled = prefs.getBoolean(
                    "timeout_signal_enabled", true
                ),
                concertModeEnabled = prefs.getBoolean(
                    "concert_mode_enabled", false
                ),
                timeoutAnnouncementMode = prefs.getString(
                    "timeout_announcement_mode", "beep"
                )?.takeIf { it in ANNOUNCEMENT_MODES } ?: "beep",
                timeoutAnnouncementText = prefs.getString(
                    "timeout_announcement_text", DEFAULT_ANNOUNCEMENT
                ) ?: DEFAULT_ANNOUNCEMENT
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(
            PREFS, Context.MODE_PRIVATE
        ).edit()
            .putInt(
                "max_recording_seconds",
                maxRecordingSeconds.coerceIn(5, 120)
            )
            .putLong(
                "playback_delay_millis",
                playbackDelayMillis.coerceIn(0L, 10000L)
            )
            .putBoolean(
                "timeout_signal_enabled",
                timeoutSignalEnabled
            )
            .putBoolean(
                "concert_mode_enabled",
                concertModeEnabled
            )
            .putString(
                "timeout_announcement_mode",
                timeoutAnnouncementMode.takeIf {
                    it in ANNOUNCEMENT_MODES
                } ?: "beep"
            )
            .putString(
                "timeout_announcement_text",
                timeoutAnnouncementText.take(1000)
            )
            .apply()
    }
}
