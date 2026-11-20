package com.avatarmocap.tracking

import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detector de lengua (fase 1, visión clásica).
 *
 *  - Región A: interior de la boca (contorno interno de los labios, achicado un 18% para no
 *    tomar píxeles del labio). Sin lengua es oscuro o blanco (dientes); con lengua, rosado/rojo.
 *  - Región B: franja de piel bajo el labio inferior. Si la lengua sale mucho, la tapa.
 *
 * Contra falsos positivos:
 *  - Mediana de los últimos 5 cuadros (descarta destellos de un solo cuadro).
 *  - Histéresis: para activarse necesita 3 cuadros seguidos sobre 50%, para apagarse 4 bajo 20%.
 *  - Umbrales más estrictos en la franja del mentón (labial, barba, rubor).
 *
 * f0/f1 vienen de la calibración guiada ("Boca abierta" y "Lengua afuera").
 */
class TongueDetector {

    class Result(
        val value: Float,     // 0..1 calibrado y estabilizado
        val feature: Float,   // característica cruda (para calibrar / depurar)
        val x: Float,         // -1..1, X+ = derecha de la imagen (izquierda del usuario)
        val y: Float,         // -1..1, Y+ = hacia abajo
        val debug: Bitmap?,
    )

    @Volatile var f0 = 0.08f
    @Volatile var f1 = 0.45f

    private var pixels = IntArray(0)
    private var frameCounter = 0
    private val hist = FloatArray(5)
    private var histN = 0
    private var active = false
    private var onCount = 0
    private var offCount = 0
    private var skinY = 128f
    private var skinCr = 140f

    fun reset() {
        histN = 0; active = false; onCount = 0; offCount = 0
    }

    fun process(bmp: Bitmap, lm: List<NormalizedLandmark>, wantDebug: Boolean): Result {
        frameCounter++
        val w = bmp.width
        val h = bmp.height
        fun px(i: Int) = lm[i].x() * w
        fun py(i: Int) = lm[i].y() * h

        val mouthW = dist(px(61), py(61), px(291), py(291)).coerceAtLeast(4f)
        val cx = (px(61) + px(291)) / 2f
        val cy = (py(13) + py(14)) / 2f

        // --- Interior de la boca (achicado hacia el centro)
        val n = INNER_LIPS.size
        val polyX = FloatArray(n) { px(INNER_LIPS[it]) }
        val polyY = FloatArray(n) { py(INNER_LIPS[it]) }
        val pcx = polyX.average().toFloat()
        val pcy = polyY.average().toFloat()
        for (i in 0 until n) {
            polyX[i] = pcx + (polyX[i] - pcx) * SHRINK
            polyY[i] = pcy + (polyY[i] - pcy) * SHRINK
        }
        val innerArea = polygonArea(polyX, polyY)
        val mouthOpenEnough = innerArea > mouthW * mouthW * 0.02f

        // --- Franja del mentón
        val chinTop = py(17) + mouthW * 0.08f
        val chinBottom = chinTop + (py(152) - py(17)) * 0.4f
        val chinL = min(px(84), px(314))
        val chinR = max(px(84), px(314))

        val rx0 = floor0(min(polyX.min(), chinL) - 2f, w)
        val ry0 = floor0(min(polyY.min(), chinTop) - 2f, h)
        val rx1 = ceilW(max(polyX.max(), chinR) + 2f, w)
        val ry1 = ceilW(max(polyY.max(), chinBottom) + 2f, h)
        val rw = rx1 - rx0
        val rh = ry1 - ry0
        if (rw < 4 || rh < 4) return stabilize(0f, 0f, 0f, null)
        if (pixels.size < rw * rh) pixels = IntArray(rw * rh)
        bmp.getPixels(pixels, 0, rw, rx0, ry0, rw, rh)

        // --- Piel de referencia (mejillas), se actualiza cada 8 cuadros
        if (frameCounter % 8 == 1) {
            val a = FloatArray(2); val b = FloatArray(2)
            sampleSkin(bmp, px(205), py(205), a)
            sampleSkin(bmp, px(425), py(425), b)
            skinY = ((a[0] + b[0]) / 2f).coerceAtLeast(20f)
            skinCr = (a[1] + b[1]) / 2f
        }

        val debugPx = if (wantDebug && frameCounter % 3 == 0) pixels.copyOf(rw * rh) else null
        val step = max(1, (max(rw, rh) / 48f).roundToInt())

        var innerTotal = 0; var innerTongue = 0
        var chinTotal = 0; var chinTongue = 0
        var sumX = 0f; var sumY = 0f; var nT = 0

        var y = 0
        while (y < rh) {
            val fy = (ry0 + y).toFloat()
            val inChinRow = fy in chinTop..chinBottom
            var x = 0
            while (x < rw) {
                val fx = (rx0 + x).toFloat()
                val inInner = mouthOpenEnough && pointInPolygon(fx, fy, polyX, polyY)
                val inChin = !inInner && inChinRow && fx in chinL..chinR
                if (inInner || inChin) {
                    val c = pixels[y * rw + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    val dCr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b - skinCr
                    val isTongue = if (inInner) {
                        val teeth = lum > 0.85f * skinY && dCr < 4f
                        !teeth && lum > 0.32f * skinY && dCr > 10f
                    } else {
                        lum > 0.4f * skinY && dCr > 16f
                    }
                    if (inInner) { innerTotal++; if (isTongue) innerTongue++ }
                    else { chinTotal++; if (isTongue) chinTongue++ }
                    if (isTongue) {
                        sumX += fx; sumY += fy; nT++
                        if (debugPx != null) paintBlock(debugPx, rw, rh, x, y, step, 0xFF00FF66.toInt())
                    } else if (debugPx != null && inInner) {
                        paintBlock(debugPx, rw, rh, x, y, 1, 0xFF3366FF.toInt())
                    }
                }
                x += step
            }
            y += step
        }

        val innerRatio = if (innerTotal > 0) innerTongue.toFloat() / innerTotal else 0f
        val chinRaw = if (chinTotal > 0) chinTongue.toFloat() / chinTotal else 0f
        val chinRatio = ((chinRaw - 0.12f) / 0.88f).coerceAtLeast(0f) * 1.3f
        val feature = max(innerRatio, chinRatio).coerceIn(0f, 1f)

        var tx = 0f; var ty = 0f
        if (nT > 0) {
            tx = ((sumX / nT - cx) / (mouthW / 2f)).coerceIn(-1f, 1f)
            ty = ((sumY / nT - cy) / (mouthW / 2f)).coerceIn(-1f, 1f)
        }
        val dbg = debugPx?.let { Bitmap.createBitmap(it, rw, rh, Bitmap.Config.ARGB_8888) }
        return stabilize(feature, tx, ty, dbg)
    }

    private fun stabilize(feature: Float, tx: Float, ty: Float, dbg: Bitmap?): Result {
        hist[histN % hist.size] = feature
        histN++
        val count = min(histN, hist.size)
        val med = hist.copyOf(count).sortedArray()[count / 2]

        val span = (f1 - f0).coerceAtLeast(0.03f)
        val t = ((med - f0) / span).coerceIn(0f, 1f)
        if (!active) {
            onCount = if (t > 0.5f) onCount + 1 else 0
            if (onCount >= 3) { active = true; offCount = 0 }
        } else {
            offCount = if (t < 0.2f) offCount + 1 else 0
            if (offCount >= 4) { active = false; onCount = 0 }
        }
        val value = if (active) t * t * (3f - 2f * t) else 0f
        return Result(value, feature, if (active) tx else 0f, if (active) ty else 0f, dbg)
    }

    /** out[0] = luminancia, out[1] = Cr */
    private fun sampleSkin(bmp: Bitmap, cx: Float, cy: Float, out: FloatArray) {
        var sy = 0f; var scr = 0f; var cnt = 0
        val x0 = cx.roundToInt(); val y0 = cy.roundToInt()
        for (dy in -1..1) for (dx in -1..1) {
            val x = x0 + dx * 3; val y = y0 + dy * 3
            if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            sy += 0.299f * r + 0.587f * g + 0.114f * b
            scr += 128f + 0.5f * r - 0.4187f * g - 0.0813f * b
            cnt++
        }
        if (cnt == 0) { out[0] = 128f; out[1] = 140f; return }
        out[0] = sy / cnt; out[1] = scr / cnt
    }

    companion object {
        val INNER_LIPS = intArrayOf(
            78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
            324, 318, 402, 317, 14, 87, 178, 88, 95,
        )
        private const val SHRINK = 0.82f

        private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0; val dy = y1 - y0
            return sqrt(dx * dx + dy * dy)
        }

        private fun floor0(v: Float, limit: Int) = v.toInt().coerceIn(0, limit - 1)
        private fun ceilW(v: Float, limit: Int) = (v.toInt() + 1).coerceIn(1, limit)

        private fun polygonArea(xs: FloatArray, ys: FloatArray): Float {
            var a = 0f
            var j = xs.size - 1
            for (i in xs.indices) {
                a += (xs[j] + xs[i]) * (ys[j] - ys[i])
                j = i
            }
            return abs(a / 2f)
        }

        private fun pointInPolygon(x: Float, y: Float, xs: FloatArray, ys: FloatArray): Boolean {
            var inside = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > y) != (ys[j] > y) &&
                    x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]
                ) inside = !inside
                j = i
            }
            return inside
        }

        private fun paintBlock(px: IntArray, w: Int, h: Int, x: Int, y: Int, size: Int, color: Int) {
            for (yy in y until min(h, y + size)) for (xx in x until min(w, x + size)) px[yy * w + xx] = color
        }
    }
}
