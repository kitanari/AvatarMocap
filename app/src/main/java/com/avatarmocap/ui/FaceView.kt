package com.avatarmocap.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import com.avatarmocap.tracking.FaceGeometry
import com.avatarmocap.tracking.PreviewFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Qué dibujar sobre la cara. */
class FaceViewOptions(
    val mesh: Boolean,
    val points: Boolean,
    val eyes: Boolean,
    val mouth: Boolean,
    val camera: Boolean,
    val mirror: Boolean,
    val hands: Boolean = true,
)

/**
 * Vista de la cara:
 *  - sin cámara: una cabeza 3D violeta (la malla real de tu cara, sombreada) centrada en el cuadro,
 *    con ojos (iris que siguen tu mirada) y boca;
 *  - con cámara: la imagen analizada con la malla encima, exactamente alineada.
 */
@Composable
fun FaceView(frame: PreviewFrame?, o: FaceViewOptions, modifier: Modifier = Modifier) {
    val buffers = remember { MeshBuffers() }
    Box(modifier.graphicsLayer(scaleX = if (o.mirror) -1f else 1f)) {
        if (frame == null) return@Box
        if (o.camera) {
            Image(frame.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Canvas(Modifier.fillMaxSize()) {
            val pts = frame.landmarks ?: return@Canvas
            val n = pts.size / 3
            if (n < 468) return@Canvas
            val iw = frame.bitmap.width.toFloat()
            val ih = frame.bitmap.height.toFloat()

            // Transformación: con cámara = recorte igual a ContentScale.Crop; sin cámara = cara centrada
            val sx: Float; val ox: Float; val oy: Float
            if (o.camera) {
                sx = max(size.width / iw, size.height / ih)
                ox = (size.width - iw * sx) / 2f
                oy = (size.height - ih * sx) / 2f
            } else {
                var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (i in 0 until 468) {
                    val x = pts[i * 3] * iw; val y = pts[i * 3 + 1] * ih
                    x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                }
                // Si hay manos, el encuadre las incluye
                if (o.hands) frame.hands?.let { hf ->
                    for (hd in listOf(hf.left, hf.right)) if (hd.found) for (i in 0 until 21) {
                        val x = hd.image[i * 2] * iw; val y = hd.image[i * 2 + 1] * ih
                        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                    }
                }
                val fw = (x1 - x0).coerceAtLeast(1f); val fh = (y1 - y0).coerceAtLeast(1f)
                sx = min(size.width * 0.86f / fw, size.height * 0.86f / fh)
                ox = size.width / 2f - (x0 + fw / 2f) * sx
                oy = size.height / 2f - (y0 + fh / 2f) * sx
            }
            fun px(i: Int) = ox + pts[i * 3] * iw * sx
            fun py(i: Int) = oy + pts[i * 3 + 1] * ih * sx

            if (!o.camera) drawShadedHead(pts, n, iw, ih, ::px, ::py, buffers)
            if (o.mesh) drawWire(n, ::px, ::py, if (o.camera) Color(0x994DD0E1) else Color(0x66E4D9FF))
            if (o.points) {
                val face = FaceGeometry.OVERLAY_POINTS.filter { it < n }.map { Offset(px(it), py(it)) }
                drawPoints(face, PointMode.Points, Color(0xFFB9A2FF), strokeWidth = 6f, cap = StrokeCap.Round)
            }
            if (o.mouth) drawMouth(::px, ::py, frame.tongueHull, ox, oy, iw * sx, ih * sx, !o.camera)
            if (o.eyes) drawEyes(pts, n, ::px, ::py, !o.camera)
            if (o.hands) frame.hands?.let { hf ->
                drawHand(hf.left, ox, oy, iw * sx, ih * sx, Color(0xFFB9A2FF))
                drawHand(hf.right, ox, oy, iw * sx, ih * sx, Color(0xFF8FD4FF))
            }
        }
    }
}

/** Arreglos reutilizados para dibujar la malla sombreada (sin basura por cuadro). */
private class MeshBuffers {
    var verts = FloatArray(0)
    var colors = IntArray(0)
    var nx = FloatArray(0)
    var ny = FloatArray(0)
    var nz = FloatArray(0)
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
}

/** Malla de tu cara rellena y sombreada (luz desde arriba-izquierda), en violeta. */
private fun DrawScope.drawShadedHead(
    pts: FloatArray, n: Int, iw: Float, ih: Float,
    px: (Int) -> Float, py: (Int) -> Float, b: MeshBuffers,
) {
    val tri = FaceGeometry.TRIANGLES
    if (b.verts.size != n * 2) {
        b.verts = FloatArray(n * 2); b.colors = IntArray(n * 2)
        b.nx = FloatArray(n); b.ny = FloatArray(n); b.nz = FloatArray(n)
    }
    b.nx.fill(0f); b.ny.fill(0f); b.nz.fill(0f)
    var t = 0
    while (t + 2 < tri.size) {
        val a = tri[t].toInt(); val c1 = tri[t + 1].toInt(); val c2 = tri[t + 2].toInt()
        t += 3
        if (a >= n || c1 >= n || c2 >= n) continue
        val ax = pts[a * 3] * iw; val ay = pts[a * 3 + 1] * ih; val az = pts[a * 3 + 2] * iw
        val ux = pts[c1 * 3] * iw - ax; val uy = pts[c1 * 3 + 1] * ih - ay; val uz = pts[c1 * 3 + 2] * iw - az
        val vx = pts[c2 * 3] * iw - ax; val vy = pts[c2 * 3 + 1] * ih - ay; val vz = pts[c2 * 3 + 2] * iw - az
        var cx = uy * vz - uz * vy; var cy = uz * vx - ux * vz; var cz = ux * vy - uy * vx
        if (cz > 0) { cx = -cx; cy = -cy; cz = -cz } // orientar hacia la cámara
        for (v in intArrayOf(a, c1, c2)) { b.nx[v] += cx; b.ny[v] += cy; b.nz[v] += cz }
    }
    // Luz: desde la cámara, un poco arriba a la izquierda
    val lx = -0.35f; val ly = -0.45f; val lz = -0.82f
    for (i in 0 until n) {
        val len = sqrt(b.nx[i] * b.nx[i] + b.ny[i] * b.ny[i] + b.nz[i] * b.nz[i]).coerceAtLeast(1e-6f)
        val d = abs((b.nx[i] * lx + b.ny[i] * ly + b.nz[i] * lz) / len)
        val l = (0.18f + 0.82f * d).coerceIn(0f, 1f)
        val r = (52 + l * (210 - 52)).toInt()
        val g = (34 + l * (190 - 34)).toInt()
        val bl = (92 + l * (255 - 92)).toInt()
        b.colors[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        b.verts[i * 2] = px(i); b.verts[i * 2 + 1] = py(i)
    }
    if (Build.VERSION.SDK_INT >= 29) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES, n * 2, b.verts, 0, null, 0,
                b.colors, 0, tri, 0, tri.size, b.paint,
            )
        }
    }
}

private fun DrawScope.drawWire(n: Int, px: (Int) -> Float, py: (Int) -> Float, color: Color) {
    val edges = FaceGeometry.MESH_EDGES
    val path = Path()
    var e = 0
    while (e + 1 < edges.size) {
        val a = edges[e]; val c = edges[e + 1]
        if (a < n && c < n) { path.moveTo(px(a), py(a)); path.lineTo(px(c), py(c)) }
        e += 2
    }
    drawPath(path, color, style = Stroke(width = 1f))
}

private fun polygon(idx: IntArray, px: (Int) -> Float, py: (Int) -> Float): Path {
    val p = Path()
    p.moveTo(px(idx[0]), py(idx[0]))
    for (k in 1 until idx.size) p.lineTo(px(idx[k]), py(idx[k]))
    p.close()
    return p
}

/** Ojos: hueco oscuro + iris que sigue tu mirada + pupila + brillo. */
private fun DrawScope.drawEyes(pts: FloatArray, n: Int, px: (Int) -> Float, py: (Int) -> Float, stylized: Boolean) {
    for ((contour, iris) in listOf(FaceGeometry.EYE_R to FaceGeometry.IRIS_R, FaceGeometry.EYE_L to FaceGeometry.IRIS_L)) {
        val path = polygon(contour, px, py)
        if (stylized) drawPath(path, Color(0xFF140A26))
        drawPath(path, Color(0xFFE9DFFF), style = Stroke(width = 2f))
        if (n < 478) continue
        val cx = px(iris[0]); val cy = py(iris[0])
        var r = 0f
        for (k in 1..4) { val dx = px(iris[k]) - cx; val dy = py(iris[k]) - cy; r += sqrt(dx * dx + dy * dy) }
        r = (r / 4f).coerceAtLeast(2f)
        // El iris se recorta al contorno del ojo (si el ojo se cierra, el iris desaparece)
        clipPathSafe(path) {
            drawCircle(Color(0xFF8D6BFF), radius = r, center = Offset(cx, cy))
            drawCircle(Color(0xFFC9B8FF), radius = r, center = Offset(cx, cy), style = Stroke(width = 1.5f))
            drawCircle(Color(0xFF0B0616), radius = r * 0.45f, center = Offset(cx, cy))
            drawCircle(Color.White, radius = r * 0.22f, center = Offset(cx - r * 0.35f, cy - r * 0.35f))
        }
    }
}

/** Boca: labios + interior oscuro + lengua (verde si se detecta). */
private fun DrawScope.drawMouth(
    px: (Int) -> Float, py: (Int) -> Float, hull: FloatArray?,
    ox: Float, oy: Float, sw: Float, sh: Float, stylized: Boolean,
) {
    val outer = polygon(FaceGeometry.LIPS_OUTER, px, py)
    val inner = polygon(FaceGeometry.LIPS_INNER, px, py)
    if (stylized) {
        drawPath(outer, Color(0xFFC0628F))
        drawPath(inner, Color(0xFF1A0B1E))
    }
    drawPath(outer, Color(0xFFFF8FC0), style = Stroke(width = 2f))
    drawPath(inner, Color(0xFFFF8FC0), style = Stroke(width = 1.5f))
    if (hull != null && hull.size >= 6) {
        val p = Path()
        p.moveTo(ox + hull[0] * sw, oy + hull[1] * sh)
        var i = 2
        while (i + 1 < hull.size) { p.lineTo(ox + hull[i] * sw, oy + hull[i + 1] * sh); i += 2 }
        p.close()
        drawPath(p, if (stylized) Color(0xFFFF6F91) else Color(0x5500FF66))
        drawPath(p, if (stylized) Color(0xFFFFB3C6) else Color(0xFF00FF66), style = Stroke(width = 2.5f))
    }
}

private val HAND_LINKS = intArrayOf(
    0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12,
    9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 0, 17, 17, 18, 18, 19, 19, 20,
)

/** Mano: huesos + articulaciones; la palma rellena suave. */
private fun DrawScope.drawHand(hand: com.avatarmocap.tracking.HandData, ox: Float, oy: Float, sw: Float, sh: Float, color: Color) {
    if (!hand.found) return
    val im = hand.image
    fun hx(i: Int) = ox + im[i * 2] * sw
    fun hy(i: Int) = oy + im[i * 2 + 1] * sh
    val palm = Path()
    palm.moveTo(hx(0), hy(0)); palm.lineTo(hx(5), hy(5)); palm.lineTo(hx(9), hy(9))
    palm.lineTo(hx(13), hy(13)); palm.lineTo(hx(17), hy(17)); palm.close()
    drawPath(palm, color.copy(alpha = 0.25f))
    var k = 0
    while (k + 1 < HAND_LINKS.size) {
        val a = HAND_LINKS[k]; val b = HAND_LINKS[k + 1]
        drawLine(color, Offset(hx(a), hy(a)), Offset(hx(b), hy(b)), strokeWidth = 4f, cap = StrokeCap.Round)
        k += 2
    }
    for (i in 0 until 21) drawCircle(Color.White, radius = if (i % 4 == 0) 5f else 3.5f, center = Offset(hx(i), hy(i)))
}

private inline fun DrawScope.clipPathSafe(path: Path, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipPath(path)
    block()
    drawContext.canvas.restore()
}
