# DDJ-REV1 Bridge

Middleware Android (Kotlin) que toma la Pioneer DJ DDJ-REV1 por USB Host, decodifica su MIDI y lo
expone por dos vías:

1. **Puerto MIDI virtual** `DDJ-REV1 Virtual Bridge` (`android.media.midi`) para djay y cualquier app MIDI.
   Bidireccional: lo que la app manda al puerto (LEDs) vuelve a la controladora por el endpoint USB OUT.
2. **WebSocket** en `ws://127.0.0.1:8080` (TCP_NODELAY) para clientes IPC como Mixxx Scratch.

## Arquitectura

```
DDJ-REV1 ──USB bulk IN (64 B)──▶ UsbMidiDriver ──▶ Rev1Parser ──▶ MainBridgeService
                                                                     ├──▶ Rev1VirtualMidiService (djay)
                                                                     └──▶ LocalMidiServer (WebSocket)
djay ──puerto virtual IN──▶ UsbMidiPacketizer ──USB bulk OUT──▶ DDJ-REV1 (LEDs)
```

| Archivo | Rol |
|---|---|
| `UsbMidiDriver.kt` | USB Host: permisos asíncronos, claim de la interfaz MIDIStreaming, bucle `bulkTransfer` en `Dispatchers.IO` con buffer reutilizado, envío USB-MIDI |
| `Rev1Parser.kt` | Paquetes USB-MIDI → mensajes MIDI (running status, Note On/Off, CC, pitch bend) y jogs → delta con signo |
| `Rev1VirtualMidiService.kt` | `MidiDeviceService` del puerto virtual (singleton mientras una app lo tenga abierto) |
| `LocalMidiServer.kt` | WebSocket RFC 6455 sobre `ServerSocket` en 127.0.0.1:8080 |
| `MainBridgeService.kt` | Foreground Service (`connectedDevice`) que orquesta todo |
| `MainActivity.kt` | Estado y botones; se abre sola al enchufar la REV1 |

## Uso

1. Instalar el APK (artefacto `ddj-rev1-bridge-debug` del workflow **Android APK**).
2. Enchufar la REV1 → Android ofrece abrir *DDJ-REV1 Bridge* → aceptar (marcar "usar siempre").
3. En djay: elegir el dispositivo MIDI **DDJ-REV1 Virtual Bridge**.
4. Clientes WebSocket: conectar a `ws://127.0.0.1:8080`. `GET http://127.0.0.1:8080/status` devuelve el estado.

> Al tomar la interfaz con `claimInterface(force = true)` el driver MIDI del sistema suelta la REV1:
> mientras el puente está activo, las apps la ven **solo** a través del puerto virtual.

## Protocolo WebSocket

Un frame de texto por evento, JSON plano. `ts` = `System.nanoTime()` del momento de la lectura USB.

```json
{"type":"jog","ch":0,"deck":1,"cc":34,"value":126,"delta":-2,"surface":"scratch","enc":"twos","ts":123}
{"type":"note_on","ch":0,"deck":1,"note":54,"vel":127,"jog_touch":true,"ts":123}
{"type":"note_off","ch":0,"deck":1,"note":11,"vel":0,"ts":123}
{"type":"cc","ch":6,"cc":31,"value":64,"ts":123}
{"type":"pitch_bend","ch":0,"deck":1,"value":8192,"ts":123}
```

- `delta` > 0 = horario. `surface`: `platter`, `scratch`, `platter_shift`, `scratch_shift`.
- `deck` aparece solo en los canales 0–3.
- `enc`: codificación del jog. Por defecto el parser la detecta sola (`AUTO`) y la fija tras 16 muestras:
  `twos` (horario 0x01..0x3F, antihorario 0x7F..0x41) u `offset64` (centro en 0x40).
  Se puede forzar con `Rev1Parser(JogEncoding.TWOS_COMPLEMENT)`.

## Mapa de controles

`Rev1Map` en `Rev1Parser.kt` define qué CC son jogs (0x21 borde, 0x22 scratch, 0x23 / 0x29 con SHIFT,
canales 0–3) y la nota de jog tocado (0x36). Si algún control de tu firmware usa otro número, se ve
en el campo `cc` del JSON al primer giro y se ajusta ahí.

## Build

```
./gradlew assembleDebug
```

compileSdk 34 · minSdk 26 · targetSdk 34 · Kotlin 1.9.23 · AGP 8.3.2 · Gradle 8.7 · JDK 17.
