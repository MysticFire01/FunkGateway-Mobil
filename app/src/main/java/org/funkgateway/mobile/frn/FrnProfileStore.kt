package org.funkgateway.mobile.frn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FrnProfile(
    val id: String,
    val name: String
)

object FrnProfileStore {
    private const val PREFS = "frn_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selected"

    fun list(context: Context): List<FrnProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw == null) {
            val first = FrnProfile(
                UUID.randomUUID().toString(),
                "Standard"
            )
            saveList(context, listOf(first))
            prefs.edit().putString("legacy_profile_id", first.id).commit()
            select(context, first.id)
            return listOf(first)
        }
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            FrnProfile(
                item.getString("id"),
                item.getString("name")
            )
        }
    }

    fun isLegacyProfile(context: Context, id: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("legacy_profile_id", null) == id

    fun selectedId(context: Context): String {
        val profiles = list(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selected = prefs.getString(KEY_SELECTED, null)
        return profiles.firstOrNull { it.id == selected }?.id
            ?: profiles.first().id
    }

    fun select(context: Context, id: String) {
        require(list(context).any { it.id == id })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }

    @Synchronized
    fun create(context: Context, name: String): FrnProfile {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) {
            "Bitte einen Namen für die Identität eingeben."
        }
        val profiles = list(context)
        val profile = FrnProfile(
            UUID.randomUUID().toString(),
            cleanName
        )
        saveList(context, profiles + profile)
        return profile
    }


    @Synchronized
    fun rename(context: Context, id: String, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Profilname darf nicht leer sein." }
        val profiles = list(context)
        require(profiles.any { it.id == id }) { "Profil nicht gefunden." }
        saveList(context, profiles.map {
            if (it.id == id) it.copy(name = clean) else it
        })
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val profiles = list(context)
        require(profiles.size > 1) {
            "Das letzte FRN-Profil darf nicht gelöscht werden."
        }
        require(profiles.any { it.id == id }) { "Profil nicht gefunden." }
        val remaining = profiles.filterNot { it.id == id }
        val prefs = context.getSharedPreferences(
            "frn_profile_data_$id", Context.MODE_PRIVATE
        )
        check(prefs.edit().clear().commit()) {
            "Profildaten konnten nicht gelöscht werden."
        }
        val wasSelected = selectedId(context) == id
        saveList(context, remaining)
        if (isLegacyProfile(context, id)) {
            check(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove("legacy_profile_id")
                    .commit()
            ) { "Kennzeichnung des alten Profils konnte nicht entfernt werden." }
        }
        if (wasSelected) {
            select(context, remaining.first().id)
        }
    }

    private fun saveList(context: Context, profiles: List<FrnProfile>) {
        val array = JSONArray()
        profiles.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, array.toString()).apply()
    }
}
