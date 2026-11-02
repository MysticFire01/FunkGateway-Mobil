package org.funkgateway.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MumbleFavorites {
    private const val PREFS = "mumble_favorites"
    private const val KEY = "servers"

    fun load(context: Context): List<MumblePublicServer> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"

        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val host = item.optString("host").trim()
                val port = item.optInt("port", 64738)
                if (host.isEmpty() || port !in 1..65535) return@mapNotNull null

                MumblePublicServer(
                    name = item.optString("name", host),
                    host = host,
                    port = port,
                    country = item.optString("country", "Unbekannt"),
                    countryCode = item.optString("countryCode"),
                    source = item.optString("source", "favorite"),
                    username = item.optString("username", "")
                )
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, servers: List<MumblePublicServer>) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(JSONObject().apply {
                put("name", server.name)
                put("host", server.host)
                put("port", server.port)
                put("country", server.country)
                put("countryCode", server.countryCode)
                put("source", server.source)
                put("username", server.username)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun add(context: Context, server: MumblePublicServer) {
        save(context, load(context).filterNot { it.id == server.id } + server)
    }

    fun update(
        context: Context,
        original: MumblePublicServer,
        updated: MumblePublicServer
    ): Boolean {
        val servers = load(context)
        val index = servers.indexOfFirst { it.id == original.id }

        if (index < 0) return false

        if (servers.any {
                it.id == updated.id && it.id != original.id
            }) {
            return false
        }

        val result = servers.toMutableList()
        result[index] = updated
        save(context, result)
        return true
    }

    fun remove(context: Context, server: MumblePublicServer) {
        save(context, load(context).filterNot { it.id == server.id })
    }

    fun contains(context: Context, server: MumblePublicServer): Boolean =
        load(context).any { it.id == server.id }
}
