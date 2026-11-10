package com.peluda.rev1bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Estado observable del puente (lo muestra MainActivity y la notificación). */
data class BridgeStatus(
    val running: Boolean = false,
    val usbState: UsbMidiDriver.State = UsbMidiDriver.State.IDLE,
    val deviceName: String? = null,
    val webSocketClients: Int = 0,
    val webSocketError: String? = null,
    val midiServiceAlive: Boolean = false,
    val midiAppsConnected: Int = 0,
    val eventsForwarded: Long = 0,
    val jogEncoding: String? = null,
)

/**
 * Foreground Service orquestador: levanta [LocalMidiServer] y [UsbMidiDriver] y reenvía cada
 * evento decodificado por [Rev1Parser] al puerto MIDI virtual y al WebSocket.
 */
class MainBridgeService : Service(), UsbMidiDriver.Listener, LocalMidiServer.Listener {

    companion object {
        const val ACTION_START = "com.peluda.rev1bridge.action.START"
        const val ACTION_STOP = "com.peluda.rev1bridge.action.STOP"
        const val ACTION_RESCAN = "com.peluda.rev1bridge.action.RESCAN"

        private const val TAG = "MainBridgeService"
        private const val CHANNEL_ID = "rev1_bridge"
        private const val NOTIFICATION_ID = 0x2B73
        private const val STATS_INTERVAL_MS = 500L

        private val _status = MutableStateFlow(BridgeStatus())
        val status: StateFlow<BridgeStatus> = _status.asStateFlow()

        fun updateStatus(transform: (BridgeStatus) -> BridgeStatus) = _status.update(transform)

        fun start(context: Context, action: String = ACTION_START) {
            val intent = Intent(context, MainBridgeService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MainBridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val parser = Rev1Parser()
    private val forwarded = AtomicLong()

    private lateinit var server: LocalMidiServer
    private lateinit var driver: UsbMidiDriver
    private var bridgeStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        server = LocalMidiServer(scope, LocalMidiServer.DEFAULT_PORT, this)
        driver = UsbMidiDriver(this, scope, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        if (!bridgeStarted) {
            bridgeStarted = true
            startBridge()
        } else if (intent?.action == ACTION_RESCAN) {
            driver.scan()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (bridgeStarted) {
            Rev1VirtualMidiService.outboundSink = null
            driver.stop()
            server.stop()
        }
        scope.cancel()
        updateStatus {
            it.copy(running = false, usbState = UsbMidiDriver.State.IDLE, deviceName = null, webSocketClients = 0)
        }
        super.onDestroy()
    }

    private fun startBridge() {
        forwarded.set(0)
        parser.reset()
        updateStatus { it.copy(running = true, webSocketError = null, eventsForwarded = 0) }

        // Apps (djay) → puerto virtual de entrada → USB OUT (LEDs de la REV1)
        Rev1VirtualMidiService.outboundSink = { data, offset, count -> driver.send(data, offset, count) }

        server.start()
        driver.start()

        // Contadores al StateFlow a ritmo fijo (no por evento, para no cargar el hilo USB)
        scope.launch {
            var lastCount = -1L
            var lastEncoding: String? = null
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val count = forwarded.get()
                val encoding = parser.activeJogEncoding?.jsonName
                if (count != lastCount || encoding != lastEncoding) {
                    lastCount = count
                    lastEncoding = encoding
                    updateStatus { it.copy(eventsForwarded = count, jogEncoding = encoding) }
                }
            }
        }

        // La notificación se actualiza solo cuando cambia algo visible
        scope.launch {
            status.distinctUntilChangedBy { listOf(it.usbState, it.deviceName, it.webSocketClients, it.midiAppsConnected) }
                .collect { notifyStatus(it) }
        }
    }

    // ---- UsbMidiDriver.Listener (hilo de lectura USB) ----

    override fun onUsbData(buffer: ByteArray, length: Int, timestampNanos: Long) {
        val events = parser.parseUsbMidi(buffer, length, timestampNanos)
        if (events.isEmpty()) return
        val wsActive = server.hasClients
        for (event in events) {
            val raw = event.toRawBytes()
            Rev1VirtualMidiService.dispatchToApps(raw, 0, raw.size, timestampNanos)
            if (wsActive) server.broadcast(event.toJson())
        }
        forwarded.addAndGet(events.size.toLong())
    }

    override fun onUsbStateChanged(state: UsbMidiDriver.State, device: UsbDevice?) {
        Log.i(TAG, "USB: $state ${device?.productName.orEmpty()}")
        updateStatus {
            it.copy(
                usbState = state,
                deviceName = if (state == UsbMidiDriver.State.CONNECTED) device?.displayName() else null,
            )
        }
    }

    // ---- LocalMidiServer.Listener ----

    override fun onClientCountChanged(count: Int) {
        updateStatus { it.copy(webSocketClients = count) }
    }

    override fun onServerError(message: String) {
        Log.e(TAG, message)
        updateStatus { it.copy(webSocketError = message) }
    }

    override fun statusJson(): String {
        val s = status.value
        return buildString {
            append("{\"app\":\"DDJ-REV1 Bridge\"")
            append(",\"usb\":\"").append(s.usbState.name.lowercase()).append('"')
            append(",\"device\":").append(s.deviceName?.let { "\"" + it.replace("\"", "'") + "\"" } ?: "null")
            append(",\"ws_clients\":").append(s.webSocketClients)
            append(",\"midi_apps\":").append(s.midiAppsConnected)
            append(",\"events\":").append(forwarded.get())
            append(",\"jog_encoding\":").append(s.jogEncoding?.let { "\"$it\"" } ?: "null")
            append('}')
        }
    }

    // ---- Notificación ----

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(status.value), type)
    }

    private fun notifyStatus(s: BridgeStatus) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(s)) }
    }

    private fun buildNotification(s: BridgeStatus): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MainBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(summary(s))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun summary(s: BridgeStatus): String {
        val usb = when (s.usbState) {
            UsbMidiDriver.State.CONNECTED -> s.deviceName ?: "REV1 conectada"
            UsbMidiDriver.State.WAITING_PERMISSION -> "esperando permiso USB"
            UsbMidiDriver.State.PERMISSION_DENIED -> "permiso USB denegado"
            UsbMidiDriver.State.NOT_FOUND, UsbMidiDriver.State.DISCONNECTED -> "sin controladora"
            UsbMidiDriver.State.ERROR -> "error USB"
            UsbMidiDriver.State.IDLE -> "inactivo"
        }
        return "$usb · WS ${s.webSocketClients} · MIDI ${s.midiAppsConnected}"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun UsbDevice.displayName(): String =
        listOfNotNull(manufacturerName, productName).joinToString(" ").ifBlank { "VID %04X PID %04X".format(vendorId, productId) }
}
