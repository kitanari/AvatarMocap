package com.avatarmocap.tracking

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detector de lengua (no existe un modelo de puntos para la lengua, así que se segmenta por color).
 *
 *  1. Se recorre una grilla sobre la boca, el labio inferior y el mentón.
 *  2. Cada celda se clasifica por color, relativo a la piel de tus mejillas. Si calibraste
 *     "Lengua afuera", se usa el color REAL de tu lengua (aprendido) en vez de umbrales fijos.
 *  3. Solo cuenta la mancha CONECTADA que sale desde el interior de la boca (el labio inferior
 *     sirve de puente pero no suma). Manchas rojas sueltas (labial, rubor, barba) no cuentan.
 *  4. Mediana de 5 cuadros + histéresis para que no se active sola.
 *
 * Devuelve además el contorno (envolvente convexa) y la punta para dibujarlos sobre la cámara.
 */
class TongueDetector {

    class Result(
        val value: Float,       // 0..1 calibrado y estabilizado
        val feature: Float,     // característica cruda (para calibrar)
        val x: Float,           // dirección -1..1 (X+ = derecha de la imagen)
        val y: Float,           // dirección -1..1 (Y+ = abajo)
        val colorCr: Float,     // color medio de la mancha (para aprender el color de la lengua)
        val colorCb: Float,
        val colorY: Float,
        val hull: FloatArray?,  // contorno x,y normalizados, o null
        val debug: Bitmap?,
    )

    @Volatile var f0 = 0.08f
    @Volatile var f1 = 0.45f

    // Color aprendido de la lengua (relativo a la piel). NaN = usar umbrales fijos.
    @Volatile private var protoCr = Float.NaN
    @Volatile private var protoCb = Float.NaN
    @Volatile private var protoY = Float.NaN

    fun setPrototype(cr: Float, cb: Float, y: Float) {
        protoCr = cr; protoCb = cb; protoY = y
    }

    private var pixels = IntArray(0)
    private var cls = ByteArray(0)    // 0 nada, 1 candidato
    private var region = ByteArray(0) // 0 fuera, 1 interior boca, 2 labio (puente), 3 mentón
    private var comp = BooleanArray(0)
    private var queue = IntArray(0)
    private var cellCr = FloatArray(0)
    private var cellCb = FloatArray(0)
    private var cellY = FloatArray(0)
    private var frameCounter = 0
    private val hist = FloatArray(5)
    private var histN = 0
    private var active = false
    private var onCount = 0
    private var offCount = 0
    private var skinY = 128f
    private var skinCr = 140f
    private var skinCb = 115f

    fun reset() {
        histN = 0; active = false; onCount = 0; offCount = 0
    }

    /** @param p puntos planos x,y,z normalizados; [n] cantidad. */
    fun process(bmp: Bitmap, p: FloatArray, n: Int, wantDebug: Boolean): Result {
        frameCounter++
        val w = bmp.width
        val h = bmp.height
        fun px(i: Int) = p[i * 3] * w
        fun py(i: Int) = p[i * 3 + 1] * h

        val mouthW = dist(px(61), py(61), px(291), py(291)).coerceAtLeast(4f)
        val cx = (px(61) + px(291)) / 2f
        val cy = (py(13) + py(14)) / 2f

        // Interior de la boca, achicado hacia el centro para no tomar el labio
        val k = INNER_LIPS.size
        val polyX = FloatArray(k) { px(INNER_LIPS[it]) }
        val polyY = FloatArray(k) { py(INNER_LIPS[it]) }
        val pcx = polyX.average().toFloat()
        val pcy = polyY.average().toFloat()
        for (i in 0 until k) {
            polyX[i] = pcx + (polyX[i] - pcx) * SHRINK
            polyY[i] = pcy + (polyY[i] - pcy) * SHRINK
        }
        val mouthOpen = polygonArea(polyX, polyY) > mouthW * mouthW * 0.015f

        // Labio inferior (puente) y mentón
        val lipTop = py(14)
        val chinTop = py(17) + mouthW * 0.08f
        val chinBottom = chinTop + (py(152) - py(17)) * 0.45f
        val sideL = min(px(84), px(314)) - mouthW * 0.05f
        val sideR = max(px(84), px(314)) + mouthW * 0.05f

        val rx0 = floor0(min(polyX.min(), sideL) - 2f, w)
        val ry0 = floor0(min(polyY.min(), lipTop) - 2f, h)
        val rx1 = ceilW(max(polyX.max(), sideR) + 2f, w)
        val ry1 = ceilW(chinBottom + 2f, h)
        val rw = rx1 - rx0
        val rh = ry1 - ry0
        if (rw < 4 || rh < 4 || !mouthOpen) {
            return stabilize(0f, 0f, 0f, Float.NaN, Float.NaN, Float.NaN, null, null)
        }
        if (pixels.size < rw * rh) pixels = IntArray(rw * rh)
        bmp.getPixels(pixels, 0, rw, rx0, ry0, rw, rh)

        if (frameCounter % 8 == 1) updateSkin(bmp, px(205), py(205), px(425), py(425))

        // ---- Grilla
        val step = max(1, (max(rw, rh) / 48f).roundToInt())
        val gw = (rw + step - 1) / step
        val gh = (rh + step - 1) / step
        val cells = gw * gh
        if (cls.size < cells) {
            cls = ByteArray(cells); region = ByteArray(cells); comp = BooleanArray(cells); queue = IntArray(cells)
            cellCr = FloatArray(cells); cellCb = FloatArray(cells); cellY = FloatArray(cells)
        }
        val usePrototype = !protoCr.isNaN()
        var innerTotal = 0
        var chinTotal = 0
        for (gy in 0 until gh) {
            val y = gy * step
            val fy = (ry0 + y).toFloat()
            for (gx in 0 until gw) {
                val x = gx * step
                val fx = (rx0 + x).toFloat()
                val ci = gy * gw + gx
                comp[ci] = false
                val reg: Int = when {
                    pointInPolygon(fx, fy, polyX, polyY) -> 1
                    fx in sideL..sideR && fy >= lipTop && fy < chinTop -> 2
                    fx in sideL..sideR && fy in chinTop..chinBottom -> 3
                    else -> 0
                }
                region[ci] = reg.toByte()
                if (reg == 0) { cls[ci] = 0; continue }
                if (reg == 1) innerTotal++ else if (reg == 3) chinTotal++
                val c = pixels[min(y, rh - 1) * rw + min(x, rw - 1)]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val dCr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b - skinCr
                val dCb = 128f - 0.1687f * r - 0.3313f * g + 0.5f * b - skinCb
                val relY = lum / skinY
                cellCr[ci] = dCr; cellCb[ci] = dCb; cellY[ci] = relY
                val teeth = relY > 0.85f && dCr < 4f
                val isTongue = when {
                    teeth || relY < 0.28f -> false
                    usePrototype -> {
                        val sCr = max(7f, abs(protoCr) * 0.5f)
                        val a = (dCr - protoCr) / sCr
                        val bb = (dCb - protoCb) / 9f
                        val cc = (relY - protoY) / 0.3f
                        a * a + bb * bb + cc * cc < 1f && dCr > 3f
                    }
                    reg == 3 -> relY > 0.4f && dCr > 16f
                    else -> dCr > 10f
                }
                cls[ci] = if (isTongue) 1 else 0
            }
        }

        // ---- Mancha conectada que nace dentro de la boca
        var head = 0; var tail = 0
        for (ci in 0 until cells) if (cls[ci].toInt() == 1 && region[ci].toInt() == 1) { comp[ci] = true; queue[tail++] = ci }
        while (head < tail) {
            val ci = queue[head++]
            val gx = ci % gw; val gy = ci / gw
            for (dir in 0..3) {
                val nx = gx + DX[dir]; val ny = gy + DY[dir]
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                val ni = ny * gw + nx
                if (!comp[ni] && cls[ni].toInt() == 1) { comp[ni] = true; queue[tail++] = ni }
            }
        }

        var innerT = 0; var chinT = 0
        var sx = 0f; var sy = 0f; var nT = 0
        var sCr = 0f; var sCb = 0f; var sY = 0f
        var tipX = cx; var tipY = cy; var tipD = -1f
        val hullPts = ArrayList<Float>()
        for (ci in 0 until cells) {
            if (!comp[ci]) continue
            val reg = region[ci].toInt()
            if (reg == 1) innerT++ else if (reg == 3) chinT++
            val fx = rx0 + (ci % gw) * step + step / 2f
            val fy = ry0 + (ci / gw) * step + step / 2f
            sx += fx; sy += fy; nT++
            sCr += cellCr[ci]; sCb += cellCb[ci]; sY += cellY[ci]
            val dd = (fx - cx) * (fx - cx) + (fy - cy) * (fy - cy)
            if (dd > tipD) { tipD = dd; tipX = fx; tipY = fy }
            hullPts.add(fx / w); hullPts.add(fy / h)
        }

        val innerRatio = if (innerTotal > 0) innerT.toFloat() / innerTotal else 0f
        val chinRaw = if (chinTotal > 0) chinT.toFloat() / chinTotal else 0f
        val chinRatio = ((chinRaw - 0.08f) / 0.92f).coerceAtLeast(0f) * 1.3f
        val feature = max(innerRatio, chinRatio).coerceIn(0f, 1f)

        var tx = 0f; var ty = 0f
        if (nT > 0) {
            // Dirección: hacia la punta (lo más alejado del centro de la boca)
            tx = ((tipX - cx) / (mouthW / 2f)).coerceIn(-1f, 1f)
            ty = ((tipY - cy) / (mouthW / 2f)).coerceIn(-1f, 1f)
        }
        val hull = if (nT >= 4) convexHull(hullPts) else null

        val dbg = if (wantDebug && frameCounter % 3 == 0) {
            val copy = pixels.copyOf(rw * rh)
            for (ci in 0 until cells) {
                val color = when {
                    comp[ci] -> 0xFF00FF66.toInt()
                    cls[ci].toInt() == 1 -> 0xFFFFAA00.toInt() // candidato suelto (no cuenta)
                    region[ci].toInt() == 1 -> 0xFF3366FF.toInt()
                    else -> 0
                }
                if (color != 0) paintBlock(copy, rw, rh, (ci % gw) * step, (ci / gw) * step, if (comp[ci]) step else 1, color)
            }
            Bitmap.createBitmap(copy, rw, rh, Bitmap.Config.ARGB_8888)
        } else null

        return if (nT > 0) stabilize(feature, tx, ty, sCr / nT, sCb / nT, sY / nT, hull, dbg)
        else stabilize(feature, 0f, 0f, Float.NaN, Float.NaN, Float.NaN, null, dbg)
    }

    private fun stabilize(
        feature: Float, tx: Float, ty: Float, cr: Float, cb: Float, cy: Float, hull: FloatArray?, dbg: Bitmap?,
    ): Result {
        hist[histN % hist.size] = feature
        histN++
        val count = min(histN, hist.size)
        val med = hist.copyOf(count).sortedArray()[count / 2]
        val span = (f1 - f0).coerceAtLeast(0.03f)
        val t = ((med - f0) / span).coerceIn(0f, 1f)
        if (!active) {
            onCount = if (t > 0.45f) onCount + 1 else 0
            if (onCount >= 3) { active = true; offCount = 0 }
        } else {
            offCount = if (t < 0.2f) offCount + 1 else 0
            if (offCount >= 4) { active = false; onCount = 0 }
        }
        val value = if (active) t * t * (3f - 2f * t) else 0f
        return Result(
            value, feature, if (active) tx else 0f, if (active) ty else 0f,
            cr, cb, cy, if (active) hull else null, dbg,
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
        private const val SHRINK = 0.82f
        private val DX = intArrayOf(1, -1, 0, 0)
        private val DY = intArrayOf(0, 0, 1, -1)

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
