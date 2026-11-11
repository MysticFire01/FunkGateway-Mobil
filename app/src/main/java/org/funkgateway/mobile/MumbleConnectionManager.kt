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
import se.lublin.humla.net.HumlaTCPMessageType

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
    // Ein Kanal-Erstellungsauftrag pro aktiver Mumble-Verbindung.
    // Erst eine passende Servermeldung bestätigt die Erstellung.
    private data class PendingChannelCreation(
        val parentId: Int,
        val name: String,
        val temporary: Boolean,
        val connectionGeneration: Long,
        val startedAt: Long
    )

    private var pendingChannelCreation: PendingChannelCreation? = null

    var onMumbleChannelCreationStatusChanged: ((String) -> Unit)? = null

    // Erwartete Serverwerte nach dem Bearbeiten eines vorhandenen Kanals.
    private data class PendingChannelEdit(
        val channelId: Int,
        val name: String,
        val description: String,
        val position: Int,
        val connectionGeneration: Long,
        val startedAt: Long
    )

    private var pendingChannelEdit: PendingChannelEdit? = null

    data class MumbleChatMessage(
        val senderSessionId: Int,
        val senderName: String,
        val text: String,
        val receivedAt: Long,
        val targetChannelIds: List<Int>,
        val targetTreeIds: List<Int>,
        val targetUserSessionIds: List<Int>
    )

    var onMumbleChatMessageReceived: ((MumbleChatMessage) -> Unit)? = null

    private data class PendingChannelDeletion(
        val channelId: Int,
        val name: String,
        val connectionGeneration: Long,
        val startedAt: Long
    )

    private var pendingChannelDeletion: PendingChannelDeletion? = null

    private data class PendingChannelMove(
        val channelId: Int,
        val parentId: Int,
        val name: String,
        val generation: Long
    )

    private var pendingChannelMove: PendingChannelMove? = null

    var onMumbleChannelMoveStatusChanged: ((String) -> Unit)? = null

    var onMumbleChannelDeletionStatusChanged: ((String) -> Unit)? = null


    // Eigene Berechtigungsanfrage für die Kanalbearbeitung.
    // Getrennt von der bereits funktionierenden ACL-Schreibfreigabe.
    private data class ChannelEditPermissionRequest(
        val channelId: Int,
        val connectionGeneration: Long,
        val startedAt: Long,
        val confirmed: Boolean = false
    )

    private var channelEditPermissionRequest:
        ChannelEditPermissionRequest? = null

    // Schutzfrist gegen verspätete Antworten nach einem Abbruch.
    private var channelEditPermissionCancelledAt = 0L
    private var channelEditPermissionCancelledGeneration = -1L


    var onMumbleChannelEditStatusChanged: ((String) -> Unit)? = null

    private fun cancelPendingChannelEdit(reason: String) {
        val pending = pendingChannelEdit ?: return
        pendingChannelEdit = null

        Log.w("FGW-Mumble", "Kanalbearbeitung abgebrochen: $reason")

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onMumbleChannelEditStatusChanged?.invoke(
                "⚠️ Kanalbearbeitung '${pending.name}': $reason"
            )
        }
    }

    private fun scheduleChannelEditTimeout(request: PendingChannelEdit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (pendingChannelEdit === request) {
                pendingChannelEdit = null

                onMumbleChannelEditStatusChanged?.invoke(
                    "⏳ Keine Serverbestätigung für '${request.name}' " +
                        "innerhalb von 15 Sekunden. Bitte Kanal erneut " +
                        "abrufen, bevor du Änderungen wiederholst."
                )
            }
        }, 15000L)
    }

    private fun cancelPendingChannelCreation(reason: String) {
        val pending = pendingChannelCreation ?: return
        pendingChannelCreation = null

        Log.w(
            "FGW-Mumble",
            "Kanalerstellung abgebrochen: ${pending.name} – $reason"
        )

        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).post {
            onMumbleChannelCreationStatusChanged?.invoke(
                "⚠️ Kanalerstellung '${pending.name}': $reason"
            )
        }
    }

    var onRegistrationStatusChanged: ((String) -> Unit)? = null
    private fun scheduleChannelCreationTimeout(
        request: PendingChannelCreation
    ) {
        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).postDelayed({
            if (pendingChannelCreation === request) {
                pendingChannelCreation = null

                Log.w(
                    "FGW-Mumble",
                    "Keine Serverbestätigung für Kanalerstellung: ${request.name}"
                )

                onMumbleChannelCreationStatusChanged?.invoke(
                    "⏳ Keine Serverbestätigung für '${request.name}' " +
                        "innerhalb von 15 Sekunden. Bitte Kanalbaum prüfen, " +
                        "bevor du die Erstellung wiederholst."
                )
            }
        }, 15000L)
    }

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

    // Getrennte Zustandsverwaltung für spätere ACL-Nachkontrollen.
    // Noch keine Verbindung zum Netzwerkempfang oder Schreibtransport.
    private val aclWriteVerificationState =
        MumbleAclWriteVerificationState()

    // Erwarteter ACL-Entwurf für genau einen Nachkontrollvorgang.
    // Die ID verhindert eine Zuordnung zu einem älteren Vorgang.
    private data class PendingAclWriteVerification(
        val verificationId: Long,
        val expectedContent: MumbleAclWriteVerification.ExpectedContent
    )

    private var pendingAclWriteVerification:
        PendingAclWriteVerification? = null

    fun getMumbleAclWriteVerificationState():
        MumbleAclWriteVerificationState.Snapshot =
        aclWriteVerificationState.snapshot()

    private fun scheduleMumbleAclWriteVerificationTimeout(
        verificationId: Long
    ) {
        reconnectHandler.postDelayed({
            if (aclWriteVerificationState.timeout(verificationId)) {
                pendingAclWriteVerification = null

                onMumbleAclWriteVerificationTimeout?.invoke(verificationId)

                Log.w(
                    "FGW-Mumble-ACL",
                    "ACL-Nachkontrolle abgelaufen: " +
                        "keine passende Serverantwort innerhalb von 15 Sekunden."
                )
            }
        }, 15000L)
    }

    private fun startMumbleAclWriteVerification(
        expectedContent: MumbleAclWriteVerification.ExpectedContent,
        expectedGeneration: Long,
        expectedAclVersion: Long
    ): Long? {
        if (!isConnected()) return null

        if (aclWriteVerificationState.snapshot().status in setOf(
                MumbleAclWriteVerificationState.Status.ARMED,
                MumbleAclWriteVerificationState.Status.PENDING
            )
        ) return null

        val generation =
            getModerationConnectionGeneration() ?: return null

        val version =
            getMumbleAclReceiveVersion() ?: return null

        if (generation < 0L ||
            generation != expectedGeneration ||
            expectedContent.channelId < 0 ||
            version <= 0L ||
            version != expectedAclVersion
        ) return null

        val verification = aclWriteVerificationState.start(
            generation,
            expectedContent.channelId,
            version
        )

        pendingAclWriteVerification = PendingAclWriteVerification(
            verification.verificationId,
            expectedContent
        )

        // Zeitüberwachung erst nach erfolgreicher
        // Übergabe der Schreibnachricht starten.
        return verification.verificationId
    }

    /**
     * Bricht ausschließlich die angegebene ACL-Nachkontrolle ab.
     * Andere oder bereits abgeschlossene Vorgänge bleiben unberührt.
     */
    private fun cancelMumbleAclWriteVerification(
        verificationId: Long
    ) {
        val current = aclWriteVerificationState.snapshot()
        if (current.status !in setOf(
                MumbleAclWriteVerificationState.Status.ARMED,
                MumbleAclWriteVerificationState.Status.PENDING
            ) ||
            current.verificationId != verificationId
        ) return

        aclWriteVerificationState.reset()

        if (pendingAclWriteVerification?.verificationId ==
            verificationId
        ) {
            pendingAclWriteVerification = null
        }

        Log.w(
            "FGW-Mumble-ACL",
            "ACL-Nachkontrolle abgebrochen: Schreibvorgang nicht abgeschlossen."
        )
    }

    /**
     * Wertet eine empfangene ACL nur bei laufender Nachkontrolle aus.
     * Eine Inhaltsübereinstimmung ist keine eindeutige
     * Bestätigung eines bestimmten Schreibvorgangs.
     */
    private fun observeMumbleAclWriteVerification(
        received: MumbleAclManager.AclSnapshot,
        receivedVersion: Long,
        expectedContent: MumbleAclWriteVerification.ExpectedContent
    ): Boolean {
        val pending = aclWriteVerificationState.snapshot()

        if (pending.status !=
            MumbleAclWriteVerificationState.Status.PENDING
        ) return false

        if (receivedVersion <= 0L) return false

        val context = MumbleAclWriteVerification.Context(
            connected = isConnected(),
            expectedGeneration = pending.connectionGeneration,
            currentGeneration =
                getModerationConnectionGeneration() ?: -1L,
            expectedChannelId = pending.channelId,
            receivedChannelId = received.channelId,
            versionBeforeWrite = pending.versionBeforeWrite,
            receivedVersion = receivedVersion
        )

        val completed = aclWriteVerificationState.observe(
            pending.verificationId,
            context,
            MumbleAclWriteVerification.matchesExpectedContent(
                expectedContent,
                received
            )
        )

        if (completed) {
            pendingAclWriteVerification = null
        }

        return completed
    }

    var onMumbleAclReceived:
        ((MumbleAclManager.VersionedAclSnapshot) -> Unit)? = null

    var onMumbleAclConnectionLost: (() -> Unit)? = null

    // Rückmeldung nur für eine tatsächlich abgelaufene Schreibnachkontrolle.
    var onMumbleAclWriteVerificationTimeout: ((Long) -> Unit)? = null

    // Keine passende ACL-Nachricht innerhalb der Wartezeit beobachtet.
    var onMumbleAclRequestTimeout: ((Int, Long) -> Unit)? = null

    fun getMumbleAclRequestState(): MumbleAclRequestState.Snapshot =
        mumbleAclManager.getAclRequestState()

    fun requestMumbleAcl(channelId: Int): Boolean {
        if (!isConnected()) return false
        return mumbleAclManager.requestAcl(channelId)
    }

    private fun attachMumbleAcl() {
        val humla = service as? HumlaService ?: return
        val generation = moderationConnectionGeneration
        val expectedConnection = humla.connection

        mumbleAclManager.onAclReceived = { received ->
            val snapshot = received.snapshot
            reconnectHandler.post {
                if (
                    service === humla &&
                    service?.isConnected == true &&
                    moderationConnectionGeneration == generation &&
                    humla.connection === expectedConnection
                ) {
                    val pending = aclWriteVerificationState.snapshot()
                    val expected = pendingAclWriteVerification

                    if (
                        pending.status ==
                            MumbleAclWriteVerificationState.Status.PENDING &&
                        expected != null &&
                        expected.verificationId == pending.verificationId
                    ) {
                        observeMumbleAclWriteVerification(
                            snapshot,
                            received.version,
                            expected.expectedContent
                        )
                    }

                    onMumbleAclReceived?.invoke(received)
                }
            }
        }

        mumbleAclManager.onAclRequestTimeout = { channelId, requestId ->
            reconnectHandler.post {
                val request = mumbleAclManager.getAclRequestState()
                if (
                    service === humla &&
                    service?.isConnected == true &&
                    moderationConnectionGeneration == generation &&
                    humla.connection === expectedConnection &&
                    request.status == MumbleAclRequestState.Status.TIMED_OUT &&
                    request.channelId == channelId &&
                    request.requestId == requestId
                ) {
                    onMumbleAclRequestTimeout?.invoke(channelId, requestId)
                }
            }
        }

        mumbleAclManager.onPermissionQueryReceived = { message ->
            reconnectHandler.post {
                if (
                    service === humla &&
                    service?.isConnected == true &&
                    moderationConnectionGeneration == generation &&
                    humla.connection === expectedConnection
                ) {
                    if (message.hasFlush() && message.flush) {
                        channelEditPermissionRequest = null
                        clearMumbleAclWritePermission()
                    } else {
                        val editRequest = channelEditPermissionRequest

                        if (editRequest != null &&
                            message.hasChannelId() &&
                            message.channelId == editRequest.channelId &&
                            message.hasPermissions() &&
                            editRequest.connectionGeneration == generation
                        ) {
                            val age =
                                android.os.SystemClock.elapsedRealtime() -
                                    editRequest.startedAt

                            channelEditPermissionRequest =
                                if (age in 0L..15000L) {
                                    editRequest.copy(
                                        confirmed = (
                                            message.permissions and
                                                se.lublin.humla.net.Permissions.Write
                                            ) != 0
                                    )
                                } else {
                                    null
                                }

                            Log.i(
                                "FGW-Mumble",
                                "Kanalbearbeitung: Write-Recht vom Server " +
                                    "geprüft für Kanal ${editRequest.channelId}"
                            )
                        }
                    }

                    if (message.hasFlush() && message.flush) {
                        // ACL-Berechtigung bereits zurückgesetzt.
                    } else if (
                        message.hasChannelId() &&
                        message.channelId == aclPermissionChannelId &&
                        aclPermissionGeneration == generation &&
                        message.hasPermissions()
                    ) {
                        val age = android.os.SystemClock.elapsedRealtime() -
                            aclPermissionRequestedAt

                        if (aclPermissionRequestedAt <= 0L ||
                            age !in 0L..15000L
                        ) {
                            clearMumbleAclWritePermission()
                            Log.w(
                                "FGW-Mumble-ACL",
                                "Verspätete ACL-Berechtigungsantwort verworfen."
                            )
                            return@post
                        }

                        val permissionCheck = MumbleAclWritePermissionSafety.check(
                            MumbleAclWritePermissionSafety.Context(
                                connected = service?.isConnected == true,
                                connectionGeneration = moderationConnectionGeneration,
                                expectedGeneration = aclPermissionGeneration,
                                requestedChannelId = aclPermissionChannelId ?: -1,
                                responseChannelId = message.channelId,
                                hasPermissionMask = message.hasPermissions(),
                                permissionMask = message.permissions,
                                flush = message.hasFlush() && message.flush
                            )
                        )

                        // Zeitlich begrenzter Berechtigungsstatus.
                        // Keine automatische Freigabe einer ACL-Änderung:
                        // PermissionQuery besitzt keine Anfragekennung.
                        aclWritePermissionConfirmed =
                            permissionCheck.permissionReported

                        Log.i(
                            "FGW-Mumble-ACL",
                            "ACL-Schreibrecht gemeldet: ${permissionCheck.permissionReported}; " +
                                "Gruende: ${permissionCheck.reasons.joinToString()}"
                        )
                    }
                }
            }
        }

        mumbleAclManager.attach(expectedConnection)
    }

    private fun detachMumbleAcl() {
        channelEditPermissionRequest = null
        channelEditPermissionCancelledAt = 0L
        channelEditPermissionCancelledGeneration = -1L

        // Laufende ACL-Nachkontrollen bei Verbindungswechsel
        // oder Trennung sofort ungültig machen.
        aclWriteVerificationState.reset()
        pendingAclWriteVerification = null
        mumbleAclManager.detach()
        mumbleAclManager.onAclReceived = null
        mumbleAclManager.onPermissionQueryReceived = null
        mumbleAclManager.onAclRequestTimeout = null
        clearMumbleAclWritePermission()
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
            mumbleChatSessionGeneration.incrementAndGet()
            onMumbleChatSessionEnded?.invoke()
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

    // Unabhängiger Callback zum Beenden der Mumble-Chat-Sitzung.
    var onMumbleChatSessionEnded: (() -> Unit)? = null

    // Eindeutige Generation jeder Mumble-Chat-Sitzung.
    private val mumbleChatSessionGeneration =
        java.util.concurrent.atomic.AtomicLong(0L)



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
                mumbleChatSessionGeneration.incrementAndGet()
                onMumbleChatSessionEnded?.invoke()
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
            mumbleChatSessionGeneration.incrementAndGet()
            cancelMumbleReconnect()
            ++moderationConnectionGeneration
            clearMumbleAclWritePermission()

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
            pendingChannelMove = null
            pendingChannelDeletion = null
            channelEditPermissionRequest = null
            cancelPendingChannelEdit("Verbindung getrennt")
            cancelPendingChannelCreation("Mumble-Verbindung getrennt")
            detachMumbleAcl()
            detachMumbleBanList()
            detachRegisteredUsers()
            cancelConnectTimeout()
            registrationPending = false
            otherRegistrationSessionId = null
            ++otherRegistrationRequestId
            rootRegistrationPermissionsConfirmed = false
            clearMumbleAclWritePermission()
            connectionInProgress = false

            // Keine alte Aussendung nach einer Trennung fortsetzen.
            stopTalking()
            stopBridgeAudio()
            mumbleChatSessionGeneration.incrementAndGet()
            onMumbleChatSessionEnded?.invoke()
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
            val pending = pendingChannelCreation

            if (pending != null &&
                service?.isConnected == true &&
                pending.connectionGeneration == moderationConnectionGeneration &&
                channel.id > 0 &&
                channel.parent?.id == pending.parentId &&
                channel.name == pending.name &&
                channel.isTemporary == pending.temporary
            ) {
                pendingChannelCreation = null

                Log.i(
                    "FGW-Mumble",
                    "Kanalerstellung vom Server bestätigt: " +
                        "${channel.name} (ID ${channel.id})"
                )

                android.os.Handler(
                    android.os.Looper.getMainLooper()
                ).post {
                    onMumbleChannelCreationStatusChanged?.invoke(
                        "✅ Kanal '${channel.name}' wurde vom Server bestätigt."
                    )
                }
            }

            notifyRoomTreeChanged()
        }

        override fun onChannelStateUpdated(channel: IChannel) {
            val move = pendingChannelMove
            if (move != null &&
                service?.isConnected == true &&
                move.generation == moderationConnectionGeneration &&
                channel.id == move.channelId &&
                channel.parent?.id == move.parentId
            ) {
                pendingChannelMove = null
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onMumbleChannelMoveStatusChanged?.invoke(
                        "✅ Kanal '${move.name}' wurde verschoben."
                    )
                }
            }

            val pending = pendingChannelEdit

            if (pending != null &&
                service?.isConnected == true &&
                pending.connectionGeneration == moderationConnectionGeneration &&
                channel.id == pending.channelId &&
                channel.name == pending.name &&
                channel.description == pending.description &&
                channel.position == pending.position
            ) {
                pendingChannelEdit = null

                Log.i(
                    "FGW-Mumble",
                    "Kanalbearbeitung vom Server bestätigt: " +
                        "${channel.name} (ID ${channel.id})"
                )

                android.os.Handler(
                    android.os.Looper.getMainLooper()
                ).post {
                    onMumbleChannelEditStatusChanged?.invoke(
                        "✅ Kanal '${channel.name}' wurde erfolgreich geändert."
                    )
                }
            }

            notifyRoomTreeChanged()
        }

        override fun onChannelRemoved(channel: IChannel) {
            val pending = pendingChannelDeletion
            if (pending != null &&
                pending.channelId == channel.id &&
                pending.connectionGeneration == moderationConnectionGeneration
            ) {
                pendingChannelDeletion = null
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onMumbleChannelDeletionStatusChanged?.invoke(
                        "✅ Kanal '${pending.name}' wurde gelöscht."
                    )
                }
            }
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
        override fun onMessageLogged(message: IMessage) {
            // Generation vor der Verarbeitung festhalten.
            val messageGeneration = mumbleChatSessionGeneration.get()
            val messageConnection =
                (service as? HumlaService)?.connection ?: return

            if (manualDisconnect ||
                connectionInProgress ||
                service?.isConnected != true
            ) return

            val received = MumbleChatMessage(
                senderSessionId = message.actor,
                senderName = message.actorName ?: "Unbekannt",
                text = android.text.Html.fromHtml(
                    message.message ?: "",
                    android.text.Html.FROM_HTML_MODE_COMPACT
                ).toString().trimEnd(),
                receivedAt = message.receivedTime,
                targetChannelIds = message.targetChannels
                    ?.map { it.id }.orEmpty(),
                targetTreeIds = message.targetTrees
                    ?.map { it.id }.orEmpty(),
                targetUserSessionIds = message.targetUsers
                    ?.map { it.session }.orEmpty()
            )

            // Erst auf dem Hauptthread die Sitzung erneut prüfen.
            // Dadurch kann ein alter Empfang nicht nach der
            // Chat-Bereinigung erneut im Speicher landen.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (messageGeneration != mumbleChatSessionGeneration.get() ||
                    (service as? HumlaService)?.connection !== messageConnection ||
                    manualDisconnect ||
                    connectionInProgress ||
                    service?.isConnected != true
                ) {
                    Log.d(
                        "FGW-Mumble-Chat",
                        "Veraltete Mumble-Nachricht verworfen"
                    )
                    return@post
                }

                org.funkgateway.mobile.mumble.MumbleChatStore
                    .addIncoming(received)

                onMumbleChatMessageReceived?.invoke(received)
            }
        }
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
            clearMumbleAclWritePermission()
            registrationPending = false
            otherRegistrationSessionId = null
            ++otherRegistrationRequestId
            // Android behält die Dienstbindung bei und verbindet sie erneut.
            // Nur die Dienstreferenz und laufende Verbindung zurücksetzen.
            service = null
            connectionInProgress = false
            pendingConnectIntent = null
            mumbleChatSessionGeneration.incrementAndGet()
            onMumbleChatSessionEnded?.invoke()
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

    fun deleteMumbleChannel(
        channelId: Int,
        expectedGeneration: Long
    ): String {
        val activeService = service ?: return "Keine Mumble-Verbindung"

        if (!activeService.isConnected ||
            moderationConnectionGeneration != expectedGeneration
        ) return "Mumble-Verbindung hat sich geändert"

        if (channelId <= 0) return "Root-Kanal darf nicht gelöscht werden"

        return try {
            val session = activeService.HumlaSession()
            val channel = session.getChannel(channelId)
                ?: return "Kanal nicht mehr vorhanden"

            if (channel.subchannels.isNotEmpty()) {
                return "Kanal enthält Unterkanäle. Bitte zuerst diese löschen."
            }

            if (pendingChannelDeletion != null) {
                return "Eine Kanallöschung läuft bereits"
            }

            val request = PendingChannelDeletion(
                channelId = channelId,
                name = channel.name,
                connectionGeneration = expectedGeneration,
                startedAt = android.os.SystemClock.elapsedRealtime()
            )

            pendingChannelDeletion = request
            reconnectHandler.postDelayed({
                if (pendingChannelDeletion === request) {
                    pendingChannelDeletion = null
                    onMumbleChannelDeletionStatusChanged?.invoke(
                        "⏳ Keine Serverbestätigung für die Löschung von " +
                            "'${request.name}' erhalten. Bitte Kanalbestand prüfen."
                    )
                }
            }, 15000L)

            try {
                session.removeChannel(channelId)
                "Kanallöschung angefordert – Serverbestätigung steht aus"
            } catch (error: Exception) {
                pendingChannelDeletion = null
                Log.e("FGW-Mumble", "Kanallöschung fehlgeschlagen", error)
                "Kanallöschung konnte nicht gesendet werden"
            }
        } catch (error: Exception) {
            Log.e("FGW-Mumble", "Kanallöschung fehlgeschlagen", error)
            "Kanallöschung fehlgeschlagen"
        }
    }

    fun createMumbleChannel(
        parentId: Int,
        channelName: String,
        description: String,
        temporary: Boolean,
        expectedGeneration: Long
    ): String {
        val activeService = service ?: return "Keine Mumble-Verbindung"
        if (!activeService.isConnected ||
            moderationConnectionGeneration != expectedGeneration
        ) {
            return "Mumble-Verbindung hat sich geändert"
        }

        val name = channelName.trim()
        if (name.isEmpty() || name.length > 128) {
            return "Kanalname muss zwischen 1 und 128 Zeichen lang sein"
        }

        if (parentId < 0) return "Ungültiger übergeordneter Kanal"

        return try {
            val session = activeService.HumlaSession()
            val parent = session.getChannel(parentId)
                ?: return "Übergeordneter Kanal nicht vorhanden"

            val requiredPermission = if (temporary) {
                se.lublin.humla.net.Permissions.MakeTempChannel
            } else {
                se.lublin.humla.net.Permissions.MakeChannel
            }

            val ownUser = session.sessionUser
            val superUser = ownUser.userId == 0 &&
                ownUser.session == session.sessionId

            if (!superUser &&
                parent.permissions and requiredPermission == 0
            ) {
                return "Keine Berechtigung zum Erstellen dieses Kanaltyps"
            }

            if (service !== activeService ||
                !activeService.isConnected ||
                moderationConnectionGeneration != expectedGeneration
            ) {
                return "Mumble-Verbindung hat sich geändert"
            }

            if (pendingChannelCreation != null) {
                return "Eine Kanalerstellung läuft bereits"
            }

            val request = PendingChannelCreation(
                parentId = parentId,
                name = name,
                temporary = temporary,
                connectionGeneration = expectedGeneration,
                startedAt = android.os.SystemClock.elapsedRealtime()
            )

            pendingChannelCreation = request
            scheduleChannelCreationTimeout(request)

            try {
                session.createChannel(
                    parentId,
                    name,
                    description,
                    0,
                    temporary
                )
            } catch (e: Exception) {
                if (pendingChannelCreation === request) {
                    pendingChannelCreation = null
                }
                throw e
            }

            "Kanalerstellung angefordert – Serverbestätigung steht aus"
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Kanalerstellung fehlgeschlagen", e)
            "Kanalerstellung fehlgeschlagen: ${e.message}"
        }
    }

    data class MumbleChannelDetails(
        val id: Int,
        val name: String,
        val description: String,
        val position: Int,
        val temporary: Boolean,
        val parentId: Int?
    )

    fun getMumbleChannelDetails(channelId: Int): MumbleChannelDetails? {
        val activeService = service ?: return null
        if (!activeService.isConnected || channelId < 0) return null

        return runCatching {
            val channel = activeService.HumlaSession()
                .getChannel(channelId) ?: return null

            MumbleChannelDetails(
                id = channel.id,
                name = channel.name,
                description = channel.description ?: "",
                position = channel.position,
                temporary = channel.isTemporary,
                parentId = channel.parent?.id
            )
        }.getOrElse { error ->
            Log.w(
                "FGW-Mumble",
                "Kanaleigenschaften konnten nicht gelesen werden",
                error
            )
            null
        }
    }

    fun cancelMumbleChannelEditPermission(channelId: Int) {
        val request = channelEditPermissionRequest ?: return

        if (request.channelId == channelId) {
            channelEditPermissionCancelledAt =
                android.os.SystemClock.elapsedRealtime()
            channelEditPermissionCancelledGeneration =
                request.connectionGeneration
            channelEditPermissionRequest = null
            Log.i(
                "FGW-Mumble",
                "Berechtigungsanfrage für Kanal $channelId verworfen."
            )
        }
    }

    fun requestMumbleChannelEditPermission(channelId: Int): Boolean {
        val activeService = service ?: return false
        if (!activeService.isConnected || channelId <= 0) return false

        val now = android.os.SystemClock.elapsedRealtime()
        val generation = moderationConnectionGeneration

        // Nach einem Abbruch keine sofortige Wiederverwendung
        // möglicherweise noch eintreffender Serverantworten.
        if (channelEditPermissionCancelledGeneration == generation &&
            now - channelEditPermissionCancelledAt in 0L..15000L
        ) {
            Log.w(
                "FGW-Mumble",
                "Neue Kanalberechtigungsanfrage während Schutzfrist abgewiesen."
            )
            return false
        }

        // PermissionQuery besitzt keine Anfragekennung.
        // Deshalb keine überlappenden Kanalbearbeitungsanfragen.
        val previous = channelEditPermissionRequest
        if (previous != null &&
            previous.connectionGeneration == generation &&
            now - previous.startedAt in 0L..15000L
        ) {
            return false
        }

        // Auch ACL-Schreibanfragen nicht überlagern.
        if (aclPermissionChannelId != null &&
            aclPermissionGeneration == generation &&
            aclPermissionRequestedAt > 0L &&
            now - aclPermissionRequestedAt in 0L..15000L
        ) {
            return false
        }

        channelEditPermissionRequest = null

        return try {
            val session = activeService.HumlaSession()
            if (session.getChannel(channelId) == null) return false

            if (service !== activeService ||
                !activeService.isConnected ||
                moderationConnectionGeneration != generation
            ) return false

            val request = ChannelEditPermissionRequest(
                channelId = channelId,
                connectionGeneration = generation,
                startedAt = now
            )

            channelEditPermissionRequest = request
            session.requestPermissions(channelId)

            Log.i(
                "FGW-Mumble",
                "Schreibberechtigung für Kanal $channelId angefragt."
            )
            true
        } catch (e: Exception) {
            channelEditPermissionRequest = null
            Log.e(
                "FGW-Mumble",
                "Berechtigungsabfrage für Kanalbearbeitung fehlgeschlagen",
                e
            )
            false
        }
    }

    fun editMumbleChannel(
        channelId: Int,
        channelName: String,
        description: String,
        position: Int,
        expectedGeneration: Long
    ): String {
        val activeService = service as? HumlaService
            ?: return "Keine Mumble-Verbindung"

        if (!activeService.isConnected ||
            moderationConnectionGeneration != expectedGeneration
        ) return "Mumble-Verbindung hat sich geändert"

        if (channelId <= 0) return "Dieser Kanal darf nicht bearbeitet werden"

        val name = channelName.trim()
        if (name.isEmpty() || name.length > 128) {
            return "Kanalname muss zwischen 1 und 128 Zeichen lang sein"
        }

        if (position < 0) return "Ungültige Sortierposition"

        return try {
            val expectedConnection = activeService.connection
            val session = activeService.HumlaSession()
            val channel = session.getChannel(channelId)
                ?: return "Kanal nicht mehr vorhanden"

            val ownUser = session.sessionUser
            val superUser = ownUser.userId == 0 &&
                ownUser.session == session.sessionId

            val permissionRequest = channelEditPermissionRequest
            val now = android.os.SystemClock.elapsedRealtime()

            val permissionConfirmed = permissionRequest != null &&
                permissionRequest.channelId == channelId &&
                permissionRequest.connectionGeneration == expectedGeneration &&
                permissionRequest.confirmed &&
                now - permissionRequest.startedAt in 0L..15000L

            if (!superUser && !permissionConfirmed) {
                return "Keine bestätigte Schreibberechtigung. " +
                    "Bitte Berechtigung erneut anfragen."
            }

            val builder = Mumble.ChannelState.newBuilder()
                .setChannelId(channelId)

            var changed = false

            if (name != channel.name) {
                builder.setName(name)
                changed = true
            }

            if (description != channel.description) {
                builder.setDescription(description)
                changed = true
            }

            if (position != channel.position) {
                builder.setPosition(position)
                changed = true
            }

            if (!changed) return "Keine Änderungen vorhanden"

            if (service !== activeService ||
                !activeService.isConnected ||
                activeService.connection !== expectedConnection ||
                moderationConnectionGeneration != expectedGeneration ||
                !expectedConnection.isSynchronized
            ) return "Mumble-Verbindung hat sich geändert"

            if (pendingChannelEdit != null) {
                return "Eine Kanalbearbeitung läuft bereits"
            }

            val request = PendingChannelEdit(
                channelId = channelId,
                name = name,
                description = description,
                position = position,
                connectionGeneration = expectedGeneration,
                startedAt = android.os.SystemClock.elapsedRealtime()
            )

            // Berechtigung unmittelbar vor dem Versand verbrauchen.
            // Ein erneuter Schreibversuch braucht eine neue Abfrage.
            if (!superUser) {
                val currentPermission = channelEditPermissionRequest
                if (currentPermission !== permissionRequest ||
                    !permissionConfirmed
                ) {
                    return "Schreibberechtigung nicht mehr gültig"
                }
            }

            channelEditPermissionRequest = null

            pendingChannelEdit = request
            scheduleChannelEditTimeout(request)

            try {
                expectedConnection.sendTCPMessage(
                    builder.build(),
                    HumlaTCPMessageType.ChannelState
                )
            } catch (e: Exception) {
                if (pendingChannelEdit === request) {
                    pendingChannelEdit = null
                }
                throw e
            }

            "Kanaländerung gesendet – Serverbestätigung steht aus"
        } catch (e: Exception) {
            Log.e("FGW-Mumble", "Kanalbearbeitung fehlgeschlagen", e)
            "Kanalbearbeitung fehlgeschlagen: ${e.message}"
        }
    }

    fun moveMumbleChannel(
        channelId: Int,
        newParentId: Int,
        expectedGeneration: Long
    ): String {
        val activeService = service as? HumlaService
            ?: return "Keine Mumble-Verbindung"

        if (!activeService.isConnected ||
            moderationConnectionGeneration != expectedGeneration
        ) return "Mumble-Verbindung hat sich geändert"

        if (channelId <= 0) return "Root-Kanal darf nicht verschoben werden"
        if (newParentId < 0) return "Ungültiger Zielkanal"

        return try {
            val connection = activeService.connection
            val session = activeService.HumlaSession()
            val channel = session.getChannel(channelId)
                ?: return "Quellkanal nicht vorhanden"
            val parent = session.getChannel(newParentId)
                ?: return "Zielkanal nicht vorhanden"

            if (channel.parent?.id == newParentId) {
                return "Kanal befindet sich bereits dort"
            }

            // Zyklen im Kanalbaum verhindern.
            var ancestor: IChannel? = parent
            while (ancestor != null) {
                if (ancestor.id == channelId) {
                    return "Verschieben in eigenen Unterkanal nicht erlaubt"
                }
                ancestor = ancestor.parent
            }

            val ownUser = session.sessionUser
            val superUser = ownUser.userId == 0 &&
                ownUser.session == session.sessionId

            val permission = channelEditPermissionRequest
            val now = android.os.SystemClock.elapsedRealtime()
            val confirmed = permission != null &&
                permission.channelId == channelId &&
                permission.connectionGeneration == expectedGeneration &&
                permission.confirmed &&
                now - permission.startedAt in 0L..15000L

            if (!superUser && !confirmed) {
                return "Keine bestätigte Schreibberechtigung"
            }

            if (pendingChannelMove != null || pendingChannelEdit != null) {
                return "Eine Kanaländerung läuft bereits"
            }

            if (service !== activeService ||
                !activeService.isConnected ||
                activeService.connection !== connection ||
                !connection.isSynchronized ||
                moderationConnectionGeneration != expectedGeneration
            ) return "Mumble-Verbindung hat sich geändert"

            val request = PendingChannelMove(
                channelId, newParentId, channel.name, expectedGeneration
            )

            channelEditPermissionRequest = null
            pendingChannelMove = request

            reconnectHandler.postDelayed({
                if (pendingChannelMove === request) {
                    pendingChannelMove = null
                    onMumbleChannelMoveStatusChanged?.invoke(
                        "⏳ Keine Serverbestätigung. Kanalposition bitte prüfen."
                    )
                }
            }, 15000L)

            try {
                connection.sendTCPMessage(
                    Mumble.ChannelState.newBuilder()
                        .setChannelId(channelId)
                        .setParent(newParentId)
                        .build(),
                    HumlaTCPMessageType.ChannelState
                )
            } catch (error: Exception) {
                if (pendingChannelMove === request) {
                    pendingChannelMove = null
                }
                throw error
            }

            "Kanalverschiebung angefordert – Serverbestätigung steht aus"
        } catch (error: Exception) {
            Log.e("FGW-Mumble", "Kanalverschiebung fehlgeschlagen", error)
            "Kanalverschiebung fehlgeschlagen: ${error.message}"
        }
    }

    fun sendMumblePrivateMessage(
        targetSessionId: Int,
        text: String
    ): String {
        val activeService = service
            ?: return "Keine Mumble-Verbindung"

        if (!activeService.isConnected) {
            return "Mumble ist nicht verbunden"
        }

        val messageText = text.trim()
        if (messageText.isEmpty()) {
            return "Bitte eine Nachricht eingeben"
        }

        if (messageText.length > 5000) {
            return "Nachricht ist zu lang (maximal 5000 Zeichen)"
        }

        return try {
            val session = activeService.HumlaSession()
            val target = session.getUser(targetSessionId)
                ?: return "Benutzer ist nicht mehr verbunden"

            if (service !== activeService ||
                !activeService.isConnected
            ) {
                return "Mumble-Verbindung hat sich geändert"
            }

            session.sendUserTextMessage(
                target.session,
                messageText
            )

            Log.i(
                "FGW-Mumble-Chat",
                "Private Textnachricht an Session $targetSessionId übergeben."
            )

            "Nachricht an Mumble übergeben"
        } catch (error: Exception) {
            Log.e(
                "FGW-Mumble-Chat",
                "Private Nachricht konnte nicht gesendet werden",
                error
            )
            "Private Nachricht konnte nicht gesendet werden"
        }
    }

    fun sendMumbleChatMessage(
        text: String,
        serverWide: Boolean
    ): String {
        val activeService = service
            ?: return "Keine Mumble-Verbindung"

        if (!activeService.isConnected) {
            return "Mumble ist nicht verbunden"
        }

        val messageText = text.trim()
        if (messageText.isEmpty()) {
            return "Bitte eine Nachricht eingeben"
        }

        if (messageText.length > 5000) {
            return "Nachricht ist zu lang (maximal 5000 Zeichen)"
        }

        return try {
            val session = activeService.HumlaSession()

            val channelId = if (serverWide) {
                0
            } else {
                session.sessionChannel?.id
                    ?: return "Aktueller Kanal nicht verfügbar"
            }

            if (!activeService.isConnected ||
                service !== activeService
            ) {
                return "Mumble-Verbindung hat sich geändert"
            }

            session.sendChannelTextMessage(
                channelId,
                messageText,
                serverWide
            )

            Log.i(
                "FGW-Mumble-Chat",
                "Textnachricht gesendet: Kanal=$channelId, " +
                    "Serverweit=$serverWide"
            )

            "Nachricht an Mumble übergeben"
        } catch (error: Exception) {
            Log.e(
                "FGW-Mumble-Chat",
                "Nachrichtenversand fehlgeschlagen",
                error
            )
            "Nachricht konnte nicht gesendet werden"
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
        pendingChannelMove = null
        pendingChannelDeletion = null
        channelEditPermissionRequest = null
        cancelPendingChannelEdit("Manuell getrennt")
        cancelPendingChannelCreation("Verbindung manuell getrennt")
        // Alle bisherigen Moderations- und ACL-Freigaben
        // bereits vor dem eigentlichen Trennen ungültig machen.
        ++moderationConnectionGeneration
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
        clearMumbleAclWritePermission()

        // Vor der manuellen Trennung die Gateway-Bridge und PTT
        // synchron über den vorhandenen Sicherheitsstopp beenden.
        mumbleChatSessionGeneration.incrementAndGet()
            onMumbleChatSessionEnded?.invoke()
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

    // ACL-Berechtigungsanfrage für die aktuelle Verbindung.
    // Eine Anfrage allein bestätigt ausdrücklich KEIN Schreibrecht.
    private var aclPermissionChannelId: Int? = null
    private var aclPermissionGeneration = -1L
    private var aclPermissionRequestedAt = 0L
    private var aclWritePermissionConfirmed = false

    // SuperUser wird vom Mumble-Server mit Benutzer-ID 0 identifiziert.
    // Die Identität muss aus der aktuell verbundenen Sitzung stammen.
    private fun isCurrentMumbleSuperUser(expectedGeneration: Long = aclPermissionGeneration): Boolean {
        val activeService = service ?: return false
        if (!activeService.isConnected) return false

        return runCatching {
            val session = activeService.HumlaSession()
            val ownUser = session.sessionUser

            MumbleAclSuperUserSafety.check(
                MumbleAclSuperUserSafety.Context(
                    connected = activeService.isConnected &&
                        service === activeService,
                    connectionGeneration = moderationConnectionGeneration,
                    expectedGeneration = expectedGeneration,
                    ownSessionId = session.sessionId,
                    reportedSessionId = ownUser.session,
                    reportedUserId = ownUser.userId
                )
            )
        }.getOrDefault(false)
    }

    fun requestMumbleAclWritePermission(channelId: Int): Boolean {
        // Eine laufende Anfrage darf nicht überschrieben werden.
        // PermissionQuery besitzt keine eindeutige Anfragekennung.
        val requestAge = android.os.SystemClock.elapsedRealtime() -
            aclPermissionRequestedAt

        if (aclPermissionChannelId != null &&
            aclPermissionGeneration == moderationConnectionGeneration &&
            aclPermissionRequestedAt > 0L &&
            requestAge in 0L..15000L
        ) {
            Log.w(
                "FGW-Mumble-ACL",
                "Weitere ACL-Berechtigungsanfrage blockiert: Anfrage läuft."
            )
            return false
        }

        clearMumbleAclWritePermission()

        val activeService = service ?: return false
        if (!activeService.isConnected || channelId < 0) return false

        aclPermissionChannelId = channelId
        aclPermissionGeneration = moderationConnectionGeneration
        aclPermissionRequestedAt = android.os.SystemClock.elapsedRealtime()

        Log.i("FGW-Mumble-ACL", "PermissionQuery an Humla übergeben: Kanal=$channelId, Generation=$aclPermissionGeneration")

        return runCatching {
            activeService.HumlaSession().requestPermissions(channelId)
            Log.i("FGW-Mumble-ACL", "PermissionQuery-Aufruf abgeschlossen: Kanal=$channelId (Serverantwort noch ausstehend)")
            true
        }.getOrElse { error ->
            Log.e("FGW-Mumble-ACL", "PermissionQuery-Aufruf fehlgeschlagen: Kanal=$channelId", error)
            clearMumbleAclWritePermission()
            false
        }
    }

    /**
     * Zentraler Einstiegspunkt für spätere ACL-Schreibzugriffe.
     *
     * Bis zum Abschluss der Sicherheitsprüfung absichtlich gesperrt.
     * Es wird keine Nachricht an den Server übertragen.
     */
    private val aclWriteAuthorization =
        MumbleAclWriteAuthorization()

    fun authorizeMumbleAclWrite(
        token: MumbleAclWriteGuard.Token,
        draft: MumbleAclDraft
    ): Boolean {
        // Eine frühere Bestätigung darf nicht erhalten bleiben.
        aclWriteAuthorization.clear()

        if (draft.channelId != token.channelId) return false

        val latest = mumbleAclManager.getLatestAclSnapshot(
            token.channelId
        ) ?: return false

        val fingerprint = MumbleAclWriteGuard.fingerprint(
            draft, latest.snapshot
        ) ?: return false

        val valid = MumbleAclWriteGuard.check(
            token,
            MumbleAclWriteGuard.Context(
                connected = isConnected(),
                connectionGeneration =
                    getModerationConnectionGeneration() ?: -1L,
                channelId = latest.snapshot.channelId,
                aclReceiveVersion = latest.version,
                draftFingerprint = fingerprint
            )
        )

        if (!valid ||
            !hasConfirmedMumbleAclWritePermission(token.channelId)
        ) {
            return false
        }

        aclWriteAuthorization.authorize(token)
        return true
    }

    fun submitMumbleAclChanges(
        draft: MumbleAclDraft,
        snapshot: MumbleAclManager.AclSnapshot,
        token: MumbleAclWriteGuard.Token
    ): Boolean {
        if (aclWriteVerificationState.snapshot().status in setOf(
                MumbleAclWriteVerificationState.Status.ARMED,
                MumbleAclWriteVerificationState.Status.PENDING
            )
        ) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: Nachkontrolle läuft bereits."
            )
            return false
        }

        val currentFingerprint = MumbleAclWriteGuard.fingerprint(
            draft, snapshot
        ).orEmpty()

        // Berechtigung vor dem einmaligen Verbrauch erfassen.
        val permissionWasConfirmed =
            hasConfirmedMumbleAclWritePermission(draft.channelId)
        val permissionConfirmedByServer =
            permissionWasConfirmed && aclWritePermissionConfirmed

        val authorizationAccepted = aclWriteAuthorization.consume(
            token,
            MumbleAclWriteGuard.Context(
                connected = isConnected(),
                connectionGeneration =
                    getModerationConnectionGeneration() ?: -1L,
                channelId = draft.channelId,
                aclReceiveVersion =
                    getMumbleAclReceiveVersion() ?: -1L,
                draftFingerprint = currentFingerprint
            )
        )

        // Jede Prüfung verbraucht auch die zuvor gemeldete
        // Schreibberechtigung. Ein erneuter Versuch erfordert
        // eine neue Berechtigungsanfrage beim Server.
        clearMumbleAclWritePermission()

        if (!authorizationAccepted) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: keine gültige " +
                    "einmalige Benutzerbestätigung."
            )
            return false
        }

        val latest = mumbleAclManager.getLatestAclSnapshot(
            draft.channelId
        ) ?: return false

        if (latest.version != token.aclReceiveVersion ||
            latest.snapshot != snapshot
        ) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: Server-Snapshot veraltet."
            )
            return false
        }

        if (!MumbleAclPreparation.canPrepareChanges(
                draft, latest.snapshot
            )
        ) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: Entwurf nicht mehr gültig."
            )
            return false
        }

        val fingerprint = MumbleAclWriteGuard.fingerprint(
            draft, latest.snapshot
        ) ?: return false

        val valid = MumbleAclWriteGuard.check(
            token,
            MumbleAclWriteGuard.Context(
                connected = isConnected(),
                connectionGeneration =
                    getModerationConnectionGeneration() ?: -1L,
                channelId = draft.channelId,
                aclReceiveVersion =
                    getMumbleAclReceiveVersion() ?: -1L,
                draftFingerprint = fingerprint
            )
        )

        if (!valid || !permissionWasConfirmed) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: Sicherheitsprüfung fehlgeschlagen."
            )
            return false
        }

        // Ausschließlich geprüfte lokale ACL-Änderungen vorbereiten.
        val preparedMessage = MumbleAclPreparation.prepareWriteMessage(
            draft, latest.snapshot
        ) ?: return false

        val expectedContent =
            MumbleAclWriteVerification.captureExpectedContent(draft)

        if (preparedMessage.channelId != expectedContent.channelId ||
            preparedMessage.query ||
            preparedMessage.inheritAcls != expectedContent.inheritAcls ||
            preparedMessage.groupsList != expectedContent.groups ||
            preparedMessage.aclsList != expectedContent.rules
        ) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: vorbereitete Daten ungültig."
            )
            return false
        }

        // Die konkrete Verbindung der geprüften Freigabe festhalten.
        val activeService = service as? HumlaService ?: return false
        if (!activeService.isConnected ||
            moderationConnectionGeneration != token.connectionGeneration
        ) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: Verbindung gewechselt."
            )
            return false
        }

        val expectedConnection = activeService.connection

        // Nachkontrolle an die geprüfte Verbindung und ACL-Version binden.
        // Noch kein Versand: Die Freischaltung erfolgt separat.
        val verificationId = startMumbleAclWriteVerification(
            expectedContent = expectedContent,
            expectedGeneration = token.connectionGeneration,
            expectedAclVersion = token.aclReceiveVersion
        ) ?: return false

        // Vor dem Versand nochmals dieselbe Verbindung prüfen.
        if (service !== activeService ||
            !activeService.isConnected ||
            activeService.connection !== expectedConnection ||
            moderationConnectionGeneration != token.connectionGeneration
        ) {
            cancelMumbleAclWriteVerification(verificationId)
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch abgebrochen: Verbindung gewechselt."
            )
            return false
        }

        // Die Berechtigung unmittelbar vor dem Versand erneut prüfen.
        // SuperUser muss weiterhin eindeutig zur aktiven Sitzung gehören.
        val permissionStillValid =
            if (permissionWasConfirmed && token.connectionGeneration ==
                moderationConnectionGeneration
            ) {
                if (permissionConfirmedByServer) {
                    true
                } else {
                    isCurrentMumbleSuperUser(token.connectionGeneration)
                }
            } else {
                false
            }

        if (!permissionStillValid) {
            cancelMumbleAclWriteVerification(verificationId)
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch blockiert: Berechtigung vor Versand ungültig."
            )
            return false
        }

        val sent = mumbleAclManager.sendPreparedAclWrite(
            preparedMessage,
            expectedConnection
        )

        if (!sent) {
            cancelMumbleAclWriteVerification(verificationId)
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Schreibversuch fehlgeschlagen: kein Versand."
            )
            return false
        }

        // Erst nach erfolgreicher Übergabe die Nachkontrolle aktivieren.
        if (!aclWriteVerificationState.activate(verificationId)) {
            Log.w(
                "FGW-Mumble-ACL",
                "ACL-Nachkontrolle konnte nach Versand nicht aktiviert werden."
            )
            return false
        }

        scheduleMumbleAclWriteVerificationTimeout(verificationId)

        // Versand ist keine Serverbestätigung.
        // Den tatsächlichen ACL-Zustand erneut anfordern.
        val readbackRequested = requestMumbleAcl(draft.channelId)

        if (!readbackRequested) {
            val failed = aclWriteVerificationState.readbackFailed(
                verificationId
            )

            if (failed) {
                pendingAclWriteVerification = null
            }

            Log.e(
                "FGW-Mumble-ACL",
                "ACL-Schreibnachricht möglicherweise übertragen, " +
                    "aber Rückleseanfrage fehlgeschlagen. " +
                    "Serverübernahme nicht bestätigt."
            )
            return false
        }

        Log.i(
            "FGW-Mumble-ACL",
            "ACL-Schreibnachricht gesendet; Server-Nachkontrolle läuft."
        )
        return true
    }

    fun hasConfirmedMumbleAclWritePermission(channelId: Int): Boolean {
        val requestValid =
            service?.isConnected == true &&
            channelId >= 0 &&
            aclPermissionChannelId == channelId &&
            aclPermissionGeneration == moderationConnectionGeneration &&
            aclPermissionRequestedAt > 0L &&
            android.os.SystemClock.elapsedRealtime() - aclPermissionRequestedAt in 0L..15000L

        if (!requestValid) return false

        // Normale Administratoren benötigen die Serverbestätigung.
        if (aclWritePermissionConfirmed) return true

        // Mumble sendet dem SuperUser keine PermissionQuery-Antwort.
        // Ausschließlich die eigene Benutzer-ID 0 erlaubt diesen Sonderweg.
        return isCurrentMumbleSuperUser()
    }

    fun cancelMumbleAclWritePermission() {
        // Abgebrochene Freigaben dürfen nicht erneut verwendet werden.
        clearMumbleAclWritePermission()
        Log.i(
            "FGW-Mumble-ACL",
            "ACL-Schreibfreigabe durch Benutzer abgebrochen."
        )
    }

    private fun clearMumbleAclWritePermission() {
        aclPermissionChannelId = null
        aclPermissionGeneration = -1L
        aclPermissionRequestedAt = 0L
        aclWritePermissionConfirmed = false
        aclWriteAuthorization.clear()
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

    fun getMumbleAclReceiveVersion(): Long? =
        if (isConnected()) mumbleAclManager.getAclReceiveVersion() else null

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
