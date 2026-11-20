package com.avatarmocap.settings

import android.content.Context
import com.avatarmocap.tracking.ArKit

data class AppSettings(
    // Salida iFacialMocap (VSeeFace, VNyan, Warudo, Webcam Motion Receiver, VBridger...)
    val ifmEnabled: Boolean = true,
    /** Vacío = automático: se envía a la PC que haga el "handshake" de iFacialMocap. */
    val ifmTargetIp: String = "",

    // Salida VTube Studio (API de plugins por WebSocket)
    val vtsEnabled: Boolean = false,
    val vtsHost: String = "",
    val vtsPort: Int = 8001,

    // Rendimiento
    val useGpu: Boolean = true,
    /** 0 = baja (320x240), 1 = media (480x360), 2 = alta (640x480). */
    val resolution: Int = 1,
    val showPreview: Boolean = true,
    val showBlendshapes: Boolean = false,

    // Tracking
    val swapLeftRight: Boolean = false,
    val invertYaw: Boolean = false,
    val invertPitch: Boolean = false,
    val invertRoll: Boolean = false,
    val invertEyesX: Boolean = false,
    val invertEyesY: Boolean = false,
    /** 0 = mínima latencia, 1 = máxima suavidad. */
    val smoothing: Float = 0.4f,
    val autoRange: Boolean = false,
    val showTongueDebug: Boolean = false,

    // Ajuste fino por grupo (multiplicadores), en el orden de ArKit.G_*
    val gainEyes: Float = 1f,
    val gainBrows: Float = 1f,
    val gainMouthOpen: Float = 1f,
    val gainSmile: Float = 1f,
    val gainCheeks: Float = 1f,
    val gainTongue: Float = 1f,
) {
    fun gains(): FloatArray = FloatArray(ArKit.GROUP_COUNT).also {
        it[ArKit.G_EYES] = gainEyes
        it[ArKit.G_BROWS] = gainBrows
        it[ArKit.G_MOUTH_OPEN] = gainMouthOpen
        it[ArKit.G_SMILE] = gainSmile
        it[ArKit.G_CHEEKS] = gainCheeks
        it[ArKit.G_TONGUE] = gainTongue
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("avatarmocap", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            ifmEnabled = prefs.getBoolean("ifmEnabled", d.ifmEnabled),
            ifmTargetIp = prefs.getString("ifmTargetIp", d.ifmTargetIp) ?: "",
            vtsEnabled = prefs.getBoolean("vtsEnabled", d.vtsEnabled),
            vtsHost = prefs.getString("vtsHost", d.vtsHost) ?: "",
            vtsPort = prefs.getInt("vtsPort", d.vtsPort),
            useGpu = prefs.getBoolean("useGpu", d.useGpu),
            resolution = prefs.getInt("resolution", d.resolution),
            showPreview = prefs.getBoolean("showPreview", d.showPreview),
            showBlendshapes = prefs.getBoolean("showBlendshapes", d.showBlendshapes),
            swapLeftRight = prefs.getBoolean("swapLeftRight", d.swapLeftRight),
            invertYaw = prefs.getBoolean("invertYaw", d.invertYaw),
            invertPitch = prefs.getBoolean("invertPitch", d.invertPitch),
            invertRoll = prefs.getBoolean("invertRoll", d.invertRoll),
            invertEyesX = prefs.getBoolean("invertEyesX", d.invertEyesX),
            invertEyesY = prefs.getBoolean("invertEyesY", d.invertEyesY),
            smoothing = prefs.getFloat("smoothing", d.smoothing),
            autoRange = prefs.getBoolean("autoRange2", d.autoRange),
            showTongueDebug = prefs.getBoolean("showTongueDebug2", d.showTongueDebug),
            gainEyes = prefs.getFloat("gainEyes", 1f),
            gainBrows = prefs.getFloat("gainBrows", 1f),
            gainMouthOpen = prefs.getFloat("gainMouthOpen", 1f),
            gainSmile = prefs.getFloat("gainSmile", 1f),
            gainCheeks = prefs.getFloat("gainCheeks", 1f),
            gainTongue = prefs.getFloat("gainTongue", 1f),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("ifmEnabled", s.ifmEnabled)
            .putString("ifmTargetIp", s.ifmTargetIp)
            .putBoolean("vtsEnabled", s.vtsEnabled)
            .putString("vtsHost", s.vtsHost)
            .putInt("vtsPort", s.vtsPort)
            .putBoolean("useGpu", s.useGpu)
            .putInt("resolution", s.resolution)
            .putBoolean("showPreview", s.showPreview)
            .putBoolean("showBlendshapes", s.showBlendshapes)
            .putBoolean("swapLeftRight", s.swapLeftRight)
            .putBoolean("invertYaw", s.invertYaw)
            .putBoolean("invertPitch", s.invertPitch)
            .putBoolean("invertRoll", s.invertRoll)
            .putBoolean("invertEyesX", s.invertEyesX)
            .putBoolean("invertEyesY", s.invertEyesY)
            .putFloat("smoothing", s.smoothing)
            .putBoolean("autoRange2", s.autoRange)
            .putBoolean("showTongueDebug2", s.showTongueDebug)
            .putFloat("gainEyes", s.gainEyes)
            .putFloat("gainBrows", s.gainBrows)
            .putFloat("gainMouthOpen", s.gainMouthOpen)
            .putFloat("gainSmile", s.gainSmile)
            .putFloat("gainCheeks", s.gainCheeks)
            .putFloat("gainTongue", s.gainTongue)
            .apply()
    }

    var calibrationJson: String
        get() = prefs.getString("calibration_v2", "") ?: ""
        set(v) = prefs.edit().putString("calibration_v2", v).apply()

    var vtsToken: String
        get() = prefs.getString("vtsToken", "") ?: ""
        set(v) = prefs.edit().putString("vtsToken", v).apply()
}
