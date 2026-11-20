package com.avatarmocap.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

object HeadPose {
    private const val RAD2DEG = (180.0 / Math.PI).toFloat()

    /**
     * Convierte la matriz de transformación facial 4x4 de MediaPipe en
     * [pitch, yaw, roll] (grados) + [tx, ty, tz] (centímetros).
     *
     * La disposición (row/column major) se detecta sola: la traslación de la cara
     * (z ≈ -30..-80 cm) está en m[12..14] si es column-major, o en m[3,7,11] si es row-major.
     */
    fun fromMatrix(m: FloatArray): FloatArray {
        val colMajor = abs(m[12]) + abs(m[13]) + abs(m[14]) >= abs(m[3]) + abs(m[7]) + abs(m[11])
        fun r(row: Int, col: Int) = if (colMajor) m[col * 4 + row] else m[row * 4 + col]

        val tx = if (colMajor) m[12] else m[3]
        val ty = if (colMajor) m[13] else m[7]
        val tz = if (colMajor) m[14] else m[11]

        val pitch = atan2(r(2, 1), r(2, 2)) * RAD2DEG
        val yaw = atan2(-r(2, 0), sqrt(r(2, 1) * r(2, 1) + r(2, 2) * r(2, 2))) * RAD2DEG
        val roll = atan2(r(1, 0), r(0, 0)) * RAD2DEG
        return floatArrayOf(pitch, yaw, roll, tx, ty, tz)
    }
}
