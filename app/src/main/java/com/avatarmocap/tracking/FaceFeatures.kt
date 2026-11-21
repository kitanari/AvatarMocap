package com.avatarmocap.tracking

import kotlin.math.sqrt

/**
 * Medidas geométricas tomadas directamente de la malla (ya suavizada). Son lineales y estables,
 * y calibradas a la cara del usuario son más precisas que algunos blendshapes de MediaPipe.
 *
 * L/R = lado del USUARIO (en MediaPipe, los puntos 263/362/473 son del ojo izquierdo del usuario).
 */
object Feat {
    const val PUFF_L = 0
    const val PUFF_R = 1
    const val TONGUE = 2
    const val EAR_L = 3      // apertura del ojo izquierdo (alto/ancho)
    const val EAR_R = 4
    const val GAP = 5        // separación de labios / distancia entre ojos
    const val IRIS_HL = 6    // iris izq.: 0 = lagrimal, 1 = extremo exterior
    const val IRIS_VL = 7    // iris izq.: vertical (+ abajo)
    const val IRIS_HR = 8
    const val IRIS_VR = 9
    // Color de la lengua (promedio de los píxeles candidatos, relativo a la piel)
    const val TONGUE_CR = 10
    const val TONGUE_CB = 11
    const val TONGUE_Y = 12
    // Color de los labios (relativo a la piel)
    const val LIP_CR = 13
    const val LIP_CB = 14
    const val LIP_Y = 15
    /** Desplazamiento lateral de la boca respecto de la nariz (+ = hacia la izquierda del usuario). */
    const val MOUTH_X = 16
    /** "Arruga" del surco nariz-boca (bordes en la imagen). Al inflar los cachetes se estira y baja. */
    const val FOLD_L = 17
    const val FOLD_R = 18
    const val COUNT = 19
}

object FaceFeatures {

    /**
     * @param p puntos planos x,y,z normalizados ([LandmarkSmoother.out]); [n] = cantidad de puntos.
     * Calcula todo menos la lengua (lo pone el detector de lengua).
     */
    fun compute(p: FloatArray, n: Int, w: Int, h: Int, out: FloatArray) {
        fun d(a: Int, b: Int): Float {
            val dx = (p[a * 3] - p[b * 3]) * w
            val dy = (p[a * 3 + 1] - p[b * 3 + 1]) * h
            val dz = (p[a * 3 + 2] - p[b * 3 + 2]) * w
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        val temples = d(127, 356).coerceAtLeast(1e-3f)
        out[Feat.PUFF_R] = (d(132, 6) + d(58, 6) + d(172, 6)) / (3f * temples)
        out[Feat.PUFF_L] = (d(361, 6) + d(288, 6) + d(397, 6)) / (3f * temples)

        out[Feat.EAR_R] = (d(160, 144) + d(159, 145) + d(158, 153)) / (3f * d(33, 133).coerceAtLeast(1e-3f))
        out[Feat.EAR_L] = (d(387, 373) + d(386, 374) + d(385, 380)) / (3f * d(263, 362).coerceAtLeast(1e-3f))

        out[Feat.GAP] = d(13, 14) / d(33, 263).coerceAtLeast(1e-3f)

        // Boca hacia los costados: centro de la boca proyectado sobre el eje de los ojos, respecto
        // del punto bajo la nariz (2). En 3D para que girar la cabeza afecte poco.
        val ax = (p[263 * 3] - p[33 * 3]) * w
        val ay = (p[263 * 3 + 1] - p[33 * 3 + 1]) * h
        val az = (p[263 * 3 + 2] - p[33 * 3 + 2]) * w
        val alen2 = (ax * ax + ay * ay + az * az).coerceAtLeast(1e-3f)
        val mx = ((p[61 * 3] + p[291 * 3]) / 2f - p[2 * 3]) * w
        val my = ((p[61 * 3 + 1] + p[291 * 3 + 1]) / 2f - p[2 * 3 + 1]) * h
        val mz = ((p[61 * 3 + 2] + p[291 * 3 + 2]) / 2f - p[2 * 3 + 2]) * w
        out[Feat.MOUTH_X] = (mx * ax + my * ay + mz * az) / alen2

        if (n >= 478) {
            iris(p, w, h, 473, 362, 263, out, Feat.IRIS_HL, Feat.IRIS_VL)
            iris(p, w, h, 468, 133, 33, out, Feat.IRIS_HR, Feat.IRIS_VR)
        }
    }

    private fun iris(
        p: FloatArray, w: Int, h: Int,
        irisIdx: Int, inner: Int, outer: Int, out: FloatArray, hIdx: Int, vIdx: Int,
    ) {
        val ix = p[inner * 3] * w; val iy = p[inner * 3 + 1] * h
        val ox = p[outer * 3] * w; val oy = p[outer * 3 + 1] * h
        val px = p[irisIdx * 3] * w; val py = p[irisIdx * 3 + 1] * h
        val vx = ox - ix; val vy = oy - iy
        val len2 = (vx * vx + vy * vy).coerceAtLeast(1e-3f)
        val len = sqrt(len2)
        out[hIdx] = ((px - ix) * vx + (py - iy) * vy) / len2
        var nx = -vy / len; var ny = vx / len
        if (ny < 0) { nx = -nx; ny = -ny }
        val mx = (ix + ox) / 2f; val my = (iy + oy) / 2f
        out[vIdx] = ((px - mx) * nx + (py - my) * ny) / len
    }
}
