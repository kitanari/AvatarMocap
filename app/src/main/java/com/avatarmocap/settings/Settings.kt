package com.avatarmocap.settings

import android.content.Context

data class AppSettings(
    // Salida iFacialMocap (VSeeFace, VNyan, Warudo, Webcam Motion Receiver, VBridger...)
    val ifmEnabled: Boolean = true,
    /** Vacío = automático: se envía a la PC que haga el "handshake" de iFacialMocap. */
    val ifmTargetIp: String = "",

    // Salida VTube Studio (API de plugins por WebSocket)
    val vtsEnabled: Boolean = false,
    val vtsHost: String = "",
    val vtsPort: Int = 8001,

    // Tracking
    val useGpu: Boolean = true,
    val swapLeftRight: Boolean = false,
    val invertYaw: Boolean = false,
    val invertPitch: Boolean = false,
    val invertRoll: Boolean = false,
    val invertEyesX: Boolean = false,
    val invertEyesY: Boolean = false,
    /** 0 = mínima latencia, 1 = máxima suavidad. */
    val smoothing: Float = 0.5f,
    val autoRange: Boolean = true,
    val showTongueDebug: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("avatarmocap", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        ifmEnabled = prefs.getBoolean("ifmEnabled", true),
        ifmTargetIp = prefs.getString("ifmTargetIp", "") ?: "",
        vtsEnabled = prefs.getBoolean("vtsEnabled", false),
        vtsHost = prefs.getString("vtsHost", "") ?: "",
        vtsPort = prefs.getInt("vtsPort", 8001),
        useGpu = prefs.getBoolean("useGpu", true),
        swapLeftRight = prefs.getBoolean("swapLeftRight", false),
        invertYaw = prefs.getBoolean("invertYaw", false),
        invertPitch = prefs.getBoolean("invertPitch", false),
        invertRoll = prefs.getBoolean("invertRoll", false),
        invertEyesX = prefs.getBoolean("invertEyesX", false),
        invertEyesY = prefs.getBoolean("invertEyesY", false),
        smoothing = prefs.getFloat("smoothing", 0.5f),
        autoRange = prefs.getBoolean("autoRange", true),
        showTongueDebug = prefs.getBoolean("showTongueDebug", true),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("ifmEnabled", s.ifmEnabled)
            .putString("ifmTargetIp", s.ifmTargetIp)
            .putBoolean("vtsEnabled", s.vtsEnabled)
            .putString("vtsHost", s.vtsHost)
            .putInt("vtsPort", s.vtsPort)
            .putBoolean("useGpu", s.useGpu)
            .putBoolean("swapLeftRight", s.swapLeftRight)
            .putBoolean("invertYaw", s.invertYaw)
            .putBoolean("invertPitch", s.invertPitch)
            .putBoolean("invertRoll", s.invertRoll)
            .putBoolean("invertEyesX", s.invertEyesX)
            .putBoolean("invertEyesY", s.invertEyesY)
            .putFloat("smoothing", s.smoothing)
            .putBoolean("autoRange", s.autoRange)
            .putBoolean("showTongueDebug", s.showTongueDebug)
            .apply()
    }

    // Datos de calibración y token de VTS (se guardan aparte de los ajustes)
    var neutralCalibration: String
        get() = prefs.getString("neutralCalibration", "") ?: ""
        set(v) = prefs.edit().putString("neutralCalibration", v).apply()

    var tongueF0: Float
        get() = prefs.getFloat("tongueF0", 0.08f)
        set(v) = prefs.edit().putFloat("tongueF0", v).apply()

    var tongueF1: Float
        get() = prefs.getFloat("tongueF1", 0.45f)
        set(v) = prefs.edit().putFloat("tongueF1", v).apply()

    var vtsToken: String
        get() = prefs.getString("vtsToken", "") ?: ""
        set(v) = prefs.edit().putString("vtsToken", v).apply()
}
