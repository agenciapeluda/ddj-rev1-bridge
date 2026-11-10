package com.peluda.rev1bridge

/**
 * Superficie del jog que generó el movimiento.
 */
enum class JogSurface(val jsonName: String) {
    /** Borde / plato sin tocar la parte de arriba (pitch bend). */
    PLATTER("platter"),
    /** Parte de arriba tocada (scratch). */
    SCRATCH("scratch"),
    /** Borde con SHIFT. */
    PLATTER_SHIFT("platter_shift"),
    /** Parte de arriba con SHIFT. */
    SCRATCH_SHIFT("scratch_shift"),
}

/**
 * Mapa de controles de la DDJ-REV1 que necesita el parser.
 *
 * Los CC de jog siguen la convención de los DDJ de Pioneer (0x21 borde, 0x22 scratch, 0x23 / 0x29
 * con SHIFT) en los canales de deck 0..3. Cada evento JSON incluye "ch" y "cc", así que cualquier
 * diferencia con el firmware real se ve en el primer giro y se corrige acá.
 */
object Rev1Map {
    /** Canales MIDI de deck (0-based): 0 = deck 1, 1 = deck 2, 2 = deck 3, 3 = deck 4. */
    val DECK_CHANNELS = 0..3

    val JOG_CONTROLLERS: Map<Int, JogSurface> = mapOf(
        0x21 to JogSurface.PLATTER,
        0x22 to JogSurface.SCRATCH,
        0x23 to JogSurface.PLATTER_SHIFT,
        0x29 to JogSurface.SCRATCH_SHIFT,
    )

    /** Nota de "jog tocado" (Note On 127 al apoyar el dedo, 0 al soltar). */
    const val JOG_TOUCH_NOTE = 0x36

    fun deckForChannel(channel: Int): Int? =
        if (channel in DECK_CHANNELS) channel + 1 else null
}

/**
 * Codificación de los valores relativos del jog.
 */
enum class JogEncoding(val jsonName: String) {
    /** Horario 0x01..0x3F (+1..+63), antihorario 0x7F..0x41 (-1..-63). */
    TWOS_COMPLEMENT("twos"),
    /** Centro en 0x40: 0x41 = +1, 0x3F = -1. */
    OFFSET_64("offset64"),
    /**
     * Decide sola: interpreta cada valor con la codificación que da el delta más chico (los giros
     * lentos dominan) y fija la codificación tras [Rev1Parser.AUTO_LATCH_SAMPLES] muestras.
     */
    AUTO("auto");

    fun decode(value: Int): Int = when (this) {
        TWOS_COMPLEMENT -> if (value >= 0x40) value - 0x80 else value
        OFFSET_64 -> value - 0x40
        AUTO -> throw IllegalStateException("AUTO se resuelve en Rev1Parser")
    }
}

/**
 * Evento MIDI decodificado. [toRawBytes] devuelve el mensaje MIDI completo (sin running status),
 * listo para el puerto virtual; [toJson] la representación para los clientes WebSocket.
 */
sealed class MidiEvent {
    abstract val channel: Int
    abstract val timestampNanos: Long
    abstract fun toRawBytes(): ByteArray
    abstract fun toJson(): String

    data class NoteOn(
        override val channel: Int,
        val note: Int,
        val velocity: Int,
        override val timestampNanos: Long,
    ) : MidiEvent() {
        override fun toRawBytes() = byteArrayOf((0x90 or channel).toByte(), note.toByte(), velocity.toByte())
        override fun toJson() = json("note_on", channel, timestampNanos) {
            append(",\"note\":").append(note)
            append(",\"vel\":").append(velocity)
            if (note == Rev1Map.JOG_TOUCH_NOTE) append(",\"jog_touch\":true")
        }
    }

    data class NoteOff(
        override val channel: Int,
        val note: Int,
        val velocity: Int,
        override val timestampNanos: Long,
    ) : MidiEvent() {
        override fun toRawBytes() = byteArrayOf((0x80 or channel).toByte(), note.toByte(), velocity.toByte())
        override fun toJson() = json("note_off", channel, timestampNanos) {
            append(",\"note\":").append(note)
            append(",\"vel\":").append(velocity)
            if (note == Rev1Map.JOG_TOUCH_NOTE) append(",\"jog_touch\":false")
        }
    }

    data class ControlChange(
        override val channel: Int,
        val controller: Int,
        val value: Int,
        override val timestampNanos: Long,
    ) : MidiEvent() {
        override fun toRawBytes() = byteArrayOf((0xB0 or channel).toByte(), controller.toByte(), value.toByte())
        override fun toJson() = json("cc", channel, timestampNanos) {
            append(",\"cc\":").append(controller)
            append(",\"value\":").append(value)
        }
    }

    /** Movimiento de jog ya traducido a delta con signo (positivo = horario). */
    data class Jog(
        override val channel: Int,
        val controller: Int,
        val rawValue: Int,
        val delta: Int,
        val surface: JogSurface,
        val encoding: JogEncoding,
        override val timestampNanos: Long,
    ) : MidiEvent() {
        override fun toRawBytes() = byteArrayOf((0xB0 or channel).toByte(), controller.toByte(), rawValue.toByte())
        override fun toJson() = json("jog", channel, timestampNanos) {
            append(",\"cc\":").append(controller)
            append(",\"value\":").append(rawValue)
            append(",\"delta\":").append(delta)
            append(",\"surface\":\"").append(surface.jsonName).append('"')
            append(",\"enc\":\"").append(encoding.jsonName).append('"')
        }
    }

    /** Pitch bend de 14 bits (0..16383, centro 8192). */
    data class PitchBend(
        override val channel: Int,
        val value: Int,
        override val timestampNanos: Long,
    ) : MidiEvent() {
        override fun toRawBytes() =
            byteArrayOf((0xE0 or channel).toByte(), (value and 0x7F).toByte(), ((value shr 7) and 0x7F).toByte())

        override fun toJson() = json("pitch_bend", channel, timestampNanos) {
            append(",\"value\":").append(value)
        }
    }

    /** Resto de mensajes de canal (Program Change, aftertouch). Se reenvían tal cual. */
    data class ChannelMessage(
        val status: Int,
        val data1: Int,
        val data2: Int,
        val length: Int,
        override val timestampNanos: Long,
    ) : MidiEvent() {
        override val channel: Int get() = status and 0x0F

        override fun toRawBytes(): ByteArray = when (length) {
            2 -> byteArrayOf(status.toByte(), data1.toByte())
            else -> byteArrayOf(status.toByte(), data1.toByte(), data2.toByte())
        }

        override fun toJson() = json("channel_msg", channel, timestampNanos) {
            append(",\"status\":").append(status)
            append(",\"d1\":").append(data1)
            if (length == 3) append(",\"d2\":").append(data2)
        }
    }
}

private inline fun json(
    type: String,
    channel: Int,
    timestampNanos: Long,
    body: StringBuilder.() -> Unit,
): String {
    val sb = StringBuilder(112)
    sb.append("{\"type\":\"").append(type).append('"')
    sb.append(",\"ch\":").append(channel)
    val deck = Rev1Map.deckForChannel(channel)
    if (deck != null) sb.append(",\"deck\":").append(deck)
    sb.body()
    sb.append(",\"ts\":").append(timestampNanos)
    sb.append('}')
    return sb.toString()
}

/**
 * Parser MIDI en tiempo real para la DDJ-REV1.
 *
 * - [parseUsbMidi]: paquetes USB-MIDI de 4 bytes (Cable/CIN + 3 bytes), lo que entrega el endpoint
 *   bulk IN de la interfaz MIDIStreaming.
 * - [parseRawMidi]: stream MIDI crudo (con running status).
 *
 * No es thread-safe: se usa desde el único hilo de lectura USB.
 */
class Rev1Parser(
    private val jogEncoding: JogEncoding = JogEncoding.AUTO,
    private val jogControllers: Map<Int, JogSurface> = Rev1Map.JOG_CONTROLLERS,
) {
    companion object {
        const val AUTO_LATCH_SAMPLES = 16
    }

    // Estado del framer MIDI
    private var status = 0
    private var expectedData = 0
    private var dataCount = 0
    private var data1 = 0
    private var inSysEx = false

    // Estado de la detección automática del jog
    @Volatile
    private var latchedEncoding: JogEncoding? =
        if (jogEncoding == JogEncoding.AUTO) null else jogEncoding
    private var autoSamples = 0
    private var autoScoreTwos = 0L
    private var autoScoreOffset = 0L

    /** Codificación de jog en uso (null mientras AUTO todavía no se fijó). */
    val activeJogEncoding: JogEncoding? get() = latchedEncoding

    fun parseUsbMidi(buffer: ByteArray, length: Int, timestampNanos: Long): List<MidiEvent> {
        val out = ArrayList<MidiEvent>(maxOf(1, length / 4))
        var i = 0
        while (i + 4 <= length) {
            val cin = buffer[i].toInt() and 0x0F
            val size = when (cin) {
                0x5, 0xF -> 1
                0x2, 0x6, 0xC, 0xD -> 2
                0x3, 0x4, 0x7, 0x8, 0x9, 0xA, 0xB, 0xE -> 3
                else -> 0 // 0x0 / 0x1 reservados (incluye el padding de ceros)
            }
            for (k in 1..size) feed(buffer[i + k].toInt() and 0xFF, timestampNanos, out)
            i += 4
        }
        return out
    }

    fun parseRawMidi(buffer: ByteArray, offset: Int, length: Int, timestampNanos: Long): List<MidiEvent> {
        val out = ArrayList<MidiEvent>(maxOf(1, length / 3))
        for (i in offset until offset + length) feed(buffer[i].toInt() and 0xFF, timestampNanos, out)
        return out
    }

    fun reset() {
        status = 0
        expectedData = 0
        dataCount = 0
        inSysEx = false
    }

    private fun feed(b: Int, ts: Long, out: MutableList<MidiEvent>) {
        when {
            b >= 0xF8 -> return // System Real-Time: no corta mensajes en curso, se ignora
            b == 0xF0 -> {
                inSysEx = true
                status = 0
                dataCount = 0
            }
            b == 0xF7 -> {
                inSysEx = false
                status = 0
                dataCount = 0
            }
            b >= 0x80 -> {
                inSysEx = false
                status = b
                dataCount = 0
                expectedData = dataLength(b)
                if (b >= 0xF0) {
                    // System Common: sin running status; los de 0 bytes (F6) no interesan
                    if (expectedData == 0) status = 0
                }
            }
            else -> {
                if (inSysEx || status == 0) return
                if (dataCount == 0) {
                    data1 = b
                    dataCount = 1
                    if (expectedData == 1) complete(status, data1, 0, ts, out)
                } else {
                    complete(status, data1, b, ts, out)
                }
            }
        }
    }

    private fun complete(st: Int, d1: Int, d2: Int, ts: Long, out: MutableList<MidiEvent>) {
        dataCount = 0
        if (st >= 0xF0) {
            status = 0 // System Common (MTC, SPP, Song Select): no se reenvía
            return
        }
        val channel = st and 0x0F
        val event = when (st and 0xF0) {
            0x90 -> if (d2 == 0) MidiEvent.NoteOff(channel, d1, 0, ts) else MidiEvent.NoteOn(channel, d1, d2, ts)
            0x80 -> MidiEvent.NoteOff(channel, d1, d2, ts)
            0xB0 -> controlChange(channel, d1, d2, ts)
            0xE0 -> MidiEvent.PitchBend(channel, d1 or (d2 shl 7), ts)
            0xC0, 0xD0 -> MidiEvent.ChannelMessage(st, d1, 0, 2, ts)
            else -> MidiEvent.ChannelMessage(st, d1, d2, 3, ts) // 0xA0 aftertouch polifónico
        }
        out.add(event)
    }

    private fun controlChange(channel: Int, controller: Int, value: Int, ts: Long): MidiEvent {
        val surface = jogControllers[controller]
        if (surface == null || channel !in Rev1Map.DECK_CHANNELS) {
            return MidiEvent.ControlChange(channel, controller, value, ts)
        }
        val (encoding, delta) = decodeJog(value)
        return MidiEvent.Jog(channel, controller, value, delta, surface, encoding, ts)
    }

    private fun decodeJog(value: Int): Pair<JogEncoding, Int> {
        latchedEncoding?.let { return it to it.decode(value) }

        val twos = JogEncoding.TWOS_COMPLEMENT.decode(value)
        val offset = JogEncoding.OFFSET_64.decode(value)
        // 0x00 y 0x40 no aportan información (son "0" en una de las dos codificaciones)
        if (value != 0x00 && value != 0x40) {
            autoScoreTwos += kotlin.math.abs(twos)
            autoScoreOffset += kotlin.math.abs(offset)
            autoSamples++
            if (autoSamples >= AUTO_LATCH_SAMPLES) {
                latchedEncoding =
                    if (autoScoreOffset < autoScoreTwos) JogEncoding.OFFSET_64 else JogEncoding.TWOS_COMPLEMENT
            }
        }
        return if (kotlin.math.abs(offset) < kotlin.math.abs(twos)) {
            JogEncoding.OFFSET_64 to offset
        } else {
            JogEncoding.TWOS_COMPLEMENT to twos
        }
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
