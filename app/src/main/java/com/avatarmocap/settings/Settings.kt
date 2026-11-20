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

    // Salida VMC (expresiones VRM para VSeeFace, VNyan, Warudo...)
    val vmcEnabled: Boolean = false,
    /** Vacío = la misma PC que se conectó por iFacialMocap. */
    val vmcHost: String = "",
    val vmcPort: Int = 39539,
    /** false = nombres VRM 0.x (A, Blink, Joy...), true = VRM 1.0 (aa, blink, happy...). */
    val vmcVrm1: Boolean = false,
    /** Enviar también los 52 ARKit (modelos "perfect sync"). */
    val vmcArkit: Boolean = false,
    /** Enviar rotación de cabeza y ojos como huesos. */
    val vmcBones: Boolean = false,

    // Rendimiento
    /** 0 = automático (prueba GPU y CPU), 1 = GPU, 2 = CPU. */
    val accel: Int = 0,
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
    /** 0 = nada, 1 = puntos, 2 = malla completa. */
    val meshMode: Int = 2,
    /** Fusiona parpadeo/boca/mirada medidos en la malla con MediaPipe. */
    val precise: Boolean = true,
    val gainEmotions: Float = 1f,

    // Boca
    /** Zona muerta en los blendshapes de boca ruidosos. */
    val mouthStable: Boolean = true,
    /** 0 = cada lado independiente, 1 = boca totalmente simétrica. */
    val mouthSymmetry: Float = 0.6f,
    /** Multiplicador de mover la boca/mandíbula a los costados. */
    val mouthSide: Float = 0.5f,

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
            vmcEnabled = prefs.getBoolean("vmcEnabled", d.vmcEnabled),
            vmcHost = prefs.getString("vmcHost", d.vmcHost) ?: "",
            vmcPort = prefs.getInt("vmcPort", d.vmcPort),
            vmcVrm1 = prefs.getBoolean("vmcVrm1", d.vmcVrm1),
            vmcArkit = prefs.getBoolean("vmcArkit", d.vmcArkit),
            vmcBones = prefs.getBoolean("vmcBones", d.vmcBones),
            accel = prefs.getInt("accel", d.accel),
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
            meshMode = prefs.getInt("meshMode", d.meshMode),
            precise = prefs.getBoolean("precise", d.precise),
            gainEmotions = prefs.getFloat("gainEmotions", d.gainEmotions),
            mouthStable = prefs.getBoolean("mouthStable", d.mouthStable),
            mouthSymmetry = prefs.getFloat("mouthSymmetry", d.mouthSymmetry),
            mouthSide = prefs.getFloat("mouthSide", d.mouthSide),
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
            .putBoolean("vmcEnabled", s.vmcEnabled)
            .putString("vmcHost", s.vmcHost)
            .putInt("vmcPort", s.vmcPort)
            .putBoolean("vmcVrm1", s.vmcVrm1)
            .putBoolean("vmcArkit", s.vmcArkit)
            .putBoolean("vmcBones", s.vmcBones)
            .putInt("accel", s.accel)
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
            .putInt("meshMode", s.meshMode)
            .putBoolean("precise", s.precise)
            .putFloat("gainEmotions", s.gainEmotions)
            .putBoolean("mouthStable", s.mouthStable)
            .putFloat("mouthSymmetry", s.mouthSymmetry)
            .putFloat("mouthSide", s.mouthSide)
            .putFloat("gainEyes", s.gainEyes)
            .putFloat("gainBrows", s.gainBrows)
            .putFloat("gainMouthOpen", s.gainMouthOpen)
            .putFloat("gainSmile", s.gainSmile)
            .putFloat("gainCheeks", s.gainCheeks)
            .putFloat("gainTongue", s.gainTongue)
            .apply()
    }

    var calibrationJson: String
        get() = prefs.getString("calibration_v3", "") ?: ""
        set(v) = prefs.edit().putString("calibration_v3", v).apply()

    var vtsToken: String
        get() = prefs.getString("vtsToken", "") ?: ""
        set(v) = prefs.edit().putString("vtsToken", v).apply()
}
