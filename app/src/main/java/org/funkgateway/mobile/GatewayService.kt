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
        // Weitere Integrationen wie Mumble, Zello und Papageien
        // werden hier später ergänzt.
        return frnConnecting || frnConnection != null
    }

    private fun stopForegroundWhenIdle() {
        if (!hasActiveGatewayWork()) {
            stopGatewayForeground()
            stopSelf()
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

    private var selectedPttTarget: PttTarget? = null

    enum class PttState {
        IDLE,
        REQUESTING,
        TRANSMITTING
    }

    private var globalMuted = false
    private var globalDeafened = false

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
    }

    private var pttState = PttState.IDLE
    private var frnAudioSender: FrnAudioSender? = null
    private var pttSessionId = 0L
    private var pttReleasePending = false
    private val pttTimeoutMillis = 180_000L
    private val pttReleaseTimeoutMillis = 5_000L
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

    @Synchronized
    fun startPtt(): Boolean {
        if (globalMuted || serviceDestroyed || pttState != PttState.IDLE || pttReleasePending) return false
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
        val connection = if (pttState != PttState.IDLE && selectedPttTarget == PttTarget.FRN) frnConnection else null
        if (connection != null) pttReleasePending = true
        resetPttState()
        val releaseSession = pttSessionId
        if (connection != null) {
            mainHandler.postDelayed({
                synchronized(this) {
                    if (frnConnection === connection && pttReleasePending && pttSessionId == releaseSession) {
                        android.util.Log.e("FunkGateway-PTT", "RX0-Zeitlimit überschritten – FRN wird getrennt")
                        disconnectFrn()
                    }
                }
            }, pttReleaseTimeoutMillis)
        }
        if (connection != null) {
            queuePttNetwork {
                try {
                    connection.send("RX0")
                    synchronized(this) {
                        if (frnConnection === connection) {
                            pttReleasePending = false
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FunkGateway-PTT", "FRN RX0 konnte nicht gesendet werden", e)
                    mainHandler.post {
                        synchronized(this) {
                            if (frnConnection === connection) disconnectFrn()
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
    private var serviceDestroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
                                activeIndex = event.activeIndex
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
                callback(event)
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
            if (current) callback(error)
        }
    }

    @Synchronized
    fun connectFrn(
        config: FrnLoginConfig,
        onEvent: (FrnEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ) {
        check(!serviceDestroyed) { "GatewayService wurde beendet." }
        check(!frnConnecting && frnConnection == null) {
            "FRN ist bereits verbunden oder wird verbunden."
        }

        frnConnecting = true
        frnLastStoppedGeneration = -1
        frnParticipants = FrnParticipants()
        frnServerRooms = emptyList()
        val generation = ++frnGeneration

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

                    frnAudio = audioReceiver

                    val receiver = FrnReceiver(
                        activeConnection,
                        onEvent = { event ->
                            if (event is FrnEvent.TxGranted) {
                                synchronized(this) {
                                    if (!serviceDestroyed &&
                                        generation == frnGeneration &&
                                        frnConnection === activeConnection &&
                                        selectedPttTarget == PttTarget.FRN &&
                                        pttState == PttState.REQUESTING
                                    ) {
                                        pttState = PttState.TRANSMITTING
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
                            if (event is FrnEvent.Audio) {
                                synchronized(this) {
                                    if (!serviceDestroyed &&
                                        generation == frnGeneration &&
                                        frnAudio === audioReceiver
                                    ) {
                                        audioReceiver.receive(event.packet)
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
                                    frnReceiver = null
                                    frnConnection = null
                                    frnConnecting = false
                                    resetPttState()
                                    pttReleasePending = false
                                    if (selectedPttTarget == PttTarget.FRN) {
                                        selectedPttTarget = null
                                    }
                                    connectedFrnRoom = null
                                    connectedFrnCallsign = null
                                    frnParticipants = FrnParticipants()
                                    frnServerRooms = emptyList()
                                    frnLastStoppedGeneration = generation
                                    frnGeneration++
                                    mainHandler.post {
                                        val current = synchronized(this) {
                                            !serviceDestroyed &&
                                                frnLastStoppedGeneration == generation
                                        }
                                        if (current) {
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
                        if (current) onConnected()
                    }
                }
            } catch (e: Exception) {
                synchronized(this) {
                    if (generation == frnGeneration && !serviceDestroyed) {
                        frnConnecting = false
                        dispatchFrnError(generation, e, onError)
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
    fun disconnectFrn() {
        resetPttState()
        pttReleasePending = false
        if (selectedPttTarget == PttTarget.FRN) {
            selectedPttTarget = null
        }
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
            disconnectFrn()
            pttNetworkExecutor.shutdownNow()
        }
        super.onDestroy()
    }
}
