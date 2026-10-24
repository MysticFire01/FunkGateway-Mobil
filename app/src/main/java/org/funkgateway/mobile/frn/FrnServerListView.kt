package org.funkgateway.mobile.frn

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object FrnServerListView {

    fun updateLiveCounts(
        container: LinearLayout,
        activeRoom: FrnRoom?,
        liveClientCount: Int?
    ) {
        // Ohne aktive Verbindung auf die SystemManager-Zahlen zurückfallen.
        // Keine alten Live-Zahlen in der Anzeige stehen lassen.

        fun count(room: FrnRoom): Int {
            val active =
                activeRoom != null && liveClientCount != null &&
                room.server.equals(activeRoom.server, ignoreCase = true) &&
                room.port == activeRoom.port &&
                room.room.equals(activeRoom.room, ignoreCase = true)
            return if (active) liveClientCount ?: room.clients else room.clients
        }

        for (i in 0 until container.childCount) {
            val section = container.getChildAt(i) as? LinearLayout
                ?: continue
            val headerRow = section.getChildAt(0) as? LinearLayout
                ?: continue
            val header = headerRow.getChildAt(0) as? TextView
                ?: continue
            val rooms = (header.tag as? List<*>)
                ?.filterIsInstance<FrnRoom>()
                ?: continue

            val headerText = header.text.toString()
            val suffix = Regex(" · [0-9]+ Teilnehmer$")
            if (suffix.containsMatchIn(headerText)) {
                header.text = headerText.replace(
                    suffix,
                    " · ${rooms.sumOf { count(it) }} Teilnehmer"
                )
            }

            val details = section.getChildAt(1) as? LinearLayout
                ?: continue
            for (j in 0 until details.childCount) {
                val entry = details.getChildAt(j) as? TextView
                    ?: continue
                val room = entry.tag as? FrnRoom ?: continue
                val entryText = entry.text.toString()
                if (suffix.containsMatchIn(entryText)) {
                    entry.text = entryText.replace(
                        suffix,
                        " · ${count(room)} Teilnehmer"
                    )
                }
            }
        }
    }


    fun populate(
        context: Context,
        container: LinearLayout,
        rooms: List<FrnRoom>,
        search: String = "",
        managerFilter: String = "",
        onRoomClick: ((FrnRoom) -> Unit)? = null,
        favoritesOnly: Boolean = false,
        activeRoom: FrnRoom? = null,
        liveClientCount: Int? = null
    ) {
        val expandedGroups = mutableSetOf<String>()
        for (index in 0 until container.childCount) {
            val section = container.getChildAt(index) as? LinearLayout
                ?: continue
            val headerRow = section.getChildAt(0) as? LinearLayout
                ?: continue
            val header = headerRow.getChildAt(0) as? TextView
                ?: continue
            val oldRooms = (header.tag as? List<*>)
                ?.filterIsInstance<FrnRoom>().orEmpty()
            val details = section.getChildAt(1) as? LinearLayout
                ?: continue
            val first = oldRooms.firstOrNull() ?: continue
            if (details.visibility == View.VISIBLE) {
                expandedGroups.add(
                    "${first.manager}|${first.server.lowercase()}|${first.port}"
                )
            }
        }
        val hadPreviousGroups = container.childCount > 0
        container.removeAllViews()

        fun displayedCount(room: FrnRoom): Int {
            val isActive = activeRoom != null &&
                room.server.equals(activeRoom.server, ignoreCase = true) &&
                room.port == activeRoom.port &&
                room.room.equals(activeRoom.room, ignoreCase = true)

            return if (isActive && liveClientCount != null) {
                liveClientCount
            } else {
                room.clients
            }
        }


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
                tag = group.rooms
                text = "${if (search.isNotBlank() && group.rooms.any { it.room.contains(search.trim(), ignoreCase = true) }) "▼" else "▶"} ${group.server}:${group.port}\n" +
                    "${group.roomCount} ${if (group.roomCount == 1) "Raum" else "Räume"} · " +
                    "${group.rooms.sumOf { displayedCount(it) }} Teilnehmer"
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
                val groupKey = "${group.manager}|${group.server.lowercase()}|${group.port}"
                if (hadPreviousGroups && groupKey in expandedGroups) {
                    visibility = View.VISIBLE
                }
            }

            for (room in group.rooms) {
                val entry = TextView(context).apply {
                    tag = room
                    text = "  ${room.room.ifBlank { "(kein Raum)" }}" +
                        " · ${displayedCount(room)} Teilnehmer"
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
