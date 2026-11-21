package com.avatarmocap.tracking

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detector de lengua por color (no existe un modelo de puntos para la lengua).
 *
 * Zona analizada: desde el centro de la boca hacia abajo hasta el mentón, un poco más ancha
 * que la boca. Se analiza SIEMPRE, aunque MediaPipe "cierre" los labios alrededor de la lengua.
 *
 * Cada celda de la grilla se compara contra tres colores (relativos a la piel de tus mejillas):
 *   - piel (mejillas), - labios (aprendido en "Neutral"), - lengua (aprendido en "Lengua afuera").
 * Sin calibrar se usan umbrales genéricos.
 *
 * Solo cuenta la lengua CONECTADA a la boca (interior o labio inferior); manchas sueltas no.
 * Mediana de 5 cuadros + histéresis contra activaciones solas.
 */
class TongueDetector {

    class Result(
        val value: Float,       // 0..1 calibrado y estabilizado
        val feature: Float,     // área de lengua (para calibrar)
        val x: Float,           // dirección -1..1 (X+ = derecha de la imagen)
        val y: Float,           // dirección -1..1 (Y+ = abajo)
        val tongueCr: Float,    // color medio de lo "no piel" bajo la boca (para aprender la lengua)
        val tongueCb: Float,
        val tongueY: Float,
        val lipCr: Float,       // color medio del labio inferior (para aprender los labios)
        val lipCb: Float,
        val lipY: Float,
        val hull: FloatArray?,  // contorno x,y normalizados, o null
        val debug: Bitmap?,
    )

    @Volatile var f0 = 0.05f
    @Volatile var f1 = 0.35f
    private val tProto = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    private val lProto = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)

    fun setPrototypes(tongue: FloatArray, lip: FloatArray) {
        for (i in 0..2) { tProto[i] = tongue[i]; lProto[i] = lip[i] }
    }

    private var pixels = IntArray(0)
    private var type = ByteArray(0)   // 0 nada, 1 lengua, 2 puente (labio)
    private var zone = ByteArray(0)   // 0 fuera, 1 interior, 2 banda del labio, 3 debajo
    private var comp = BooleanArray(0)
    private var queue = IntArray(0)
    private var frameCounter = 0
    private val hist = FloatArray(5)
    private var histN = 0
    private var active = false
    private var onCount = 0
    private var offCount = 0
    /** Brillo de la piel (mejillas) medido en el último cuadro. */
    val skinLuma: Float get() = skinY
    private var skinY = 128f
    private var skinCr = 140f
    private var skinCb = 115f

    fun reset() {
        histN = 0; active = false; onCount = 0; offCount = 0
    }

    fun process(bmp: Bitmap, p: FloatArray, n: Int, wantDebug: Boolean): Result {
        frameCounter++
        val w = bmp.width
        val h = bmp.height
        fun px(i: Int) = p[i * 3] * w
        fun py(i: Int) = p[i * 3 + 1] * h

        val mouthW = dist(px(61), py(61), px(291), py(291)).coerceAtLeast(4f)
        val cx = (px(61) + px(291)) / 2f
        val cy = (py(13) + py(14)) / 2f

        // Interior de la boca (achicado para no tomar el labio)
        val k = INNER_LIPS.size
        val polyX = FloatArray(k) { px(INNER_LIPS[it]) }
        val polyY = FloatArray(k) { py(INNER_LIPS[it]) }
        val pcx = polyX.average().toFloat()
        val pcy = polyY.average().toFloat()
        for (i in 0 until k) {
            polyX[i] = pcx + (polyX[i] - pcx) * SHRINK
            polyY[i] = pcy + (polyY[i] - pcy) * SHRINK
        }
        val hasInner = polygonArea(polyX, polyY) > mouthW * mouthW * 0.004f

        val lipTop = py(14)
        val lipBottom = max(py(17), lipTop + mouthW * 0.08f) + mouthW * 0.04f
        val bottom = min(lipTop + mouthW * 1.1f, py(152))
        val left = min(px(61), px(291)) - mouthW * 0.12f
        val right = max(px(61), px(291)) + mouthW * 0.12f

        if (frameCounter % 4 == 1) updateSkin(bmp, px(205), py(205), px(425), py(425))

        val rx0 = floor0(left, w)
        val ry0 = floor0(min(polyY.min(), cy) - 2f, h)
        val rx1 = ceilW(right, w)
        val ry1 = ceilW(bottom, h)
        val rw = rx1 - rx0
        val rh = ry1 - ry0
        if (rw < 4 || rh < 4) return stabilize(0f, 0f, 0f, NAN3, NAN3, null, null)
        if (pixels.size < rw * rh) pixels = IntArray(rw * rh)
        bmp.getPixels(pixels, 0, rw, rx0, ry0, rw, rh)

        val step = max(1, (max(rw, rh) / 48f).roundToInt())
        val gw = (rw + step - 1) / step
        val gh = (rh + step - 1) / step
        val cells = gw * gh
        if (type.size < cells) {
            type = ByteArray(cells); zone = ByteArray(cells); comp = BooleanArray(cells); queue = IntArray(cells)
        }

        val hasT = !tProto[0].isNaN()
        val hasL = !lProto[0].isNaN()
        val sCr = if (hasT) max(7f, abs(tProto[0]) * 0.5f) else 8f
        var nsCr = 0f; var nsCb = 0f; var nsY = 0f; var nsN = 0      // "no piel" bajo la boca
        var lCr = 0f; var lCb = 0f; var lY = 0f; var lN = 0          // labio inferior

        for (gy in 0 until gh) {
            val yy = gy * step
            val fy = (ry0 + yy).toFloat()
            for (gx in 0 until gw) {
                val xx = gx * step
                val fx = (rx0 + xx).toFloat()
                val ci = gy * gw + gx
                comp[ci] = false
                type[ci] = 0
                val z = when {
                    hasInner && pointInPolygon(fx, fy, polyX, polyY) -> 1
                    fy < cy -> 0
                    fy < lipBottom -> 2
                    else -> 3
                }
                zone[ci] = z.toByte()
                if (z == 0) continue
                val c = pixels[min(yy, rh - 1) * rw + min(xx, rw - 1)]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val dCr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b - skinCr
                val dCb = 128f - 0.1687f * r - 0.3313f * g + 0.5f * b - skinCb
                val relY = lum / skinY
                if (relY < 0.28f) continue                       // cavidad oscura
                if (relY > 0.85f && dCr < 4f) continue           // dientes

                // Estadísticas para aprender colores
                if (z == 2 && dCr > 3f) { lCr += dCr; lCb += dCb; lY += relY; lN++ }
                if ((z == 3 || z == 1) && dCr > 6f) { nsCr += dCr; nsCb += dCb; nsY += relY; nsN++ }

                val isTongue: Boolean
                val isLip: Boolean
                if (hasT) {
                    val dT = sq((dCr - tProto[0]) / sCr) + sq((dCb - tProto[1]) / 9f) + sq((relY - tProto[2]) / 0.3f)
                    val dS = sq(dCr / sCr) + sq(dCb / 9f) + sq((relY - 1f) / 0.3f)
                    val dL = if (hasL) sq((dCr - lProto[0]) / sCr) + sq((dCb - lProto[1]) / 9f) + sq((relY - lProto[2]) / 0.3f)
                    else Float.MAX_VALUE
                    isTongue = dT < 1.6f && dT < dS && (z == 1 || dT < dL * 0.8f || (!hasL && z == 3))
                    isLip = !isTongue && z == 2 && (if (hasL) dL < 2f else dCr > 4f)
                } else {
                    isTongue = when (z) {
                        1 -> dCr > 9f
                        3 -> dCr > 13f && relY > 0.4f
                        else -> false // sin calibrar no se puede separar labio de lengua
                    }
                    isLip = z == 2 && dCr > 4f
                }
                type[ci] = when { isTongue -> 1; isLip -> 2; else -> 0 }
            }
        }

        // ---- Mancha conectada a la boca: semillas en el interior (lengua) o en el labio inferior
        var head = 0; var tail = 0
        for (ci in 0 until cells) {
            val t = type[ci].toInt()
            val z = zone[ci].toInt()
            if ((t == 1 && z == 1) || (t != 0 && z == 2)) { comp[ci] = true; queue[tail++] = ci }
        }
        while (head < tail) {
            val ci = queue[head++]
            val gx = ci % gw; val gy = ci / gw
            for (dir in 0..3) {
                val nx = gx + DX[dir]; val ny = gy + DY[dir]
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                val ni = ny * gw + nx
                if (!comp[ni] && type[ni].toInt() != 0) { comp[ni] = true; queue[tail++] = ni }
            }
        }

        var nT = 0
        var tipX = cx; var tipY = cy; var tipD = -1f
        val hullPts = ArrayList<Float>()
        for (ci in 0 until cells) {
            if (!comp[ci] || type[ci].toInt() != 1) continue
            nT++
            val fx = rx0 + (ci % gw) * step + step / 2f
            val fy = ry0 + (ci / gw) * step + step / 2f
            val dd = (fx - cx) * (fx - cx) + (fy - cy) * (fy - cy)
            if (dd > tipD) { tipD = dd; tipX = fx; tipY = fy }
            hullPts.add(fx / w); hullPts.add(fy / h)
        }
        // Área de lengua relativa al tamaño de la boca (una lengua bien afuera ≈ 0.35·ancho²)
        val area = nT.toFloat() * step * step
        val feature = (area / (mouthW * mouthW * 0.35f)).coerceIn(0f, 1.5f)

        var tx = 0f; var ty = 0f
        if (nT > 0) {
            tx = ((tipX - cx) / (mouthW / 2f)).coerceIn(-1f, 1f)
            ty = ((tipY - cy) / (mouthW / 2f)).coerceIn(-1f, 1f)
        }
        val hull = if (nT >= 4) convexHull(hullPts) else null

        val dbg = if (wantDebug && frameCounter % 3 == 0) {
            val copy = pixels.copyOf(rw * rh)
            for (ci in 0 until cells) {
                val color = when {
                    comp[ci] && type[ci].toInt() == 1 -> 0xFF00FF66.toInt()
                    type[ci].toInt() == 1 -> 0xFFFFAA00.toInt()
                    type[ci].toInt() == 2 -> 0xFFFF33CC.toInt()
                    zone[ci].toInt() == 1 -> 0xFF3366FF.toInt()
                    else -> 0
                }
                if (color != 0) paintBlock(copy, rw, rh, (ci % gw) * step, (ci / gw) * step, max(1, step / 2), color)
            }
            Bitmap.createBitmap(copy, rw, rh, Bitmap.Config.ARGB_8888)
        } else null

        val tc = if (nsN >= 3) floatArrayOf(nsCr / nsN, nsCb / nsN, nsY / nsN) else NAN3
        val lc = if (lN >= 3) floatArrayOf(lCr / lN, lCb / lN, lY / lN) else NAN3
        return stabilize(feature, tx, ty, tc, lc, hull, dbg)
    }

    private fun stabilize(
        feature: Float, tx: Float, ty: Float, tc: FloatArray, lc: FloatArray, hull: FloatArray?, dbg: Bitmap?,
    ): Result {
        hist[histN % hist.size] = feature
        histN++
        val count = min(histN, hist.size)
        val med = hist.copyOf(count).sortedArray()[count / 2]
        val span = (f1 - f0).coerceAtLeast(0.03f)
        val t = ((med - f0) / span).coerceIn(0f, 1f)
        if (!active) {
            onCount = if (t > 0.35f) onCount + 1 else 0
            if (onCount >= 2) { active = true; offCount = 0 }
        } else {
            offCount = if (t < 0.15f) offCount + 1 else 0
            if (offCount >= 4) { active = false; onCount = 0 }
        }
        val value = if (active) t * t * (3f - 2f * t) else 0f
        return Result(
            value, feature, if (active) tx else 0f, if (active) ty else 0f,
            tc[0], tc[1], tc[2], lc[0], lc[1], lc[2], if (active) hull else null, dbg,
        )
    }

    private fun updateSkin(bmp: Bitmap, x1: Float, y1: Float, x2: Float, y2: Float) {
        var sy = 0f; var scr = 0f; var scb = 0f; var cnt = 0
        for ((cx, cy) in arrayOf(x1 to y1, x2 to y2)) {
            val x0 = cx.roundToInt(); val y0 = cy.roundToInt()
            for (dy in -1..1) for (dx in -1..1) {
                val x = x0 + dx * 3; val y = y0 + dy * 3
                if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                sy += 0.299f * r + 0.587f * g + 0.114f * b
                scr += 128f + 0.5f * r - 0.4187f * g - 0.0813f * b
                scb += 128f - 0.1687f * r - 0.3313f * g + 0.5f * b
                cnt++
            }
        }
        if (cnt == 0) return
        skinY = (sy / cnt).coerceAtLeast(20f); skinCr = scr / cnt; skinCb = scb / cnt
    }

    companion object {
        val INNER_LIPS = intArrayOf(
            78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308,
            324, 318, 402, 317, 14, 87, 178, 88, 95,
        )
        private val NAN3 = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
        private const val SHRINK = 0.82f
        private val DX = intArrayOf(1, -1, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1)

        private fun sq(v: Float) = v * v

        private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0; val dy = y1 - y0
            return sqrt(dx * dx + dy * dy)
        }

        private fun floor0(v: Float, limit: Int) = v.toInt().coerceIn(0, limit - 1)
        private fun ceilW(v: Float, limit: Int) = (v.toInt() + 1).coerceIn(1, limit)

        private fun polygonArea(xs: FloatArray, ys: FloatArray): Float {
            var a = 0f
            var j = xs.size - 1
            for (i in xs.indices) { a += (xs[j] + xs[i]) * (ys[j] - ys[i]); j = i }
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

        /** Envolvente convexa (monotone chain) de puntos x,y intercalados. */
        fun convexHull(flat: List<Float>): FloatArray {
            val n = flat.size / 2
            val idx = (0 until n).sortedWith(compareBy({ flat[it * 2] }, { flat[it * 2 + 1] }))
            fun cross(o: Int, a: Int, b: Int) =
                (flat[a * 2] - flat[o * 2]) * (flat[b * 2 + 1] - flat[o * 2 + 1]) -
                    (flat[a * 2 + 1] - flat[o * 2 + 1]) * (flat[b * 2] - flat[o * 2])
            val hull = ArrayList<Int>()
            for (i in idx) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], i) <= 0) hull.removeAt(hull.size - 1)
                hull.add(i)
            }
            val lower = hull.size + 1
            for (i in idx.reversed()) {
                while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], i) <= 0) hull.removeAt(hull.size - 1)
                hull.add(i)
            }
            if (hull.size > 1) hull.removeAt(hull.size - 1)
            val out = FloatArray(hull.size * 2)
            for ((k, i) in hull.withIndex()) { out[k * 2] = flat[i * 2]; out[k * 2 + 1] = flat[i * 2 + 1] }
            return out
        }
    }
}
