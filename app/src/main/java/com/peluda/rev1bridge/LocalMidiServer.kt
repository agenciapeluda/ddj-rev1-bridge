package com.peluda.rev1bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor WebSocket (RFC 6455) mínimo sobre java.net.ServerSocket, solo en 127.0.0.1.
 *
 * - Handshake HTTP → WebSocket con Sec-WebSocket-Accept (SHA-1 + Base64).
 * - TCP_NODELAY en cada cliente: cada evento sale en su propio segmento, sin esperar a Nagle.
 * - [broadcast] codifica el frame de texto (0x81) una sola vez y lo encola por cliente; cada
 *   cliente tiene su corrutina de escritura, así un cliente lento no frena al hilo USB
 *   (si se atrasa, se descartan los eventos más viejos de su cola).
 * - Responde ping con pong y cierra limpio ante un frame de close.
 * - Un GET sin Upgrade devuelve el estado en JSON (útil como health check).
 */
class LocalMidiServer(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val listener: Listener,
) {
    interface Listener {
        fun onClientCountChanged(count: Int)
        fun onServerError(message: String)
        /** Texto recibido de un cliente (no se usa para el ruteo MIDI, queda para extensiones). */
        fun onClientText(text: String) {}
        /** JSON que devuelve GET /status. */
        fun statusJson(): String
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "LocalMidiServer"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HEADER_BYTES = 8192
        private const val MAX_CLIENT_PAYLOAD = 64 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 5000
        private const val CLIENT_QUEUE_CAPACITY = 1024

        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        fun acceptKey(clientKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest((clientKey.trim() + WS_GUID).toByteArray(Charsets.US_ASCII))
            return Base64.getEncoder().encodeToString(digest)
        }

        /** Frame de servidor (sin máscara) con FIN=1. */
        fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray {
            val len = payload.size
            val headerSize = when {
                len < 126 -> 2
                len <= 0xFFFF -> 4
                else -> 10
            }
            val frame = ByteArray(headerSize + len)
            frame[0] = (0x80 or opcode).toByte()
            when (headerSize) {
                2 -> frame[1] = len.toByte()
                4 -> {
                    frame[1] = 126
                    frame[2] = (len shr 8).toByte()
                    frame[3] = len.toByte()
                }
                else -> {
                    frame[1] = 127
                    val l = len.toLong()
                    for (i in 0 until 8) frame[2 + i] = (l shr (56 - 8 * i)).toByte()
                }
            }
            System.arraycopy(payload, 0, frame, headerSize, len)
            return frame
        }
    }

    private inner class Client(val socket: Socket, val output: OutputStream) {
        val queue = Channel<ByteArray>(CLIENT_QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
        @Volatile var closed = false

        fun close() {
            if (closed) return
            closed = true
            queue.close()
            runCatching { socket.close() }
            if (clients.remove(this)) listener.onClientCountChanged(clients.size)
        }
    }

    private val clients = CopyOnWriteArraySet<Client>()
    private val connectionCounter = AtomicInteger()
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val clientCount: Int get() = clients.size
    val hasClients: Boolean get() = clients.isNotEmpty()
    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun start() {
        if (serverSocket != null) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
            }
        } catch (e: IOException) {
            listener.onServerError("No se pudo abrir 127.0.0.1:$port (${e.message})")
            return
        }
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break // socket cerrado en stop()
                } catch (e: IOException) {
                    Log.w(TAG, "accept() falló", e)
                    continue
                }
                launch(Dispatchers.IO) { handleConnection(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (c in clients) {
            c.queue.trySend(encodeFrame(OP_CLOSE, byteArrayOf(0x03, 0xE9.toByte()))) // 1001 going away
            c.close()
        }
        clients.clear()
        listener.onClientCountChanged(0)
    }

    /** Envía un frame de texto a todos los clientes. Thread-safe y no bloqueante. */
    fun broadcast(text: String) {
        if (clients.isEmpty()) return
        val frame = encodeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
        for (c in clients) c.queue.trySend(frame)
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val request = readHttpHeaders(input) ?: run { socket.close(); return }
            val requestLine = request.firstOrNull().orEmpty()
            val headers = request.drop(1).mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }.toMap()

            val isUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            val key = headers["sec-websocket-key"]

            if (!requestLine.startsWith("GET ")) {
                writeHttp(output, "405 Method Not Allowed", "text/plain", "Solo GET")
                socket.close()
                return
            }
            if (!isUpgrade || key.isNullOrBlank()) {
                writeHttp(output, "200 OK", "application/json", listener.statusJson())
                socket.close()
                return
            }

            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(acceptKey(key)).append("\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
            socket.soTimeout = 0

            val client = Client(socket, output)
            clients.add(client)
            listener.onClientCountChanged(clients.size)
            Log.i(TAG, "Cliente WebSocket #${connectionCounter.incrementAndGet()} conectado")

            scope.launch(Dispatchers.IO) { writerLoop(client) }
            readerLoop(client, input)
        } catch (e: IOException) {
            runCatching { socket.close() }
        }
    }

    private suspend fun writerLoop(client: Client) {
        try {
            for (frame in client.queue) {
                client.output.write(frame)
                client.output.flush()
            }
        } catch (e: IOException) {
            // cliente desconectado
        } finally {
            client.close()
        }
    }

    private fun readerLoop(client: Client, input: InputStream) {
        val header = ByteArray(8)
        val mask = ByteArray(4)
        var fragmentOpcode = -1
        val fragments = java.io.ByteArrayOutputStream()
        try {
            while (!client.closed) {
                readFully(input, header, 2)
                val b0 = header[0].toInt() and 0xFF
                val b1 = header[1].toInt() and 0xFF
                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) {
                    readFully(input, header, 2)
                    length = (((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)).toLong()
                } else if (length == 127L) {
                    readFully(input, header, 8)
                    length = 0
                    for (i in 0 until 8) length = (length shl 8) or (header[i].toLong() and 0xFF)
                }
                if (!masked || length > MAX_CLIENT_PAYLOAD) {
                    // RFC 6455 §5.1: los frames de cliente deben venir enmascarados
                    client.queue.trySend(encodeFrame(OP_CLOSE, byteArrayOf(0x03, 0xEA.toByte()))) // 1002
                    break
                }
                readFully(input, mask, 4)
                val payload = ByteArray(length.toInt())
                readFully(input, payload, payload.size)
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

                when (opcode) {
                    OP_CLOSE -> {
                        val code = if (payload.size >= 2) payload.copyOfRange(0, 2) else ByteArray(0)
                        client.queue.trySend(encodeFrame(OP_CLOSE, code))
                        break
                    }
                    OP_PING -> client.queue.trySend(encodeFrame(OP_PONG, payload))
                    OP_PONG -> Unit
                    OP_TEXT, OP_BINARY -> {
                        if (fin) {
                            if (opcode == OP_TEXT) listener.onClientText(String(payload, Charsets.UTF_8))
                        } else {
                            fragmentOpcode = opcode
                            fragments.reset()
                            fragments.write(payload)
                        }
                    }
                    OP_CONTINUATION -> {
                        if (fragmentOpcode < 0) continue
                        fragments.write(payload)
                        if (fragments.size() > MAX_CLIENT_PAYLOAD) break
                        if (fin) {
                            if (fragmentOpcode == OP_TEXT) listener.onClientText(fragments.toString("UTF-8"))
                            fragmentOpcode = -1
                            fragments.reset()
                        }
                    }
                    else -> break
                }
            }
        } catch (e: IOException) {
            // desconexión
        } finally {
            // Dejar que el writer mande el close pendiente antes de cortar el socket
            client.queue.close()
        }
    }

    private fun readHttpHeaders(input: InputStream): List<String>? {
        val buf = java.io.ByteArrayOutputStream(512)
        var last4 = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) {
                return buf.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun writeHttp(output: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readFully(input: InputStream, target: ByteArray, count: Int) {
        var read = 0
        while (read < count) {
            val n = input.read(target, read, count - read)
            if (n < 0) throw EOFException()
            read += n
        }
    }
}
