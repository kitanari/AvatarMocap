package com.avatarmocap.tracking

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cachetes inflados mirando la IMAGEN, no la malla.
 *
 * La malla de MediaPipe casi no se deforma al inflar los cachetes. Lo que sí cambia en la foto
 * es el surco nasolabial (la "arruga" que va de la nariz a la comisura): al inflar, la piel se
 * estira y el surco casi desaparece. Medimos cuántos bordes (contraste) hay a lo largo de ese
 * surco en cada lado. Al sonreír pasa lo contrario (el surco se marca más), así que no se confunde.
 */
object CheekFold {

    /** out[0] = lado izquierdo del usuario, out[1] = derecho. Normalizado por el brillo de la piel. */
    fun compute(bmp: Bitmap, p: FloatArray, skinLuma: Float, out: FloatArray) {
        val w = bmp.width
        val h = bmp.height
        fun px(i: Int) = p[i * 3] * w
        fun py(i: Int) = p[i * 3 + 1] * h
        val mouthW = sqrt((px(61) - px(291)).let { it * it } + (py(61) - py(291)).let { it * it }).coerceAtLeast(4f)
        // Izquierda del usuario: ala de la nariz 327 -> comisura 291. Derecha: 98 -> 61.
        out[0] = side(bmp, px(327), py(327), px(291), py(291), mouthW) / skinLuma.coerceAtLeast(20f)
        out[1] = side(bmp, px(98), py(98), px(61), py(61), mouthW) / skinLuma.coerceAtLeast(20f)
    }

    private fun lum(bmp: Bitmap, x: Int, y: Int): Float {
        val c = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
        return 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    }

    /** Promedio del gradiente de brillo en una franja a lo largo del segmento a→b. */
    private fun side(bmp: Bitmap, ax: Float, ay: Float, bx: Float, by: Float, mouthW: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        // Perpendicular, hacia afuera de la cara (el surco está algo afuera de la línea nariz-boca)
        val nx = -dy / len; val ny = dx / len
        val d = (mouthW * 0.025f).coerceAtLeast(1f).roundToInt()
        var sum = 0f; var n = 0
        for (i in 1..8) {
            val t = 0.15f + 0.7f * i / 9f
            for (k in -2..2) {
                val off = k * mouthW * 0.05f
                val x = (ax + dx * t + nx * off).roundToInt()
                val y = (ay + dy * t + ny * off).roundToInt()
                if (x < d || y < d || x >= bmp.width - d || y >= bmp.height - d) continue
                val gx = lum(bmp, x + d, y) - lum(bmp, x - d, y)
                val gy = lum(bmp, x, y + d) - lum(bmp, x, y - d)
                sum += abs(gx) + abs(gy); n++
            }
        }
        return if (n == 0) Float.NaN else sum / n
    }
}
