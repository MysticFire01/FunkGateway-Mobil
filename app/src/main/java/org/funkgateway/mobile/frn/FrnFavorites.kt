package org.funkgateway.mobile.frn

import android.content.Context
import java.util.Locale

object FrnFavorites {
    private const val PREFS = "frn_favorites"
    private const val KEY_SERVERS = "servers"

    fun serverKey(server: String, port: Int): String =
        server.trim().lowercase(Locale.ROOT) + ":" + port

    fun getAll(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SERVERS, emptySet())
            ?.toSet()
            ?: emptySet()

    fun isFavorite(context: Context, server: String, port: Int): Boolean =
        serverKey(server, port) in getAll(context)

    fun setFavorite(
        context: Context,
        server: String,
        port: Int,
        favorite: Boolean
    ) {
        val updated = getAll(context).toMutableSet()
        val key = serverKey(server, port)

        if (favorite) {
            updated.add(key)
        } else {
            updated.remove(key)
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SERVERS, updated)
            .apply()
    }
}
