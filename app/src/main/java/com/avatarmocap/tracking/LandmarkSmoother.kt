package com.avatarmocap.tracking

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtro One Euro sobre los 478 puntos (x, y, z) a la vez, en arreglos planos (sin objetos).
 * Quita el "temblor" de la malla: los ojos, la boca y el iris medidos sobre ella quedan
 * estables, sin agregar retraso cuando te movés rápido.
 *
 * Salida: [out] = x0, y0, z0, x1, y1, z1, ... (coordenadas normalizadas de MediaPipe).
 */
class LandmarkSmoother(
    private val minCutoff: Float = 2.5f,
    private val beta: Float = 25f,
    private val dCutoff: Float = 1f,
) {
    var out = FloatArray(0)
        private set
    var count = 0
        private set
    private var dx = FloatArray(0)
    private var lastNanos = 0L
    private var initialized = false

    fun reset() { initialized = false }

    fun update(lm: List<NormalizedLandmark>, nanos: Long): FloatArray {
        val n = lm.size
        if (out.size != n * 3) {
            out = FloatArray(n * 3); dx = FloatArray(n * 3); initialized = false
        }
        count = n
        if (!initialized) {
            for (i in 0 until n) {
                val p = lm[i]
                out[i * 3] = p.x(); out[i * 3 + 1] = p.y(); out[i * 3 + 2] = p.z()
            }
            dx.fill(0f)
            lastNanos = nanos
            initialized = true
            return out
        }
        val dt = ((nanos - lastNanos) / 1e9f).coerceIn(1e-3f, 0.3f)
        lastNanos = nanos
        val aD = alpha(dt, dCutoff)
        for (i in 0 until n) {
            val p = lm[i]
            for (c in 0..2) {
                val k = i * 3 + c
                val v = when (c) { 0 -> p.x(); 1 -> p.y(); else -> p.z() }
                val d = (v - out[k]) / dt
                dx[k] += aD * (d - dx[k])
                val cutoff = minCutoff + beta * abs(dx[k])
                out[k] += alpha(dt, cutoff) * (v - out[k])
            }
        }
        return out
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
