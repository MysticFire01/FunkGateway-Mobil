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
    var onChannelMembershipChanged: (() -> Unit)? = null
    var onRegistrationStatusChanged: ((String) -> Unit)? = null
    private var registrationPending = false
    private var registrationRequestId = 0L

    private var service: IHumlaService? = null

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
    private var lastUsername: String = ""
    private var lastPassword: String = ""
    // Automatische Mumble-Wiederverbindung.
    private val reconnectHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectBackoff = ReconnectBackoff()
    private var reconnectEnabled = false
    private var reconnectGeneration = 0L
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
            onStatusChanged("Mit Mumble verbunden")
        }

        override fun onDisconnected(error: HumlaException?) {
            cancelConnectTimeout()
            registrationPending = false
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

        override fun onChannelPermissionsUpdated(channel: IChannel) {}

        override fun onUserConnected(user: IUser) {
            notifyRoomTreeChanged()
        }
        override fun onUserStateUpdated(user: IUser) {
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
            cancelConnectTimeout()
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
        cancelConnectTimeout()
        // Bewusste Trennung: keine automatische Wiederverbindung.
        manualDisconnect = true
        reconnectEnabled = false
        cancelMumbleReconnect()
        registrationPending = false
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
            } else if (registrationPending) {
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

    fun isConnected(): Boolean = service?.isConnected ?: false

    fun isConnecting(): Boolean = connectionInProgress

    fun hasActiveConnectionAttempt(): Boolean =
        isConnected() || connectionInProgress ||
            pendingCertificate != null ||
            reconnectTask != null

    fun release() {
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
