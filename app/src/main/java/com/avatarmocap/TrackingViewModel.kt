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
import com.avatarmocap.i18n.I18n
import com.avatarmocap.i18n.tr
import com.avatarmocap.output.IFacialMocapServer
import com.avatarmocap.output.NetUtils
import com.avatarmocap.output.VTubeStudioPlugin
import com.avatarmocap.output.VmcSender
import com.avatarmocap.settings.AppSettings
import com.avatarmocap.settings.SettingsStore
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.CalibStep
import com.avatarmocap.tracking.CaptureStatus
import com.avatarmocap.tracking.PreviewFrame
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
    val cameraFps: Float = 0f,
    val detectMs: Float = 0f,
    val accel: String = "",
    val vrm: FloatArray = FloatArray(com.avatarmocap.tracking.Vrm.COUNT),
    val vmcStatus: String = "",
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
    val capture: CaptureStatus? = null,
    val calibratedSteps: Set<CalibStep> = emptySet(),
    val convertMs: Float = 0f,
    val sendFps: Float = 0f,
    val paused: Boolean = false,
    /** Alguna salida tiene una PC recibiendo. */
    val connected: Boolean = false,
    val exposure: String = "",
    val puffDiag: FloatArray = FloatArray(5),
    /** Blendshapes crudos de MediaPipe (para el editor de gestos). */
    val raw: FloatArray = FloatArray(ArKit.COUNT),
    val profiles: List<String> = emptyList(),
    /** Se incrementa cuando cambian los ajustes por gesto (para redibujar el editor). */
    val overridesVersion: Int = 0,
    val tongueMs: Float = 0f,
)

class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<AppSettings> = _settings

    init { I18n.setMode(_settings.value.language) }

    private val _ui = MutableStateFlow(UiState(phoneIps = NetUtils.localIps(), profiles = profileNames()))
    val ui: StateFlow<UiState> = _ui

    private val camera = CameraSource(app)

    /** Vista previa a fps completos, separada del resto del estado (solo se redibuja la imagen). */
    private val _preview = MutableStateFlow<PreviewFrame?>(null)
    val preview: StateFlow<PreviewFrame?> = _preview

    private val pipeline: TrackingPipeline by lazy {
        TrackingPipeline(app, _settings.value).also { p ->
            p.calibration.fromJson(store.calibrationJson)
            p.calibration.overridesFromJson(store.overridesJson)
            p.calibration.onStepDone = { step, ok, msg -> onStepDone(step, ok, msg) }
            p.listener = ::onFrame
            p.onPreview = { _preview.value = it }
            rebuildSinks(p, _settings.value)
            _ui.update { it.copy(calibratedSteps = p.calibration.done.toSet()) }
        }
    }

    // ---------- Cámara

    private fun p0Capturing() = pipeline.calibration.capturing != null

    fun startCamera(owner: LifecycleOwner, previewView: PreviewView? = null) {
        // MediaPipe se inicializa en el hilo de la cámara con el primer cuadro (no traba la UI)
        camera.start(owner, previewView, _settings.value.resolution, _settings.value.rearCamera) { bmp -> pipeline.process(bmp) }
    }

    // ---------- Estado para la UI (limitado a ~10 Hz para no robarle CPU al tracking)

    private var lastUiNanos = 0L
    private var frames = 0
    private var fpsWindowStart = 0L
    private var fps = 0f
    private var lastDebug: ImageBitmap? = null

    private fun onFrame(frame: FaceFrame, t: TongueDetector.Result?) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (frame.faceFound && pipeline.faceLuma > 0f) camera.reportFaceLuma(pipeline.faceLuma)
        frames++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1_000_000_000L) {
            fps = frames * 1e9f / (now - fpsWindowStart)
            frames = 0; fpsWindowStart = now
        }
        t?.debug?.let { lastDebug = it.asImageBitmap() }
        // Con los puntos visibles refrescamos a ~20 Hz para que sigan bien a la cara
        val interval = if (p0Capturing()) 50_000_000L else 100_000_000L
        if (now - lastUiNanos < interval) return
        lastUiNanos = now
        val p = pipeline
        val sinks = p.sinks
        _ui.update { it.copy(
            faceFound = frame.faceFound,
            fps = fps,
            processMs = p.lastProcessMs,
            cameraFps = camera.cameraFps,
            detectMs = p.lastDetectMs,
            accel = p.accelLabel,
            vrm = frame.vrm.copyOf(),
            vmcStatus = sinks.firstOrNull { it is VmcSender }?.status?.value ?: tr("Desactivado", "Off"),
            blend = frame.blend.copyOf(),
            headRot = frame.headRot.copyOf(),
            tongueValue = frame.blend[ArKit.TONGUE_OUT],
            tongueFeature = t?.feature ?: 0f,
            tongueDebug = if (_settings.value.showTongueDebug) lastDebug else null,
            ifmStatus = sinks.firstOrNull { it is IFacialMocapServer }?.status?.value ?: tr("Desactivado", "Off"),
            vtsStatus = sinks.firstOrNull { it is VTubeStudioPlugin }?.status?.value ?: tr("Desactivado", "Off"),
            capture = p.calibration.captureStatus(now),
            convertMs = camera.convertMs,
            sendFps = p.scheduler.sendFps,
            connected = sinks.any { it.connected },
            exposure = camera.exposureInfo,
            puffDiag = p.puffDiag,
            raw = p.lastRaw,
            tongueMs = p.lastTongueMs,
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
        if (old.language != new.language) I18n.setMode(new.language)
        _settings.value = new
        store.save(new)
        pipeline.settings = new
        val netChanged = old.ifmEnabled != new.ifmEnabled || old.ifmTargetIp != new.ifmTargetIp ||
            old.vtsEnabled != new.vtsEnabled || old.vtsHost != new.vtsHost || old.vtsPort != new.vtsPort ||
            old.vmcEnabled != new.vmcEnabled || old.vmcHost != new.vmcHost || old.vmcPort != new.vmcPort ||
            old.vmcVrm1 != new.vmcVrm1 || old.vmcArkit != new.vmcArkit || old.vmcBones != new.vmcBones
        if (netChanged) viewModelScope.launch(Dispatchers.IO) { rebuildSinks(pipeline, new) }
        if (old.accel != new.accel) message(tr("El cambio de GPU/CPU se aplica al reiniciar la app", "The GPU/CPU change applies after restarting the app"))
    }

    @Synchronized
    private fun rebuildSinks(p: TrackingPipeline, s: AppSettings) {
        val old = p.sinks.toList()
        p.sinks.clear()
        old.forEach { it.close() }
        val ifm = if (s.ifmEnabled) IFacialMocapServer(s.ifmTargetIp) else null
        if (ifm != null) p.sinks.add(ifm)
        if (s.vmcEnabled) {
            p.sinks.add(VmcSender(s.vmcHost, s.vmcPort, s.vmcVrm1, s.vmcArkit, s.vmcBones) { ifm?.currentTarget })
        }
        if (s.vtsEnabled && s.vtsHost.isNotBlank()) {
            p.sinks.add(VTubeStudioPlugin(s.vtsHost.trim(), s.vtsPort, store.vtsToken) { store.vtsToken = it })
        }
        _ui.update { it.copy(phoneIps = NetUtils.localIps()) }
    }

    fun discoverVts() {
        message(tr("Buscando el programa Live2D en la red…", "Searching for the Live2D app on the network…"))
        viewModelScope.launch(Dispatchers.IO) {
            val found = VTubeStudioPlugin.discover(getApplication<Application>())
            if (found == null) {
                message(tr("No se encontró. ¿Está la API de plugins activada y la PC en la misma red Wi-Fi?", "Not found. Is the plugin API enabled and the PC on the same Wi-Fi?"))
            } else {
                message(tr("Programa Live2D encontrado en ${found.first}:${found.second}", "Live2D app found at ${found.first}:${found.second}"))
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
            message = if (ok && nextStep == null) tr("¡Calibración completa! ✓", "Calibration complete! ✓") else msg,
            wizardStep = nextStep,
            calibratedSteps = p.calibration.done.toSet(),
            capture = null,
        ) }
    }

    fun resetCalibration() {
        pipeline.calibration.resetAll()
        store.calibrationJson = ""
        _ui.update { it.copy(calibratedSteps = emptySet(), message = tr("Calibración borrada", "Calibration cleared")) }
    }

    // ---------- Barra inferior

    fun togglePause() {
        val p = !pipeline.paused
        pipeline.paused = p
        _ui.update { it.copy(paused = p, message = if (p) tr("Tracking en pausa", "Tracking paused") else "") }
    }

    fun restartTracking() {
        pipeline.resetTracking()
        pipeline.paused = false
        _ui.update { it.copy(paused = false, message = tr("Tracking reiniciado", "Tracking restarted")) }
    }

    fun switchCamera() = update { it.copy(rearCamera = !it.rearCamera) }

        // ---------- Editor de gestos (ajustes manuales por blendshape)

    fun effMin(i: Int) = pipeline.calibration.effMin(i)
    fun effMax(i: Int) = pipeline.calibration.effMax(i)
    fun outMax(i: Int) = pipeline.calibration.outMax(i)
    fun isOverridden(i: Int): Boolean {
        val c = pipeline.calibration
        return !c.ovMin[i].isNaN() || !c.ovMax[i].isNaN() || !c.ovOut[i].isNaN()
    }

    fun setRange(i: Int, min: Float, max: Float) {
        val c = pipeline.calibration
        c.ovMin[i] = min.coerceIn(0f, 0.95f)
        c.ovMax[i] = max.coerceIn(c.ovMin[i] + 0.03f, 1f)
        saveOverrides()
    }

    fun setOut(i: Int, v: Float) {
        pipeline.calibration.ovOut[i] = v.coerceIn(0f, 1f)
        saveOverrides()
    }

    /** Toma el valor actual del gesto como mínimo (reposo) o como máximo. */
    fun captureLimit(i: Int, asMax: Boolean) {
        val c = pipeline.calibration
        val v = pipeline.lastRaw[i]
        if (asMax) c.ovMax[i] = v.coerceIn(c.effMin(i) + 0.03f, 1f)
        else c.ovMin[i] = v.coerceIn(0f, 0.95f)
        saveOverrides()
    }

    fun resetGesture(i: Int) {
        val c = pipeline.calibration
        c.ovMin[i] = Float.NaN; c.ovMax[i] = Float.NaN; c.ovOut[i] = Float.NaN
        saveOverrides()
    }

    private fun saveOverrides() {
        store.overridesJson = pipeline.calibration.overridesJson()
        _ui.update { it.copy(overridesVersion = it.overridesVersion + 1) }
    }

    // ---------- Perfiles (calibración + ajustes por gesto, con nombre)

    private fun profileNames(): List<String> = store.profiles().keys().asSequence().toList().sorted()

    fun saveProfile(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val all = store.profiles()
        all.put(n, org.json.JSONObject()
            .put("calib", pipeline.calibration.toJson())
            .put("ov", pipeline.calibration.overridesJson()))
        store.saveProfiles(all)
        _ui.update { it.copy(profiles = profileNames(), message = tr("Perfil \"$n\" guardado ✓", "Profile \"$n\" saved ✓")) }
    }

    fun loadProfile(name: String) {
        val o = store.profiles().optJSONObject(name) ?: return
        val c = pipeline.calibration
        c.fromJson(o.optString("calib"))
        c.overridesFromJson(o.optString("ov"))
        store.calibrationJson = c.toJson()
        store.overridesJson = c.overridesJson()
        _ui.update {
            it.copy(
                calibratedSteps = c.done, overridesVersion = it.overridesVersion + 1,
                message = tr("Perfil \"$name\" cargado ✓", "Profile \"$name\" loaded ✓"),
            )
        }
    }

    fun deleteProfile(name: String) {
        val all = store.profiles()
        all.remove(name)
        store.saveProfiles(all)
        _ui.update { it.copy(profiles = profileNames()) }
    }

    override fun onCleared() {
        camera.shutdown()
        pipeline.close()
    }
}
