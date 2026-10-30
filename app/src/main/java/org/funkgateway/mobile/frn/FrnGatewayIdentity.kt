package org.funkgateway.mobile.frn

import android.content.Context
import java.util.UUID

object FrnGatewayIdentity {
    private const val PREFS = "funkgateway_identity"
    private const val KEY_ID = "installation_id"

    @Synchronized
    fun getId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val existing = prefs.getString(KEY_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val newId = UUID.randomUUID().toString()

        check(prefs.edit().putString(KEY_ID, newId).commit()) {
            "FunkGateway-Identität konnte nicht gespeichert werden."
        }

        return newId
    }
}
