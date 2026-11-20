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
 * Envoltorio de MediaPipe Face Landmarker en modo VIDEO (síncrono): así el resultado y el
 * bitmap del mismo cuadro están disponibles juntos (el detector de lengua necesita ambos).
 */
class FaceTracker(context: Context, preferGpu: Boolean) : Closeable {

    private val landmarker: FaceLandmarker
    val usingGpu: Boolean

    init {
        var gpu = preferGpu
        val lm = try {
            create(context, if (gpu) Delegate.GPU else Delegate.CPU)
        } catch (e: Exception) {
            Log.w(TAG, "GPU no disponible, usando CPU", e)
            gpu = false
            create(context, Delegate.CPU)
        }
        landmarker = lm
        usingGpu = gpu
    }

    private fun create(context: Context, delegate: Delegate): FaceLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .setDelegate(delegate)
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
        return FaceLandmarker.createFromOptions(context, options)
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
    }
}
