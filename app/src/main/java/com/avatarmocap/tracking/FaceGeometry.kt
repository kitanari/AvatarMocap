package com.avatarmocap.tracking

import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/** Índices de la malla para dibujar sobre la cámara. */
object FaceGeometry {

    /** Contornos principales (modo "puntos"). */
    val OVERLAY_POINTS: IntArray = (
        intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
            152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109) +
            intArrayOf(70, 63, 105, 66, 107, 336, 296, 334, 293, 300) +
            intArrayOf(33, 160, 159, 158, 133, 153, 145, 144, 362, 385, 386, 387, 263, 373, 374, 380) +
            intArrayOf(6, 197, 4, 1, 98, 327) +
            intArrayOf(468, 473)
        )

    val OVERLAY_LIPS: IntArray = intArrayOf(
        61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146,
        78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95,
    )

    /** Aristas de la malla completa de MediaPipe (pares inicio, fin). */
    val MESH_EDGES: IntArray by lazy {
        val out = ArrayList<Int>()
        for (c in FaceLandmarker.FACE_LANDMARKS_TESSELATION) {
            out.add(c.start()); out.add(c.end())
        }
        out.toIntArray()
    }
}
