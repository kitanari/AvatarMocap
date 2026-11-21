package com.avatarmocap.output

import com.avatarmocap.i18n.tr
import android.util.Log
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.FaceFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Emula el protocolo UDP de iFacialMocap, así cualquier programa que acepte iFacialMocap
 * (VSeeFace, VNyan, Warudo, Webcam Motion Receiver, VBridger, Blender/Faceit...) lo recibe
 * sin cambios.
 *
 *  - El teléfono escucha en UDP 49983.
 *  - La PC envía "iFacialMocap_sahuasouryya9218sauhuiayeta91555dy3719" (opcionalmente con
 *    "|sendDataVersion=v2") y desde ese momento le mandamos datos a su IP, puerto 49983.
 *  - Si se configura una IP fija, se envía ahí directamente sin esperar el handshake.
 *
 * Formato (v1):  nombre-valor|...|=head#pitch,yaw,roll,x,y,z|rightEye#x,y,z|leftEye#x,y,z|
 *   nombres ARKit con sufijos _L/_R; valores enteros 0..100; ángulos en grados.
 * En v2 el separador nombre/valor es '&'.
 */
class IFacialMocapServer(manualTargetIp: String) : OutputSink {

    override val name = "Face"
    private val _status = MutableStateFlow(tr("Esperando conexión desde la PC (puerto $PORT)…", "Waiting for the PC to connect (port $PORT)…"))
    override val status: StateFlow<String> = _status

    @Volatile private var target: InetAddress? = null
    /** PC conectada (por handshake o IP fija), o null. */
    val currentTarget: InetAddress? get() = target
    override val connected: Boolean get() = target != null
    @Volatile private var v2 = false
    @Volatile private var running = true
    private var socket: DatagramSocket? = null
    private val sb = StringBuilder(1500)
    private var sentCount = 0L

    init {
        try {
            socket = DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el puerto $PORT", e)
            _status.value = tr("Error: no se pudo abrir el puerto $PORT (${e.message})", "Error: couldn't open port $PORT (${e.message})")
        }
        if (manualTargetIp.isNotBlank()) {
            thread(name = "ifm-resolve") {
                try {
                    target = InetAddress.getByName(manualTargetIp.trim())
                    _status.value = tr("Enviando a ${manualTargetIp.trim()}:$PORT (IP fija)", "Sending to ${manualTargetIp.trim()}:$PORT (fixed IP)")
                } catch (e: Exception) {
                    _status.value = tr("IP inválida: $manualTargetIp", "Invalid IP: $manualTargetIp")
                }
            }
        }
        socket?.let { s -> thread(name = "ifm-listen", isDaemon = true) { listen(s) } }
    }

    private fun listen(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = String(p.data, 0, p.length, Charsets.UTF_8)
                if (text.contains(HANDSHAKE)) {
                    v2 = text.contains("sendDataVersion=v2")
                    if (target != p.address) {
                        target = p.address
                        _status.value = tr("Conectado a ${p.address.hostAddress} (${if (v2) "v2" else "v1"})", "Connected to ${p.address.hostAddress} (${if (v2) "v2" else "v1"})")
                    }
                }
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    override fun send(frame: FaceFrame) {
        val dst = target ?: return
        val s = socket ?: return
        if (!frame.faceFound) return // mantener la última pose en el receptor
        sb.setLength(0)
        val sep = if (v2) '&' else '-'
        for (i in 0 until ArKit.COUNT) {
            sb.append(IFM_NAMES[i]).append(sep).append((frame.blend[i] * 100f).roundToInt()).append('|')
        }
        val r = frame.headRot
        val p = frame.headPos
        sb.append("=head#")
            .append(f(r[0])).append(',').append(f(r[1])).append(',').append(f(r[2])).append(',')
            .append(f(p[0])).append(',').append(f(p[1])).append(',').append(f(p[2])).append('|')
        sb.append("rightEye#").append(f(frame.rightEye[0])).append(',').append(f(frame.rightEye[1])).append(",0|")
        sb.append("leftEye#").append(f(frame.leftEye[0])).append(',').append(f(frame.leftEye[1])).append(",0|")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        try {
            s.send(DatagramPacket(bytes, bytes.size, dst, PORT))
            if (++sentCount % 300 == 0L) {
                _status.value = tr("Enviando a ${dst.hostAddress}:$PORT · ${sentCount} paquetes", "Sending to ${dst.hostAddress}:$PORT · ${sentCount} packets")
            }
        } catch (e: Exception) {
            _status.value = tr("Error enviando: ${e.message}", "Send error: ${e.message}")
        }
    }

    override fun close() {
        running = false
        socket?.close()
    }

    companion object {
        private const val TAG = "IFM"
        const val PORT = 49983
        const val HANDSHAKE = "iFacialMocap_sahuasouryya9218sauhuiayeta91555dy3719"

        private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

        /** eyeBlinkLeft -> eyeBlink_L ... pero jawLeft / mouthLeft quedan igual (son direcciones). */
        val IFM_NAMES: Array<String> = Array(ArKit.COUNT) { i ->
            val n = ArKit.NAMES[i]
            when {
                n == "jawLeft" || n == "jawRight" || n == "mouthLeft" || n == "mouthRight" -> n
                n.endsWith("Left") -> n.removeSuffix("Left") + "_L"
                n.endsWith("Right") -> n.removeSuffix("Right") + "_R"
                else -> n
            }
        }
    }
}
