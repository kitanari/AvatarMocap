package com.avatarmocap.tracking

import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detector de lengua EXPERIMENTAL (fase 1).
 *
 * MediaPipe devuelve tongueOut siempre ~0, así que lo calculamos nosotros con visión clásica:
 *
 *  - Región A: el interior de la boca (polígono de los labios internos). Sin lengua, el interior
 *    es oscuro (cavidad) o blanco (dientes). Con la lengua afuera se llena de tejido rosado/rojo.
 *  - Región B: una franja debajo del labio inferior (piel del mentón). Si la lengua sale mucho,
 *    tapa esa piel con un color bastante más rojo.
 *
 * Los colores se comparan contra la piel de tus propias mejillas (en YCbCr) para tolerar
 * distintas iluminaciones y tonos de piel. Después se calibra con dos poses:
 *   "boca abierta sin lengua" (f0) y "lengua afuera" (f1).
 *
 * Fase siguiente: reemplazar esto por un clasificador TFLite pequeño entrenado con recortes de boca.
 */
class TongueDetector {

    class Result(
        val value: Float,     // 0..1 ya calibrado
        val feature: Float,   // característica cruda (para calibración / depuración)
        val x: Float,         // -1..1, X+ = hacia la izquierda del usuario (derecha de la imagen)
        val y: Float,         // -1..1, Y+ = hacia abajo
        val debug: Bitmap?,   // recorte de la boca con píxeles de lengua en verde
    )

    @Volatile var f0 = 0.08f
    @Volatile var f1 = 0.45f

    /** 0 = ninguno, 1 = capturando f0 (boca abierta sin lengua), 2 = capturando f1 (lengua afuera). */
    private var calibTarget = 0
    private var calibLeft = 0
    private var calibSum = 0f
    private var calibN = 0
    var onCalibrated: ((target: Int, value: Float) -> Unit)? = null

    private var pixels = IntArray(0)
    private var frameCounter = 0

    fun startCalibration(target: Int, frames: Int = 45) {
        calibSum = 0f; calibN = 0
        calibLeft = frames
        calibTarget = target
    }

    val isCalibrating: Boolean get() = calibLeft > 0

    fun process(bmp: Bitmap, lm: List<NormalizedLandmark>, wantDebug: Boolean): Result {
        val w = bmp.width
        val h = bmp.height
        fun px(i: Int) = lm[i].x() * w
        fun py(i: Int) = lm[i].y() * h

        val mouthW = dist(px(61), py(61), px(291), py(291)).coerceAtLeast(4f)
        val cx = (px(61) + px(291)) / 2f
        val cy = (py(13) + py(14)) / 2f

        // --- Polígono interior de la boca
        val polyX = FloatArray(INNER_LIPS.size) { px(INNER_LIPS[it]) }
        val polyY = FloatArray(INNER_LIPS.size) { py(INNER_LIPS[it]) }
        val innerArea = polygonArea(polyX, polyY)
        val mouthOpenEnough = innerArea > mouthW * mouthW * 0.012f

        // --- Franja del mentón (debajo del labio inferior)
        val chinTop = py(17) + mouthW * 0.04f
        val chinBottom = chinTop + (py(152) - py(17)) * 0.45f
        val chinL = min(px(84), px(314))
        val chinR = max(px(84), px(314))

        // --- Rectángulo a leer (une ambas regiones)
        val rx0 = floor0(min(polyX.min(), chinL) - 2f, w)
        val ry0 = floor0(min(polyY.min(), chinTop) - 2f, h)
        val rx1 = ceilW(max(polyX.max(), chinR) + 2f, w)
        val ry1 = ceilW(max(polyY.max(), chinBottom) + 2f, h)
        val rw = rx1 - rx0
        val rh = ry1 - ry0
        if (rw < 4 || rh < 4) return finish(0f, 0f, 0f, null)
        if (pixels.size < rw * rh) pixels = IntArray(rw * rh)
        bmp.getPixels(pixels, 0, rw, rx0, ry0, rw, rh)

        // --- Referencia de piel (mejillas)
        val skin = FloatArray(3)
        sampleSkin(bmp, px(205), py(205), skin)
        val skin2 = FloatArray(3)
        sampleSkin(bmp, px(425), py(425), skin2)
        val skinY = ((skin[0] + skin2[0]) / 2f).coerceAtLeast(20f)
        val skinCr = (skin[2] + skin2[2]) / 2f

        val debugPx = if (wantDebug && (frameCounter++ % 3 == 0)) pixels.copyOf(rw * rh) else null
        val step = max(1, (max(rw, rh) / 56f).roundToInt())

        var innerTotal = 0; var innerTongue = 0
        var chinTotal = 0; var chinTongue = 0
        var sumX = 0f; var sumY = 0f; var nT = 0

        var y = 0
        while (y < rh) {
            var x = 0
            val fy = (ry0 + y).toFloat()
            while (x < rw) {
                val fx = (rx0 + x).toFloat()
                val inInner = mouthOpenEnough && pointInPolygon(fx, fy, polyX, polyY)
                val inChin = !inInner && fx in chinL..chinR && fy in chinTop..chinBottom
                if (inInner || inChin) {
                    val c = pixels[y * rw + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    val cr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b
                    val dCr = cr - skinCr
                    val isTongue = if (inInner) {
                        val teeth = lum > 0.85f * skinY && dCr < 4f
                        !teeth && lum > 0.30f * skinY && dCr > 8f
                    } else {
                        lum > 0.35f * skinY && dCr > 12f
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
        val chinRatio = if (chinTotal > 0) chinTongue.toFloat() / chinTotal else 0f
        val feature = max(innerRatio, chinRatio * 1.3f).coerceIn(0f, 1f)

        var tx = 0f; var ty = 0f
        if (nT > 0) {
            tx = ((sumX / nT - cx) / (mouthW / 2f)).coerceIn(-1f, 1f)
            ty = ((sumY / nT - cy) / (mouthW / 2f)).coerceIn(-1f, 1f)
        }
        val dbg = debugPx?.let { Bitmap.createBitmap(it, rw, rh, Bitmap.Config.ARGB_8888) }
        return finish(feature, tx, ty, dbg)
    }

    private fun finish(feature: Float, tx: Float, ty: Float, dbg: Bitmap?): Result {
        if (calibLeft > 0) {
            calibSum += feature; calibN++; calibLeft--
            if (calibLeft == 0 && calibN > 0) {
                val v = calibSum / calibN
                if (calibTarget == 1) f0 = v else if (calibTarget == 2) f1 = v
                onCalibrated?.invoke(calibTarget, v)
                calibTarget = 0
            }
        }
        val span = (f1 - f0).coerceAtLeast(0.03f)
        val t = ((feature - f0) / span).coerceIn(0f, 1f)
        val value = t * t * (3f - 2f * t) // smoothstep
        return Result(value, feature, if (value > 0.05f) tx else 0f, if (value > 0.05f) ty else 0f, dbg)
    }

    private fun sampleSkin(bmp: Bitmap, cx: Float, cy: Float, out: FloatArray) {
        var sy = 0f; var scb = 0f; var scr = 0f; var n = 0
        val x0 = cx.roundToInt(); val y0 = cy.roundToInt()
        for (dy in -2..2) for (dx in -2..2) {
            val x = x0 + dx * 2; val y = y0 + dy * 2
            if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            sy += 0.299f * r + 0.587f * g + 0.114f * b
            scb += 128f - 0.1687f * r - 0.3313f * g + 0.5f * b
            scr += 128f + 0.5f * r - 0.4187f * g - 0.0813f * b
            n++
        }
        if (n == 0) { out[0] = 128f; out[1] = 128f; out[2] = 140f; return }
        out[0] = sy / n; out[1] = scb / n; out[2] = scr / n
    }

    companion object {
        /** Contorno interno de los labios en la malla de 478 puntos de MediaPipe (en orden). */
        val INNER_LIPS = intArrayOf(
            78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
            324, 318, 402, 317, 14, 87, 178, 88, 95,
        )

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
