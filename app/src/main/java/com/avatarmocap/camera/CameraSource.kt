package com.avatarmocap.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Cámara frontal con CameraX. Entrega bitmaps RGBA ya rotados en posición vertical
 * (sin espejar: así MediaPipe identifica bien izquierda/derecha del usuario).
 */
class CameraSource(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    fun start(owner: LifecycleOwner, previewView: PreviewView, onFrame: (Bitmap) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p

            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val preview = Preview.Builder().setResolutionSelector(resolution).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val bmp = proxy.toBitmap()
                    val rot = proxy.imageInfo.rotationDegrees
                    val upright = if (rot != 0) {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    } else bmp
                    onFrame(upright)
                } finally {
                    proxy.close()
                }
            }

            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
    }

    fun shutdown() {
        stop()
        executor.shutdown()
        // Esperar a que termine el cuadro en curso antes de liberar MediaPipe.
        executor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}
