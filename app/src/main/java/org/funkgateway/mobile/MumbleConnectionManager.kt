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
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onCertificateUntrusted: (String, Int, X509Certificate) -> Unit = { _, _, _ -> }
) {
    var onTalkStateChanged: ((Int, String) -> Unit)? = null
    var onChannelMembershipChanged: (() -> Unit)? = null
    var onRegistrationStatusChanged: ((String) -> Unit)? = null
    private var registrationPending = false
    private var registrationRequestId = 0L

    private var service: IHumlaService? = null
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

    private var pendingCertificate: X509Certificate? = null
    private var pendingCertificateHost: String? = null
    private var pendingCertificatePort: Int = 0

    private val observer = object : IHumlaObserver {
        override fun onConnecting() {
            onStatusChanged("Verbinde mit Mumble …")
        }

        override fun onConnected() {
            connectionInProgress = false
            onStatusChanged("Mit Mumble verbunden")
        }

        override fun onDisconnected(error: HumlaException?) {
            registrationPending = false
            connectionInProgress = false
            Log.e("FGW-Mumble", "Verbindung getrennt", error)
            if (pendingCertificate != null) {
                onStatusChanged("Mumble-Zertifikat wartet auf Bestätigung")
                return
            }
            onStatusChanged(
                if (error == null) "Mumble getrennt"
                else "Mumble getrennt: ${error.message}"
            )
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

        override fun onChannelAdded(channel: IChannel) {}
        override fun onChannelStateUpdated(channel: IChannel) {}
        override fun onChannelRemoved(channel: IChannel) {}
        override fun onChannelPermissionsUpdated(channel: IChannel) {}
        override fun onUserConnected(user: IUser) {}
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
        override fun onUserRemoved(user: IUser, reason: String?) {}
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
            applyPlaybackVolume()
            bound = true
            binding = false
            service?.registerObserver(observer)
            val pending = pendingConnectIntent
            pendingConnectIntent = null
            if (pending != null) {
                context.startService(pending)
                return
            }
            onStatusChanged(
                if (service?.isConnected == true) "Mit Mumble verbunden"
                else "Mumble-Dienst bereit"
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            binding = false
            connectionInProgress = false
            onStatusChanged("Mumble-Dienst getrennt")
        }
    }

    fun connect(host: String, port: Int, username: String, password: String) {
        if (host.isBlank() || username.isBlank() || port !in 1..65535) {
            onStatusChanged("Bitte Server, Port und Benutzername prüfen")
            return
        }

        if (connectionInProgress) {
            onStatusChanged("Mumble-Verbindung läuft bereits – bitte warten")
            return
        }

        pendingCertificate = null
        pendingCertificateHost = null
        pendingCertificatePort = 0
        connectionInProgress = true
        currentHost = host.trim()
        currentPort = port
        lastUsername = username.trim()
        lastPassword = password

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
            if (bound) {
                context.startService(intent)
            } else {
                pendingConnectIntent = intent
                if (!binding) {
                    binding = context.bindService(
                        Intent(context, HumlaService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE
                    )
                    if (!binding) {
                        pendingConnectIntent = null
                        connectionInProgress = false
                        onStatusChanged("Mumble-Dienst konnte nicht gebunden werden")
                    }
                }
            }
        } catch (e: Exception) {
            connectionInProgress = false
            onStatusChanged("Mumble-Fehler: ${e.message}")
        }
    }

    data class MumbleUserItem(
        val sessionId: Int,
        val name: String,
        val talkState: String
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
                        .map { MumbleUserItem(it.session, it.name, it.talkState.name) },
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
        registrationPending = false
        stopTalking()
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

    fun isConnected(): Boolean = service?.isConnected ?: false

    fun release() {
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
