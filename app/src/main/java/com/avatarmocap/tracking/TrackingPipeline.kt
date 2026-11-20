package com.avatarmocap.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.avatarmocap.output.OutputSink
import com.avatarmocap.settings.AppSettings
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * cámara (Bitmap) -> MediaPipe -> lengua + cachetes -> calibración -> filtros -> FaceFrame -> salidas
 *
 * Se ejecuta completo en el hilo de análisis de la cámara, sin crear objetos grandes por cuadro.
 */
class TrackingPipeline(context: Context, initial: AppSettings) : Closeable {

    @Volatile var settings: AppSettings = initial
        set(value) { field = value; gains = value.gains() }
    @Volatile private var gains: FloatArray = initial.gains()

    private val tracker = FaceTracker(context.applicationContext, initial.useGpu)
    val usingGpu get() = tracker.usingGpu

    val tongue = TongueDetector()
    val calibration = Calibration()
    val sinks = CopyOnWriteArrayList<OutputSink>()

    /** Se llama en cada cuadro (en el hilo de la cámara). */
    var listener: ((FaceFrame, TongueDetector.Result?) -> Unit)? = null

    /** Tiempo de procesamiento del último cuadro (ms), para mostrar en la UI. */
    @Volatile var lastProcessMs = 0f
        private set

    /** Puntos de la cara para dibujar (x,y normalizados), o null si está apagado. */
    @Volatile var landmarkSnapshot: FloatArray? = null
        private set
    @Volatile var imageSize: Long = 0L // (w shl 32) or h
        private set

    private val puffSides = FloatArray(2)
    private val raw = FloatArray(ArKit.COUNT)
    private val cal = FloatArray(ArKit.COUNT)
    private val headRel = FloatArray(6)
    private val blendFilters = Array(ArKit.COUNT) { OneEuroFilter() }
    private val headFilters = Array(6) { OneEuroFilter() }
    private val tongueDirFilters = Array(2) { OneEuroFilter(minCutoff = 2f) }
    private var lastTs = 0L
    private var lostFrames = 0

    fun process(bitmap: Bitmap) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val s = settings
        val nowMs = SystemClock.uptimeMillis()
        val ts = if (nowMs <= lastTs) lastTs + 1 else nowMs
        lastTs = ts

        val result = tracker.detect(bitmap, ts)
        val landmarks = result?.faceLandmarks()?.firstOrNull()
        if (result == null || landmarks == null) {
            if (++lostFrames == 10) resetFilters()
            landmarkSnapshot = null
            emit(FaceFrame.lost(t0), null)
            lastProcessMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
            return
        }
        lostFrames = 0

        // 1) Blendshapes de MediaPipe -> orden ARKit
        raw.fill(0f)
        result.faceBlendshapes().orElse(null)?.firstOrNull()?.forEach { c ->
            ArKit.INDEX[c.categoryName()]?.let { raw[it] = c.score() }
        }

        // 2) Lengua y cachetes (detectores propios)
        tongue.f0 = calibration.tongueF0
        tongue.f1 = calibration.tongueF1
        val t = tongue.process(bitmap, landmarks, s.showTongueDebug)
        raw[ArKit.TONGUE_OUT] = t.value
        FaceGeometry.puffSides(landmarks, bitmap.width, bitmap.height, puffSides)
        if (s.showLandmarks) {
            val pts = FloatArray((FaceGeometry.OVERLAY_POINTS.size + FaceGeometry.OVERLAY_LIPS.size) * 2)
            var k = 0
            for (idx in FaceGeometry.OVERLAY_POINTS) {
                if (idx < landmarks.size) { pts[k] = landmarks[idx].x(); pts[k + 1] = landmarks[idx].y() }
                k += 2
            }
            for (idx in FaceGeometry.OVERLAY_LIPS) {
                pts[k] = landmarks[idx].x(); pts[k + 1] = landmarks[idx].y(); k += 2
            }
            landmarkSnapshot = pts
            imageSize = (bitmap.width.toLong() shl 32) or bitmap.height.toLong()
        } else {
            landmarkSnapshot = null
        }

        // 3) Pose de la cabeza
        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        val head = if (matrix != null && matrix.size >= 16) HeadPose.fromMatrix(matrix) else FloatArray(6)

        // 4) Calibración
        calibration.feed(raw, head, puffSides, t.feature, t0)
        val g = gains
        calibration.apply(raw, cal, s.autoRange, g)
        // Cachetes: lo mejor entre MediaPipe y la geometría. Se anula si la boca está abierta,
        // sonriendo, en "beso" o movida hacia un costado (eso también ensancha un lado de la cara).
        val jaw = cal[ArKit.JAW_OPEN]
        val smile = (cal[ArKit.SMILE_L] + cal[ArKit.SMILE_R]) / 2f
        var side = 0f
        for (i in ArKit.SIDEWAYS) side = max(side, cal[i])
        val gate = gateDown(jaw, 0.2f, 4f) * gateDown(smile, 0.3f, 3f) *
            gateDown(side, 0.12f, 4f) * gateDown(cal[ArKit.MOUTH_PUCKER], 0.5f, 2f)
        val puffSig = calibration.puffSignal(puffSides) * gate * g[ArKit.G_CHEEKS]
        // Y el valor de MediaPipe también pasa por la misma compuerta
        cal[ArKit.CHEEK_PUFF] *= gate
        cal[ArKit.CHEEK_PUFF] = max(cal[ArKit.CHEEK_PUFF], puffSig).coerceIn(0f, 1f)
        cal[ArKit.TONGUE_OUT] = (cal[ArKit.TONGUE_OUT]).coerceIn(0f, 1f)

        // Boca estable: sin ruido, simétrica y sin clipping
        MouthFix.apply(cal, s.mouthStable, s.mouthSymmetry, s.mouthSide)

        calibration.relativeHead(head, headRel)
        var tx = t.x
        if (s.swapLeftRight) {
            ArKit.swapSides(cal)
            tx = -tx
            headRel[1] = -headRel[1]; headRel[2] = -headRel[2]; headRel[3] = -headRel[3]
        }

        // 5) Filtros One Euro
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

        val pitch = if (s.invertPitch) -hf[0] else hf[0]
        val yaw = if (s.invertYaw) -hf[1] else hf[1]
        val roll = if (s.invertRoll) -hf[2] else hf[2]

        // 6) Ojos (grados) a partir de los blendshapes de mirada
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

        val frame = FaceFrame(
            timestampNanos = t0,
            faceFound = true,
            blend = blend,
            headRot = floatArrayOf(pitch, yaw, roll),
            headPos = floatArrayOf(hf[3] / 100f, hf[4] / 100f, hf[5] / 100f),
            leftEye = leftEye,
            rightEye = rightEye,
            tongueX = tdx,
            tongueY = tdy,
        )
        emit(frame, t)
        lastProcessMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
    }

    private fun emit(frame: FaceFrame, t: TongueDetector.Result?) {
        for (sink in sinks) sink.send(frame)
        listener?.invoke(frame, t)
    }

    private fun resetFilters() {
        blendFilters.forEach { it.reset() }
        headFilters.forEach { it.reset() }
        tongueDirFilters.forEach { it.reset() }
        tongue.reset()
    }

    override fun close() {
        sinks.forEach { it.close() }
        sinks.clear()
        tracker.close()
    }

    companion object {
        const val EYE_YAW_DEG = 30f
        const val EYE_PITCH_DEG = 25f
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        /** 1 por debajo de [start]; baja hasta 0 con pendiente [slope]. */
        private fun gateDown(v: Float, start: Float, slope: Float) = (1f - max(0f, v - start) * slope).coerceIn(0f, 1f)
    }
}
