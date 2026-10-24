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

class GatewayService : Service() {
    companion object {
        const val ACTION_START = "org.funkgateway.mobile.START_GATEWAY"
        const val ACTION_STOP = "org.funkgateway.mobile.STOP_GATEWAY"
        private const val CHANNEL_ID = "funkgateway_radio"
        private const val NOTIFICATION_ID = 1001
    }

    private var foregroundActive = false

    private fun startGatewayForeground() {
        if (foregroundActive) return

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
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        foregroundActive = true
    }

    private fun stopGatewayForeground() {
        if (!foregroundActive) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
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

    @Synchronized
    fun getConnectedFrnRoom(): org.funkgateway.mobile.frn.FrnRoom? =
        connectedFrnRoom

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

                    frnAudio = audioReceiver

                    val receiver = FrnReceiver(
                        activeConnection,
                        onEvent = { event ->
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
                                    connectedFrnRoom = null
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
        frnAudio?.close()
        frnAudio = null
        frnLastStoppedGeneration = -1
        frnGeneration++
        frnConnecting = false
        connectedFrnRoom = null
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
        }
        super.onDestroy()
    }
}
