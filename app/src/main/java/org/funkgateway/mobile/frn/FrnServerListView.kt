package org.funkgateway.mobile.frn

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object FrnServerListView {

    fun populate(
        context: Context,
        container: LinearLayout,
        rooms: List<FrnRoom>,
        search: String = "",
        managerFilter: String = "",
        onRoomClick: ((FrnRoom) -> Unit)? = null,
        favoritesOnly: Boolean = false
    ) {
        container.removeAllViews()

        val query = search.trim()
        val groups = FrnGroups.group(rooms).filter { group ->
            (managerFilter.isEmpty() || group.manager == managerFilter) &&
            (!favoritesOnly || FrnFavorites.isFavorite(
                context, group.server, group.port
            )) && (
            query.isEmpty() ||
                group.server.contains(query, ignoreCase = true) ||
                group.port.toString().contains(query) ||
                group.rooms.any {
                    it.room.contains(query, ignoreCase = true)
                })
        }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        var previousManager = ""
        for (group in groups) {
            if (group.manager != previousManager) {
                previousManager = group.manager
                val managerName = when (group.manager) {
                    "de_orig" -> "DE Original"
                    "de_alt" -> "DE Alternativ"
                    "ru" -> "Russland"
                    else -> group.manager
                }
                val heading = TextView(context).apply {
                    text = managerName
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(18), 0, dp(10))
                }
                container.addView(heading)
            }

            val section = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(Color.rgb(32, 43, 58))
            }

            val header = TextView(context).apply {
                text = "${if (search.isNotBlank() && group.rooms.any { it.room.contains(search.trim(), ignoreCase = true) }) "▼" else "▶"} ${group.server}:${group.port}\n" +
                    "${group.roomCount} ${if (group.roomCount == 1) "Raum" else "Räume"} · " +
                    "${group.clientCount} Teilnehmer"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(6))
            }

            val details = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (
                    search.isNotBlank() &&
                    group.rooms.any {
                        it.room.contains(search.trim(), ignoreCase = true)
                    }
                ) View.VISIBLE else View.GONE
            }

            for (room in group.rooms) {
                val entry = TextView(context).apply {
                    text = "  ${room.room.ifBlank { "(kein Raum)" }}" +
                        " · ${room.clients} Teilnehmer"
                    textSize = 13f
                    setTextColor(Color.LTGRAY)
                    setPadding(dp(8), dp(8), 0, dp(8))
                }
                entry.setOnClickListener { onRoomClick?.invoke(room) }
                details.addView(entry)
            }

            header.setOnClickListener {
                val expanded = details.visibility == View.VISIBLE
                details.visibility =
                    if (expanded) View.GONE else View.VISIBLE
                header.text = header.text.toString().replaceFirstChar {
                    if (expanded) "▶" else "▼"
                }
            }

            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            headerRow.addView(
                header,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val favoriteStar = TextView(context).apply {
                textSize = 27f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                minimumWidth = dp(48)
                minimumHeight = dp(48)
            }

            fun updateFavoriteStar() {
                val favorite = FrnFavorites.isFavorite(
                    context, group.server, group.port
                )
                favoriteStar.text = if (favorite) "★" else "☆"
                favoriteStar.setTextColor(
                    if (favorite) Color.rgb(251, 191, 36)
                    else Color.LTGRAY
                )
                favoriteStar.contentDescription =
                    if (favorite) "Server aus Favoriten entfernen"
                    else "Server zu Favoriten hinzufügen"
            }

            updateFavoriteStar()

            favoriteStar.setOnClickListener {
                val favorite = FrnFavorites.isFavorite(
                    context, group.server, group.port
                )
                FrnFavorites.setFavorite(
                    context, group.server, group.port, !favorite
                )
                updateFavoriteStar()
            }

            headerRow.addView(
                favoriteStar,
                LinearLayout.LayoutParams(dp(52), -2)
            )

            section.addView(headerRow)
            section.addView(details)

            container.addView(
                section,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(6)
                }
            )
        }
    }
}
