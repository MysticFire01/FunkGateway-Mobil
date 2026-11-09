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

    // Zertifikatsdialog nur bei aktiver Oberfläche anzeigen.
    private var mumbleUiResumed = false
    private var activeMumbleCertificateDialog: android.app.AlertDialog? = null
    private var activeMumbleCertificateKey: String? = null

    override fun onResume() {
        super.onResume()
        mumbleUiResumed = true
        window.decorView.post {
            checkPendingMumbleCertificate()
        }
    }

    private fun checkPendingMumbleCertificate() {
        if (!mumbleUiResumed || isFinishing || isDestroyed) return

        val manager = mumbleManager ?: return
        val pending = manager.getPendingCertificateRequest() ?: return

        showMumbleCertificateDialog(
            manager, pending.host, pending.port, pending.certificate
        )
    }

    override fun onPause() {
        mumbleUiResumed = false
        super.onPause()
    }


    private val mumbleCertificateImportRequest = 1496

    private val mumbleCertificateExportRequest = 1497
    private var pendingMumbleCertificateExportId: String? = null
    private var pendingMumbleCertificateExportPassword: String? = null

    private fun exportMumbleCertificate(id: String) {
        val store = MumbleIdentityStore(this)

        val info = runCatching {
            val bytes = store.loadIdentity(id)
            val password = store.loadIdentityPassword(id)
            store.inspectPkcs12(bytes, password)
        }.getOrElse { error ->
            Toast.makeText(
                this,
                error.message ?: "Zertifikat nicht lesbar",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val filename = info.subject
            .substringAfter("CN=", "mumble")
            .substringBefore(",")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(48)
            .ifBlank { "mumble" } + ".p12"

        val password1 = android.widget.EditText(this).apply {
            hint = "Neues Exportpasswort"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        val password2 = android.widget.EditText(this).apply {
            hint = "Passwort wiederholen"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(password1)
            addView(password2)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("🔐 Zertifikat exportieren")
            .setMessage(
                "Vergib ein neues Passwort mit mindestens 12 Zeichen. " +
                "Du benötigst es später zum Importieren."
            )
            .setView(fields)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speicherort wählen", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val first = password1.text.toString()
                    val second = password2.text.toString()

                    when {
                        first.length < 12 ->
                            password1.error = "Mindestens 12 Zeichen"
                        first != second ->
                            password2.error = "Passwörter stimmen nicht überein"
                        else -> {
                            pendingMumbleCertificateExportId = id
                            pendingMumbleCertificateExportPassword = first

                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/x-pkcs12"
                                putExtra(Intent.EXTRA_TITLE, filename)
                            }

                            dialog.dismiss()
                            startActivityForResult(
                                intent, mumbleCertificateExportRequest
                            )
                        }
                    }
                }
        }

        dialog.show()
    }



    private fun selectMumbleCertificateFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, mumbleCertificateImportRequest)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == mumbleCertificateExportRequest) {
            val id = pendingMumbleCertificateExportId
            val exportPassword = pendingMumbleCertificateExportPassword

            pendingMumbleCertificateExportId = null
            pendingMumbleCertificateExportPassword = null

            if (resultCode != Activity.RESULT_OK) return
            val destination = data?.data ?: return

            if (id == null || exportPassword == null) {
                Toast.makeText(
                    this, "Exportdaten fehlen",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            Thread {
                val result = runCatching {
                    val store = MumbleIdentityStore(this)
                    val original = store.loadIdentity(id)
                    val originalPassword = store.loadIdentityPassword(id)

                    val exported = store.repackagePkcs12(
                        original, originalPassword, exportPassword
                    )

                    store.inspectPkcs12(exported, exportPassword)

                    contentResolver.openOutputStream(destination, "w")?.use {
                        it.write(exported)
                        it.flush()
                    } ?: error("Zieldatei konnte nicht geöffnet werden")
                }

                runOnUiThread {
                    result.onSuccess {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("✅ Zertifikat exportiert")
                            .setMessage(
                                "Das Zertifikat wurde mit deinem neuen " +
                                "Passwort gespeichert. Bewahre das Passwort sicher auf."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }.onFailure { error ->
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Zertifikatsexport fehlgeschlagen")
                            .setMessage(error.message ?: "Unbekannter Fehler")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }.start()
            return
        }

        if (requestCode != mumbleCertificateImportRequest) return
        if (resultCode != Activity.RESULT_OK) return

        val uri = data?.data ?: return

        val passwordInput = android.widget.EditText(this).apply {
            hint = "Zertifikatspasswort"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("🔐 Mumble-Zertifikat importieren")
            .setMessage("Bitte das Passwort der .p12- oder .pfx-Datei eingeben.")
            .setView(passwordInput)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Zertifikat prüfen") { _, _ ->
                val password = passwordInput.text.toString()
                Thread {
                    val result = runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use {
                            it.readBytes()
                        } ?: error("Datei konnte nicht geöffnet werden")
                        require(bytes.size <= 1024 * 1024) {
                            "Zertifikatsdatei ist zu groß (maximal 1 MB)"
                        }
                        val store = MumbleIdentityStore(this)
                        val info = store.inspectPkcs12(bytes, password)
                        val id = "import_" + java.util.UUID.randomUUID()
                            .toString().replace("-", "")
                        store.saveCompleteIdentity(id, bytes, password)
                        info
                    }
                    runOnUiThread {
                        result.onSuccess { info ->
                            android.app.AlertDialog.Builder(this)
                                .setTitle("✅ Benutzerzertifikat importiert")
                                .setMessage(
                                    "Inhaber: ${info.subject}\\n\\n" +
                                    "Aussteller: ${info.issuer}\\n\\n" +
                                    "SHA-256: ${info.fingerprint}"
                                )
                                .setPositiveButton("OK") { _, _ ->
                                    showMumbleCertificates()
                                }
                                .show()
                        }.onFailure { error ->
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Zertifikatsprüfung fehlgeschlagen")
                                .setMessage(error.message ?: "Unbekannter Fehler")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }.start()
            }
            .show()
    }

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
    private var mumbleStartupPending = false

    private fun startMumbleOnStartupIfRequested() {
        if (!mumbleStartupPending) return
        mumbleStartupPending = false

        if (!ReconnectSettings.load(this).mumbleConnectOnStart) return

        try {
            val saved = MumbleConnectionStore.load(this)
            if (saved.host.isBlank() || saved.username.isBlank() ||
                saved.port !in 1..65535
            ) {
                android.util.Log.w(
                    "FunkGateway-Mumble",
                    "Autostart übersprungen: keine gültige Standardverbindung"
                )
                return
            }

            mumbleHost = saved.host
            mumblePort = saved.port.toString()
            mumbleUsername = saved.username
            mumblePassword = saved.password

            val manager = getOrCreateMumbleManager()
            if (!manager.hasActiveConnectionAttempt()) {
                val startIntent = Intent(
                    this, GatewayService::class.java
                ).apply {
                    action = GatewayService.ACTION_START
                }
                startForegroundService(startIntent)

                manager.connect(
                    saved.host, saved.port, saved.username, saved.password
                )
            } else {
                android.util.Log.i(
                    "FunkGateway-Mumble",
                    "Autostart übersprungen: Verbindung oder Reconnect aktiv"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e(
                "FunkGateway-Mumble",
                "Mumble-Autostart fehlgeschlagen",
                e
            )
        }
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
            val existingMumbleManager =
                gatewayService?.getMumbleManager()

            if (existingMumbleManager != null) {
                if (mumbleManager !== existingMumbleManager) {
                    mumbleManager?.release()
                }
                mumbleManager = existingMumbleManager
                mumbleOwnedByGatewayService = true
                attachMumbleUiCallbacks(existingMumbleManager)
            } else {
                gatewayService?.setMumbleManager(mumbleManager)
                mumbleOwnedByGatewayService =
                    gatewayService != null && mumbleManager != null
            }

            startMumbleOnStartupIfRequested()

            val audioPrefs = getSharedPreferences(
                "funkgateway_audio",
                Context.MODE_PRIVATE
            )
            gatewayService?.setFrnVolume(
                audioPrefs.getInt("frn_volume", 100).coerceIn(0, 100)
            )
            gatewayService?.setMumbleVolume(
                audioPrefs.getInt("mumble_volume", 100).coerceIn(0, 100)
            )

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

    override fun onDestroy() {
        mumbleManager?.let { manager ->
            manager.clearUiCallbacks(this)

            if (!mumbleOwnedByGatewayService) {
                manager.release()
            }
        }

        mumbleManager = null
        mumbleOwnedByGatewayService = false
        mumbleStatusView = null

        super.onDestroy()
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
        room: org.funkgateway.mobile.frn.FrnRoom,
        profileId: String = org.funkgateway.mobile.frn.FrnProfileStore.selectedId(this),
        navigateToParticipants: Boolean = true
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
                profileId
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
                    selectedFrnRoom?.let { connectedRoom ->
                        org.funkgateway.mobile.frn.FrnLastConnectionStore.save(
                            this,
                            connectedRoom,
                            profileId
                        )
                    }
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

            if (navigateToParticipants) {
                showFrnParticipants()
            }
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
        if (name == "Mumble" || name == "Mumble-Einstellungen") {
            showMumble()
            return
        }
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
        if (name == "Papageien") {
            showParrots()
            return
        }
        if (name == "Hilfe") {
            showHelp()
            return
        }
        if (name == "Verlinkung") {
            root.addView(label("🔗 FRN ↔ Mumble", 26f, Color.WHITE, true))

            val frnStatus = label("", 17f, Color.WHITE)
            val mumbleBridgeStatus = label("", 17f, Color.WHITE)
            val bridgeStatus = label("", 18f, Color.WHITE, true)

            listOf(frnStatus, mumbleBridgeStatus, bridgeStatus).forEach {
                it.setPadding(0, dp(12), 0, dp(12))
                root.addView(it)
            }

            val startButton = button("▶ Verlinkung starten") {
                if (gatewayService?.isMumbleParrotActive() == true) {
                Toast.makeText(this, "Der Papagei läuft noch. Bitte zuerst im Menü Papageien beenden.", Toast.LENGTH_LONG).show()
                return@button
            }
            val started = gatewayService?.startVoipBridge() == true
                Toast.makeText(
                    this,
                    if (started) "FRN-Mumble-Verlinkung gestartet"
                    else "Start nicht möglich. Beide Dienste müssen verbunden sein.",
                    Toast.LENGTH_LONG
                ).show()
                showPage("Verlinkung")
            }

            val stopButton = button("■ Verlinkung stoppen") {
                gatewayService?.stopVoipBridgeManually()
                showPage("Verlinkung")
            }

            val service = gatewayService
            val frnReady = service?.getConnectedFrnRoom() != null
            val mumbleReady = mumbleManager?.isConnected() == true
            val active = service?.isVoipBridgeActive() == true

            frnStatus.text = if (frnReady) "🟢 FRN verbunden"
                else "🔴 FRN nicht verbunden"
            mumbleBridgeStatus.text = if (mumbleReady) "🟢 Mumble verbunden"
                else "🔴 Mumble nicht verbunden"
            bridgeStatus.text = if (active) "🟢 Verlinkung aktiv"
                else "⚪ Verlinkung ausgeschaltet"

            // Auch bei aktivem Papagei anklickbar lassen, damit die Warnung erscheint.
            startButton.isEnabled = frnReady && mumbleReady && !active
            stopButton.isEnabled = active

            root.addView(startButton, LinearLayout.LayoutParams(-1, dp(64)))
            root.addView(stopButton, LinearLayout.LayoutParams(-1, dp(64)))
            root.addView(button("↻ Status aktualisieren") {
                showPage("Verlinkung")
            }, LinearLayout.LayoutParams(-1, dp(56)))
            root.addView(button("← Zurück zur Übersicht") {
                showHome()
            }, LinearLayout.LayoutParams(-1, dp(64)))

            showGatewayScreen(
                ScrollView(this).apply {
                    isFillViewport = true
                    addView(root)
                }
            )
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


    private fun showHelp() {
        currentPage = "Hilfe"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(bg)
        }

        content.addView(label("❔ Hilfe", 26f, Color.WHITE, true))
        content.addView(label(
            "FunkGateway Mobile einfach erklärt. " +
            "Tippe auf ein Thema, um die Anleitung zu öffnen.",
            16f, muted
        ).apply {
            setPadding(0, dp(12), 0, dp(20))
        })

        fun chapter(title: String, explanation: String) {
            val details = label(explanation, 16f, Color.WHITE).apply {
                setPadding(dp(14), dp(12), dp(14), dp(18))
                visibility = android.view.View.GONE
            }

            val header = button("▸ $title") {
                val opening = details.visibility != android.view.View.VISIBLE
                details.visibility = if (opening)
                    android.view.View.VISIBLE
                else
                    android.view.View.GONE
            }

            content.addView(header)
            content.addView(details)
        }

        chapter(
            "🚀 Erste Schritte",
            "1. Öffne FRN oder Mumble.\n" +
            "2. Wähle deinen Server und verbinde dich.\n" +
            "3. Auf der Startseite siehst du den Verbindungsstatus.\n" +
            "4. Mit „Beide zuletzt verbinden“ kannst du später " +
            "beide zuletzt verwendeten Verbindungen starten."
        )

        chapter(
            "📱 Handy-User",
            "In diesem Modus nutzt du dein Smartphone zum Sprechen " +
            "und Zuhören.\n\n" +
            "Drücke die PTT-Taste, wenn du sprechen möchtest. " +
            "Lasse sie wieder los, wenn du fertig bist."
        )

        chapter(
            "🔗 VoIP-Verlinkung",
            "Mit der Verlinkung werden FRN und Mumble miteinander verbunden.\n\n" +
            "Verbinde zuerst beide Dienste. Starte danach die Verlinkung.\n" +
            "Gespräche können dann zwischen beiden Diensten übertragen werden.\n\n" +
            "Zum Beenden drücke „Verlinkung stoppen“."
        )

        chapter(
            "🦜 Mumble-Papagei",
            "Der Papagei nimmt eine Aussendung auf und gibt sie " +
            "anschließend wieder.\n\n" +
            "Verbinde zuerst Mumble. Starte dann den Papagei.\n" +
            "Zum Beenden drücke „Papagei stoppen“."
        )

        chapter(
            "🎙️ PTT und Audio",
            "PTT bedeutet: Drücken, um zu sprechen.\n\n" +
            "Im Handy-User-Modus:\n" +
            "1. Verbinde FRN oder Mumble.\n" +
            "2. Tippe unten auf Ziel.\n" +
            "3. Wähle dein PTT-Ziel aus.\n" +
            "4. Halte die PTT-Taste zum Sprechen gedrückt.\n" +
            "5. Lasse die Taste danach los.\n\n" +
            "Wichtig: Ohne ausgewähltes Ziel kannst du nicht senden.\n\n\n\n" +
            "Prüfe, ob Mikrofon und Lautsprecher richtig eingestellt sind.\n" +
            "Wenn du nichts hörst, prüfe die Lautstärke und die Verbindung."
        )

        chapter(
            "🛡️ Sicherheit",
            "Verlinkung und Papagei können nicht gleichzeitig laufen.\n" +
            "Beim Stoppen werden laufende Audioübertragungen beendet.\n" +
            "Eine Sendezeitbegrenzung schützt vor zu langen Aussendungen."
        )

        chapter(
            "🛠️ Probleme lösen",
            "Keine Verbindung? Prüfe Internet, Server und Zugangsdaten.\n\n" +
            "Kein Ton? Prüfe Lautstärke und Audioeinstellungen.\n\n" +
            "PTT funktioniert nicht? Prüfe Verbindung und ausgewähltes Sendeziel.\n\n" +
            "Verlinkung startet nicht? FRN und Mumble müssen verbunden sein."
        )

        showGatewayScreen(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    private fun showParrots() {
        currentPage = "Papageien"
        var settings = org.funkgateway.mobile.audio.ParrotSettings.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label("🦜 Papageien", 26f, Color.WHITE, true))
        root.addView(label("Mumble-Papagei", 20f, accent, true))
        root.addView(label(
            "Aufnehmen, kurz warten und die Sprache wiedergeben.",
            15f, muted
        ))

        fun timeSlider(
            title: String,
            initial: Int,
            maximum: Int,
            display: (Int) -> String,
            save: (Int) -> Unit
        ) {
            val valueLabel = label(
                "$title: ${display(initial)}", 17f, Color.WHITE, true
            ).apply { setPadding(0, dp(20), 0, dp(8)) }
            root.addView(valueLabel)
            root.addView(android.widget.SeekBar(this).apply {
                max = maximum
                progress = initial
                setOnSeekBarChangeListener(
                    object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: android.widget.SeekBar?,
                            value: Int,
                            fromUser: Boolean
                        ) {
                            if (!fromUser) return
                            valueLabel.text = "$title: ${display(value)}"
                            save(value)
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
        }

        timeSlider(
            "Maximale Aufnahmezeit",
            settings.maxRecordingSeconds - 5,
            115,
            { "${it + 5} Sekunden" }
        ) {
            settings = settings.copy(maxRecordingSeconds = it + 5)
            settings.save(this)
        }

        timeSlider(
            "Wartezeit vor Wiedergabe",
            (settings.playbackDelayMillis / 500L).toInt(),
            20,
            { "${it / 2}.${if (it % 2 == 0) 0 else 5} Sekunden" }
        ) {
            settings = settings.copy(playbackDelayMillis = it * 500L)
            settings.save(this)
        }

        root.addView(label(
            "Warnung bei abgelaufener Redezeit",
            17f, Color.WHITE, true
        ).apply {
            setPadding(0, dp(16), 0, dp(8))
        })

        val announcementModes = listOf(
            "Aus",
            "Piepton",
            "Sprachansage",
            "Piepton und Sprachansage"
        )
        val announcementValues = listOf(
            "off", "beep", "speech", "both"
        )

        root.addView(android.widget.Spinner(this).apply {
            adapter = object : android.widget.ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                announcementModes
            ) {
                override fun getView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup
                ): android.view.View {
                    return super.getView(position, convertView, parent).also {
                        (it as? android.widget.TextView)?.apply {
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.rgb(30, 41, 55))
                            setPadding(16, 12, 16, 12)
                        }
                    }
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup
                ): android.view.View {
                    return super.getDropDownView(position, convertView, parent).also {
                        (it as? android.widget.TextView)?.apply {
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.rgb(30, 41, 55))
                            setPadding(20, 18, 20, 18)
                        }
                    }
                }
            }
            setSelection(
                announcementValues.indexOf(
                    settings.timeoutAnnouncementMode
                ).coerceAtLeast(0)
            )
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long
                    ) {
                        settings = settings.copy(
                            timeoutAnnouncementMode =
                                announcementValues[position],
                            timeoutSignalEnabled =
                                announcementValues[position] != "off"
                        )
                        settings.save(this@MainActivity)
                    }

                    override fun onNothingSelected(
                        parent: android.widget.AdapterView<*>?
                    ) = Unit
                }
        })

        root.addView(label(
            "Text der Sprachansage",
            17f, Color.WHITE, true
        ).apply {
            setPadding(0, dp(16), 0, dp(8))
        })

        val announcementTextField = android.widget.EditText(this).apply {
            setText(settings.timeoutAnnouncementText)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            hint = "Hier den Ansagetext eingeben"
            minLines = 3
            maxLines = 8
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = android.view.Gravity.TOP
        }
        root.addView(announcementTextField)

        root.addView(button("💾 Ansagetext speichern") {
            val newText = announcementTextField.text.toString().trim()
            if (newText.isBlank()) {
                Toast.makeText(
                    this,
                    "Bitte einen Ansagetext eingeben.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                settings = settings.copy(
                    timeoutAnnouncementText = newText.take(1000)
                )
                settings.save(this)
                Toast.makeText(
                    this,
                    "Ansagetext gespeichert. Bitte Sprachansage neu vorbereiten.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        val announcementPrepared =
            gatewayService?.isMumbleParrotAnnouncementPrepared() == true

        root.addView(label(
            if (announcementPrepared)
                "✅ Sprachansage vorbereitet und einsatzbereit"
            else
                "⏳ Sprachansage noch nicht vorbereitet",
            15f,
            if (announcementPrepared) Color.GREEN else muted
        ))

        root.addView(button("🔊 Sprachansage vorbereiten") {
            val service = gatewayService
            if (service == null) {
                Toast.makeText(
                    this,
                    "Gateway-Service ist nicht verbunden.",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (service.isMumbleParrotActive() ||
                       service.isVoipBridgeActive()) {
                Toast.makeText(
                    this,
                    "Bitte zuerst Papagei oder VoIP-Verlinkung beenden.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Sprachansage wird vorbereitet …",
                    Toast.LENGTH_SHORT
                ).show()

                Thread({
                    val prepared = service.prepareMumbleParrotAnnouncement()
                    runOnUiThread {
                        if (currentPage == "Papageien") {
                            showParrots()
                        }
                        Toast.makeText(
                            this,
                            if (prepared)
                                "Sprachansage ist vorbereitet und einsatzbereit."
                            else
                                "Vorbereitung fehlgeschlagen. Bitte TTS-Stimme prüfen.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }, "ParrotAnnouncementPreparation").start()
            }
        })

        root.addView(android.widget.CheckBox(this).apply {
            text = "Papageienkonzert erlauben"
            setTextColor(Color.WHITE)
            isChecked = settings.concertModeEnabled
            setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(concertModeEnabled = checked)
                settings.save(this@MainActivity)
            }
        })

        root.addView(button("▶ Mumble-Papagei starten") {
            if (gatewayService?.isVoipBridgeActive() == true) {
                Toast.makeText(
                    this,
                    "Die VoIP-Verlinkung läuft noch. Bitte zuerst im Menü Verlinkung beenden.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val started = gatewayService?.startMumbleParrot() == true
                Toast.makeText(
                    this,
                    if (started) "Mumble-Papagei gestartet"
                    else "Start nicht möglich. Mumble muss verbunden und die Audiosteuerung frei sein.",
                    Toast.LENGTH_LONG
                ).show()
                showParrots()
            }
        })

        root.addView(button("■ Mumble-Papagei beenden") {
            gatewayService?.stopMumbleParrotManually()
            Toast.makeText(
                this,
                "Mumble-Papagei wird beendet.",
                Toast.LENGTH_SHORT
            ).show()
            showParrots()
        }.apply {
            isEnabled = gatewayService?.isMumbleParrotActive() == true
        })

        val parrotRunning =
            gatewayService?.isMumbleParrotActive() == true
        val mumbleConnected =
            mumbleManager?.isConnected() == true

        val parrotStatus = when {
            parrotRunning ->
                "🟢 Mumble-Papagei läuft – Aufnahme und Wiedergabe aktiv"
            !mumbleConnected ->
                "🔴 Mumble nicht verbunden – bitte zuerst verbinden"
            else ->
                "🟢 Mumble verbunden – Papagei bereit zum Starten"
        }

        root.addView(label(
            parrotStatus,
            14f,
            if (parrotRunning || mumbleConnected) Color.GREEN else muted
        ))
        root.addView(button("← Zurück zur Übersicht") { showHome() })

        showGatewayScreen(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private var mumbleStatusView: android.widget.TextView? = null
    private var mumbleConnectButton: android.widget.Button? = null
    private var mumbleDisconnectButton: android.widget.Button? = null

    private fun refreshMumbleConnectionButtons() {
        val manager = mumbleManager
        val connected = manager?.isConnected() == true
        val connecting = manager?.isConnecting() == true

        mumbleConnectButton?.isEnabled = !connected && !connecting
        mumbleDisconnectButton?.isEnabled = connected || connecting

        if (currentPage == "Mumble") {
            val actualStatus = when {
                connected -> "Mit Mumble verbunden"
                connecting -> "Verbinde mit Mumble …"
                else -> null
            }
            if (actualStatus != null && mumbleStatus != actualStatus) {
                mumbleStatus = actualStatus
                mumbleStatusView?.text = "Status: $actualStatus"
            }
        }
    }

    private fun attachMumbleUiCallbacks(
        manager: MumbleConnectionManager
    ) {
        manager.setUiCallbacks(
            statusCallback = { message ->
                runOnUiThread {
                    mumbleStatus = message
                    if (currentPage == "Mumble") {
                        mumbleStatusView?.text = "Status: $message"
                        refreshMumbleConnectionButtons()
                    }
                }
            },
            certificateCallback = { _, _, _ ->
                runOnUiThread {
                    if (mumbleManager === manager) {
                        android.util.Log.i(
                            "FGW-Mumble-Cert",
                            "Zertifikatsanfrage an Oberfläche gemeldet"
                        )
                        checkPendingMumbleCertificate()
                    }
                }
            },
            owner = this
        )

        manager.getPendingCertificateRequest()?.let { pending ->
            runOnUiThread {
                if (mumbleManager === manager) {
                    showMumbleCertificateDialog(
                        manager,
                        pending.host,
                        pending.port,
                        pending.certificate
                    )
                }
            }
        }
    }

    private fun getOrCreateMumbleManager(): MumbleConnectionManager {
        mumbleManager?.let { return it }

        return MumbleConnectionManager(
            applicationContext,
            onStatusChanged = {},
            onCertificateUntrusted = { _, _, _ -> }
        ).also { manager ->
            mumbleManager = manager
            attachMumbleUiCallbacks(manager)
            gatewayService?.setMumbleManager(manager)
            mumbleOwnedByGatewayService = gatewayService != null
        }
    }

    private var mumbleManager: MumbleConnectionManager? = null
    private var mumbleOwnedByGatewayService = false
    private var mumbleStatus = "Getrennt"
    private var mumbleHost = ""
    private var mumblePort = "64738"
    private var mumbleUsername = ""
    private var mumblePassword = ""

    private fun getDefaultMumbleUsername(): String =
        getSharedPreferences("mumble_settings", Context.MODE_PRIVATE)
            .getString("default_username", "") ?: ""

    private fun setDefaultMumbleUsername(username: String) {
        getSharedPreferences("mumble_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("default_username", username.trim())
            .apply()
    }

    private fun showMumbleCertificateDialog(
        manager: MumbleConnectionManager,
        host: String,
        port: Int,
        certificate: java.security.cert.X509Certificate
    ) {
        if (!mumbleUiResumed || isFinishing || isDestroyed) return

        val pending = manager.getPendingCertificateRequest() ?: return
        if (pending.host != host || pending.port != port ||
            !pending.certificate.encoded.contentEquals(certificate.encoded)
        ) return

        val fingerprint = manager.certificateFingerprint(certificate)
        val key = "$host:$port:$fingerprint"

        if (activeMumbleCertificateDialog?.isShowing == true) {
            if (activeMumbleCertificateKey == key) return
            activeMumbleCertificateDialog?.dismiss()
        }

        val previous = manager.previousCertificateFingerprint(host, port)
        val warning = if (previous == null) {
            "Unbekanntes Serverzertifikat"
        } else {
            "WARNUNG: Das Serverzertifikat wurde geändert!"
        }

        val message = buildString {
            appendLine(warning)
            appendLine()
            appendLine("Server: $host:$port")
            appendLine()
            appendLine("SHA-256:")
            appendLine(fingerprint)
            if (previous != null) {
                appendLine()
                appendLine("Bisheriger Fingerabdruck:")
                appendLine(previous)
            }
            appendLine()
            append("Nur bestätigen, wenn du dem Zertifikat vertraust.")
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Mumble-Zertifikat prüfen")
            .setMessage(message)
            .setNegativeButton("Abbrechen") { _, _ ->
                manager.rejectCertificate(host, port, certificate)
            }
            .setPositiveButton("Zertifikat vertrauen") { _, _ ->
                manager.acceptCertificate(host, port, certificate)
            }
            .create()

        dialog.setOnCancelListener {
            manager.rejectCertificate(host, port, certificate)
        }
        dialog.setOnDismissListener {
            if (activeMumbleCertificateDialog === dialog) {
                activeMumbleCertificateDialog = null
                activeMumbleCertificateKey = null
            }
        }

        activeMumbleCertificateDialog = dialog
        activeMumbleCertificateKey = key
        dialog.show()
    }

    private fun styleMumbleDialog(dialog: android.app.AlertDialog) {
        val dark = android.graphics.Color.rgb(31, 43, 57)
        val turquoise = android.graphics.Color.rgb(58, 205, 193)

        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(dark)
                cornerRadius = dp(16).toFloat()
            }
        )

        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<android.widget.TextView>(titleId)
            ?.setTextColor(Color.WHITE)

        dialog.findViewById<android.widget.TextView>(android.R.id.message)
            ?.setTextColor(Color.LTGRAY)

        dialog.listView?.apply {
            setBackgroundColor(dark)
            setOnHierarchyChangeListener(
                object : android.view.ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(
                        parent: android.view.View,
                        child: android.view.View
                    ) {
                        (child as? android.widget.TextView)
                            ?.setTextColor(Color.LTGRAY)
                    }

                    override fun onChildViewRemoved(
                        parent: android.view.View,
                        child: android.view.View
                    ) = Unit
                }
            )
            for (i in 0 until childCount) {
                (getChildAt(i) as? android.widget.TextView)
                    ?.setTextColor(Color.LTGRAY)
            }
        }

        listOf(
            android.app.AlertDialog.BUTTON_POSITIVE,
            android.app.AlertDialog.BUTTON_NEGATIVE
        ).forEach { which ->
            dialog.getButton(which)?.setTextColor(turquoise)
        }
    }


    private fun showStyledMumbleDialog(
        builder: android.app.AlertDialog.Builder
    ): android.app.AlertDialog {
        val dialog = builder.create()
        dialog.show()
        styleMumbleDialog(dialog)
        return dialog
    }

    private fun showMumble() {
        currentPage = "Mumble"

        if (mumbleHost.isBlank()) {
            try {
                val saved = MumbleConnectionStore.loadLastSuccessful(this)
                    ?: MumbleConnectionStore.load(this)
                if (saved.host.isNotBlank()) {
                    mumbleHost = saved.host
                    mumblePort = saved.port.toString()
                    mumbleUsername = saved.username
                    mumblePassword = saved.password
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "FunkGateway",
                    "Gespeicherte Mumble-Verbindung konnte nicht geladen werden",
                    e
                )
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label("🎧 Mumble", 26f, Color.WHITE, true))
        root.addView(label("Nativer Mumble-Client · Humla", 16f, muted))

        fun field(title: String, value: String, password: Boolean = false):
            android.widget.EditText {
            root.addView(label(title, 15f, Color.WHITE).apply {
                setPadding(0, dp(16), 0, dp(4))
            })
            return android.widget.EditText(this).apply {
                setText(value)
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                if (password) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                root.addView(this)
            }
        }

        val host = field("Serveradresse", mumbleHost)
        val port = field("Port", mumblePort).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val username = field(
            "Benutzername",
            mumbleUsername.ifBlank { getDefaultMumbleUsername() }
        )
        val password = field("Passwort", mumblePassword, true)

        val settingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        settingsContainer.addView(button("💾 Als Standardverbindung speichern") {
            val address = host.text.toString().trim()
            val number = port.text.toString().toIntOrNull()
            val user = username.text.toString().trim()

            if (address.isBlank() || number == null ||
                number !in 1..65535 || user.isBlank()
            ) {
                android.widget.Toast.makeText(
                    this,
                    "Bitte Server, gültigen Port und Benutzernamen eingeben",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                try {
                    val connection = MumbleSavedConnection(
                        host = address,
                        port = number,
                        username = user,
                        password = password.text.toString()
                    )
                    MumbleConnectionStore.save(this, connection)
                    mumbleHost = address
                    mumblePort = number.toString()
                    mumbleUsername = user
                    mumblePassword = connection.password

                    android.widget.Toast.makeText(
                        this,
                        "Mumble-Standardverbindung sicher gespeichert",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.util.Log.e(
                        "FunkGateway",
                        "Mumble-Standardverbindung konnte nicht gespeichert werden",
                        e
                    )
                    android.widget.Toast.makeText(
                        this,
                        "Speichern fehlgeschlagen",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        settingsContainer.addView(button("👤 Standard-Benutzername einstellen") {
            val input = android.widget.EditText(this).apply {
                setSingleLine(true)
                setText(getDefaultMumbleUsername())
                setSelectAllOnFocus(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                        Color.rgb(58, 205, 193)
                    )
                )
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input)
            }

            android.app.AlertDialog.Builder(this)
                .setTitle("Standard-Benutzername für Mumble")
                .setMessage("Dieser Name wird verwendet, wenn kein eigener Benutzername für einen Server ausgewählt wurde.")
                .setView(container)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern") { _, _ ->
                    val value = input.text.toString().trim()
                    setDefaultMumbleUsername(value)
                    if (username.text.toString().isBlank()) {
                        username.setText(value)
                    }
                    android.widget.Toast.makeText(
                        this,
                        "Standard-Benutzername gespeichert",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                .show()
                .apply { styleMumbleDialog(this) }
        })

        val serverButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        serverButtons.addView(button("🌐 Serverliste") {
            mumbleHost = host.text.toString()
            mumblePort = port.text.toString()
            mumbleUsername = username.text.toString()
            mumblePassword = password.text.toString()
            showMumbleServerBrowser(false)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        serverButtons.addView(button("⭐ Favoriten") {
            mumbleHost = host.text.toString()
            mumblePort = port.text.toString()
            mumbleUsername = username.text.toString()
            mumblePassword = password.text.toString()
            showMumbleServerBrowser(true)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        root.addView(serverButtons)

        fun isCurrentMumbleFavorite(): Boolean {
            val address = host.text.toString().trim()
            val number = port.text.toString().trim().toIntOrNull()
            if (address.isEmpty() || number == null || number !in 1..65535) {
                return false
            }
            return MumbleFavorites.load(this).any {
                it.host.trim().equals(address, ignoreCase = true) &&
                    it.port == number
            }
        }

        val saveFavoriteButton = button("☆ Server als Favorit speichern") {}

        fun refreshFavoriteButton() {
            val saved = isCurrentMumbleFavorite()
            saveFavoriteButton.text = if (saved) {
                "✓ Bereits in Favoriten"
            } else {
                "☆ Server als Favorit speichern"
            }
            saveFavoriteButton.isEnabled = !saved
        }

        saveFavoriteButton.setOnClickListener {
            val address = host.text.toString().trim()
            val number = port.text.toString().trim().toIntOrNull()

            when {
                address.isEmpty() -> {
                    Toast.makeText(
                        this, "Bitte Serveradresse eingeben",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                number == null || number !in 1..65535 -> {
                    Toast.makeText(
                        this, "Bitte gültigen Port eingeben",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isCurrentMumbleFavorite() -> {
                    refreshFavoriteButton()
                }
                else -> {
                    MumbleFavorites.add(
                        this,
                        MumblePublicServer(
                            name = address,
                            host = address,
                            port = number,
                            source = "private",
                            username = username.text.toString().trim()
                        )
                    )
                    Toast.makeText(
                        this, "Mumble-Server als Favorit gespeichert",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshFavoriteButton()
                }
            }
        }

        val favoriteWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                refreshFavoriteButton()
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        }

        host.addTextChangedListener(favoriteWatcher)
        port.addTextChangedListener(favoriteWatcher)
        root.addView(saveFavoriteButton)
        refreshFavoriteButton()

        root.addView(button("⚙️ Verbindungseinstellungen") {
            settingsContainer.visibility =
                if (settingsContainer.visibility == View.VISIBLE)
                    View.GONE else View.VISIBLE
        })
        root.addView(settingsContainer)

        val status = label("Status: $mumbleStatus", 17f, Color.WHITE).apply {
            setPadding(0, dp(24), 0, dp(16))
        }
        root.addView(status)

        val tlsStatus = label(
            "🔐 TLS: Noch keine Verbindung gestartet",
            14f, Color.LTGRAY
        )
        root.addView(tlsStatus)

        fun refreshTlsStatus() {
            val currentManager = mumbleManager
            tlsStatus.text = when {
                currentManager?.getPendingCertificateRequest() != null ->
                    "🔐 TLS: Zertifikat empfangen – Bestätigung erforderlich"
                currentManager?.isConnected() == true ->
                    "✅ TLS: Verbindung hergestellt"
                currentManager?.isConnecting() == true ->
                    "⏳ TLS: Warte auf TLS-Ergebnis vom Server"
                else ->
                    "🔐 TLS: Keine aktive Verbindung"
            }
        }

        refreshTlsStatus()

        fun saveInputs() {
            mumbleHost = host.text.toString()
            mumblePort = port.text.toString()
            mumbleUsername = username.text.toString()
            mumblePassword = password.text.toString()
        }

        mumbleStatusView = status
        val manager = getOrCreateMumbleManager()

        mumbleStatus = when {
            manager.isConnected() -> "Mit Mumble verbunden"
            manager.isConnecting() -> "Verbinde mit Mumble …"
            else -> mumbleStatus
        }
        status.text = "Status: $mumbleStatus"

        val connectionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val connectButton = button("🔗 Verbinden") {
            saveInputs()
            val portNumber = mumblePort.toIntOrNull()
            if (portNumber == null) {
                mumbleStatus = "Ungültiger Port"
                status.text = "Status: $mumbleStatus"
            } else {
                val startIntent = Intent(
                    this, GatewayService::class.java
                ).apply {
                    action = GatewayService.ACTION_START
                }
                startForegroundService(startIntent)

                manager.connect(mumbleHost, portNumber, mumbleUsername, mumblePassword)
            }
        }
        connectionButtons.addView(
            connectButton, LinearLayout.LayoutParams(0, dp(56), 1f)
        )

        val disconnectButton = button("⛔ Trennen") {
            saveInputs()
            // Bridge und PTT vor dem eigentlichen Mumble-Disconnect stoppen.
            gatewayService?.onMumbleConnectionLost(manager)
            manager.disconnect()
            gatewayService?.stopIfIdle()
            refreshMumbleConnectionButtons()
        }
        connectionButtons.addView(
            disconnectButton, LinearLayout.LayoutParams(0, dp(56), 1f)
        )

        mumbleConnectButton = connectButton
        mumbleDisconnectButton = disconnectButton
        refreshMumbleConnectionButtons()

        val connectionStatusHandler = android.os.Handler(
            android.os.Looper.getMainLooper()
        )
        val connectionStatusUpdater = object : Runnable {
            override fun run() {
                if (currentPage != "Mumble" ||
                    mumbleStatusView !== status) return
                refreshMumbleConnectionButtons()
                refreshTlsStatus()
                checkPendingMumbleCertificate()
                connectionStatusHandler.postDelayed(this, 500L)
            }
        }
        connectionStatusHandler.postDelayed(connectionStatusUpdater, 500L)

        root.addView(connectionButtons)

        root.addView(button("🔐 Serverzertifikat prüfen") {
            saveInputs()
            val pending = manager.getPendingCertificateRequest()

            when {
                pending != null -> {
                    checkPendingMumbleCertificate()
                }
                manager.isConnected() -> {
                    Toast.makeText(
                        this,
                        "Mumble ist verbunden. Keine Zertifikatsbestätigung offen.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                manager.isConnecting() -> {
                    Toast.makeText(
                        this,
                        "Verbindungsaufbau läuft. Warte auf TLS-Ergebnis.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    val portNumber = mumblePort.toIntOrNull()
                    if (portNumber == null || portNumber !in 1..65535 ||
                        mumbleHost.isBlank() || mumbleUsername.isBlank()
                    ) {
                        Toast.makeText(
                            this,
                            "Bitte zuerst gültige Mumble-Verbindungsdaten eingeben.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        startForegroundService(
                            Intent(this, GatewayService::class.java).apply {
                                action = GatewayService.ACTION_START
                            }
                        )
                        manager.connect(
                            mumbleHost, portNumber, mumbleUsername, mumblePassword
                        )
                    }
                }
            }
        })

        root.addView(button("← Zurück zur Übersicht") {
            showHome()
        })

        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun showMumbleServerBrowser(favoritesOnly: Boolean) {
        currentPage = "Mumble-Serverbrowser"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label(
            if (favoritesOnly) "⭐ Mumble-Favoriten"
            else "🌐 Öffentliche Mumble-Server",
            23f, Color.WHITE, true
        ))

        val search = android.widget.EditText(this).apply {
            hint = "🔎 Servername oder Adresse suchen"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
        }
        root.addView(search)

        val countries = android.widget.Spinner(this)
        root.addView(countries)

        val status = label("Serverliste wird vorbereitet …", 15f, muted)
        root.addView(status)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(list)

        var allServers = emptyList<MumblePublicServer>()
        var selectedCountry = "Alle Länder"
        var visibleCount = 50
        var previousFilter = ""

        fun render() {
            val query = search.text.toString().trim()
            val filtered = allServers.filter { server ->
                (selectedCountry == "Alle Länder" ||
                    server.country == selectedCountry) &&
                    (query.isEmpty() ||
                        server.name.contains(query, ignoreCase = true) ||
                        server.host.contains(query, ignoreCase = true))
            }

            val filterKey = "$selectedCountry|$query"
            if (filterKey != previousFilter) {
                visibleCount = 50
                previousFilter = filterKey
            }

            list.removeAllViews()
            status.text = "${filtered.size} von ${allServers.size} Servern" +
                " · ${minOf(visibleCount, filtered.size)} angezeigt"

            filtered.take(visibleCount).forEach { server ->
                val entry = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }

                val selectButton = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.rgb(32, 44, 58))
                        cornerRadius = dp(12).toFloat()
                    }
                    isClickable = true
                    isFocusable = true

                    addView(label(server.name, 16f, Color.WHITE, true))
                    addView(label(server.country, 13f, muted))
                    addView(label(
                        "${server.host}:${server.port}", 12f, muted
                    ).apply {
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })

                    setOnClickListener {
                        mumbleHost = server.host
                        mumblePort = server.port.toString()
                        val savedFavorite = MumbleFavorites.load(this@MainActivity)
                            .firstOrNull { it.id == server.id }
                        mumbleUsername = savedFavorite?.username
                            ?.ifBlank { getDefaultMumbleUsername() }
                            ?: getDefaultMumbleUsername()
                        showMumble()
                    }
                }

                entry.addView(
                    selectButton,
                    LinearLayout.LayoutParams(0, -2, 1f)
                )

                val favoriteButton = button(
                    if (MumbleFavorites.contains(this, server)) "★" else "☆"
                ) {
                    if (MumbleFavorites.contains(this, server)) {
                        MumbleFavorites.remove(this, server)
                    } else {
                        MumbleFavorites.add(this, server)
                    }
                    if (favoritesOnly) {
                        run {
                            allServers = MumbleFavorites.load(this)
                            render()
                        }
                    } else {
                        render()
                    }
                }

                entry.addView(favoriteButton)

                if (MumbleFavorites.contains(this, server)) {
                    entry.addView(button("✏️") {
                        val saved = MumbleFavorites.load(this)
                            .firstOrNull { it.id == server.id }
                            ?: server

                        val container = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(20), dp(8), dp(20), dp(8))
                        }

                        fun editField(
                            title: String,
                            value: String,
                            numeric: Boolean = false
                        ): android.widget.EditText {
                            container.addView(label(title, 14f, Color.LTGRAY))
                            return android.widget.EditText(this).apply {
                                setSingleLine(true)
                                setText(value)
                                setTextColor(Color.WHITE)
                                setHintTextColor(Color.LTGRAY)
                                if (numeric) {
                                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                                }
                                setBackgroundTintList(
                                    android.content.res.ColorStateList.valueOf(
                                        Color.rgb(58, 205, 193)
                                    )
                                )
                                container.addView(this)
                            }
                        }

                        val nameInput = editField("Servername", saved.name)
                        val hostInput = editField("Serveradresse / IP", saved.host)
                        val portInput = editField("Port", saved.port.toString(), true)
                        val userInput = editField("Benutzername", saved.username).apply {
                            hint = "Leer = Standard-Benutzername"
                        }

                        var editorDialog: android.app.AlertDialog? = null

                        val deleteButton = button("🗑 Favorit löschen") {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Favorit wirklich löschen?")
                                .setMessage(
                                    "Der Favorit '${saved.name}' wird entfernt. " +
                                    "Die Mumble-Zertifikate bleiben erhalten."
                                )
                                .setNegativeButton("Abbrechen", null)
                                .setPositiveButton("Löschen") { _, _ ->
                                    MumbleFavorites.remove(this, saved)
                                    if (favoritesOnly) {
                                        allServers = MumbleFavorites.load(this)
                                    }
                                    render()
                                    editorDialog?.dismiss()
                                    android.widget.Toast.makeText(
                                        this,
                                        "Favorit gelöscht",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .show()
                                .apply { styleMumbleDialog(this) }
                        }.apply {
                            setTextColor(Color.rgb(255, 125, 125))
                        }

                        container.addView(deleteButton)

                        val scroll = android.widget.ScrollView(this).apply {
                            addView(container)
                        }

                        android.app.AlertDialog.Builder(this)
                            .setTitle("✏️ Mumble-Favorit bearbeiten")
                            .setView(scroll)
                            .setNegativeButton("Abbrechen", null)
                            .setPositiveButton("Speichern", null)
                            .create()
                            .apply {
                                editorDialog = this
                                show()
                                styleMumbleDialog(this)

                                getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                                    .setOnClickListener {
                                        val name = nameInput.text.toString().trim()
                                        val host = hostInput.text.toString().trim()
                                        val port = portInput.text.toString().toIntOrNull()
                                        val username = userInput.text.toString().trim()

                                        val error = when {
                                            name.isBlank() -> "Bitte Servernamen eingeben"
                                            host.isBlank() -> "Bitte Serveradresse eingeben"
                                            port == null || port !in 1..65535 ->
                                                "Port muss zwischen 1 und 65535 liegen"
                                            else -> null
                                        }

                                        if (error != null) {
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                error,
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            val updated = saved.copy(
                                                name = name,
                                                host = host,
                                                port = port!!,
                                                username = username
                                            )
                                            val success = MumbleFavorites.update(
                                                this@MainActivity, saved, updated
                                            )
                                            if (success) {
                                                if (favoritesOnly) {
                                                    allServers = MumbleFavorites.load(
                                                        this@MainActivity
                                                    )
                                                }
                                                render()
                                                dismiss()
                                            }
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                if (success) "Favorit gespeichert"
                                                else "Favorit fehlt oder Adresse/Port bereits vorhanden",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                            }
                    })
                }

                list.addView(entry)
            }

            if (filtered.size > visibleCount) {
                list.addView(button("⬇ Weitere 50 Server anzeigen") {
                    visibleCount += 50
                    render()
                })
            }
        }

        fun display(servers: List<MumblePublicServer>) {
            allServers = servers
            val options = listOf("Alle Länder") +
                servers.map { it.country }.distinct().sorted()

            countries.adapter = android.widget.ArrayAdapter(
                this,
                R.layout.mumble_country_spinner,
                options
            ).apply {
                setDropDownViewResource(
                    R.layout.mumble_country_dropdown
                )
            }
            render()
        }

        countries.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedCountry = countries.selectedItem?.toString()
                        ?: "Alle Länder"
                    render()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {}
            }

        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    render()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )

        if (!favoritesOnly) {
            root.addView(button("🔄 Serverliste aktualisieren") {
                status.text = "Aktualisiere offizielle Serverliste …"
                Thread {
                    val result = runCatching { MumblePublicServers.load() }
                    runOnUiThread {
                        if (currentPage != "Mumble-Serverbrowser") {
                            return@runOnUiThread
                        }
                        result.onSuccess { servers ->
                            display(servers)
                        }.onFailure { error ->
                            status.text = "Aktualisierung fehlgeschlagen: ${error.message}"
                        }
                    }
                }.start()
            })
        }

        root.addView(button("← Zurück zu Mumble") {
            showMumble()
        })

        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })

        if (favoritesOnly) {
            display(MumbleFavorites.load(this))
        } else {
            status.text = "Lade offizielle Serverliste …"
            Thread {
                val result = runCatching { MumblePublicServers.load() }
                runOnUiThread {
                    if (currentPage != "Mumble-Serverbrowser") return@runOnUiThread

                    result.onSuccess { servers ->
                        display(servers)
                    }.onFailure { error ->
                        status.text = "Fehler beim Laden: ${error.message}"
                    }
                }
            }.start()
        }
    }

    private fun mumbleMouth(color: Int): View {
        return object : View(this) {
            var mouthColor: Int = color
            private val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
            )

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)

                val cx = width / 2f
                val cy = height / 2f
                val w = width * 0.38f
                val h = height * 0.22f

                paint.color = mouthColor
                paint.style = android.graphics.Paint.Style.FILL

                canvas.drawOval(
                    cx - w, cy - h,
                    cx + w, cy + h,
                    paint
                )

                paint.color = android.graphics.Color.BLACK
                canvas.drawOval(
                    cx - w * 0.77f,
                    cy - h * 0.62f,
                    cx + w * 0.77f,
                    cy + h * 0.62f,
                    paint
                )
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            contentDescription = "Mumble-Sprecherstatus"
        }
    }

    private fun createMumbleIdentity() {
            val nameInput = android.widget.EditText(this).apply {
                setText("FunkGateway")
                setSingleLine(true)
                selectAll()
            }

            android.app.AlertDialog.Builder(this)
                .setTitle("Neues Mumble-Benutzerzertifikat")
                .setMessage("Bitte einen Namen für das Zertifikat eingeben.")
                .setView(nameInput)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Erstellen") { _, _ ->
                    val certificateName = nameInput.text.toString().trim()
                    Thread {
                        val result = runCatching {
                            val generated = MumbleIdentityGenerator.generate(certificateName)
                            val store = MumbleIdentityStore(this)
                            val info = store.inspectPkcs12(
                                generated.pkcs12, generated.password
                            )
                            val id = "fgw_" + java.util.UUID.randomUUID()
                                .toString().replace("-", "")
                            store.saveCompleteIdentity(
                                id, generated.pkcs12, generated.password
                            )
                            info
                        }
                        runOnUiThread {
                            result.onSuccess { info ->
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("Zertifikat erfolgreich erstellt")
                                    .setMessage(
                                        "Inhaber: ${info.subject}\\n\\n" +
                                        "SHA-256: ${info.fingerprint}\\n\\n" +
                                        "Das Zertifikat wurde verschlüsselt gespeichert."
                                    )
                                    .setPositiveButton("OK") { _, _ ->
                                        showMumbleCertificates()
                                    }
                                    .show()
                            }.onFailure { error ->
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("Zertifikatserstellung fehlgeschlagen")
                                    .setMessage(error.message ?: "Unbekannter Fehler")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }.start()
                }
                .show()
    }

    private var mumbleCertificateTab = "TLS"

    private fun showMumbleCertificates() {
        currentPage = "MUMBLE_CERTIFICATES"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val identityCertificateContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tlsCertificateContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val registrationTitle = android.widget.TextView(this).apply {
            text = "👤 Registrierung auf dem Mumble-Server"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setPadding(0, dp(12), 0, dp(8))
        }
        identityCertificateContent.addView(registrationTitle)

        val registrationStatus = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        identityCertificateContent.addView(registrationStatus)

        val registrationManager = mumbleManager
        val registrationInfo = registrationManager?.getOwnRegistrationInfo()

        registrationStatus.text = when {
            registrationInfo == null ->
                "⚪ Nicht mit einem Mumble-Server verbunden."
            registrationInfo.isRegistered ->
                "✅ ${registrationInfo.username} ist registriert (Benutzer-ID ${registrationInfo.userId})."
            else ->
                "🔓 ${registrationInfo.username} ist auf diesem Server noch nicht registriert."
        }

        val registrationButton = button("🔐 Eigenen Benutzer registrieren") {
            val manager = mumbleManager
            if (manager == null) {
                registrationStatus.text = "⚠️ Keine Mumble-Verbindung vorhanden."
            } else {
                registrationStatus.text = manager.requestOwnRegistration()
            }
        }
        registrationButton.isEnabled =
            registrationInfo != null && !registrationInfo.isRegistered
        identityCertificateContent.addView(registrationButton)

        registrationManager?.onRegistrationStatusChanged = { message ->
            runOnUiThread {
                if (currentPage == "MUMBLE_CERTIFICATES") {
                    registrationStatus.text = message
                    val updated = registrationManager?.getOwnRegistrationInfo()
                    registrationButton.isEnabled =
                        updated != null && !updated.isRegistered
                }
            }
        }

        // Gespeicherte TLS-Serverzertifikate unabhängig vom gewählten Server.
        tlsCertificateContent.addView(label(
            "🔐 Gespeicherte TLS-Serverzertifikate", 18f, Color.WHITE
        ))
        tlsCertificateContent.addView(label(
            "Hier findest du alle Mumble-Server, deren Zertifikat du bestätigt hast.",
            14f, Color.LTGRAY
        ))

        val serverCertificateStore = MumbleCertificateStore(this)
        val savedServers = runCatching {
            serverCertificateStore.listServerCertificates()
        }

        savedServers.onFailure { error ->
            tlsCertificateContent.addView(label(
                "⚠️ Zertifikatsliste konnte nicht gelesen werden: ${error.message}",
                14f, Color.LTGRAY
            ))
        }

        savedServers.getOrNull()?.let { servers ->
            if (servers.isEmpty()) {
                tlsCertificateContent.addView(label(
                    "Noch keine TLS-Serverzertifikate gespeichert.",
                    14f, Color.LTGRAY
                ))
            } else {
                tlsCertificateContent.addView(label(
                    "Server auswählen:", 15f, Color.WHITE
                ))

                val serverNames = servers.map {
                    "${it.host}:${it.port}"
                }

                val selector = android.widget.Spinner(this).apply {
                    adapter = android.widget.ArrayAdapter(
                        this@MainActivity,
                        R.layout.frn_spinner_item,
                        serverNames
                    )
                    background = rounded(panel)
                    setPopupBackgroundDrawable(rounded(panel))
                    minimumHeight = dp(54)
                    setPadding(dp(8), 0, dp(8), 0)
                }

                tlsCertificateContent.addView(selector)

                val detailsContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                tlsCertificateContent.addView(detailsContainer)

                fun showSelectedCertificate(position: Int) {
                    detailsContainer.removeAllViews()
                    val saved = servers.getOrNull(position) ?: return
                    val serverName = "${saved.host}:${saved.port}"

                    detailsContainer.addView(label(
                        "🛡️ $serverName", 16f, Color.WHITE
                    ))
                    detailsContainer.addView(label(
                        "SHA-256-Fingerabdruck:", 13f, Color.LTGRAY
                    ))
                    detailsContainer.addView(label(
                        serverCertificateStore.fingerprint(saved.certificate),
                        13f, Color.LTGRAY
                    ))

                    detailsContainer.addView(button(
                        "🗑️ Zertifikat dieses Servers löschen"
                    ) {
                        if (mumbleManager?.isConnected() == true ||
                            mumbleManager?.isConnecting() == true
                        ) {
                            Toast.makeText(
                                this,
                                "Bitte zuerst die Mumble-Verbindung trennen.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("TLS-Zertifikat löschen?")
                                .setMessage(
                                    "Server: $serverName\n\n" +
                                    "Dieses Serverzertifikat wirklich löschen?\n\n" +
                                    "Bei der nächsten Verbindung muss es erneut " +
                                    "geprüft und bestätigt werden.\n\n" +
                                    "Persönliche P12/PFX-Zertifikate bleiben erhalten."
                                )
                                .setNegativeButton("Abbrechen", null)
                                .setPositiveButton("Löschen") { _, _ ->
                                    if (mumbleManager?.isConnected() == true ||
                                        mumbleManager?.isConnecting() == true
                                    ) {
                                        Toast.makeText(
                                            this,
                                            "Bitte zuerst Mumble trennen.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        runCatching {
                                            serverCertificateStore.deleteServerCertificate(
                                                saved.host, saved.port
                                            )
                                        }.onSuccess {
                                            Toast.makeText(
                                                this,
                                                "TLS-Zertifikat gelöscht.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            showMumbleCertificates()
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                this,
                                                "Löschen fehlgeschlagen: ${error.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                                .show()
                        }
                    })
                }

                selector.onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            showSelectedCertificate(position)
                        }

                        override fun onNothingSelected(
                            parent: android.widget.AdapterView<*>?
                        ) {
                            detailsContainer.removeAllViews()
                        }
                    }
            }
        }

        identityCertificateContent.addView(label("👤 Persönliche Benutzerzertifikate (P12/PFX)", 18f, Color.WHITE))
        identityCertificateContent.addView(label(
            "Diese Zertifikate gehören zu deinem Benutzerkonto und " +
            "werden durch das Löschen eines TLS-Serverzertifikats nicht verändert.",
            14f, Color.LTGRAY
        ))

        identityCertificateContent.addView(button("➕ Neues Zertifikat erstellen") {
            createMumbleIdentity()
        })

        identityCertificateContent.addView(button("📂 Zertifikat importieren") {
            selectMumbleCertificateFile()
        })

        var identityCertificatesLoaded = false

        fun loadIdentityCertificates() {
            if (identityCertificatesLoaded) return
            identityCertificatesLoaded = true

            val store = MumbleIdentityStore(this)
            val identities = store.listIdentities()
            val selected = store.selectedIdentityId()

            if (identities.isEmpty()) {
                identityCertificateContent.addView(android.widget.TextView(this).apply {
                    text = "Noch keine Mumble-Zertifikate gespeichert."
                    textSize = 15f
                })
            }

            identities.forEach { id ->
                val active = id == selected

                val details = runCatching {
                    val bytes = store.loadIdentity(id)
                    val password = store.loadIdentityPassword(id)
                    store.inspectPkcs12(bytes, password)
                }

                val displayName = details.getOrNull()?.subject
                    ?.let { subject ->
                        Regex("(?:^|,)\\s*CN=([^,]+)")
                            .find(subject)?.groupValues?.get(1)
                            ?: subject
                    }
                    ?: "Zertifikat nicht lesbar"

                val validity = details.getOrNull()?.let { info ->
                    val date = java.text.SimpleDateFormat(
                        "dd.MM.yyyy",
                        java.util.Locale.GERMANY
                    ).format(java.util.Date(info.validUntil))
                    "Gültig bis $date"
                } ?: "Zertifikat prüfen"

                val status = if (active) "✅ Aktiv" else "🔐 Auswählbar"
                identityCertificateContent.addView(button(
                    "$status · $displayName\n$validity"
                ) {
                    val info = details.getOrNull()
                    val dateFormat = java.text.SimpleDateFormat(
                        "dd.MM.yyyy", java.util.Locale.GERMANY
                    )

                    val message = if (info != null) {
                        "Name: $displayName\n\n" +
                        "Inhaber: ${info.subject}\n\n" +
                        "Aussteller: ${info.issuer}\n\n" +
                        "Gültig ab: ${dateFormat.format(java.util.Date(info.validFrom))}\n" +
                        "Gültig bis: ${dateFormat.format(java.util.Date(info.validUntil))}\n\n" +
                        "SHA-256:\n${info.fingerprint}\n\n" +
                        "Status: ${if (active) "Aktive Identität" else "Nicht ausgewählt"}"
                    } else {
                        "Dieses Zertifikat konnte nicht gelesen werden.\n\n" +
                        (details.exceptionOrNull()?.message ?: "Unbekannter Fehler")
                    }

                    android.app.AlertDialog.Builder(this)
                        .setTitle("🔐 Zertifikatsdetails")
                        .setMessage(message)
                        .setNegativeButton("Schließen", null)
                        .setNeutralButton("📤 Exportieren") { _, _ ->
                            exportMumbleCertificate(id)
                        }
                        .setPositiveButton("Auswählen") { _, _ ->
                            runCatching {
                                store.selectIdentity(id)
                            }.onSuccess {
                                showMumbleCertificates()
                            }.onFailure { error ->
                                android.widget.Toast.makeText(
                                    this, error.message ?: "Auswahl fehlgeschlagen",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .show()
                })
                identityCertificateContent.addView(button("🗑️ $displayName löschen") {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Zertifikat wirklich löschen?")
                        .setMessage(
                            "Zertifikat: $displayName\n\n" +
                            "Das Zertifikat und sein gespeichertes Passwort " +
                            "werden von diesem Smartphone gelöscht.\n\n" +
                            "Diese Aktion kann nicht rückgängig gemacht werden. " +
                            "Bereits exportierte Dateien bleiben erhalten." +
                            if (active) "\n\nACHTUNG: Dieses Zertifikat ist aktuell ausgewählt." else ""
                        )
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Endgültig löschen") { _, _ ->
                            runCatching {
                                store.deleteIdentity(id)
                            }.onSuccess {
                                Toast.makeText(
                                    this,
                                    "Zertifikat gelöscht",
                                    Toast.LENGTH_SHORT
                                ).show()
                                showMumbleCertificates()
                            }.onFailure { error ->
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("Löschen fehlgeschlagen")
                                    .setMessage(error.message ?: "Unbekannter Fehler")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                        .show()
                })



            }
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tlsTabButton = button("🔐 TLS-Server") {}
        val identityTabButton = button("👤 Benutzer-Identität") {}

        tabs.addView(tlsTabButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        tabs.addView(identityTabButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        root.addView(tabs)
        root.addView(tlsCertificateContent)
        root.addView(identityCertificateContent)

        fun updateCertificateTabs() {
            val showTls = mumbleCertificateTab == "TLS"
            tlsCertificateContent.visibility =
                if (showTls) View.VISIBLE else View.GONE
            identityCertificateContent.visibility =
                if (showTls) View.GONE else View.VISIBLE

            if (!showTls) {
                loadIdentityCertificates()
            }

            tlsTabButton.isEnabled = !showTls
            identityTabButton.isEnabled = showTls
        }

        tlsTabButton.setOnClickListener {
            mumbleCertificateTab = "TLS"
            updateCertificateTabs()
        }
        identityTabButton.setOnClickListener {
            mumbleCertificateTab = "IDENTITY"
            updateCertificateTabs()
        }
        updateCertificateTabs()

        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun showMumbleRooms() {
        currentPage = "MUMBLE_ROOMS"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label("📂 Mumble – Räume & Nutzer", 22f, Color.WHITE, true))
        root.addView(label("Tippe auf einen Raum zum Auf- und Zuklappen.", 14f, muted))

        val treeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }


        val expandedChannels = mutableSetOf<Int>()
        var ownPosition = mumbleManager?.getOwnPosition()
        val mouthViews = mutableMapOf<Int, View>()

        fun expandOwnPath(channel: MumbleConnectionManager.MumbleChannelItem): Boolean {
            val containsSelf = channel.id == ownPosition?.channelId ||
                channel.children.any { expandOwnPath(it) }
            if (containsSelf) expandedChannels.add(channel.id)
            return containsSelf
        }

        mumbleManager?.getChannelTree()?.let { expandOwnPath(it) }

        var treeScroll: ScrollView? = null

        fun renderTree() {
            val previousScrollY = treeScroll?.scrollY ?: 0
            ownPosition = mumbleManager?.getOwnPosition()
            mouthViews.clear()
            treeContainer.removeAllViews()
            val channelTree = mumbleManager?.getChannelTree()

            if (channelTree == null) {
                treeContainer.addView(label("Nicht mit Mumble verbunden.", 15f, muted))
                return
            }

            fun addChannel(
                channel: MumbleConnectionManager.MumbleChannelItem,
                depth: Int
            ) {
                val expanded = channel.id in expandedChannels
                val prefix = if (expanded) "📂" else "📁"
                val isOwnRoom = channel.id == ownPosition?.channelId

                val room = label(
                    "$prefix ${channel.name} (${channel.users.size})" +
                        if (isOwnRoom) "  📍 Mein Raum" else "",
                    16f,
                    Color.WHITE,
                    true
                ).apply {
                    setPadding(dp(8 + depth * 16), dp(12), dp(8), dp(12))
                    setOnClickListener {
                        if (expanded) expandedChannels.remove(channel.id)
                        else expandedChannels.add(channel.id)
                        renderTree()
                    }
                    setOnLongClickListener {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Mumble-Raum betreten")
                            .setMessage("Möchtest du den Raum \"${channel.name}\" betreten?")
                            .setPositiveButton("Raum betreten") { _, _ ->
                                if (mumbleManager?.joinChannel(channel.id) == true) {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Raumwechsel angefordert: ${channel.name}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .setNegativeButton("Abbrechen", null)
                            .show()
                        true
                    }
                }
                treeContainer.addView(room)

                if (expanded) {
                    channel.users.forEach { user ->
                        val isSelf = user.sessionId == ownPosition?.sessionId
                        val isTalking = user.talkState != "PASSIVE"
                        val mouth = when {
                            !isTalking -> "🟢"
                            isSelf -> "🟠"
                            else -> "🔴"
                        }

                        val userRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(dp(28 + depth * 16), dp(5), dp(8), dp(5))
                        }

                        val mouthColor = when {
                            !isTalking -> Color.GREEN
                            isSelf -> Color.rgb(255, 165, 0)
                            else -> Color.RED
                        }

                        val mouthView = mumbleMouth(mouthColor)
                        mouthViews[user.sessionId] = mouthView
                        userRow.addView(mouthView)
                        val userStatus = buildList {
                            add(if (user.registered) "🟢R" else "⚪G")
                            if (user.selfMuted) add("🎙️SM")
                            if (user.selfDeafened) add("🎧SD")
                            if (user.serverMuted) add("🔴MM")
                            if (user.serverDeafened) add("🔴MD")
                            if (user.localMuted) add("🔇LM")
                            if (user.suppressed) add("⛔SUP")
                        }.joinToString(" ")

                        val userDetails = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(8), 0, 0, 0)
                        }

                        val displayName =
                            if (isSelf) "⭐ ${user.name} (Ich)"
                            else "👤 ${user.name}"

                        userDetails.addView(
                            label(
                                displayName,
                                15f,
                                if (isSelf) Color.WHITE else muted
                            )
                        )

                        userDetails.addView(
                            label(
                                userStatus,
                                12f,
                                Color.rgb(170, 190, 205)
                            )
                        )

                        userRow.addView(
                            userDetails,
                            LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        )

                        if (!isSelf) {
                            userRow.setOnLongClickListener {
                                val manager = mumbleManager
                                val generation =
                                    manager?.getModerationConnectionGeneration()

                                val actions = mutableListOf<String>()
                                if (!user.registered && manager?.canRegisterOtherUsers() == true) {
                                    actions.add("🔐 Benutzer registrieren")
                                }
                                if (generation != null &&
                                    manager.hasMumbleModerationPermission(
                                        se.lublin.humla.net.Permissions.Kick
                                    )
                                ) {
                                    actions.add("🚪 Benutzer kicken")
                                }
                                if (generation != null &&
                                    manager.hasMumbleModerationPermission(
                                        se.lublin.humla.net.Permissions.MuteDeafen
                                    )
                                ) {
                                    actions.add(
                                        if (user.serverMuted)
                                            "🔊 Server-Mute aufheben"
                                        else "🔇 Serverseitig stummschalten"
                                    )
                                    actions.add(
                                        if (user.serverDeafened)
                                            "🔉 Server-Deaf aufheben"
                                        else "🎧 Serverseitig taubschalten"
                                    )
                                }

                                if (actions.isEmpty()) {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Keine verfügbaren Benutzeraktionen",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    android.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("👤 ${user.name}")
                                        .setItems(actions.toTypedArray()) { _, selected ->
                                            when (actions[selected]) {
                                                "🔐 Benutzer registrieren" -> {
                                                    android.app.AlertDialog.Builder(this@MainActivity)
                                                        .setTitle("Benutzer registrieren?")
                                                        .setMessage(
                                                            "Soll ${user.name} auf diesem Mumble-Server " +
                                                                "registriert werden?\n\n" +
                                                                "Dafür benötigst du die entsprechende " +
                                                                "Berechtigung auf dem Server."
                                                        )
                                                        .setNegativeButton("Abbrechen", null)
                                                        .setPositiveButton("Registrieren") { _, _ ->
                                                            val result = mumbleManager
                                                                ?.requestOtherUserRegistration(user.sessionId)
                                                                ?: "Keine Mumble-Verbindung vorhanden"
                                                            android.widget.Toast.makeText(
                                                                this@MainActivity,
                                                                result,
                                                                android.widget.Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                        .show().also { styleMumbleDialog(it) }
                                                }
                                                "🔇 Serverseitig stummschalten",
                                                "🔊 Server-Mute aufheben",
                                                "🎧 Serverseitig taubschalten",
                                                "🔉 Server-Deaf aufheben" -> {
                                                    val action = actions[selected]
                                                    val isMute = action.contains("Mute") ||
                                                        action.contains("stummschalten")
                                                    val enable = action.contains("stummschalten") ||
                                                        action.contains("taubschalten")

                                                    android.app.AlertDialog.Builder(this@MainActivity)
                                                        .setTitle(action)
                                                        .setMessage(
                                                            "Soll der serverseitige Audiostatus von " +
                                                                "${user.name} geändert werden?\n\n" +
                                                                "Dafür benötigst du die entsprechende " +
                                                                "Mumble-Administratorberechtigung."
                                                        )
                                                        .setNegativeButton("Abbrechen", null)
                                                        .setPositiveButton("Bestätigen") { _, _ ->
                                                            val result = if (generation != null) {
                                                                mumbleManager
                                                                    ?.setMumbleUserServerAudioState(
                                                                        user.sessionId,
                                                                        user.name,
                                                                        generation,
                                                                        muted = if (isMute) enable else null,
                                                                        deafened = if (isMute) null else enable
                                                                    )
                                                                    ?: "Keine Mumble-Verbindung vorhanden"
                                                            } else {
                                                                "Mumble-Verbindung hat sich geändert"
                                                            }
                                                            android.widget.Toast.makeText(
                                                                this@MainActivity,
                                                                result,
                                                                android.widget.Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                        .show().also { styleMumbleDialog(it) }
                                                }
                                                "🚪 Benutzer kicken" -> {
                                                    android.app.AlertDialog.Builder(this@MainActivity)
                                                        .setTitle("Benutzer kicken?")
                                                        .setMessage(
                                                            "Soll ${user.name} vom Mumble-Server " +
                                                                "entfernt werden?\n\n" +
                                                                "Dies ist kein dauerhafter Bann."
                                                        )
                                                        .setNegativeButton("Abbrechen", null)
                                                        .setPositiveButton("Kicken") { _, _ ->
                                                            val result = if (generation != null) {
                                                                mumbleManager?.kickMumbleUser(
                                                                    user.sessionId,
                                                                    user.name,
                                                                    generation
                                                                ) ?: "Keine Mumble-Verbindung vorhanden"
                                                            } else {
                                                                "Mumble-Verbindung hat sich geändert"
                                                            }
                                                            android.widget.Toast.makeText(
                                                                this@MainActivity,
                                                                result,
                                                                android.widget.Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                        .show().also { styleMumbleDialog(it) }
                                                }
                                            }
                                        }
                                        .show().also { styleMumbleDialog(it) }
                                }
                                true
                            }
                        }

                        treeContainer.addView(userRow)
                    }

                    channel.children.forEach { child ->
                        addChannel(child, depth + 1)
                    }
                }
            }

            addChannel(channelTree, 0)
            treeScroll?.post {
                treeScroll?.scrollTo(0, previousScrollY)
            }
        }

        fun collectChannelIds(
            channel: MumbleConnectionManager.MumbleChannelItem
        ) {
            expandedChannels.add(channel.id)
            channel.children.forEach { collectChannelIds(it) }
        }

        val expandButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        expandButtons.addView(
            button("📂 Alle auf") {
                mumbleManager?.getChannelTree()?.let { collectChannelIds(it) }
                renderTree()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        expandButtons.addView(
            button("📁 Alle zu") {
                expandedChannels.clear()
                renderTree()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val refreshButton = button("🔄") {
            renderTree()
        }

        expandButtons.addView(
            refreshButton,
            LinearLayout.LayoutParams(dp(56), dp(48))
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(treeContainer)
        }

        root.addView(expandButtons)

        val mumbleAudioButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val mumbleMuteButton = button("🎙️ Mumble Mute") {}
        val mumbleDeafButton = button("🎧 Mumble Deaf") {}

        fun refreshMumbleAudioButtons() {
            val state = mumbleManager?.getSelfAudioState()
            mumbleMuteButton.isEnabled = state != null
            mumbleDeafButton.isEnabled = state != null

            mumbleMuteButton.text =
                if (state?.muted == true) "🔴 Mumble Mute"
                else "🟢 Mumble Mikrofon"

            mumbleDeafButton.text =
                if (state?.deafened == true) "🔴 Mumble Deaf"
                else "🟢 Mumble Hören"
        }

        mumbleMuteButton.setOnClickListener {
            val state = mumbleManager?.getSelfAudioState()
                ?: return@setOnClickListener
            val newMute = !state.muted
            mumbleManager?.setSelfAudioState(
                muted = newMute,
                deafened = if (newMute) state.deafened else false
            )
            refreshMumbleAudioButtons()
        }

        mumbleDeafButton.setOnClickListener {
            val state = mumbleManager?.getSelfAudioState()
                ?: return@setOnClickListener
            val newDeaf = !state.deafened
            mumbleManager?.setSelfAudioState(
                muted = if (newDeaf) true else state.muted,
                deafened = newDeaf
            )
            refreshMumbleAudioButtons()
        }

        mumbleAudioButtons.addView(
            mumbleMuteButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        mumbleAudioButtons.addView(
            mumbleDeafButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        root.addView(mumbleAudioButtons)

        val mumbleAudioStatusHandler =
            android.os.Handler(android.os.Looper.getMainLooper())
        val mumbleAudioStatusUpdater = object : Runnable {
            override fun run() {
                if (currentPage != "MUMBLE_ROOMS") return
                refreshMumbleAudioButtons()
                mumbleAudioStatusHandler.postDelayed(this, 500L)
            }
        }
        mumbleAudioStatusHandler.postDelayed(
            mumbleAudioStatusUpdater, 500L
        )

        refreshMumbleAudioButtons()
        treeScroll = scroll
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        renderTree()

        mumbleManager?.onChannelMembershipChanged = {
            if (currentPage == "MUMBLE_ROOMS") {
                renderTree()
            }
        }

        mumbleManager?.onOtherUserRegistrationStatusChanged = { message ->
            runOnUiThread {
                if (currentPage == "MUMBLE_ROOMS") {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("🔐 Mumble-Registrierung")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show().also { styleMumbleDialog(it) }
                    renderTree()
                }
            }
        }

        mumbleManager?.onTalkStateChanged = { sessionId, talkState ->
            if (currentPage == "MUMBLE_ROOMS") {
                mouthViews[sessionId]?.let { mouthView ->
                    val color = when {
                        talkState == "PASSIVE" -> Color.GREEN
                        sessionId == ownPosition?.sessionId -> Color.rgb(255, 165, 0)
                        else -> Color.RED
                    }
                    val field = mouthView.javaClass.getDeclaredField("mouthColor")
                    field.isAccessible = true
                    field.setInt(mouthView, color)
                    mouthView.invalidate()
                }
            }
        }

        showGatewayScreen(root)
    }


    private fun showMumbleAdmin() {
        currentPage = "MUMBLE_ADMIN"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label("🛡️ Mumble – Administration", 22f, Color.WHITE, true))
        root.addView(label(
            "Verwaltung und Moderation des verbundenen Mumble-Servers.",
            14f, muted
        ))

        root.addView(button("👥 Registrierte Benutzer verwalten") {
            showMumbleRegisteredUsers()
        })

        root.addView(button("🚫 Bannliste verwalten") {
            showMumbleBanList()
        })

        root.addView(button("🔐 Kanalrechte und Gruppen (ACL)") {
            showMumbleAcl()
        })

        root.addView(label(
            "Weitere Administratorfunktionen folgen.",
            14f, muted
        ))

        showGatewayScreen(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun createMumbleAclSpinner(
        options: List<String>,
        selectedIndex: Int = 0
    ): Spinner {
        val darkColor = Color.rgb(31, 43, 57)

        val selector = Spinner(this)
        selector.adapter = object : android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            options
        ) {
            override fun getView(
                position: Int,
                convertView: android.view.View?,
                parent: android.view.ViewGroup
            ): android.view.View {
                return super.getView(position, convertView, parent).also {
                    (it as? android.widget.TextView)?.apply {
                        setTextColor(
                            if (options.getOrNull(position)?.contains(
                                    "geerbt", ignoreCase = true
                                ) == true
                            ) Color.rgb(155, 165, 177)
                            else Color.WHITE
                        )
                        setBackgroundColor(darkColor)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                    }
                }
            }

            override fun getDropDownView(
                position: Int,
                convertView: android.view.View?,
                parent: android.view.ViewGroup
            ): android.view.View {
                return super.getDropDownView(
                    position, convertView, parent
                ).also {
                    (it as? android.widget.TextView)?.apply {
                        setTextColor(
                            if (options.getOrNull(position)?.contains(
                                    "geerbt", ignoreCase = true
                                ) == true
                            ) Color.rgb(155, 165, 177)
                            else Color.WHITE
                        )
                        setBackgroundColor(darkColor)
                        setPadding(dp(12), dp(14), dp(12), dp(14))
                    }
                }
            }
        }

        selector.setPopupBackgroundDrawable(rounded(darkColor))
        selector.background = rounded(darkColor)
        selector.minimumHeight = dp(48)

        if (options.isNotEmpty()) {
            selector.setSelection(
                selectedIndex.coerceIn(0, options.lastIndex)
            )
        }

        return selector
    }

    private fun showMumbleAclRuleEditor(
        rule: se.lublin.humla.protobuf.Mumble.ACL.ChanACL?,
        availableGroups: List<String> = emptyList(),
        availableRules: List<
            se.lublin.humla.protobuf.Mumble.ACL.ChanACL
        > = emptyList(),
        onEditSelectedRule: ((
            se.lublin.humla.protobuf.Mumble.ACL.ChanACL
        ) -> Unit)? = null,
        onSave: (
            se.lublin.humla.protobuf.Mumble.ACL.ChanACL
        ) -> Unit
    ) {
        val bgColor = Color.rgb(31, 43, 57)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundColor(bgColor)
        }

        content.addView(label(
            "Regelziel", 17f, Color.WHITE, true
        ))

        val targetOptions = listOf(
            "Allgemein", "Benutzer", "Gruppe"
        )

        val targetIndex = when (
            rule?.let { MumbleAclRuleEditor.targetType(it) }
        ) {
            MumbleAclRuleEditor.TargetType.USER -> 1
            MumbleAclRuleEditor.TargetType.GROUP -> 2
            else -> 0
        }

        val targetSpinner = createMumbleAclSpinner(
            targetOptions, targetIndex
        )
        content.addView(targetSpinner)

        val userIdInput = android.widget.EditText(this).apply {
            hint = "Registrierte Benutzer-ID"
            setText(
                if (rule?.hasUserId() == true)
                    rule.userId.toString()
                else ""
            )
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = rounded(bgColor)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val groupNameInput = android.widget.EditText(this).apply {
            hint = "Mumble-Gruppenname"
            setText(
                if (rule?.hasGroup() == true) rule.group
                else ""
            )
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            background = rounded(bgColor)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        var onGroupSelectionChanged: ((String, se.lublin.humla.protobuf.Mumble.ACL.ChanACL?) -> Unit)? = null
        var viewingDifferentGroup = false

        val groupOptions = (
            listOf("@all", "@in", "@out", "@auth", "@sub") +
            availableGroups +
            listOfNotNull(rule?.takeIf { it.hasGroup() }?.group)
        ).filter { it.isNotBlank() }.distinct()

        val aclGroupEntries = availableRules
            .filter { it.hasGroup() && it.group.isNotBlank() }

        val groupEntryTargets = groupOptions.flatMap { groupName ->
            val matchingRules = aclGroupEntries.filter {
                it.group == groupName
            }
            if (matchingRules.isEmpty()) {
                listOf(groupName to null)
            } else {
                matchingRules.map { acl -> groupName to acl }
            }
        }

        val groupDisplayOptions = groupEntryTargets.mapIndexed {
                position, entry ->
            val (groupName, acl) = entry
            if (acl == null) {
                groupName
            } else {
                val number = groupEntryTargets.take(position + 1)
                    .count { it.first == groupName }
                "$groupName – Regel $number – " +
                    if (acl.inherited) "geerbt (nur lesen)"
                    else "direkt"
            }
        }

        val groupSpinner = createMumbleAclSpinner(
            listOf("Gruppe auswählen …") + groupDisplayOptions +
                listOf("✏️ Gruppenname manuell eingeben"),
            groupEntryTargets.indexOfFirst {
                it.second === rule
            }.let { index ->
                if (index >= 0) index + 1
                else groupEntryTargets.indexOfFirst {
                    it.first == rule?.takeIf { r -> r.hasGroup() }?.group
                }.let { if (it >= 0) it + 1 else 0 }
            }
        )

        groupSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position in 1..groupEntryTargets.size) {
                        val (groupName, selectedAcl) =
                            groupEntryTargets[position - 1]
                        groupNameInput.setText(groupName)
                        onGroupSelectionChanged?.invoke(groupName, selectedAcl)
                    }
                    groupNameInput.visibility =
                        if (targetSpinner.selectedItemPosition == 2 &&
                            position == groupEntryTargets.size + 1)
                            View.VISIBLE else View.GONE
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) = Unit
            }

        content.addView(userIdInput)
        content.addView(groupSpinner)
        content.addView(groupNameInput)

        fun refreshTargetFields() {
            userIdInput.visibility =
                if (targetSpinner.selectedItemPosition == 1)
                    View.VISIBLE else View.GONE

            groupSpinner.visibility =
                if (targetSpinner.selectedItemPosition == 2)
                    View.VISIBLE else View.GONE

            groupNameInput.visibility =
                if (targetSpinner.selectedItemPosition == 2 &&
                    groupSpinner.selectedItemPosition ==
                        groupEntryTargets.size + 1)
                    View.VISIBLE else View.GONE
        }

        targetSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    refreshTargetFields()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {
                    refreshTargetFields()
                }
            }

        refreshTargetFields()

        content.addView(label(
            "Berechtigungen", 17f, Color.WHITE, true
        ))

        val permissionSelectors = mutableListOf<
            Triple<Int, android.widget.CheckBox, android.widget.CheckBox>
        >()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(label("Berechtigung", 13f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("Erlauben", 12f, Color.WHITE, true),
            LinearLayout.LayoutParams(dp(76), -2))
        header.addView(label("Verweigern", 12f, Color.WHITE, true),
            LinearLayout.LayoutParams(dp(86), -2))
        content.addView(header)

        MumbleAclManager.editablePermissions().forEach { (bit, name) ->
            val currentState = if (rule == null) {
                MumbleAclPermissionEditor.State.UNSET
            } else {
                MumbleAclPermissionEditor.state(rule.grant, rule.deny, bit)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
            }

            row.addView(label(name, 13f, Color.WHITE),
                LinearLayout.LayoutParams(0, -2, 1f))

            fun checkbox(checked: Boolean): android.widget.CheckBox =
                android.widget.CheckBox(this).apply {
                    isChecked = checked
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        Color.rgb(75, 210, 195)
                    )
                    gravity = android.view.Gravity.CENTER
                    minimumHeight = dp(44)
                    contentDescription = name
                }

            val allow = checkbox(
                currentState == MumbleAclPermissionEditor.State.ALLOW
            )
            val deny = checkbox(
                currentState == MumbleAclPermissionEditor.State.DENY
            )

            allow.setOnCheckedChangeListener { _, checked ->
                if (checked) deny.isChecked = false
            }
            deny.setOnCheckedChangeListener { _, checked ->
                if (checked) allow.isChecked = false
            }

            row.addView(allow, LinearLayout.LayoutParams(dp(76), dp(48)))
            row.addView(deny, LinearLayout.LayoutParams(dp(86), dp(48)))
            permissionSelectors.add(Triple(bit, allow, deny))
            content.addView(row)
        }

        var updateRuleValidity:
            ((se.lublin.humla.protobuf.Mumble.ACL.ChanACL?) -> Unit)? = null

        var selectedDisplayedRule:
            se.lublin.humla.protobuf.Mumble.ACL.ChanACL? = rule
        var onDisplayedRuleChanged: (() -> Unit)? = null

        fun displayRulePermissions(
            selectedRule: se.lublin.humla.protobuf.Mumble.ACL.ChanACL?
        ) {
            selectedDisplayedRule = selectedRule
            onDisplayedRuleChanged?.invoke()
            permissionSelectors.forEach { (bit, allow, deny) ->
                val state = if (selectedRule == null) {
                    MumbleAclPermissionEditor.State.UNSET
                } else {
                    MumbleAclPermissionEditor.state(
                        selectedRule.grant, selectedRule.deny, bit
                    )
                }

                allow.isChecked =
                    state == MumbleAclPermissionEditor.State.ALLOW
                deny.isChecked =
                    state == MumbleAclPermissionEditor.State.DENY
            }
            updateRuleValidity?.invoke(selectedRule)
        }

        val groupRuleInfo = label(
            "Gruppenregel auswählen, um ihre Berechtigungen anzuzeigen.",
            13f, Color.LTGRAY
        )
        val groupRuleSpinner = createMumbleAclSpinner(
            listOf("Keine Gruppenregel ausgewählt"), 0
        )

        var displayedGroupRules =
            emptyList<se.lublin.humla.protobuf.Mumble.ACL.ChanACL>()
        var updatingGroupRuleSelection = false

        lateinit var applyHereCheckbox: android.widget.CheckBox
        lateinit var applySubsCheckbox: android.widget.CheckBox
        var validityFieldsReady = false

        fun refreshPermissionEditability() {
            val editable = !viewingDifferentGroup &&
                rule?.inherited != true

            permissionSelectors.forEach { (_, allow, deny) ->
                allow.isEnabled = editable
                deny.isEnabled = editable
            }
            if (validityFieldsReady) {
                applyHereCheckbox.isEnabled = editable
                applySubsCheckbox.isEnabled = editable
            }
        }

        groupRuleSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (updatingGroupRuleSelection) return

                    val selectedRule =
                        displayedGroupRules.getOrNull(position - 1)

                    if (selectedRule == null) {
                        viewingDifferentGroup = rule != null &&
                            (
                                !rule.hasGroup() ||
                                groupNameInput.text.toString().trim() !=
                                    rule.group
                            )
                        displayRulePermissions(
                            if (viewingDifferentGroup) null else rule
                        )
                        refreshPermissionEditability()
                        return
                    }

                    if (selectedRule != null) {
                        viewingDifferentGroup =
                            rule == null || selectedRule !== rule ||
                                selectedRule.inherited
                        refreshPermissionEditability()
                        displayRulePermissions(selectedRule)
                        groupRuleInfo.text =
                            if (selectedRule.inherited)
                                "Geerbte Regel – nur zur Ansicht"
                            else if (viewingDifferentGroup)
                                "Direkte Regel – nur zur Ansicht"
                            else
                                "Direkte Regel – bearbeitbar"
                    }
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) = Unit
            }

        onGroupSelectionChanged = { groupName, selectedAcl ->
            viewingDifferentGroup = rule != null &&
                (!rule.hasGroup() || rule.group != groupName)
            refreshPermissionEditability()

            displayedGroupRules = MumbleAclRuleEditor.rulesForGroup(
                availableRules, groupName
            )

            val options = listOf("Regel auswählen …") +
                displayedGroupRules.mapIndexed { index, acl ->
                    "$groupName – Regel ${index + 1} – " +
                        if (acl.inherited) "geerbt (nur lesen)"
                        else "direkt"
                }

            updatingGroupRuleSelection = true
            val styledSpinner = createMumbleAclSpinner(options, 0)
            groupRuleSpinner.adapter = styledSpinner.adapter

            val originalRuleIndex =
                displayedGroupRules.indexOfFirst {
                    it === (selectedAcl ?: rule)
                }

            if (originalRuleIndex >= 0) {
                groupRuleSpinner.setSelection(originalRuleIndex + 1)
                val shownRule = displayedGroupRules[originalRuleIndex]
                viewingDifferentGroup =
                    rule == null || shownRule !== rule ||
                        shownRule.inherited
                displayRulePermissions(shownRule)
                refreshPermissionEditability()
            } else if (displayedGroupRules.isNotEmpty()) {
                val selectedRule = displayedGroupRules.first()
                groupRuleSpinner.setSelection(1)
                viewingDifferentGroup = true
                displayRulePermissions(selectedRule)
                refreshPermissionEditability()
            } else {
                viewingDifferentGroup = rule != null
                displayRulePermissions(null)
                refreshPermissionEditability()
            }

            updatingGroupRuleSelection = false

            groupRuleInfo.text =
                "${displayedGroupRules.size} ACL-Regel(n) für $groupName"

            groupRuleSpinner.visibility =
                if (displayedGroupRules.isNotEmpty()) View.VISIBLE
                else View.GONE
        }

        content.removeView(groupRuleInfo)
        content.removeView(groupRuleSpinner)

        val groupInputPosition = content.indexOfChild(groupNameInput)
        content.addView(label(
            "ACL-Regel auswählen", 17f, Color.WHITE, true
        ), groupInputPosition + 1)
        content.addView(groupRuleInfo, groupInputPosition + 2)
        content.addView(groupRuleSpinner, groupInputPosition + 3)

        content.addView(label(
            "Gültigkeit der Regel", 17f, Color.WHITE, true
        ))

        applyHereCheckbox = android.widget.CheckBox(this).apply {
            text = "Gilt in diesem Kanal"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(75, 210, 195)
            )
            isChecked = rule?.applyHere ?: true
            minimumHeight = dp(48)
        }
        content.addView(applyHereCheckbox)

        applySubsCheckbox = android.widget.CheckBox(this).apply {
            text = "Gilt in Unterkanälen"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(75, 210, 195)
            )
            isChecked = rule?.applySubs ?: false
            minimumHeight = dp(48)
        }
        content.addView(applySubsCheckbox)

        updateRuleValidity = { selectedRule ->
            applyHereCheckbox.isChecked = selectedRule?.applyHere ?: true
            applySubsCheckbox.isChecked = selectedRule?.applySubs ?: false

            val editable = !viewingDifferentGroup &&
                rule?.inherited != true

            applyHereCheckbox.isEnabled = editable
            applySubsCheckbox.isEnabled = editable
        }
        validityFieldsReady = true
        updateRuleValidity?.invoke(rule)


        content.addView(label(
            "Die Regel wird zunächst nur lokal vorbereitet. " +
                "Es erfolgt keine Änderung am Mumble-Server.",
            13f, Color.LTGRAY
        ))

        var closeEditorForRuleSwitch: (() -> Unit)? = null

        val editSelectedButton = button(
            "✏️ Diese direkte Regel bearbeiten"
        ) {
            val selected = selectedDisplayedRule
            if (
                selected != null &&
                !selected.inherited &&
                selected !== rule &&
                onEditSelectedRule != null
            ) {
                closeEditorForRuleSwitch?.invoke()
                onEditSelectedRule.invoke(selected)
            }
        }

        fun refreshEditSelectedButton() {
            val selected = selectedDisplayedRule
            editSelectedButton.visibility =
                if (
                    selected != null &&
                    !selected.inherited &&
                    selected !== rule &&
                    onEditSelectedRule != null
                ) View.VISIBLE else View.GONE
        }

        content.addView(
            editSelectedButton,
            content.indexOfChild(groupRuleSpinner) + 1
        )
        onDisplayedRuleChanged = { refreshEditSelectedButton() }
        refreshEditSelectedButton()

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(
                if (rule == null) "Neue ACL-Regel"
                else "ACL-Regel bearbeiten"
            )
            .setView(ScrollView(this).apply {
                setBackgroundColor(bgColor)
                addView(content)
            })
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Lokal übernehmen", null)
            .create()

        if (targetSpinner.selectedItemPosition == 2) {
            val selectedEntry =
                groupEntryTargets.getOrNull(
                    groupSpinner.selectedItemPosition - 1
                )
            onGroupSelectionChanged?.invoke(
                groupNameInput.text.toString().trim(),
                selectedEntry?.second
            )
        }

        closeEditorForRuleSwitch = { dialog.dismiss() }

        dialog.show()
        styleMumbleDialog(dialog)

        dialog.getButton(
            android.app.AlertDialog.BUTTON_POSITIVE
        ).setOnClickListener {
            try {
                if (viewingDifferentGroup || rule?.inherited == true) {
                    throw IllegalArgumentException(
                        "Diese Gruppe wird nur angezeigt. " +
                            "Bitte ihre eigene ACL-Regel zum Bearbeiten öffnen."
                    )
                }

                val base = rule ?:
                    se.lublin.humla.protobuf.Mumble.ACL.ChanACL
                        .newBuilder()
                        .setInherited(false)
                        .build()

                val type = when (targetSpinner.selectedItemPosition) {
                    1 -> MumbleAclRuleEditor.TargetType.USER
                    2 -> MumbleAclRuleEditor.TargetType.GROUP
                    else -> MumbleAclRuleEditor.TargetType.GENERAL
                }

                val userId = if (
                    type == MumbleAclRuleEditor.TargetType.USER
                ) {
                    userIdInput.text.toString().trim().toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "Bitte eine gültige Benutzer-ID eingeben."
                        )
                } else null

                if (
                    rule != null &&
                    rule.hasGroup() &&
                    type == MumbleAclRuleEditor.TargetType.GROUP &&
                    groupNameInput.text.toString().trim() != rule.group
                ) {
                    throw IllegalArgumentException(
                        "Andere Gruppe ausgewählt. Bitte deren eigene " +
                            "ACL-Regel zum Bearbeiten öffnen."
                    )
                }

                if (
                    rule == null &&
                    type == MumbleAclRuleEditor.TargetType.GROUP &&
                    MumbleAclRuleEditor.rulesForGroup(
                        availableRules,
                        groupNameInput.text.toString()
                    ).isNotEmpty()
                ) {
                    throw IllegalArgumentException(
                        "Für diese Gruppe bestehen bereits ACL-Regeln. " +
                            "Bitte die gewünschte Regel direkt bearbeiten. " +
                            "Eine zusätzliche Regel muss ausdrücklich " +
                            "angelegt werden."
                    )
                }

                if (rule != null) {
                    val originalType = MumbleAclRuleEditor.targetType(rule)
                    val targetChanged = originalType != type ||
                        (type == MumbleAclRuleEditor.TargetType.USER &&
                            userId != rule.userId) ||
                        (type == MumbleAclRuleEditor.TargetType.GROUP &&
                            groupNameInput.text.toString().trim() != rule.group)

                    if (targetChanged) {
                        throw IllegalArgumentException(
                            "Das Ziel einer bestehenden ACL-Regel darf " +
                                "hier nicht geändert werden. Bitte eine " +
                                "neue Regel für das gewünschte Ziel anlegen."
                        )
                    }
                }

                val updatedTarget = MumbleAclRuleEditor.updateTarget(
                    base,
                    type,
                    userId,
                    groupNameInput.text.toString()
                )

                val selections = permissionSelectors.associate {
                    (bit, allow, deny) ->
                    val state = when {
                        allow.isChecked -> MumbleAclPermissionEditor.State.ALLOW
                        deny.isChecked -> MumbleAclPermissionEditor.State.DENY
                        else -> MumbleAclPermissionEditor.State.UNSET
                    }
                    bit to state
                }

                val updated = MumbleAclRuleEditor.updatePermissions(
                    updatedTarget, selections
                ).toBuilder()
                    .setApplyHere(applyHereCheckbox.isChecked)
                    .setApplySubs(applySubsCheckbox.isChecked)
                    .build()

                onSave(updated)
                dialog.dismiss()
            } catch (error: IllegalArgumentException) {
                Toast.makeText(
                    this,
                    error.message ?: "Ungültige ACL-Eingabe",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showMumbleAcl() {
        currentPage = "MUMBLE_ACL"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(bg)
        }

        root.addView(label(
            "🔐 Mumble – Kanalrechte",
            18f, Color.WHITE, true
        ))
        var selectedChannelId: Int? = null
        var latestAclSnapshot: MumbleAclManager.AclSnapshot? = null
        var aclDraft: MumbleAclDraft? = null
        var aclDraftConnectionGeneration: Long? = null
        hasPendingMumbleAclChanges = {
            aclDraft?.hasChanges() == true
        }

        val selectedChannel = label(
            "Noch kein Kanal ausgewählt.",
            15f, Color.WHITE
        )

        val aclStatus = label(
            "Bitte zuerst einen Kanal auswählen.",
            14f, muted
        )
        root.addView(aclStatus)

        val groupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val aclGroupsTitle = label(
            "👥 Gruppen und Mitgliedschaften",
            18f, Color.WHITE, true
        )


        val aclRightsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(
                "Berechtigungsregeln werden hier angezeigt.",
                14f, muted
            ))
        }
        val aclPropertiesPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(
                "Kanaleigenschaften werden hier angezeigt.",
                14f, muted
            ))
        }


        var aclChannelButton: android.widget.Button? = null
        val channelButton = button("📁 Kanal auswählen ▾") {
            val tree = mumbleManager?.getChannelTree()

            if (tree == null) {
                android.widget.Toast.makeText(
                    this,
                    "Keine Mumble-Verbindung vorhanden.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                val channels = mutableListOf<Pair<Int, String>>()

                fun collect(
                    item: MumbleConnectionManager.MumbleChannelItem,
                    depth: Int
                ) {
                    channels.add(
                        item.id to ("  ".repeat(depth) + "📁 " + item.name)
                    )
                    item.children.forEach { collect(it, depth + 1) }
                }

                collect(tree, 0)

                android.app.AlertDialog.Builder(this)
                    .setTitle("Mumble-Kanal auswählen")
                    .setItems(channels.map { it.second }.toTypedArray()) {
                        _, index ->
                        val chosen = channels[index]
                        fun applyChannelChange() {
                            selectedChannelId = chosen.first
                            selectedChannel.text = chosen.second.trim()
                        aclChannelButton?.text =
                            chosen.second.trim() + " ▾"
                        latestAclSnapshot = null
                        aclDraft = null
                        aclDraftConnectionGeneration = null
                        groupContainer.removeAllViews()
                        groupContainer.addView(label(
                            "Bitte Kanalrechte abrufen.", 14f, muted
                        ))
                        aclRightsPage.removeAllViews()
                        aclPropertiesPage.removeAllViews()
                        aclRightsPage.addView(label(
                            "Bitte Kanalrechte abrufen.", 14f, muted
                        ))
                        aclPropertiesPage.addView(label(
                            "Bitte Kanalrechte abrufen.", 14f, muted
                        ))
                            aclStatus.text = "Bitte Kanalrechte abrufen."
                        }

                        if (chosen.first == selectedChannelId) {
                            return@setItems
                        }

                        if (aclDraft?.hasChanges() == true) {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Ungespeicherte Änderungen")
                                .setMessage(
                                    "Die lokalen ACL-Änderungen sind noch " +
                                    "nicht gespeichert. Beim Kanalwechsel " +
                                    "werden sie verworfen."
                                )
                                .setNegativeButton(
                                    "Weiter bearbeiten", null
                                )
                                .setPositiveButton(
                                    "Änderungen verwerfen"
                                ) { _, _ ->
                                    applyChannelChange()
                                }
                                .show().also {
                                    styleMumbleDialog(it)
                                }
                        } else {
                            applyChannelChange()
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show().also { styleMumbleDialog(it) }
            }
        }


        aclChannelButton = channelButton
        root.addView(channelButton)

        val aclActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val aclRefreshButton = button("🔄 Abrufen") {
            val channelId = selectedChannelId

            if (channelId == null) {
                aclStatus.text = "⚠️ Bitte zuerst einen Kanal auswählen."
            } else {
                aclStatus.text = "Kanalrechte werden angefordert …"

                mumbleManager?.requestAclUserNames()
                val sent = mumbleManager?.requestMumbleAcl(channelId) == true

                if (!sent) {
                    aclStatus.text =
                        "⚠️ ACL-Abfrage nicht möglich. Mumble-Verbindung prüfen."
                }
            }
        }


        aclActionRow.addView(
            aclRefreshButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        aclActionRow.addView(
            button("← Zurück") {
                if (aclDraft?.hasChanges() == true) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Ungespeicherte Änderungen")
                        .setMessage(
                            "Es gibt lokale ACL-Änderungen, die noch " +
                            "nicht auf dem Server gespeichert wurden. " +
                            "Möchtest du diese verwerfen?"
                        )
                        .setNegativeButton(
                            "Weiter bearbeiten", null
                        )
                        .setPositiveButton(
                            "Änderungen verwerfen"
                        ) { _, _ ->
                            aclDraft = null
                            showMumbleAdmin()
                        }
                        .show().also {
                            styleMumbleDialog(it)
                        }
                } else {
                    showMumbleAdmin()
                }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        root.addView(aclActionRow)

        val aclCheckButton = button("💾 Änderungen prüfen") {
            val draft = aclDraft

            if (draft == null || !draft.hasChanges()) {
                aclStatus.text = "Keine lokalen ACL-Änderungen vorhanden."
            } else {
                val manager = mumbleManager
                val generation = manager
                    ?.getModerationConnectionGeneration()

                val safety = MumbleAclChangeSafety.check(
                    MumbleAclChangeSafety.Context(
                        connected = manager?.isConnected() == true,
                        connectionGeneration = generation ?: -1L,
                        expectedGeneration =
                            aclDraftConnectionGeneration ?: -2L,
                        selectedChannelId = selectedChannelId,
                        requestedChannelId = draft.channelId,
                        serverSnapshot = latestAclSnapshot,
                        draft = draft
                    )
                )

                val summary = MumbleAclChangeSummary.summarize(draft)

                val details = buildString {
                    if (safety.validForReview) {
                        appendLine("✅ Lokale Sicherheitsprüfung bestanden.")
                        appendLine(
                            "Der Entwurf kann weiter geprüft werden."
                        )
                        appendLine("Keine Schreibfreigabe.")
                    } else {
                        appendLine("⚠️ Sicherheitsprüfung fehlgeschlagen.")
                        appendLine(
                            "Der ACL-Entwurf ist nicht zur weiteren " +
                            "Prüfung geeignet."
                        )
                        appendLine()
                        safety.reasons.forEach { reason ->
                            appendLine("• $reason")
                        }
                    }

                    appendLine()
                    appendLine("Geplante Änderungen:")
                    appendLine()
                    appendLine("Neue Gruppen: ${summary.addedGroups.size}")
                    appendLine("Entfernte Gruppen: ${summary.removedGroups.size}")
                    appendLine("Geänderte Gruppen: ${summary.modifiedGroups.size}")

                    if (summary.addedGroups.isNotEmpty()) {
                        appendLine()
                        appendLine("Hinzugefügte Gruppen:")
                        summary.addedGroups.forEach {
                            appendLine("  + $it")
                        }
                    }

                    if (summary.removedGroups.isNotEmpty()) {
                        appendLine()
                        appendLine("Entfernte Gruppen:")
                        summary.removedGroups.forEach {
                            appendLine("  - $it")
                        }
                    }

                    if (summary.modifiedGroups.isNotEmpty()) {
                        appendLine()
                        appendLine("Geänderte Gruppen:")
                        summary.modifiedGroups.forEach {
                            appendLine("  • $it")
                        }
                    }

                    appendLine()
                    appendLine("Neue Regelvorkommen: ${summary.addedRules}")
                    appendLine("Nicht mehr vorhandene Regelvorkommen: ${summary.removedRules}")
                    appendLine("Bearbeitungen: nicht eindeutig bestimmbar")

                    if (summary.rulesReordered) {
                        appendLine()
                        appendLine(
                            "🔀 Reihenfolge der Berechtigungsregeln geändert."
                        )
                        appendLine(
                            "Die Reihenfolge vorhandener Regeln wurde " +
                            "verändert. Zusätzlich können Regeln " +
                            "hinzugefügt oder entfernt worden sein."
                        )
                    }

                    val originalRules =
                        draft.original.rules.filterNot { it.inherited }
                    val plannedRules =
                        MumbleAclPreparation.localRules(draft)

                    if (originalRules != plannedRules) {
                        appendLine()
                        appendLine("Geplante lokale Regelreihenfolge:")

                        plannedRules.forEachIndexed { index, rule ->
                            val target = when {
                                rule.hasUserId() ->
                                    "Benutzer-ID ${rule.userId}"
                                rule.hasGroup() ->
                                    "Gruppe: ${rule.group}"
                                else -> "Allgemeine Regel"
                            }

                            val allowed =
                                MumbleAclManager.permissionNames(rule.grant)
                            val denied =
                                MumbleAclManager.permissionNames(rule.deny)

                            appendLine()
                            appendLine("${index + 1}. $target")
                            appendLine(
                                "  Erlaubt: " +
                                allowed.ifEmpty { listOf("Keine") }
                                    .joinToString(", ")
                            )
                            appendLine(
                                "  Verweigert: " +
                                denied.ifEmpty { listOf("Keine") }
                                    .joinToString(", ")
                            )
                            appendLine(
                                "  Dieser Kanal: " +
                                if (rule.applyHere) "Ja" else "Nein"
                            )
                            appendLine(
                                "  Unterkanäle: " +
                                if (rule.applySubs) "Ja" else "Nein"
                            )
                        }

                        appendLine()
                        appendLine(
                            "Hinweis: Die Reihenfolge ist verbindlich. " +
                            "Regelvorkommen werden unabhängig von ihrer Position verglichen."
                        )
                    }

                    appendLine(
                        "Vererbung geändert: " +
                        if (summary.inheritanceChanged) "Ja" else "Nein"
                    )



                    appendLine()
                    append(
                        "Nur Vorschau. Es werden keine Änderungen " +
                        "an den Mumble-Server gesendet."
                    )
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("🔐 ACL-Änderungen prüfen")
                    .setMessage(details)
                    .setPositiveButton("Schließen", null)
                    .show().also { styleMumbleDialog(it) }
            }
        }

        root.addView(aclCheckButton)

        // ACL-Reiter: Gruppen, Berechtigungen und Eigenschaften.
        val aclTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(bg)
        }

        val aclGroupPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val aclPages = listOf(
            aclGroupPage, aclRightsPage, aclPropertiesPage
        )
        val aclTabButtons = mutableListOf<android.widget.Button>()

        fun selectAclTab(index: Int) {
            aclPages.forEachIndexed { pageIndex, view ->
                view.visibility =
                    if (pageIndex == index)
                        android.view.View.VISIBLE
                    else
                        android.view.View.GONE
            }
            aclTabButtons.forEachIndexed { tabIndex, button ->
                button.setTextColor(
                    if (tabIndex == index) accent else Color.WHITE
                )
            }
        }

        listOf("Gruppen", "Rechte", "Info").forEachIndexed {
                index, title ->
            val tab = button(title) {
                selectAclTab(index)
            }.apply {
                textSize = 12f
                setPadding(dp(4), 0, dp(4), 0)
            }
            aclTabButtons.add(tab)
            aclTabs.addView(
                tab,
                LinearLayout.LayoutParams(0, dp(44), 1f)
            )
        }

        aclGroupPage.addView(aclGroupsTitle)
        aclGroupPage.addView(groupContainer)
        root.addView(aclTabs)
        root.addView(aclGroupPage)
        root.addView(aclRightsPage)
        root.addView(aclPropertiesPage)
        selectAclTab(0)

        fun resetAclPages() {
            groupContainer.removeAllViews()
            groupContainer.addView(label(
                "Bitte Kanalrechte abrufen.", 14f, muted
            ))
            aclRightsPage.removeAllViews()
            aclRightsPage.addView(label(
                "Bitte Kanalrechte abrufen.", 14f, muted
            ))
            aclPropertiesPage.removeAllViews()
            aclPropertiesPage.addView(label(
                "Bitte Kanalrechte abrufen.", 14f, muted
            ))
        }

        fun renderAclInfo(snapshot: MumbleAclManager.AclSnapshot) {
            aclPropertiesPage.removeAllViews()

            aclPropertiesPage.addView(label(
                "ℹ️ Kanalinformationen",
                18f, Color.WHITE, true
            ))

            val inheritedRules = snapshot.rules.count { it.inherited }
            val localRules = snapshot.rules.size - inheritedRules
            val inheritedGroups = snapshot.groups.count { it.inherited }

            val info = listOf(
                "Kanal" to selectedChannel.text.toString(),
                "Kanal-ID" to snapshot.channelId.toString(),
                "Rechte vom übergeordneten Kanal übernehmen" to
                    if (snapshot.inheritAcls) "Ja" else "Nein",
                "Gruppen insgesamt" to snapshot.groups.size.toString(),
                "Davon geerbte Gruppen" to inheritedGroups.toString(),
                "Berechtigungsregeln insgesamt" to
                    snapshot.rules.size.toString(),
                "Eigene Berechtigungsregeln" to localRules.toString(),
                "Geerbte Berechtigungsregeln" to
                    inheritedRules.toString()
            )

            info.forEach { (name, value) ->
                val entry = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(panel)
                }
                entry.addView(label(name, 13f, muted))
                entry.addView(label(value, 16f, Color.WHITE, true))
                aclPropertiesPage.addView(
                    entry,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(8)
                    }
                )
            }

            aclPropertiesPage.addView(label(
                "🔒 Nur lesender Zugriff. Es werden keine " +
                    "Serverrechte oder Gruppen verändert.",
                14f, muted
            ))
        }

        fun renderAclRights(snapshot: MumbleAclManager.AclSnapshot) {
            aclRightsPage.removeAllViews()

            val displayedRules =
                aclDraft?.takeIf { it.channelId == snapshot.channelId }
                    ?.getRules() ?: snapshot.rules

            aclRightsPage.addView(label(
                "🛡️ Berechtigungsregeln (${displayedRules.size})",
                18f, Color.WHITE, true
            ))

            aclRightsPage.addView(button("➕ Neue Regel") {
                val draft = aclDraft
                if (draft == null || draft.channelId != snapshot.channelId) {
                    aclStatus.text =
                        "⚠️ Kein gültiger lokaler ACL-Entwurf vorhanden."
                } else {
                    val expectedDraft = draft
                    showMumbleAclRuleEditor(null, (
                        aclDraft?.takeIf {
                            it.channelId == snapshot.channelId
                        }?.getGroups() ?: snapshot.groups
                    ).map { it.name }.filter { it.isNotBlank() }.distinct(), (
                        aclDraft?.takeIf {
                            it.channelId == snapshot.channelId
                        }?.getRules() ?: snapshot.rules
                    )) { newRule ->
                        val currentDraft = aclDraft
                        if (
                            currentDraft !== expectedDraft ||
                            currentDraft.channelId != snapshot.channelId
                        ) {
                            aclStatus.text =
                                "⚠️ ACL-Daten haben sich geändert. " +
                                "Bitte den Editor erneut öffnen."
                        } else if (currentDraft.addRule(newRule)) {
                            renderAclRights(snapshot)
                            aclStatus.text =
                                "✏️ Neue ACL-Regel lokal hinzugefügt. " +
                                "Noch nicht auf dem Server gespeichert."
                        }
                    }
                }
            })

            if (displayedRules.isEmpty()) {
                aclRightsPage.addView(label(
                    "Keine Berechtigungsregeln vorhanden.",
                    14f, muted
                ))
            }

            displayedRules.forEachIndexed { index, rule ->
                val target = when {
                    rule.hasUserId() -> {
                        val name = mumbleManager
                            ?.getRegisteredUserName(rule.userId)
                        if (name.isNullOrBlank())
                            "Benutzer-ID ${rule.userId}"
                        else
                            "$name (ID ${rule.userId})"
                    }
                    rule.hasGroup() -> "Gruppe: ${rule.group}"
                    else -> "Allgemeine Regel"
                }

                val entry = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(panel)
                    isClickable = true
                    isFocusable = true
                }

                val description = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val ruleTextColor =
                    if (rule.inherited) Color.rgb(155, 165, 177)
                    else Color.WHITE

                description.addView(label(
                    "${index + 1}. $target" +
                        if (rule.inherited) " · 🔒 Geerbt · Nur lesen" else "",
                    15f, ruleTextColor, true
                ))
                description.addView(label(
                    "Erlaubt: ${MumbleAclManager.permissionNames(rule.grant).size}" +
                        " · Verweigert: ${MumbleAclManager.permissionNames(rule.deny).size}",
                    13f,
                    if (rule.inherited) Color.rgb(135, 145, 157) else muted
                ))

                entry.addView(
                    description,
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                entry.addView(label("›", 24f, accent, true))

                entry.setOnClickListener {
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(12), dp(18), dp(12))
                        setBackgroundColor(bg)
                    }

                    fun addSection(title: String, mask: Int) {
                        details.addView(label(
                            title, 16f, accent, true
                        ))
                        val names = MumbleAclManager.permissionNames(mask)
                        details.addView(label(
                            if (names.isEmpty()) "Keine"
                            else names.joinToString("\n") { "• $it" },
                            14f, Color.WHITE
                        ))
                    }

                    details.addView(label(
                        target, 16f, Color.WHITE, true
                    ))
                    addSection("Erlaubte Rechte", rule.grant)
                    addSection("Verweigerte Rechte", rule.deny)

                    details.addView(label(
                        "Gilt in diesem Kanal: " +
                            if (rule.applyHere) "Ja" else "Nein",
                        14f, muted
                    ))
                    details.addView(label(
                        "Gilt in Unterkanälen: " +
                            if (rule.applySubs) "Ja" else "Nein",
                        14f, muted
                    ))
                    details.addView(label(
                        "Geerbte Regel: " +
                            if (rule.inherited) "Ja" else "Nein",
                        14f, muted
                    ))

                    var ruleDialog: android.app.AlertDialog? = null

                    if (!rule.inherited) {
                        val actions = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                        }

                        actions.addView(button("✏️ Regel bearbeiten") {
                            val draft = aclDraft
                            if (
                                draft == null ||
                                draft.channelId != snapshot.channelId ||
                                draft.getRules().getOrNull(index) !== rule
                            ) {
                                ruleDialog?.dismiss()
                                aclStatus.text =
                                    "⚠️ Die Regel hat sich verändert. " +
                                    "Bitte erneut öffnen."
                            } else {
                                val expectedDraft = draft

                                fun openDirectRuleEditor(
                                    selectedRule: se.lublin.humla.protobuf.Mumble.ACL.ChanACL
                                ) {
                                    val currentDraft = aclDraft
                                    val selectedIndex =
                                        currentDraft?.getRules()?.indexOfFirst {
                                            it === selectedRule
                                        } ?: -1

                                    if (
                                        currentDraft !== expectedDraft ||
                                        currentDraft.channelId != snapshot.channelId ||
                                        selectedRule.inherited ||
                                        selectedIndex < 0
                                    ) {
                                        aclStatus.text =
                                            "⚠️ Die ausgewählte Regel ist nicht mehr " +
                                            "gültig. Bitte erneut öffnen."
                                        return
                                    }

                                    showMumbleAclRuleEditor(
                                        selectedRule,
                                        currentDraft.getGroups()
                                            .map { it.name }
                                            .filter { it.isNotBlank() }
                                            .distinct(),
                                        currentDraft.getRules(),
                                        onEditSelectedRule = { nextRule ->
                                            openDirectRuleEditor(nextRule)
                                        }
                                    ) { updated ->
                                        val latestDraft = aclDraft
                                        if (
                                            latestDraft !== expectedDraft ||
                                            latestDraft.channelId != snapshot.channelId ||
                                            latestDraft.getRules()
                                                .getOrNull(selectedIndex) !== selectedRule
                                        ) {
                                            aclStatus.text =
                                                "⚠️ Die Regel wurde zwischenzeitlich " +
                                                "verändert. Bitte erneut öffnen."
                                        } else if (
                                            latestDraft.updateRule(
                                                selectedIndex, updated
                                            )
                                        ) {
                                            ruleDialog?.dismiss()
                                            renderAclRights(snapshot)
                                            aclStatus.text =
                                                "✏️ ACL-Regel lokal bearbeitet. " +
                                                "Noch nicht auf dem Server gespeichert."
                                        }
                                    }
                                }

                                openDirectRuleEditor(rule)
                            }
                        })

                        fun moveRule(direction: Int) {
                            val draft = aclDraft ?: return
                            if (draft.channelId != snapshot.channelId) return
                            if (draft.getRules().getOrNull(index) !== rule) {
                                aclStatus.text =
                                    "⚠️ Die Regelliste wurde verändert. " +
                                    "Bitte die Regel erneut öffnen."
                                ruleDialog?.dismiss()
                                return
                            }

                            if (draft.moveRule(index, direction)) {
                                ruleDialog?.dismiss()
                                renderAclRights(snapshot)
                                aclStatus.text =
                                    "✏️ Reihenfolge lokal geändert. " +
                                    "Noch nicht auf dem Server gespeichert."
                            }
                        }

                        val moveRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                        }

                        moveRow.addView(
                            button("↑ Nach oben") { moveRule(-1) },
                            LinearLayout.LayoutParams(0, dp(48), 1f)
                        )
                        moveRow.addView(
                            button("↓ Nach unten") { moveRule(1) },
                            LinearLayout.LayoutParams(0, dp(48), 1f)
                        )
                        actions.addView(moveRow)

                        actions.addView(button("🗑️ Regel löschen") {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("ACL-Regel löschen?")
                                .setMessage(
                                    "Diese Berechtigungsregel wird nur " +
                                    "aus dem lokalen Entwurf entfernt. " +
                                    "Der Mumble-Server bleibt unverändert."
                                )
                                .setNegativeButton("Abbrechen", null)
                                .setPositiveButton("Lokal löschen") { _, _ ->
                                    val draft = aclDraft
                                    if (
                                        draft != null &&
                                        draft.channelId == snapshot.channelId &&
                                        draft.getRules().getOrNull(index) === rule &&
                                        draft.removeRule(index)
                                    ) {
                                        ruleDialog?.dismiss()
                                        renderAclRights(snapshot)
                                        aclStatus.text =
                                            "✏️ Regel lokal entfernt. " +
                                            "Noch nicht auf dem Server gespeichert."
                                    }
                                }
                                .show().also { styleMumbleDialog(it) }
                        })

                        details.addView(actions)
                    }

                    val dialog = android.app.AlertDialog.Builder(this)
                        .setTitle("🛡️ ACL-Regel ${index + 1}")
                        .setView(ScrollView(this).apply {
                            setBackgroundColor(bg)
                            addView(details)
                        })
                        .setPositiveButton("Schließen", null)
                        .create()

                    ruleDialog = dialog
                    dialog.show()
                    styleMumbleDialog(dialog)
                }

                aclRightsPage.addView(
                    entry,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(8)
                    }
                )
            }
        }

        fun renderAclGroups(snapshot: MumbleAclManager.AclSnapshot) {
            groupContainer.removeAllViews()

            groupContainer.addView(label(
                if (snapshot.inheritAcls)
                    "Kanalrechte werden vom übergeordneten Kanal geerbt."
                else
                    "Vererbung übergeordneter Kanalrechte deaktiviert.",
                14f, muted
            ))

            if (snapshot.groups.isEmpty()) {
                groupContainer.addView(label(
                    "Keine Gruppen vom Server geliefert.",
                    14f, muted
                ))
            }

            fun members(values: List<Int>): String =
                if (values.isEmpty()) "Keine"
                else values.joinToString("\n") { userId ->
                    val name = mumbleManager
                        ?.getRegisteredUserName(userId)
                        ?.takeIf { it.isNotBlank() }
                    if (name != null) "$name (ID $userId)"
                    else "ID $userId"
                }

            val displayedGroups =
                aclDraft?.takeIf { it.channelId == snapshot.channelId }
                    ?.getGroups() ?: snapshot.groups

            displayedGroups.forEachIndexed { groupIndex, group ->
                val originalGroup = snapshot.groups.getOrNull(groupIndex)
                val locallyChanged =
                    originalGroup != null && originalGroup != group

                val directCount = group.addList.size
                val inheritedCount = group.inheritedMembersList.size
                val removedCount = group.removeList.size

                val entry = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(panel)
                    isClickable = true
                    isFocusable = true
                }

                val description = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }

                description.addView(label(
                    "👥 ${group.name}" +
                        (if (group.inherited) " · Geerbt" else "") +
                        (if (locallyChanged) " · Nicht gespeichert" else ""),
                    16f, Color.WHITE, true
                ))

                description.addView(label(
                    "$directCount direkt · $inheritedCount geerbt" +
                        if (removedCount > 0)
                            " · $removedCount entfernt"
                        else "",
                    13f, muted
                ))

                entry.addView(
                    description,
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                entry.addView(label("›", 24f, accent, true))

                entry.setOnClickListener {
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(12), dp(18), dp(12))
                        setBackgroundColor(bg)
                    }

                    fun section(title: String, values: List<Int>) {
                        details.addView(label(
                            "$title (${values.size})",
                            16f, accent, true
                        ))
                        details.addView(label(
                            members(values), 14f, Color.WHITE
                        ))
                    }

                    section("Direkte Mitglieder", group.addList)
                    section("Entfernte Mitglieder", group.removeList)
                    section(
                        "Geerbte Mitglieder",
                        group.inheritedMembersList
                    )

                    details.addView(label(
                        "Übernimmt Mitglieder: " +
                            if (group.inherit) "Ja" else "Nein",
                        14f, muted
                    ))
                    details.addView(label(
                        "An Unterkanäle vererbbar: " +
                            if (group.inheritable) "Ja" else "Nein",
                        14f, muted
                    ))

                    val dialog = android.app.AlertDialog.Builder(this)
                        .setTitle("👥 ${group.name}")
                        .setNeutralButton("Bearbeiten") { _, _ ->
                            val users = mumbleManager
                                ?.getAclRegisteredUsers()
                                .orEmpty()

                            if (users.isEmpty()) {
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("Keine Benutzerliste")
                                    .setMessage(
                                        "Der Server hat noch keine Liste " +
                                        "registrierter Benutzer geliefert."
                                    )
                                    .setPositiveButton("OK", null)
                                    .show().also { styleMumbleDialog(it) }
                            } else {
                                val original = group.addList.toSet()
                                val selected = original.toMutableSet()

                                // Ausgeschlossene Mitglieder getrennt verwalten.
                                val originalExcluded =
                                    if (group.inherited) {
                                        aclDraft
                                            ?.takeIf {
                                                it.channelId == snapshot.channelId
                                            }
                                            ?.getLocalExclusionsForInheritedGroup(
                                                groupIndex
                                            )
                                            ?: group.removeList.toSet()
                                    } else {
                                        group.removeList.toSet()
                                    }
                                val selectedExcluded =
                                    originalExcluded.toMutableSet()

                                val localGroupForInheritance =
                                    if (group.inherited) {
                                        aclDraft
                                            ?.takeIf {
                                                it.channelId == snapshot.channelId
                                            }
                                            ?.getGroups()
                                            ?.firstOrNull {
                                                !it.inherited &&
                                                    it.name == group.name
                                            }
                                    } else {
                                        group
                                    }

                                val initialInheritable =
                                    localGroupForInheritance?.inheritable ?: false

                                val editor = LinearLayout(this).apply {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(dp(16), dp(8), dp(16), dp(8))
                                    setBackgroundColor(bg)
                                }

                                val search = android.widget.EditText(this).apply {
                                    hint = "Benutzername oder ID suchen"
                                    setSingleLine(true)
                                    setTextColor(Color.WHITE)
                                    setHintTextColor(muted)
                                    backgroundTintList =
                                        android.content.res.ColorStateList.valueOf(accent)
                                }
                                editor.addView(search)
                                // Auswahl zwischen direkten und
                                // ausgeschlossenen Mitgliedern.
                                var editingExcluded = false

                                val memberModeRow = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    setPadding(0, dp(8), 0, dp(8))
                                }

                                var refreshMemberList: (() -> Unit)? = null

                                val directModeButton =
                                    button("Direkte ✓") {
                                        editingExcluded = false
                                        refreshMemberList?.invoke()
                                    }

                                val excludedModeButton =
                                    button("Ausschlüsse") {
                                        editingExcluded = true
                                        refreshMemberList?.invoke()
                                    }

                                fun updateMemberModeButtons() {
                                    directModeButton.text =
                                        if (editingExcluded)
                                            "Direkte"
                                        else
                                            "Direkte ✓"

                                    excludedModeButton.text =
                                        if (editingExcluded)
                                            "Ausschlüsse ✓"
                                        else
                                            "Ausschlüsse"
                                }

                                memberModeRow.addView(
                                    directModeButton,
                                    LinearLayout.LayoutParams(0, dp(48), 1f)
                                )
                                memberModeRow.addView(
                                    excludedModeButton,
                                    LinearLayout.LayoutParams(0, dp(48), 1f)
                                )

                                editor.addView(memberModeRow)

                                val inheritanceSwitch =
                                    android.widget.CheckBox(this).apply {
                                        text = "An Unterkanäle vererben"
                                        setTextColor(Color.WHITE)
                                        textSize = 15f
                                        isChecked = initialInheritable
                                        buttonTintList =
                                            android.content.res.ColorStateList.valueOf(
                                                accent
                                            )
                                        setPadding(0, dp(8), 0, dp(8))
                                    }

                                editor.addView(inheritanceSwitch)

                                val resultCount = TextView(this).apply {
                                    setTextColor(muted)
                                    textSize = 13f
                                    setPadding(0, dp(8), 0, dp(4))
                                }
                                editor.addView(resultCount)

                                val list = LinearLayout(this).apply {
                                    orientation = LinearLayout.VERTICAL
                                }

                                editor.addView(ScrollView(this).apply {
                                    addView(list)
                                }, LinearLayout.LayoutParams(
                                    -1, 0, 1f
                                ))

                                fun refreshUsers(filter: String) {
                                    list.removeAllViews()
                                    val matches = users.filter { (id, name) ->
                                        name.contains(filter, ignoreCase = true) ||
                                            id.toString().contains(filter)
                                    }
                                    resultCount.text =
                                        "${matches.size} von ${users.size} Benutzern"

                                    matches.forEach { (id, name) ->
                                        val inherited =
                                            id in group.inheritedMembersList
                                        val check = android.widget.CheckBox(this).apply {
                                            text = "$name (ID $id)" + when {
                                                inherited && id in selectedExcluded ->
                                                    " · geerbt · lokal ausgeschlossen"
                                                inherited -> " · geerbt"
                                                else -> ""
                                            }
                                            setTextColor(Color.WHITE)
                                            textSize = 15f
                                            buttonTintList =
                                                android.content.res.ColorStateList.valueOf(
                                                    accent
                                                )
                                            isChecked = if (editingExcluded) {
                                                id in selectedExcluded
                                            } else {
                                                id in selected || inherited
                                            }
                                            isEnabled =
                                                editingExcluded || !inherited
                                            setPadding(0, dp(6), 0, dp(6))
                                            setOnCheckedChangeListener { _, enabled ->
                                                val target = if (editingExcluded) {
                                                    selectedExcluded
                                                } else {
                                                    selected
                                                }
                                                val opposite = if (editingExcluded) {
                                                    selected
                                                } else {
                                                    selectedExcluded
                                                }
                                                if (enabled && id in opposite) {
                                                    isChecked = false
                                                    android.widget.Toast.makeText(
                                                        this@MainActivity,
                                                        "Benutzer ist bereits in der anderen Liste.",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                } else if (enabled) {
                                                    target.add(id)
                                                } else {
                                                    target.remove(id)
                                                }
                                            }
                                        }
                                        list.addView(check)
                                    }
                                }

                                refreshMemberList = {
                                    updateMemberModeButtons()
                                    refreshUsers(search.text.toString())
                                }
                                refreshUsers("")

                                search.addTextChangedListener(
                                    object : android.text.TextWatcher {
                                        override fun beforeTextChanged(
                                            s: CharSequence?, start: Int,
                                            count: Int, after: Int
                                        ) = Unit

                                        override fun onTextChanged(
                                            s: CharSequence?, start: Int,
                                            before: Int, count: Int
                                        ) {
                                            refreshUsers(s?.toString().orEmpty())
                                        }

                                        override fun afterTextChanged(
                                            s: android.text.Editable?
                                        ) = Unit
                                    }
                                )

                                android.app.AlertDialog.Builder(this)
                                    .setTitle("Mitglieder: ${group.name}")
                                    .setView(editor)
                                    .setNegativeButton("Abbrechen", null)
                                    .setPositiveButton("Änderungen prüfen") {
                                        _, _ ->
                                        val added = selected - original
                                        val removed = original - selected
                                        val excludedAdded =
                                            selectedExcluded - originalExcluded
                                        val excludedRemoved =
                                            originalExcluded - selectedExcluded

                                        val summary = buildString {
                                            appendLine(
                                                "Nur lokale Vorschau – " +
                                                "keine Serveränderung."
                                            )
                                            appendLine()
                                            val oldInheritance =
                                                if (initialInheritable) "Ja" else "Nein"
                                            val newInheritance =
                                                if (inheritanceSwitch.isChecked) "Ja" else "Nein"
                                            appendLine(
                                                "An Unterkanäle vererben: " +
                                                if (initialInheritable != inheritanceSwitch.isChecked) {
                                                    "$oldInheritance → $newInheritance"
                                                } else {
                                                    "$oldInheritance (unverändert)"
                                                }
                                            )
                                            appendLine()
                                            appendLine(
                                                "Hinzufügen: ${added.size}"
                                            )
                                            added.forEach { id ->
                                                appendLine(
                                                    "+ " +
                                                    (mumbleManager
                                                        ?.getRegisteredUserName(id)
                                                        ?: "ID $id")
                                                )
                                            }
                                            appendLine()
                                            appendLine(
                                                "Entfernen: ${removed.size}"
                                            )
                                            removed.forEach { id ->
                                                appendLine(
                                                    "- " +
                                                    (mumbleManager
                                                        ?.getRegisteredUserName(id)
                                                        ?: "ID $id")
                                                )
                                            }

                                            appendLine()
                                            appendLine(
                                                "Neu ausgeschlossen: ${excludedAdded.size}"
                                            )
                                            excludedAdded.forEach { id ->
                                                appendLine(
                                                    "⊖ " +
                                                    (mumbleManager
                                                        ?.getRegisteredUserName(id)
                                                        ?: "ID $id")
                                                )
                                            }

                                            appendLine()
                                            appendLine(
                                                "Ausschluss aufgehoben: ${excludedRemoved.size}"
                                            )
                                            excludedRemoved.forEach { id ->
                                                appendLine(
                                                    "⊕ " +
                                                    (mumbleManager
                                                        ?.getRegisteredUserName(id)
                                                        ?: "ID $id")
                                                )
                                            }
                                        }

                                        android.app.AlertDialog.Builder(this)
                                            .setTitle("Änderungsübersicht")
                                            .setMessage(summary)
                                            .setNegativeButton(
                                                "Zurück", null
                                            )
                                            .setPositiveButton(
                                                "Lokal übernehmen"
                                            ) { _, _ ->
                                                val draft = aclDraft
                                                if (
                                                    currentPage != "MUMBLE_ACL" ||
                                                    selectedChannelId != snapshot.channelId ||
                                                    draft == null ||
                                                    draft.channelId != snapshot.channelId
                                                ) {
                                                    android.widget.Toast.makeText(
                                                        this,
                                                        "ACL-Entwurf nicht mehr gültig.",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    val accepted =
                                                        if (group.inherited) {
                                                            if (
                                                                selected != original ||
                                                                (
                                                                    selectedExcluded.isEmpty() &&
                                                                    localGroupForInheritance == null
                                                                )
                                                            ) {
                                                                false
                                                            } else {
                                                                draft.updateInheritedGroupExclusions(
                                                                    groupIndex,
                                                                    selectedExcluded,
                                                                    inheritanceSwitch.isChecked
                                                                )
                                                            }
                                                        } else {
                                                            draft.updateGroupMembers(
                                                                groupIndex,
                                                                selected,
                                                                selectedExcluded,
                                                                inheritanceSwitch.isChecked
                                                            )
                                                        }
                                                    if (accepted) {
                                                        renderAclGroups(snapshot)
                                                        aclStatus.text =
                                                            "✏️ Lokale Änderungen vorgemerkt. " +
                                                            "Noch nicht auf dem Server gespeichert."
                                                    } else {
                                                        android.widget.Toast.makeText(
                                                            this,
                                                            "Mitglieder konnten nicht übernommen werden.",
                                                            android.widget.Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            }
                                            .show().also {
                                                styleMumbleDialog(it)
                                            }
                                    }
                                    .show().also {
                                        styleMumbleDialog(it)
                                    }
                            }
                        }
                        .setView(ScrollView(this).apply {
                            setBackgroundColor(bg)
                            addView(details)
                        })
                        .setPositiveButton("Schließen", null)
                        .create()

                    dialog.setOnShowListener {
                        dialog.window?.setBackgroundDrawableResource(
                            android.R.color.transparent
                        )
                        dialog.window?.decorView?.setBackgroundColor(bg)
                        dialog.getButton(
                            android.app.AlertDialog.BUTTON_POSITIVE
                        )?.setTextColor(accent)
                        dialog.findViewById<android.widget.TextView>(
                            resources.getIdentifier(
                                "alertTitle", "id", "android"
                            )
                        )?.setTextColor(Color.WHITE)
                    }
                    dialog.show()
                }

                groupContainer.addView(
                    entry,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(8)
                    }
                )
            }
        }



        mumbleManager?.onMumbleAclReceived = { snapshot ->
            runOnUiThread {
                if (
                    currentPage == "MUMBLE_ACL" &&
                    selectedChannelId == snapshot.channelId
                ) {
                    aclStatus.text =
                        "✅ Kanal ${snapshot.channelId}: " +
                        "${snapshot.groups.size} Gruppen, " +
                        "${snapshot.rules.size} Berechtigungsregeln empfangen."
                    latestAclSnapshot = snapshot
                    val existingDraft = aclDraft
                    if (
                        existingDraft == null ||
                        existingDraft.channelId != snapshot.channelId ||
                        !existingDraft.hasChanges()
                    ) {
                        aclDraft = MumbleAclDraft(snapshot)
                        aclDraftConnectionGeneration =
                            mumbleManager?.getModerationConnectionGeneration()
                    } else {
                        val unchanged =
                            MumbleAclPreparation.isServerStateUnchanged(
                                existingDraft.original,
                                snapshot
                            )

                        aclStatus.text = if (unchanged) {
                            "ℹ️ Empfangene ACL-Daten stimmen mit dem " +
                            "ursprünglichen Stand überein. " +
                            "Lokale Änderungen bleiben erhalten. " +
                            "Aktualität nicht garantiert."
                        } else {
                            "⚠️ ACL-Konflikt: Empfangene Serverdaten " +
                            "weichen vom ursprünglichen Stand ab. " +
                            "Lokale Änderungen bleiben erhalten. " +
                            "Keine Schreibfreigabe."
                        }
                    }
                    renderAclGroups(snapshot)
                    renderAclRights(snapshot)
                    renderAclInfo(snapshot)
                }
            }
        }

        // Gemeinsame Benutzerliste aktualisiert die ACL-Namensanzeige.
        mumbleManager?.onRegisteredUsersReceived = { _ ->
            runOnUiThread {
                if (currentPage == "MUMBLE_ACL") {
                    latestAclSnapshot?.let { snapshot ->
                        if (selectedChannelId == snapshot.channelId) {
                            renderAclGroups(snapshot)
                            renderAclRights(snapshot)
                        }
                    }
                }
            }
        }

        mumbleManager?.onMumbleAclConnectionLost = {
            runOnUiThread {
                if (currentPage == "MUMBLE_ACL") {
                    selectedChannelId = null
                    latestAclSnapshot = null
                    aclDraft = null
                    aclDraftConnectionGeneration = null
                    selectedChannel.text = "Keine Verbindung."
                    aclChannelButton?.text = "📁 Kanal auswählen ▾"
                    resetAclPages()
                    aclStatus.text = "⚠️ Mumble-Verbindung getrennt."
                }
            }
        }

        // Fester Kopfbereich mit Kanalauswahl und ACL-Steuerung.
        val fixedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Titel, Kanalauswahl und Abruf bleiben fest sichtbar.
        // Gruppenüberschrift, Gruppenliste und Zurück-Button
        // befinden sich im scrollbaren Bereich.
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            setBackgroundColor(bg)
        }

        // Kopfbereich und Reiter bleiben fest sichtbar.
        // Nur die Inhalte der ausgewählten ACL-Seite scrollen.
        val children = (0 until root.childCount).map {
            root.getChildAt(it)
        }

        children.forEach { child ->
            root.removeView(child)
            when (child) {
                aclGroupPage, aclRightsPage, aclPropertiesPage -> {
                    scrollContent.addView(child)
                }
                else -> fixedHeader.addView(child)
            }
        }



        // Die ACL-Inhalte bleiben scrollbar.
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(fixedHeader)
            addView(
                ScrollView(this@MainActivity).apply {
                    addView(scrollContent)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        showGatewayScreen(page)
    }

    private fun showMumbleBanList() {
        currentPage = "MUMBLE_BAN_LIST"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(bg)
        }

        root.addView(label("🚫 Mumble – Bannliste", 21f, Color.WHITE, true))
        root.addView(label(
            "Gesperrte Benutzer des verbundenen Mumble-Servers.",
            14f, muted
        ))

        val search = android.widget.EditText(this).apply {
            hint = "🔎 Name / IP / Sperrgrund"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        toolbar.addView(
            search,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val refreshButton = button("🔄") {}
        toolbar.addView(
            refreshButton,
            LinearLayout.LayoutParams(dp(52), dp(48))
        )

        toolbar.addView(
            button("←") { showMumbleAdmin() },
            LinearLayout.LayoutParams(dp(52), dp(48))
        )

        root.addView(toolbar)

        val addBanButton = button("➕ Neue Sperre") {
            val fields = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }

            fun addField(title: String, hintText: String): android.widget.EditText {
                fields.addView(label(title, 14f, Color.WHITE, true))
                return android.widget.EditText(this).apply {
                    hint = hintText
                    setSingleLine(true)
                    setTextColor(Color.WHITE)
                    setHintTextColor(muted)
                    setBackgroundColor(panel)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    fields.addView(this)
                }
            }

            val nameInput = addField("Benutzername (optional)", "Benutzername")
            val ipInput = addField("IP-Adresse / CIDR", "192.0.2.10/32")
            val reasonInput = addField("Begründung", "Grund der Sperre")
            val hashInput = addField("Zertifikat-Hash (optional)", "Zertifikat-Hash")

            fields.addView(label("Sperrdauer", 14f, Color.WHITE, true))
            val durationOptions = arrayOf(
                "Dauerhaft", "1 Stunde", "1 Tag", "7 Tage",
                "30 Tage", "Eigene Dauer (Sekunden)"
            )
            val durationSpinner = android.widget.Spinner(this).apply {
                adapter = object : android.widget.ArrayAdapter<String>(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    durationOptions
                ) {
                    override fun getView(
                        position: Int,
                        convertView: android.view.View?,
                        parent: android.view.ViewGroup
                    ): android.view.View {
                        return super.getView(position, convertView, parent).also {
                            (it as? android.widget.TextView)?.apply {
                                setTextColor(Color.WHITE)
                                setBackgroundColor(panel)
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                            }
                        }
                    }

                    override fun getDropDownView(
                        position: Int,
                        convertView: android.view.View?,
                        parent: android.view.ViewGroup
                    ): android.view.View {
                        return super.getDropDownView(position, convertView, parent).also {
                            (it as? android.widget.TextView)?.apply {
                                setTextColor(Color.WHITE)
                                setBackgroundColor(panel)
                                setPadding(dp(12), dp(12), dp(12), dp(12))
                            }
                        }
                    }
                }
            }
            durationSpinner.setBackgroundColor(panel)
            fields.addView(durationSpinner)

            val customDurationLabel = label(
                "Eigene Dauer in Sekunden", 14f, Color.WHITE, true
            ).apply {
                visibility = android.view.View.GONE
            }
            fields.addView(customDurationLabel)

            val customDuration = android.widget.EditText(this).apply {
                hint = "3600"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                setBackgroundColor(panel)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                visibility = android.view.View.GONE
            }
            fields.addView(customDuration)

            durationSpinner.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long
                    ) {
                        val visibilityValue =
                            if (position == 5) android.view.View.VISIBLE
                            else android.view.View.GONE
                        customDurationLabel.visibility = visibilityValue
                        customDuration.visibility = visibilityValue
                    }

                    override fun onNothingSelected(
                        parent: android.widget.AdapterView<*>?
                    ) = Unit
                }

            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Mumble – Sperre hinzufügen")
                .setView(ScrollView(this).apply { addView(fields) })
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Prüfen", null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        val parsed = MumbleBanList.parseBanAddress(
                            ipInput.text.toString()
                        )
                        if (parsed == null) {
                            ipInput.error = "Ungültige IP-Adresse oder Netzmaske"
                            return@setOnClickListener
                        }

                        val duration = when (durationSpinner.selectedItemPosition) {
                            0 -> 0L
                            1 -> 3600L
                            2 -> 86400L
                            3 -> 604800L
                            4 -> 2592000L
                            else -> customDuration.text.toString().toLongOrNull() ?: -1L
                        }

                        if (duration < 0L || duration > Int.MAX_VALUE) {
                            customDuration.error = "Ungültige Sperrdauer"
                            return@setOnClickListener
                        }

                        val wideNetwork = parsed.mask < 120
                        val warning = if (wideNetwork) {
                            "\n\n⚠️ Achtung: Diese Netzsperre kann viele Adressen betreffen."
                        } else {
                            ""
                        }

                        val newBan = se.lublin.humla.protobuf.Mumble.BanList.BanEntry
                            .newBuilder()
                            .setAddress(
                                com.google.protobuf.ByteString.copyFrom(parsed.address)
                            )
                            .setMask(parsed.mask)
                            .setName(nameInput.text.toString().trim())
                            .setReason(reasonInput.text.toString().trim())
                            .setHash(hashInput.text.toString().trim())
                            .setDuration(duration.toInt())
                            .build()

                        val manager = mumbleManager
                        val change = manager?.prepareMumbleBanAddition(newBan)

                        if (change == null) {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Bitte die aktuelle Bannliste erneut laden.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@setOnClickListener
                        }

                        showStyledMumbleDialog(
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Neue Mumble-Sperre bestätigen")
                                .setMessage(
                                    "Benutzer: ${nameInput.text}\\n" +
                                    "IP/Netz: ${ipInput.text}\\n" +
                                    "Grund: ${reasonInput.text}\\n" +
                                    "Dauer: ${if (duration == 0L) "Dauerhaft" else "$duration Sekunden"}" +
                                    warning +
                                    "\\n\\nDie vollständige Bannliste wird übertragen. " +
                                    "Zwischenzeitliche Änderungen anderer Administratoren " +
                                    "könnten überschrieben werden."
                                )
                                .setNegativeButton("Abbrechen", null)
                                .setPositiveButton("Sperre übertragen") { _, _ ->
                                    val sent = manager.sendMumblePreparedBanChange(change)
                                    if (sent) {
                                        dialog.dismiss()
                                        showMumbleBanList()
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Änderung gesendet. Serverliste wird kontrolliert.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Nicht gesendet. Bitte Bannliste neu laden.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                        )
                    }
            }
            dialog.show()
            styleMumbleDialog(dialog)
        }

        root.addView(addBanButton)

        val status = label("Noch keine Bannliste geladen.", 14f, muted)
        root.addView(status)

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var allBans = emptyList<MumbleBanList.BanEntry>()

        fun formatBanStart(value: String): String {
            if (value.isBlank()) return "Unbekannt"

            return try {
                val instant = java.time.Instant.parse(value)
                val berlin = instant.atZone(
                    java.time.ZoneId.of("Europe/Berlin")
                )
                val formatter = java.time.format.DateTimeFormatter.ofPattern(
                    "dd.MM.yyyy, HH:mm 'Uhr' z",
                    java.util.Locale.GERMANY
                )
                berlin.format(formatter)
            } catch (e: Exception) {
                value
            }
        }

        fun renderBans() {
            listContainer.removeAllViews()

            val filter = search.text.toString().trim()
            val filtered = allBans.filter {
                it.name.contains(filter, ignoreCase = true) ||
                    it.reason.contains(filter, ignoreCase = true) ||
                    MumbleBanList.formatAddress(it).contains(filter, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                listContainer.addView(label(
                    if (allBans.isEmpty()) {
                        "Keine Sperren in der geladenen Liste."
                    } else {
                        "Keine passenden Einträge."
                    },
                    14f, muted
                ))
            }

            filtered.forEach { ban ->
                val duration = when {
                    ban.durationSeconds == 0 -> "Dauerhaft"
                    ban.durationSeconds < 3600 ->
                        "${ban.durationSeconds / 60} Minuten"
                    ban.durationSeconds < 86400 ->
                        "${ban.durationSeconds / 3600} Stunden"
                    else ->
                        "${ban.durationSeconds / 86400} Tage"
                }

                val entry = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = GradientDrawable().apply {
                        setColor(panel)
                        cornerRadius = dp(16).toFloat()
                    }
                }

                entry.addView(label(
                    ban.name.ifBlank { "Unbekannter Benutzer" },
                    17f, Color.WHITE, true
                ))
                entry.addView(label(
                    "IP/Netz: ${MumbleBanList.formatAddress(ban)}",
                    14f, muted
                ))
                entry.addView(label(
                    "Grund: ${ban.reason.ifBlank { "Nicht angegeben" }}",
                    14f, muted
                ))
                entry.addView(label(
                    "Beginn: ${formatBanStart(ban.start)}",
                    14f, muted
                ))
                entry.addView(label(
                    "Dauer: $duration",
                    14f, muted
                ))

                entry.addView(button("🗑️ Sperre entfernen") {
                    val manager = mumbleManager
                    val change = manager?.prepareMumbleBanRemoval(ban.original)

                    if (change == null) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Bitte die aktuelle Bannliste erneut laden.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        showStyledMumbleDialog(
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Mumble – Sperre entfernen")
                                .setMessage(
                                    "Sperre für „${ban.name.ifBlank {
                                        MumbleBanList.formatAddress(ban)
                                    }}“ wirklich entfernen?\\n\\n" +
                                    "Die gesamte aktualisierte Bannliste wird " +
                                    "übertragen. Änderungen anderer Administratoren " +
                                    "könnten überschrieben werden."
                                )
                                .setNegativeButton("Abbrechen", null)
                                .setPositiveButton("Sperre entfernen") { _, _ ->
                                    val sent = manager.sendMumblePreparedBanChange(change)
                                    if (sent) {
                                        showMumbleBanList()
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Entbannung gesendet. Serverliste wird kontrolliert.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Nicht gesendet. Bitte Bannliste neu laden.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                        )
                    }
                })

                listContainer.addView(
                    entry,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dp(6), 0, dp(10))
                    }
                )
            }
        }

        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}
                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    renderBans()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )

        refreshButton.setOnClickListener {
            allBans = emptyList()
            listContainer.removeAllViews()
            status.text = "Bannliste wird angefordert …"
            if (mumbleManager?.requestMumbleBanList() != true) {
                status.text =
                    "Keine Verbindung oder keine bestätigten Bannlistenrechte."
            }
        }

        root.addView(
            ScrollView(this).apply {
                addView(listContainer)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        mumbleManager?.onMumbleBanListConnectionLost = {
            runOnUiThread {
                if (currentPage == "MUMBLE_BAN_LIST") {
                    allBans = emptyList()
                    listContainer.removeAllViews()
                    status.text = "Mumble-Verbindung getrennt."
                }
            }
        }

        mumbleManager?.onMumbleBansReceived = { bans ->
            runOnUiThread {
                if (currentPage == "MUMBLE_BAN_LIST") {
                    allBans = bans
                    status.text = "${bans.size} Sperren geladen."
                    renderBans()
                }
            }
        }

        mumbleManager?.onMumbleBanListTimeout = {
            runOnUiThread {
                if (currentPage == "MUMBLE_BAN_LIST") {
                    allBans = emptyList()
                    listContainer.removeAllViews()
                    status.text =
                        "⚠️ Keine Antwort vom Mumble-Server innerhalb von " +
                        "10 Sekunden. Bitte die Mumble-Verbindung " +
                        "trennen und neu verbinden."
                }
            }
        }

        showGatewayScreen(root)

        status.text = "Bannliste wird angefordert …"
        if (mumbleManager?.requestMumbleBanList() != true) {
            status.text =
                "Keine Verbindung oder keine bestätigten Bannlistenrechte."
        }
    }

    private fun showMumbleRegisteredUsers() {
        currentPage = "MUMBLE_REGISTERED_USERS"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(bg)
        }

        root.addView(label("👥 Registrierte Mumble-Benutzer", 21f, Color.WHITE, true))
        root.addView(label(
            "Hier erscheinen auch registrierte Benutzer, die gerade offline sind.",
            14f, muted
        ))

        val search = android.widget.EditText(this).apply {
            hint = "🔎 Name / ID"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        toolbar.addView(
            search,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val refreshUsersButton = button("🔄") {}
        toolbar.addView(
            refreshUsersButton,
            LinearLayout.LayoutParams(dp(52), dp(48))
        )

        toolbar.addView(
            button("←") { showMumbleAdmin() },
            LinearLayout.LayoutParams(dp(52), dp(48))
        )

        root.addView(toolbar)

        val status = label("Noch keine Benutzerliste geladen.", 14f, muted)
        root.addView(status)

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var allUsers = emptyList<MumbleRegisteredUsers.RegisteredUser>()
        var pendingRemovalId: Int? = null
        var pendingRemovalName: String? = null
        var removalVerificationRequested = false

        fun renderUsers() {
            listContainer.removeAllViews()
            val query = search.text.toString().trim()
            val visible = allUsers.filter {
                query.isEmpty() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.id.toString().contains(query)
            }

            status.text = if (allUsers.isEmpty()) {
                "Keine registrierten Benutzer empfangen."
            } else {
                "${visible.size} von ${allUsers.size} registrierten Benutzern"
            }

            visible.forEach { user ->
                val userRow = label(
                    "👤 ${user.name}  ·  ID ${user.id}",
                    16f, Color.WHITE
                ).apply {
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("👤 Registrierter Benutzer")
                            .setMessage(
                                "Name: ${user.name}\n" +
                                "Registrierungs-ID: ${user.id}\n" +
                                "Zuletzt gesehen: " +
                                (user.lastSeen.ifBlank { "Unbekannt" })
                            )
                            .setNegativeButton("Schließen", null)
                            .setPositiveButton("Registrierung entfernen") { _, _ ->
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("⚠️ Registrierung entfernen?")
                                    .setMessage(
                                        "Benutzer: ${user.name}\n" +
                                        "Registrierungs-ID: ${user.id}\n\n" +
                                        "Die Registrierung wird dauerhaft vom " +
                                        "Mumble-Server entfernt.\n\n" +
                                        "Möchtest du wirklich fortfahren?"
                                    )
                                    .setNegativeButton("Abbrechen", null)
                                    .setPositiveButton("Ja, entfernen") { _, _ ->
                                        val result = mumbleManager
                                            ?.unregisterRegisteredUser(user.id)
                                            ?: "Keine Mumble-Verbindung vorhanden"
                                        status.text = result
                                        if (result.startsWith("Deregistrierung für ID")) {
                                            pendingRemovalId = user.id
                                            pendingRemovalName = user.name
                                            removalVerificationRequested = false
                                            status.postDelayed({
                                                if (currentPage == "MUMBLE_REGISTERED_USERS") {
                                                    status.text = "Überprüfe Benutzerliste …"
                                                    removalVerificationRequested =
                                                        mumbleManager?.requestRegisteredUsers() == true
                                                    if (!removalVerificationRequested) {
                                                        pendingRemovalId = null
                                                        pendingRemovalName = null
                                                        status.text = "⚠️ Überprüfung nicht möglich: " +
                                                            "Keine Verbindung oder Berechtigung."
                                                    }
                                                }
                                            }, 1200L)
                                        }
                                    }
                                    .show().also { styleMumbleDialog(it) }
                            }
                            .show().also { styleMumbleDialog(it) }
                    }
                }
                listContainer.addView(userRow)
                if (user.lastSeen.isNotBlank()) {
                    listContainer.addView(label(
                        "Zuletzt gesehen: ${user.lastSeen}",
                        13f, muted
                    ))
                }
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {}
            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                renderUsers()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        refreshUsersButton.setOnClickListener {
            status.text = "Benutzerliste wird angefordert …"
            if (mumbleManager?.requestRegisteredUsers() != true) {
                status.text = "Keine Verbindung oder keine bestätigten Administratorrechte."
            }
        }

        root.addView(
            ScrollView(this).apply {
                addView(listContainer)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        mumbleManager?.onRegisteredUsersConnectionLost = {
            runOnUiThread {
                if (currentPage == "MUMBLE_REGISTERED_USERS") {
                    val wasPending = pendingRemovalId != null
                    pendingRemovalId = null
                    pendingRemovalName = null
                    removalVerificationRequested = false
                    if (wasPending) {
                        status.text = "⚠️ Mumble-Verbindung getrennt. " +
                            "Deregistrierung konnte nicht überprüft werden."
                    }
                }
            }
        }

        mumbleManager?.onRegisteredUsersReceived = { users ->
            runOnUiThread {
                if (currentPage == "MUMBLE_REGISTERED_USERS") {
                    allUsers = users
                    renderUsers()

                    val removalId = pendingRemovalId
                    if (removalId != null &&
                        removalVerificationRequested &&
                        mumbleManager?.isConnected() == true
                    ) {
                        removalVerificationRequested = false
                        val removalName = pendingRemovalName ?: "Unbekannt"
                        pendingRemovalId = null
                        pendingRemovalName = null

                        status.text = if (users.none { it.id == removalId }) {
                            "✅ Registrierung entfernt: $removalName (ID $removalId)"
                        } else {
                            "⚠️ Registrierung weiterhin vorhanden: " +
                                "$removalName (ID $removalId)"
                        }
                    }
                }
            }
        }

        showGatewayScreen(root)

        status.text = "Benutzerliste wird angefordert …"
        if (mumbleManager?.requestRegisteredUsers() != true) {
            status.text = "Keine Verbindung oder keine bestätigten Administratorrechte."
        }
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
                    val currentChannel = bandChannel.text.toString().trim()
                    when (position) {
                        0 -> if (currentChannel.isBlank() ||
                            currentChannel.equals("PC-Only", true) ||
                            currentChannel.equals("PC Only", true)
                        ) {
                            bandChannel.setText("Crosslink")
                        }
                        2 -> if (currentChannel.isBlank() ||
                            currentChannel.equals("Crosslink", true)
                        ) {
                            bandChannel.setText("PC Only")
                        }
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
        root.addView(button("🔄 Automatische Wiederverbindung") {
            showReconnectSettings()
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


    private fun showReconnectSettings() {
        currentPage = "Automatische Wiederverbindung"
        val saved = ReconnectSettings.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(bg)
        }

        root.addView(label("🔄 Automatische Wiederverbindung", 24f, Color.WHITE, true))
        root.addView(label(
            "Bei einem technischen Verbindungsabbruch kann FunkGateway die Verbindung erneut aufbauen.",
            14f, muted
        ))

        fun option(title: String, checked: Boolean): android.widget.CheckBox {
            return android.widget.CheckBox(this).apply {
                text = title
                setTextColor(Color.WHITE)
                isChecked = checked
                setPadding(0, dp(8), 0, dp(8))
                root.addView(this)
            }
        }

        root.addView(label("Wiederverbindung", 19f, accent, true).apply {
            setPadding(0, dp(22), 0, dp(8))
        })
        val frnReconnect = option("FRN automatisch wiederverbinden", saved.frnEnabled)
        val mumbleReconnect = option("Mumble automatisch wiederverbinden", saved.mumbleEnabled)

        root.addView(label("Automatisch beim App-Start verbinden", 19f, accent, true).apply {
            setPadding(0, dp(22), 0, dp(8))
        })
        val frnStart = option("FRN beim Start verbinden", saved.frnConnectOnStart)
        val mumbleStart = option("Mumble beim Start verbinden", saved.mumbleConnectOnStart)

        fun delaySelector(title: String, values: List<Int>, selected: Int): android.widget.Spinner {
            root.addView(label(title, 15f, muted).apply {
                setPadding(0, dp(18), 0, dp(6))
            })
            return android.widget.Spinner(this).apply {
                background = rounded(panel)
                setPopupBackgroundDrawable(rounded(panel))
                minimumHeight = dp(54)
                adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    R.layout.frn_spinner_item,
                    values.map { "$it Sekunden" }
                )
                setSelection(values.indexOf(selected).coerceAtLeast(0))
                root.addView(this)
            }
        }

        val initialValues = listOf(3, 5, 10)
        val maxValues = listOf(30, 60, 120)

        val initialDelay = delaySelector(
            "Wartezeit vor dem ersten Versuch",
            initialValues,
            saved.initialDelaySeconds
        )
        val maxDelay = delaySelector(
            "Maximale Wartezeit",
            maxValues,
            saved.maxDelaySeconds
        )

        root.addView(label(
            "Bei wiederholten Fehlern steigt die Wartezeit bis zum eingestellten Maximum. " +
                "Nach einer Wiederverbindung bleiben PTT und Aussendung ausgeschaltet. " +
                "Ein manuelles Trennen startet keine Wiederverbindung.",
            14f, muted
        ).apply {
            setPadding(0, dp(20), 0, dp(12))
        })

        root.addView(button("💾 Einstellungen speichern") {
            ReconnectSettings.save(
                this,
                ReconnectSettings(
                    frnEnabled = frnReconnect.isChecked,
                    mumbleEnabled = mumbleReconnect.isChecked,
                    initialDelaySeconds = initialValues[initialDelay.selectedItemPosition],
                    maxDelaySeconds = maxValues[maxDelay.selectedItemPosition],
                    frnConnectOnStart = frnStart.isChecked,
                    mumbleConnectOnStart = mumbleStart.isChecked
                )
            )
            android.widget.Toast.makeText(
                this,
                "Wiederverbindungs-Einstellungen gespeichert",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            showSettings()
        })

        root.addView(button("← Zurück zu Einstellungen") {
            showSettings()
        })

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

        val frnMicInitial = prefs.getInt(
            "frn_microphone_gain", 100
        ).coerceIn(0, 300)
        gatewayService?.setFrnMicrophoneGain(frnMicInitial)

        val frnMicLabel = label(
            "🎙 FRN Mikrofonverstärkung · $frnMicInitial %",
            19f, accent, true
        ).apply {
            setPadding(0, dp(28), 0, dp(12))
        }
        root.addView(frnMicLabel)

        root.addView(android.widget.SeekBar(this).apply {
            max = 300
            progress = frnMicInitial
            setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: android.widget.SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return
                        frnMicLabel.text =
                            "🎙 FRN Mikrofonverstärkung · $value %"
                        gatewayService?.setFrnMicrophoneGain(value)
                        prefs.edit()
                            .putInt("frn_microphone_gain", value)
                            .apply()
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
            "100 % = normal. Höhere Werte machen das Mikrofon lauter, " +
                "können aber auch Rauschen und Verzerrungen verstärken.",
            13f, muted
        ))

        val mumbleInitial = prefs.getInt("mumble_volume", 100).coerceIn(0, 100)
        gatewayService?.setMumbleVolume(mumbleInitial)

        val mumbleValueLabel = label(
            "Mumble · $mumbleInitial %",
            19f, accent, true
        ).apply {
            setPadding(0, dp(28), 0, dp(12))
        }
        root.addView(mumbleValueLabel)

        root.addView(android.widget.SeekBar(this).apply {
            max = 100
            progress = mumbleInitial
            setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: android.widget.SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return
                        mumbleValueLabel.text = "Mumble · $value %"
                        gatewayService?.setMumbleVolume(value)
                        prefs.edit().putInt("mumble_volume", value).apply()
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

        val mumbleMicInitial = prefs.getInt(
            "mumble_microphone_gain", 100
        ).coerceIn(0, 300)

        val mumbleMicLabel = label(
            "🎙 Mumble Mikrofon · $mumbleMicInitial %",
            19f, accent, true
        ).apply {
            setPadding(0, dp(28), 0, dp(12))
        }
        root.addView(mumbleMicLabel)

        root.addView(android.widget.SeekBar(this).apply {
            max = 300
            progress = mumbleMicInitial
            setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: android.widget.SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return
                        mumbleMicLabel.text =
                            "🎙 Mumble Mikrofon · $value %"
                        gatewayService?.setMumbleMicrophoneGain(value)
                        prefs.edit()
                            .putInt("mumble_microphone_gain", value)
                            .apply()
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
            "100 % = normal. Höhere Werte verstärken auch Rauschen. " +
                "Änderungen wirken sofort, auch während einer Mumble-Verbindung. " +
                "VoIP-Verlinkung und Papagei bleiben unverändert.",
            13f, muted
        ))

        root.addView(label(
            "Die globale Lautsprecher-Stummschaltung gilt für FRN und Mumble.",
            13f, muted
        ))
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
                    val destination = recipientId ?: ""
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
                "Mumble", "MUMBLE_ROOMS", "MUMBLE_CERTIFICATES",
                "MUMBLE_REGISTERED_USERS", "MUMBLE_ADMIN",
                "MUMBLE_BAN_LIST", "MUMBLE_ACL" -> "Mumble"
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

        if (currentPage in listOf("Mumble", "MUMBLE_ROOMS", "MUMBLE_REGISTERED_USERS", "MUMBLE_CERTIFICATES", "MUMBLE_ADMIN",
            "MUMBLE_BAN_LIST", "MUMBLE_ACL")) {
            val subTabs = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), 0, dp(8), dp(8))
            }

            val sections = listOf(
                "Verbindung" to "Mumble",
                "Räume & Nutzer" to "MUMBLE_ROOMS",
                "🔐 Zertifikate" to "MUMBLE_CERTIFICATES",
                "🛡️ Admin" to "MUMBLE_ADMIN"
            )

            sections.forEach { (title, page) ->
                val tab = button(title) {
                    when (page) {
                        "Mumble" -> showMumble()
                        "MUMBLE_ROOMS" -> showMumbleRooms()
                        "MUMBLE_CERTIFICATES" -> showMumbleCertificates()
                        "MUMBLE_ADMIN" -> showMumbleAdmin()
                    }
                }.apply {
                    textSize = 13f
                    setPadding(dp(14), 0, dp(14), 0)
                    isSingleLine = true
                    if (currentPage == page) setTextColor(accent)
                }

                subTabs.addView(
                    tab,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(48)
                    ).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    }
                )
            }

            frame.addView(
                android.widget.HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    isFillViewport = false
                    addView(subTabs)
                }
            )
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
            val channelBusy = when (target) {
                GatewayService.PttTarget.FRN ->
                    service?.getFrnParticipants()?.activeIndex != null
                GatewayService.PttTarget.MUMBLE ->
                    mumbleManager?.isMumbleChannelBusy() == true
                else -> false
            }
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
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        content.addView(label(
            "⚓ FunkGateway Mobile",
            24f, Color.WHITE, true
        ))
        content.addView(label(
            "Betriebszentrale · Verbindungen und Sprachdienste",
            14f, accent
        ).apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        val summary = label("", 16f, Color.WHITE, true)
        val frnDetails = label("", 15f, muted)
        val mumbleDetails = label("", 15f, muted)
        val bridgeDetails = label("", 15f, muted)

        fun section(title: String, details: android.widget.TextView) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(16))
                background = rounded(panel)
            }
            card.addView(label(title, 19f, Color.WHITE, true))
            details.setPadding(0, dp(10), 0, 0)
            card.addView(details)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
        }

        section("📡 Gesamtstatus", summary)
        section("📻 FRN", frnDetails)

        val frnActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun frnAction(title: String, action: () -> Unit) {
            frnActions.addView(
                button(title, action),
                LinearLayout.LayoutParams(0, -1, 1f)
            )
        }

        frnAction("🔗 Verbinden") {
            val last = org.funkgateway.mobile.frn.FrnLastConnectionStore.load(this)
            if (last == null) {
                showFrn()
            } else {
                connectFrnFromSelection(
                    last.room,
                    last.profileId,
                    navigateToParticipants = false
                )
            }
        }

        frnAction("⛔ Trennen") {
            gatewayService?.disconnectFrn()
        }

        frnAction("⚙ Öffnen") {
            showFrn()
        }

        (frnDetails.parent as? LinearLayout)?.addView(
            frnActions,
            LinearLayout.LayoutParams(-1, dp(72)).apply {
                topMargin = dp(12)
            }
        )
        section("🎧 Mumble", mumbleDetails)

        val mumbleActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun mumbleAction(title: String, action: () -> Unit) {
            mumbleActions.addView(
                button(title, action),
                LinearLayout.LayoutParams(0, -1, 1f)
            )
        }

        mumbleAction("🔗 Verbinden") {
            val saved = MumbleConnectionStore.loadLastSuccessful(this)
                ?: MumbleConnectionStore.load(this)
            val manager = getOrCreateMumbleManager()

            if (manager.isConnected() || manager.isConnecting()) {
                android.widget.Toast.makeText(
                    this, "Mumble ist bereits verbunden oder verbindet.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else if (saved.host.isBlank() ||
                       saved.username.isBlank() ||
                       saved.port !in 1..65535) {
                showMumble()
            } else {
                startForegroundService(
                    Intent(this, GatewayService::class.java).apply {
                        action = GatewayService.ACTION_START
                    }
                )
                manager.connect(
                    saved.host, saved.port,
                    saved.username, saved.password
                )
            }
        }

        mumbleAction("⛔ Trennen") {
            mumbleManager?.let { manager ->
                gatewayService?.onMumbleConnectionLost(manager)
                manager.disconnect()
                gatewayService?.stopIfIdle()
            }
        }

        mumbleAction("⚙ Öffnen") {
            showMumble()
        }

        (mumbleDetails.parent as? LinearLayout)?.addView(
            mumbleActions,
            LinearLayout.LayoutParams(-1, dp(72)).apply {
                topMargin = dp(12)
            }
        )
        section("🔗 Sprachverlinkung und Papagei", bridgeDetails)

        val bridgeButton = button("🔗 Verlinkung starten") {
            val service = gatewayService
            if (service?.isVoipBridgeActive() == true) {
                service.stopVoipBridgeManually()
            } else if (service != null &&
                service.getFrnStatus() == GatewayService.FrnStatus.CONNECTED &&
                mumbleManager?.isConnected() == true &&
                !service.isMumbleParrotActive()
            ) {
                if (!service.startVoipBridge()) {
                    android.widget.Toast.makeText(
                        this, "Verlinkung noch nicht bereit.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        content.addView(bridgeButton)

        val parrotButton = button("🦜 Mumble-Papagei starten") {
            val service = gatewayService
            if (service?.isMumbleParrotActive() == true) {
                service.stopMumbleParrotManually()
            } else if (service != null &&
                mumbleManager?.isConnected() == true &&
                !service.isVoipBridgeActive()
            ) {
                if (!service.startMumbleParrot()) {
                    android.widget.Toast.makeText(
                        this, "Papagei noch nicht bereit.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        content.addView(parrotButton)

        content.addView(button("🔗 Beide zuletzt verbinden") {
            val service = gatewayService
            val lastFrn =
                org.funkgateway.mobile.frn.FrnLastConnectionStore.load(this)
            val lastMumble =
                MumbleConnectionStore.loadLastSuccessful(this)
                    ?: MumbleConnectionStore.load(this)

            val messages = mutableListOf<String>()

            if (service == null) {
                messages.add("Gateway-Dienst noch nicht bereit")
            } else if (
                service.getFrnStatus() ==
                GatewayService.FrnStatus.DISCONNECTED
            ) {
                if (lastFrn == null) {
                    messages.add("FRN: Noch kein letzter Raum gespeichert")
                } else {
                    connectFrnFromSelection(
                        lastFrn.room,
                        lastFrn.profileId,
                        navigateToParticipants = false
                    )
                }
            }

            val manager = getOrCreateMumbleManager()
            if (!manager.isConnected() && !manager.hasActiveConnectionAttempt()) {
                if (
                    lastMumble.host.isBlank() ||
                    lastMumble.username.isBlank() ||
                    lastMumble.port !in 1..65535
                ) {
                    messages.add("Mumble: Keine Verbindungsdaten gespeichert")
                } else {
                    startForegroundService(
                        Intent(this, GatewayService::class.java).apply {
                            action = GatewayService.ACTION_START
                        }
                    )
                    manager.connect(
                        lastMumble.host,
                        lastMumble.port,
                        lastMumble.username,
                        lastMumble.password
                    )
                }
            }

            if (messages.isNotEmpty()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Schnellverbindung")
                    .setMessage(messages.joinToString("\\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        })

        content.addView(button("⛔ Alle Verbindungen trennen") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Alle Verbindungen trennen?")
                .setMessage(
                    "FRN und Mumble werden getrennt. " +
                    "Laufende Sprachübertragungen werden beendet."
                )
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Alle trennen") { _, _ ->
                    val service = gatewayService
                    service?.stopVoipBridgeManually()
                    service?.stopMumbleParrotManually()

                    mumbleManager?.let { manager ->
                        service?.onMumbleConnectionLost(manager)
                        manager.disconnect()
                    }

                    service?.disconnectFrn()
                    service?.stopIfIdle()
                }
                .show()
        })

        content.addView(label(
            "Verbindungen verwalten",
            18f, Color.WHITE, true
        ).apply {
            setPadding(0, dp(10), 0, dp(10))
        })
        content.addView(button("📻 FRN öffnen") { showFrn() })
        content.addView(button("🎧 Mumble öffnen") { showMumble() })
        content.addView(button("🔗 Verlinkung öffnen") {
            showPage("Verlinkung")
        })

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (currentPage != "Start" || !content.isAttachedToWindow) return

                val service = gatewayService
                val frnState = service?.getFrnStatus()
                val frnConnected =
                    frnState == GatewayService.FrnStatus.CONNECTED
                val mumbleConnected = mumbleManager?.isConnected() == true
                val mumbleConnecting = mumbleManager?.isConnecting() == true
                val bridgeActive = service?.isVoipBridgeActive() == true
                val parrotActive = service?.isMumbleParrotActive() == true
                val connectedCount =
                    (if (frnConnected) 1 else 0) +
                    (if (mumbleConnected) 1 else 0)

                val bridgeStatus = when {
                    bridgeActive && frnConnected && mumbleConnected ->
                        "🟢 Aktiv"
                    bridgeActive ->
                        "🟡 Unterbrochen"
                    else ->
                        "⚪ Aus"
                }

                summary.text = "$connectedCount von 2 Sprachdiensten verbunden" +
                    "\nFRN↔Mumble: " +
                    bridgeStatus

                val activeRoom = service?.getConnectedFrnRoom()
                val lastRoom =
                    org.funkgateway.mobile.frn.FrnLastConnectionStore.load(this@MainActivity)
                val frnText = when (frnState) {
                    GatewayService.FrnStatus.CONNECTED -> "🟢 Verbunden"
                    GatewayService.FrnStatus.CONNECTING -> "🟡 Verbindet …"
                    else -> "⚪ Getrennt"
                }
                val roomText = activeRoom ?: lastRoom?.room
                frnDetails.text = frnText + "\n" +
                    (roomText?.let {
                        "${it.server}:${it.port} · ${it.room}"
                    } ?: "Noch kein FRN-Raum gespeichert")

                val savedMumble = MumbleConnectionStore.loadLastSuccessful(this@MainActivity)
                    ?: MumbleConnectionStore.load(this@MainActivity)
                val mumbleText = when {
                    mumbleConnected -> "🟢 Verbunden"
                    mumbleConnecting -> "🟡 Verbindet …"
                    else -> "⚪ Getrennt"
                }
                val connectedServer = mumbleManager?.getConnectedServer()
                val hostText = connectedServer?.first ?: savedMumble.host
                val portText = (connectedServer?.second ?: savedMumble.port).toString()
                mumbleDetails.text = mumbleText + "\n" +
                    (if (hostText.isNotBlank()) "$hostText:$portText"
                     else "Noch kein Mumble-Server gespeichert")

                bridgeDetails.text =
                    "FRN ↔ Mumble: " +
                    bridgeStatus +
                    "\nMumble-Papagei: " +
                    (if (parrotActive) "🟢 Aktiv" else "⚪ Aus")

                bridgeButton.text = if (bridgeActive)
                    "⛔ FRN↔Mumble-Verlinkung stoppen"
                else
                    "🔗 FRN↔Mumble-Verlinkung starten"
                bridgeButton.isEnabled = bridgeActive ||
                    (frnConnected && mumbleConnected && !parrotActive)

                parrotButton.text = if (parrotActive)
                    "⛔ Mumble-Papagei stoppen"
                else
                    "🦜 Mumble-Papagei starten"
                parrotButton.isEnabled = parrotActive ||
                    (mumbleConnected && !bridgeActive)

                handler.postDelayed(this, 1000L)
            }
        }

        showGatewayScreen(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        handler.post(updater)
    }

    private var hasPendingMumbleAclChanges: (() -> Boolean)? = null

    @Deprecated("Back navigation for the current Activity")
    override fun onBackPressed() {
        if (
            currentPage == "MUMBLE_ACL" &&
            hasPendingMumbleAclChanges?.invoke() == true
        ) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Ungespeicherte ACL-Änderungen")
                .setMessage(
                    "Du hast lokale Änderungen an Mumble-Gruppen " +
                    "vorgemerkt. Möchtest du diese verwerfen?"
                )
                .setNegativeButton("Weiter bearbeiten", null)
                .setPositiveButton("Änderungen verwerfen") { _, _ ->
                    hasPendingMumbleAclChanges = null
                    showHome()
                }
                .show().also { styleMumbleDialog(it) }
            return
        }

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
        mumbleStartupPending =
            savedInstanceState == null &&
            ReconnectSettings.load(this).mumbleConnectOnStart
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
