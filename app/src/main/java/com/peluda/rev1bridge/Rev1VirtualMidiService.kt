package com.peluda.rev1bridge

import android.media.midi.MidiDeviceService
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiReceiver
import android.util.Log
import java.io.IOException

/**
 * Puerto MIDI virtual "DDJ-REV1 Virtual Bridge" que ven djay y cualquier app con android.media.midi.
 *
 * El sistema crea este servicio cuando una app abre el dispositivo virtual. Mientras viva, queda
 * registrado como singleton para que [MainBridgeService] le inyecte los eventos de la REV1.
 *
 * - Puerto de salida 0: eventos de la controladora → apps.
 * - Puerto de entrada 0: lo que mandan las apps (LEDs) → [outboundSink] → USB OUT de la REV1.
 */
class Rev1VirtualMidiService : MidiDeviceService() {

    companion object {
        private const val TAG = "Rev1VirtualMidi"

        @Volatile
        private var instance: Rev1VirtualMidiService? = null

        /** Destino de los mensajes que las apps mandan al puerto de entrada (lo setea MainBridgeService). */
        @Volatile
        var outboundSink: ((data: ByteArray, offset: Int, count: Int) -> Unit)? = null

        /** true si alguna app tiene abierto el dispositivo virtual. */
        val isAvailable: Boolean get() = instance != null

        /**
         * Envía MIDI crudo a las apps conectadas al puerto de salida.
         * @return false si no hay ninguna app conectada.
         */
        fun dispatchToApps(data: ByteArray, offset: Int, count: Int, timestampNanos: Long): Boolean =
            instance?.sendToApps(data, offset, count, timestampNanos) ?: false
    }

    private val inputReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            outboundSink?.invoke(msg, offset, count)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MainBridgeService.updateStatus { it.copy(midiServiceAlive = true) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        MainBridgeService.updateStatus { it.copy(midiServiceAlive = false, midiAppsConnected = 0) }
        super.onDestroy()
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(inputReceiver)

    override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
        val openOutputs = status.getOutputPortOpenCount(0)
        val openInputs = if (status.isInputPortOpen(0)) 1 else 0
        MainBridgeService.updateStatus { it.copy(midiAppsConnected = maxOf(openOutputs, openInputs)) }
    }

    private fun sendToApps(data: ByteArray, offset: Int, count: Int, timestampNanos: Long): Boolean {
        val receivers = outputPortReceivers
        if (receivers.isNullOrEmpty()) return false
        return try {
            receivers[0].send(data, offset, count, timestampNanos)
            true
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo enviar al puerto virtual", e)
            false
        }
    }
}
