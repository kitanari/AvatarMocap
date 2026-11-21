package com.avatarmocap.tracking

/**
 * Un cuadro de tracking ya procesado (calibrado + filtrado), listo para enviarse.
 *
 * Convenciones:
 *  - blend: 52 valores 0..1 en orden [ArKit.NAMES]. "Left/Right" = lado del USUARIO.
 *  - headRot: grados (pitch, yaw, roll) relativos a la pose neutral calibrada.
 *  - headPos: metros (x, y, z) relativos a la pose neutral calibrada.
 *  - leftEye / rightEye: grados (pitch, yaw).
 *  - tongueX / tongueY: -1..1, dirección de la lengua (experimental). X+ = hacia la izquierda del usuario, Y+ = hacia abajo.
 */
class FaceFrame(
    val timestampNanos: Long,
    val faceFound: Boolean,
    val blend: FloatArray,
    val headRot: FloatArray,
    val headPos: FloatArray,
    val leftEye: FloatArray,
    val rightEye: FloatArray,
    val tongueX: Float,
    val tongueY: Float,
    /** Expresiones VRM (ver [Vrm]). */
    val vrm: FloatArray = FloatArray(Vrm.COUNT),
    /** Manos (si el seguimiento de manos está activado). */
    val hands: HandsFrame = HandsFrame.NONE,
) {
    operator fun get(i: Int): Float = blend[i]

    companion object {
        fun lost(timestampNanos: Long) = FaceFrame(
            timestampNanos, false, FloatArray(ArKit.COUNT), FloatArray(3), FloatArray(3),
            FloatArray(2), FloatArray(2), 0f, 0f,
        )
    }
}
