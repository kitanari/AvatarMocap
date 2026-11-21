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
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.camera.core.Camera
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
    fun start(
        owner: LifecycleOwner,
        previewView: PreviewView?,
        resolution: Int,
        rear: Boolean,
        frameHandler: (Bitmap) -> Unit,
    ) {
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
            val lens = if (rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            val fpsRange = pickFpsRange(p, lens)
            expIndex = 0; swGain = 1f; updateGainPaint()

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
                camera = p.bindToLifecycle(owner, lens, *useCases.toTypedArray())
                camera?.cameraControl?.setExposureCompensationIndex(0)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir la cámara", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Rango de fps hasta 30 con un mínimo de ~20-24: la cámara puede exponer un poco más cuando
     * hay poca luz (antes se forzaba [30,30] y la imagen se oscurecía).
     */
    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    private fun pickFpsRange(p: ProcessCameraProvider, lens: CameraSelector): Range<Int>? = try {
        val info = lens.filter(p.availableCameraInfos).firstOrNull()
        val ranges = info?.let {
            Camera2CameraInfo.from(it)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }
        ranges?.filter { it.upper in 25..30 && it.lower in 15..24 }?.maxByOrNull { it.lower }
            ?: ranges?.filter { it.upper in 25..30 }?.minByOrNull { it.lower }
    } catch (e: Exception) {
        null
    }

    // ---------- Exposición medida en la cara (no en el fondo)

    private var camera: Camera? = null
    private var expIndex = 0
    private var lastExpAdjust = 0L
    @Volatile private var swGain = 1f
    @Volatile private var gainPaint: Paint? = null
    @Volatile var faceLuma = 0f
        private set
    @Volatile var exposureInfo = ""
        private set

    /**
     * Llamado por el tracking con el brillo medio de la cara (0..255). Sube o baja la
     * compensación de exposición de la cámara para mantener la cara bien iluminada; si la
     * cámara ya está al máximo, aclara la imagen por software.
     */
    fun reportFaceLuma(luma: Float) {
        faceLuma = luma
        val now = SystemClock.elapsedRealtime()
        if (now - lastExpAdjust < 400) return
        lastExpAdjust = now
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        val range = st.exposureCompensationRange
        val supported = st.isExposureCompensationSupported
        var newIdx = expIndex
        if (supported) {
            if (luma < TARGET_LOW && expIndex < range.upper) newIdx++
            else if (luma > TARGET_HIGH && expIndex > range.lower) newIdx--
            if (newIdx != expIndex) {
                expIndex = newIdx
                cam.cameraControl.setExposureCompensationIndex(newIdx)
            }
        }
        // Ganancia por software solo si la cámara no puede más (o no soporta compensación)
        val atMax = !supported || expIndex >= range.upper
        val target = if (atMax && luma > 1f && luma < TARGET_LOW) (TARGET_MID / luma * swGain).coerceIn(1f, 2.2f)
        else if (luma > TARGET_HIGH) (swGain * 0.95f).coerceAtLeast(1f)
        else swGain
        if (kotlin.math.abs(target - swGain) > 0.03f) {
            swGain = swGain + (target - swGain) * 0.5f
            updateGainPaint()
        }
        exposureInfo = "EV $expIndex · x%.1f".format(swGain)
    }

    private fun updateGainPaint() {
        gainPaint = if (swGain <= 1.01f) null else Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(swGain, swGain, swGain, 1f) })
        }
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
        Canvas(o!!).drawBitmap(s, matrix, gainPaint)
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

    companion object {
        private const val TAG = "CameraSource"
        private const val TARGET_LOW = 105f
        private const val TARGET_MID = 135f
        private const val TARGET_HIGH = 175f
    }
}
