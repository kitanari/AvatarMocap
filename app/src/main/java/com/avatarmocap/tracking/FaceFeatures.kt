package com.avatarmocap.tracking

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

/**
 * Medidas geométricas tomadas directamente de la malla de 478 puntos. Son lineales y estables,
 * y una vez calibradas a la cara del usuario son más precisas que algunos blendshapes de
 * MediaPipe (parpadeo, apertura de boca, mirada).
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
    const val COUNT = 10
}

object FaceFeatures {

    /** Calcula todo menos TONGUE (lo pone el detector de lengua). */
    fun compute(lm: List<NormalizedLandmark>, w: Int, h: Int, out: FloatArray) {
        fun d(a: Int, b: Int): Float {
            val pa = lm[a]; val pb = lm[b]
            val dx = (pa.x() - pb.x()) * w
            val dy = (pa.y() - pb.y()) * h
            val dz = (pa.z() - pb.z()) * w
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        // Cachetes por lado: contorno bajo -> puente de la nariz, / ancho de sienes
        val temples = d(127, 356).coerceAtLeast(1e-3f)
        out[Feat.PUFF_R] = (d(132, 6) + d(58, 6) + d(172, 6)) / (3f * temples)
        out[Feat.PUFF_L] = (d(361, 6) + d(288, 6) + d(397, 6)) / (3f * temples)

        // Ojos: promedio de 3 alturas / ancho
        out[Feat.EAR_R] = (d(160, 144) + d(159, 145) + d(158, 153)) / (3f * d(33, 133).coerceAtLeast(1e-3f))
        out[Feat.EAR_L] = (d(387, 373) + d(386, 374) + d(385, 380)) / (3f * d(263, 362).coerceAtLeast(1e-3f))

        // Boca: separación de los labios internos / distancia entre ojos (no cambia al abrir la boca)
        out[Feat.GAP] = d(13, 14) / d(33, 263).coerceAtLeast(1e-3f)

        // Iris (si el modelo los trae: 478 puntos)
        if (lm.size >= 478) {
            iris(lm, w, h, 473, 362, 263, out, Feat.IRIS_HL, Feat.IRIS_VL)
            iris(lm, w, h, 468, 133, 33, out, Feat.IRIS_HR, Feat.IRIS_VR)
        }
    }

    /** Posición del iris dentro del ojo, en el eje del ojo y su perpendicular (2D). */
    private fun iris(
        lm: List<NormalizedLandmark>, w: Int, h: Int,
        irisIdx: Int, inner: Int, outer: Int, out: FloatArray, hIdx: Int, vIdx: Int,
    ) {
        val ix = lm[inner].x() * w; val iy = lm[inner].y() * h
        val ox = lm[outer].x() * w; val oy = lm[outer].y() * h
        val px = lm[irisIdx].x() * w; val py = lm[irisIdx].y() * h
        val vx = ox - ix; val vy = oy - iy
        val len2 = (vx * vx + vy * vy).coerceAtLeast(1e-3f)
        val len = sqrt(len2)
        out[hIdx] = ((px - ix) * vx + (py - iy) * vy) / len2
        // Perpendicular apuntando hacia abajo de la imagen
        var nx = -vy / len; var ny = vx / len
        if (ny < 0) { nx = -nx; ny = -ny }
        val mx = (ix + ox) / 2f; val my = (iy + oy) / 2f
        out[vIdx] = ((px - mx) * nx + (py - my) * ny) / len
    }
}
