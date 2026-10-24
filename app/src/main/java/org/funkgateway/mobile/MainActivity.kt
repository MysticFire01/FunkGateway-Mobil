package org.funkgateway.mobile

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.os.Bundle
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import org.funkgateway.mobile.frn.FrnSystemManager
import org.funkgateway.mobile.frn.FrnCache

class MainActivity : Activity() {
    private var gatewayService: GatewayService? = null

    private val microphonePermissionRequest = 1208

    private fun ensureMicrophonePermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            microphonePermissionRequest
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode != microphonePermissionRequest) return

        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        Toast.makeText(
            this,
            if (granted) {
                "Mikrofon freigegeben. PTT zum Senden erneut drücken."
            } else {
                "Ohne Mikrofonberechtigung ist Senden nicht möglich."
            },
            Toast.LENGTH_LONG
        ).show()
    }
    private var gatewayBound = false
    private var refreshPttBar: (() -> Unit)? = null
    private var refreshFrnRoomStatus: (() -> Unit)? = null
    private var refreshFrnParticipantsView: (() -> Unit)? = null
    private var refreshFrnServerCounts: (() -> Unit)? = null

    private val gatewayConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            gatewayService =
                (binder as? GatewayService.LocalBinder)?.getService()
            refreshPttBar?.invoke()
            if (currentPage == "FRN_ROOM") {
                refreshFrnRoomStatus?.invoke()
            }
            if (currentPage == "FRN_PARTICIPANTS") {
                refreshFrnParticipantsView?.invoke()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayService = null
            refreshPttBar?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        frnActivityActive = true
        startFrnRefreshTimer()
        gatewayBound = bindService(
            Intent(this, GatewayService::class.java),
            gatewayConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        // Beim Wechsel in den Hintergrund niemals weiter senden.
        try {
            gatewayService?.stopPtt()
        } catch (e: Exception) {
            android.util.Log.w(
                "FunkGateway-PTT",
                "PTT-Abbruch beim Verlassen der App",
                e
            )
        }
        frnActivityActive = false
        stopFrnRefreshTimer()
        if (gatewayBound) {
            unbindService(gatewayConnection)
            gatewayBound = false
        }
        gatewayService = null
        super.onStop()
    }


    private val bg = Color.rgb(17, 23, 34)
    private val panel = Color.rgb(32, 42, 54)
    private val accent = Color.rgb(49, 217, 211)
    private val muted = Color.rgb(181, 194, 207)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
        }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun button(value: String, action: () -> Unit): Button =
        Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(panel)
            setOnClickListener { action() }
        }

    private var currentPage = "Start"
    private var selectedFrnRoom: org.funkgateway.mobile.frn.FrnRoom? = null

    private fun availableFrnRooms(): List<org.funkgateway.mobile.frn.FrnRoom> {
        val selected = selectedFrnRoom ?: return emptyList()
        val cached = roomsForSelectedFrnServer()

        val serverNames = if (
            gatewayService?.getFrnStatus() ==
                GatewayService.FrnStatus.CONNECTED
        ) {
            gatewayService?.getFrnServerRooms().orEmpty()
        } else {
            emptyList()
        }

        val additional = serverNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .filterNot { name ->
                cached.any {
                    it.room.equals(name, ignoreCase = true)
                }
            }
            .map { name ->
                selected.copy(room = name, clients = 0)
            }

        return (cached + additional)
            .sortedBy { it.room.lowercase() }
    }

    private fun roomsForSelectedFrnServer(): List<org.funkgateway.mobile.frn.FrnRoom> {
        val selected = selectedFrnRoom ?: return emptyList()
        return FrnCache.rooms
            .filter {
                it.server.equals(selected.server, ignoreCase = true) &&
                    it.port == selected.port
            }
            .distinctBy { it.room.lowercase() }
            .sortedBy { it.room.lowercase() }
    }

    private val frnRefreshHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private var frnRefreshTask: Runnable? = null
    private var frnRefreshButton: Button? = null
    private var frnRefreshResults: LinearLayout? = null
    private var refreshFrnCachedList: (() -> Unit)? = null
    private var frnActivityActive = false

    private fun stopFrnRefreshTimer() {
        frnRefreshTask?.let {
            frnRefreshHandler.removeCallbacks(it)
        }
        frnRefreshTask = null
    }

    private fun startFrnRefreshTimer() {
        stopFrnRefreshTimer()
        if (!frnActivityActive) return

        val task = object : Runnable {
            override fun run() {
                if (!frnActivityActive) {
                    stopFrnRefreshTimer()
                    return
                }

                // Gemeinsame, abgesicherte Hintergrundabfrage.
                // Unabhängig vom gerade geöffneten FRN-Tab.
                refreshFrnCacheOnStartup()

                frnRefreshHandler.postDelayed(this, 300_000L)
            }
        }

        frnRefreshTask = task
        frnRefreshHandler.postDelayed(task, 300_000L)
    }

    private var frnManagerFailureCount = 0
    private var frnManagerPauseUntil = 0L

    private fun frnAutomaticRefreshAllowed(): Boolean {
        return android.os.SystemClock.elapsedRealtime() >=
            frnManagerPauseUntil
    }

    private fun recordFrnManagerRefreshResult(success: Boolean) {
        if (success) {
            frnManagerFailureCount = 0
            frnManagerPauseUntil = 0L
        } else {
            frnManagerFailureCount++
            if (frnManagerFailureCount >= 3) {
                frnManagerPauseUntil =
                    android.os.SystemClock.elapsedRealtime() + 900_000L
                frnManagerFailureCount = 0
                android.util.Log.w(
                    "FunkGateway",
                    "FRN-SystemManager: 15 Minuten Schutzpause."
                )
            }
        }
    }

    private val frnManagerRefreshBusy =
        java.util.concurrent.atomic.AtomicBoolean(false)
    private var frnRegistrationInProgress = false


    private fun frnCacheStatusText(): String {
        val summary = org.funkgateway.mobile.frn.FrnStats.summary(
            FrnCache.rooms
        )
        if (FrnCache.lastUpdated <= 0L) return summary

        val time = java.text.SimpleDateFormat(
            "HH:mm",
            java.util.Locale.getDefault()
        ).format(java.util.Date(FrnCache.lastUpdated))

        return "$summary · Aktualisiert um $time Uhr"
    }

    private fun showFrn() {
        if (FrnCache.lastUpdated == 0L) FrnCache.load(this)
        currentPage = "FRN"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
            setBackgroundColor(bg)
        }

        root.addView(label("FRN · Server", 23f, Color.WHITE, true))

        val activeRoom = gatewayService?.getConnectedFrnRoom()
        val activeState = gatewayService?.getFrnStatus()
        val activeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(panel)
        }

        activeBox.addView(label(
            "FRN-Verbindung",
            15f, Color.WHITE, true
        ))

        val activeDescription = when (activeState) {
            GatewayService.FrnStatus.CONNECTED ->
                if (activeRoom != null) {
                    "${activeRoom.server}:${activeRoom.port} · ${activeRoom.room}"
                } else {
                    "Verbunden · Raumdaten nicht verfügbar"
                }
            GatewayService.FrnStatus.CONNECTING ->
                "Verbindungsaufbau läuft ..."
            else ->
                "Keine aktive FRN-Verbindung"
        }

        activeBox.addView(label(
            activeDescription,
            14f, muted
        ).apply {
            setPadding(0, dp(8), 0, dp(12))
        })

        val disconnectActive = button("Aktive FRN-Verbindung trennen") {
            gatewayService?.disconnectFrn()
            showFrn()
        }
        disconnectActive.isEnabled =
            activeState == GatewayService.FrnStatus.CONNECTED ||
            activeState == GatewayService.FrnStatus.CONNECTING
        activeBox.addView(disconnectActive)

        root.addView(activeBox, LinearLayout.LayoutParams(
            -1, -2
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })


        root.addView(label(
            "FRN-SystemManager auswählen",
            16f, Color.WHITE, true
        ).apply {
            setPadding(0, dp(18), 0, dp(8))
        })
        val options = listOf(
            "Alle SystemManager",
            "DE Original",
            "DE Alternativ",
            "Russland"
        )

        val selector = Spinner(this)
        selector.adapter = ArrayAdapter(
            this,
            R.layout.frn_spinner_item,
            options
        )
        (selector.adapter as ArrayAdapter<*>).setDropDownViewResource(R.layout.frn_spinner_item)
        selector.minimumHeight = dp(52)
        val spinnerFrame = android.widget.FrameLayout(this)

        spinnerFrame.addView(
            selector,
            android.widget.FrameLayout.LayoutParams(-1, -1)
        )

        val dropdownArrow = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.frn_dropdown_arrow)
            contentDescription = "SystemManager auswählen"
            isClickable = false
            isFocusable = false
        }

        spinnerFrame.addView(
            dropdownArrow,
            android.widget.FrameLayout.LayoutParams(
                dp(32),
                dp(32),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                rightMargin = dp(8)
            }
        )

        root.addView(
            spinnerFrame,
            LinearLayout.LayoutParams(-1, dp(52))
        )

        val status = label(
            "Noch keine Serverliste geladen",
            14f,
            muted
        ).apply {
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        val scroll = ScrollView(this)
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        refreshFrnServerCounts = {
            if (currentPage == "FRN") {
                val connected = gatewayService?.getFrnStatus() ==
                    GatewayService.FrnStatus.CONNECTED
                org.funkgateway.mobile.frn.FrnServerListView.updateLiveCounts(
                    results,
                    if (connected) gatewayService?.getConnectedFrnRoom() else null,
                    if (connected) gatewayService?.getFrnParticipants()
                        ?.entries?.size?.takeIf { it > 0 } else null
                )
            }
        }
        fun selectedManagerFilter(): String = when (selector.selectedItemPosition) {
            1 -> "de_orig"
            2 -> "de_alt"
            3 -> "ru"
            else -> ""
        }
        var favoritesOnly = false
        scroll.addView(results)

        selector.setSelection(FrnCache.selectedManager)

        if (FrnCache.lastUpdated > 0L) {
            status.text = frnCacheStatusText()

            org.funkgateway.mobile.frn.FrnServerListView.populate(this, results, FrnCache.rooms, "", selectedManagerFilter(), onRoomClick = { room -> showFrnRoomSelection(room) }, favoritesOnly = favoritesOnly,
                activeRoom = gatewayService?.getConnectedFrnRoom()
                    ?.takeIf {
                        gatewayService?.getFrnStatus() ==
                            GatewayService.FrnStatus.CONNECTED
                    },
                liveClientCount = gatewayService?.getFrnParticipants()
                    ?.entries?.size?.takeIf { it > 0 })
        }

        val searchField = android.widget.EditText(this).apply {
            hint = "Server oder Raum suchen …"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panel)
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        searchRow.addView(
            searchField,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val clearSearch = android.widget.TextView(this).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            contentDescription = "Suche löschen"
            setOnClickListener { searchField.text.clear() }
        }
        searchRow.addView(
            clearSearch,
            LinearLayout.LayoutParams(dp(48), dp(52))
        )
        root.addView(searchRow)

        fun showFavoriteSelection() {
            org.funkgateway.mobile.frn.FrnServerListView.populate(
                this@MainActivity,
                results,
                FrnCache.rooms,
                searchField.text.toString(),
                selectedManagerFilter(),
                onRoomClick = { room -> showFrnRoomSelection(room) },
                favoritesOnly = favoritesOnly,
                activeRoom = gatewayService?.getConnectedFrnRoom()
                    ?.takeIf {
                        gatewayService?.getFrnStatus() ==
                            GatewayService.FrnStatus.CONNECTED
                    },
                liveClientCount = gatewayService?.getFrnParticipants()
                    ?.entries?.size?.takeIf { it > 0 }
            )
        }

        val favoritesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val allServersButton = button("Alle Server") {
            favoritesOnly = false
            showFavoriteSelection()
        }

        val favoriteServersButton = button("★ Favoriten") {
            favoritesOnly = true
            showFavoriteSelection()
        }

        fun refreshFavoriteButtons() {
            allServersButton.setTextColor(
                if (favoritesOnly) muted else accent
            )
            favoriteServersButton.setTextColor(
                if (favoritesOnly) accent else muted
            )
        }

        favoritesRow.addView(
            allServersButton,
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )
        favoritesRow.addView(
            favoriteServersButton,
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        allServersButton.setOnClickListener {
            favoritesOnly = false
            showFavoriteSelection()
            refreshFavoriteButtons()
        }

        favoriteServersButton.setOnClickListener {
            favoritesOnly = true
            showFavoriteSelection()
            refreshFavoriteButtons()
        }

        root.addView(favoritesRow)
        refreshFavoriteButtons()


        clearSearch.visibility = android.view.View.GONE
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearSearch.visibility = if (s.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                org.funkgateway.mobile.frn.FrnServerListView.populate(
                    this@MainActivity, results, FrnCache.rooms, s?.toString() ?: "", selectedManagerFilter(), onRoomClick = { room -> showFrnRoomSelection(room) }, favoritesOnly = favoritesOnly,
                activeRoom = gatewayService?.getConnectedFrnRoom()
                    ?.takeIf {
                        gatewayService?.getFrnStatus() ==
                            GatewayService.FrnStatus.CONNECTED
                    },
                liveClientCount = gatewayService?.getFrnParticipants()
                    ?.entries?.size?.takeIf { it > 0 })
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        selector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                FrnCache.selectedManager = position
                FrnCache.save(this@MainActivity)
                org.funkgateway.mobile.frn.FrnServerListView.populate(
                    this@MainActivity, results, FrnCache.rooms,
                    searchField.text.toString(), selectedManagerFilter(), onRoomClick = { room -> showFrnRoomSelection(room) }, favoritesOnly = favoritesOnly,
                activeRoom = gatewayService?.getConnectedFrnRoom()
                    ?.takeIf {
                        gatewayService?.getFrnStatus() ==
                            GatewayService.FrnStatus.CONNECTED
                    },
                liveClientCount = gatewayService?.getFrnParticipants()
                    ?.entries?.size?.takeIf { it > 0 })
            }
        }
        val refresh = button("Serverliste aktualisieren") {
            if (!frnManagerRefreshBusy.compareAndSet(false, true)) {
                android.widget.Toast.makeText(
                    this,
                    "FRN-Serverliste wird bereits aktualisiert.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@button
            }
            status.text = "Lade FRN-SystemManager …"

            val selected = selector.selectedItemPosition
            FrnCache.selectedManager = selected

            val managers = when (selected) {
                1 -> listOf(FrnSystemManager.managers[0])
                2 -> listOf(FrnSystemManager.managers[1])
                3 -> listOf(FrnSystemManager.managers[2])
                else -> FrnSystemManager.managers
            }

            Thread {
                try {
                val rows = mutableListOf<
                    org.funkgateway.mobile.frn.FrnRoom
                >()
                val errors = mutableListOf<String>()

                for (manager in managers) {
                    try {
                        rows.addAll(FrnSystemManager.load(manager))
                    } catch (e: Exception) {
                        errors.add(
                            manager.name + ": " +
                            (e.message ?: "Verbindungsfehler")
                        )
                    }
                }

                runOnUiThread {
                    try {
                    val successfulManagers = managers.map { it.id }
                        .filter { id ->
                            errors.none { error ->
                                managers.any { it.id == id && error.startsWith(it.name + ":") }
                            }
                        }.toSet()
                    recordFrnManagerRefreshResult(
                        successfulManagers.isNotEmpty()
                    )

                    if (successfulManagers.isNotEmpty()) {
                        FrnCache.rooms = FrnCache.rooms.filter {
                            it.manager !in successfulManagers
                        } + rows
                        FrnCache.lastUpdated = System.currentTimeMillis()
                        FrnCache.save(this@MainActivity)
                    }
                    if (frnActivityActive &&
                        currentPage == "FRN" &&
                        frnRefreshResults === results) {
                        status.text =
                            frnCacheStatusText() +
                            if (errors.isNotEmpty()) {
                                "\n" + errors.joinToString("\n")
                            } else ""

                        val previousScrollY = scroll.scrollY
                        org.funkgateway.mobile.frn.FrnServerListView.populate(this@MainActivity, results, FrnCache.rooms, searchField.text.toString(), selectedManagerFilter(), onRoomClick = { room -> showFrnRoomSelection(room) }, favoritesOnly = favoritesOnly,
                activeRoom = gatewayService?.getConnectedFrnRoom()
                    ?.takeIf {
                        gatewayService?.getFrnStatus() ==
                            GatewayService.FrnStatus.CONNECTED
                    },
                liveClientCount = gatewayService?.getFrnParticipants()
                    ?.entries?.size?.takeIf { it > 0 })
                        scroll.post {
                            if (frnActivityActive &&
                                currentPage == "FRN" &&
                                frnRefreshResults === results
                            ) {
                                scroll.scrollTo(0, previousScrollY)
                            }
                        }
                    }
                    } finally {
                        frnManagerRefreshBusy.set(false)
                    }
                }
                } catch (e: Exception) {
                    frnManagerRefreshBusy.set(false)
                    android.util.Log.e("FunkGateway", "FRN-Aktualisierung fehlgeschlagen", e)
                }
            }.start()
        }

        root.addView(
            refresh,
            LinearLayout.LayoutParams(-1, dp(60)).apply {
                topMargin = dp(12)
            }
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) }
        )



        refreshFrnCachedList = {
            if (frnActivityActive &&
                currentPage == "FRN" &&
                frnRefreshResults === results
            ) {
                val previousScrollY = scroll.scrollY
                status.text =
                    frnCacheStatusText()
                org.funkgateway.mobile.frn.FrnServerListView.populate(
                    this@MainActivity,
                    results,
                    FrnCache.rooms,
                    searchField.text.toString(),
                    selectedManagerFilter(),
                    onRoomClick = { room -> showFrnRoomSelection(room) },
                    favoritesOnly = favoritesOnly,
                    activeRoom = gatewayService?.getConnectedFrnRoom()
                        ?.takeIf {
                            gatewayService?.getFrnStatus() ==
                                GatewayService.FrnStatus.CONNECTED
                        },
                    liveClientCount = gatewayService?.getFrnParticipants()
                        ?.entries?.size?.takeIf { it > 0 }
                )
                scroll.post {
                    if (frnActivityActive &&
                        currentPage == "FRN" &&
                        frnRefreshResults === results
                    ) {
                        scroll.scrollTo(0, previousScrollY)
                    }
                }
            }
        }
        frnRefreshButton = refresh
        frnRefreshResults = results

        showGatewayScreen(root)
    }

    private fun showFrnRooms() {
        val activeRoom = gatewayService?.getConnectedFrnRoom()
        val connected = gatewayService?.getFrnStatus() ==
            GatewayService.FrnStatus.CONNECTED

        if (!connected || activeRoom == null) {
            currentPage = "FRN_ROOMS"

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(28), dp(20), dp(20))
                setBackgroundColor(bg)
            }

            content.addView(label(
                "FRN · Räume", 24f, Color.WHITE, true
            ))
            content.addView(label(
                "Keine aktive FRN-Verbindung. " +
                    "Bitte zuerst einen Server verbinden.",
                16f, muted
            ).apply {
                setPadding(0, dp(20), 0, dp(20))
            })
            content.addView(button("Zur Serverliste") {
                showFrn()
            })

            showGatewayScreen(ScrollView(this).apply {
                addView(content)
            })
            return
        }

        selectedFrnRoom = activeRoom
        val selected = activeRoom

        currentPage = "FRN_ROOMS"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        root.addView(label("FRN · Räume", 24f, Color.WHITE, true))
        root.addView(label(
            "${selected.server}:${selected.port}",
            15f,
            muted
        ))

        val rooms = availableFrnRooms()
        root.addView(label(
            "${rooms.size} bekannte Räume",
            16f,
            Color.WHITE
        ))

        if (rooms.isEmpty()) {
            root.addView(label(
                "Keine Raumliste im Cache. Bitte Serverliste aktualisieren.",
                15f,
                muted
            ))
        }

        for (room in rooms) {
            val isSelected =
                gatewayService?.getFrnStatus() == GatewayService.FrnStatus.CONNECTED &&
                activeRoom.server.equals(room.server, ignoreCase = true) &&
                activeRoom.port == room.port &&
                activeRoom.room.equals(room.room, ignoreCase = true)

            val managerCountKnown = FrnCache.rooms.any {
                it.server.equals(room.server, ignoreCase = true) &&
                    it.port == room.port &&
                    it.room.equals(room.room, ignoreCase = true)
            }
            val liveCount = if (isSelected) {
                gatewayService?.getFrnParticipants()?.entries?.size
                    ?.takeIf { it > 0 }
            } else {
                null
            }
            val countText = when {
                liveCount != null -> "$liveCount Teilnehmer"
                isSelected -> "Teilnehmerzahl unbekannt"
                managerCountKnown -> "${room.clients} Teilnehmer"
                else -> "Teilnehmerzahl unbekannt"
            }
            root.addView(button(
                "${if (isSelected) "✓ " else ""}" +
                    "${room.room.ifBlank { "(kein Raum)" }}" +
                    " · $countText"
            ) {
                showFrnRoomSelection(room)
            })
        }

        root.addView(button("← Zurück zum FRN-Raum") {
            showFrnRoom(selected)
        })

        showGatewayScreen(scroll)
    }

    private fun refreshFrnOccupancyUi() {
        when (currentPage) {
            "FRN" -> refreshFrnServerCounts?.invoke()
            "FRN_ROOMS" -> showFrnRooms()
        }
    }

    private fun handleFrnUiEvent(
        event: org.funkgateway.mobile.frn.FrnEvent
    ) {
        when (event) {
            is org.funkgateway.mobile.frn.FrnEvent.ClientList -> {
                when (currentPage) {
                    "FRN" -> refreshFrnServerCounts?.invoke()
                    "FRN_ROOMS" -> showFrnRooms()
                    "FRN_PARTICIPANTS" ->
                        refreshFrnParticipantsView?.invoke()
                }
            }
            is org.funkgateway.mobile.frn.FrnEvent.Audio,
            is org.funkgateway.mobile.frn.FrnEvent.Idle -> {
                if (currentPage == "FRN_PARTICIPANTS") {
                    refreshFrnParticipantsView?.invoke()
                }
            }
            else -> Unit
        }
    }

    private fun showFrnParticipants() {
        currentPage = "FRN_PARTICIPANTS"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        root.addView(label("FRN · Teilnehmer", 24f, Color.WHITE, true))

        val connectionStatus = label("", 15f, muted).apply {
            setPadding(0, dp(12), 0, dp(16))
        }
        root.addView(connectionStatus)

        val participantsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(participantsContainer)

        fun refreshParticipantsView() {
            val service = gatewayService
            val connected = service?.getFrnStatus() ==
                GatewayService.FrnStatus.CONNECTED

            val room = service?.getConnectedFrnRoom()
            connectionStatus.text = when {
                connected && room != null ->
                    "Verbunden: ${room.server} · ${room.room}"
                service?.getFrnStatus() == GatewayService.FrnStatus.CONNECTING ->
                    "FRN-Verbindung wird aufgebaut …"
                else ->
                    "Keine aktive FRN-Verbindung."
            }

            participantsContainer.removeAllViews()

            val snapshot = if (connected) {
                service?.getFrnParticipants()
            } else {
                null
            }
            val entries = snapshot?.entries.orEmpty()

            if (entries.isEmpty()) {
                participantsContainer.addView(label(
                    "Noch keine Teilnehmerdaten empfangen.",
                    16f, muted
                ))
            }

            entries.forEachIndexed { index, entry ->
                val match = Regex(
                    "<ON>(.*?)</ON>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(entry)

                val name = android.text.Html.fromHtml(
                    match?.groupValues?.get(1) ?: "Teilnehmer #${index + 1}",
                    android.text.Html.FROM_HTML_MODE_LEGACY
                ).toString().trim()

                val speaking = snapshot?.activeIndex == index + 1
                val color = if (speaking) {
                    Color.rgb(239, 68, 68)
                } else {
                    Color.rgb(34, 197, 94)
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }

                row.addView(android.widget.ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dp(24), dp(36)
                    ).apply {
                        marginEnd = dp(12)
                    }
                    setImageDrawable(HandheldRadioDrawable(color))
                })

                row.addView(label(
                    name.ifEmpty { "Teilnehmer #${index + 1}" },
                    16f,
                    Color.WHITE
                ))

                participantsContainer.addView(row)
            }
        }

        refreshFrnParticipantsView = { refreshParticipantsView() }
        refreshParticipantsView()

        showGatewayScreen(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun connectFrnFromSelection(
        room: org.funkgateway.mobile.frn.FrnRoom
    ) {
        val service = gatewayService
        if (service == null) {
            android.widget.Toast.makeText(
                this, "Gateway-Dienst noch nicht bereit.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        if (service.getFrnStatus() != GatewayService.FrnStatus.DISCONNECTED) {
            android.widget.Toast.makeText(
                this, "FRN ist bereits verbunden oder verbindet gerade.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val saved = org.funkgateway.mobile.frn.FrnSettingsStore.load(this)
            if (saved.email.isBlank() || saved.password.isBlank() ||
                saved.callsign.isBlank()
            ) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("FRN-Anmeldung fehlt")
                    .setMessage("Bitte zuerst die FRN-Einstellungen ausfüllen.")
                    .setPositiveButton("Einstellungen") { _, _ ->
                        showFrnSettings()
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
                return
            }

            val config = org.funkgateway.mobile.frn.FrnLoginConfig(
                room = room,
                email = saved.email,
                password = saved.password,
                callsign = saved.callsign,
                description = saved.description,
                country = saved.country,
                city = saved.city,
                vx = saved.vx,
                clientType = saved.clientType,
                bandChannel = saved.bandChannel
            )

            val startIntent = Intent(
                this, GatewayService::class.java
            ).apply {
                action = GatewayService.ACTION_START
            }
            startForegroundService(startIntent)

            service.connectFrn(
                config = config,
                onEvent = { event ->
                    handleFrnUiEvent(event)
                },
                onError = { error ->
                    android.app.AlertDialog.Builder(this)
                        .setTitle("FRN-Verbindungsfehler")
                        .setMessage(
                            error.message ?: "Verbindung nicht möglich."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    if (currentPage == "FRN_PARTICIPANTS") {
                        refreshFrnParticipantsView?.invoke()
                    }
                },
                onConnected = {
                    selectedFrnRoom = service.getConnectedFrnRoom()
                    refreshFrnOccupancyUi()
                    if (currentPage == "FRN_PARTICIPANTS") {
                        refreshFrnParticipantsView?.invoke()
                    }
                },
                onDisconnected = {
                    refreshFrnOccupancyUi()
                    if (currentPage == "FRN_PARTICIPANTS") {
                        refreshFrnParticipantsView?.invoke()
                    }
                }
            )

            showFrnParticipants()
        } catch (e: Exception) {
            android.app.AlertDialog.Builder(this)
                .setTitle("FRN-Verbindung")
                .setMessage(e.message ?: "Verbindung nicht möglich.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showFrnRoomSelection(
        room: org.funkgateway.mobile.frn.FrnRoom
    ) {
        val service = gatewayService
        val state = service?.getFrnStatus()
        val active = service?.getConnectedFrnRoom()

        val sameRoom = active != null &&
            active.server.equals(room.server, ignoreCase = true) &&
            active.port == room.port &&
            active.room.equals(room.room, ignoreCase = true)

        val selectedText =
            "${room.server}:${room.port}\\nRaum: ${room.room}"

        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("FRN · Raum auswählen")
            .setNegativeButton("Abbrechen", null)

        when {
            service == null -> {
                builder.setMessage(
                    "$selectedText\\n\\nGateway-Dienst noch nicht bereit."
                )
                builder.setPositiveButton("OK", null)
            }

            state == GatewayService.FrnStatus.DISCONNECTED -> {
                builder.setMessage(
                    "$selectedText\\n\\nKeine aktive Verbindung."
                )
                builder.setPositiveButton("Verbinden") { _, _ ->
                    connectFrnFromSelection(room)
                }
            }

            state == GatewayService.FrnStatus.CONNECTING -> {
                builder.setMessage(
                    "$selectedText\\n\\nEine FRN-Verbindung wird gerade aufgebaut."
                )
                builder.setPositiveButton("OK", null)
            }

            sameRoom -> {
                builder.setMessage(
                    "$selectedText\\n\\nDu bist bereits mit diesem Raum verbunden."
                )
                builder.setPositiveButton("Teilnehmer ansehen") { _, _ ->
                    showFrnParticipants()
                }
            }

            else -> {
                val activeText = if (active == null) {
                    "Verbindungsdaten werden geladen."
                } else {
                    "${active.server}:${active.port} / ${active.room}"
                }

                builder.setTitle("FRN · Raumwechsel")
                builder.setMessage(
                    "Aktuell verbunden:\n$activeText\n\n" +
                    "Gewählter Raum:\n${room.server}:${room.port} / ${room.room}\n\n" +
                    "Möchtest du die bestehende Verbindung trennen " +
                    "und in den neuen Raum wechseln?"
                )
                builder.setPositiveButton("Raum wechseln") { _, _ ->
                    val currentService = gatewayService
                    val latest = currentService?.getConnectedFrnRoom()
                    val unchanged = active != null && latest != null &&
                        latest.server.equals(active.server, ignoreCase = true) &&
                        latest.port == active.port &&
                        latest.room.equals(active.room, ignoreCase = true)

                    if (currentService?.getFrnStatus() !=
                        GatewayService.FrnStatus.CONNECTED || !unchanged
                    ) {
                        android.widget.Toast.makeText(
                            this,
                            "Verbindung hat sich geändert. Bitte erneut auswählen.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        currentService.disconnectFrn()
                        connectFrnFromSelection(room)
                    }
                }
            }
        }

        builder.show()
    }

    private fun showFrnRoom(room: org.funkgateway.mobile.frn.FrnRoom) {
        selectedFrnRoom = room
        currentPage = "FRN_ROOM"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        root.addView(label("FRN · Verbindung", 24f, Color.WHITE, true))
        root.addView(label("Server: ${room.server}", 16f, Color.WHITE))
        root.addView(label("Port: ${room.port}", 16f, Color.WHITE))
        root.addView(label("Raum: ${room.room}", 18f, Color.WHITE))
        val participantCountLabel = label(
            "Teilnehmer: unbekannt", 16f, muted
        )
        root.addView(participantCountLabel)

        val status = label("Noch nicht verbunden", 16f, muted)
        root.addView(status)


        val participantsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val participantsLabel = label(
            "Noch keine Teilnehmerdaten empfangen.",
            15f,
            Color.WHITE
        )
        participantsContainer.addView(participantsLabel)

        fun frnParticipantName(entry: String, index: Int): String {
            val match = Regex("<ON>(.*?)</ON>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)
            return match?.groupValues?.get(1)
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&quot;", "\\\"")
                ?.replace("&amp;", "&")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Teilnehmer #$index"
        }

        val participantIcons = mutableListOf<android.widget.ImageView>()
        var displayedParticipants: List<String> = emptyList()
        var displayedSpeaker: Int? = null

        fun refreshParticipants() {
            val snapshot = gatewayService?.getFrnParticipants()
            val entries = snapshot?.entries ?: emptyList()
            val connected = gatewayService?.getFrnStatus() ==
                GatewayService.FrnStatus.CONNECTED
            participantCountLabel.text =
                if (connected && entries.isNotEmpty()) {
                    "Teilnehmer: ${entries.size}"
                } else {
                    "Teilnehmer: unbekannt"
                }

            if (entries != displayedParticipants) {
                displayedParticipants = entries.toList()
                participantIcons.clear()
                participantsContainer.removeAllViews()
                participantsContainer.addView(participantsLabel)

                entries.forEachIndexed { index, entry ->
                    val participantRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, dp(3), 0, dp(3))
                    }

                    val radioIcon = android.widget.ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            dp(24), dp(36)
                        ).apply {
                            marginEnd = dp(12)
                        }
                        setImageDrawable(
                            HandheldRadioDrawable(Color.rgb(34, 197, 94))
                        )
                    }

                    participantIcons.add(radioIcon)
                    participantRow.addView(radioIcon)
                    participantRow.addView(
                        label(
                            frnParticipantName(entry, index + 1),
                            16f,
                            Color.WHITE
                        )
                    )
                    participantsContainer.addView(participantRow)
                }
                displayedSpeaker = null
            }

            participantsLabel.visibility =
                if (entries.isEmpty()) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }

            if (entries.isEmpty()) {
                participantsLabel.text =
                    if (snapshot == null) {
                        "Gateway-Dienst noch nicht bereit."
                    } else {
                        "Noch keine Teilnehmerdaten empfangen."
                    }
            }

            val speaker = snapshot?.activeIndex
                ?.takeIf { it in 1..entries.size }

            if (speaker != displayedSpeaker || entries.isEmpty()) {
                participantIcons.forEachIndexed { index, icon ->
                    val color = if (speaker == index + 1) {
                        Color.rgb(239, 68, 68)
                    } else {
                        Color.rgb(34, 197, 94)
                    }
                    icon.setImageDrawable(HandheldRadioDrawable(color))
                }
                displayedSpeaker = speaker
            }
        }

        lateinit var connectButton: Button
        lateinit var disconnectButton: Button

        fun updateButtons() {
            val state = gatewayService?.getFrnStatus()
            connectButton.isEnabled =
                state == GatewayService.FrnStatus.DISCONNECTED
            disconnectButton.isEnabled =
                state == GatewayService.FrnStatus.CONNECTED ||
                state == GatewayService.FrnStatus.CONNECTING
        }

        connectButton = button("Mit FRN verbinden") {
            val service = gatewayService
            if (service == null) {
                status.text = "Gateway-Dienst noch nicht bereit."
            } else {
                try {
                    val saved = org.funkgateway.mobile.frn.FrnSettingsStore.load(this)
                    if (saved.email.isBlank() || saved.password.isBlank() ||
                        saved.callsign.isBlank()) {
                        status.text = "Bitte zuerst die FRN-Einstellungen ausfüllen."
                    } else {
                        val config = org.funkgateway.mobile.frn.FrnLoginConfig(
                            room = room,
                            email = saved.email,
                            password = saved.password,
                            callsign = saved.callsign,
                            description = saved.description,
                            country = saved.country,
                            city = saved.city,
                            vx = saved.vx,
                            clientType = saved.clientType,
                            bandChannel = saved.bandChannel
                        )
                        val startIntent = Intent(
                            this,
                            GatewayService::class.java
                        ).apply {
                            action = GatewayService.ACTION_START
                        }
                        startForegroundService(startIntent)

                        service.connectFrn(
                            config = config,
                            onEvent = { event ->
                                if (currentPage == "FRN_ROOM" &&
                                    (event is org.funkgateway.mobile.frn.FrnEvent.ClientList ||
                                     event is org.funkgateway.mobile.frn.FrnEvent.Audio ||
                                     event is org.funkgateway.mobile.frn.FrnEvent.Idle)
                                ) {
                                    refreshParticipants()
                                }
                                handleFrnUiEvent(event)
                            },
                            onError = { error ->
                                if (currentPage == "FRN_ROOM") {
                                    status.text = "FRN-Fehler: ${error.message ?: "Unbekannter Fehler"}"
                                    updateButtons()
                                }
                            },
                            onConnected = {
                                refreshFrnOccupancyUi()
                                if (currentPage == "FRN_ROOM") {
                                    status.text = "Mit FRN verbunden."
                                    updateButtons()
                                }
                            },
                            onDisconnected = {
                                refreshFrnOccupancyUi()
                                if (currentPage == "FRN_ROOM") {
                                    if (!status.text.toString().startsWith("FRN-Fehler:")) {
                                        status.text = "FRN-Verbindung beendet."
                                    }
                                    updateButtons()
                                }
                            }
                        )
                        status.text = "FRN-Verbindung wird aufgebaut ..."
                        updateButtons()
                    }
                } catch (e: Exception) {
                    status.text = "FRN: ${e.message ?: "Verbindung nicht möglich"}"
                    updateButtons()
                }
            }
        }
        root.addView(connectButton)

        disconnectButton = button("Trennen") {
            gatewayService?.disconnectFrn()
            status.text = "FRN-Verbindung getrennt."
            updateButtons()
            refreshParticipants()
        }
        root.addView(disconnectButton)
        refreshFrnRoomStatus = {
            if (currentPage == "FRN_ROOM") {
                when (gatewayService?.getFrnStatus()) {
                    GatewayService.FrnStatus.CONNECTED ->
                        status.text = "FRN ist bereits verbunden."
                    GatewayService.FrnStatus.CONNECTING ->
                        status.text = "FRN-Verbindung wird aufgebaut ..."
                    GatewayService.FrnStatus.DISCONNECTED ->
                        status.text = "Noch nicht verbunden."
                    null ->
                        status.text = "Gateway-Dienst wird vorbereitet."
                }
                updateButtons()
                refreshParticipants()
            }
        }
        when (gatewayService?.getFrnStatus()) {
            GatewayService.FrnStatus.CONNECTED ->
                status.text = "FRN ist bereits verbunden."
            GatewayService.FrnStatus.CONNECTING ->
                status.text = "FRN-Verbindung wird aufgebaut ..."
            GatewayService.FrnStatus.DISCONNECTED ->
                status.text = "Noch nicht verbunden."
            null ->
                status.text = "Gateway-Dienst wird vorbereitet."
        }
        updateButtons()
        refreshParticipants()

        root.addView(button("Räume auf diesem Server") {
            showFrnRooms()
        })

        root.addView(button("⚙ FRN-Einstellungen") {
            showFrnSettings()
        })
        root.addView(button("Noch kein FRN-Konto? Jetzt registrieren") {
            showFrnRegistration(room)
        })

        root.addView(button("← Zurück zur Serverliste") {
            showFrn()
        })

        showGatewayScreen(scroll)
    }

    private fun showFrnRegistration(room: org.funkgateway.mobile.frn.FrnRoom? = null) {
        currentPage = "FRN_REGISTRATION"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        scroll.addView(root)

        root.addView(label("FRN · Registrierung", 24f, Color.WHITE, true))
        root.addView(label(
            "Neues FRN-Konto anlegen und Passwort per E-Mail anfordern.",
            15f, muted
        ))

        fun addDropdown(spinner: Spinner) {
            val frame = android.widget.FrameLayout(this)

            frame.addView(
                spinner,
                android.widget.FrameLayout.LayoutParams(-1, -1)
            )

            val arrow = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.frn_dropdown_arrow)
                contentDescription = "Auswahl öffnen"
                isClickable = false
                isFocusable = false
            }

            frame.addView(
                arrow,
                android.widget.FrameLayout.LayoutParams(
                    dp(32), dp(32),
                    Gravity.END or Gravity.CENTER_VERTICAL
                ).apply {
                    rightMargin = dp(8)
                }
            )

            root.addView(
                frame,
                LinearLayout.LayoutParams(-1, dp(48))
            )
        }
        root.addView(label("FRN-SystemManager", 16f, Color.WHITE, true))
        val manager = Spinner(this)
        val managerEntries = org.funkgateway.mobile.frn.FrnSystemManager.managers
        val managers = managerEntries.map { it.name }
        manager.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            managers
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View = super.getView(position, convertView, parent).apply {
                (this as TextView).setTextColor(Color.WHITE)
            }
        }
        manager.setBackgroundColor(panel)
        addDropdown(manager)

        fun field(title: String, hint: String): android.widget.EditText {
            root.addView(label(title, 16f, Color.WHITE, true))
            return android.widget.EditText(this).apply {
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                setHint(hint)
                setSingleLine(true)
                root.addView(this)
            }
        }

        val email = field("E-Mail-Adresse", "name@beispiel.de")
        email.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        val username = field("Benutzername / Rufzeichen", "Dein FRN-Name")
        root.addView(label("Land", 16f, Color.WHITE, true))

        val countries = java.util.Locale.getISOCountries()
            .map { code ->
                val locale = java.util.Locale("", code)
                locale.getDisplayCountry(java.util.Locale.GERMAN) to
                    locale.getDisplayCountry(java.util.Locale.ENGLISH)
            }
            .distinctBy { it.first }
            .sortedWith(
                compareBy(java.text.Collator.getInstance(java.util.Locale.GERMAN)) {
                    it.first
                }
            )

        val countryNames = countries.map { it.first }

        val country = Spinner(this)
        val countryAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            countryNames
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View = super.getView(position, convertView, parent).apply {
                (this as TextView).setTextColor(Color.WHITE)
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup
            ): View = super.getDropDownView(position, convertView, parent).apply {
                (this as TextView).setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
            }
        }
        countryAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )
        country.adapter = countryAdapter
        country.setBackgroundColor(panel)
        country.setSelection(countryNames.indexOf("Deutschland").coerceAtLeast(0))
        addDropdown(country)
        val city = field("Ort", "Dein Wohnort")
        val description = field("Beschreibung", "Zum Beispiel: FunkGateway Mobile")

        root.addView(label(
            "Die Registrierung ist derzeit nur bei DE Original freigeschaltet. " +
            "Bitte verwende für den ersten Test ein neues FRN-Konto.",
            14f, muted
        ))

        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(12))
        })
        root.addView(button("FRN-Passwort anfordern") {
            val mail = email.text.toString().trim()
            val name = username.text.toString().trim()
            val place = city.text.toString().trim()
            val info = description.text.toString().trim()

            when {
                !android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches() ->
                    email.error = "Bitte eine gültige E-Mail-Adresse eingeben."

                name.isBlank() ->
                    username.error = "Bitte einen Benutzernamen eingeben."

                place.isBlank() ->
                    city.error = "Bitte einen Ort eingeben."

                else -> {
                    val selectedManager = managerEntries[manager.selectedItemPosition]
                    val selectedCountry = countries[country.selectedItemPosition].second

                    android.app.AlertDialog.Builder(this)
                        .setTitle("FRN-Registrierung prüfen")
                        .setMessage(
                            "SystemManager: ${selectedManager.name}\n" +
                            "Benutzername: $name\n" +
                            "E-Mail: $mail\n" +
                            "Land: $selectedCountry\n" +
                            "Ort: $place\n" +
                            "Beschreibung: $info\n\n" +
                            "Bitte prüfe deine Angaben vor dem Absenden."
                        )
                        .setPositiveButton("Weiter") { _, _ ->
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Datenschutz und Registrierung")
                                .setMessage(
                                    "Deine E-Mail-Adresse, dein Benutzername, " +
                                    "dein Land, dein Ort und deine Beschreibung " +
                                    "werden bei einer Registrierung über das " +
                                    "klassische FRN-Protokoll unverschlüsselt " +
                                    "an den ausgewählten SystemManager übertragen.\n\n" +
                                    "Mit \"Jetzt registrieren\" werden diese Daten " +
                                    "tatsächlich an DE Original gesendet."
                                )
                                .setNegativeButton("Abbrechen", null)
                                .setPositiveButton("Jetzt registrieren") { _, _ ->
                                    if (frnRegistrationInProgress) {
                                        android.widget.Toast.makeText(
                                            this,
                                            "Eine Registrierung läuft bereits.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else if (selectedManager.id != "de_orig") {
                                        android.app.AlertDialog.Builder(this)
                                            .setTitle("Noch nicht freigeschaltet")
                                            .setMessage(
                                                "Die Registrierung ist derzeit nur bei DE Original möglich."
                                            )
                                            .setPositiveButton("OK", null)
                                            .show()
                                    } else {
                                        frnRegistrationInProgress = true

                                        val progress = android.app.AlertDialog.Builder(this)
                                            .setTitle("FRN-Registrierung")
                                            .setMessage("Anfrage wird an DE Original gesendet …")
                                            .setCancelable(false)
                                            .create()
                                        progress.show()

                                        val request = org.funkgateway.mobile.frn.FrnRegistration.Request(
                                            email = mail,
                                            username = name,
                                            country = selectedCountry,
                                            city = place,
                                            description = info
                                        )

                                        Thread {
                                            val result = org.funkgateway.mobile.frn.FrnRegistration.register(
                                                selectedManager,
                                                request
                                            )

                                            runOnUiThread {
                                                frnRegistrationInProgress = false
                                                progress.dismiss()

                                                val message = when (result) {
                                                    is org.funkgateway.mobile.frn.FrnRegistration.Result.Accepted ->
                                                        "Der SystemManager hat die Registrierung angenommen. Bitte prüfe dein E-Mail-Postfach."

                                                    is org.funkgateway.mobile.frn.FrnRegistration.Result.AlreadyRegistered ->
                                                        "Dieses FRN-Konto ist bereits registriert."

                                                    is org.funkgateway.mobile.frn.FrnRegistration.Result.Error ->
                                                        result.message
                                                }

                                                android.app.AlertDialog.Builder(this)
                                                    .setTitle("FRN-Registrierung")
                                                    .setMessage(message)
                                                    .setPositiveButton("OK", null)
                                                    .show()
                                            }
                                        }.start()
                                    }
                                }
                                .show()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
        })

        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(12))
        })
        root.addView(button("← Zurück") {
            if (room != null) showFrnRoom(room) else showFrn()
        })

        showGatewayScreen(scroll)
    }

    private fun showPage(name: String) {
        currentPage = name

        if (name == "Mehr") {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(24))
            }

            content.addView(label("Mehr", 26f, Color.WHITE, true))
            content.addView(label(
                "Weitere Funktionen und Informationen",
                15f, muted
            ).apply {
                setPadding(0, dp(8), 0, dp(24))
            })

            val entries = listOf(
                "⚙  Einstellungen" to "Einstellungen",
                "🦜  Papageien" to "Papageien",
                "🔗  Verlinkung" to "Verlinkung",
                "❔  Hilfe" to "Hilfe",
                "ℹ  Über FunkGateway Mobile" to "Über FunkGateway Mobile"
            )

            entries.forEach { (title, destination) ->
                content.addView(button(title) {
                    if (destination == "Einstellungen") {
                        showSettings()
                    } else {
                        showPage(destination)
                    }
                })
            }

            showGatewayScreen(
                ScrollView(this).apply {
                    isFillViewport = true
                    addView(content)
                }
            )
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        if (name == "Über FunkGateway Mobile") {
            showAboutMobile()
            return
        }
        root.addView(label(name, 26f, Color.WHITE, true))
        root.addView(label(
            "Dieser Bereich wird in einer der nächsten Versionen eingerichtet.",
            16f, muted
        ).apply {
            setPadding(0, dp(20), 0, dp(28))
        })

        root.addView(button("← Zurück zur Übersicht") {
            showHome()
        }, LinearLayout.LayoutParams(-1, dp(64)))

        showGatewayScreen(root)
    }

    private fun showAboutMobile() {
        currentPage = "Über FunkGateway Mobile"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }
        val version = packageManager.getPackageInfo(packageName, 0)
            .versionName ?: "Unbekannt"

        root.addView(label("Über FunkGateway Mobile", 24f, Color.WHITE, true))
        root.addView(label("Version: $version", 16f, muted))
        root.addView(label(
            "FunkGateway Mobile – Funk und Sprachkommunikation.",
            16f, muted
        ))
        root.addView(button("Open-Source-Lizenzen") {
            showMobileLicenses()
        })
        root.addView(button("← Zurück zu Mehr") {
            showPage("Mehr")
        })
        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun showMobileLicenses() {
        currentPage = "Open-Source-Lizenzen"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }
        root.addView(label("Open-Source-Lizenzen", 24f, Color.WHITE, true))
        root.addView(label(
            "libgsm – Jutta Degener und Carsten Bormann",
            17f, Color.WHITE, true
        ))
        val licenseText = try {
            assets.open("licenses/libgsm.txt").bufferedReader().use {
                it.readText()
            }
        } catch (e: Exception) {
            "Lizenztext konnte nicht geladen werden: " +
                (e.message ?: "Unbekannter Fehler")
        }
        root.addView(label(licenseText, 14f, muted))
        root.addView(button("← Zurück zu Über FunkGateway") {
            showAboutMobile()
        })
        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun showFrnSettings() {
        currentPage = "FRN-Einstellungen"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }
        root.addView(label("📻 FRN-Einstellungen", 25f, Color.WHITE, true))
        root.addView(label("Anmeldung und Protokoll", 15f, muted))

        root.addView(button("Neues FRN-Konto registrieren") {
            showFrnRegistration()
        }.apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })

        val saved = try {
            org.funkgateway.mobile.frn.FrnSettingsStore.load(this)
        } catch (e: Exception) {
            org.funkgateway.mobile.frn.FrnSettings()
        }

        fun field(title: String, value: String, secret: Boolean = false):
            android.widget.EditText {
            root.addView(label(title, 14f, muted).apply {
                setPadding(0, dp(12), 0, dp(4))
            })
            return android.widget.EditText(this).apply {
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                background = rounded(panel)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                if (secret) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setText(value)
                root.addView(this, LinearLayout.LayoutParams(-1, dp(54)))
            }
        }

        val email = field("E-Mail-Adresse", saved.email)
        val password = field("Passwort", saved.password, true)

        root.addView(android.widget.CheckBox(this).apply {
            text = "Passwort anzeigen"
            setTextColor(muted)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                password.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (checked) {
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                password.setSelection(password.text.length)
            }
        })

        val callsign = field("Rufzeichen / Benutzername", saved.callsign)
        val description = field("Beschreibung", saved.description)
        val country = field("Land", saved.country)
        val city = field("Stadt", saved.city)
        val vx = field("VX · Protokollkennung", saved.vx)
        val clientType = field("CL · Clienttyp", saved.clientType)
        val bandChannel = field("BC · Band / Kanal", saved.bandChannel)

        val status = label("", 14f, accent).apply {
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        root.addView(button("Einstellungen speichern") {
            val settings = org.funkgateway.mobile.frn.FrnSettings(
                email = email.text.toString().trim(),
                password = password.text.toString(),
                callsign = callsign.text.toString().trim(),
                description = description.text.toString().trim(),
                country = country.text.toString().trim(),
                city = city.text.toString().trim(),
                vx = vx.text.toString().trim(),
                clientType = clientType.text.toString().trim(),
                bandChannel = bandChannel.text.toString().trim()
            )
            if (settings.email.isBlank() || settings.callsign.isBlank() ||
                settings.vx.isBlank()) {
                status.text = "Bitte E-Mail, Rufzeichen und VX angeben."
            } else {
                try {
                    org.funkgateway.mobile.frn.FrnSettingsStore.save(this, settings)
                    status.text = "FRN-Einstellungen gespeichert."
                } catch (e: Exception) {
                    status.text = "Speichern fehlgeschlagen: " +
                        (e.message ?: "Unbekannter Fehler")
                }
            }
        })

        root.addView(button("← Zurück zu Einstellungen") {
            showSettings()
        })
        showGatewayScreen(ScrollView(this).apply { addView(root) })
    }

    private fun showSettings() {
        currentPage = "Einstellungen"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }
        root.addView(label("⚙ Einstellungen", 26f, Color.WHITE, true))
        root.addView(label("Connect · Verbindungen", 19f, accent, true).apply {
            setPadding(0, dp(24), 0, dp(12))
        })
        root.addView(button("📻 FRN · Anmeldung und Protokoll") {
            showFrnSettings()
        })
        root.addView(button("🎧 Mumble · Verbindung") {
            showPage("Mumble-Einstellungen")
        })
        root.addView(button("Zello · Verbindung (später)") {
            showPage("Zello-Einstellungen")
        })
        root.addView(label("Weitere Einstellungen", 19f, accent, true).apply {
            setPadding(0, dp(24), 0, dp(12))
        })
        for (name in listOf("Audio & PTT", "Schutzfunktionen", "Darstellung")) {
            root.addView(button(name) {
                if (name == "Audio & PTT") {
                    showAudioPttSettings()
                } else {
                    showPage(name)
                }
            })
        }
        root.addView(label("Software & Updates", 19f, accent, true).apply {
            setPadding(0, dp(24), 0, dp(12))
        })
        root.addView(button("Software-Update") {
            showSoftwareUpdate()
        })
        root.addView(button("← Zurück zur Übersicht") { showHome() })
        showGatewayScreen(ScrollView(this).apply { addView(root) })
    }

    private fun showAudioPttSettings() {
        currentPage = "Audio & PTT"
        val prefs = getSharedPreferences("funkgateway_ptt", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label("🎙 Audio & PTT", 25f, Color.WHITE, true))
        root.addView(label(
            "Globale Push-to-Talk-Bedienung",
            18f, accent, true
        ).apply {
            setPadding(0, dp(20), 0, dp(12))
        })

        val currentMode = prefs.getString("ptt_mode", "HOLD") ?: "HOLD"
        val status = label(
            "Aktueller Modus: " +
                if (currentMode == "LATCH") "Latch" else "Hold",
            16f, muted
        )
        root.addView(status)

        fun selectMode(mode: String) {
            // Beim Wechsel niemals eine Aussendung fortsetzen.
            gatewayService?.stopPtt()
            prefs.edit().putString("ptt_mode", mode).apply()
            status.text = "Aktueller Modus: " +
                if (mode == "LATCH") "Latch" else "Hold"
            Toast.makeText(
                this,
                "PTT-Modus gespeichert",
                Toast.LENGTH_SHORT
            ).show()
        }

        root.addView(button("Hold – gedrückt halten") {
            selectMode("HOLD")
        })
        root.addView(label(
            "Die Aussendung endet beim Loslassen der PTT-Taste.",
            14f, muted
        ).apply {
            setPadding(0, 0, 0, dp(16))
        })

        root.addView(button("Latch – ein- und ausschalten") {
            selectMode("LATCH")
        })
        root.addView(label(
            "Einmal drücken: PTT ein. Erneut drücken: PTT aus.",
            14f, muted
        ).apply {
            setPadding(0, 0, 0, dp(16))
        })

        root.addView(label(
            "Standard: Hold. Die PTT-Sendetaste bleibt bis zum " +
                "Abschluss der Sicherheitsprüfungen deaktiviert.",
            14f, muted
        ).apply {
            setPadding(0, dp(16), 0, dp(16))
        })

        root.addView(button("← Zurück zu Einstellungen") {
            showSettings()
        })
        showGatewayScreen(ScrollView(this).apply { addView(root) })
    }

    private fun showSoftwareUpdate() {
        currentPage = "Software-Update"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label("Software-Update", 24f, Color.WHITE, true))

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val installedVersion = packageInfo.versionName ?: "Unbekannt"

        root.addView(label(
            "Installierte Version: $installedVersion",
            16f, muted
        ).apply {
            setPadding(0, dp(16), 0, dp(12))
        })

        val status = label(
            "Noch keine Updateprüfung durchgeführt.",
            15f, muted
        )
        root.addView(status)

        val changelog = label("", 14f, muted).apply {
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(changelog)

        var testChannel = false
        var updateRequestId = 0
        lateinit var installButton: android.widget.Button
        lateinit var channelButton: android.widget.Button
        var availableUpdate: MobileUpdateInfo? = null
        var downloading = false

        channelButton = button("Update-Kanal: Stabil") {
            if (downloading) {
                status.text = "Bitte laufenden Download abschließen."
                return@button
            }

            updateRequestId++
            testChannel = !testChannel
            channelButton.text = if (testChannel) {
                "Update-Kanal: Test"
            } else {
                "Update-Kanal: Stabil"
            }
            availableUpdate = null
            installButton.visibility = View.GONE

            status.text = "Bitte erneut nach Updates suchen."
            changelog.text = ""
        }
        root.addView(channelButton)

        installButton = button("Update herunterladen und installieren") {
            val update = availableUpdate

            if (update == null || downloading) {
                status.text = "Bitte zuerst nach Updates suchen."
            } else {
                val cachedApk = java.io.File(
                    cacheDir, "updates/FunkGateway-Mobile.apk"
                )

                val cacheValid = cachedApk.isFile &&
                    MobileUpdateVerifier.verify(
                        this, cachedApk, update
                    ).isSuccess

                if (cacheValid) {
                    status.text = "Gespeichertes Update geprüft."

                    if (!packageManager.canRequestPackageInstalls()) {
                        status.text =
                            "Bitte Installation aus dieser Quelle erlauben. " +
                            "Danach erneut auf Update installieren tippen."
                    } else {
                        status.text = "Öffne Android-Installation ..."
                    }

                    MobileUpdateInstaller.install(
                        this, cachedApk, update
                    ).onFailure { error ->
                        status.text =
                            "Installation konnte nicht gestartet werden: " +
                            (error.message ?: "Unbekannter Fehler")
                    }
                    return@button
                }

                if (cachedApk.exists()) {
                    cachedApk.delete()
                }

                downloading = true
                status.text = "Lade ${update.version} herunter ..."
                run {
                    MobileUpdateDownload.download(
                        this,
                        update,
                        onProgress = { percent ->
                            if (currentPage == "Software-Update") {
                                status.text = "Download: $percent %"
                            }
                        },
                        onFinished = { result ->
                            downloading = false

                            if (currentPage != "Software-Update") {
                                return@download
                            }

                            result.fold(
                                onSuccess = { apk ->
                                    status.text = "Prüfe heruntergeladene APK ..."

                                    MobileUpdateVerifier.verify(
                                        this, apk, update
                                    ).fold(
                                        onSuccess = {
                                            status.text =
                                                "APK geprüft. Öffne Android-Installation ..."

                                            MobileUpdateInstaller.install(
                                                this, apk, update
                                            ).onFailure { error ->
                                                status.text =
                                                    "Installation konnte nicht gestartet werden: " +
                                                    (error.message ?: "Unbekannter Fehler")
                                            }
                                        },
                                        onFailure = { error ->
                                            status.text =
                                                "APK-Prüfung fehlgeschlagen: " +
                                                (error.message ?: "Unbekannter Fehler")
                                            apk.delete()
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    status.text =
                                        "Download fehlgeschlagen: " +
                                        (error.message ?: "Unbekannter Fehler")
                                }
                            )
                        }
                    )
                }
            }
        }
        installButton.visibility = View.GONE

        root.addView(installButton)

        val checkButton = button("Nach Updates suchen") {
            if (downloading) {
                status.text = "Bitte laufenden Download abschließen."
                return@button
            }
            availableUpdate = null
            installButton.visibility = View.GONE
            val requestedChannel = testChannel
            val requestId = ++updateRequestId
            status.text = if (requestedChannel) {
                "Prüfe GitHub-Testversionen ..."
            } else {
                "Prüfe GitHub-Releases ..."
            }
            changelog.text = ""
            MobileUpdater.check(this, testChannel = requestedChannel) { result ->
                if (currentPage != "Software-Update" ||
                    requestId != updateRequestId ||
                    testChannel != requestedChannel
                ) return@check

                result.fold(
                    onSuccess = { update ->
                        if (update == null) {
                            status.text = "Keine neuere Version verfügbar."
                        } else {
                            availableUpdate = update
                            installButton.visibility = View.VISIBLE
                            status.text =
                                "Neue Version verfügbar: ${update.version}"
                            changelog.text = update.changelog.ifBlank {
                                "Kein Änderungsprotokoll vorhanden."
                            }
                        }
                    },
                    onFailure = { error ->
                        status.text = "Updateprüfung fehlgeschlagen: " +
                            (error.message ?: "Unbekannter Fehler")
                    }
                )
            }
        }
        root.addView(checkButton)

        root.addView(button("← Zurück zu Einstellungen") {
            showSettings()
        })

        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun showFrnChat() {
        currentPage = "FRN_CHAT"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        content.addView(label("FRN · Chat", 24f, Color.WHITE, true))
        content.addView(label(
            "Raumchat und private Nachrichten werden hier integriert.",
            16f, muted
        ).apply {
            setPadding(0, dp(16), 0, dp(16))
        })
        showGatewayScreen(ScrollView(this).apply {
            addView(content)
        })
    }

    private fun showGatewayScreen(content: android.view.View) {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val heading = label(
            "FunkGateway Mobile",
            18f,
            Color.WHITE,
            true
        ).apply {
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            heading,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val audioIndicators = label(
            "🎙  🔊",
            20f,
            muted
        ).apply {
            setPadding(dp(4), dp(8), dp(16), dp(8))
            contentDescription =
                "Globale Mikrofon- und Lautsprechersteuerung in Vorbereitung"
        }

        header.addView(audioIndicators)
        frame.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }

        val integrations = listOf(
            "Start", "FRN", "Mumble", "Zello", "Mehr"
        )

        integrations.forEach { integration ->
            val tab = button(integration) {
                when (integration) {
                    "Start" -> showHome()
                    "FRN" -> showFrn()
                    "Mumble" -> showPage("Mumble")
                    "Zello" -> showPage("Zello")
                    else -> showPage("Mehr")
                }
            }.apply {
                textSize = 12f
                setPadding(dp(2), 0, dp(2), 0)
            }

            val activeIntegration = when (currentPage) {
                "Start" -> "Start"
                "FRN", "FRN_ROOMS", "FRN_ROOM", "FRN_REGISTRATION",
                "FRN-Einstellungen", "FRN_CHAT", "FRN_PARTICIPANTS" -> "FRN"
                "Mumble" -> "Mumble"
                "Zello" -> "Zello"
                else -> "Mehr"
            }

            if (integration == activeIntegration) {
                tab.setTextColor(accent)
            }

            tabs.addView(
                tab,
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            )
        }

        frame.addView(tabs)

        val frnPages = setOf(
            "FRN", "FRN_ROOMS", "FRN_ROOM",
            "FRN_REGISTRATION", "FRN-Einstellungen", "FRN_CHAT", "FRN_PARTICIPANTS"
        )

        if (currentPage in frnPages) {
            val subTabs = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), 0, dp(8), dp(8))
            }

            val sections = listOf(
                "Verbind." to "FRN-Einstellungen",
                "Server" to "FRN",
                "Räume" to "FRN_ROOMS",
                "Teiln." to "FRN_PARTICIPANTS",
                "Chat" to "FRN_CHAT"
            )

            sections.forEach { (title, page) ->
                val tab = button(title) {
                    when (page) {
                        "FRN-Einstellungen" -> showFrnSettings()
                        "FRN_CHAT" -> showFrnChat()
                        "FRN" -> showFrn()
                        "FRN_ROOMS" -> showFrnRooms()
                        "FRN_PARTICIPANTS" -> showFrnParticipants()
                        else -> showPage("FRN Audio & PTT")
                    }
                }.apply {
                    textSize = 10f
                    setPadding(dp(1), 0, dp(1), 0)
                    if (currentPage == page) {
                        setTextColor(accent)
                    }
                }

                subTabs.addView(
                    tab,
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    }
                )
            }

            frame.addView(subTabs)
        }

        frame.addView(
            content,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val pttBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            setBackgroundColor(panel)
        }

        val targetButton = button("Ziel: keines") {}
        val pttButton = button("🎙 PTT") {}

        pttButton.isEnabled = false
        pttButton.alpha = 0.45f
        pttButton.contentDescription =
            "Globale PTT: Sendefunktion noch in Vorbereitung"

        fun updatePttTarget() {
            val service = gatewayService
            val target = service?.getSelectedPttTarget()
            val available = service?.getAvailablePttTargets().orEmpty()

            targetButton.text = "Ziel: " + when {
                target == null -> "keines"
                target !in available -> "nicht verbunden"
                else -> target.name
            }
            targetButton.isEnabled = service != null
            val state = service?.getPttState() ?: GatewayService.PttState.IDLE
            val ready = target != null && target in available
            pttButton.isEnabled = ready
            val (status, color) = when {
                state == GatewayService.PttState.TRANSMITTING -> "🔴 SENDET" to Color.rgb(190, 30, 40)
                state == GatewayService.PttState.REQUESTING -> "🔴 Warte auf Freigabe" to Color.rgb(165, 45, 45)
                service?.isPttReleasePending() == true -> "🎙 PTT wird freigegeben" to Color.rgb(110, 120, 135)
                ready -> "🎙 PTT bereit" to accent
                else -> "🎙 PTT gesperrt" to Color.rgb(85, 92, 103)
            }
            pttButton.text = status
            pttButton.background = rounded(color)
            pttButton.alpha = if (pttButton.isEnabled) 1f else 0.65f
        }

        targetButton.setOnClickListener {
            val service = gatewayService ?: return@setOnClickListener
            val available = service.getAvailablePttTargets()
            val choices = listOf("Keines") + available.map { it.name }

            android.app.AlertDialog.Builder(this)
                .setTitle("Globales PTT-Sendeziel")
                .setItems(choices.toTypedArray()) { _, which ->
                    val target =
                        if (which == 0) null else available[which - 1]
                    service.selectPttTarget(target)
                    updatePttTarget()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        // Eine globale PTT, aber niemals mehrere Sendeziele.
        // HOLD: Drücken = Start, Loslassen = Stopp.
        // LATCH: Antippen = Umschalten.
        pttButton.setOnTouchListener { _, event ->
            val service = gatewayService
            val prefs = getSharedPreferences(
                "funkgateway_ptt",
                Context.MODE_PRIVATE
            )
            val latch = prefs.getString("ptt_mode", "HOLD") == "LATCH"

            fun releasePtt() {
                try {
                    service?.stopPtt()
                } catch (e: Exception) {
                    android.util.Log.e(
                        "FunkGateway-PTT",
                        "PTT konnte nicht sauber beendet werden",
                        e
                    )
                }
            }

            fun requestPtt() {
                if (service == null) return
                if (!ensureMicrophonePermission()) return
                try {
                    if (!service.startPtt()) {
                        Toast.makeText(
                            this,
                            "PTT momentan nicht sendebereit.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "FunkGateway-PTT",
                        "PTT-Start fehlgeschlagen",
                        e
                    )
                    Toast.makeText(
                        this,
                        "PTT-Start fehlgeschlagen.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!latch) requestPtt()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (latch) {
                        if (service?.getPttState() !=
                            GatewayService.PttState.IDLE
                        ) {
                            releasePtt()
                        } else {
                            requestPtt()
                        }
                    } else {
                        releasePtt()
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Auch Latch bei unterbrochener Berührung sicher beenden.
                    releasePtt()
                }
            }
            true
        }

        pttBar.addView(
            targetButton,
            LinearLayout.LayoutParams(0, dp(54), 2f)
        )
        pttBar.addView(
            pttButton,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        frame.addView(pttBar)
        refreshPttBar = { updatePttTarget() }
        updatePttTarget()
        setContentView(frame)
        val pttStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val pttStatusTask = object : Runnable {
            override fun run() {
                if (!frame.isAttachedToWindow || !frnActivityActive) return
                updatePttTarget()
                pttStatusHandler.postDelayed(this, 250L)
            }
        }
        pttStatusHandler.postDelayed(pttStatusTask, 250L)
    }

    private fun showHome() {
        currentPage = "Start"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(24))
        }

        content.addView(label(
            "Willkommen bei FunkGateway Mobile!",
            24f, Color.WHITE, true
        ))

        content.addView(label(
            "Deine mobile Zentrale für Funk und Sprachkommunikation.",
            16f, accent
        ).apply {
            setPadding(0, dp(12), 0, dp(28))
        })

        content.addView(label(
            "Was ist FunkGateway Mobile?",
            20f, Color.WHITE, true
        ))

        content.addView(label(
            "FunkGateway Mobile ist die Android-Erweiterung unseres " +
            "FunkGateway-Projekts. Verschiedene Kommunikationsdienste " +
            "sollen hier über eine gemeinsame Oberfläche bedient werden.",
            16f, muted
        ).apply {
            setPadding(0, dp(12), 0, dp(28))
        })

        content.addView(label(
            "Unsere Kommunikationsdienste",
            20f, Color.WHITE, true
        ))

        listOf(
            "📻  FRN – bereits integriert",
            "🎧  Mumble – in Vorbereitung",
            "💬  Zello – in Vorbereitung",
            "🦜  Papageien und Verlinkung – geplant"
        ).forEach { entry ->
            content.addView(label(entry, 16f, Color.WHITE).apply {
                setPadding(0, dp(14), 0, dp(8))
            })
        }

        content.addView(label(
            "Wähle oben einen Kommunikationsdienst aus, um loszulegen.",
            15f, accent
        ).apply {
            setPadding(0, dp(30), 0, dp(14))
        })

        content.addView(label(
            "FunkGateway Mobile · Gemeinsam verbunden",
            13f, muted
        ))

        showGatewayScreen(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            }
        )
    }

    @Deprecated("Back navigation for the current Activity")
    override fun onBackPressed() {
        if (currentPage != "Start") {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    private fun refreshFrnCacheOnStartup() {
        // Gespeicherte Daten zuerst verfügbar machen.
        if (FrnCache.lastUpdated == 0L) {
            FrnCache.load(this)
        }

        // Netzwerkabfragen niemals auf dem UI-Thread ausführen.
        if (!frnAutomaticRefreshAllowed()) return
        if (!frnManagerRefreshBusy.compareAndSet(false, true)) return

        Thread {
            try {
            val rows = mutableListOf<org.funkgateway.mobile.frn.FrnRoom>()
            val successfulManagers = mutableSetOf<String>()

            for (manager in FrnSystemManager.managers) {
                try {
                    val loaded = FrnSystemManager.load(manager)
                    rows.addAll(loaded)
                    successfulManagers.add(manager.id)
                } catch (e: Exception) {
                    android.util.Log.w(
                        "FunkGateway",
                        "FRN-SystemManager ${manager.name}: " +
                            (e.message ?: "Abfrage fehlgeschlagen")
                    )
                }
            }

            runOnUiThread {
                try {
                if (successfulManagers.isNotEmpty()) {
                    FrnCache.rooms = FrnCache.rooms.filter {
                        it.manager !in successfulManagers
                    } + rows
                    FrnCache.lastUpdated = System.currentTimeMillis()
                    FrnCache.save(this@MainActivity)
                    android.util.Log.i(
                        "FunkGateway",
                        "FRN-SystemManager-Cache aktualisiert."
                    )
                    refreshFrnCachedList?.invoke()
                }
                recordFrnManagerRefreshResult(
                    successfulManagers.isNotEmpty()
                )
                } finally {
                    frnManagerRefreshBusy.set(false)
                }
            }
            } catch (e: Exception) {
                frnManagerRefreshBusy.set(false)
                android.util.Log.e("FunkGateway", "FRN-Aktualisierung fehlgeschlagen", e)
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        showHome()
        refreshFrnCacheOnStartup()
    }
}
