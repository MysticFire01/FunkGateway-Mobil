package org.funkgateway.mobile

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import android.os.Handler
import android.os.Looper
import org.funkgateway.mobile.frn.FrnConnection
import org.funkgateway.mobile.frn.FrnReceiver
import org.funkgateway.mobile.frn.FrnLoginConfig
import org.funkgateway.mobile.frn.FrnEvent
import org.funkgateway.mobile.audio.FrnAudioReceiver
import org.funkgateway.mobile.audio.FrnAudioSender

class GatewayService : Service() {
    companion object {
        const val ACTION_START = "org.funkgateway.mobile.START_GATEWAY"
        const val ACTION_STOP = "org.funkgateway.mobile.STOP_GATEWAY"
        private const val CHANNEL_ID = "funkgateway_radio"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(
            "funkgateway_audio",
            MODE_PRIVATE
        )
        setFrnVolume(prefs.getInt("frn_volume", 100))
    }

    private var foregroundActive = false
    private var microphoneForegroundActive = false

    private fun startGatewayForeground(microphone: Boolean = false) {
        if (foregroundActive &&
            microphoneForegroundActive == microphone) return

        if (microphone) {
            check(
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                "Mikrofonberechtigung fehlt."
            }
        }

        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FunkGateway Funkbetrieb",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aktive Funk- und VoIP-Verbindungen"
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("FunkGateway Mobile")
            .setContentText("Funkbetrieb im Hintergrund aktiv")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (microphone)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else 0)

            startForeground(NOTIFICATION_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= 29 && microphone) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        foregroundActive = true
        microphoneForegroundActive = microphone
    }

    private fun stopGatewayForeground() {
        if (!foregroundActive) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
        microphoneForegroundActive = false
    }

    @Synchronized
    private fun hasActiveGatewayWork(): Boolean {
        // Mumble-Verbindungen ebenfalls als aktive Gateway-Arbeit
        // berücksichtigen. Weitere Integrationen folgen später.
        return frnConnecting ||
            frnConnection != null ||
            mumbleManager?.hasActiveConnectionAttempt() == true
    }

    private fun stopForegroundWhenIdle() {
        if (!hasActiveGatewayWork()) {
            stopGatewayForeground()
            stopSelf()
        }
    }

    fun stopIfIdle() {
        mainHandler.post {
            if (!serviceDestroyed) {
                stopForegroundWhenIdle()
            }
        }
    }


    data class FrnParticipants(
        val entries: List<String> = emptyList(),
        val activeIndex: Int? = null
    )

    private var frnParticipants = FrnParticipants()

    private var frnServerRooms: List<String> = emptyList()

    @Synchronized
    fun getFrnServerRooms(): List<String> =
        frnServerRooms.toList()

    @Synchronized
    fun getFrnParticipants(): FrnParticipants =
        frnParticipants.copy(entries = frnParticipants.entries.toList())

    private var connectedFrnRoom: org.funkgateway.mobile.frn.FrnRoom? = null
    private var connectedFrnCallsign: String? = null

    @Synchronized
    fun getConnectedFrnCallsign(): String? = connectedFrnCallsign

    @Synchronized
    fun getConnectedFrnRoom(): org.funkgateway.mobile.frn.FrnRoom? =
        connectedFrnRoom

    enum class PttTarget {
        FRN, MUMBLE, ZELLO
    }

    private var mumbleManager: MumbleConnectionManager? = null

    @Synchronized
    fun setMumbleManager(manager: MumbleConnectionManager?) {
        if (mumbleManager === manager) return

        // Alte Bridge vor einem Manager-Wechsel vollständig stoppen.
        if (voipBridgeActive || bridgeFrnPttActive) {
            stopVoipBridge()
        }

        if (selectedPttTarget == PttTarget.MUMBLE &&
            pttState != PttState.IDLE) {
            stopPtt()
        }
        mumbleManager?.onConnectionLost = null
        mumbleManager = manager

        manager?.let { activeManager ->
            activeManager.onConnectionLost = {
                mainHandler.post {
                    onMumbleConnectionLost(activeManager)
                }
            }
        }

        manager?.setPlaybackVolume(mumbleVolume / 100f)
        manager?.setPlaybackDeafened(globalDeafened)
    }

    /**
     * Überträgt bereits dekodiertes FRN-PCM an Mumble.
     * Die Sitzung wird bei jedem Bridge-Neustart ungültig.
     */
    private fun runFrnToMumbleBridge(
        session: Long,
        manager: MumbleConnectionManager
    ) {
        Thread({
            // Humla/Opus erwartet exakt 480 Samples bei 48 kHz.
            // Der Puffer gehört ausschließlich zu dieser FRN-Sprechphase.
            val opusFrameSize = 480
            val pending = ShortArray(opusFrameSize)
            var pendingCount = 0

            try {
                bridgeLoop@ while (bridgeFrnToMumbleQueue.isActive(session)) {
                    val frame = bridgeFrnToMumbleQueue.poll() ?: continue
                    if (!bridgeFrnToMumbleQueue.isActive(session)) break

                    val pcm48 =
                        org.funkgateway.mobile.audio.PcmResampler
                            .frnToMumble(frame.pcm)

                    var offset = 0
                    while (offset < pcm48.size) {
                        if (!bridgeFrnToMumbleQueue.isActive(session)) {
                            break@bridgeLoop
                        }

                        val count = minOf(
                            opusFrameSize - pendingCount,
                            pcm48.size - offset
                        )

                        System.arraycopy(
                            pcm48, offset,
                            pending, pendingCount,
                            count
                        )

                        pendingCount += count
                        offset += count

                        if (pendingCount == opusFrameSize) {
                            val submitted = synchronized(bridgeMumbleAudioLock) {
                                if (bridgeFrnToMumbleQueue.isActive(session)) {
                                    manager.submitBridgeAudio(
                                        pending.copyOf(),
                                        opusFrameSize
                                    )
                                } else {
                                    null
                                }
                            }

                            if (submitted == null) break@bridgeLoop

                            if (!submitted) {
                                android.util.Log.w(
                                    "FunkGateway-Bridge",
                                    "FRN-PCM konnte nicht an Mumble übergeben werden"
                                )
                                break@bridgeLoop
                            }

                            pendingCount = 0
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                android.util.Log.e(
                    "FunkGateway-Bridge",
                    "FRN-Mumble-Audio-Worker fehlgeschlagen",
                    e
                )
            }
        }, "Bridge-FRN-Mumble").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Verarbeitet Mumble-PCM direkt für FRN.
     * Noch keine Netzwerkübertragung: zunächst Codec und Audioweg prüfen.
     */
    /**
     * Rücklaufschutz: Mumble darf nicht nach FRN senden,
     * solange FRN bereits zur Mumble-Seite spricht.
     */
    @Synchronized
    private fun canBridgeMumbleToFrn(session: Long): Boolean =
        !serviceDestroyed &&
        voipBridgeActive &&
        bridgeMumbleToFrnQueue.isActive(session) &&
        bridgeFrnSpeakerIndex == null &&
        frnParticipants.activeIndex == null &&
        !globalMuted

    private fun runMumbleToFrnBridge(session: Long) {
        Thread({
            val resampler =
                org.funkgateway.mobile.audio.StreamingPcmResampler()
            val encoder =
                org.funkgateway.mobile.audio.FrnBridgeEncoder()

            var speechActive = false
            var lastSpeechAt = 0L
            var speechCandidateAt = 0L

            try {
                while (bridgeMumbleToFrnQueue.isActive(session)) {
                    val frame = bridgeMumbleToFrnQueue.poll()

                    if (!bridgeMumbleToFrnQueue.isActive(session)) break

                    val now = android.os.SystemClock.elapsedRealtime()
                    val permitted = canBridgeMumbleToFrn(session)

                    if (speechActive &&
                        (!permitted || now - lastSpeechAt >= 1_000L)
                    ) {
                        if (!permitted) {
                            android.util.Log.w(
                                "FunkGateway-Bridge",
                                "Mumble-FRN: PTT-Abbruch durch Rücklaufschutz" +
                                    ", FRN-Sprecher=" + frnParticipants.activeIndex +
                                    ", Bridge-Sprecher=" + bridgeFrnSpeakerIndex +
                                    ", Bridge-PTT=" + bridgeFrnPttActive +
                                    ", PTT=" + pttState +
                                    ", Stumm=" + globalMuted
                            )
                        }
                        speechActive = false
                        synchronized(this@GatewayService) {
                            if (bridgeFrnPttActive) stopPtt()
                        }
                        android.util.Log.i(
                            "FunkGateway-Bridge",
                            "Mumble-FRN: SPRACHE ENDET (Nachlauf)"
                        )
                    }

                    if (frame == null) continue

                    val pcm8 = resampler.process(frame.pcm)
                    if (!bridgeMumbleToFrnQueue.isActive(session)) break

                    if (pcm8.isNotEmpty()) {
                        val averageLevel = pcm8.sumOf {
                            kotlin.math.abs(it.toInt()).toLong()
                        } / pcm8.size

                        android.util.Log.d(
                            "FunkGateway-Bridge",
                            "Mumble-FRN: PCM-Pegel=" + averageLevel +
                                ", Samples=" + pcm8.size
                        )

                        val now = android.os.SystemClock.elapsedRealtime()
                        val permitted = canBridgeMumbleToFrn(session)

                        if (permitted && averageLevel >= 250L) {
                            lastSpeechAt = now
                            if (!speechActive) {
                                if (speechCandidateAt == 0L) {
                                    speechCandidateAt = now
                                }
                                if (now - speechCandidateAt >= 150L &&
                                    bridgeMumbleToFrnQueue.isActive(session) &&
                                    canBridgeMumbleToFrn(session)
                                ) {
                                    if (startBridgeFrnPtt()) {
                                        speechActive = true
                                        android.util.Log.i(
                                            "FunkGateway-Bridge",
                                            "Mumble-FRN: TX0 angefordert"
                                        )
                                        android.util.Log.i(
                                            "FunkGateway-Bridge",
                                            "Mumble-FRN: SPRACHE BEGINNT"
                                        )
                                    }
                                }
                            }
                        } else if (speechActive &&
                            (!permitted || now - lastSpeechAt >= 1_000L)
                        ) {
                            speechActive = false
                            synchronized(this@GatewayService) {
                                if (bridgeFrnPttActive) stopPtt()
                            }
                            android.util.Log.i(
                                "FunkGateway-Bridge",
                                "Mumble-FRN: SPRACHE ENDET"
                            )
                        }
                        if (!permitted || averageLevel < 250L) {
                            if (!speechActive) speechCandidateAt = 0L
                        }
                    }

                    val packets = encoder.encode(pcm8)
                    if (!bridgeMumbleToFrnQueue.isActive(session)) break

                    for (packet in packets) {
                        if (!bridgeMumbleToFrnQueue.isActive(session)) break
                        if (packet.size != 325) continue

                        val tx = synchronized(this@GatewayService) {
                            val connection = frnConnection
                            if (speechActive &&
                                connection != null &&
                                canBridgeMumbleToFrn(session) &&
                                isPttSessionValidForBridge(pttSessionId, connection)
                            ) {
                                Pair(connection, pttSessionId)
                            } else null
                        } ?: continue

                        queuePttAudio {
                            val valid = synchronized(this@GatewayService) {
                                canBridgeMumbleToFrn(session) &&
                                    isPttSessionValidForBridge(tx.second, tx.first)
                            }
                            if (valid) {
                                try {
                                    tx.first.sendAudioPacket(packet)
                                } catch (e: Exception) {
                                    android.util.Log.e(
                                        "FunkGateway-Bridge",
                                        "Mumble-FRN: GSM-Senden fehlgeschlagen",
                                        e
                                    )
                                    mainHandler.post {
                                        synchronized(this@GatewayService) {
                                            if (isPttSessionValidForBridge(
                                                    tx.second, tx.first
                                                )) stopPtt()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                android.util.Log.e(
                    "FunkGateway-Bridge",
                    "Mumble-FRN-Audio-Worker fehlgeschlagen",
                    e
                )
            } finally {
                encoder.close()
            }
        }, "Bridge-Mumble-FRN").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Beendet die aktuelle FRN-Sprechphase.
     * Die Bridge-Verbindungen bleiben bestehen.
     */
    @Synchronized
    private fun resetFrnToMumbleBridgeAudio() {
        bridgeFrnSpeakerIndex = null
        bridgeFrnToMumbleQueue.stop()
        frnAudio?.setPcmListener(null)
        frnAudio?.clearPendingPackets()
        synchronized(bridgeMumbleAudioLock) {
            mumbleManager?.stopBridgeAudio()
        }
    }

    /**
     * Aktiviert die FRN-Mumble-Verlinkung.
     * Die Audio-Worker werden anschließend separat angeschlossen.
     */
    @Synchronized
    fun startVoipBridge(): Boolean {
        if (serviceDestroyed || voipBridgeActive || globalMuted ||
            pttState != PttState.IDLE || pttReleasePending ||
            frnConnection == null || frnConnecting ||
            mumbleManager?.isConnected() != true) return false

        val manager = mumbleManager ?: return false
        if (!manager.setBridgeAudioEnabled(true)) return false

        // Noch keinen FRN-Audio-Worker starten.
        // Jede Aussendung erhält ihre eigene Sitzung.
        bridgeFrnSpeakerIndex = null
        bridgeFrnToMumbleQueue.stop()
        frnAudio?.setPcmListener(null)

        val mumbleSession = bridgeMumbleToFrnQueue.start()
        bridgeSessionGeneration++
        voipBridgeActive = true

        manager.setBridgePcmListener { pcm ->
            if (bridgeMumbleToFrnQueue.isActive(mumbleSession)) {
                bridgeMumbleToFrnQueue.offer(mumbleSession, pcm)
            }
        }

        runMumbleToFrnBridge(mumbleSession)
        return true
    }

    @Synchronized
    fun isVoipBridgeActive(): Boolean = voipBridgeActive

    /**
     * Beendet die automatische VoIP-Verlinkung.
     * Bestehende FRN- und Mumble-Verbindungen bleiben erhalten.
     */
    @Synchronized
    fun stopVoipBridgeManually() {
        stopVoipBridge()
    }

    @Synchronized
    private fun stopVoipBridge() {
        // Zuerst alle alten Bridge-Audiositzungen ungültig machen.
        voipBridgeActive = false
        bridgeSessionGeneration++
        bridgeFrnSpeakerIndex = null
        bridgeFrnToMumbleQueue.stop()
        bridgeMumbleToFrnQueue.stop()

        // FRN-Empfang bleibt aktiv, aber nicht mehr zur Bridge.
        frnAudio?.setPcmListener(null)

        // Danach automatische Sendungen freigeben.
        if (bridgeFrnPttActive) {
            stopPtt()
        }

        mumbleManager?.setBridgePcmListener(null)
        synchronized(bridgeMumbleAudioLock) {
            mumbleManager?.stopBridgeAudio()
        }
        mumbleManager?.setBridgeAudioEnabled(false)
    }

    /**
     * Sicherheitsstopp bei unerwartetem Mumble-Verbindungsverlust.
     * Die bestehende FRN-Verbindung bleibt erhalten.
     */
    @Synchronized
    fun onMumbleConnectionLost(manager: MumbleConnectionManager) {
        if (mumbleManager !== manager) return

        if (voipBridgeActive || bridgeFrnPttActive) {
            stopVoipBridge()
        }

        if (selectedPttTarget == PttTarget.MUMBLE &&
            pttState != PttState.IDLE) {
            manager.stopTalking()
            resetPttState()
            pttReleasePending = false
        }
    }

    @Synchronized
    fun getMumbleManager(): MumbleConnectionManager? = mumbleManager

    private var selectedPttTarget: PttTarget? = null

    enum class PttState {
        IDLE,
        REQUESTING,
        TRANSMITTING
    }

    private var globalMuted = false
    private var globalDeafened = false
    private var frnVolume = 100
    private var mumbleVolume = 100

    @Synchronized
    fun getMumbleVolume(): Int = mumbleVolume

    @Synchronized
    fun setMumbleVolume(value: Int) {
        mumbleVolume = value.coerceIn(0, 100)
        mumbleManager?.setPlaybackVolume(mumbleVolume / 100f)
    }

    @Synchronized
    fun getFrnVolume(): Int = frnVolume

    @Synchronized
    fun setFrnVolume(value: Int) {
        frnVolume = value.coerceIn(0, 100)
        frnAudio?.setVolume(frnVolume / 100f)
    }

    @Synchronized
    fun isGlobalMuted(): Boolean = globalMuted

    @Synchronized
    fun isGlobalDeafened(): Boolean = globalDeafened

    @Synchronized
    fun setGlobalMuted(value: Boolean) {
        globalMuted = value
        if (value && pttState != PttState.IDLE) stopPtt()
    }

    @Synchronized
    fun setGlobalDeafened(value: Boolean) {
        globalDeafened = value
        frnAudio?.setDeafened(value)
        mumbleManager?.setPlaybackDeafened(value)
    }

    private var pttState = PttState.IDLE
    private var frnAudioSender: FrnAudioSender? = null

    /**
     * Kennzeichnet eine automatische FRN-Sendung der VoIP-Bridge.
     * Die manuelle Handy-PTT verwendet weiterhin den Mikrofonpfad.
     */
    private var bridgeFrnPttActive = false
    private var bridgeFrnPttSession = 0L

    /**
     * Getrennte Audiopuffer für beide Richtungen der VoIP-Bridge.
     * Sitzungswechsel machen alte Audiopakete ungültig.
     */
    private val bridgeFrnToMumbleQueue =
        org.funkgateway.mobile.audio.BridgeAudioQueue()

    private val bridgeMumbleToFrnQueue =
        org.funkgateway.mobile.audio.BridgeAudioQueue()

    private var bridgeSessionGeneration = 0L
    private var voipBridgeActive = false

    // Sprecherkennung ausschließlich für den FRN-Mumble-Audioweg.
    // Zugriff erfolgt unter dem GatewayService-Monitor.
    private var bridgeFrnSpeakerIndex: Int? = null

    // Gemeinsame Sperre für Mumble-Audioübergabe und Sendeende.
    private val bridgeMumbleAudioLock = Any()



    @Synchronized
    private fun isBridgeFrnPttSessionValid(session: Long): Boolean =
        !serviceDestroyed &&
        bridgeFrnPttActive &&
        bridgeFrnPttSession == session

    private var pttSessionId = 0L
    private var pttReleasePending = false
    private val pttTimeoutMillis = 180_000L
    private val pttReleaseTimeoutMillis = 20_000L
    private val pttNetworkExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { task ->
            Thread(task, "FRN-PTT-Network").apply { isDaemon = true }
        }
    )

    private fun queuePttNetwork(task: () -> Unit) {
        try {
            pttNetworkExecutor.execute(task)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            android.util.Log.w("FunkGateway-PTT", "Netzwerkauftrag abgewiesen", e)
        }
    }
    private val pendingPttAudio = java.util.concurrent.atomic.AtomicInteger(0)
    private fun queuePttAudio(task: () -> Unit) {
        if (pendingPttAudio.incrementAndGet() > 3) {
            pendingPttAudio.decrementAndGet()
            return
        }
        try {
            pttNetworkExecutor.execute {
                try { task() } finally { pendingPttAudio.decrementAndGet() }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pendingPttAudio.decrementAndGet()
            android.util.Log.w("FunkGateway-PTT", "Audiopaket abgewiesen", e)
        }
    }
    @Synchronized
    private fun isPttSessionValid(
        session: Long,
        connection: FrnConnection
    ): Boolean =
        !serviceDestroyed &&
        session == pttSessionId &&
        frnConnection === connection &&
        selectedPttTarget == PttTarget.FRN &&
        pttState != PttState.IDLE

    @Synchronized
    fun getPttState(): PttState = pttState

    @Synchronized
    fun isPttReleasePending(): Boolean = pttReleasePending

    @Synchronized
    private fun resetPttState() {
        pttState = PttState.IDLE
        pttSessionId++
        bridgeFrnPttActive = false
        bridgeFrnPttSession++
        val sender = frnAudioSender
        frnAudioSender = null
        sender?.close()
        if (microphoneForegroundActive && foregroundActive) {
            try {
                startGatewayForeground(false)
            } catch (e: Exception) {
                android.util.Log.e(
                    "FunkGateway-PTT",
                    "Rückkehr zum Empfangsmodus fehlgeschlagen",
                    e
                )
            }
        }
    }


    @Synchronized
    fun getAvailablePttTargets(): List<PttTarget> =
        buildList {
            if (frnConnection != null && !frnConnecting) {
                add(PttTarget.FRN)
            }
            if (mumbleManager?.isConnected() == true) {
                add(PttTarget.MUMBLE)
            }
        }

    @Synchronized
    fun getSelectedPttTarget(): PttTarget? =
        selectedPttTarget

    @Synchronized
    fun selectPttTarget(target: PttTarget?): Boolean {
        if (pttState != PttState.IDLE) return false
        if (target != null && target !in getAvailablePttTargets()) {
            return false
        }
        selectedPttTarget = target
        return true
    }

    /**
     * Fordert FRN-Senderecht für die digitale VoIP-Bridge an.
     * Startet kein Mikrofon. Audio folgt erst nach TxGranted.
     */
    @Synchronized
    private fun startBridgeFrnPtt(): Boolean {
        if (serviceDestroyed || globalMuted ||
            pttState != PttState.IDLE || pttReleasePending ||
            bridgeFrnPttActive) return false

        val connection = frnConnection ?: return false
        if (frnConnecting || frnParticipants.activeIndex != null) return false

        bridgeFrnPttActive = true
        val bridgeSession = ++bridgeFrnPttSession
        pttState = PttState.REQUESTING
        val pttSession = ++pttSessionId

        queuePttNetwork {
            if (isBridgeFrnPttSessionValid(bridgeSession) &&
                isBridgeFrnPttRequestValid(pttSession, connection)) {
                try {
                    connection.send("TX0")
                    android.util.Log.i(
                        "FunkGateway-Bridge",
                        "FRN TX0 erfolgreich geschrieben"
                    )
                } catch (e: Exception) {
                    android.util.Log.e(
                        "FunkGateway-Bridge", "FRN TX0 fehlgeschlagen", e
                    )
                    mainHandler.post {
                        if (isBridgeFrnPttSessionValid(bridgeSession)) {
                            stopPtt()
                        }
                    }
                }
            }
        }

        mainHandler.postDelayed({
            synchronized(this) {
                if (isBridgeFrnPttSessionValid(bridgeSession) &&
                    pttSession == pttSessionId &&
                    pttState == PttState.REQUESTING) {
                    android.util.Log.w(
                        "FunkGateway-Bridge",
                        "FRN TX0: keine Sendefreigabe innerhalb von 3 Sekunden"
                    )
                    stopPtt()
                }
            }
        }, 3_000L)

        mainHandler.postDelayed({
            synchronized(this) {
                if (isBridgeFrnPttSessionValid(bridgeSession) &&
                    pttSession == pttSessionId &&
                    pttState != PttState.IDLE) {
                    android.util.Log.w(
                        "FunkGateway-Bridge",
                        "FRN-Bridge-Sendezeitlimit: 180 Sekunden"
                    )
                    stopPtt()
                }
            }
        }, pttTimeoutMillis)

        return true
    }

    @Synchronized
    private fun isBridgeFrnPttRequestValid(
        session: Long,
        connection: FrnConnection
    ): Boolean =
        !serviceDestroyed &&
        session == pttSessionId &&
        frnConnection === connection &&
        bridgeFrnPttActive &&
        pttState == PttState.REQUESTING

    @Synchronized
    private fun isPttSessionValidForBridge(
        session: Long,
        connection: FrnConnection
    ): Boolean =
        !serviceDestroyed &&
        session == pttSessionId &&
        frnConnection === connection &&
        bridgeFrnPttActive &&
        pttState == PttState.TRANSMITTING

    @Synchronized
    fun startPtt(): Boolean {
        if (globalMuted || serviceDestroyed ||
            pttState != PttState.IDLE || pttReleasePending ||
            bridgeFrnPttActive) return false
        if (selectedPttTarget == PttTarget.MUMBLE) {
            val manager = mumbleManager ?: return false
            if (!manager.isConnected()) return false

            try {
                startGatewayForeground(true)
            } catch (e: Exception) {
                android.util.Log.e(
                    "FunkGateway-PTT",
                    "Mumble-Mikrofon-Vordergrunddienst fehlgeschlagen",
                    e
                )
                return false
            }

            pttState = PttState.REQUESTING

            if (!manager.startTalking()) {
                resetPttState()
                return false
            }

            pttState = PttState.TRANSMITTING
            val session = ++pttSessionId

            mainHandler.postDelayed({
                synchronized(this) {
                    if (session == pttSessionId &&
                        selectedPttTarget == PttTarget.MUMBLE &&
                        pttState != PttState.IDLE
                    ) {
                        android.util.Log.w(
                            "FunkGateway-PTT",
                            "Mumble-Sendezeitlimit erreicht: 180 Sekunden"
                        )
                        stopPtt()
                    }
                }
            }, pttTimeoutMillis)

            return true
        }

        if (selectedPttTarget != PttTarget.FRN) return false
        val connection = frnConnection ?: return false
        if (frnConnecting || frnParticipants.activeIndex != null) {
            return false
        }
        // Mikrofonmodus aktivieren, bevor TX0 angefordert wird.
        try {
            startGatewayForeground(true)
        } catch (e: Exception) {
            android.util.Log.e(
                "FunkGateway-PTT",
                "Mikrofon-Vordergrunddienst konnte nicht starten",
                e
            )
            return false
        }

        pttState = PttState.REQUESTING
        val session = ++pttSessionId
        try {
            queuePttNetwork {
                if (isPttSessionValid(session, connection)) {
                    try {
                        connection.send("TX0")
                    } catch (e: Exception) {
                        android.util.Log.e("FunkGateway-PTT", "TX0 fehlgeschlagen", e)
                        mainHandler.post {
                            if (isPttSessionValid(session, connection)) stopPtt()
                        }
                    }
                }
            }
            mainHandler.postDelayed({
                synchronized(this) {
                    if (session == pttSessionId &&
                        pttState != PttState.IDLE
                    ) {
                        android.util.Log.w(
                            "FunkGateway-PTT",
                            "FRN-Sendezeitlimit erreicht: 180 Sekunden"
                        )
                        stopPtt()
                    }
                }
            }, pttTimeoutMillis)
        } catch (e: Exception) {
            resetPttState()
            throw e
        }
        return true
    }

    @Synchronized
    fun stopPtt() {
        if (pttState == PttState.IDLE) return
        if (selectedPttTarget == PttTarget.MUMBLE &&
            !bridgeFrnPttActive) {
            try {
                mumbleManager?.stopTalking()
            } finally {
                resetPttState()
            }
            return
        }
        val connection = if (
            pttState != PttState.IDLE &&
            (selectedPttTarget == PttTarget.FRN || bridgeFrnPttActive)
        ) frnConnection else null
        if (connection != null) pttReleasePending = true
        resetPttState()
        val releaseSession = pttSessionId
        if (connection != null) {
            mainHandler.postDelayed({
                synchronized(this) {
                    if (frnConnection === connection && pttReleasePending && pttSessionId == releaseSession) {
                        android.util.Log.e("FunkGateway-PTT", "RX0-Zeitlimit überschritten – FRN wird getrennt")
                        recoverFrnConnection()
                    }
                }
            }, pttReleaseTimeoutMillis)
        }
        if (connection != null) {
            queuePttNetwork {
                try {
                    connection.send("RX0")
                    android.util.Log.i(
                        "FunkGateway-PTT",
                        "FRN RX0 erfolgreich geschrieben"
                    )
                    synchronized(this) {
                        if (frnConnection === connection &&
                            pttSessionId == releaseSession
                        ) {
                            pttReleasePending = false
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FunkGateway-PTT", "FRN RX0 konnte nicht gesendet werden", e)
                    mainHandler.post {
                        synchronized(this) {
                            if (frnConnection === connection) recoverFrnConnection()
                        }
                    }
                }
            }
        }
    }

    private var frnConnection: FrnConnection? = null
    private var frnReceiver: FrnReceiver? = null
    private var frnAudio: FrnAudioReceiver? = null

    private var frnGeneration = 0
    private var frnLastStoppedGeneration = -1
    private var frnConnecting = false

    // Automatische FRN-Wiederverbindung.
    private var frnReconnectEnabled = false
    private var frnReconnectAttempt = 0
    // Wartezeiten für FRN-Wiederverbindungsversuche.
    private val frnReconnectBackoff = ReconnectBackoff()
    private var frnReconnectGeneration = 0L
    private var frnReconnectTask: Runnable? = null

    // Zugangsdaten für den Wiederaufbau der letzten FRN-Verbindung.
    // Keine Activity-Callbacks dauerhaft im Service speichern.
    private var lastFrnLoginConfig: FrnLoginConfig? = null
    private var serviceDestroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val groupInvitationRetryTask = object : Runnable {
        override fun run() {
            if (serviceDestroyed) return

            val connectedRoom = getConnectedFrnRoom()
            if (connectedRoom != null) {
                val invitations =
                    org.funkgateway.mobile.frn.FrnAcceptedGroupInvitations
                        .takeDueRetries()

                invitations.forEach { invitation ->
                    if (
                        invitation.server.equals(
                            connectedRoom.server, ignoreCase = true
                        ) &&
                        invitation.room.equals(
                            connectedRoom.room, ignoreCase = true
                        )
                    ) {
                        val currentRoom = getConnectedFrnRoom()
                        if (
                            currentRoom != null &&
                            currentRoom.server.equals(
                                invitation.server, ignoreCase = true
                            ) &&
                            currentRoom.room.equals(
                                invitation.room, ignoreCase = true
                            ) &&
                            org.funkgateway.mobile.frn.FrnAcceptedGroupInvitations
                                .markRetryStarted(invitation.invitationId)
                        ) {
                            val response =
                                org.funkgateway.mobile.frn.FrnGroupInvitationResponseParser
                                    .encode(
                                        org.funkgateway.mobile.frn.FrnGroupResponseType.ACCEPT,
                                        invitation.invitationId,
                                        invitation.groupId
                                    )

                            sendFrnGroupControlMessageAsync(
                                invitation.senderId,
                                response,
                                invitation.server,
                                invitation.room
                            ) { sent, error ->
                                if (!sent) {
                                    android.util.Log.w(
                                        "FunkGateway-Groups",
                                        "ACCEPT-Wiederholung fehlgeschlagen: " +
                                            (error ?: "Unbekannter Fehler")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (connectedRoom != null) {
                val ownId =
                    org.funkgateway.mobile.frn.FrnGatewayIdentity.getId(
                        this@GatewayService
                    )

                val retries =
                    org.funkgateway.mobile.frn.FrnGroupInvitationTracker
                        .takeDueConfirmRetries()

                retries.forEach { invitation ->
                    val group =
                        org.funkgateway.mobile.frn.FrnChatGroups.get(
                            invitation.groupId
                        )
                    val currentRoom = getConnectedFrnRoom()

                    if (
                        group != null &&
                        group.active &&
                        group.server.equals(
                            invitation.server, ignoreCase = true
                        ) &&
                        group.room.equals(
                            invitation.room, ignoreCase = true
                        ) &&
                        org.funkgateway.mobile.frn.FrnChatGroups.isHost(
                            group.id, ownId
                        ) &&
                        currentRoom != null &&
                        currentRoom.server.equals(
                            invitation.server, ignoreCase = true
                        ) &&
                        currentRoom.room.equals(
                            invitation.room, ignoreCase = true
                        ) &&
                        org.funkgateway.mobile.frn.FrnGroupInvitationTracker
                            .markConfirmRetryStarted(invitation.invitationId)
                    ) {
                        val response =
                            org.funkgateway.mobile.frn.FrnGroupInvitationResponseParser
                                .encode(
                                    org.funkgateway.mobile.frn.FrnGroupResponseType.CONFIRM,
                                    invitation.invitationId,
                                    invitation.groupId
                                )

                        sendFrnGroupControlMessageAsync(
                            invitation.recipientId,
                            response,
                            invitation.server,
                            invitation.room
                        ) { sent, error ->
                            if (!sent) {
                                android.util.Log.w(
                                    "FunkGateway-Groups",
                                    "CONFIRM-Wiederholung fehlgeschlagen: " +
                                        (error ?: "Unbekannter Fehler")
                                )
                            }
                        }
                    }
                }
            }

            if (!serviceDestroyed) {
                mainHandler.postDelayed(this, 5_000L)
            }
        }
    }

    private fun dispatchFrnEvent(
        generation: Int,
        event: FrnEvent,
        callback: (FrnEvent) -> Unit
    ) {
        mainHandler.post {
            val current = synchronized(this) {
                !serviceDestroyed && generation == frnGeneration
            }
            if (current) {
                synchronized(this) {
                    when (event) {
                        is FrnEvent.ClientList -> {
                            frnParticipants = FrnParticipants(
                                entries = event.entries.toList(),
                                activeIndex = event.activeIndex.takeIf { it != 0 }
                            )
                        }
                        is FrnEvent.ServerList -> {
                            if (event.type == 5) {
                                frnServerRooms = event.entries.mapNotNull { raw ->
                                    val match = Regex(
                                        "<NT>(.*?)</NT>",
                                        RegexOption.DOT_MATCHES_ALL
                                    ).find(raw)
                                    val name = android.text.Html.fromHtml(
                                        match?.groupValues?.get(1) ?: raw,
                                        android.text.Html.FROM_HTML_MODE_LEGACY
                                    ).toString().trim()
                                    name.takeIf { it.isNotEmpty() }
                                }.distinct()
                            }
                        }
                        is FrnEvent.Audio -> {
                            frnParticipants = frnParticipants.copy(
                                activeIndex = event.index
                            )
                        }
                        is FrnEvent.Idle -> {
                            frnParticipants = frnParticipants.copy(
                                activeIndex = null
                            )
                        }
                        else -> Unit
                    }
                }
                if (event is FrnEvent.TextMessage) {
                    relayFrnGroupMessages(event)
                }
                callback(event)
            }
        }
    }

    private fun relayFrnGroupMessages(event: FrnEvent.TextMessage) {
        val room = getConnectedFrnRoom() ?: return
        val clients = getFrnParticipants().entries

        val messages =
            org.funkgateway.mobile.frn.FrnChatParser.parse(
                event.entries, clients
            )

        messages.forEach { message ->
            val reply =
                org.funkgateway.mobile.frn.FrnGroupMessageParser.recognize(
                    message, room.server, room.room
                ) ?: return@forEach

            val group =
                org.funkgateway.mobile.frn.FrnChatGroups.get(
                    reply.groupId
                ) ?: return@forEach

            val ownInstallationId =
                org.funkgateway.mobile.frn.FrnGatewayIdentity.getId(this)
            if (!org.funkgateway.mobile.frn.FrnChatGroups.isHost(
                    group.id, ownInstallationId
                )
            ) return@forEach

            org.funkgateway.mobile.frn.FrnGroupChatStore.add(
                groupId = reply.groupId,
                sender = reply.sender,
                senderId = reply.senderId,
                message = reply.text,
                outgoing = false
            )

            val recipients = group.participants.keys.filterNot {
                it.equals(reply.senderId, ignoreCase = true)
            }

            if (recipients.isEmpty()) return@forEach

            val forwarded =
                "[${group.shortCode}] ${reply.sender}: ${reply.text}"

            sendFrnGroupMessageAsync(recipients, forwarded) { sent, total ->
                android.util.Log.d(
                    "FRN-Group",
                    "Gruppenantwort weitergegeben: $sent/$total"
                )
            }
        }
    }

    private fun dispatchFrnError(
        generation: Int,
        error: Throwable,
        callback: (Throwable) -> Unit
    ) {
        mainHandler.post {
            val current = synchronized(this) {
                !serviceDestroyed &&
                    (generation == frnGeneration ||
                     generation == frnLastStoppedGeneration)
            }
            if (current) {
                GatewayLog.add(
                    "FRN",
                    error.message ?: error.javaClass.simpleName,
                    "ERROR"
                )
                callback(error)
            }
        }
    }

    @Synchronized
    fun connectFrn(
        config: FrnLoginConfig,
        onEvent: (FrnEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {},
        automaticReconnect: Boolean = false
    ) {
        check(!serviceDestroyed) { "GatewayService wurde beendet." }
        check(!frnConnecting && frnConnection == null) {
            "FRN ist bereits verbunden oder wird verbunden."
        }

        // Ein manueller Verbindungsaufbau ersetzt geplante Reconnects.
        if (!automaticReconnect) {
            frnReconnectGeneration++
            frnReconnectTask?.let { mainHandler.removeCallbacks(it) }
            frnReconnectTask = null
        }

        // Letzte gewünschte FRN-Verbindung für einen möglichen Reconnect.
        // Keine Activity-Callbacks speichern.
        lastFrnLoginConfig = config

        // Reconnect nur aktivieren, wenn der Benutzer ihn erlaubt.
        frnReconnectEnabled = ReconnectSettings.load(this).frnEnabled
        if (!automaticReconnect) {
            frnReconnectBackoff.reset()
            frnReconnectAttempt = 0
        }

        frnConnecting = true
        frnLastStoppedGeneration = -1
        frnParticipants = FrnParticipants()
        frnServerRooms = emptyList()
        val generation = ++frnGeneration
        GatewayLog.add("FRN", "Verbindungsaufbau gestartet.")

        Thread({
            var connection: FrnConnection? = null

            try {
                connection = FrnConnection.connect(config)

                synchronized(this) {
                    if (serviceDestroyed || generation != frnGeneration) {
                        connection.close()
                        return@Thread
                    }

                    val activeConnection = connection

                    val audioReceiver = FrnAudioReceiver()
                    audioReceiver.setDeafened(globalDeafened)
                    audioReceiver.setVolume(frnVolume / 100f)

                    frnAudio = audioReceiver

                    val receiver = FrnReceiver(
                        activeConnection,
                        onEvent = { event ->
                            if (event is FrnEvent.TxGranted) {
                                synchronized(this) {
                                    android.util.Log.i(
                                        "FunkGateway-Bridge",
                                        "FRN TxGranted empfangen: index=" +
                                            event.index +
                                            ", bridgePTT=" + bridgeFrnPttActive +
                                            ", pttState=" + pttState
                                    )
                                    if (!serviceDestroyed &&
                                        generation == frnGeneration &&
                                        frnConnection === activeConnection &&
                                        (selectedPttTarget == PttTarget.FRN ||
                                            bridgeFrnPttActive) &&
                                        pttState == PttState.REQUESTING
                                    ) {
                                        pttState = PttState.TRANSMITTING
                                        if (!bridgeFrnPttActive) {
                                        val audioSession = pttSessionId
                                        val sender = FrnAudioSender(
                                            this,
                                            onPacket = { packet ->
                                                if (isPttSessionValid(audioSession, activeConnection)) {
                                                    queuePttAudio {
                                                        if (isPttSessionValid(audioSession, activeConnection)) {
                                                            try {
                                                                activeConnection.sendAudioPacket(packet)
                                                            } catch (e: Exception) {
                                                                android.util.Log.e("FunkGateway-PTT", "TX1 fehlgeschlagen", e)
                                                                mainHandler.post {
                                                                    if (isPttSessionValid(audioSession, activeConnection)) stopPtt()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            
                                            onError = { error ->
                                                android.util.Log.e(
                                                    "FunkGateway-PTT",
                                                    "FRN-Mikrofonfehler",
                                                    error
                                                )
                                                synchronized(this) {
                                                    if (generation == frnGeneration &&
                                                        frnConnection === activeConnection &&
                                                        audioSession == pttSessionId
                                                    ) {
                                                        stopPtt()
                                                    }
                                                }
                                            }
                                        )
                                        frnAudioSender = sender
                                        try {
                                            sender.start()
                                        } catch (e: Exception) {
                                            stopPtt()
                                            android.util.Log.e(
                                                "FunkGateway-PTT",
                                                "Mikrofonstart fehlgeschlagen",
                                                e
                                            )
                                        }
                                        }
                                    }
                                }
                            }
                            if (event is FrnEvent.Audio) {
                                synchronized(this) {
                                    if (!serviceDestroyed &&
                                        generation == frnGeneration &&
                                        frnAudio === audioReceiver
                                    ) {
                                        if (voipBridgeActive &&
                                            bridgeFrnSpeakerIndex != event.index
                                        ) {
                                            resetFrnToMumbleBridgeAudio()

                                            val manager = mumbleManager
                                            if (manager?.isConnected() == true) {
                                                val session =
                                                    bridgeFrnToMumbleQueue.start()

                                                bridgeFrnSpeakerIndex = event.index

                                                audioReceiver.setPcmListener { pcm ->
                                                    if (bridgeFrnToMumbleQueue.isActive(session)) {
                                                        bridgeFrnToMumbleQueue.offer(session, pcm)
                                                    }
                                                }

                                                runFrnToMumbleBridge(session, manager)
                                            }
                                        }

                                        audioReceiver.receive(event.packet)
                                    }
                                }
                            } else if (event is FrnEvent.Idle) {
                                synchronized(this) {
                                    if (!serviceDestroyed &&
                                        generation == frnGeneration &&
                                        frnAudio === audioReceiver &&
                                        voipBridgeActive
                                    ) {
                                        resetFrnToMumbleBridgeAudio()
                                    }
                                }
                            }
                            dispatchFrnEvent(generation, event, onEvent)
                        },
                        onError = { error ->
                            dispatchFrnError(generation, error, onError)
                        },
                        onStopped = {
                            audioReceiver.close()
                            synchronized(this) {
                                if (frnAudio === audioReceiver) {
                                    frnAudio = null
                                }
                                if (generation == frnGeneration &&
                                    frnConnection === activeConnection
                                ) {
                                    // Bei FRN-Verbindungsverlust die Bridge stoppen.
                                    if (voipBridgeActive || bridgeFrnPttActive) {
                                        stopVoipBridge()
                                    }

                                    frnReceiver = null
                                    frnConnection = null
                                    org.funkgateway.mobile.frn.FrnGroupMemberSyncBuffer.clear()
                                    frnConnecting = false
                                    resetPttState()
                                    pttReleasePending = false

                                    connectedFrnRoom = null
                                    connectedFrnCallsign = null
                                    frnParticipants = FrnParticipants()
                                    frnServerRooms = emptyList()
                                    frnLastStoppedGeneration = generation
                                    frnGeneration++

                                    // Unerwarteter Verbindungsabbruch:
                                    // Nach dem Aufräumen Wiederverbindung planen.
                                    scheduleFrnReconnect()

                                    mainHandler.post {
                                        val current = synchronized(this) {
                                            !serviceDestroyed &&
                                                frnLastStoppedGeneration == generation
                                        }
                                        if (current) {
                                            GatewayLog.add(
                                                "FRN",
                                                "Verbindung zum Server beendet.",
                                                "WARN"
                                            )
                                            try {
                                                onDisconnected()
                                            } finally {
                                                stopForegroundWhenIdle()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )

                    // Erfolgreiche Verbindung: Reconnect-Zähler zurücksetzen.
                    frnReconnectBackoff.reset()
                    frnReconnectAttempt = 0
                    frnReconnectGeneration++
                    frnReconnectTask?.let { mainHandler.removeCallbacks(it) }
                    frnReconnectTask = null

                    frnConnection = activeConnection
                    frnReceiver = receiver
                    connectedFrnRoom = config.room
                    connectedFrnCallsign = config.callsign.trim()
                    frnConnecting = false
                    receiver.start()
                    mainHandler.post {
                        val current = synchronized(this) {
                            !serviceDestroyed &&
                                generation == frnGeneration &&
                                frnConnection === activeConnection
                        }
                        if (current) {
                            GatewayLog.add("FRN", "Verbindung hergestellt.")
                            onConnected()
                        }
                    }
                }
            } catch (e: Exception) {
                synchronized(this) {
                    if (generation == frnGeneration && !serviceDestroyed) {
                        frnConnecting = false
                        dispatchFrnError(generation, e, onError)

                        // Nach einem fehlgeschlagenen Aufbau erneut versuchen.
                        // Die gespeicherte Benutzereinstellung entscheidet.
                        scheduleFrnReconnect()
                    }
                }
                connection?.close()
                mainHandler.post {
                    val current = synchronized(this) {
                        !serviceDestroyed && generation == frnGeneration
                    }
                    if (current) {
                        stopForegroundWhenIdle()
                    }
                }
            }
        }, "FRN-Connect").start()
    }

    enum class FrnStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    @Synchronized
    fun getFrnStatus(): FrnStatus =
        when {
            frnConnecting -> FrnStatus.CONNECTING
            frnConnection != null -> FrnStatus.CONNECTED
            else -> FrnStatus.DISCONNECTED
        }

    @Synchronized
    fun sendFrnTextMessage(recipientId: String, text: String): Boolean {
        val connection = frnConnection ?: return false
        val recipient = recipientId.trim()
        val message = text.trim()

        require(recipient.isNotEmpty() && recipient.length <= 128) {
            "Ungültiger FRN-Empfänger."
        }
        require(recipient.none { it == '<' || it == '>' ||
            it == '\r' || it == '\n' || it == '\u0000' }) {
            "Ungültige Zeichen in der Empfänger-ID."
        }
        require(message.isNotEmpty() && message.length <= 500) {
            "Nachricht muss zwischen 1 und 500 Zeichen enthalten."
        }
        require(message.none { it == '\r' || it == '\n' ||
            it == '\u0000' }) {
            "Zeilenumbrüche sind nicht erlaubt."
        }

        fun escape(value: String): String =
            value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")

        connection.send(
            "TM:<ID>${escape(recipient)}</ID>" +
                "<MS>${escape(message)}</MS>"
        )
        return true
    }

    fun sendFrnGroupControlMessageAsync(
        recipientId: String,
        text: String,
        expectedServer: String,
        expectedRoom: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val expectedConnection: FrnConnection?
        synchronized(this) {
            val room = connectedFrnRoom
            expectedConnection = if (
                room != null &&
                room.server.equals(expectedServer, ignoreCase = true) &&
                room.room.equals(expectedRoom, ignoreCase = true)
            ) frnConnection else null
        }

        if (expectedConnection == null) {
            mainHandler.post {
                callback(false, "Ursprüngliche FRN-Verbindung nicht verfügbar.")
            }
            return
        }

        Thread({
            try {
                val sent = synchronized(this) {
                    val room = connectedFrnRoom
                    if (
                        !serviceDestroyed &&
                        frnConnection === expectedConnection &&
                        room != null &&
                        room.server.equals(expectedServer, ignoreCase = true) &&
                        room.room.equals(expectedRoom, ignoreCase = true)
                    ) {
                        sendFrnTextMessage(recipientId, text)
                    } else {
                        false
                    }
                }
                mainHandler.post {
                    callback(
                        sent,
                        if (sent) null else "FRN-Verbindung inzwischen geändert."
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(
                        false,
                        e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }, "FRN-Group-Control-TX").start()
    }

    fun sendFrnMemberSyncAsync(
        groupId: String,
        installationId: String
    ) {
        val group = org.funkgateway.mobile.frn.FrnChatGroups.get(groupId)
            ?: return
        if (!org.funkgateway.mobile.frn.FrnChatGroups.isHost(
                groupId, installationId
            )
        ) return

        val messages =
            org.funkgateway.mobile.frn.FrnChatGroups.createMemberSyncMessages(
                groupId, installationId
            )
        val recipients =
            org.funkgateway.mobile.frn.FrnChatGroups.getGatewaySyncRecipients(
                groupId, installationId
            )

        if (messages.isEmpty() || recipients.isEmpty()) return

        val server = group.server
        val roomName = group.room

        val expectedConnection = synchronized(this) {
            val room = connectedFrnRoom
            if (!serviceDestroyed &&
                room != null &&
                room.server.equals(server, ignoreCase = true) &&
                room.room.equals(roomName, ignoreCase = true)
            ) frnConnection else null
        } ?: return

        Thread({
            for (recipientId in recipients) {
                for (message in messages) {
                    val sent = try {
                        synchronized(this) {
                            val room = connectedFrnRoom
                            if (!serviceDestroyed &&
                                frnConnection === expectedConnection &&
                                room != null &&
                                room.server.equals(server, ignoreCase = true) &&
                                room.room.equals(roomName, ignoreCase = true)
                            ) {
                                sendFrnTextMessage(recipientId, message)
                            } else {
                                false
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "FunkGateway-Chat",
                            "MEMBER-Versand fehlgeschlagen",
                            e
                        )
                        false
                    }

                    if (!sent) {
                        android.util.Log.w(
                            "FunkGateway-Chat",
                            "Mitglieder-Synchronisation abgebrochen."
                        )
                        return@Thread
                    }
                }
            }
        }, "FRN-Member-Sync-TX").start()
    }

    fun sendFrnGroupMessageAsync(
        recipientIds: List<String>,
        text: String,
        callback: (Int, Int) -> Unit
    ) {
        val targets = recipientIds.distinct().filter { it.isNotBlank() }

        Thread({
            var successful = 0

            targets.forEach { id ->
                try {
                    val result = sendFrnTextMessage(id, text)
                    if (result) successful++
                } catch (e: Exception) {
                    android.util.Log.w(
                        "FunkGateway-Chat",
                        "Gruppennachricht an $id fehlgeschlagen",
                        e
                    )
                }
            }

            mainHandler.post {
                callback(successful, targets.size)
            }
        }, "FRN-Group-Chat-TX").start()
    }

    fun sendFrnGroupInvitationAsync(
        invitation: org.funkgateway.mobile.frn.FrnGroupInvitation,
        callback: (Boolean, String?) -> Unit
    ) {
        val text = try {
            org.funkgateway.mobile.frn.FrnGroupInvitationParser
                .encode(invitation)
        } catch (e: IllegalArgumentException) {
            mainHandler.post {
                callback(false, "Ungültige Gruppeneinladung.")
            }
            return
        }

        if (text.length > 500 || invitation.senderId.isBlank()) {
            mainHandler.post {
                callback(false, "Einladung ist zu lang oder Empfänger fehlt.")
            }
            return
        }

        val tracker = org.funkgateway.mobile.frn.FrnGroupInvitationTracker
        val pending = org.funkgateway.mobile.frn.FrnPendingGroupInvitation(
            invitationId = invitation.invitationId,
            groupId = invitation.groupId,
            recipientId = invitation.senderId,
            server = invitation.server,
            room = invitation.room
        )

        if (!tracker.register(pending)) {
            mainHandler.post {
                callback(false, "Einladung konnte nicht vorgemerkt werden.")
            }
            return
        }

        sendFrnTextMessageAsync(invitation.senderId, text) { sent, error ->
            if (!sent) {
                tracker.complete(invitation.invitationId)
            }
            callback(sent, error)
        }
    }

    fun sendFrnTextMessageAsync(
        recipientId: String,
        text: String,
        callback: (Boolean, String?) -> Unit
    ) {
        Thread({
            try {
                val sent = sendFrnTextMessage(recipientId, text)
                mainHandler.post {
                    callback(
                        sent,
                        if (sent) null else "FRN ist nicht verbunden."
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(
                        false,
                        e.message?.takeIf { it.isNotBlank() }
                            ?: e.javaClass.simpleName
                    )
                }
            }
        }, "FRN-Chat-TX").start()
    }

    /**
     * Verhindert weitere automatische FRN-Verbindungsversuche.
     * Bereits geplante Versuche werden entfernt.
     */
    /**
     * Plant einen FRN-Wiederverbindungsversuch ein.
     * Die eigentliche Verbindung wird im nächsten Schritt ergänzt.
     */
    @Synchronized
    private fun scheduleFrnReconnect() {
        if (serviceDestroyed || !frnReconnectEnabled) return
        if (!ReconnectSettings.load(this).frnEnabled) return
        if (lastFrnLoginConfig == null) return
        if (frnConnecting || frnConnection != null) return
        if (frnReconnectTask != null) return

        val settings = ReconnectSettings.load(this)
        val delay = frnReconnectBackoff.nextDelayMillis(settings)
        val generation = ++frnReconnectGeneration

        val task = Runnable {
            synchronized(this) {
                if (generation != frnReconnectGeneration ||
                    serviceDestroyed || !frnReconnectEnabled
                ) return@synchronized

                frnReconnectTask = null

                val config = lastFrnLoginConfig
                if (config == null ||
                    frnConnecting ||
                    frnConnection != null ||
                    !ReconnectSettings.load(this).frnEnabled
                ) return@synchronized

                GatewayLog.add(
                    "FRN",
                    "Automatischer Wiederverbindungsversuch gestartet."
                )

                try {
                    connectFrn(
                        config = config,
                        onEvent = {},
                        onError = { error ->
                            GatewayLog.add(
                                "FRN",
                                "Wiederverbindung fehlgeschlagen: ${error.message}",
                                "WARN"
                            )
                        },
                        automaticReconnect = true
                    )
                } catch (e: Exception) {
                    GatewayLog.add(
                        "FRN",
                        "Reconnect konnte nicht gestartet werden: ${e.message}",
                        "WARN"
                    )
                }
            }
        }

        frnReconnectTask = task
        mainHandler.postDelayed(task, delay)

        GatewayLog.add(
            "FRN",
            "Wiederverbindung in ${delay / 1000} Sekunden geplant."
        )
    }

    @Synchronized
    private fun cancelFrnReconnect() {
        frnReconnectEnabled = false
        frnReconnectGeneration++
        frnReconnectAttempt = 0
        frnReconnectBackoff.reset()

        frnReconnectTask?.let { task ->
            mainHandler.removeCallbacks(task)
        }
        frnReconnectTask = null
        lastFrnLoginConfig = null
    }

    /**
     * Technischer FRN-Verbindungsfehler.
     * PTT sicher beenden und anschließend Reconnect ermöglichen.
     */
    @Synchronized
    private fun recoverFrnConnection() {
        val config = lastFrnLoginConfig
        val reconnectAllowed =
            frnReconnectEnabled &&
            ReconnectSettings.load(this).frnEnabled &&
            !serviceDestroyed

        // Bestehende Verbindung vollständig und sicher beenden.
        disconnectFrn()

        if (reconnectAllowed && config != null && !serviceDestroyed) {
            lastFrnLoginConfig = config
            frnReconnectEnabled = true
            scheduleFrnReconnect()
        }
    }

    @Synchronized
    fun disconnectFrn() {
        // Bewusste Trennung: keine automatische Wiederverbindung.
        cancelFrnReconnect()

        // Automatische Audioweiterleitung vor FRN-Trennung beenden.
        if (voipBridgeActive || bridgeFrnPttActive) {
            stopVoipBridge()
        }

        org.funkgateway.mobile.frn.FrnGroupMemberSyncBuffer.clear()
        if (frnConnection != null || frnConnecting) {
            GatewayLog.add(
                "FRN",
                "Verbindung manuell getrennt."
            )
        }
        resetPttState()
        pttReleasePending = false

        frnAudio?.close()
        frnAudio = null
        frnLastStoppedGeneration = -1
        frnGeneration++
        frnConnecting = false
        connectedFrnRoom = null
        connectedFrnCallsign = null
        frnParticipants = FrnParticipants()
        frnServerRooms = emptyList()

        frnReceiver?.close()
        frnReceiver = null

        frnConnection?.close()
        frnConnection = null

        mainHandler.post {
            stopForegroundWhenIdle()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        mainHandler.removeCallbacks(groupInvitationRetryTask)
        mainHandler.postDelayed(groupInvitationRetryTask, 5_000L)
        when (intent?.action) {
            ACTION_START -> startGatewayForeground()
            ACTION_STOP -> {
                disconnectFrn()
                stopGatewayForeground()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(this) {
            serviceDestroyed = true
            mainHandler.removeCallbacks(groupInvitationRetryTask)
            stopPtt()
            if (voipBridgeActive || bridgeFrnPttActive) {
                stopVoipBridge()
            }
            mumbleManager?.let { manager ->
                manager.onConnectionLost = null
                manager.setUiCallbacks(null, null)
                manager.release()
            }
            mumbleManager = null
            disconnectFrn()
            pttNetworkExecutor.shutdownNow()
        }
        super.onDestroy()
    }
}
