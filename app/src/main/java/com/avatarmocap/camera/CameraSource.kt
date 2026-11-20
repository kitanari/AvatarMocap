package com.avatarmocap.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Process
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cámara frontal con CameraX. Entrega bitmaps RGBA en posición vertical (sin espejar).
 *
 * Optimizado para gama media:
 *  - Resolución de análisis configurable (MediaPipe recorta la cara a 256 px igual).
 *  - Reutiliza los mismos dos bitmaps en cada cuadro: sin basura para el GC.
 *  - Hilo de análisis con prioridad alta.
 *  - La vista previa se puede apagar (ahorra GPU y batería).
 */
class CameraSource(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            r.run()
        }, "camera-analysis")
    }
    private var provider: ProcessCameraProvider? = null

    // Bitmaps reutilizados (solo se tocan desde el hilo de análisis)
    private var src: Bitmap? = null
    private var out: Bitmap? = null
    private val matrix = Matrix()
    private val rect = RectF()

    fun start(
        owner: LifecycleOwner,
        previewView: PreviewView?,
        resolution: Int,
        onFrame: (Bitmap) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p

            val size = when (resolution) {
                0 -> Size(320, 240)
                2 -> Size(640, 480)
                else -> Size(480, 360)
            }
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    onFrame(toUpright(proxy))
                } finally {
                    proxy.close()
                }
            }

            val useCases = mutableListOf<UseCase>(analysis)
            if (previewView != null) {
                val preview = Preview.Builder().setResolutionSelector(selector).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                useCases.add(preview)
            }

            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases.toTypedArray())
        }, ContextCompat.getMainExecutor(context))
    }

    private fun toUpright(proxy: ImageProxy): Bitmap {
        val w = proxy.width
        val h = proxy.height
        val plane = proxy.planes[0]
        val rowPx = plane.rowStride / plane.pixelStride
        val buf = plane.buffer
        buf.rewind()

        // Copia directa del buffer a un bitmap reutilizado (incluye el relleno de fila si lo hay)
        var s = src
        if (s == null || s.width != rowPx || s.height != h) {
            s = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
            src = s
        }
        if (buf.remaining() >= rowPx * h * 4) {
            s.copyPixelsFromBuffer(buf)
        } else {
            // Algunos dispositivos no rellenan la última fila: caemos al método de CameraX
            s = proxy.toBitmap()
        }

        val rot = proxy.imageInfo.rotationDegrees
        val outW = if (rot % 180 == 0) w else h
        val outH = if (rot % 180 == 0) h else w
        var o = out
        if (o == null || o.width != outW || o.height != outH) {
            o = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            out = o
        }
        matrix.reset()
        matrix.postRotate(rot.toFloat())
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        matrix.mapRect(rect)
        matrix.postTranslate(-rect.left, -rect.top)
        // Lo que cae fuera (relleno de fila) queda recortado por el tamaño de 'o'
        Canvas(o).drawBitmap(s, matrix, null)
        return o
    }

    fun stop() {
        provider?.unbindAll()
    }

    fun shutdown() {
        stop()
        executor.shutdown()
        executor.awaitTermination(500, TimeUnit.MILLISECONDS)
    }
}
