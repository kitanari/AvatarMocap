package com.avatarmocap.tracking

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Expresiones básicas de VRM, calculadas a partir de los blendshapes y de las plantillas que el
 * usuario grabó en la calibración (A, I, U, E, O, Joy, Angry, Sorrow, Fun, Sorpresa).
 *
 * Para cada expresión se mide cuánto del gesto grabado está presente ahora (proyección) y cuánto
 * se parece la forma (coseno). Así la "O" no se confunde con la "U" aunque ambas usen los labios.
 */
object Vrm {
    const val A = 0; const val I = 1; const val U = 2; const val E = 3; const val O = 4
    const val BLINK = 5; const val BLINK_L = 6; const val BLINK_R = 7
    const val JOY = 8; const val ANGRY = 9; const val SORROW = 10; const val FUN = 11; const val SURPRISED = 12
    const val COUNT = 13

    /** Nombres de VRM 0.x (VSeeFace, VNyan, la mayoría de modelos de VRoid). */
    val NAMES_V0 = arrayOf("A", "I", "U", "E", "O", "Blink", "Blink_L", "Blink_R", "Joy", "Angry", "Sorrow", "Fun", "Surprised")
    /** Nombres de VRM 1.0. */
    val NAMES_V1 = arrayOf("aa", "ih", "ou", "ee", "oh", "blink", "blinkLeft", "blinkRight", "happy", "angry", "sad", "relaxed", "surprised")
    /** Para mostrar en la app. */
    val LABELS = arrayOf("A", "I", "U", "E", "O", "Parpadeo", "Parpadeo izq.", "Parpadeo der.", "Alegría", "Enojo", "Tristeza", "Diversión", "Sorpresa")

    private val MOUTH = arrayOf(
        "jawOpen", "mouthFunnel", "mouthPucker", "mouthSmileLeft", "mouthSmileRight",
        "mouthStretchLeft", "mouthStretchRight", "mouthLowerDownLeft", "mouthLowerDownRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "mouthClose", "mouthRollLower", "mouthRollUpper",
        "mouthPressLeft", "mouthPressRight", "mouthShrugLower", "mouthShrugUpper",
    ).map { ArKit.idx(it) }.toIntArray()

    private val EMOTION = arrayOf(
        "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
        "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight",
        "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
        "cheekSquintLeft", "cheekSquintRight", "noseSneerLeft", "noseSneerRight",
        "mouthPressLeft", "mouthPressRight", "jawOpen",
    ).map { ArKit.idx(it) }.toIntArray()

    private val x = FloatArray(ArKit.COUNT)
    private val scores = FloatArray(5)

    /**
     * @param raw blendshapes crudos de MediaPipe (antes de calibrar)
     * @param blend blendshapes finales (para el parpadeo)
     */
    fun compute(
        raw: FloatArray, blend: FloatArray, cal: Calibration,
        visemeGain: Float, emotionGain: Float, out: FloatArray,
    ) {
        out.fill(0f)
        for (i in 0 until ArKit.COUNT) x[i] = max(0f, raw[i] - (if (cal.hasNeutral) cal.neutral[i] else 0f))

        // ---- Vocales
        if (cal.visemeT.any { it != null }) {
            distribute(cal.visemeT, MOUTH, 3f, 0f)
            for (k in 0 until 5) out[A + k] = (scores[k] * visemeGain).coerceIn(0f, 1f)
        } else {
            // Sin calibrar: aproximación simple
            val jaw = blend[ArKit.JAW_OPEN]
            val stretch = (blend[ArKit.idx("mouthStretchLeft")] + blend[ArKit.idx("mouthStretchRight")]) / 2f
            val funnel = blend[ArKit.idx("mouthFunnel")]
            val pucker = blend[ArKit.MOUTH_PUCKER]
            out[A] = (jaw * (1f - funnel)) * visemeGain
            out[O] = (jaw * funnel) * visemeGain
            out[U] = (pucker * (1f - jaw)) * visemeGain
            out[I] = (stretch * (1f - jaw)) * visemeGain
            out[E] = (stretch * jaw) * visemeGain
            for (k in A..O) out[k] = out[k].coerceIn(0f, 1f)
        }

        // ---- Emociones (con zona muerta para que no parpadeen)
        if (cal.emotionT.any { it != null }) {
            distribute(cal.emotionT, EMOTION, 2f, 0f)
            for (k in 0 until 5) {
                val w = ((scores[k] - 0.25f) / 0.75f).coerceIn(0f, 1f)
                out[JOY + k] = (w * emotionGain).coerceIn(0f, 1f)
            }
        }

        // ---- Parpadeo: ambos ojos -> Blink; la diferencia -> Blink_L/R (guiños)
        val l = blend[ArKit.EYE_BLINK_L]
        val r = blend[ArKit.EYE_BLINK_R]
        val both = minOf(l, r)
        out[BLINK] = both
        out[BLINK_L] = l - both
        out[BLINK_R] = r - both
    }

    /**
     * Para cada plantilla: intensidad (proyección) × parecido (coseno^p). Después se reparte la
     * intensidad máxima entre las candidatas en proporción a su puntaje al cuadrado.
     */
    private fun distribute(templates: Array<FloatArray?>, subset: IntArray, p: Float, floor: Float) {
        var xx = 0f
        for (i in subset) xx += x[i] * x[i]
        val xn = sqrt(xx)
        var best = 0f
        var sumSq = 0f
        for (k in 0 until 5) {
            val t = templates[k]
            if (t == null || xn < 1e-4f) { scores[k] = 0f; continue }
            var xt = 0f; var tt = 0f
            for (i in subset) { xt += x[i] * t[i]; tt += t[i] * t[i] }
            if (tt < 1e-4f) { scores[k] = 0f; continue }
            val intensity = (xt / tt).coerceIn(0f, 1.2f)
            val cos = (xt / (xn * sqrt(tt))).coerceIn(0f, 1f)
            val s = max(floor, intensity * Math.pow(cos.toDouble(), p.toDouble()).toFloat())
            scores[k] = s
            best = max(best, s)
            sumSq += s * s
        }
        if (sumSq <= 0f) return
        for (k in 0 until 5) scores[k] = best * scores[k] * scores[k] / sumSq
    }
}
