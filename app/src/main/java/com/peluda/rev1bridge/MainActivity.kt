package com.peluda.rev1bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pantalla mínima de control: arranca/detiene el puente y muestra su estado.
 * También es el destino de USB_DEVICE_ATTACHED, así que al enchufar la REV1 se abre sola.
 */
class MainActivity : Activity() {

    private val scope: CoroutineScope = MainScope()
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        requestNotificationPermission()

        scope.launch {
            MainBridgeService.status.collect { render(it) }
        }

        // Al abrir la app (o al enchufar la controladora) el puente arranca directo
        MainBridgeService.start(this, actionFor(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        MainBridgeService.start(this, actionFor(intent))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun actionFor(intent: Intent?): String =
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            MainBridgeService.ACTION_RESCAN
        } else {
            MainBridgeService.ACTION_START
        }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun render(s: BridgeStatus) {
        val usb = when (s.usbState) {
            UsbMidiDriver.State.CONNECTED -> "conectada · ${s.deviceName.orEmpty()}"
            UsbMidiDriver.State.WAITING_PERMISSION -> "esperando permiso"
            UsbMidiDriver.State.PERMISSION_DENIED -> "permiso denegado (tocá Buscar controladora)"
            UsbMidiDriver.State.NOT_FOUND -> "no se encontró una DDJ-REV1"
            UsbMidiDriver.State.DISCONNECTED -> "desconectada"
            UsbMidiDriver.State.ERROR -> "error al abrir la interfaz MIDI"
            UsbMidiDriver.State.IDLE -> "inactivo"
        }
        val ws = s.webSocketError ?: "ws://127.0.0.1:${LocalMidiServer.DEFAULT_PORT} · ${s.webSocketClients} cliente(s)"
        val midi = when {
            s.midiAppsConnected > 0 -> "${s.midiAppsConnected} app(s) conectada(s)"
            s.midiServiceAlive -> "dispositivo abierto, sin puertos en uso"
            else -> "ninguna app abrió \"DDJ-REV1 Virtual Bridge\""
        }
        statusView.text = buildString {
            append("Puente     ").append(if (s.running) "ACTIVO" else "detenido").append('\n')
            append("USB        ").append(usb).append('\n')
            append("WebSocket  ").append(ws).append('\n')
            append("MIDI       ").append(midi).append('\n')
            append("Eventos    ").append(s.eventsForwarded).append('\n')
            append("Jog        ").append(s.jogEncoding ?: "detectando…")
        }
    }

    private fun buildLayout(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111418"))
            setPadding(dp(24), dp(32), dp(24), dp(24))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#C9D1D9"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(20), 0, dp(24))
            setLineSpacing(0f, 1.25f)
        }
        root.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
            text = getString(label)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        root.addView(button(R.string.button_start) { MainBridgeService.start(this) }, params)
        root.addView(button(R.string.button_rescan) { MainBridgeService.start(this, MainBridgeService.ACTION_RESCAN) }, params)
        root.addView(button(R.string.button_stop) { MainBridgeService.stop(this) }, params)
        return root
    }
}
