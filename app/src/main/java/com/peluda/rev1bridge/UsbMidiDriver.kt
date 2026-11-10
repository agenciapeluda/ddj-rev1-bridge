package com.peluda.rev1bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Motor de I/O USB Host para la DDJ-REV1.
 *
 * Toma la interfaz MIDIStreaming (clase Audio, subclase 3) de la controladora con
 * claimInterface(force = true): eso desconecta el driver MIDI del sistema, así que la única forma
 * de que otras apps vean la REV1 pasa a ser el puerto virtual de este puente.
 *
 * El bucle de lectura reutiliza un buffer de 64 bytes por conexión: el [Listener.onUsbData] tiene que
 * consumirlo de forma síncrona (no guardar la referencia).
 */
class UsbMidiDriver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        /** Llamado desde el hilo de lectura USB. [buffer] se reutiliza en la siguiente lectura. */
        fun onUsbData(buffer: ByteArray, length: Int, timestampNanos: Long)
        fun onUsbStateChanged(state: State, device: UsbDevice?)
    }

    enum class State { IDLE, NOT_FOUND, WAITING_PERMISSION, PERMISSION_DENIED, CONNECTED, DISCONNECTED, ERROR }

    companion object {
        const val ACTION_USB_PERMISSION = "com.peluda.rev1bridge.USB_PERMISSION"

        /** 0x2B73 = Pioneer DJ Corporation, 0x08E4 = Pioneer Corporation (equipos anteriores). */
        val PIONEER_VENDOR_IDS = setOf(0x2B73, 0x08E4)

        private const val TAG = "UsbMidiDriver"
        private const val READ_BUFFER_SIZE = 64
        private const val READ_TIMEOUT_MS = 50
        private const val WRITE_TIMEOUT_MS = 20
        private const val USB_SUBCLASS_MIDISTREAMING = 3
    }

    private data class MidiEndpoints(
        val usbInterface: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint?,
    )

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val writeLock = Any()
    private val packetizer = UsbMidiPacketizer()
    private val writeBuffer = ByteArrayOutputStream(256)

    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var device: UsbDevice? = null
    @Volatile private var endpoints: MidiEndpoints? = null
    private var readJob: Job? = null
    private var receiverRegistered = false

    val connectedDevice: UsbDevice? get() = device

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val dev = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        open(dev)
                    } else {
                        listener.onUsbStateChanged(State.PERMISSION_DENIED, dev)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (dev.deviceName == device?.deviceName) {
                        close()
                        listener.onUsbStateChanged(State.DISCONNECTED, dev)
                    }
                }
            }
        }
    }

    /** Registra los receivers y busca la controladora. Llamar desde el hilo principal. */
    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        scan()
    }

    /** Busca una REV1 enchufada; si no hay permiso lo pide (respuesta asíncrona vía broadcast). */
    fun scan() {
        if (connection != null) return
        val candidate = usbManager.deviceList.values.firstOrNull { isRev1Candidate(it) }
        if (candidate == null) {
            listener.onUsbStateChanged(State.NOT_FOUND, null)
            return
        }
        if (usbManager.hasPermission(candidate)) {
            open(candidate)
        } else {
            requestPermission(candidate)
        }
    }

    fun stop() {
        close()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        listener.onUsbStateChanged(State.IDLE, null)
    }

    /**
     * Envía MIDI crudo (p. ej. LEDs que manda djay al puerto virtual) a la controladora,
     * empaquetado como USB-MIDI. Thread-safe.
     */
    fun send(data: ByteArray, offset: Int, count: Int) {
        synchronized(writeLock) {
            val conn = connection ?: return
            val out = endpoints?.output ?: return
            writeBuffer.reset()
            packetizer.encode(data, offset, count, writeBuffer)
            if (writeBuffer.size() == 0) return
            val packets = writeBuffer.toByteArray()
            val chunk = out.maxPacketSize.takeIf { it >= 4 }?.let { it - it % 4 } ?: 64
            var pos = 0
            while (pos < packets.size) {
                val len = minOf(chunk, packets.size - pos)
                val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    conn.bulkTransfer(out, packets, pos, len, WRITE_TIMEOUT_MS)
                } else {
                    // La variante con offset existe recién desde API 28
                    val slice = if (pos == 0 && len == packets.size) packets else packets.copyOfRange(pos, pos + len)
                    conn.bulkTransfer(out, slice, len, WRITE_TIMEOUT_MS)
                }
                if (sent < 0) {
                    Log.w(TAG, "bulkTransfer OUT falló ($len bytes)")
                    return
                }
                pos += len
            }
        }
    }

    private fun isRev1Candidate(dev: UsbDevice): Boolean =
        dev.vendorId in PIONEER_VENDOR_IDS && findMidiEndpoints(dev) != null

    private fun requestPermission(dev: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Mutable: el sistema agrega EXTRA_DEVICE y EXTRA_PERMISSION_GRANTED. Intent explícito (package).
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        listener.onUsbStateChanged(State.WAITING_PERMISSION, dev)
        usbManager.requestPermission(dev, pending)
    }

    private fun open(dev: UsbDevice) {
        if (connection != null) return
        val eps = findMidiEndpoints(dev)
        if (eps == null) {
            listener.onUsbStateChanged(State.ERROR, dev)
            return
        }
        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            listener.onUsbStateChanged(State.ERROR, dev)
            return
        }
        if (!conn.claimInterface(eps.usbInterface, true)) {
            conn.close()
            listener.onUsbStateChanged(State.ERROR, dev)
            return
        }
        synchronized(writeLock) {
            packetizer.reset()
            connection = conn
            endpoints = eps
            device = dev
        }
        startReadLoop(conn, eps.input)
        listener.onUsbStateChanged(State.CONNECTED, dev)
    }

    private fun startReadLoop(conn: UsbDeviceConnection, input: UsbEndpoint) {
        readJob = scope.launch(Dispatchers.IO) {
            // Sin puntos de suspensión: la corrutina queda en el mismo hilo durante todo el bucle,
            // así que la prioridad aplica a la lectura y se restaura al devolver el hilo al pool.
            val tid = Process.myTid()
            val previousPriority = Process.getThreadPriority(tid)
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                // Un buffer por conexión, reutilizado en cada lectura (sin basura para el GC)
                val buffer = ByteArray(READ_BUFFER_SIZE)
                val size = input.maxPacketSize.takeIf { it in 4..buffer.size } ?: buffer.size
                while (isActive) {
                    val n = conn.bulkTransfer(input, buffer, size, READ_TIMEOUT_MS)
                    if (n > 0) listener.onUsbData(buffer, n, System.nanoTime())
                    // n < 0 = timeout sin datos (o error); la desconexión llega por ACTION_USB_DEVICE_DETACHED
                }
            } finally {
                Process.setThreadPriority(tid, previousPriority)
            }
        }
    }

    private fun close() {
        val job = readJob
        readJob = null
        val (conn, eps) = synchronized(writeLock) {
            val current = connection to endpoints
            connection = null
            endpoints = null
            device = null
            current
        }
        job?.cancel()
        if (conn != null) {
            // Cerrar recién cuando el bucle salió del bulkTransfer en curso (máx. READ_TIMEOUT_MS)
            scope.launch(Dispatchers.IO) {
                job?.join()
                eps?.let { runCatching { conn.releaseInterface(it.usbInterface) } }
                conn.close()
            }
        }
    }

    private fun findMidiEndpoints(dev: UsbDevice): MidiEndpoints? {
        var vendorFallback: MidiEndpoints? = null
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK && ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    if (input == null) input = ep
                } else if (output == null) {
                    output = ep
                }
            }
            if (input == null) continue
            val found = MidiEndpoints(intf, input, output)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                intf.interfaceSubclass == USB_SUBCLASS_MIDISTREAMING
            ) {
                return found
            }
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && vendorFallback == null) {
                vendorFallback = found
            }
        }
        return vendorFallback
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}

/**
 * Convierte un stream MIDI crudo (con running status y SysEx) en paquetes USB-MIDI 1.0
 * de 4 bytes (Cable Number | Code Index Number, MIDI_0, MIDI_1, MIDI_2).
 */
internal class UsbMidiPacketizer(cable: Int = 0) {
    private val cableNibble = (cable and 0x0F) shl 4

    private var runningStatus = 0
    private val message = IntArray(3)
    private var messageCount = 0
    private var messageExpected = 0

    private var inSysEx = false
    private val sysEx = IntArray(3)
    private var sysExCount = 0

    fun reset() {
        runningStatus = 0
        messageCount = 0
        messageExpected = 0
        inSysEx = false
        sysExCount = 0
    }

    fun encode(data: ByteArray, offset: Int, count: Int, out: ByteArrayOutputStream) {
        for (i in offset until offset + count) {
            val b = data[i].toInt() and 0xFF

            if (b >= 0xF8) { // Real-Time: un paquete propio, en cualquier momento
                packet(out, 0xF, b, 0, 0)
                continue
            }

            if (inSysEx) {
                if (b == 0xF7) {
                    sysEx[sysExCount++] = b
                    when (sysExCount) {
                        1 -> packet(out, 0x5, sysEx[0], 0, 0)
                        2 -> packet(out, 0x6, sysEx[0], sysEx[1], 0)
                        else -> packet(out, 0x7, sysEx[0], sysEx[1], sysEx[2])
                    }
                    inSysEx = false
                    sysExCount = 0
                    continue
                }
                if (b < 0x80) {
                    sysEx[sysExCount++] = b
                    if (sysExCount == 3) {
                        packet(out, 0x4, sysEx[0], sysEx[1], sysEx[2])
                        sysExCount = 0
                    }
                    continue
                }
                // Status inesperado dentro de un SysEx: se descarta el SysEx incompleto
                inSysEx = false
                sysExCount = 0
            }

            when {
                b == 0xF0 -> {
                    inSysEx = true
                    runningStatus = 0
                    messageCount = 0
                    sysEx[0] = b
                    sysExCount = 1
                }
                b >= 0x80 -> {
                    runningStatus = if (b < 0xF0) b else 0
                    message[0] = b
                    messageCount = 1
                    messageExpected = dataLength(b) + 1
                    if (messageExpected == 1) { // F6 Tune Request (F4/F5/F7 sueltos se ignoran)
                        if (b == 0xF6) packet(out, 0x5, b, 0, 0)
                        messageCount = 0
                    }
                }
                else -> {
                    if (messageCount == 0) {
                        if (runningStatus == 0) continue
                        message[0] = runningStatus
                        messageCount = 1
                        messageExpected = dataLength(runningStatus) + 1
                    }
                    message[messageCount++] = b
                    if (messageCount == messageExpected) {
                        emitMessage(out)
                        messageCount = 0
                    }
                }
            }
        }
    }

    private fun emitMessage(out: ByteArrayOutputStream) {
        val st = message[0]
        val d1 = if (messageExpected > 1) message[1] else 0
        val d2 = if (messageExpected > 2) message[2] else 0
        val cin = when {
            st < 0xF0 -> st shr 4
            st == 0xF1 || st == 0xF3 -> 0x2
            st == 0xF2 -> 0x3
            else -> 0x5
        }
        packet(out, cin, st, d1, d2)
    }

    private fun packet(out: ByteArrayOutputStream, cin: Int, b0: Int, b1: Int, b2: Int) {
        out.write(cableNibble or cin)
        out.write(b0)
        out.write(b1)
        out.write(b2)
    }

    private fun dataLength(st: Int): Int = when {
        st < 0xF0 -> when (st and 0xF0) {
            0xC0, 0xD0 -> 1
            else -> 2
        }
        st == 0xF1 || st == 0xF3 -> 1
        st == 0xF2 -> 2
        else -> 0
    }
}
