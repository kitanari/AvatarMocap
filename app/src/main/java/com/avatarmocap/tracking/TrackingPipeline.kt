package com.avatarmocap.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.avatarmocap.output.OutputSink
import com.avatarmocap.settings.AppSettings
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * cámara -> MediaPipe -> medidas de la malla (ojos, boca, iris, cachetes, lengua)
 *        -> calibración -> fusión -> boca estable -> filtros -> ARKit + VRM -> salidas
 *
 * Corre en el hilo de tracking (ver CameraSource), sin objetos grandes por cuadro.
 */
class TrackingPipeline(context: Context, initial: AppSettings) : Closeable {

    private val appContext = context.applicationContext

    @Volatile var settings: AppSettings = initial
        set(value) {
            field = value; gains = value.gains(); calibration.quickMode = value.quickCalibration
            scheduler.enabled = value.interpolate
        }
    @Volatile private var gains: FloatArray = initial.gains()

    // ---- MediaPipe + elección automática GPU/CPU
    private var tracker: FaceTracker = FaceTracker.createWithFallback(appContext, initial.accel != ACCEL_CPU)
    private var benchPhase = if (initial.accel == ACCEL_AUTO && tracker.gpu) 1 else 0
    private var benchSum = 0f
    private var benchN = 0
    private var gpuMs = 0f
    private var other: FaceTracker? = null
    @Volatile var accelLabel: String = if (benchPhase == 1) "probando GPU…" else if (tracker.gpu) "GPU" else "CPU"
        private set

    val tongue = TongueDetector()
    val calibration = Calibration().also { it.quickMode = initial.quickCalibration }
    val sinks = CopyOnWriteArrayList<OutputSink>()
    /** Envío a 60 fps con interpolación. */
    val scheduler = com.avatarmocap.output.OutputScheduler(sinks).also { it.enabled = initial.interpolate }
    private val rigidCur = FloatArray(RigidHead.COUNT * 3)
    private val rigidRot = FloatArray(3)

    var listener: ((FaceFrame, TongueDetector.Result?) -> Unit)? = null

    @Volatile var lastProcessMs = 0f
        private set
    @Volatile var lastDetectMs = 0f
        private set

    /** Último cuadro para la vista previa: la imagen ANALIZADA + su malla + la lengua (siempre alineadas). */
    @Volatile var previewFrame: PreviewFrame? = null
        private set
    /** Se llama con cada cuadro de vista previa (hilo de tracking). */
    var onPreview: ((PreviewFrame) -> Unit)? = null
    @Volatile var lastTongueMs = 0f
        private set
    private var lastGood: FaceFrame? = null
    private val previewPool = arrayOfNulls<Bitmap>(4)
    private var previewIdx = 0
    private var lastPreviewNanos = 0L

    private val smoother = LandmarkSmoother()

    private val feat = FloatArray(Feat.COUNT)
    private val fold = FloatArray(2)
    /** Brillo medio de la piel de la cara (0..255), para la exposición automática. */
    @Volatile var faceLuma = 0f
        private set
    /** Componentes de la detección de cachetes (MediaPipe, malla, surco, patrón, compuerta). */
    @Volatile var puffDiag = FloatArray(5)
        private set
    /** En pausa no se procesa nada. */
    @Volatile var paused = false
    private val raw = FloatArray(ArKit.COUNT)
    /** Copia de los blendshapes crudos del último cuadro (para el editor de gestos). */
    @Volatile var lastRaw: FloatArray = FloatArray(ArKit.COUNT)
        private set
    private val cal = FloatArray(ArKit.COUNT)
    private val headRel = FloatArray(6)
    private val gz = FloatArray(2)
    private val lastGaze = FloatArray(4)
    private val vrmRaw = FloatArray(Vrm.COUNT)
    private val blendFilters = Array(ArKit.COUNT) { OneEuroFilter() }
    private val headFilters = Array(6) { OneEuroFilter() }
    private val vrmFilters = Array(Vrm.COUNT) { OneEuroFilter() }
    private val tongueDirFilters = Array(2) { OneEuroFilter(minCutoff = 2f) }
    private var lastTs = 0L
    private var lostFrames = 0

    fun process(bitmap: Bitmap) {
        if (paused) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        val s = settings
        val nowMs = SystemClock.uptimeMillis()
        val ts = if (nowMs <= lastTs) lastTs + 1 else nowMs
        lastTs = ts

        val result = tracker.detect(bitmap, ts)
        val detectMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        lastDetectMs = detectMs
        val landmarks = result?.faceLandmarks()?.firstOrNull()
        if (result == null || landmarks == null) {
            lostFrames++
            publishPreview(bitmap, null, null, t0)
            val held = lastGood
            if (lostFrames <= HOLD_FRAMES && held != null) {
                // Pérdida breve (parpadeo de detección, mano delante): se mantiene la última pose
                emit(held, null)
            } else {
                if (lostFrames == HOLD_FRAMES + 1) { resetFilters(); smoother.reset() }
                emit(FaceFrame.lost(t0), null)
            }
            lastProcessMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
            return
        }
        lostFrames = 0
        benchmark(detectMs)

        val w = bitmap.width
        val h = bitmap.height
        // Malla suavizada (sin temblor): todo lo geométrico se mide sobre ella
        val pts = smoother.update(landmarks, t0)
        val n = smoother.count

        // 1) Blendshapes de MediaPipe -> orden ARKit
        raw.fill(0f)
        result.faceBlendshapes().orElse(null)?.firstOrNull()?.forEach { c ->
            ArKit.INDEX[c.categoryName()]?.let { raw[it] = c.score() }
        }

        lastRaw = raw.copyOf()

        // 2) Medidas propias sobre la malla + lengua
        FaceFeatures.compute(pts, n, w, h, feat)
        tongue.f0 = calibration.tongueF0
        tongue.f1 = calibration.tongueF1
        tongue.setPrototypes(calibration.tongueColor, calibration.lipColor)
        val tt0 = SystemClock.elapsedRealtimeNanos()
        val t = tongue.process(bitmap, pts, n, s.showTongueDebug)
        lastTongueMs = (SystemClock.elapsedRealtimeNanos() - tt0) / 1e6f
        faceLuma = tongue.skinLuma
        CheekFold.compute(bitmap, pts, tongue.skinLuma, fold)
        feat[Feat.FOLD_L] = fold[0]
        feat[Feat.FOLD_R] = fold[1]
        feat[Feat.TONGUE] = t.feature
        feat[Feat.TONGUE_CR] = t.tongueCr
        feat[Feat.TONGUE_CB] = t.tongueCb
        feat[Feat.TONGUE_Y] = t.tongueY
        feat[Feat.LIP_CR] = t.lipCr
        feat[Feat.LIP_CB] = t.lipCb
        feat[Feat.LIP_Y] = t.lipY
        raw[ArKit.TONGUE_OUT] = t.value
        if (s.showPreview) publishPreview(bitmap, pts, t.hull, t0)

        // 3) Pose de la cabeza
        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        val head = if (matrix != null && matrix.size >= 16) HeadPose.fromMatrix(matrix) else FloatArray(6)

        // 4) Calibración (interpolada según hacia dónde mira la cabeza)
        RigidHead.extract(pts, w, h, rigidCur)
        calibration.feed(raw, head, feat, rigidCur, t0)
        calibration.updatePose(head[0], head[1])
        calibration.apply(raw, cal, s.autoRange)

        // 5) Fusión con las medidas geométricas (más lineales y precisas una vez calibradas)
        if (s.precise) fuseGeometry()

        // 6) Cachetes inflados: MediaPipe, malla (contorno), imagen (surco nariz-boca) y tu patrón
        //    calibrado. Se anulan si la boca se abre, sonríe o se va muy a un costado.
        val smile = (cal[ArKit.SMILE_L] + cal[ArKit.SMILE_R]) / 2f
        var side = 0f
        for (i in ArKit.SIDEWAYS) side = max(side, cal[i])
        val gap0 = calibration.featNeutral[Feat.GAP].takeIf { !it.isNaN() } ?: feat[Feat.GAP]
        val lipsGate = gateDown(feat[Feat.GAP] - gap0, 0.04f, 8f)
        val gate = gateDown(smile, 0.35f, 3f) * gateDown(side, 0.35f, 3f) * lipsGate
        val pMp = cal[ArKit.CHEEK_PUFF]
        val pGeo = calibration.puffSignal(feat)
        val pFold = calibration.foldSignal(feat)
        val pTmpl = calibration.puffTemplate(raw)
        val image = max(pGeo, pFold)
        val puffAll = maxOf(pMp, image, pTmpl * (0.6f + 0.4f * image))
        cal[ArKit.CHEEK_PUFF] = (puffAll * gate).coerceIn(0f, 1f)
        puffDiag = floatArrayOf(pMp, pGeo, pFold, pTmpl, gate)

        // 7) Ajuste fino por grupo + boca estable (anti-clipping al final)
        val g = gains
        for (i in 0 until ArKit.COUNT) {
            val gi = ArKit.GROUP[i]
            if (gi >= 0) cal[i] = (cal[i] * g[gi]).coerceIn(0f, 1f)
        }
        // Salida máxima de cada gesto (por defecto solo se limita la boca; editable por gesto)
        for (i in 0 until ArKit.COUNT) cal[i] *= calibration.outMax(i)
        MouthFix.apply(cal, s.mouthStable, s.mouthSymmetry, s.mouthSide, s.mouthLimit)

        calibration.relativeHead(head, headRel)
        // Giro de cabeza solo con puntos rígidos: mover la boca ya no gira la cabeza
        val ref = calibration.rigidRef
        if (ref != null) {
            RigidHead.solve(ref, rigidCur, rigidRot)
            headRel[0] = rigidRot[0]; headRel[1] = rigidRot[1]; headRel[2] = rigidRot[2]
        }
        var tx = t.x
        if (s.swapLeftRight) {
            ArKit.swapSides(cal)
            tx = -tx
            headRel[1] = -headRel[1]; headRel[2] = -headRel[2]; headRel[3] = -headRel[3]
        }

        // 8) Filtros One Euro
        val sm = s.smoothing.coerceIn(0f, 1f)
        val blendCut = lerp(12f, 1.2f, sm)
        val headCut = lerp(6f, 0.6f, sm)
        val blend = FloatArray(ArKit.COUNT)
        for (i in 0 until ArKit.COUNT) {
            val f = blendFilters[i]
            f.minCutoff = blendCut; f.beta = 1.5f
            blend[i] = f.filter(cal[i], t0).coerceIn(0f, 1f)
        }
        val hf = FloatArray(6)
        for (i in 0 until 6) {
            val f = headFilters[i]
            f.minCutoff = headCut; f.beta = if (i < 3) 0.05f else 0.3f
            hf[i] = f.filter(headRel[i], t0)
        }
        val tdx = tongueDirFilters[0].filter(tx, t0)
        val tdy = tongueDirFilters[1].filter(t.y, t0)

        // 9) Expresiones VRM (A I U E O, parpadeo, emociones)
        Vrm.compute(raw, blend, calibration, g[ArKit.G_MOUTH_OPEN], s.gainEmotions, vrmRaw)
        val vrm = FloatArray(Vrm.COUNT)
        for (i in 0 until Vrm.COUNT) {
            val f = vrmFilters[i]
            f.minCutoff = blendCut; f.beta = 1.5f
            vrm[i] = f.filter(vrmRaw[i], t0).coerceIn(0f, 1f)
        }

        val pitch = if (s.invertPitch) -hf[0] else hf[0]
        val yaw = if (s.invertYaw) -hf[1] else hf[1]
        val roll = if (s.invertRoll) -hf[2] else hf[2]
        val ex = if (s.invertEyesX) -1f else 1f
        val ey = if (s.invertEyesY) -1f else 1f
        val leftEye = floatArrayOf(
            ey * (blend[ArKit.LOOK_DOWN_L] - blend[ArKit.LOOK_UP_L]) * EYE_PITCH_DEG,
            ex * (blend[ArKit.LOOK_OUT_L] - blend[ArKit.LOOK_IN_L]) * EYE_YAW_DEG,
        )
        val rightEye = floatArrayOf(
            ey * (blend[ArKit.LOOK_DOWN_R] - blend[ArKit.LOOK_UP_R]) * EYE_PITCH_DEG,
            ex * (blend[ArKit.LOOK_IN_R] - blend[ArKit.LOOK_OUT_R]) * EYE_YAW_DEG,
        )

        val frame =
            FaceFrame(
                timestampNanos = t0, faceFound = true, blend = blend,
                headRot = floatArrayOf(pitch, yaw, roll),
                headPos = floatArrayOf(hf[3] / 100f, hf[4] / 100f, hf[5] / 100f),
                leftEye = leftEye, rightEye = rightEye, tongueX = tdx, tongueY = tdy, vrm = vrm,
            )
        lastGood = frame
        emit(frame, t)
        lastProcessMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
    }

    /** Parpadeo, boca y mirada: mitad MediaPipe, mitad geometría calibrada. */
    private fun fuseGeometry() {
        for (k in 0..1) {
            val idx = if (k == 0) ArKit.EYE_BLINK_L else ArKit.EYE_BLINK_R
            val geo = calibration.blinkGeo(k, feat[if (k == 0) Feat.EAR_L else Feat.EAR_R])
            if (!geo.isNaN()) {
                val v = 0.5f * cal[idx] + 0.5f * geo
                // Los extremos quedan firmes (ojo bien abierto / bien cerrado)
                cal[idx] = ((v - 0.12f) / 0.76f).coerceIn(0f, 1f)
            }
        }
        val jg = calibration.jawGeo(feat[Feat.GAP])
        if (!jg.isNaN()) cal[ArKit.JAW_OPEN] = 0.5f * cal[ArKit.JAW_OPEN] + 0.5f * jg

        // Boca a los costados: medida directa sobre la malla (MediaPipe casi no la detecta)
        if (calibration.mouthSide(feat, gz)) {
            cal[ArKit.MOUTH_LEFT] = max(cal[ArKit.MOUTH_LEFT] * 0.5f, gz[0])
            cal[ArKit.MOUTH_RIGHT] = max(cal[ArKit.MOUTH_RIGHT] * 0.5f, gz[1])
        }

        // Mirada desde el iris (se congela mientras el ojo está cerrado: el iris no se ve)
        for (k in 0..1) {
            val left = k == 0
            val blink = cal[if (left) ArKit.EYE_BLINK_L else ArKit.EYE_BLINK_R]
            if (calibration.gaze(feat, left, gz)) {
                if (blink < 0.5f) { lastGaze[k * 2] = gz[0]; lastGaze[k * 2 + 1] = gz[1] }
                val hh = lastGaze[k * 2]; val vv = lastGaze[k * 2 + 1]
                val out = if (left) ArKit.LOOK_OUT_L else ArKit.LOOK_OUT_R
                val inn = if (left) ArKit.LOOK_IN_L else ArKit.LOOK_IN_R
                val up = if (left) ArKit.LOOK_UP_L else ArKit.LOOK_UP_R
                val down = if (left) ArKit.LOOK_DOWN_L else ArKit.LOOK_DOWN_R
                cal[out] = 0.3f * cal[out] + 0.7f * max(0f, hh)
                cal[inn] = 0.3f * cal[inn] + 0.7f * max(0f, -hh)
                cal[down] = 0.3f * cal[down] + 0.7f * max(0f, vv)
                cal[up] = 0.3f * cal[up] + 0.7f * max(0f, -vv)
            }
        }
    }

    /** En modo automático mide GPU y CPU con la cara real y se queda con el más rápido. */
    private fun benchmark(ms: Float) {
        if (benchPhase == 0) return
        benchN++
        if (benchN <= BENCH_SKIP) return
        benchSum += ms
        if (benchN < BENCH_SKIP + BENCH_FRAMES) return
        val avg = benchSum / BENCH_FRAMES
        benchN = 0; benchSum = 0f
        if (benchPhase == 1) {
            gpuMs = avg
            try {
                val cpu = FaceTracker(appContext, false)
                other = tracker
                tracker = cpu
                benchPhase = 2
                accelLabel = "probando CPU…"
            } catch (e: Throwable) {
                benchPhase = 0
                accelLabel = "GPU"
            }
        } else {
            val cpuMs = avg
            val keepCpu = cpuMs < gpuMs * 0.9f
            val gpuTracker = other
            if (keepCpu || gpuTracker == null) {
                gpuTracker?.close()
            } else {
                tracker.close()
                tracker = gpuTracker
            }
            other = null
            benchPhase = 0
            accelLabel = if (tracker.gpu) "GPU (auto)" else "CPU (auto)"
            Log.i(TAG, "Benchmark: GPU %.1f ms, CPU %.1f ms -> %s".format(gpuMs, cpuMs, accelLabel))
        }
    }

    /** Copia el cuadro analizado (máx. ~20 por segundo) junto con SU malla, para que siempre coincidan. */
    private fun publishPreview(src: Bitmap, pts: FloatArray?, hull: FloatArray?, nanos: Long) {
        if (!settings.showPreview) return
        lastPreviewNanos = nanos
        previewIdx = (previewIdx + 1) % previewPool.size
        var dst = previewPool[previewIdx]
        if (dst == null || dst.width != src.width || dst.height != src.height) {
            dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            previewPool[previewIdx] = dst
        }
        android.graphics.Canvas(dst!!).drawBitmap(src, 0f, 0f, null)
        val pf = PreviewFrame(dst, pts?.copyOf(), hull, ++previewSeq)
        previewFrame = pf
        onPreview?.invoke(pf)
    }

    private var previewSeq = 0L

    private fun emit(frame: FaceFrame, t: TongueDetector.Result?) {
        scheduler.push(frame)
        listener?.invoke(frame, t)
    }

    /** "Reiniciar": olvida el estado del tracking (filtros, malla, lengua) sin tocar la calibración. */
    fun resetTracking() {
        resetFilters()
        smoother.reset()
        lastGood = null
    }

    private fun resetFilters() {
        blendFilters.forEach { it.reset() }
        headFilters.forEach { it.reset() }
        vrmFilters.forEach { it.reset() }
        tongueDirFilters.forEach { it.reset() }
        tongue.reset()
        smoother.reset()
    }

    override fun close() {
        scheduler.close()
        sinks.forEach { it.close() }
        sinks.clear()
        tracker.close()
        other?.close()
    }

    companion object {
        private const val TAG = "Pipeline"
        const val ACCEL_AUTO = 0
        const val ACCEL_GPU = 1
        const val ACCEL_CPU = 2
        const val EYE_YAW_DEG = 30f
        const val EYE_PITCH_DEG = 25f
        private const val BENCH_SKIP = 10
        private const val HOLD_FRAMES = 12
        private const val BENCH_FRAMES = 40
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun gateDown(v: Float, start: Float, slope: Float) = (1f - max(0f, v - start) * slope).coerceIn(0f, 1f)
    }
}

/** Imagen analizada + malla (x,y,z normalizados) + contorno de la lengua, del MISMO cuadro. */
class PreviewFrame(val bitmap: Bitmap, val landmarks: FloatArray?, val tongueHull: FloatArray?, val seq: Long)
