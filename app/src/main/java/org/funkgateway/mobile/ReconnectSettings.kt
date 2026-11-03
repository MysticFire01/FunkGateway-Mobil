package org.funkgateway.mobile

import android.content.Context

/**
 * Gemeinsame Einstellungen für die automatische Wiederverbindung.
 * Gelten unabhängig vom Betriebsmodus.
 */
data class ReconnectSettings(
    val frnEnabled: Boolean = true,
    val mumbleEnabled: Boolean = true,
    val initialDelaySeconds: Int = 5,
    val maxDelaySeconds: Int = 60,
    val frnConnectOnStart: Boolean = false,
    val mumbleConnectOnStart: Boolean = false
) {
    companion object {
        private const val PREFS = "funkgateway_reconnect"

        fun load(context: Context): ReconnectSettings {
            val prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE
            )

            return ReconnectSettings(
                frnEnabled = prefs.getBoolean("frn_enabled", true),
                mumbleEnabled = prefs.getBoolean("mumble_enabled", true),
                initialDelaySeconds = prefs.getInt(
                    "initial_delay", 5
                ).coerceIn(3, 10),
                maxDelaySeconds = prefs.getInt(
                    "max_delay", 60
                ).coerceIn(30, 120),
                frnConnectOnStart = prefs.getBoolean(
                    "frn_connect_on_start", false
                ),
                mumbleConnectOnStart = prefs.getBoolean(
                    "mumble_connect_on_start", false
                )
            )
        }

        fun save(context: Context, settings: ReconnectSettings) {
            context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE
            ).edit()
                .putBoolean("frn_enabled", settings.frnEnabled)
                .putBoolean("mumble_enabled", settings.mumbleEnabled)
                .putInt(
                    "initial_delay",
                    settings.initialDelaySeconds.coerceIn(3, 10)
                )
                .putInt(
                    "max_delay",
                    settings.maxDelaySeconds.coerceIn(30, 120)
                )
                .putBoolean(
                    "frn_connect_on_start",
                    settings.frnConnectOnStart
                )
                .putBoolean(
                    "mumble_connect_on_start",
                    settings.mumbleConnectOnStart
                )
                .apply()
        }
    }
}
