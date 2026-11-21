package com.avatarmocap.output

import com.avatarmocap.i18n.tr
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.FaceFrame
import com.avatarmocap.tracking.Vrm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

/**
 * Protocolo VMC (OSC sobre UDP, puerto 39539 por defecto): envía las expresiones VRM calibradas
 * (A, I, U, E, O, Blink, Joy, Angry, Sorrow, Fun, Surprised) directo a los "blendshape clips"
 * del modelo. Funciona con VSeeFace (receptor VMC), VNyan, Warudo, VirtualMotionCapture...
 *
 * Todo va en un único paquete OSC "bundle" por cuadro.
 */
class VmcSender(
    manualHost: String,
    private val port: Int,
    private val vrm1: Boolean,
    private val sendArkit: Boolean,
    private val sendBones: Boolean,
    /** Si no hay IP fija, se usa la PC conectada por iFacialMocap. */
    private val fallbackTarget: () -> InetAddress?,
) : OutputSink {

    override val name = "VRM"
    private val _status = MutableStateFlow("")
    override val status: StateFlow<String> = _status

    private val socket = DatagramSocket()
    @Volatile private var manualTarget: InetAddress? = null
    private val names = if (vrm1) Vrm.NAMES_V1 else Vrm.NAMES_V0
    private val out = ByteArrayOutputStream(4096)
    private val msg = ByteArrayOutputStream(128)
    private val start = System.nanoTime()
    private var count = 0L
    @Volatile private var lastSent = 0L
    override val connected: Boolean get() = System.currentTimeMillis() - lastSent < 1500

    init {
        if (manualHost.isNotBlank()) {
            _status.value = tr("Resolviendo $manualHost…", "Resolving $manualHost…")
            thread(name = "vmc-resolve") {
                try {
                    manualTarget = InetAddress.getByName(manualHost.trim())
                    _status.value = tr("Enviando a ${manualHost.trim()}:$port", "Sending to ${manualHost.trim()}:$port")
                } catch (e: Exception) {
                    _status.value = tr("IP inválida: $manualHost", "Invalid IP: $manualHost")
                }
            }
        } else {
            _status.value = tr("Esperando a que la PC se conecte (o poné una IP fija)", "Waiting for the PC to connect (or set a fixed IP)")
        }
    }

    override fun send(frame: FaceFrame) {
        if (!frame.faceFound) return
        val dst = manualTarget ?: fallbackTarget() ?: return
        out.reset()
        out.write("#bundle\u0000".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)) // "inmediatamente"

        element("/VMC/Ext/OK") { int(1) }
        element("/VMC/Ext/T") { float((System.nanoTime() - start) / 1e9f) }
        for (i in 0 until Vrm.COUNT) element("/VMC/Ext/Blend/Val") { str(names[i]); float(frame.vrm[i]) }
        // La lengua no es una expresión estándar de VRM: se manda como clip propio "TongueOut"
        if (!sendArkit) element("/VMC/Ext/Blend/Val") { str("TongueOut"); float(frame.blend[ArKit.TONGUE_OUT]) }
        if (sendArkit) {
            for (i in 0 until ArKit.COUNT) element("/VMC/Ext/Blend/Val") { str(PASCAL[i]); float(frame.blend[i]) }
        }
        element("/VMC/Ext/Blend/Apply") { }
        if (sendBones) {
            val r = frame.headRot
            // Mitad del giro al cuello y mitad a la cabeza: se ve más natural
            bone("Neck", r[0] / 2f, r[1] / 2f, r[2] / 2f)
            bone("Head", r[0] / 2f, r[1] / 2f, r[2] / 2f)
            bone("LeftEye", frame.leftEye[0], frame.leftEye[1], 0f)
            bone("RightEye", frame.rightEye[0], frame.rightEye[1], 0f)
        }

        val bytes = out.toByteArray()
        try {
            socket.send(DatagramPacket(bytes, bytes.size, dst, port))
            lastSent = System.currentTimeMillis()
            if (++count % 300 == 0L || count == 1L) {
                _status.value = tr("Enviando a ${dst.hostAddress}:$port · $count paquetes", "Sending to ${dst.hostAddress}:$port · $count packets")
            }
        } catch (e: Exception) {
            _status.value = tr("Error enviando: ${e.message}", "Send error: ${e.message}")
        }
    }

    // ---------- Codificación OSC

    private inner class Args {
        val tags = StringBuilder(",")
        val data = ByteArrayOutputStream(64)
        fun int(v: Int) { tags.append('i'); data.write(ByteBuffer.allocate(4).putInt(v).array()) }
        fun float(v: Float) { tags.append('f'); data.write(ByteBuffer.allocate(4).putFloat(v).array()) }
        fun str(v: String) { tags.append('s'); data.write(oscString(v)) }
    }

    private fun element(address: String, build: Args.() -> Unit) {
        val a = Args().apply(build)
        msg.reset()
        msg.write(oscString(address))
        msg.write(oscString(a.tags.toString()))
        msg.write(a.data.toByteArray())
        out.write(ByteBuffer.allocate(4).putInt(msg.size()).array())
        msg.writeTo(out)
    }

    /** Rotación local (grados, orden de Unity: Z, X, Y) como cuaternión. */
    private fun bone(name: String, pitch: Float, yaw: Float, roll: Float) {
        val q = eulerToQuat(pitch, yaw, roll)
        element("/VMC/Ext/Bone/Pos") {
            str(name)
            float(0f); float(0f); float(0f)
            float(q[0]); float(q[1]); float(q[2]); float(q[3])
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        /** Nombres "perfect sync" (EyeBlinkLeft, JawOpen...). */
        val PASCAL = Array(ArKit.COUNT) { ArKit.NAMES[it].replaceFirstChar { c -> c.uppercaseChar() } }

        private fun oscString(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            val len = (b.size / 4 + 1) * 4 // al menos un byte nulo, múltiplo de 4
            return b.copyOf(len)
        }

        /** Euler de Unity (Quaternion.Euler: Z, luego X, luego Y). Devuelve x, y, z, w. */
        fun eulerToQuat(xDeg: Float, yDeg: Float, zDeg: Float): FloatArray {
            val d = (Math.PI / 360.0).toFloat()
            val cx = cos(xDeg * d); val sx = sin(xDeg * d)
            val cy = cos(yDeg * d); val sy = sin(yDeg * d)
            val cz = cos(zDeg * d); val sz = sin(zDeg * d)
            // q = qY * qX * qZ
            val x = cy * sx * cz + sy * cx * sz
            val y = sy * cx * cz - cy * sx * sz
            val z = cy * cx * sz - sy * sx * cz
            val w = cy * cx * cz + sy * sx * sz
            return floatArrayOf(x, y, z, w)
        }
    }
}
