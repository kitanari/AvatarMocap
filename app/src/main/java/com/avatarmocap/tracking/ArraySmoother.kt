package com.avatarmocap.tracking

import kotlin.math.PI
import kotlin.math.abs

/** Filtro One Euro sobre un arreglo de valores (p. ej. los 21 puntos de una mano). */
class ArraySmoother(private val minCutoff: Float = 2f, private val beta: Float = 20f, private val dCutoff: Float = 1f) {
    private var out = FloatArray(0)
    private var dx = FloatArray(0)
    private var last = 0L
    private var init = false

    fun reset() { init = false }

    fun update(v: FloatArray, nanos: Long): FloatArray {
        if (out.size != v.size) { out = FloatArray(v.size); dx = FloatArray(v.size); init = false }
        if (!init) { v.copyInto(out); dx.fill(0f); last = nanos; init = true; return out.copyOf() }
        val dt = ((nanos - last) / 1e9f).coerceIn(1e-3f, 0.3f)
        last = nanos
        val aD = alpha(dt, dCutoff)
        for (i in v.indices) {
            val d = (v[i] - out[i]) / dt
            dx[i] += aD * (d - dx[i])
            out[i] += alpha(dt, minCutoff + beta * abs(dx[i])) * (v[i] - out[i])
        }
        return out.copyOf()
    }

    private fun alpha(dt: Float, cutoff: Float): Float = 1f / (1f + 1f / (2f * PI.toFloat() * cutoff) / dt)
}
