package com.avatarmocap

import android.app.Application
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.avatarmocap.camera.CameraSource
import com.avatarmocap.output.IFacialMocapServer
import com.avatarmocap.output.NetUtils
import com.avatarmocap.output.OutputSink
import com.avatarmocap.output.VTubeStudioPlugin
import com.avatarmocap.settings.AppSettings
import com.avatarmocap.settings.SettingsStore
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.FaceFrame
import com.avatarmocap.tracking.TongueDetector
import com.avatarmocap.tracking.TrackingPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val faceFound: Boolean = false,
    val fps: Float = 0f,
    val gpu: Boolean = false,
    val blend: FloatArray = FloatArray(ArKit.COUNT),
    val headRot: FloatArray = FloatArray(3),
    val tongueValue: Float = 0f,
    val tongueFeature: Float = 0f,
    val tongueF0: Float = 0f,
    val tongueF1: Float = 0f,
    val tongueDebug: ImageBitmap? = null,
    val ifmStatus: String = "",
    val vtsStatus: String = "",
    val message: String = "",
    val neutralCalibrated: Boolean = false,
    val phoneIps: List<String> = emptyList(),
)

class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<AppSettings> = _settings

    private val _ui = MutableStateFlow(UiState(phoneIps = NetUtils.localIps()))
    val ui: StateFlow<UiState> = _ui

    private val camera = CameraSource(app)

    private val pipeline: TrackingPipeline by lazy {
        TrackingPipeline(app, _settings.value).also { p ->
            p.calibration.deserialize(store.neutralCalibration)
            p.tongue.f0 = store.tongueF0
            p.tongue.f1 = store.tongueF1
            p.calibration.onNeutralDone = {
                store.neutralCalibration = p.calibration.serialize()
                message("Cara neutral calibrada ✓")
            }
            p.tongue.onCalibrated = { target, v ->
                if (target == 1) store.tongueF0 = v else store.tongueF1 = v
                val ok = p.tongue.f1 - p.tongue.f0 > 0.05f
                message(
                    if (target == 1) "Boca abierta sin lengua guardada (f0=%.2f)".format(v)
                    else if (ok) "Lengua afuera guardada (f1=%.2f) ✓".format(v)
                    else "Poca diferencia entre las dos poses (f0=%.2f, f1=%.2f). Probá con más luz.".format(p.tongue.f0, v)
                )
            }
            p.listener = ::onFrame
            rebuildSinks(p, _settings.value)
        }
    }

    // ---------- Cámara

    fun startCamera(owner: LifecycleOwner, previewView: PreviewView) {
        camera.start(owner, previewView) { bmp -> pipeline.process(bmp) }
    }

    // ---------- Estado para la UI (limitado a ~15 Hz)

    private var lastUiNanos = 0L
    private var frames = 0
    private var fpsWindowStart = 0L
    private var fps = 0f
    private var lastDebug: ImageBitmap? = null

    private fun onFrame(frame: FaceFrame, t: TongueDetector.Result?) {
        val now = SystemClock.elapsedRealtimeNanos()
        frames++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1_000_000_000L) {
            fps = frames * 1e9f / (now - fpsWindowStart)
            frames = 0; fpsWindowStart = now
        }
        t?.debug?.let { lastDebug = it.asImageBitmap() }
        if (now - lastUiNanos < 66_000_000L) return
        lastUiNanos = now
        val p = pipeline
        val sinks = p.sinks
        _ui.value = _ui.value.copy(
            faceFound = frame.faceFound,
            fps = fps,
            gpu = p.usingGpu,
            blend = frame.blend.copyOf(),
            headRot = frame.headRot.copyOf(),
            tongueValue = t?.value ?: 0f,
            tongueFeature = t?.feature ?: 0f,
            tongueF0 = p.tongue.f0,
            tongueF1 = p.tongue.f1,
            tongueDebug = lastDebug,
            ifmStatus = sinks.firstOrNull { it is IFacialMocapServer }?.status?.value ?: "Desactivado",
            vtsStatus = sinks.firstOrNull { it is VTubeStudioPlugin }?.status?.value ?: "Desactivado",
            neutralCalibrated = p.calibration.hasNeutral,
        )
    }

    private fun message(m: String) {
        _ui.value = _ui.value.copy(message = m)
    }

    // ---------- Ajustes

    fun update(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        store.save(new)
        pipeline.settings = new
        val netChanged = old.ifmEnabled != new.ifmEnabled || old.ifmTargetIp != new.ifmTargetIp ||
            old.vtsEnabled != new.vtsEnabled || old.vtsHost != new.vtsHost || old.vtsPort != new.vtsPort
        if (netChanged) viewModelScope.launch(Dispatchers.IO) { rebuildSinks(pipeline, new) }
        if (old.useGpu != new.useGpu) message("El cambio de GPU/CPU se aplica al reiniciar la app")
    }

    @Synchronized
    private fun rebuildSinks(p: TrackingPipeline, s: AppSettings) {
        val old = p.sinks.toList()
        p.sinks.clear()
        old.forEach { it.close() }
        if (s.ifmEnabled) p.sinks.add(IFacialMocapServer(s.ifmTargetIp))
        if (s.vtsEnabled && s.vtsHost.isNotBlank()) {
            p.sinks.add(VTubeStudioPlugin(s.vtsHost.trim(), s.vtsPort, store.vtsToken) { store.vtsToken = it })
        }
        _ui.value = _ui.value.copy(phoneIps = NetUtils.localIps())
    }

    fun discoverVts() {
        message("Buscando VTube Studio en la red…")
        viewModelScope.launch(Dispatchers.IO) {
            val found = VTubeStudioPlugin.discover(getApplication())
            if (found == null) {
                message("No se encontró VTS. ¿Está la API activada y la PC en la misma red Wi-Fi?")
            } else {
                message("VTube Studio encontrado en ${found.first}:${found.second}")
                update { it.copy(vtsHost = found.first, vtsPort = found.second, vtsEnabled = true) }
            }
        }
    }

    // ---------- Calibración

    fun calibrateNeutral() {
        message("Mirá a la cámara con la cara relajada… (1 s)")
        pipeline.calibration.startNeutralCapture()
    }

    fun resetAutoRange() {
        pipeline.calibration.resetAutoRange()
        message("Auto-rango reiniciado: hacé tus expresiones al máximo un par de veces")
    }

    fun calibrateTongue(target: Int) {
        message(
            if (target == 1) "Abrí la boca SIN sacar la lengua… (1.5 s)"
            else "Sacá la lengua todo lo que puedas… (1.5 s)"
        )
        pipeline.tongue.startCalibration(target)
    }

    override fun onCleared() {
        camera.shutdown()
        pipeline.close()
    }
}
