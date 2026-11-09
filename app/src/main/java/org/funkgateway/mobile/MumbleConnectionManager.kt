package org.funkgateway.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import se.lublin.humla.HumlaService
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.Server
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.HumlaException
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.IMessage
import se.lublin.humla.util.VoiceTargetMode
import java.security.cert.X509Certificate
import se.lublin.humla.protobuf.Mumble

class MumbleConnectionManager(
    context: Context,
    onStatusChanged: (String) -> Unit,
    onCertificateUntrusted: (String, Int, X509Certificate) -> Unit = { _, _, _ -> }
) {
    private val context: Context = context.applicationContext

    private var onStatusChanged: (String) -> Unit = onStatusChanged
    private var onCertificateUntrusted:
        (String, Int, X509Certificate) -> Unit = onCertificateUntrusted

    private var uiCallbackOwner: Any? = null

    @Synchronized
    fun setUiCallbacks(
        statusCallback: ((String) -> Unit)?,
        certificateCallback: ((String, Int, X509Certificate) -> Unit)?,
        owner: Any? = null
    ) {
        uiCallbackOwner = owner
        onStatusChanged = statusCallback ?: {}
        onCertificateUntrusted = certificateCallback ?: { _, _, _ -> }
    }

    @Synchronized
    fun clearUiCallbacks(owner: Any) {
        if (uiCallbackOwner !== owner) return
        uiCallbackOwner = null
        onStatusChanged = {}
        onCertificateUntrusted = { _, _, _ -> }
    }
    var onTalkStateChanged: ((Int, String) -> Unit)? = null
    var onParrotTalkStateChanged: ((Int, String) -> Unit)? = null
    var onChannelMembershipChanged: (() -> Unit)? = null
    var onRegistrationStatusChanged: ((String) -> Unit)? = null
    private var registrationPending = false
    private var registrationRequestId = 0L

    var onOtherUserRegistrationStatusChanged: ((String) -> Unit)? = null
    private var otherRegistrationSessionId: Int? = null
    private var otherRegistrationRequestId = 0L

    private var service: IHumlaService? = null

    private val registeredUsers = MumbleRegisteredUsers()

    // Namen registrierter Benutzer, ausschließlich für die aktuelle Sitzung.
    private var registeredUserNames: Map<Int, String> = emptyMap()

    fun getRegisteredUserName(userId: Int): String? =
        registeredUserNames[userId]

    // Registrierte Benutzer für die lokale ACL-Gruppenbearbeitung.
    // Die Liste gehört ausschließlich zur aktuellen Mumble-Sitzung.
    fun getAclRegisteredUsers(): List<Pair<Int, String>> =
        registeredUserNames.entries
            .map { it.key to it.value }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.second }
            )


    // Mumble-Bannliste: zunächst ausschließlich lesen.
    private val mumbleBanList = MumbleBanList()

    // Mumble-Kanalrechte: zunächst ausschließlich lesen.
    private val mumbleAclManager = MumbleAclManager()

    var onMumbleAclReceived:
        ((MumbleAclManager.AclSnapshot) -> Unit)? = null

    var onMumbleAclConnectionLost: (() -> Unit)? = null

    fun requestMumbleAcl(channelId: Int): Boolean {
        if (!isConnected()) return false
        return mumbleAclManager.requestAcl(channelId)
    }

    private fun attachMumbleAcl() {
        val humla = service as? HumlaService ?: return
        val generation = moderationConnectionGeneration
        val expectedConnection = humla.connection

        mumbleAclManager.onAclReceived = { snapshot ->
            reconnectHandler.post {
                if (
                    service === humla &&
                    service?.isConnected == true &&
                    moderationConnectionGeneration == generation &&
                    humla.connection === expectedConnection
                ) {
                    onMumbleAclReceived?.invoke(snapshot)
                }
            }
        }

        mumbleAclManager.attach(expectedConnection)
    }

    private fun detachMumbleAcl() {
        mumbleAclManager.detach()
        mumbleAclManager.onAclReceived = null
        onMumbleAclConnectionLost?.invoke()
    }

    var onMumbleBansReceived:
        ((List<MumbleBanList.BanEntry>) -> Unit)? = null

    var onMumbleBanListConnectionLost: (() -> Unit)? = null

    var onMumbleBanListTimeout: (() -> Unit)? = null

    fun requestMumbleBanList(): Boolean {
        if (!hasMumbleModerationPermission(
                se.lublin.humla.net.Permissions.Ban
            )
        ) return false

        return mumbleBanList.requestBans()
    }

    fun getMumbleBans(): List<MumbleBanList.BanEntry> =
        mumbleBanList.getBans()


    var onRegisteredUsersReceived:
        ((List<MumbleRegisteredUsers.RegisteredUser>) -> Unit)? = null

    var onRegisteredUsersConnectionLost: (() -> Unit)? = null

    fun requestRegisteredUsers(): Boolean {
        if (!canRegisterOtherUsers()) return false
        return registeredUsers.requestUsers()
    }

    // Nur lesende Namensauflösung für die ACL-Gruppenanzeige.
    // Der Mumble-Server entscheidet, ob die Benutzerliste verfügbar ist.
    fun requestAclUserNames(): Boolean {
        if (!isConnected()) return false
        return registeredUsers.requestUsers()
    }

    private fun attachMumbleBanList() {
        val humla = service as? HumlaService ?: return
        val generation = moderationConnectionGeneration
        val expectedConnection = humla.connection

        mumbleBanList.onBansReceived = { bans ->
            reconnectHandler.post {
                if (
                    service === humla &&
                    service?.isConnected == true &&
                    moderationConnectionGeneration == generation &&
                    humla.connection === expectedConnection
                ) {
                    onMumbleBansReceived?.invoke(bans)
                }
            }
        }

        mumbleBanList.onRequestTimeout = {
            reconnectHandler.post {
                if (
                    service === humla &&
                    service?.isConnected == true &&
                    moderationConnectionGeneration == generation &&
                    humla.connection === expectedConnection
                ) {
                    onMumbleBanListTimeout?.invoke()
                }
            }
        }

        mumbleBanList.attach(expectedConnection)
    }

    private fun detachMumbleBanList() {
        // Vorbereitete Moderationsaktionen der alten Sitzung ungültig machen.
        ++moderationConnectionGeneration
        mumbleBanList.detach()
        mumbleBanList.onBansReceived = null
        mumbleBanList.onRequestTimeout = null
        onMumbleBanListConnectionLost?.invoke()
        onMumbleBansReceived?.invoke(emptyList())
    }

    private fun attachRegisteredUsers() {
        val humla = service as? HumlaService ?: return
        val generation = moderationConnectionGeneration
        val expectedConnection = humla.connection

        registeredUserNames = emptyMap()

        registeredUsers.onUsersReceived = { users ->
            reconnectHandler.post {
                if (
                    service === humla &&
                    service?.isConnected == true &&
                    moderationConnectionGeneration == generation &&
                    humla.connection === expectedConnection
                ) {
                    registeredUserNames = users.associate { it.id to it.name }
                    onRegisteredUsersReceived?.invoke(users)
                }
            }
        }
        registeredUsers.attach(expectedConnection)
    }

    private fun detachRegisteredUsers() {
        registeredUsers.detach()
        registeredUsers.onUsersReceived = null
        registeredUserNames = emptyMap()
        onRegisteredUsersConnectionLost?.invoke()
        onRegisteredUsersReceived?.invoke(emptyList())
    }

    private var bridgePcmListener: ((ShortArray) -> Unit)? = null

    private val humlaBridgePcmListener =
        se.lublin.humla.audio.AudioOutput.BridgePcmListener { pcm ->
            bridgePcmListener?.invoke(pcm)
        }

    fun setBridgePcmListener(listener: ((ShortArray) -> Unit)?) {
        bridgePcmListener = listener
        val humla = service as? HumlaService ?: return
        humla.setBridgePcmListener(
            if (listener != null) humlaBridgePcmListener else null
        )
    }

    private var playbackVolume = 1.0f
    private var playbackDeafened = false

    fun setPlaybackVolume(volume: Float) {
        playbackVolume = volume.coerceIn(0f, 1f)
        applyPlaybackVolume()
    }

    fun setPlaybackDeafened(deafened: Boolean) {
        playbackDeafened = deafened
        applyPlaybackVolume()
    }

    fun setMicrophoneGain(percent: Int) {
        val gain = percent.coerceIn(0, 300) / 100.0f
        (service as? HumlaService)?.setMicrophoneGain(gain)
    }

    private fun applyPlaybackVolume() {
        (service as? HumlaService)?.setLocalPlaybackVolume(
            if (playbackDeafened) 0f else playbackVolume
        )
    }
    private var bound = false
    private var binding = false
    private var pendingConnectIntent: Intent? = null
    private val certificateStore = MumbleCertificateStore(context)
    private var currentHost: String? = null
    private var currentPort: Int = 0
    private var connectionInProgress = false
    private var tlsConnectStartedAt = 0L
    private var lastUsername: String = ""
    private var lastPassword: String = ""
    // Automatische Mumble-Wiederverbindung.
    private val reconnectHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectBackoff = ReconnectBackoff()
    private var reconnectEnabled = false
    private var reconnectGeneration = 0L
    private var moderationConnectionGeneration = 0L
    private var reconnectTask: Runnable? = null
    private var manualDisconnect = false

    // Zeitüberwachung für den Mumble-Verbindungsaufbau.
    private var connectTimeoutTask: Runnable? = null

    private fun cancelConnectTimeout() {
        connectTimeoutTask?.let { reconnectHandler.removeCallbacks(it) }
        connectTimeoutTask = null
    }

    private fun startConnectTimeout() {
        cancelConnectTimeout()

        val task = Runnable {
            connectTimeoutTask = null

            if (!connectionInProgress || manualDisconnect) return@Runnable

            Log.w("FGW-Mumble", "Verbindungsaufbau nach 30 Sekunden abgebrochen")

            connectionInProgress = false
            pendingConnectIntent = null

            stopTalking()
            stopBridgeAudio()
            onConnectionLost?.invoke()

            // Einen eventuell noch laufenden Humla-Verbindungsaufbau abbrechen.
            try {
                service?.disconnect()
            } catch (e: Exception) {
                Log.w("FGW-Mumble", "Timeout: Humla-Abbruch fehlgeschlagen", e)
            }

            onStatusChanged("Mumble-Verbindungsaufbau: Zeitüberschreitung")
            scheduleMumbleReconnect()
        }

        connectTimeoutTask = task
        reconnectHandler.postDelayed(task, 30_000L)
    }

    // Benachrichtigt den GatewayService bei Verbindungsverlust.
    // Keine dauerhafte Referenz auf eine Activity speichern.
    var onConnectionLost: (() -> Unit)? = null


    private var pendingCertificate: X509Certificate? = null
    private var pendingCertificateHost: String? = null
    private var pendingCertificatePort: Int = 0

    data class PendingCertificateRequest(
        val host: String,
        val port: Int,
        val certificate: X509Certificate
    )

    fun getPendingCertificateRequest(): PendingCertificateRequest? {
        val certificate = pendingCertificate ?: return null
        val host = pendingCertificateHost ?: return null
        val port = pendingCertificatePort
        if (port !in 1..65535) return null

        return PendingCertificateRequest(host, port, certificate)
    }

    private fun cancelMumbleReconnect() {
        reconnectGeneration++
        reconnectTask?.let { reconnectHandler.removeCallbacks(it) }
        reconnectTask = null
        reconnectBackoff.reset()
    }

    private fun scheduleMumbleReconnect() {
        val settings = ReconnectSettings.load(context)
        if (!reconnectEnabled || manualDisconnect ||
            !settings.mumbleEnabled || pendingCertificate != null ||
            connectionInProgress || reconnectTask != null ||
            currentHost.isNullOrBlank() || lastUsername.isBlank()
        ) return

        val delay = reconnectBackoff.nextDelayMillis(settings)
        val generation = ++reconnectGeneration

        val task = Runnable {
            if (generation != reconnectGeneration) return@Runnable
            reconnectTask = null

            if (!reconnectEnabled || manualDisconnect ||
                !ReconnectSettings.load(context).mumbleEnabled ||
                pendingCertificate != null || connectionInProgress ||
                service?.isConnected == true
            ) return@Runnable

            val host = currentHost ?: return@Runnable
            Log.i("FGW-Mumble", "Automatischer Reconnect gestartet")
            connect(
                host, currentPort, lastUsername, lastPassword,
                automaticReconnect = true
            )
        }

        reconnectTask = task
        reconnectHandler.postDelayed(task, delay)
        Log.i("FGW-Mumble", "Reconnect in ${delay / 1000} Sekunden geplant")
    }

    private val observer = object : IHumlaObserver {
        override fun onConnecting() {
            onStatusChanged("Verbinde mit Mumble …")
        }

        override fun onConnected() {
            if (!connectionInProgress || manualDisconnect) {
                Log.w("FGW-Mumble", "Unerwartete Mumble-Verbindungsbestätigung")
                stopTalking()
                stopBridgeAudio()
                onConnectionLost?.invoke()
                try {
                    service?.disconnect()
                } catch (e: Exception) {
                    Log.w("FGW-Mumble", "Verspätete Verbindung konnte nicht getrennt werden", e)
                }
                return
            }

            cancelConnectTimeout()
            connectionInProgress = false
            cancelMumbleReconnect()
            ++moderationConnectionGeneration

            // Registrierungsrechte für jeden Server neu anfordern.
            requestRegistrationPermissions()
            attachRegisteredUsers()
            attachMumbleBanList()
            attachMumbleAcl()

            // Nur eine tatsächlich erfolgreiche Verbindung merken.
            try {
                MumbleConnectionStore.saveLastSuccessful(
                    context,
                    MumbleSavedConnection(
                        host = currentHost?.takeIf { it.isNotBlank() }
                            ?: error("Keine gültige Mumble-Serveradresse"),
                        port = currentPort,
                        username = lastUsername,
                        password = lastPassword
                    )
                )
                Log.i("FGW-Mumble", "Letzte erfolgreiche Verbindung gespeichert")
            } catch (e: Exception) {
                Log.w(
                    "FGW-Mumble",
                    "Letzte erfolgreiche Verbindung konnte nicht gespeichert werden",
                    e
                )
            }

            onStatusChanged("Mit Mumble verbunden")
        }

        override fun onDisconnected(error: HumlaException?) {
            detachMumbleAcl()
            detachMumbleBanList()
            detachRegisteredUsers()
            cancelConnectTimeout()
            registrationPending = false
            otherRegistrationSessionId = null
            ++otherRegistrationRequestId
            rootRegistrationPermissionsConfirmed = false
            connectionInProgress = false

            // Keine alte Aussendung nach einer Trennung fortsetzen.
            stopTalking()
            stopBridgeAudio()
            onConnectionLost?.invoke()

            Log.e("FGW-Mumble", "Verbindung getrennt", error)
            if (pendingCertificate != null) {
                onStatusChanged("Mumble-Zertifikat wartet auf Bestätigung")
                return
            }

            onStatusChanged(
                if (error == null) "Mumble getrennt"
                else "Mumble getrennt: ${error.message}"
            )

            if (!manualDisconnect) {
                scheduleMumbleReconnect()
            }
        }

        override fun onTLSHandshakeFailed(certs: Array<X509Certificate>?) {
            if (!connectionInProgress) {
                Log.w("FGW-Mumble", "Verspätete TLS-Meldung ignoriert")
                return
            }
            val certificate = certs?.firstOrNull()
            val host = currentHost
            val port = currentPort
            val target = service?.targetServer

            if (target == null ||
                !target.host.equals(host, ignoreCase = true) ||
                target.port != port
            ) {
                Log.w("FGW-Mumble", "TLS-Zertifikat: Zielserver stimmt nicht überein")
                return
            }

            if (certificate == null || host == null || port !in 1..65535) {
                onStatusChanged("Mumble-Zertifikat konnte nicht geprüft werden")
                return
            }

            Log.i(
                "FGW-Mumble-Cert",
                "TLS-Zertifikat nach " +
                    (android.os.SystemClock.elapsedRealtime() - tlsConnectStartedAt) +
                    " ms gemeldet"
            )
            pendingCertificate = certificate
            pendingCertificateHost = host
            pendingCertificatePort = port
            onStatusChanged("Mumble-Zertifikat benötigt Bestätigung")
            onCertificateUntrusted(host, port, certificate)
        }

        private fun notifyRoomTreeChanged() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onChannelMembershipChanged?.invoke()
            }
        }

        override fun onChannelAdded(channel: IChannel) {
            notifyRoomTreeChanged()
        }

        override fun onChannelStateUpdated(channel: IChannel) {
            notifyRoomTreeChanged()
        }

        override fun onChannelRemoved(channel: IChannel) {
            notifyRoomTreeChanged()
        }

        override fun onChannelPermissionsUpdated(channel: IChannel) {
            if (channel.id == 0) {
                Log.i("FGW-Mumble", "Root-Rechte: Antwort empfangen, permissions=${channel.permissions}, Register=${(channel.permissions and 262144) != 0}")
                rootRegistrationPermissionsConfirmed = true
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onChannelMembershipChanged?.invoke()
                }
            }
}

        override fun onUserConnected(user: IUser) {
            notifyRoomTreeChanged()
        }
        override fun onUserStateUpdated(user: IUser) {
            if (otherRegistrationSessionId == user.session && user.userId >= 0) {
                otherRegistrationSessionId = null
                ++otherRegistrationRequestId
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onOtherUserRegistrationStatusChanged?.invoke(
                        "✅ ${user.name} wurde registriert (ID ${user.userId})."
                    )
                }
            }
            val ownSessionId = runCatching {
                service?.HumlaSession()?.sessionId
            }.getOrNull()
            if (ownSessionId != null && user.session == ownSessionId) {
                if (user.userId >= 0) registrationPending = false
                val message = if (user.userId >= 0) {
                    "✅ Auf Mumble-Server registriert (ID ${user.userId})"
                } else {
                    "🔓 Auf Mumble-Server nicht registriert"
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRegistrationStatusChanged?.invoke(message)
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onChannelMembershipChanged?.invoke()
            }
        }
        override fun onUserTalkStateUpdated(user: IUser) {
            val sessionId = user.session
            val talkState = user.talkState.name
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onTalkStateChanged?.invoke(sessionId, talkState)
                onParrotTalkStateChanged?.invoke(sessionId, talkState)
            }
        }
        override fun onUserJoinedChannel(user: IUser, newChannel: IChannel, oldChannel: IChannel) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onChannelMembershipChanged?.invoke()
            }
        }
        override fun onUserRemoved(user: IUser, reason: String?) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onChannelMembershipChanged?.invoke()
            }
        }
        override fun onPermissionDenied(reason: String?) {
            Log.w(
                "FGW-Mumble",
                "Mumble PermissionDenied empfangen: ${reason ?: "Keine Begründung"}"
            )
            if (!registrationPending) return
            registrationPending = false
            val message = "⛔ Der Mumble-Server hat eine Berechtigung verweigert. Die Selbstregistrierung ist möglicherweise nicht erlaubt. Servermeldung: ${reason ?: "Keine Begründung"}"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onRegistrationStatusChanged?.invoke(message)
            }
        }
        override fun onMessageLogged(message: IMessage) {}
        override fun onVoiceTargetChanged(mode: VoiceTargetMode) {}
        override fun onLogInfo(message: String?) { Log.i("FGW-Mumble", "INFO: $message") }
        override fun onLogWarning(message: String?) { Log.w("FGW-Mumble", "WARNUNG: $message") }
        override fun onLogError(message: String?) { Log.e("FGW-Mumble", "FEHLER: $message") }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as HumlaService.HumlaBinder).service
            (service as? HumlaService)?.setBridgePcmListener(
                if (bridgePcmListener != null) humlaBridgePcmListener else null
            )
            applyPlaybackVolume()
            bound = true
            binding = false
            service?.registerObserver(observer)
            val pending = pendingConnectIntent
            pendingConnectIntent = null
            if (pending != null) {
                if (connectionInProgress && !manualDisconnect) {
                    context.startService(pending)
                    return
                }
                Log.w(
                    "FGW-Mumble",
                    "Veralteter Humla-Verbindungswunsch verworfen"
                )
            }
            onStatusChanged(
                if (service?.isConnected == true) "Mit Mumble verbunden"
                else "Mumble-Dienst bereit"
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            detachMumbleAcl()
            detachMumbleBanList()
            detachRegisteredUsers()
            cancelConnectTimeout()
            rootRegistrationPermissionsConfirmed = false
            registrationPending = false
            otherRegistrationSessionId = null
            ++otherRegistrationRequestId
            // Android behält die Dienstbindung bei und verbindet sie erneut.
            // Nur die Dienstreferenz und laufende Verbindung zurücksetzen.
            service = null
            connectionInProgress = false
            pendingConnectIntent = null
            onConnectionLost?.invoke()

            onStatusChanged("Mumble-Dienst getrennt")

            if (!manualDisconnect && pendingCertificate == null) {
                scheduleMumbleReconnect()
            }
        }
    }

    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        automaticReconnect: Boolean = false
    ) {
        if (host.isBlank() || username.isBlank() || port !in 1..65535) {
            onStatusChanged("Bitte Server, Port und Benutzername prüfen")
            return
        }

        if (connectionInProgress) {
            onStatusChanged("Mumble-Verbindung läuft bereits – bitte warten")
            return
        }

        if (!automaticReconnect) {
            cancelMumbleReconnect()
            manualDisconnect = false
            reconnectEnabled = ReconnectSettings.load(context).mumbleEnabled
        }

        pendingCertificate = null
        pendingCertificateHost = null
        pendingCertificatePort = 0
        connectionInProgress = true
        tlsConnectStartedAt = android.os.SystemClock.elapsedRealtime()
        currentHost = host.trim()
        currentPort = port
        lastUsername = username.trim()
        lastPassword = password
        startConnectTimeout()

        try {
            val server = Server(
                0L, host.trim(), host.trim(), port, username.trim(), password
            )

            val intent = Intent(context, HumlaService::class.java).apply {
                action = HumlaService.ACTION_CONNECT
                putExtra(HumlaService.EXTRAS_SERVER, server)
                putExtra(HumlaService.EXTRAS_CLIENT_NAME, "FunkGateway Mobile")
                putExtra(HumlaService.EXTRAS_TRANSMIT_MODE, se.lublin.humla.Constants.TRANSMIT_PUSH_TO_TALK)
                putExtra(HumlaService.EXTRAS_INPUT_RATE, 48000)
                putExtra(HumlaService.EXTRAS_INPUT_QUALITY, 40000)
                putExtra(HumlaService.EXTRAS_FRAMES_PER_PACKET, 2)
                putExtra(HumlaService.EXTRAS_AUDIO_SOURCE, android.media.MediaRecorder.AudioSource.MIC)
                val mumbleMicGain = context
                    .getSharedPreferences("funkgateway_audio", Context.MODE_PRIVATE)
                    .getInt("mumble_microphone_gain", 100)
                    .coerceIn(0, 300)
                putExtra(
                    HumlaService.EXTRAS_AMPLITUDE_BOOST,
                    mumbleMicGain / 100.0f
                )
                putExtra(HumlaService.EXTRAS_AUDIO_STREAM, android.media.AudioManager.STREAM_MUSIC)
                putExtra(HumlaService.EXTRAS_ECHO_CANCELLATION_METHOD, "none")
                putExtra(HumlaService.EXTRAS_USE_OPUS, true)
            putExtra(HumlaService.EXTRAS_FORCE_TCP, false) // UDP automatisch, TCP als Fallback
                putExtra(HumlaService.EXTRAS_HALF_DUPLEX, false)
                val trustPath = certificateStore.connectionTrustStore(host.trim(), port)
                putExtra(HumlaService.EXTRAS_TRUST_STORE, trustPath)
                putExtra(HumlaService.EXTRAS_TRUST_STORE_PASSWORD, certificateStore.getStorePassword())
                putExtra(HumlaService.EXTRAS_TRUST_STORE_FORMAT, certificateStore.getStoreFormat())
                val identityStore = MumbleIdentityStore(context)
                val selectedId = identityStore.selectedIdentityId()

                if (selectedId != null) {
                    val bytes = identityStore.loadIdentity(selectedId)
                    val identityPassword =
                        identityStore.loadIdentityPassword(selectedId)

                    identityStore.inspectPkcs12(bytes, identityPassword)

                    putExtra(HumlaService.EXTRAS_CERTIFICATE, bytes)
                    putExtra(
                        HumlaService.EXTRAS_CERTIFICATE_PASSWORD,
                        identityPassword
                    )

                    Log.i(
                        "FGW-Mumble",
                        "Ausgewähltes Benutzerzertifikat geladen: $selectedId"
                    )
                } else if (identityStore.listIdentities().isNotEmpty()) {
                    error("Bitte zuerst ein Mumble-Benutzerzertifikat auswählen")
                }
            }

            onStatusChanged("Verbinde mit ${host.trim()}:$port …")
            if (bound && service != null) {
                context.startService(intent)
            } else {
                pendingConnectIntent = intent
                if (!bound && !binding) {
                    binding = context.bindService(
                        Intent(context, HumlaService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE
                    )
                    if (!binding) {
                        pendingConnectIntent = null
                        connectionInProgress = false
                        cancelConnectTimeout()
                        onStatusChanged("Mumble-Dienst konnte nicht gebunden werden")
                        scheduleMumbleReconnect()
                    }
                }
            }
        } catch (e: Exception) {
            connectionInProgress = false
            cancelConnectTimeout()
            pendingConnectIntent = null
            onStatusChanged("Mumble-Fehler: ${e.message}")
            scheduleMumbleReconnect()
        }
    }

    data class MumbleUserItem(
        val sessionId: Int,
        val name: String,
        val talkState: String,
        val registered: Boolean,
        val selfMuted: Boolean,
        val selfDeafened: Boolean,
        val serverMuted: Boolean,
        val serverDeafened: Boolean,
        val localMuted: Boolean,
        val suppressed: Boolean
    )

    data class MumbleChannelItem(
        val id: Int,
        val name: String,
        val users: List<MumbleUserItem>,
        val children: List<MumbleChannelItem>
    )

    data class MumbleOwnPosition(
        val channelId: Int,
        val sessionId: Int,
        val username: String
    )

    fun getOwnPosition(): MumbleOwnPosition? {
        val activeService = service ?: return null
        if (!activeService.isConnected) return null

        return try {
            val session = activeService.HumlaSession()
            val ownChannel = session.sessionChannel
            val ownUser = session.sessionUser

            MumbleOwnPosition(
                channelId = ownChannel.id,
                sessionId = session.sessionId,
                username = ownUser.name
            )
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Eigene Position nicht verfügbar", e)
            null
        }
    }

    fun isMumbleChannelBusy(): Boolean {
        val activeService = service ?: return false
        if (!activeService.isConnected) return false

        return try {
            val session = activeService.HumlaSession()
            val ownSessionId = session.sessionId
            val ownChannel = session.sessionChannel

            ownChannel.users.any { user ->
                user.session != ownSessionId &&
                    user.talkState.name != "PASSIVE"
            }
        } catch (e: Exception) {
            Log.w(
                "FGW-Mumble",
                "Mumble-Kanalbelegung nicht ermittelbar",
                e
            )
            false
        }
    }

    fun setBridgeAudioEnabled(enabled: Boolean): Boolean {
        val humla = service as? HumlaService ?: return false
        return try {
            humla.setBridgeAudioEnabled(enabled)
            true
        } catch (e: Exception) {
            android.util.Log.e("MumbleBridge", "Bridge-Umschaltung fehlgeschlagen", e)
            false
        }
    }

    fun submitBridgeAudio(pcm: ShortArray, frameSize: Int): Boolean {
        val humla = service as? HumlaService ?: return false
        return try {
            humla.submitBridgeAudio(pcm, frameSize)
            true
        } catch (e: Exception) {
            android.util.Log.e("MumbleBridge", "PCM-Uebergabe fehlgeschlagen", e)
            false
        }
    }

    fun stopBridgeAudio() {
        val humla = service as? HumlaService ?: return
        try {
            humla.stopBridgeAudio()
        } catch (e: Exception) {
            android.util.Log.e("MumbleBridge", "Bridge-PTT-Stopp fehlgeschlagen", e)
        }
    }

    fun startTalking(): Boolean {
        val activeService = service ?: return false
        if (!activeService.isConnected) return false

        return try {
            activeService.HumlaSession().setTalkingState(true)
            Log.i("FGW-Mumble", "Mumble-PTT eingeschaltet")
            true
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Mumble-PTT Start fehlgeschlagen", e)
            try {
                activeService.HumlaSession().setTalkingState(false)
            } catch (_: Exception) {}
            false
        }
    }

    fun stopTalking() {
        try {
            service?.HumlaSession()?.setTalkingState(false)
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Mumble-PTT Stop fehlgeschlagen", e)
        }
    }

    fun joinChannel(channelId: Int): Boolean {
        val activeService = service
        if (activeService?.isConnected != true) {
            onStatusChanged("Raumwechsel nicht möglich: Mumble ist getrennt")
            return false
        }

        return try {
            activeService.HumlaSession().joinChannel(channelId)
            Log.i("FGW-Mumble", "Raumwechsel angefordert: $channelId")
            true
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Raumwechsel fehlgeschlagen", e)
            onStatusChanged("Raumwechsel fehlgeschlagen: ${e.message}")
            false
        }
    }

    fun getChannelTree(): MumbleChannelItem? {
        val activeService = service ?: return null
        if (!activeService.isConnected) return null

        return try {
            fun snapshot(channel: IChannel): MumbleChannelItem {
                return MumbleChannelItem(
                    id = channel.id,
                    name = channel.name,
                    users = channel.users
                        .sortedBy { it.name.lowercase() }
                        .map { user ->
                            MumbleUserItem(
                                sessionId = user.session,
                                name = user.name,
                                talkState = user.talkState.name,
                                registered = user.userId >= 0,
                                selfMuted = user.isSelfMuted,
                                selfDeafened = user.isSelfDeafened,
                                serverMuted = user.isMuted,
                                serverDeafened = user.isDeafened,
                                localMuted = user.isLocalMuted,
                                suppressed = user.isSuppressed
                            )
                        },
                    children = channel.subchannels
                        .sortedWith(compareBy<IChannel> { it.position }.thenBy { it.name })
                        .map { snapshot(it) }
                )
            }

            snapshot(activeService.HumlaSession().rootChannel)
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Strukturierte Raumliste fehlgeschlagen", e)
            null
        }
    }

    fun getChannelTreeText(): String {
        val activeService = service ?: return "Mumble-Dienst nicht bereit"
        if (!activeService.isConnected) return "Nicht mit Mumble verbunden"

        return try {
            val session = activeService.HumlaSession()
            val root = session.rootChannel
            val result = StringBuilder()

            fun appendChannel(channel: IChannel, depth: Int) {
                val indent = "  ".repeat(depth)
                result.append(indent).append("📁 ").append(channel.name).append("\n")

                channel.users.sortedBy { it.name.lowercase() }.forEach { user ->
                    result.append(indent).append("  👤 ")
                        .append(user.name).append("\n")
                }

                channel.subchannels.sortedWith(
                    compareBy<IChannel> { it.position }.thenBy { it.name }
                ).forEach { child ->
                    appendChannel(child, depth + 1)
                }
            }

            appendChannel(root, 0)
            result.toString().trimEnd()
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Raumbaum konnte nicht gelesen werden", e)
            "Mumble-Raumübersicht nicht verfügbar: ${e.message}"
        }
    }

    fun disconnect() {
        detachMumbleAcl()
        detachMumbleBanList()
        detachRegisteredUsers()
        cancelConnectTimeout()
        // Bewusste Trennung: keine automatische Wiederverbindung.
        manualDisconnect = true
        reconnectEnabled = false
        cancelMumbleReconnect()
        registrationPending = false
        otherRegistrationSessionId = null
        ++otherRegistrationRequestId
        rootRegistrationPermissionsConfirmed = false

        // Vor der manuellen Trennung die Gateway-Bridge und PTT
        // synchron über den vorhandenen Sicherheitsstopp beenden.
        onConnectionLost?.invoke()

        stopTalking()
        stopBridgeAudio()
        pendingCertificate = null
        pendingCertificateHost = null
        pendingCertificatePort = 0
        connectionInProgress = false
        pendingConnectIntent = null
        try {
            service?.disconnect()
            onStatusChanged("Mumble getrennt")
        } catch (e: Exception) {
            onStatusChanged("Trennen fehlgeschlagen: ${e.message}")
        }
    }

    fun certificateFingerprint(certificate: X509Certificate): String =
        certificateStore.fingerprint(certificate)

    fun previousCertificateFingerprint(host: String, port: Int): String? =
        certificateStore.getCertificate(host, port)?.let {
            certificateStore.fingerprint(it)
        }

    fun rejectCertificate(
        host: String,
        port: Int,
        certificate: X509Certificate
    ) {
        val pending = pendingCertificate ?: return
        if (host != pendingCertificateHost ||
            port != pendingCertificatePort ||
            !pending.encoded.contentEquals(certificate.encoded)
        ) return

        pendingCertificate = null
        pendingCertificateHost = null
        pendingCertificatePort = 0
        lastPassword = ""
        onStatusChanged("Mumble-Zertifikat abgelehnt – keine Verbindung")
    }

    fun acceptCertificate(
        host: String,
        port: Int,
        certificate: X509Certificate
    ) {
        if (connectionInProgress ||
            host != currentHost || port != currentPort ||
            host != pendingCertificateHost || port != pendingCertificatePort ||
            pendingCertificate == null ||
            !pendingCertificate!!.encoded.contentEquals(certificate.encoded)
        ) {
            onStatusChanged("Zertifikat gehört nicht zum aktuellen Mumble-Server")
            return
        }

        try {
            certificateStore.saveCertificate(host, port, certificate)
            pendingCertificate = null
            pendingCertificateHost = null
            pendingCertificatePort = 0
            onStatusChanged("Mumble-Zertifikat gespeichert – verbinde erneut …")
            connect(host, port, lastUsername, lastPassword)
        } catch (e: Exception) {
            onStatusChanged("Zertifikat konnte nicht gespeichert werden: ${e.message}")
        }
    }


    data class MumbleRegistrationInfo(
        val username: String,
        val sessionId: Int,
        val userId: Int
    ) {
        val isRegistered: Boolean
            get() = userId >= 0
    }

    fun getOwnRegistrationInfo(): MumbleRegistrationInfo? {
        val activeService = service ?: return null
        if (!activeService.isConnected) return null

        return try {
            val ownUser = activeService.HumlaSession().sessionUser
            MumbleRegistrationInfo(
                username = ownUser.name,
                sessionId = ownUser.session,
                userId = ownUser.userId
            )
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Registrierungsstatus nicht verfügbar", e)
            null
        }
    }

    fun requestOwnRegistration(): String {
        val activeService = service
        if (activeService?.isConnected != true) {
            return "Keine Mumble-Verbindung vorhanden"
        }

        return try {
            val session = activeService.HumlaSession()
            val ownUser = session.sessionUser

            if (ownUser.userId >= 0) {
                "Benutzer ${ownUser.name} ist bereits registriert (ID ${ownUser.userId})"
            } else if (registrationPending || otherRegistrationSessionId != null) {
                "Registrierung wurde bereits angefordert – warte auf Server"
            } else {
                registrationPending = true
                val requestId = ++registrationRequestId
                try {
                    session.registerUser(ownUser.session)
                } catch (e: Exception) {
                    registrationPending = false
                    throw e
                }
                val requestedSession = ownUser.session
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (registrationPending && registrationRequestId == requestId) {
                        val currentSession = runCatching {
                            service?.takeIf { it.isConnected }
                                ?.HumlaSession()?.sessionId
                        }.getOrNull()
                        if (currentSession == requestedSession) {
                            registrationPending = false
                            onRegistrationStatusChanged?.invoke(
                                "⏳ Keine Serverbestätigung nach 15 Sekunden. Bitte Status prüfen oder erneut versuchen."
                            )
                        }
                    }
                }, 15000L)
                Log.i(
                    "FGW-Mumble",
                    "Registrierung angefordert: ${ownUser.name}, Sitzung ${ownUser.session}"
                )
                "Registrierung für ${ownUser.name} angefordert – warte auf Server"
            }
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Registrierungsanforderung fehlgeschlagen", e)
            "Registrierung konnte nicht angefordert werden: ${e.message}"
        }
    }

    private var rootRegistrationPermissionsConfirmed = false

    fun hasMumbleModerationPermission(permission: Int): Boolean {
        val activeService = service ?: return false
        if (!activeService.isConnected || !rootRegistrationPermissionsConfirmed) {
            return false
        }

        val allowedPermissions = setOf(
            se.lublin.humla.net.Permissions.Kick,
            se.lublin.humla.net.Permissions.Ban,
            se.lublin.humla.net.Permissions.MuteDeafen,
            se.lublin.humla.net.Permissions.Move
        )
        if (permission !in allowedPermissions) return false

        return runCatching {
            val root = activeService.HumlaSession().rootChannel
            (root.permissions and permission) == permission
        }.getOrDefault(false)
    }

    fun canRegisterOtherUsers(): Boolean {
        val activeService = service ?: return false
        if (!activeService.isConnected ||
            !rootRegistrationPermissionsConfirmed
        ) return false

        return runCatching {
            val root = activeService.HumlaSession().rootChannel
            (root.permissions and 262144) != 0
        }.getOrDefault(false)
    }

    fun requestRegistrationPermissions() {
        // Bereits bestätigte Rechte nicht durch eine erneute Abfrage verlieren.
        // Beim Trennen der Verbindung werden sie weiterhin zurückgesetzt.
        val activeService = service ?: return
        if (!activeService.isConnected) {
            Log.w("FGW-Mumble", "Root-Rechte: Abfrage übersprungen, isConnected=false")
            return
        }

        Log.i("FGW-Mumble", "Root-Rechte: PermissionQuery für Kanal 0 wird gesendet")
        runCatching {
            activeService.HumlaSession().requestPermissions(0)
        }.onFailure {
            Log.w("FGW-Mumble", "Root-Berechtigungen nicht abrufbar", it)
        }
    }

    fun unregisterRegisteredUser(userId: Int): String {
        if (!canRegisterOtherUsers()) {
            return "⛔ Keine bestätigte Berechtigung zur Deregistrierung"
        }
        if (userId <= 0) {
            return "Ungültige Registrierungs-ID"
        }
        if (service?.isConnected != true) {
            return "Keine Mumble-Verbindung vorhanden"
        }

        return if (registeredUsers.unregisterUser(userId)) {
            "Deregistrierung für ID $userId angefordert"
        } else {
            "Deregistrierung konnte nicht gesendet werden"
        }
    }

    fun setMumbleUserServerAudioState(
        sessionId: Int,
        expectedName: String,
        expectedGeneration: Long,
        muted: Boolean? = null,
        deafened: Boolean? = null
    ): String {
        if (muted == null && deafened == null) {
            return "Keine Audioänderung ausgewählt"
        }

        if (getModerationConnectionGeneration() != expectedGeneration) {
            return "⛔ Mumble-Verbindung hat sich geändert"
        }

        if (!hasMumbleModerationPermission(
                se.lublin.humla.net.Permissions.MuteDeafen
            )
        ) {
            return "⛔ Keine bestätigte Mute/Deaf-Berechtigung"
        }

        val activeService = service ?: return "Keine Mumble-Verbindung"
        if (!activeService.isConnected) return "Keine Mumble-Verbindung"

        return try {
            val session = activeService.HumlaSession()

            if (sessionId == session.sessionId) {
                return "⛔ Eigenen Benutzer hier nicht administrieren"
            }

            val target = session.getUser(sessionId)
                ?: return "Benutzer ist nicht mehr verbunden"

            if (target.name != expectedName) {
                return "⛔ Benutzeridentität hat sich geändert"
            }

            if (getModerationConnectionGeneration() != expectedGeneration) {
                return "⛔ Mumble-Verbindung hat sich geändert"
            }

            val newMuted = muted ?: target.isMuted
            val newDeafened = deafened ?: target.isDeafened

            session.setMuteDeafState(
                sessionId,
                newMuted,
                newDeafened
            )

            Log.i(
                "FGW-Mumble",
                "Server-Audiostatus angefordert: ${target.name}, " +
                    "Mute=$newMuted, Deaf=$newDeafened"
            )

            "Audioänderung für ${target.name} angefordert"
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Server-Mute/Deaf fehlgeschlagen", e)
            "Audioänderung fehlgeschlagen: ${e.message}"
        }
    }

    fun kickMumbleUser(
        sessionId: Int,
        expectedName: String,
        expectedGeneration: Long,
        reason: String = ""
    ): String {
        if (getModerationConnectionGeneration() != expectedGeneration) {
            return "⛔ Mumble-Verbindung hat sich geändert"
        }
        if (!hasMumbleModerationPermission(
                se.lublin.humla.net.Permissions.Kick
            )
        ) {
            return "⛔ Keine bestätigte Kick-Berechtigung"
        }

        val activeService = service ?: return "Keine Mumble-Verbindung"
        if (!activeService.isConnected) return "Keine Mumble-Verbindung"

        return try {
            val session = activeService.HumlaSession()
            if (sessionId == session.sessionId) {
                return "⛔ Eigenen Benutzer nicht entfernen"
            }

            val target = session.getUser(sessionId)
                ?: return "Benutzer ist nicht mehr verbunden"

            if (target.name != expectedName) {
                return "⛔ Benutzeridentität hat sich geändert"
            }

            if (getModerationConnectionGeneration() != expectedGeneration) {
                return "⛔ Mumble-Verbindung hat sich geändert"
            }

            session.kickBanUser(sessionId, reason, false)

            Log.i(
                "FGW-Mumble",
                "Kick ohne Bann angefordert: ${target.name}, Sitzung $sessionId"
            )
            "Kick für ${target.name} angefordert"
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Mumble-Kick fehlgeschlagen", e)
            "Kick konnte nicht angefordert werden: ${e.message}"
        }
    }

    fun banMumbleUser(
        sessionId: Int,
        expectedName: String,
        expectedGeneration: Long,
        reason: String = ""
    ): String {
        if (getModerationConnectionGeneration() != expectedGeneration) {
            return "⛔ Mumble-Verbindung hat sich geändert"
        }
        if (!hasMumbleModerationPermission(
                se.lublin.humla.net.Permissions.Ban
            )
        ) {
            return "⛔ Keine bestätigte Bann-Berechtigung"
        }

        val activeService = service ?: return "Keine Mumble-Verbindung"
        if (!activeService.isConnected) return "Keine Mumble-Verbindung"

        return try {
            val session = activeService.HumlaSession()
            if (sessionId == session.sessionId) {
                return "⛔ Eigenen Benutzer nicht bannen"
            }

            val target = session.getUser(sessionId)
                ?: return "Benutzer ist nicht mehr verbunden"

            if (target.name != expectedName) {
                return "⛔ Benutzeridentität hat sich geändert"
            }

            if (getModerationConnectionGeneration() != expectedGeneration) {
                return "⛔ Mumble-Verbindung hat sich geändert"
            }

            session.kickBanUser(sessionId, reason, true)

            Log.i(
                "FGW-Mumble",
                "Bann angefordert: ${target.name}, Sitzung $sessionId"
            )
            "Bann für ${target.name} angefordert"
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Mumble-Bann fehlgeschlagen", e)
            "Bann konnte nicht angefordert werden: ${e.message}"
        }
    }

    fun requestOtherUserRegistration(sessionId: Int): String {
        if (!canRegisterOtherUsers()) {
            return "⛔ Keine bestätigte Berechtigung zum Registrieren anderer Benutzer"
        }
        val activeService = service
        if (activeService?.isConnected != true) {
            return "Keine Mumble-Verbindung vorhanden"
        }

        return try {
            val session = activeService.HumlaSession()
            val targetUser = session.getUser(sessionId)
                ?: return "Benutzer ist nicht mehr verbunden"

            if (sessionId == session.sessionId) {
                return "Für die Eigenregistrierung bitte die vorhandene Funktion verwenden"
            }
            if (targetUser.userId >= 0) {
                return "${targetUser.name} ist bereits registriert"
            }
            if (registrationPending || otherRegistrationSessionId != null) {
                return "Eine Mumble-Registrierung läuft bereits"
            }

            otherRegistrationSessionId = sessionId
            val requestId = ++otherRegistrationRequestId

            try {
                session.registerUser(sessionId)
            } catch (e: Exception) {
                otherRegistrationSessionId = null
                throw e
            }

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (otherRegistrationSessionId == sessionId &&
                    otherRegistrationRequestId == requestId
                ) {
                    otherRegistrationSessionId = null
                    onOtherUserRegistrationStatusChanged?.invoke(
                        "⏳ Keine Serverbestätigung für ${targetUser.name} nach 15 Sekunden."
                    )
                }
            }, 15000L)

            Log.i(
                "FGW-Mumble",
                "Fremdregistrierung angefordert: ${targetUser.name}, Sitzung $sessionId"
            )
            "Registrierung für ${targetUser.name} angefordert – warte auf Server"
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Fremdregistrierung fehlgeschlagen", e)
            "Registrierung konnte nicht angefordert werden: ${e.message}"
        }
    }

    data class MumbleSelfAudioState(
        val muted: Boolean,
        val deafened: Boolean
    )

    fun getSelfAudioState(): MumbleSelfAudioState? {
        return try {
            val active = service ?: return null
            if (!active.isConnected) return null
            val ownSessionId = active.HumlaSession().sessionId
            fun findOwnUser(
                channel: MumbleChannelItem
            ): MumbleUserItem? {
                channel.users.firstOrNull {
                    it.sessionId == ownSessionId
                }?.let { return it }
                channel.children.forEach { child ->
                    findOwnUser(child)?.let { return it }
                }
                return null
            }
            val ownUser = findOwnUser(getChannelTree() ?: return null)
                ?: return null
            MumbleSelfAudioState(
                muted = ownUser.selfMuted,
                deafened = ownUser.selfDeafened
            )
        } catch (e: Exception) {
            Log.w("FGW-Mumble", "Eigener Mumble-Audiostatus nicht verfügbar", e)
            null
        }
    }

    fun setSelfAudioState(muted: Boolean, deafened: Boolean): Boolean {
        val active = service as? HumlaService ?: return false
        if (!active.isConnected) return false

        return try {
            active.setSelfMuteDeafState(muted, deafened)
            true
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Mumble Mute/Deaf fehlgeschlagen", e)
            false
        }
    }

    /**
     * Prüft die Voraussetzungen für eine geplante Bannlistenänderung.
     * Diese Funktion führt ausdrücklich keinen Schreibvorgang aus.
     *
     * Eine erfolgreiche Prüfung ersetzt keine frische Serverabfrage
     * unmittelbar vor einer späteren Übertragung.
     */
    fun checkMumbleBanChangeSafety(
        expectedGeneration: Long,
        expectedServer: Pair<String, Int>,
        expectedListVersion: Long
    ): String? = MumbleBanChangeSafety.check(
        connected = isConnected(),
        expectedGeneration = expectedGeneration,
        currentGeneration = moderationConnectionGeneration,
        expectedServer = expectedServer,
        currentServer = getConnectedServer(),
        hasBanPermission = hasMumbleModerationPermission(
            se.lublin.humla.net.Permissions.Ban
        ),
        hasValidBanList = mumbleBanList.isBanListWriteSafe(),
        expectedListVersion = expectedListVersion,
        currentListVersion = mumbleBanList.getReceivedListVersion()
    )

    /**
     * Erstellt einen Sicherheitskontext für eine geplante
     * Bannlistenänderung. Führt keinen Schreibvorgang aus.
     */
    /**
     * Prüft einen zuvor gespeicherten Sicherheitskontext erneut.
     *
     * Eine erfolgreiche Prüfung ist noch keine Schreibfreigabe.
     * Vor einem Schreibvorgang ist eine frische Serverabfrage nötig.
     */
    /**
     * Bereitet eine neue Mumble-Sperre ausschließlich lokal vor.
     * Es wird keine Bannliste an den Server übertragen.
     */
    /**
     * Bereitet das Entfernen einer Mumble-Sperre ausschließlich lokal vor.
     * Es wird keine Bannliste an den Server übertragen.
     */
    /**
     * Sendet eine bereits vorbereitete und ausdrücklich bestätigte
     * Bannlistenänderung. Keine automatische Wiederholung.
     *
     * true bedeutet nur: Übertragung ausgelöst.
     * Die tatsächliche Serverliste muss danach neu geladen werden.
     */
    internal fun sendMumblePreparedBanChange(
        change: MumblePreparedBanChange
    ): Boolean {
        if (validateMumbleBanChangeContext(change.context) != null) {
            return false
        }

        val snapshot = mumbleBanList.getBanListSnapshot()
            ?: return false

        if (snapshot.first != change.context.banListVersion ||
            snapshot.second != change.originalBans
        ) {
            return false
        }

        if (validateMumbleBanChangeContext(change.context) != null) {
            return false
        }

        return mumbleBanList.sendPreparedBanList(
            expectedVersion = change.context.banListVersion,
            entries = change.preparedBans
        )
    }

    internal fun prepareMumbleBanRemoval(
        selectedBan: Mumble.BanList.BanEntry
    ): MumblePreparedBanChange? {
        val context = createMumbleBanChangeContext()
            ?: return null

        val snapshot = mumbleBanList.getBanListSnapshot()
            ?: return null

        if (snapshot.first != context.banListVersion) {
            return null
        }

        if (validateMumbleBanChangeContext(context) != null) {
            return null
        }

        val originalBans = snapshot.second
        val preparedBans = try {
            MumbleBanList.prepareBanRemoval(
                existing = originalBans,
                selected = selectedBan
            )
        } catch (e: IllegalArgumentException) {
            return null
        }

        return MumblePreparedBanChange(
            context = context,
            operation = MumblePreparedBanChange.Operation.REMOVE,
            originalBans = originalBans,
            preparedBans = preparedBans
        )
    }

    internal fun prepareMumbleBanAddition(
        newBan: Mumble.BanList.BanEntry
    ): MumblePreparedBanChange? {
        val context = createMumbleBanChangeContext()
            ?: return null

        val snapshot = mumbleBanList.getBanListSnapshot()
            ?: return null

        if (snapshot.first != context.banListVersion) {
            return null
        }

        if (validateMumbleBanChangeContext(context) != null) {
            return null
        }

        val originalBans = snapshot.second
        val preparedBans = MumbleBanList.prepareBanAddition(
            existing = originalBans,
            newBan = newBan
        )

        return MumblePreparedBanChange(
            context = context,
            operation = MumblePreparedBanChange.Operation.ADD,
            originalBans = originalBans,
            preparedBans = preparedBans
        )
    }

    internal fun validateMumbleBanChangeContext(
        context: MumbleBanChangeContext
    ): String? = checkMumbleBanChangeSafety(
        expectedGeneration = context.connectionGeneration,
        expectedServer = context.server,
        expectedListVersion = context.banListVersion
    )

    internal fun createMumbleBanChangeContext(): MumbleBanChangeContext? {
        val generation = getModerationConnectionGeneration()
            ?: return null
        val server = getConnectedServer()
            ?: return null
        val version = mumbleBanList.getReceivedListVersion()
            ?: return null

        if (
            checkMumbleBanChangeSafety(
                expectedGeneration = generation,
                expectedServer = server,
                expectedListVersion = version
            ) != null
        ) {
            return null
        }

        return MumbleBanChangeContext(
            connectionGeneration = generation,
            server = server,
            banListVersion = version
        )
    }

    fun getModerationConnectionGeneration(): Long? =
        if (isConnected()) moderationConnectionGeneration else null

    fun getConnectedServer(): Pair<String, Int>? =
        if (isConnected() && !currentHost.isNullOrBlank() && currentPort in 1..65535)
            Pair(currentHost!!, currentPort)
        else null

    fun isConnected(): Boolean = service?.isConnected ?: false

    fun isConnecting(): Boolean = connectionInProgress

    fun hasActiveConnectionAttempt(): Boolean =
        isConnected() || connectionInProgress ||
            pendingCertificate != null ||
            reconnectTask != null

    fun release() {
        detachMumbleAcl()
        detachMumbleBanList()
        detachRegisteredUsers()
        cancelConnectTimeout()
        cancelMumbleReconnect()
        stopTalking()
        if (bound || binding) {
            service?.unregisterObserver(observer)
            context.unbindService(connection)
            bound = false
            binding = false
            service = null
        }
    }
}
