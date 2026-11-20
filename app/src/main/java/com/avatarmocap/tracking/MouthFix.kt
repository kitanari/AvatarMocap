package com.avatarmocap.tracking

import kotlin.math.min

/**
 * Correcciones de boca para que el avatar no se deforme.
 *
 * Los modelos ARKit/"perfect sync" suman TODOS los blendshapes de boca a la vez. Con iPhone
 * eso anda porque los valores son limpios; con MediaPipe muchos tienen ruido y la boca se
 * tuerce o los labios atraviesan la mandíbula. Acá:
 *  1. Zona muerta en los blendshapes ruidosos (laterales, roll, shrug, press, stretch...).
 *  2. Simetría parcial entre izquierda y derecha (evita la boca "chueca").
 *  3. Movimiento lateral atenuado (configurable).
 *  4. Anti-clipping: mouthClose nunca supera a jawOpen y los labios no se enrollan con la boca abierta.
 */
object MouthFix {

    fun apply(v: FloatArray, stable: Boolean, symmetry: Float, sideGain: Float) {
        if (stable) {
            for (i in ArKit.NOISY_MOUTH) v[i] = ((v[i] - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
        }
        if (symmetry > 0f) {
            for (p in ArKit.MOUTH_PAIRS) {
                val l = v[p[0]]; val r = v[p[1]]
                val m = (l + r) / 2f
                v[p[0]] = l + (m - l) * symmetry
                v[p[1]] = r + (m - r) * symmetry
            }
        }
        for (i in ArKit.SIDEWAYS) v[i] = (v[i] * sideGain).coerceIn(0f, 1f)

        val jaw = v[ArKit.JAW_OPEN]
        // En ARKit mouthClose = labios juntos CON la mandíbula abierta; si lo supera, los labios se cruzan.
        v[ArKit.MOUTH_CLOSE] = min(v[ArKit.MOUTH_CLOSE], jaw)
        val open = (1f - jaw * 1.5f).coerceIn(0f, 1f)
        v[ArKit.ROLL_LOWER] *= open
        v[ArKit.ROLL_UPPER] *= open
    }

    private const val DEADZONE = 0.15f
}
