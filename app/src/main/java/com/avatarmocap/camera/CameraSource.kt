package com.avatarmocap.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import java.util.concurrent.Executors

/**
 * Cámara frontal optimizada para tracking:
 *
 *  - Fuerza la cámara a 30 fps aunque haya poca luz (muchas frontales bajan solas a 15).
 *  - DOS hilos: el de la cámara solo convierte la imagen; el de tracking procesa. Mientras
 *    MediaPipe trabaja en un cuadro, la cámara ya prepara el siguiente (triple buffer).
 *  - Reutiliza 3 bitmaps fijos: cero basura por cuadro.
 *  - La vista previa es opcional.
 *
 * [onFrame] se llama en el hilo de tracking con el cuadro más reciente (los viejos se descartan).
 */
class CameraSource(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); r.run() }, "camera-convert")
    }

    // ---- Triple buffer
    private val pool = arrayOfNulls<Bitmap>(3)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private var latest = -1
    private var processing = -1
    @Volatile private var running = true
    @Volatile private var onFrame: ((Bitmap) -> Unit)? = null
    private var worker: Thread? = null

    // ---- Conversión
    private var src: Bitmap? = null
    private val matrix = Matrix()
    private val rect = RectF()

    // ---- Métricas
    @Volatile var cameraFps = 0f
        private set
    /** Lo que tarda convertir/rotar cada imagen (hilo de cámara). */
    @Volatile var convertMs = 0f
        private set
    private var camFrames = 0
    private var camWindow = 0L

    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    fun start(owner: LifecycleOwner, previewView: PreviewView?, resolution: Int, frameHandler: (Bitmap) -> Unit) {
        onFrame = frameHandler
        ensureWorker()
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
            val fpsRange = pickFpsRange(p)

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            if (fpsRange != null) {
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                try { convertAndPublish(proxy) } finally { proxy.close() }
            }

            val useCases = mutableListOf<UseCase>(analysis)
            if (previewView != null) {
                val pb = Preview.Builder().setResolutionSelector(selector)
                if (fpsRange != null) {
                    Camera2Interop.Extender(pb)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }
                val preview = pb.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                useCases.add(preview)
            }

            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases.toTypedArray())
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir la cámara", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** El rango de fps con el mínimo más alto que llegue a 30 (p.ej. [30,30] mejor que [15,30]). */
    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    private fun pickFpsRange(p: ProcessCameraProvider): Range<Int>? = try {
        val info = CameraSelector.DEFAULT_FRONT_CAMERA.filter(p.availableCameraInfos).firstOrNull()
        val ranges = info?.let {
            Camera2CameraInfo.from(it)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }
        ranges?.filter { it.upper in 25..30 }?.maxByOrNull { it.lower }
            ?: ranges?.maxByOrNull { it.upper * 100 + it.lower }
    } catch (e: Exception) {
        null
    }

    private fun convertAndPublish(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtimeNanos()
        camFrames++
        if (camWindow == 0L) camWindow = now
        if (now - camWindow >= 1_000_000_000L) {
            cameraFps = camFrames * 1e9f / (now - camWindow); camFrames = 0; camWindow = now
        }

        // Elegir un buffer que no sea el último publicado ni el que se está procesando
        val idx: Int
        synchronized(lock) {
            var i = 0
            while (i == latest || i == processing) i++
            idx = i
        }

        val w = proxy.width
        val h = proxy.height
        val rot = proxy.imageInfo.rotationDegrees
        val outW = if (rot % 180 == 0) w else h
        val outH = if (rot % 180 == 0) h else w
        var o = pool[idx]
        if (o == null || o.width != outW || o.height != outH) {
            o = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            pool[idx] = o
        }

        val plane = proxy.planes[0]
        val rowPx = plane.rowStride / plane.pixelStride
        val buf = plane.buffer
        buf.rewind()
        var s = src
        if (s == null || s.width != rowPx || s.height != h) {
            s = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
            src = s
        }
        if (buf.remaining() >= rowPx * h * 4) s.copyPixelsFromBuffer(buf) else s = proxy.toBitmap()

        matrix.reset()
        matrix.postRotate(rot.toFloat())
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        matrix.mapRect(rect)
        matrix.postTranslate(-rect.left, -rect.top)
        Canvas(o!!).drawBitmap(s, matrix, null)
        convertMs = (SystemClock.elapsedRealtimeNanos() - now) / 1e6f

        synchronized(lock) {
            latest = idx
            lock.notifyAll()
        }
    }

    private fun ensureWorker() {
        if (worker != null) return
        running = true
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (running) {
                var idx = -1
                synchronized(lock) {
                    if (latest < 0 && running) lock.wait(200)
                    if (latest >= 0) {
                        idx = latest
                        latest = -1
                        processing = idx
                    }
                }
                if (idx < 0) continue
                try {
                    pool[idx]?.let { onFrame?.invoke(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando cuadro", e)
                } finally {
                    synchronized(lock) { processing = -1 }
                }
            }
        }, "tracking").also { it.start() }
    }

    fun stop() {
        provider?.unbindAll()
    }

    fun shutdown() {
        stop()
        running = false
        synchronized(lock) { lock.notifyAll() }
        worker?.join(700)
        cameraExecutor.shutdown()
    }

    companion object { private const val TAG = "CameraSource" }
}
