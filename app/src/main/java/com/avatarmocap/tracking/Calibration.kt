package com.avatarmocap.tracking

import kotlin.math.max

/**
 * Calibración por usuario:
 *  1. Neutral: con la cara relajada mirando a la cámara se guarda el valor de reposo de
 *     cada blendshape y la pose de la cabeza. Se resta y se re-escala (0..1).
 *  2. Auto-rango: aprende el máximo real que alcanza cada blendshape en tu cara y lo
 *     estira hasta 1.0 (MediaPipe rara vez llega a 1 en parpadeos, sonrisas, etc.).
 *
 * Todo se ejecuta en el hilo de la cámara; las lecturas desde la UI son tolerantes.
 */
class Calibration {
    val neutral = FloatArray(ArKit.COUNT)
    val neutralHead = FloatArray(6)

    @Volatile
    var hasNeutral = false
        private set

    private val autoMax = FloatArray(ArKit.COUNT)

    private var capLeft = 0
    private var capN = 0
    private val capSum = FloatArray(ArKit.COUNT)
    private val capHead = FloatArray(6)

    var onNeutralDone: (() -> Unit)? = null

    @Synchronized
    fun startNeutralCapture(frames: Int = 30) {
        capSum.fill(0f); capHead.fill(0f); capN = 0
        capLeft = frames
    }

    val isCapturing: Boolean get() = capLeft > 0

    @Synchronized
    fun feed(raw: FloatArray, head: FloatArray) {
        if (capLeft <= 0) return
        for (i in 0 until ArKit.COUNT) capSum[i] += raw[i]
        for (i in 0 until 6) capHead[i] += head[i]
        capN++
        capLeft--
        if (capLeft == 0 && capN > 0) {
            for (i in 0 until ArKit.COUNT) neutral[i] = capSum[i] / capN
            for (i in 0 until 6) neutralHead[i] = capHead[i] / capN
            hasNeutral = true
            autoMax.fill(0f)
            onNeutralDone?.invoke()
        }
    }

    fun apply(raw: FloatArray, out: FloatArray, autoRange: Boolean) {
        for (i in 0 until ArKit.COUNT) {
            var v = raw[i]
            if (i != ArKit.TONGUE_OUT) { // la lengua tiene su propia calibración
                if (hasNeutral) {
                    val n = neutral[i].coerceAtMost(0.8f)
                    v = ((v - n) / (1f - n)).coerceIn(0f, 1f)
                }
                if (autoRange) {
                    autoMax[i] = max(autoMax[i] * AUTO_DECAY, v)
                    v = (v / max(autoMax[i], AUTO_MIN_RANGE)).coerceIn(0f, 1f)
                }
            }
            out[i] = v
        }
    }

    /** Pose de la cabeza relativa a la neutral. Entrada/salida: pitch,yaw,roll (°), x,y,z (cm). */
    fun relativeHead(head: FloatArray, out: FloatArray) {
        for (i in 0 until 6) out[i] = if (hasNeutral) head[i] - neutralHead[i] else head[i]
        if (!hasNeutral) { out[3] = 0f; out[4] = 0f; out[5] = 0f }
    }

    fun resetAutoRange() = autoMax.fill(0f)

    fun serialize(): String =
        if (!hasNeutral) "" else (neutral.toList() + neutralHead.toList()).joinToString(",")

    fun deserialize(s: String) {
        val parts = s.split(',').mapNotNull { it.toFloatOrNull() }
        if (parts.size != ArKit.COUNT + 6) return
        for (i in 0 until ArKit.COUNT) neutral[i] = parts[i]
        for (i in 0 until 6) neutralHead[i] = parts[ArKit.COUNT + i]
        hasNeutral = true
    }

    companion object {
        /** Decaimiento por cuadro del máximo aprendido (~45 s de vida media a 30 fps). */
        private const val AUTO_DECAY = 0.9995f
        /** Nunca amplificar más de 1/0.4 = 2.5x, para no convertir ruido en expresión. */
        private const val AUTO_MIN_RANGE = 0.4f
    }
}
