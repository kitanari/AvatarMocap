package com.avatarmocap.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtro One Euro (Casiez et al. 2012): suaviza mucho cuando el valor está quieto
 * (elimina el temblor) y casi no agrega retraso cuando el valor se mueve rápido.
 */
class OneEuroFilter(
    var minCutoff: Float = 1.0f,
    var beta: Float = 0.0f,
    var dCutoff: Float = 1.0f,
) {
    private var xPrev = Float.NaN
    private var dxPrev = 0f
    private var tPrev = 0L

    fun filter(x: Float, tNanos: Long): Float {
        if (xPrev.isNaN()) {
            xPrev = x; tPrev = tNanos; dxPrev = 0f
            return x
        }
        val dt = ((tNanos - tPrev) / 1e9f).coerceIn(1e-3f, 0.5f)
        tPrev = tNanos
        val dx = (x - xPrev) / dt
        dxPrev += alpha(dt, dCutoff) * (dx - dxPrev)
        val cutoff = minCutoff + beta * abs(dxPrev)
        xPrev += alpha(dt, cutoff) * (x - xPrev)
        return xPrev
    }

    fun reset() {
        xPrev = Float.NaN
        dxPrev = 0f
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
