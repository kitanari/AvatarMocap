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
import com.avatarmocap.output.VTubeStudioPlugin
import com.avatarmocap.settings.AppSettings
import com.avatarmocap.settings.SettingsStore
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.CalibStep
import com.avatarmocap.tracking.FaceFrame
import com.avatarmocap.tracking.TongueDetector
import com.avatarmocap.tracking.TrackingPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val faceFound: Boolean = false,
    val fps: Float = 0f,
    val processMs: Float = 0f,
    val gpu: Boolean = false,
    val blend: FloatArray = FloatArray(ArKit.COUNT),
    val headRot: FloatArray = FloatArray(3),
    val tongueValue: Float = 0f,
    val tongueFeature: Float = 0f,
    val tongueDebug: ImageBitmap? = null,
    val ifmStatus: String = "",
    val vtsStatus: String = "",
    val message: String = "",
    val phoneIps: List<String> = emptyList(),
    // Calibración guiada
    val wizardStep: CalibStep? = null,
    /** -1 sin captura; 0..1 preparación; 1..2 midiendo. */
    val captureProgress: Float = -1f,
    val calibratedSteps: Set<CalibStep> = emptySet(),
    // Puntos de la cara para dibujar sobre la cámara
    val landmarks: FloatArray? = null,
    val imgW: Int = 0,
    val imgH: Int = 0,
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
            p.calibration.fromJson(store.calibrationJson)
            p.calibration.onStepDone = { step, ok, msg -> onStepDone(step, ok, msg) }
            p.listener = ::onFrame
            rebuildSinks(p, _settings.value)
            _ui.update { it.copy(calibratedSteps = p.calibration.done.toSet()) }
        }
    }

    // ---------- Cámara

    fun startCamera(owner: LifecycleOwner, previewView: PreviewView?) {
        // MediaPipe se inicializa en el hilo de la cámara con el primer cuadro (no traba la UI)
        camera.start(owner, previewView, _settings.value.resolution) { bmp -> pipeline.process(bmp) }
    }

    // ---------- Estado para la UI (limitado a ~10 Hz para no robarle CPU al tracking)

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
        // Con los puntos visibles refrescamos a ~20 Hz para que sigan bien a la cara
        val interval = if (_settings.value.showLandmarks) 50_000_000L else 100_000_000L
        if (now - lastUiNanos < interval) return
        lastUiNanos = now
        val p = pipeline
        val sinks = p.sinks
        _ui.update { it.copy(
            faceFound = frame.faceFound,
            fps = fps,
            processMs = p.lastProcessMs,
            gpu = p.usingGpu,
            blend = frame.blend.copyOf(),
            headRot = frame.headRot.copyOf(),
            tongueValue = frame.blend[ArKit.TONGUE_OUT],
            tongueFeature = t?.feature ?: 0f,
            tongueDebug = if (_settings.value.showTongueDebug) lastDebug else null,
            ifmStatus = sinks.firstOrNull { it is IFacialMocapServer }?.status?.value ?: "Desactivado",
            vtsStatus = sinks.firstOrNull { it is VTubeStudioPlugin }?.status?.value ?: "Desactivado",
            captureProgress = p.calibration.progress(now),
            landmarks = p.landmarkSnapshot,
            imgW = (p.imageSize ushr 32).toInt(),
            imgH = (p.imageSize and 0xFFFFFFFFL).toInt(),
        ) }
    }

    private fun message(m: String) {
        _ui.update { it.copy(message = m) }
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
        _ui.update { it.copy(phoneIps = NetUtils.localIps()) }
    }

    fun discoverVts() {
        message("Buscando VTube Studio en la red…")
        viewModelScope.launch(Dispatchers.IO) {
            val found = VTubeStudioPlugin.discover(getApplication<Application>())
            if (found == null) {
                message("No se encontró VTS. ¿Está la API activada y la PC en la misma red Wi-Fi?")
            } else {
                message("VTube Studio encontrado en ${found.first}:${found.second}")
                update { it.copy(vtsHost = found.first, vtsPort = found.second, vtsEnabled = true) }
            }
        }
    }

    // ---------- Calibración guiada

    fun startWizard() {
        _ui.update { it.copy(wizardStep = CalibStep.entries.first(), message = "") }
    }

    fun openStep(step: CalibStep) {
        _ui.update { it.copy(wizardStep = step, message = "") }
    }

    fun closeWizard() {
        _ui.update { it.copy(wizardStep = null) }
    }

    fun captureCurrent() {
        val step = _ui.value.wizardStep ?: return
        pipeline.calibration.startCapture(step, SystemClock.elapsedRealtimeNanos())
    }

    fun skipStep() {
        val step = _ui.value.wizardStep ?: return
        _ui.update { it.copy(wizardStep = next(step)) }
    }

    private fun next(step: CalibStep): CalibStep? =
        CalibStep.entries.getOrNull(step.ordinal + 1)

    private fun onStepDone(step: CalibStep, ok: Boolean, msg: String) {
        val p = pipeline
        store.calibrationJson = p.calibration.toJson()
        val nextStep = if (ok) next(step) else step
        _ui.update { it.copy(
            message = if (ok && nextStep == null) "¡Calibración completa! ✓" else msg,
            wizardStep = nextStep,
            calibratedSteps = p.calibration.done.toSet(),
            captureProgress = -1f,
        ) }
    }

    fun resetCalibration() {
        pipeline.calibration.resetAll()
        store.calibrationJson = ""
        _ui.update { it.copy(calibratedSteps = emptySet(), message = "Calibración borrada") }
    }

    override fun onCleared() {
        camera.shutdown()
        pipeline.close()
    }
}
