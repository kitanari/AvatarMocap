package com.avatarmocap.output

import com.avatarmocap.tracking.FaceFrame
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** Destino de los datos de tracking (un protocolo). */
interface OutputSink {
    val name: String
    val status: StateFlow<String>
    /** true si hay una PC recibiendo los datos. */
    val connected: Boolean get() = false
    /** Se llama desde el hilo de la cámara: no debe bloquear. */
    fun send(frame: FaceFrame)
    fun close()
}

object NetUtils {
    /** IPs IPv4 locales del teléfono (para mostrarlas y escribirlas en la PC). */
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }
}
