package com.avatarmocap.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pose de la cabeza calculada SOLO con puntos rígidos de la cara (frente, sienes, puente y punta
 * de la nariz, comisuras de los ojos, pómulos altos).
 *
 * La pose que da MediaPipe se ajusta a TODOS los puntos, incluida la boca: por eso mover la boca
 * hacia un costado hacía girar la cabeza del avatar. Acá la boca, la mandíbula y los cachetes no
 * participan.
 *
 * Se resuelve la rotación entre la cara neutral calibrada y la actual con el método de Horn
 * (cuaterniones, mínimos cuadrados), robusto y sin SVD.
 */
object RigidHead {

    val POINTS = intArrayOf(
        10, 151, 9, 8,              // frente / entrecejo
        127, 356, 162, 389, 21, 251, // sienes
        33, 263, 133, 362,          // comisuras de los ojos
        6, 168, 197, 195, 5, 4, 1,  // puente y punta de la nariz
        116, 345, 93, 323,          // pómulos altos
    )
    val COUNT = POINTS.size

    /** Pasa los puntos rígidos a coordenadas de cámara (X derecha, Y arriba, Z hacia la cámara). */
    fun extract(p: FloatArray, w: Int, h: Int, out: FloatArray) {
        for ((k, i) in POINTS.withIndex()) {
            out[k * 3] = p[i * 3] * w
            out[k * 3 + 1] = -p[i * 3 + 1] * h
            out[k * 3 + 2] = -p[i * 3 + 2] * w
        }
    }

    private val q = floatArrayOf(1f, 0f, 0f, 0f)
    private val n = FloatArray(16)
    private val tmp = FloatArray(4)

    /**
     * Rotación de [ref] a [cur] (ambos de [extract]). Devuelve pitch, yaw, roll en grados en [out],
     * con la misma convención que [HeadPose].
     */
    fun solve(ref: FloatArray, cur: FloatArray, out: FloatArray) {
        var ax = 0f; var ay = 0f; var az = 0f; var bx = 0f; var by = 0f; var bz = 0f
        for (k in 0 until COUNT) {
            ax += ref[k * 3]; ay += ref[k * 3 + 1]; az += ref[k * 3 + 2]
            bx += cur[k * 3]; by += cur[k * 3 + 1]; bz += cur[k * 3 + 2]
        }
        ax /= COUNT; ay /= COUNT; az /= COUNT; bx /= COUNT; by /= COUNT; bz /= COUNT

        var sxx = 0f; var sxy = 0f; var sxz = 0f
        var syx = 0f; var syy = 0f; var syz = 0f
        var szx = 0f; var szy = 0f; var szz = 0f
        for (k in 0 until COUNT) {
            val a0 = ref[k * 3] - ax; val a1 = ref[k * 3 + 1] - ay; val a2 = ref[k * 3 + 2] - az
            val b0 = cur[k * 3] - bx; val b1 = cur[k * 3 + 1] - by; val b2 = cur[k * 3 + 2] - bz
            sxx += a0 * b0; sxy += a0 * b1; sxz += a0 * b2
            syx += a1 * b0; syy += a1 * b1; syz += a1 * b2
            szx += a2 * b0; szy += a2 * b1; szz += a2 * b2
        }
        // Matriz simétrica de Horn; su autovector principal es el cuaternión (w, x, y, z)
        n[0] = sxx + syy + szz; n[1] = syz - szy; n[2] = szx - sxz; n[3] = sxy - syx
        n[4] = n[1]; n[5] = sxx - syy - szz; n[6] = sxy + syx; n[7] = szx + sxz
        n[8] = n[2]; n[9] = n[6]; n[10] = -sxx + syy - szz; n[11] = syz + szy
        n[12] = n[3]; n[13] = n[7]; n[14] = n[11]; n[15] = -sxx - syy + szz
        var shift = 0f
        for (v in n) shift += abs(v)
        for (d in 0..3) n[d * 5] += shift

        // Iteración de potencias, arrancando del cuaternión del cuadro anterior (converge rápido)
        repeat(25) {
            for (r in 0..3) tmp[r] = n[r * 4] * q[0] + n[r * 4 + 1] * q[1] + n[r * 4 + 2] * q[2] + n[r * 4 + 3] * q[3]
            val len = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2] + tmp[3] * tmp[3])
            if (len < 1e-9f) return
            for (r in 0..3) q[r] = tmp[r] / len
        }
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val r00 = 1 - 2 * (y * y + z * z)
        val r10 = 2 * (x * y + w * z)
        val r20 = 2 * (x * z - w * y)
        val r21 = 2 * (y * z + w * x)
        val r22 = 1 - 2 * (x * x + y * y)
        out[0] = Math.toDegrees(atan2(r21, r22).toDouble()).toFloat()
        out[1] = Math.toDegrees(atan2(-r20, sqrt(r21 * r21 + r22 * r22)).toDouble()).toFloat()
        out[2] = Math.toDegrees(atan2(r10, r00).toDouble()).toFloat()
    }
}
