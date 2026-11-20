package com.avatarmocap.output

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.FaceFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Se conecta a VTube Studio (PC) como plugin por la API pública (WebSocket, puerto 8001 por defecto)
 * e inyecta los parámetros de tracking.
 *
 * Además de los parámetros estándar de VTS (FaceAngleX, MouthOpen, EyeOpenLeft, TongueOut...)
 * crea parámetros personalizados con los 52 blendshapes crudos ("AM" + nombre, p.ej. AMjawOpen)
 * y la dirección de la lengua (AMTongueX / AMTongueY), para riggear modelos Live2D avanzados
 * al estilo VBridger.
 *
 * Requisitos en VTS: Ajustes -> "Start API (allow plugins)" activado y aceptar el popup del plugin.
 */
class VTubeStudioPlugin(
    host: String,
    port: Int,
    private var token: String,
    private val onNewToken: (String) -> Unit,
) : OutputSink {

    override val name = "VTube Studio"
    private val _status = MutableStateFlow("Conectando a $host:$port…")
    override val status: StateFlow<String> = _status

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private val url = "ws://$host:$port"
    @Volatile private var ws: WebSocket? = null
    @Volatile private var authenticated = false
    @Volatile private var closed = false
    private var reqId = 0
    private var lastSendNanos = 0L
    private var lastFaceFound = true

    init { connect() }

    private fun connect() {
        if (closed) return
        ws = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _status.value = "Conectado. Autenticando…"
            if (token.isNotEmpty()) authenticate() else requestToken()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try { handle(JSONObject(text)) } catch (e: Exception) { Log.w(TAG, "Mensaje inválido", e) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authenticated = false
            if (closed) return
            _status.value = "Sin conexión (${t.message}). Reintentando en 3 s…"
            Thread { Thread.sleep(3000); connect() }.start()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            if (!closed) _status.value = "Conexión cerrada por VTS ($reason)"
        }
    }

    private fun handle(msg: JSONObject) {
        val data = msg.optJSONObject("data") ?: JSONObject()
        when (msg.optString("messageType")) {
            "AuthenticationTokenResponse" -> {
                token = data.optString("authenticationToken")
                onNewToken(token)
                authenticate()
            }
            "AuthenticationResponse" -> {
                if (data.optBoolean("authenticated")) {
                    authenticated = true
                    _status.value = "Autenticado ✓ — enviando tracking"
                    createCustomParameters()
                } else {
                    _status.value = "Token rechazado, pidiendo uno nuevo (aceptá el popup en VTS)…"
                    token = ""
                    onNewToken("")
                    requestToken()
                }
            }
            "APIError" -> {
                val m = data.optString("message")
                // Errores de parámetros inexistentes al arrancar son normales; el resto se muestra.
                if (!m.contains("parameter", ignoreCase = true)) _status.value = "VTS: $m"
            }
        }
    }

    private fun request(type: String, data: JSONObject? = null) {
        val msg = JSONObject()
            .put("apiName", "VTubeStudioPublicAPI")
            .put("apiVersion", "1.0")
            .put("requestID", "am${reqId++}")
            .put("messageType", type)
        if (data != null) msg.put("data", data)
        ws?.send(msg.toString())
    }

    private fun requestToken() {
        _status.value = "Pedí permiso en VTube Studio: aceptá el popup en la PC"
        request("AuthenticationTokenRequest", JSONObject()
            .put("pluginName", PLUGIN_NAME)
            .put("pluginDeveloper", PLUGIN_DEV))
    }

    private fun authenticate() {
        request("AuthenticationRequest", JSONObject()
            .put("pluginName", PLUGIN_NAME)
            .put("pluginDeveloper", PLUGIN_DEV)
            .put("authenticationToken", token))
    }

    private fun createCustomParameters() {
        for (n in ArKit.NAMES) createParam(customName(n), "ARKit $n (AvatarMocap)", 0.0, 1.0, 0.0)
        createParam("AMTongueX", "Dirección horizontal de la lengua", -1.0, 1.0, 0.0)
        createParam("AMTongueY", "Dirección vertical de la lengua", -1.0, 1.0, 0.0)
    }

    private fun createParam(name: String, explanation: String, min: Double, max: Double, def: Double) {
        request("ParameterCreationRequest", JSONObject()
            .put("parameterName", name)
            .put("explanation", explanation)
            .put("min", min)
            .put("max", max)
            .put("defaultValue", def))
    }

    override fun send(frame: FaceFrame) {
        if (!authenticated) return
        // Si se pierde la cara, avisamos una vez y luego dejamos de inyectar.
        if (!frame.faceFound) {
            if (lastFaceFound) {
                lastFaceFound = false
                request("InjectParameterDataRequest", JSONObject()
                    .put("faceFound", false).put("mode", "set").put("parameterValues", JSONArray()))
            }
            return
        }
        lastFaceFound = true
        // Límite ~30 envíos/s (VTS interpola a 60 fps por su cuenta)
        if (frame.timestampNanos - lastSendNanos < 32_000_000L) return
        lastSendNanos = frame.timestampNanos

        val b = frame.blend
        val values = JSONArray()
        fun put(id: String, v: Float) = values.put(JSONObject().put("id", id).put("value", v.toDouble()))

        // ---- Parámetros estándar de VTube Studio
        put("FaceAngleX", frame.headRot[1])
        put("FaceAngleY", frame.headRot[0])
        put("FaceAngleZ", frame.headRot[2])
        put("FacePositionX", frame.headPos[0] * 100f)
        put("FacePositionY", frame.headPos[1] * 100f)
        put("FacePositionZ", frame.headPos[2] * 100f)
        put("MouthOpen", b[ArKit.JAW_OPEN])
        val smile = (b[ArKit.SMILE_L] + b[ArKit.SMILE_R]) / 2f
        val frown = (b[ArKit.FROWN_L] + b[ArKit.FROWN_R]) / 2f
        put("MouthSmile", (0.5f + 0.5f * (smile - frown)).coerceIn(0f, 1f))
        put("MouthX", (b[ArKit.MOUTH_RIGHT] - b[ArKit.MOUTH_LEFT]).coerceIn(-1f, 1f))
        put("EyeOpenLeft", (1f - b[ArKit.EYE_BLINK_L] + 0.25f * b[ArKit.EYE_WIDE_L]).coerceIn(0f, 1f))
        put("EyeOpenRight", (1f - b[ArKit.EYE_BLINK_R] + 0.25f * b[ArKit.EYE_WIDE_R]).coerceIn(0f, 1f))
        put("EyeLeftX", (b[ArKit.LOOK_OUT_L] - b[ArKit.LOOK_IN_L]).coerceIn(-1f, 1f))
        put("EyeLeftY", (b[ArKit.LOOK_UP_L] - b[ArKit.LOOK_DOWN_L]).coerceIn(-1f, 1f))
        put("EyeRightX", (b[ArKit.LOOK_IN_R] - b[ArKit.LOOK_OUT_R]).coerceIn(-1f, 1f))
        put("EyeRightY", (b[ArKit.LOOK_UP_R] - b[ArKit.LOOK_DOWN_R]).coerceIn(-1f, 1f))
        val browL = 0.5f + 0.5f * (max(b[ArKit.BROW_OUTER_UP_L], b[ArKit.BROW_INNER_UP]) - b[ArKit.BROW_DOWN_L])
        val browR = 0.5f + 0.5f * (max(b[ArKit.BROW_OUTER_UP_R], b[ArKit.BROW_INNER_UP]) - b[ArKit.BROW_DOWN_R])
        put("BrowLeftY", browL.coerceIn(0f, 1f))
        put("BrowRightY", browR.coerceIn(0f, 1f))
        put("Brows", ((browL + browR) / 2f).coerceIn(0f, 1f))
        put("CheekPuff", b[ArKit.CHEEK_PUFF])
        put("TongueOut", b[ArKit.TONGUE_OUT])

        // ---- Parámetros personalizados (52 ARKit crudos + lengua)
        for (i in 0 until ArKit.COUNT) put(CUSTOM_NAMES[i], b[i])
        put("AMTongueX", frame.tongueX)
        put("AMTongueY", frame.tongueY)

        request("InjectParameterDataRequest", JSONObject()
            .put("faceFound", true)
            .put("mode", "set")
            .put("parameterValues", values))
    }

    override fun close() {
        closed = true
        authenticated = false
        ws?.close(1000, "bye")
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "VTS"
        const val PLUGIN_NAME = "AvatarMocap"
        const val PLUGIN_DEV = "AvatarMocap"

        /** "AM" + nombre ARKit. VTS acepta 4..32 caracteres alfanuméricos. */
        fun customName(arkit: String) = "AM$arkit"
        private val CUSTOM_NAMES = Array(ArKit.COUNT) { customName(ArKit.NAMES[it]) }

        /**
         * VTube Studio anuncia su API por broadcast UDP en el puerto 47779 cada ~2 s.
         * Bloquea hasta [timeoutMs]; devuelve (ip, puerto) o null. Llamar fuera del hilo principal.
         */
        fun discover(context: Context, timeoutMs: Int = 5000): Pair<String, Int>? {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("vts-discovery").apply { setReferenceCounted(false); acquire() }
            try {
                DatagramSocket(null as java.net.SocketAddress?).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.bind(InetSocketAddress(47779))
                    s.soTimeout = timeoutMs
                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (e: Exception) { return null }
                        val json = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                        if (json.optString("messageType") == "VTubeStudioAPIStateBroadcast") {
                            val port = json.optJSONObject("data")?.optInt("port", 8001) ?: 8001
                            return (p.address.hostAddress ?: return null) to port
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Descubrimiento falló", e)
            } finally {
                lock.release()
            }
            return null
        }
    }
}
