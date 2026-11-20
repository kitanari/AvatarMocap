package com.avatarmocap.tracking

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

/**
 * Medidas geométricas de la malla de 478 puntos para lo que MediaPipe detecta mal.
 */
object FaceGeometry {

    /**
     * Cachetes inflados, medido POR LADO: distancia del contorno bajo de la cara al puente de la
     * nariz (punto rígido), dividida por el ancho de las sienes (no cambia con gestos).
     *
     * Al inflar, se ensanchan los DOS lados. Al mover la boca o la mandíbula hacia un costado,
     * un lado se aleja y el otro se acerca, así que usar el mínimo de ambos lados evita el falso
     * positivo. En 3D para que girar la cabeza afecte poco.
     *
     * out[0] = lado izquierdo de la imagen, out[1] = lado derecho.
     */
    fun puffSides(lm: List<NormalizedLandmark>, w: Int, h: Int, out: FloatArray) {
        fun d(a: Int, b: Int): Float {
            val pa = lm[a]; val pb = lm[b]
            val dx = (pa.x() - pb.x()) * w
            val dy = (pa.y() - pb.y()) * h
            val dz = (pa.z() - pb.z()) * w
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val upper = d(127, 356).coerceAtLeast(1e-3f)
        out[0] = (d(132, NOSE_BRIDGE) + d(58, NOSE_BRIDGE) + d(172, NOSE_BRIDGE)) / (3f * upper)
        out[1] = (d(361, NOSE_BRIDGE) + d(288, NOSE_BRIDGE) + d(397, NOSE_BRIDGE)) / (3f * upper)
    }

    private const val NOSE_BRIDGE = 6

    /** Puntos que se dibujan sobre la cámara (contornos principales). */
    val OVERLAY_POINTS: IntArray = (
        // contorno de la cara
        intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
            152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109) +
            // cejas
            intArrayOf(70, 63, 105, 66, 107, 336, 296, 334, 293, 300) +
            // ojos
            intArrayOf(33, 160, 158, 133, 153, 144, 362, 385, 387, 263, 373, 380) +
            // nariz
            intArrayOf(6, 197, 4, 1, 98, 327) +
            // iris (si el modelo los trae)
            intArrayOf(468, 473)
        )

    /** Labios (se dibujan en otro color). */
    val OVERLAY_LIPS: IntArray = intArrayOf(
        61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146,
        78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95,
    )
}
