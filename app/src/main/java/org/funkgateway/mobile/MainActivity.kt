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
    private var refreshGlobalAudioControls: (() -> Unit)? = null
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
            refreshGlobalAudioControls?.invoke()
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
            refreshGlobalAudioControls?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        org.funkgateway.mobile.frn.FrnChatStore.initialize(this)
        org.funkgateway.mobile.frn.FrnChatParser.initialize(this)
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

    private var selectedFrnChatId: String? = null
    private val frnOpenChatContacts =
        linkedMapOf<String, String>()
    private val frnClosedChatIds = mutableSetOf<String>()
    private val frnHiddenGroupIds = mutableSetOf<String>()

    private val frnUnreadChats = mutableMapOf<String, Int>()
    private val frnPrivateDialogs =
        mutableMapOf<String, android.app.Dialog>()

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

        if (activeState == null ||
            activeState == GatewayService.FrnStatus.DISCONNECTED) {
            root.addView(label(
                "FRN: Nicht verbunden",
                13f, muted
            ).apply {
                setPadding(0, dp(6), 0, dp(2))
            })
        } else {
            root.addView(activeBox, LinearLayout.LayoutParams(
                -1, -2
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            })
        }


        root.addView(label(
            "FRN-SystemManager auswählen",
            16f, Color.WHITE, true
        ).apply {
            setPadding(0, dp(8), 0, dp(4))
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
        selector.minimumHeight = dp(44)
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
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(6)
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

    private fun fillFrnChatBubbles(
        container: LinearLayout,
        entries: List<org.funkgateway.mobile.frn.FrnChatStore.Entry>
    ) {
        container.removeAllViews()
        entries.forEach { entry ->
            container.addView(
                org.funkgateway.mobile.frn.FrnChatBubble.create(
                    this, entry
                )
            )
        }
    }

    private fun handleFrnUiEvent(
        event: org.funkgateway.mobile.frn.FrnEvent,
        service: GatewayService
    ) {
        when (event) {
            is org.funkgateway.mobile.frn.FrnEvent.TextMessage -> {
                val clients = service
                    ?.getFrnParticipants()?.entries.orEmpty()
                val messages =
                    org.funkgateway.mobile.frn.FrnChatParser.parse(
                        event.entries, clients
                    )
                messages.forEach { message ->
                    val currentRoom = service.getConnectedFrnRoom()
                    val groupReply = currentRoom?.let { room ->
                        org.funkgateway.mobile.frn.FrnGroupMessageParser.recognize(
                            message,
                            room.server,
                            room.room
                        )
                    }

                    if (groupReply != null) {
                        // Speicherung und Weiterleitung erfolgen im GatewayService.
                        return@forEach
                    }

                    val groupResponse =
                        org.funkgateway.mobile.frn.FrnGroupInvitationResponseParser.parse(
                            message
                        )
                    if (groupResponse != null) {
                        val room = service.getConnectedFrnRoom()
                        val responseType = groupResponse.type

                        if (room != null &&
                            responseType ==
                                org.funkgateway.mobile.frn.FrnGroupResponseType.ACK
                        ) {
                            val group =
                                org.funkgateway.mobile.frn.FrnChatGroups.get(
                                    groupResponse.groupId
                                )
                            val ownId =
                                org.funkgateway.mobile.frn.FrnGatewayIdentity.getId(this)

                            if (group != null &&
                                group.active &&
                                group.server.equals(room.server, ignoreCase = true) &&
                                group.room.equals(room.room, ignoreCase = true) &&
                                org.funkgateway.mobile.frn.FrnChatGroups.isHost(
                                    group.id, ownId
                                )
                            ) {
                                val confirmed =
                                    org.funkgateway.mobile.frn.FrnGroupInvitationTracker.markConfirmed(
                                        groupResponse.invitationId,
                                        groupResponse.groupId,
                                        groupResponse.senderId,
                                        room.server,
                                        room.room
                                    )

                                if (confirmed) {
                                    val added =
                                        org.funkgateway.mobile.frn.FrnChatGroups.addConfirmedMember(
                                            group.id,
                                            ownId,
                                            groupResponse.senderId,
                                            message.sender
                                        )

                                    val memberReady = added ||
                                        org.funkgateway.mobile.frn.FrnChatGroups.hasConfirmedMember(
                                            group.id,
                                            ownId,
                                            groupResponse.senderId
                                        )
                                    if (memberReady) {
                                        val groupSaved =
                                            saveFrnGroupsOrWarn()

                                        if (!groupSaved) {
                                            android.util.Log.w(
                                                "FunkGateway-Groups",
                                                "Bestätigtes Gruppenmitglied konnte nicht dauerhaft gespeichert werden."
                                            )
                                            org.funkgateway.mobile.frn.FrnGroupInvitationTracker.rollbackConfirmation(
                                                groupResponse.invitationId,
                                                groupResponse.groupId,
                                                groupResponse.senderId,
                                                room.server,
                                                room.room
                                            )
                                            return@forEach
                                        }

                                        org.funkgateway.mobile.frn.FrnGroupInvitationTracker.complete(
                                            groupResponse.invitationId
                                        )

                                        service.sendFrnMemberSyncAsync(
                                            group.id,
                                            ownId
                                        )

                                        android.widget.Toast.makeText(
                                            this,
                                            "${message.sender} ist Gruppe ${group.shortCode} beigetreten.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()

                                        android.util.Log.i(
                                            "FunkGateway-Chat",
                                            "Gruppenmitglied bestätigt: ${message.sender}"
                                        )
                                    }
                                }
                            }
                        }

                        if (room != null &&
                            responseType ==
                                org.funkgateway.mobile.frn.FrnGroupResponseType.CONFIRM
                        ) {
                            val accepted =
                                org.funkgateway.mobile.frn.FrnAcceptedGroupInvitations.find(
                                    groupResponse.invitationId,
                                    groupResponse.groupId,
                                    groupResponse.senderId,
                                    room.server,
                                    room.room
                                )

                            if (accepted != null) {
                                val existing =
                                    org.funkgateway.mobile.frn.FrnChatGroups.get(
                                        accepted.groupId
                                    )

                                val joined = if (
                                    existing != null &&
                                    existing.active &&
                                    existing.hostId == accepted.hostId &&
                                    existing.server.equals(
                                        accepted.server, ignoreCase = true
                                    ) &&
                                    existing.room.equals(
                                        accepted.room, ignoreCase = true
                                    ) &&
                                    existing.shortCode == accepted.shortCode
                                ) {
                                    existing
                                } else if (existing == null) {
                                    org.funkgateway.mobile.frn.FrnChatGroups.joinInvitation(
                                        accepted
                                    )
                                } else {
                                    null
                                }

                                if (joined != null) {
                                    val groupSaved =
                                        saveFrnGroupsOrWarn()

                                    if (!groupSaved) {
                                        android.util.Log.w(
                                            "FunkGateway-Groups",
                                            "Angenommene Gruppe konnte nicht dauerhaft gespeichert werden."
                                        )
                                        return@forEach
                                    }

                                    org.funkgateway.mobile.frn.FrnAcceptedGroupInvitations
                                        .markConfirmed(accepted.invitationId)
                                    val acknowledgment =
                                        org.funkgateway.mobile.frn.FrnGroupInvitationResponseParser.encode(
                                            org.funkgateway.mobile.frn.FrnGroupResponseType.ACK,
                                            accepted.invitationId,
                                            accepted.groupId
                                        )

                                    service.sendFrnTextMessageAsync(
                                        accepted.senderId,
                                        acknowledgment
                                    ) { sent, error ->
                                        if (sent) {
                                            // Einladung für mögliche CONFIRM-Wiederholungen
                                            // bis zum Ablauf der Gültigkeit aufbewahren.
                                            android.widget.Toast.makeText(
                                                this,
                                                "Gruppe ${joined.shortCode} beigetreten.",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            android.widget.Toast.makeText(
                                                this,
                                                "Gruppenbestätigung fehlgeschlagen: " +
                                                    (error ?: "Unbekannter Fehler"),
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        }

                        if (room != null &&
                            (responseType == org.funkgateway.mobile.frn.FrnGroupResponseType.ACCEPT ||
                             responseType == org.funkgateway.mobile.frn.FrnGroupResponseType.DECLINE)
                        ) {
                            val pending =
                                org.funkgateway.mobile.frn.FrnGroupInvitationTracker.find(
                                    groupResponse.invitationId,
                                    groupResponse.groupId,
                                    groupResponse.senderId,
                                    room.server,
                                    room.room
                                )
                            val group =
                                org.funkgateway.mobile.frn.FrnChatGroups.get(
                                    groupResponse.groupId
                                )
                            val ownId =
                                org.funkgateway.mobile.frn.FrnGatewayIdentity.getId(this)

                            if (pending != null && group != null &&
                                org.funkgateway.mobile.frn.FrnChatGroups.isHost(
                                    group.id, ownId
                                )
                            ) {
                                if (responseType ==
                                    org.funkgateway.mobile.frn.FrnGroupResponseType.DECLINE &&
                                    pending.status ==
                                        org.funkgateway.mobile.frn.FrnInvitationStatus.PENDING
                                ) {
                                    org.funkgateway.mobile.frn.FrnGroupInvitationTracker.complete(
                                        groupResponse.invitationId
                                    )
                                    android.widget.Toast.makeText(
                                        this,
                                        "${message.sender} hat die Gruppeneinladung abgelehnt.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else if (
                                    responseType ==
                                        org.funkgateway.mobile.frn.FrnGroupResponseType.ACCEPT &&
                                    (
                                        org.funkgateway.mobile.frn.FrnGroupInvitationTracker.markAccepted(
                                            groupResponse.invitationId,
                                            groupResponse.groupId,
                                            groupResponse.senderId,
                                            room.server,
                                            room.room
                                        ) ||
                                        org.funkgateway.mobile.frn.FrnGroupInvitationTracker.isAwaitingAck(
                                            groupResponse.invitationId,
                                            groupResponse.groupId,
                                            groupResponse.senderId,
                                            room.server,
                                            room.room
                                        )
                                    )
                                ) {
                                    val confirmation =
                                        org.funkgateway.mobile.frn.FrnGroupInvitationResponseParser.encode(
                                            org.funkgateway.mobile.frn.FrnGroupResponseType.CONFIRM,
                                            groupResponse.invitationId,
                                            groupResponse.groupId
                                        )

                                    service.sendFrnTextMessageAsync(
                                        groupResponse.senderId,
                                        confirmation
                                    ) { sent, error ->
                                        if (sent) {
                                            android.util.Log.i(
                                                "FunkGateway-Chat",
                                                "Gruppenbestätigung an ${message.sender} gesendet."
                                            )
                                        } else {
                                            android.widget.Toast.makeText(
                                                this,
                                                "Bestätigung an ${message.sender} fehlgeschlagen: " +
                                                    (error ?: "Unbekannter Fehler"),
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        }
                        return@forEach
                    }

                    val memberSync =
                        org.funkgateway.mobile.frn.FrnGroupMemberSyncParser.parse(
                            message
                        )
                    if (memberSync != null) {
                        val room = service.getConnectedFrnRoom()
                        val group =
                            org.funkgateway.mobile.frn.FrnChatGroups.get(
                                memberSync.groupId
                            )

                        if (room != null &&
                            group != null &&
                            group.active &&
                            group.hostFrnId.isNotBlank() &&
                            memberSync.senderId == group.hostFrnId &&
                            group.server.equals(
                                room.server, ignoreCase = true
                            ) &&
                            group.room.equals(
                                room.room, ignoreCase = true
                            ) &&
                            memberSync.version > group.hostVersion
                        ) {
                            val complete =
                                org.funkgateway.mobile.frn.FrnGroupMemberSyncBuffer.accept(
                                    memberSync
                                )

                            if (complete != null) {
                                val updated =
                                    org.funkgateway.mobile.frn.FrnChatGroups.applyMemberSync(
                                        complete
                                    )

                                if (updated) {
                                    val groupSaved =
                                        saveFrnGroupsOrWarn()

                                    if (!groupSaved) {
                                        android.util.Log.w(
                                            "FunkGateway-Groups",
                                            "Synchronisierte Mitgliederliste konnte nicht gespeichert werden."
                                        )
                                    }

                                    android.util.Log.i(
                                        "FunkGateway-Chat",
                                        "Mitgliederliste für ${group.shortCode} " +
                                            "auf Version ${complete.version} aktualisiert."
                                    )
                                }
                            }
                        }

                        return@forEach
                    }

                    val dissolution =
                        org.funkgateway.mobile.frn.FrnGroupDissolutionParser.parse(
                            message
                        )
                    if (dissolution != null) {
                        val room = service.getConnectedFrnRoom()
                        val group =
                            org.funkgateway.mobile.frn.FrnChatGroups.get(
                                dissolution.groupId
                            )

                        if (room != null &&
                            group != null &&
                            group.server.equals(room.server, ignoreCase = true) &&
                            group.room.equals(room.room, ignoreCase = true)
                        ) {
                            val removed =
                                org.funkgateway.mobile.frn.FrnChatGroups.applyDissolution(
                                    dissolution
                                )

                            if (removed) {
                                saveFrnGroupsOrWarn()
                                android.util.Log.i(
                                    "FunkGateway-Groups",
                                    "Gruppe ${dissolution.groupId} durch Host aufgelöst."
                                )
                            }
                        }
                        return@forEach
                    }

                    val hostTransfer =
                        org.funkgateway.mobile.frn.FrnHostTransferParser.parse(
                            message
                        )
                    if (hostTransfer != null) {
                        android.util.Log.i(
                            "FunkGateway-HostTransfer",
                            "Hostübergabenachricht empfangen: " +
                                hostTransfer.type.name +
                                " / Gruppe " + hostTransfer.groupId
                        )
                        return@forEach
                    }
                    val invitation =
                        org.funkgateway.mobile.frn.FrnGroupInvitationParser.parse(
                            message
                        )
                    if (invitation != null) {
                        val room = service.getConnectedFrnRoom()
                        if (room != null &&
                            invitation.server.equals(room.server, ignoreCase = true) &&
                            invitation.room.equals(room.room, ignoreCase = true)
                        ) {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("👥 FunkGateway-Gruppeneinladung")
                                .setMessage(
                                    "${invitation.senderName} lädt dich ein.\\n\\n" +
                                    "Gruppe: ${invitation.groupName}\\n" +
                                    "Kennung: ${invitation.shortCode}\\n\\n" +
                                    "Möchtest du dieser Gruppe beitreten?"
                                )
                                .setPositiveButton("Annehmen") { _, _ ->
                                    val registered =
                                        org.funkgateway.mobile.frn.FrnAcceptedGroupInvitations.register(
                                            invitation
                                        )
                                    if (!registered) {
                                        android.widget.Toast.makeText(
                                            this,
                                            "Diese Einladung wird bereits bearbeitet.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                    val response =
                                        org.funkgateway.mobile.frn.FrnGroupInvitationResponseParser.encode(
                                            org.funkgateway.mobile.frn.FrnGroupResponseType.ACCEPT,
                                            invitation.invitationId,
                                            invitation.groupId
                                        )
                                    service.sendFrnTextMessageAsync(
                                        invitation.senderId,
                                        response
                                    ) { sent, error ->
                                        // Bei einem Sendefehler bleibt die
                                        // Einladung für einen erneuten Versuch
                                        // bis zum Ablauf der Gültigkeit erhalten.
                                        android.widget.Toast.makeText(
                                            this,
                                            if (sent)
                                                "Annahme gesendet. Warte auf Bestätigung."
                                            else
                                                "Annahme fehlgeschlagen: ${error ?: "Unbekannter Fehler"}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    }
                                }
                                .setNegativeButton("Ablehnen") { _, _ ->
                                    val response =
                                        org.funkgateway.mobile.frn.FrnGroupInvitationResponseParser.encode(
                                            org.funkgateway.mobile.frn.FrnGroupResponseType.DECLINE,
                                            invitation.invitationId,
                                            invitation.groupId
                                        )
                                    service.sendFrnTextMessageAsync(
                                        invitation.senderId,
                                        response
                                    ) { sent, error ->
                                        android.widget.Toast.makeText(
                                            this,
                                            if (sent)
                                                "Gruppeneinladung abgelehnt."
                                            else
                                                "Ablehnung fehlgeschlagen: ${error ?: "Unbekannter Fehler"}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                .show()
                        }
                        return@forEach
                    }
                    org.funkgateway.mobile.frn.FrnChatStore.add(message)
                    if (message.privateMessage) {
                        frnClosedChatIds.remove(message.senderId)
                        frnOpenChatContacts[message.senderId] = message.sender
                    }
                    if (message.privateMessage &&
                        !(currentPage == "FRN_CHAT" &&
                          selectedFrnChatId == message.senderId)
                    ) {
                        frnUnreadChats[message.senderId] =
                            (frnUnreadChats[message.senderId] ?: 0) + 1
                    }
                    if (message.privateMessage) {
                        val chatId = message.senderId
                        val existing = frnPrivateDialogs[chatId]
                        if (existing?.isShowing == true) {
                            existing.findViewById<LinearLayout>(
                                12001
                            )?.let { container ->
                                fillFrnChatBubbles(
                                    container,
                                    org.funkgateway.mobile.frn.FrnChatStore
                                        .privateWith(chatId)
                                )
                                (container.parent as? ScrollView)?.post {
                                    (container.parent as? ScrollView)
                                        ?.fullScroll(View.FOCUS_DOWN)
                                }
                            }
                            return@forEach
                        }
                        val dialogContent = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(22), dp(22), dp(22), dp(16))
                            addView(label(
                                "Privatnachricht von ${message.sender}",
                                20f, accent, true
                            ))
                            val messageList = LinearLayout(this@MainActivity).apply {
                                id = 12001
                                orientation = LinearLayout.VERTICAL
                            }
                            fillFrnChatBubbles(
                                messageList,
                                org.funkgateway.mobile.frn.FrnChatStore
                                    .privateWith(chatId)
                            )
                            val messageScroll = ScrollView(this@MainActivity).apply {
                                addView(messageList)
                                isFillViewport = true
                            }
                            messageScroll.post {
                                messageScroll.fullScroll(View.FOCUS_DOWN)
                            }
                            addView(
                                messageScroll,
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(280)
                                )
                            )

                            val inputRow = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            val messageInput =
                                android.widget.EditText(this@MainActivity).apply {
                                    hint = "Nachricht schreiben ..."
                                    setHintTextColor(muted)
                                    setTextColor(Color.WHITE)
                                    textSize = 15f
                                    setSingleLine(true)
                                    filters = arrayOf(
                                        android.text.InputFilter.LengthFilter(500)
                                    )
                                    background = rounded(panel)
                                    setPadding(
                                        dp(12), dp(10), dp(12), dp(10)
                                    )
                                }
                            inputRow.addView(
                                messageInput,
                                LinearLayout.LayoutParams(0, -2, 1f)
                            )
                            inputRow.addView(
                                button("➤") {
                                    val text = messageInput.text.toString().trim()
                                    if (text.isNotEmpty()) {
                                        val service = gatewayService
                                        if (service == null) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "FRN ist nicht verbunden.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            service.sendFrnTextMessageAsync(chatId, text) { sent, error ->
                                                if (sent) {
                                                    org.funkgateway.mobile.frn.FrnChatStore
                                                        .addOutgoing(chatId, text, true)
                                                    fillFrnChatBubbles(
                                                        messageList,
                                                        org.funkgateway.mobile.frn.FrnChatStore
                                                            .privateWith(chatId)
                                                    )
                                                    if (messageInput.text.toString() == text) {
                                                        messageInput.text.clear()
                                                    }
                                                    messageScroll.post {
                                                        messageScroll.fullScroll(
                                                            View.FOCUS_DOWN
                                                        )
                                                    }
                                                } else {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        error ?: "Senden fehlgeschlagen.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                LinearLayout.LayoutParams(
                                    dp(54), dp(48)
                                ).apply {
                                    marginStart = dp(8)
                                }
                            )
                            addView(inputRow, LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = dp(12)
                            })
                        }
                        val dialog = android.app.AlertDialog.Builder(this)
                            .setView(dialogContent)
                            .setPositiveButton("Schließen", null)
                            .create()
                        dialog.setOnShowListener {
                            dialog.window?.setBackgroundDrawable(
                                android.graphics.drawable.ColorDrawable(
                                    Color.rgb(31, 42, 56)
                                )
                            )
                            dialog.getButton(
                                android.app.AlertDialog.BUTTON_POSITIVE
                            )?.setTextColor(accent)
                        }
                        dialog.setOnDismissListener {
                            frnPrivateDialogs.remove(chatId)
                        }
                        frnPrivateDialogs[chatId] = dialog
                        dialog.show()
                    }
                }
                if (currentPage == "FRN_CHAT") {
                    showFrnChat()
                }
            }
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
            val ownFrnName = service?.getConnectedFrnCallsign().orEmpty()
            val pttState = service?.getPttState()
            val serverNames = entries.mapNotNull { entry ->
                Regex("<ON>(.*?)</ON>", RegexOption.DOT_MATCHES_ALL)
                    .find(entry)?.groupValues?.get(1)
            }
            if (entries.isEmpty()) {
                participantsContainer.addView(label(
                    "Noch keine Teilnehmerdaten empfangen.",
                    16f, muted
                ))
            }

            val sortedEntries = entries.withIndex().sortedWith(
                compareBy<IndexedValue<String>> {
                    Regex("<CL>(.*?)</CL>", RegexOption.DOT_MATCHES_ALL)
                        .find(it.value)?.groupValues?.get(1)
                        ?.trim()?.toIntOrNull()
                        ?.takeIf { type -> type in 0..2 } ?: 3
                }.thenBy { it.index }
            )
            sortedEntries.forEach { item ->
                val index = item.index
                val entry = item.value
                val match = Regex(
                    "<ON>(.*?)</ON>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(entry)

                val name = android.text.Html.fromHtml(
                    match?.groupValues?.get(1) ?: "Teilnehmer #${index + 1}",
                    android.text.Html.FROM_HTML_MODE_LEGACY
                ).toString().trim()

                fun normalizeFrnName(value: String): String =
                    android.text.Html.fromHtml(
                        value,
                        android.text.Html.FROM_HTML_MODE_LEGACY
                    ).toString().replace(
                        Regex("[\\s\\u00A0]+"), " "
                    ).trim()

                val ownName = normalizeFrnName(ownFrnName)
                val participantName = normalizeFrnName(name)
                val ownTransmitting =
                    ownName.isNotEmpty() &&
                    participantName.equals(ownName, ignoreCase = true) &&
                    pttState == GatewayService.PttState.TRANSMITTING
                val speaking = snapshot?.activeIndex == index + 1
                val color = when {
                    ownTransmitting -> Color.rgb(255, 165, 0)
                    speaking -> Color.rgb(239, 68, 68)
                    else -> Color.rgb(34, 197, 94)
                }

                val clientType = Regex(
                    "<CL>(.*?)</CL>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(entry)?.groupValues?.get(1)
                    ?.trim()?.toIntOrNull()
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
                    setImageDrawable(
                        when (clientType) {
                            0, 2 -> FrnClientTypeDrawable(clientType, color)
                            else -> HandheldRadioDrawable(color)
                        }
                    )
                })

                val description = Regex(
                    "<DS>(.*?)</DS>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(entry)?.groupValues?.get(1).orEmpty()

                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                details.addView(label(
                    name.ifEmpty { "Teilnehmer #${index + 1}" },
                    16f,
                    Color.WHITE
                ))
                val bandChannel = Regex(
                    "<BC>(.*?)</BC>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(entry)?.groupValues?.get(1).orEmpty()
                if (bandChannel.isNotBlank()) {
                    val bcText = android.text.Html.fromHtml(
                        bandChannel,
                        android.text.Html.FROM_HTML_MODE_LEGACY
                    ).toString().trim()
                    details.addView(label(
                        "Band/Kanal: $bcText",
                        13f, accent
                    ))
                }
                if (description.isNotBlank()) {
                    details.addView(label(
                        android.text.Html.fromHtml(
                            description,
                            android.text.Html.FROM_HTML_MODE_LEGACY
                        ).toString().trim(),
                        13f,
                        muted
                    ))
                }
                row.addView(details)

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
            val saved = org.funkgateway.mobile.frn.FrnSettingsStore.loadProfile(
                this,
                org.funkgateway.mobile.frn.FrnProfileStore.selectedId(this)
            )
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
                    handleFrnUiEvent(event, service)
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
                    val saved = org.funkgateway.mobile.frn.FrnSettingsStore.loadProfile(
                this,
                org.funkgateway.mobile.frn.FrnProfileStore.selectedId(this)
            )
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
                                handleFrnUiEvent(event, service)
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


    private fun showLog(source: String = "FRN") {
        currentPage = "Log"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(label("Diagnose & Log", 24f, Color.WHITE, true))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf("FRN", "Mumble", "Zello", "System").forEach { name ->
            tabs.addView(button(name) {
                showLog(name)
            }.apply {
                textSize = 12f
                if (name == source) setTextColor(accent)
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        root.addView(tabs)

        val records = GatewayLog.get(source)
        root.addView(label(
            "$source · ${records.size} Einträge",
            14f, accent
        ))

        val logScroll = ScrollView(this).apply {
            isFillViewport = false
        }
        val logContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (records.isEmpty()) {
            logContent.addView(label(
                "Noch keine Diagnosemeldungen vorhanden.",
                14f, muted
            ))
        } else {
            records.asReversed().forEach { entry ->
                val color = when (entry.level) {
                    "ERROR" -> Color.rgb(239, 68, 68)
                    "WARN" -> Color.rgb(255, 165, 0)
                    else -> muted
                }
                logContent.addView(label(GatewayLog.format(entry), 12f, color))
            }
        }

        logScroll.addView(logContent)
        root.addView(logScroll, LinearLayout.LayoutParams(
            -1, 0, 1f
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(button("Kopieren") {
            val content = GatewayLog.get(source)
                .joinToString("\n") { GatewayLog.format(it) }
            val clipboard = getSystemService(
                android.content.Context.CLIPBOARD_SERVICE
            ) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "FunkGateway $source Log", content
                )
            )
            android.widget.Toast.makeText(
                this, "Log kopiert.", android.widget.Toast.LENGTH_SHORT
            ).show()
        })

        actions.addView(button("Löschen") {
            GatewayLog.clear(source)
            showLog(source)
        })

        actions.getChildAt(0).layoutParams =
            LinearLayout.LayoutParams(0, dp(52), 1f)
        actions.getChildAt(1).layoutParams =
            LinearLayout.LayoutParams(0, dp(52), 1f)
        root.addView(actions)
        showGatewayScreen(root)
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

        val profiles = org.funkgateway.mobile.frn.FrnProfileStore.list(this)
        val selectedProfileId =
            org.funkgateway.mobile.frn.FrnProfileStore.selectedId(this)
        val saved = try {
            org.funkgateway.mobile.frn.FrnSettingsStore.loadProfile(
                this, selectedProfileId
            )
        } catch (e: Exception) {
            org.funkgateway.mobile.frn.FrnSettings()
        }

        root.addView(label("Gespeicherte FRN-Identität", 14f, muted))
        val profileSelector = android.widget.Spinner(this).apply {
            val arrow = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }

            background = rounded(panel)
            setPopupBackgroundDrawable(rounded(panel))
            minimumHeight = dp(54)
            setPadding(dp(8), 0, dp(8), 0)
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                R.layout.frn_spinner_item,
                profiles.map { it.name }
            )
            setSelection(
                profiles.indexOfFirst { it.id == selectedProfileId }
                    .coerceAtLeast(0)
            )
            root.addView(this)
        }
        profileSelector.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    val profile = profiles.getOrNull(position) ?: return
                    if (profile.id != selectedProfileId) {
                        org.funkgateway.mobile.frn.FrnProfileStore.select(
                            this@MainActivity, profile.id
                        )
                        showFrnSettings()
                    }
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) = Unit
            }

        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(8))
        })
        root.addView(button("＋ Neue Identität") {
            val input = android.widget.EditText(this).apply {
                setSingleLine(true)
                hint = "Name der Identität"
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Neue FRN-Identität")
                .setView(input)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Anlegen") { _, _ ->
                    try {
                        val profile =
                            org.funkgateway.mobile.frn.FrnProfileStore.create(
                                this, input.text.toString()
                            )
                        org.funkgateway.mobile.frn.FrnProfileStore.select(
                            this, profile.id
                        )
                        showFrnSettings()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            this,
                            e.message ?: "Identität konnte nicht angelegt werden.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        })

        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(8))
        })
        root.addView(button("✎ Identität umbenennen") {
            val selected = profiles.firstOrNull {
                it.id == selectedProfileId
            } ?: return@button
            val input = android.widget.EditText(this).apply {
                setSingleLine(true)
                setText(selected.name)
                setSelection(text.length)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("FRN-Identität umbenennen")
                .setView(input)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern") { _, _ ->
                    try {
                        org.funkgateway.mobile.frn.FrnProfileStore.rename(
                            this, selected.id, input.text.toString()
                        )
                        showFrnSettings()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            this,
                            e.message ?: "Umbenennen fehlgeschlagen.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        })

        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(8))
        })
        root.addView(button("Identität löschen") {
            val selected = profiles.firstOrNull {
                it.id == selectedProfileId
            } ?: return@button

            if (profiles.size <= 1) {
                android.widget.Toast.makeText(
                    this, "Das letzte Profil kann nicht gelöscht werden.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@button
            }

            android.app.AlertDialog.Builder(this)
                .setTitle("FRN-Identität löschen?")
                .setMessage(
                    "Die Identität '${selected.name}' und ihre " +
                    "gespeicherten Zugangsdaten werden gelöscht. " +
                    "Eine bestehende Verbindung bleibt aktiv."
                )
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen") { _, _ ->
                    try {
                        org.funkgateway.mobile.frn.FrnProfileStore.delete(
                            this, selected.id
                        )
                        showFrnSettings()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            this,
                            e.message ?: "Löschen fehlgeschlagen.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        })

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
        root.addView(label("CL · Clienttyp", 14f, muted).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        val clientTypes = arrayOf(
            "0 – Crosslink", "1 – Gateway", "2 – PC-Only"
        )
        val clientType = android.widget.Spinner(this).apply {
            val arrow = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }

            background = rounded(panel)
            setPopupBackgroundDrawable(rounded(panel))
            minimumHeight = dp(54)
            setPadding(dp(8), 0, dp(8), 0)
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                R.layout.frn_spinner_item,
                clientTypes
            )
            setSelection(saved.clientType.toIntOrNull()
                ?.coerceIn(0, 2) ?: 2)
            root.addView(this)
        }
        val bandChannel = field("BC · Band / Kanal", saved.bandChannel)
        clientType.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    if (position == 2 &&
                        bandChannel.text.toString().isBlank()
                    ) {
                        bandChannel.setText("PC-Only")
                    }
                }
                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) = Unit
            }

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
                clientType = clientType.selectedItemPosition.toString(),
                bandChannel = bandChannel.text.toString().trim()
            )
            if (settings.email.isBlank() || settings.callsign.isBlank() ||
                settings.vx.isBlank()) {
                status.text = "Bitte E-Mail, Rufzeichen und VX angeben."
            } else {
                try {
                    org.funkgateway.mobile.frn.FrnSettingsStore.saveProfile(
                        this, selectedProfileId, settings
                    )
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
        root.addView(button("🎚 Audio-Mixer") {
            showAudioMixer()
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

    private fun showAudioMixer() {
        currentPage = "Audio-Mixer"
        val prefs = getSharedPreferences("funkgateway_audio", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        root.addView(label("🎚 Audio-Mixer", 25f, Color.WHITE, true))
        root.addView(label(
            "Empfangslautstärke der einzelnen Integrationen",
            14f, muted
        ).apply {
            setPadding(0, dp(12), 0, dp(24))
        })

        val initial = prefs.getInt("frn_volume", 100).coerceIn(0, 100)
        gatewayService?.setFrnVolume(initial)

        val valueLabel = label("FRN · $initial %", 19f, accent, true)
        root.addView(valueLabel)

        root.addView(android.widget.SeekBar(this).apply {
            max = 100
            progress = initial
            setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: android.widget.SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return
                        valueLabel.text = "FRN · $value %"
                        gatewayService?.setFrnVolume(value)
                        prefs.edit().putInt("frn_volume", value).apply()
                    }
                    override fun onStartTrackingTouch(
                        bar: android.widget.SeekBar?
                    ) = Unit
                    override fun onStopTrackingTouch(
                        bar: android.widget.SeekBar?
                    ) = Unit
                }
            )
        })

        root.addView(label(
            "Global Deaf bleibt unabhängig von der FRN-Lautstärke.",
            13f, muted
        ))

        root.addView(label("Mumble · folgt später", 16f, muted).apply {
            setPadding(0, dp(28), 0, dp(12))
        })
        root.addView(label("Zello · folgt später", 16f, muted))

        root.addView(button("← Zurück zu Einstellungen") {
            showSettings()
        })

        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })
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

        val store = org.funkgateway.mobile.frn.FrnChatStore
        val contacts = frnOpenChatContacts.toList()
            .filterNot { (id, _) -> id in frnClosedChatIds }

        val groups = org.funkgateway.mobile.frn.FrnChatGroups.all()
        val groupIds = groups.map { "group:${it.id}" }.toSet()
        if (selectedFrnChatId != null &&
            contacts.none { it.first == selectedFrnChatId } &&
            selectedFrnChatId !in groupIds) {
            selectedFrnChatId = null
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }
        content.addView(label("FRN · Chat", 24f, Color.WHITE, true))

        val service = gatewayService
        val chatContacts = if (
            service?.getFrnStatus() == GatewayService.FrnStatus.CONNECTED
        ) {
            org.funkgateway.mobile.frn.FrnChatContacts.fromParticipants(
                service.getFrnParticipants().entries,
                service.getConnectedFrnCallsign().orEmpty()
            )
        } else {
            emptyList()
        }

        val contactPicker = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                R.layout.frn_spinner_item,
                listOf("＋ Privatchat auswählen") +
                    chatContacts.map { it.name }
            ) {
                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: android.view.ViewGroup
                ): View {
                    return TextView(this@MainActivity).apply {
                        text = getItem(position).orEmpty()
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(panel)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        minHeight = dp(48)
                    }
                }
            }
            background = rounded(panel)
            dropDownWidth = dp(260)
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(
                        parent: android.widget.AdapterView<*>?
                    ) = Unit

                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (position == 0) return
                        val contact = chatContacts.getOrNull(position - 1)
                            ?: return
                        frnOpenChatContacts[contact.id] = contact.name
                        frnClosedChatIds.remove(contact.id)
                        selectedFrnChatId = contact.id
                        frnUnreadChats.remove(contact.id)
                        showFrnChat()
                    }
                }
        }

        content.addView(
            contactPicker,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(12)
            }
        )

        content.addView(
            button("＋ Gruppenchat") {
                if (chatContacts.size < 2) {
                    Toast.makeText(
                        this,
                        "Für einen Gruppenchat werden mindestens zwei andere Teilnehmer benötigt.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val selectedIds = linkedSetOf<String>()
                    val choices = chatContacts.map { it.name }.toTypedArray()
                    val checked = BooleanArray(chatContacts.size)

                    android.app.AlertDialog.Builder(this)
                        .setTitle("FRN · Gruppenchat")
                        .setMultiChoiceItems(
                            choices,
                            checked
                        ) { _, index, enabled ->
                            val id = chatContacts[index].id
                            if (enabled) selectedIds.add(id)
                            else selectedIds.remove(id)
                        }
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Gruppe erstellen") { _, _ ->
                            if (selectedIds.size < 2) {
                                Toast.makeText(
                                    this,
                                    "Bitte mindestens zwei Teilnehmer auswählen.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                val members = chatContacts.filter {
                                    it.id in selectedIds
                                }
                                val gatewayIds = linkedSetOf<String>()
                                val gatewayChoices =
                                    members.map { it.name }.toTypedArray()
                                val gatewayChecked =
                                    BooleanArray(members.size)

                                android.app.AlertDialog.Builder(this)
                                    .setTitle("FunkGateway-Teilnehmer")
                                    .setMessage(
                                        "Welche Teilnehmer verwenden FunkGateway? " +
                                        "Diese erhalten später eine Einladung. " +
                                        "Nicht markierte Teilnehmer werden wie bisher direkt aufgenommen."
                                    )
                                    .setMultiChoiceItems(
                                        gatewayChoices,
                                        gatewayChecked
                                    ) { _, index, enabled ->
                                        val id = members[index].id
                                        if (enabled) gatewayIds.add(id)
                                        else gatewayIds.remove(id)
                                    }
                                    .setNegativeButton("Zurück", null)
                                    .setPositiveButton("Weiter") { _, _ ->
                                        val directMembers = members.filter {
                                            it.id !in gatewayIds
                                        }
                                        val room = service?.getConnectedFrnRoom()
                                        if (room == null) {
                                            Toast.makeText(
                                                this,
                                                "Keine FRN-Raumverbindung vorhanden.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            val group =
                                                org.funkgateway.mobile.frn.FrnChatGroups.create(
                                                    members,
                                                    server = room.server,
                                                    room = room.room,
                                                    creatorId =
                                                        org.funkgateway.mobile.frn.FrnGatewayIdentity.getId(this),
                                                    creatorCallsign =
                                                        service.getConnectedFrnCallsign().orEmpty(),
                                                    initialMembers = directMembers
                                                )
                                            val groupSaved =
                                                saveFrnGroupsOrWarn()

                                            if (!groupSaved) {
                                                android.util.Log.w(
                                                    "FunkGateway-Groups",
                                                    "Neue Gruppe konnte nicht dauerhaft gespeichert werden."
                                                )
                                            }

                                            val gatewayMembers = members.filter {
                                                it.id in gatewayIds
                                            }
                                            gatewayMembers.forEach { contact ->
                                                val invitation =
                                                    org.funkgateway.mobile.frn.FrnGroupInvitation(
                                                        invitationId = java.util.UUID.randomUUID().toString(),
                                                        groupId = group.id,
                                                        groupName = group.name,
                                                        shortCode = group.shortCode,
                                                        server = group.server,
                                                        room = group.room,
                                                        senderId = contact.id,
                                                        senderName = contact.name,
                                                        hostId = group.hostId
                                                    )
                                                service.sendFrnGroupInvitationAsync(
                                                    invitation
                                                ) { sent, error ->
                                                    if (!sent) {
                                                        Toast.makeText(
                                                            this,
                                                            "Einladung an ${contact.name} fehlgeschlagen: " +
                                                                (error ?: "Unbekannter Fehler"),
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            }
                                            selectedFrnChatId = "group:${group.id}"
                                            showFrnChat()
                                            Toast.makeText(
                                                this,
                                                "${directMembers.size} direkt aufgenommen, " +
                                                "${gatewayIds.size} Einladungen noch ausstehend.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                    .show()
                            }
                        }
                        .show()
                }
            },
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(8)
            }
        )

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun addTab(title: String, id: String?) {
            val active = selectedFrnChatId == id
            val tab = button(title) {
                selectedFrnChatId = id
                if (id != null) frnUnreadChats.remove(id)
                showFrnChat()
            }
            tab.background = rounded(
                if (active) Color.rgb(18, 103, 98) else panel
            )
            tabs.addView(tab, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)
            ).apply {
                marginEnd = dp(8)
            })
        }

        content.addView(
            button("👥 Gruppen öffnen") {
                val availableGroups =
                    org.funkgateway.mobile.frn.FrnChatGroups.all()

                if (availableGroups.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Es sind keine Gruppen vorhanden.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val names = availableGroups.map {
                        "${it.shortCode} · ${it.name} (${it.participants.size} Mitglieder)"
                    }.toTypedArray()

                    val dialog = android.app.AlertDialog.Builder(this)
                        .setTitle("FRN · Meine Gruppen")
                        .setItems(names) { _, index ->
                            val group = availableGroups[index]
                            frnHiddenGroupIds.remove(group.id)
                            selectedFrnChatId = "group:${group.id}"
                            showFrnChat()
                        }
                        .setNegativeButton("Schließen", null)
                        .create()

                    dialog.setOnShowListener {
                        dialog.window?.setBackgroundDrawable(
                            rounded(panel)
                        )
                        dialog.findViewById<TextView>(
                            resources.getIdentifier(
                                "alertTitle", "id", "android"
                            )
                        )?.setTextColor(Color.WHITE)
                        dialog.listView?.setBackgroundColor(panel)
                        dialog.listView?.let { list ->
                            for (i in 0 until list.childCount) {
                                (list.getChildAt(i) as? TextView)
                                    ?.setTextColor(Color.WHITE)
                            }
                        }
                        dialog.getButton(
                            android.app.AlertDialog.BUTTON_NEGATIVE
                        )?.setTextColor(accent)
                    }
                    dialog.show()
                }
            },
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )

        addTab("Raumchat", null)
        contacts.forEach { (id, name) ->
            val unread = frnUnreadChats[id] ?: 0
            val tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(
                    if (selectedFrnChatId == id)
                        Color.rgb(18, 103, 98) else panel
                )
            }
            tabRow.addView(button(
                name + if (unread > 0) " ($unread)" else ""
            ) {
                selectedFrnChatId = id
                frnUnreadChats.remove(id)
                showFrnChat()
            }.apply {
                background = null
            })
            tabRow.addView(button("×") {
                frnOpenChatContacts.remove(id)
                frnClosedChatIds.add(id)
                frnUnreadChats.remove(id)
                if (selectedFrnChatId == id) {
                    selectedFrnChatId = null
                }
                showFrnChat()
            }.apply {
                background = null
                textSize = 22f
                setTextColor(muted)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), 0, dp(12), 0)
            })
            tabs.addView(
                tabRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(46)
                ).apply {
                    marginEnd = dp(8)
                }
            )
        }

        groups.filterNot { it.id in frnHiddenGroupIds }.forEach { group ->
            val groupTabId = "group:${group.id}"
            val groupRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(
                    if (selectedFrnChatId == groupTabId)
                        Color.rgb(18, 103, 98) else panel
                )
            }
            groupRow.addView(button("👥 ${group.shortCode} · ${group.name}") {
                selectedFrnChatId = groupTabId
                showFrnChat()
            }.apply { background = null })
            groupRow.addView(button("×") {
                frnHiddenGroupIds.add(group.id)
                if (selectedFrnChatId == groupTabId) {
                    selectedFrnChatId = null
                }
                showFrnChat()
            }.apply {
                background = null
                textSize = 22f
                setTextColor(muted)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(6), 0, dp(12), 0)
            })
            tabs.addView(
                groupRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(46)
                ).apply { marginEnd = dp(8) }
            )
        }

        content.addView(
            android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LinearLayout.LayoutParams(-1, dp(54)).apply {
                topMargin = dp(12)
            }
        )

        val activeGroup = selectedFrnChatId
            ?.takeIf { it.startsWith("group:") }
            ?.removePrefix("group:")
            ?.let { org.funkgateway.mobile.frn.FrnChatGroups.get(it) }

        if (activeGroup != null) {
            val ownInstallationId =
                org.funkgateway.mobile.frn.FrnGatewayIdentity.getId(this)

            val isGroupHost =
                org.funkgateway.mobile.frn.FrnChatGroups.isHost(
                    activeGroup.id,
                    ownInstallationId
                )

            val membersPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(panel)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }

            membersPanel.addView(label(
                "👥 ${activeGroup.shortCode} · ${activeGroup.name} · ${activeGroup.participants.size} Mitglieder",
                17f, accent, true
            ))

            activeGroup.participants.values.forEach { name ->
                membersPanel.addView(label(
                    "• $name",
                    15f, Color.WHITE
                ))
            }

            membersPanel.addView(label(
                if (isGroupHost)
                    "⚓ Du bist der Host dieser Gruppe."
                else
                    "👥 Du bist Mitglied dieser Gruppe.",
                14f,
                if (isGroupHost) accent else Color.WHITE
            ))

            content.addView(
                membersPanel,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                }
            )
        }

        val messages = if (selectedFrnChatId == null) {
            store.room()
        } else if (activeGroup != null) {
            org.funkgateway.mobile.frn.FrnGroupChatStore
                .messages(activeGroup.id)
        } else {
            store.privateWith(selectedFrnChatId!!)
        }

        val messageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (messages.isEmpty()) {
            messageList.addView(label(
                "Noch keine Nachrichten in diesem Chat.",
                16f, muted
            ))
        } else {
            fillFrnChatBubbles(messageList, messages)
        }

        val chatScroll = ScrollView(this).apply {
            addView(messageList)
        }
        content.addView(
            chatScroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        chatScroll.post {
            chatScroll.fullScroll(View.FOCUS_DOWN)
        }

        val recipientId = selectedFrnChatId
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val messageInput = android.widget.EditText(this).apply {
            hint = when {
                activeGroup != null -> "Nachricht an Gruppe schreiben ..."
                recipientId == null -> "Nachricht an alle (Test) ..."
                else -> "Private Nachricht schreiben ..."
            }
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            filters = arrayOf(
                android.text.InputFilter.LengthFilter(500)
            )
            background = rounded(panel)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        inputRow.addView(
            messageInput,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        inputRow.addView(
            button("➤") {
                val text = messageInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    val destination = recipientId ?: "0"
                    val service = gatewayService
                    if (service == null) {
                        Toast.makeText(
                            this, "FRN ist nicht verbunden.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (activeGroup != null) {
                        val targets = activeGroup.participants.keys.toList()
                        val groupText = "[${activeGroup.shortCode}] $text"
                        service.sendFrnGroupMessageAsync(
                            targets, groupText
                        ) { sent, total ->
                            if (sent > 0) {
                                org.funkgateway.mobile.frn.FrnGroupChatStore.add(
                                    groupId = activeGroup.id,
                                    sender = "Du",
                                    senderId = "",
                                    message = text,
                                    outgoing = true
                                )
                                if (currentPage == "FRN_CHAT" &&
                                    selectedFrnChatId == "group:${activeGroup.id}"
                                ) {
                                    showFrnChat()
                                }
                            }
                            Toast.makeText(
                                this,
                                "An $sent von $total Teilnehmern an Server gesendet; Zustellung unbestätigt.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        service.sendFrnTextMessageAsync(destination, text) { sent, error ->
                            if (sent) {
                                org.funkgateway.mobile.frn.FrnChatStore
                                    .addOutgoing(destination, text, recipientId != null)
                                if (currentPage == "FRN_CHAT" &&
                                    selectedFrnChatId == recipientId
                                ) {
                                    showFrnChat()
                                }
                                Toast.makeText(
                                    this,
                                    "An Server gesendet; Zustellung unbestätigt.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    error ?: "Senden fehlgeschlagen.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(dp(54), dp(48)).apply {
                marginStart = dp(8)
            }
        )
        content.addView(
            inputRow,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
        )

        showGatewayScreen(content)
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

        val audioControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun audioIcon(microphone: Boolean): android.widget.ImageButton =
            android.widget.ImageButton(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageDrawable(object : android.graphics.drawable.Drawable() {
                    private val pen = android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 2.4f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    override fun draw(canvas: android.graphics.Canvas) {
                        val b = bounds
                        canvas.save()
                        canvas.translate(b.left.toFloat(), b.top.toFloat())
                        canvas.scale(b.width() / 24f, b.height() / 24f)
                        pen.color = (callback as? android.view.View)
                            ?.tag as? Int ?: Color.GREEN
                        if (microphone) {
                            canvas.drawRoundRect(9f, 2f, 15f, 15f, 3f, 3f, pen)
                            canvas.drawArc(6f, 7f, 18f, 19f, 0f, 180f, false, pen)
                            canvas.drawLine(12f, 19f, 12f, 22f, pen)
                            canvas.drawLine(8f, 22f, 16f, 22f, pen)
                        } else {
                            val path = android.graphics.Path().apply {
                                moveTo(3f, 9f)
                                lineTo(7f, 9f)
                                lineTo(12f, 5f)
                                lineTo(12f, 19f)
                                lineTo(7f, 15f)
                                lineTo(3f, 15f)
                                close()
                            }
                            canvas.drawPath(path, pen)
                            canvas.drawArc(12f, 5f, 22f, 19f, -65f, 130f, false, pen)
                        }
                        if ((callback as? android.view.View)?.isSelected == true) {
                            canvas.drawLine(3f, 3f, 21f, 21f, pen)
                        }
                        canvas.restore()
                    }
                    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
                    override fun setColorFilter(
                        filter: android.graphics.ColorFilter?
                    ) { pen.colorFilter = filter }
                    override fun getOpacity() =
                        android.graphics.PixelFormat.TRANSLUCENT
                })
            }

        val micButton = audioIcon(true)
        val speakerButton = audioIcon(false)

        fun refreshAudioControls() {
            val micMuted = gatewayService?.isGlobalMuted() == true
            val deafened = gatewayService?.isGlobalDeafened() == true
            micButton.isEnabled = gatewayService != null
            speakerButton.isEnabled = gatewayService != null
            micButton.isSelected = micMuted
            speakerButton.isSelected = deafened
            micButton.tag = if (micMuted) Color.RED else Color.GREEN
            speakerButton.tag = if (deafened) Color.RED else Color.GREEN
            micButton.drawable?.invalidateSelf()
            speakerButton.drawable?.invalidateSelf()
            micButton.contentDescription =
                if (micMuted) "Mikrofon einschalten" else "Mikrofon stummschalten"
            speakerButton.contentDescription =
                if (deafened) "Lautsprecher einschalten" else "Lautsprecher stummschalten"
        }

        micButton.setOnClickListener {
            gatewayService?.let { it.setGlobalMuted(!it.isGlobalMuted()) }
            refreshAudioControls()
        }
        speakerButton.setOnClickListener {
            gatewayService?.let { it.setGlobalDeafened(!it.isGlobalDeafened()) }
            refreshAudioControls()
        }
        audioControls.addView(micButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        audioControls.addView(speakerButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(audioControls)
        refreshGlobalAudioControls = ::refreshAudioControls
        refreshAudioControls()
        frame.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }

        val integrations = listOf(
            "Start", "FRN", "Mumble", "Zello", "Log", "Mehr"
        )

        integrations.forEach { integration ->
            val tab = button(integration) {
                when (integration) {
                    "Start" -> showHome()
                    "FRN" -> showFrn()
                    "Mumble" -> showPage("Mumble")
                    "Zello" -> showPage("Zello")
                    "Log" -> showLog()
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
                "Log" -> "Log"
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
                target !in available -> "${target.name} (getrennt)"
                else -> target.name
            }
            targetButton.isEnabled = service != null
            val state = service?.getPttState() ?: GatewayService.PttState.IDLE
            val ready = target != null && target in available
            val channelBusy =
                target == GatewayService.PttTarget.FRN &&
                service?.getFrnParticipants()?.activeIndex != null
            val muted = service?.isGlobalMuted() == true
            val releasePending = service?.isPttReleasePending() == true
            pttButton.isEnabled =
                ready && !channelBusy && !muted &&
                !releasePending
            val (status, color) = when {
                state == GatewayService.PttState.TRANSMITTING ->
                    "🔴 SENDET" to Color.rgb(190, 30, 40)
                state == GatewayService.PttState.REQUESTING ->
                    "🔴 Warte auf Freigabe" to Color.rgb(165, 45, 45)
                releasePending ->
                    "PTT wird freigegeben" to Color.rgb(110, 120, 135)
                muted ->
                    "PTT · Mikrofon stumm" to Color.rgb(85, 92, 103)
                channelBusy ->
                    "PTT belegt" to Color.rgb(85, 92, 103)
                ready ->
                    "🎙 PTT bereit" to accent
                target == GatewayService.PttTarget.FRN &&
                    service?.getFrnStatus() ==
                    GatewayService.FrnStatus.CONNECTING ->
                    "PTT verbindet …" to Color.rgb(65, 75, 90)
                else ->
                    "Kein Funkkontakt" to Color.rgb(52, 61, 76)
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
                if (!frame.isAttachedToWindow) return
                if (!frnActivityActive) {
                    pttStatusHandler.postDelayed(this, 250L)
                    return
                }
                updatePttTarget()
                if (currentPage == "FRN_PARTICIPANTS") refreshFrnParticipantsView?.invoke()
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

    private fun commitFrnHostTransferSafely(
        groupId: String,
        installationId: String,
        transferId: String
    ): Boolean {
        val saved =
            org.funkgateway.mobile.frn.FrnGroupPersistence
                .commitHostTransferPersisted(
                    this, groupId, installationId, transferId
                )

        if (!saved) {
            android.util.Log.e(
                "FunkGateway-Groups",
                "Hostübergabe angehalten: Sperre nicht bestätigt gespeichert."
            )
            android.widget.Toast.makeText(
                this,
                "Hostübergabe angehalten: Speicherfehler. " +
                    "Bitte keine neue Übergabe starten.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        return saved
    }

    private fun saveFrnGroupsOrWarn(): Boolean {
        val saved =
            org.funkgateway.mobile.frn.FrnGroupPersistence
                .saveCurrentGroups(this)

        if (!saved) {
            android.util.Log.e(
                "FunkGateway-Groups",
                "FRN-Gruppen konnten nicht dauerhaft gespeichert werden."
            )

            android.widget.Toast.makeText(
                this,
                "Achtung: Gruppenänderung konnte nicht gespeichert werden. " +
                    "Sie könnte nach einem Neustart verloren gehen.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        return saved
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val groupsLoaded =
            org.funkgateway.mobile.frn.FrnGroupPersistence.initialize(this)

        if (!groupsLoaded) {
            android.util.Log.w(
                "FunkGateway-Groups",
                "Gruppen konnten nicht initialisiert werden. " +
                    "Vorhandene Gruppendaten bleiben unverändert."
            )
        }

        showHome()
        refreshFrnCacheOnStartup()
    }
}
