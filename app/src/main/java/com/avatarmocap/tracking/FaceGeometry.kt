package com.avatarmocap.tracking

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

/**
 * Medidas geométricas de la malla de 478 puntos para lo que MediaPipe detecta mal.
 */
object FaceGeometry {

    /**
     * Cachetes inflados: al inflarlos, la parte baja del contorno de la cara se ensancha y la
     * boca se angosta. Al sonreír pasa lo contrario (boca más ancha), así que la sonrisa no
     * se confunde. Todo se divide por el ancho de las sienes, que no cambia con el gesto.
     * Se usa 3D (x, y, z) para que girar la cabeza afecte poco.
     */
    fun puffRatio(lm: List<NormalizedLandmark>, w: Int, h: Int): Float {
        fun d(a: Int, b: Int): Float {
            val pa = lm[a]; val pb = lm[b]
            val dx = (pa.x() - pb.x()) * w
            val dy = (pa.y() - pb.y()) * h
            val dz = (pa.z() - pb.z()) * w
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val upper = d(127, 356).coerceAtLeast(1e-3f)
        val lower = (d(132, 361) + d(58, 288) + d(172, 397)) / 3f
        val mouth = d(61, 291)
        return lower / upper - 0.5f * mouth / upper
    }
}
