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
