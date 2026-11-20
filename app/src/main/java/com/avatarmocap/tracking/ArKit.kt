package com.avatarmocap.tracking

/**
 * Los 52 blendshapes de ARKit en su orden canónico. Todo el pipeline usa este orden
 * (FloatArray de tamaño 52), así las salidas (iFacialMocap, VTS, VMC...) solo traducen nombres.
 */
object ArKit {
    val NAMES = arrayOf(
        "eyeBlinkLeft", "eyeLookDownLeft", "eyeLookInLeft", "eyeLookOutLeft", "eyeLookUpLeft",
        "eyeSquintLeft", "eyeWideLeft",
        "eyeBlinkRight", "eyeLookDownRight", "eyeLookInRight", "eyeLookOutRight", "eyeLookUpRight",
        "eyeSquintRight", "eyeWideRight",
        "jawForward", "jawLeft", "jawRight", "jawOpen",
        "mouthClose", "mouthFunnel", "mouthPucker", "mouthLeft", "mouthRight",
        "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
        "mouthDimpleLeft", "mouthDimpleRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthRollLower", "mouthRollUpper", "mouthShrugLower", "mouthShrugUpper",
        "mouthPressLeft", "mouthPressRight", "mouthLowerDownLeft", "mouthLowerDownRight",
        "mouthUpperUpLeft", "mouthUpperUpRight",
        "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
        "cheekPuff", "cheekSquintLeft", "cheekSquintRight",
        "noseSneerLeft", "noseSneerRight",
        "tongueOut",
    )

    const val COUNT = 52

    val INDEX: Map<String, Int> = NAMES.withIndex().associate { it.value to it.index }

    fun idx(name: String): Int = INDEX.getValue(name)

    val EYE_BLINK_L = idx("eyeBlinkLeft")
    val EYE_BLINK_R = idx("eyeBlinkRight")
    val EYE_WIDE_L = idx("eyeWideLeft")
    val EYE_WIDE_R = idx("eyeWideRight")
    val LOOK_UP_L = idx("eyeLookUpLeft")
    val LOOK_DOWN_L = idx("eyeLookDownLeft")
    val LOOK_IN_L = idx("eyeLookInLeft")
    val LOOK_OUT_L = idx("eyeLookOutLeft")
    val LOOK_UP_R = idx("eyeLookUpRight")
    val LOOK_DOWN_R = idx("eyeLookDownRight")
    val LOOK_IN_R = idx("eyeLookInRight")
    val LOOK_OUT_R = idx("eyeLookOutRight")
    val JAW_OPEN = idx("jawOpen")
    val SMILE_L = idx("mouthSmileLeft")
    val SMILE_R = idx("mouthSmileRight")
    val FROWN_L = idx("mouthFrownLeft")
    val FROWN_R = idx("mouthFrownRight")
    val MOUTH_LEFT = idx("mouthLeft")
    val MOUTH_RIGHT = idx("mouthRight")
    val BROW_DOWN_L = idx("browDownLeft")
    val BROW_DOWN_R = idx("browDownRight")
    val BROW_INNER_UP = idx("browInnerUp")
    val BROW_OUTER_UP_L = idx("browOuterUpLeft")
    val BROW_OUTER_UP_R = idx("browOuterUpRight")
    val CHEEK_PUFF = idx("cheekPuff")
    val TONGUE_OUT = idx("tongueOut")

    /** Para cada índice, el índice de su par espejado (o él mismo si no tiene lado). */
    val MIRROR: IntArray = IntArray(COUNT) { i ->
        val n = NAMES[i]
        val m = when {
            n.contains("Left") -> n.replace("Left", "Right")
            n.contains("Right") -> n.replace("Right", "Left")
            else -> n
        }
        INDEX.getValue(m)
    }

    // Grupos para el "ajuste fino" de sensibilidad
    const val G_EYES = 0
    const val G_BROWS = 1
    const val G_MOUTH_OPEN = 2
    const val G_SMILE = 3
    const val G_CHEEKS = 4
    const val G_TONGUE = 5
    const val GROUP_COUNT = 6

    /** Grupo de cada blendshape, o -1 si no tiene ajuste de sensibilidad. */
    val GROUP: IntArray = IntArray(COUNT) { i ->
        val n = NAMES[i]
        when {
            n.startsWith("eyeBlink") || n.startsWith("eyeWide") || n.startsWith("eyeSquint") -> G_EYES
            n.startsWith("brow") -> G_BROWS
            n == "jawOpen" || n.startsWith("mouthLowerDown") || n.startsWith("mouthUpperUp") -> G_MOUTH_OPEN
            n.startsWith("mouthSmile") || n.startsWith("cheekSquint") || n.startsWith("mouthDimple") -> G_SMILE
            n == "cheekPuff" -> G_CHEEKS
            n == "tongueOut" -> G_TONGUE
            else -> -1
        }
    }

    val MOUTH_PUCKER = idx("mouthPucker")
    val MOUTH_CLOSE = idx("mouthClose")
    val ROLL_LOWER = idx("mouthRollLower")
    val ROLL_UPPER = idx("mouthRollUpper")
    val JAW_LEFT = idx("jawLeft")
    val JAW_RIGHT = idx("jawRight")

    /** Blendshapes de boca que MediaPipe estima con mucho ruido: llevan zona muerta en modo estable. */
    val NOISY_MOUTH: IntArray = arrayOf(
        "jawLeft", "jawRight", "jawForward",
        "mouthRollLower", "mouthRollUpper", "mouthShrugLower", "mouthShrugUpper",
        "mouthPressLeft", "mouthPressRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft", "mouthFrownRight",
    ).map { idx(it) }.toIntArray()

    /** Pares izquierda/derecha de la boca que se pueden simetrizar. */
    val MOUTH_PAIRS: Array<IntArray> = arrayOf(
        "mouthSmile", "mouthFrown", "mouthStretch", "mouthPress",
        "mouthLowerDown", "mouthUpperUp", "mouthDimple", "cheekSquint",
    ).map { intArrayOf(idx(it + "Left"), idx(it + "Right")) }.toTypedArray()

    val SIDEWAYS: IntArray = intArrayOf(MOUTH_LEFT, MOUTH_RIGHT, JAW_LEFT, JAW_RIGHT)

    /**
     * Valor que manda un iPhone (ARKit) cuando hacés el gesto al máximo. Los modelos "perfect sync"
     * están diseñados para estos rangos: si les mandás 1.0 en todo, la boca se rompe.
     * La calibración lleva TU máximo a este valor (no a 1.0).
     */
    val REF_MAX: FloatArray = FloatArray(COUNT) { i ->
        val n = NAMES[i]
        when {
            n == "jawOpen" -> 0.70f
            n.startsWith("mouthLowerDown") -> 0.55f
            n.startsWith("mouthUpperUp") -> 0.50f
            n == "mouthFunnel" -> 0.60f
            n == "mouthPucker" -> 0.80f
            n.startsWith("mouthStretch") -> 0.45f
            n.startsWith("mouthSmile") -> 0.85f
            n.startsWith("mouthFrown") -> 0.55f
            n.startsWith("mouthDimple") -> 0.45f
            n.startsWith("mouthRoll") -> 0.50f
            n.startsWith("mouthShrug") -> 0.50f
            n.startsWith("mouthPress") -> 0.50f
            n == "mouthClose" -> 0.35f
            n == "mouthLeft" || n == "mouthRight" -> 0.50f
            n == "jawLeft" || n == "jawRight" || n == "jawForward" -> 0.40f
            n.startsWith("eyeWide") -> 0.70f
            n.startsWith("eyeSquint") -> 0.75f
            n.startsWith("cheekSquint") -> 0.75f
            n.startsWith("noseSneer") -> 0.70f
            n == "cheekPuff" -> 0.85f
            else -> 1f // parpadeo, mirada, cejas, lengua
        }
    }

    /** Intercambia lados in-place (útil si la cámara entrega la imagen espejada). */
    fun swapSides(v: FloatArray) {
        for (i in 0 until COUNT) {
            val j = MIRROR[i]
            if (j > i) {
                val t = v[i]; v[i] = v[j]; v[j] = t
            }
        }
    }
}
