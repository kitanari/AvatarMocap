package com.avatarmocap.tracking

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.Closeable

/**
 * MediaPipe Face Landmarker en modo VIDEO (síncrono, con seguimiento entre cuadros: solo corre
 * el detector de caras cuando pierde la cara, el resto del tiempo solo la malla).
 * Lanza excepción si el [gpu] pedido no está disponible.
 */
class FaceTracker(context: Context, val gpu: Boolean) : Closeable {

    private val landmarker: FaceLandmarker = run {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .setDelegate(if (gpu) Delegate.GPU else Delegate.CPU)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build()
        FaceLandmarker.createFromOptions(context, options)
    }

    /** [timestampMs] debe ser estrictamente creciente. */
    fun detect(bitmap: Bitmap, timestampMs: Long): FaceLandmarkerResult? =
        try {
            landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo en detectForVideo", e)
            null
        }

    override fun close() = landmarker.close()

    companion object {
        private const val TAG = "FaceTracker"
        const val MODEL = "face_landmarker.task"

        /** Crea con el delegado pedido y, si falla, con CPU. */
        fun createWithFallback(context: Context, preferGpu: Boolean): FaceTracker =
            if (!preferGpu) FaceTracker(context, false)
            else try {
                FaceTracker(context, true)
            } catch (e: Throwable) {
                Log.w(TAG, "GPU no disponible, usando CPU", e)
                FaceTracker(context, false)
            }
    }
}
