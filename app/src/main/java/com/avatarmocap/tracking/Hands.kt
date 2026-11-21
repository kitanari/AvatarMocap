package com.avatarmocap.tracking

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Detector de manos (21 puntos por mano, hasta 2 manos). */
class HandTracker(context: Context, gpu: Boolean) : Closeable {
    private val landmarker: HandLandmarker = run {
        fun create(delegate: Delegate): HandLandmarker {
            val base = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(delegate).build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            return HandLandmarker.createFromOptions(context, options)
        }
        if (gpu) try { create(Delegate.GPU) } catch (e: Throwable) { create(Delegate.CPU) } else create(Delegate.CPU)
    }

    fun detect(bitmap: Bitmap, timestampMs: Long): HandLandmarkerResult? = try {
        landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), timestampMs)
    } catch (e: Exception) {
        Log.e("HandTracker", "Fallo en detectForVideo", e)
        null
    }

    override fun close() = landmarker.close()

    companion object { const val MODEL = "hand_landmarker.task" }
}

/** Una mano ya procesada. Lado = mano del USUARIO (izquierda/derecha reales). */
class HandData(
    val found: Boolean,
    /** 21 puntos x,y normalizados en la imagen. */
    val image: FloatArray,
    /** Flexión de cada articulación en grados: [dedo*3 + articulación], dedos: pulgar, índice, medio, anular, meñique. */
    val bend: FloatArray,
    /** 0 = puño, 1 = mano abierta. */
    val openness: Float,
    /** Rotación de la palma en el espacio del avatar. */
    val palm: Quat,
    /** Posición de la muñeca relativa a la cabeza, en metros aprox. (espacio del avatar). */
    val pos: V3,
) {
    companion object {
        val NONE = HandData(false, FloatArray(42), FloatArray(15), 0f, Quat.IDENTITY, V3(0f, 0f, 0f))
    }
}

class HandsFrame(val left: HandData, val right: HandData) {
    companion object { val NONE = HandsFrame(HandData.NONE, HandData.NONE) }
}

/**
 * Convierte el resultado de MediaPipe en datos de mano para el avatar:
 * flexión de dedos (desde los puntos 3D "de mundo"), apertura, orientación de la palma y
 * posición de la muñeca respecto de la cara.
 */
object HandSolver {

    /** Articulaciones de cada dedo: [base, a, b, c, punta] (la base del pulgar es la muñeca). */
    private val FINGERS = arrayOf(
        intArrayOf(0, 1, 2, 3, 4),
        intArrayOf(0, 5, 6, 7, 8),
        intArrayOf(0, 9, 10, 11, 12),
        intArrayOf(0, 13, 14, 15, 16),
        intArrayOf(0, 17, 18, 19, 20),
    )

    /**
     * @param world 21×3 puntos de mundo de MediaPipe (metros, x derecha, y abajo, z hacia adentro)
     * @param image 21×2 puntos de imagen normalizados
     * @param face (centroX, centroY, anchoCara) en píxeles de la imagen, o null
     * @param userLeft true si es la mano izquierda del usuario
     */
    fun solve(world: FloatArray, image: FloatArray, w: Int, h: Int, face: FloatArray?, userLeft: Boolean): HandData {
        // Espacio del avatar (Unity): el avatar te imita como un espejo de ti mismo:
        // tu izquierda = izquierda del avatar = -X; arriba = +Y; hacia la cámara = +Z.
        // Imagen sin espejar: tu izquierda aparece a la DERECHA de la imagen (+x) -> X avatar = -x.
        fun p(i: Int) = V3(-world[i * 3], -world[i * 3 + 1], -world[i * 3 + 2])

        val bend = FloatArray(15)
        var total = 0f
        for (f in 0 until 5) {
            val j = FINGERS[f]
            for (k in 1..3) {
                val a = p(j[k]) - p(j[k - 1])
                val b = p(j[k + 1]) - p(j[k])
                val cos = (a.dot(b) / (a.len() * b.len()).coerceAtLeast(1e-6f)).coerceIn(-1f, 1f)
                var deg = (acos(cos) * 180f / PI.toFloat())
                // La base del dedo (desde la palma) ya tiene un ángulo natural: se descuenta
                if (k == 1) deg = (deg - if (f == 0) 35f else 10f).coerceAtLeast(0f)
                bend[f * 3 + k - 1] = deg.coerceIn(0f, 110f)
                if (f > 0) total += bend[f * 3 + k - 1]
            }
        }
        val openness = (1f - total / (4f * 200f)).coerceIn(0f, 1f)

        // Base de la palma: dedos (muñeca -> medio) y travesía (índice -> meñique)
        val fwd = (p(9) - p(0)).norm()
        var across = (p(17) - p(5))
        across = (across - fwd * across.dot(fwd)).norm()
        val normal = across.cross(fwd)
        // Pose de referencia (T-pose, palma hacia abajo)
        val restF = if (userLeft) V3(-1f, 0f, 0f) else V3(1f, 0f, 0f)
        val restA = V3(0f, 0f, -1f)
        val restN = restA.cross(restF)
        val rCur = Quat.fromColumns(across, fwd, normal)
        val rRest = Quat.fromColumns(restA, restF, restN)
        val palm = rCur * rRest.inverse()

        // Posición de la muñeca respecto de la cara (en metros: una cara mide ~15 cm de ancho)
        var pos = V3(0f, 0f, 0.25f)
        if (face != null && face[2] > 1f) {
            val wx = image[0] * w; val wy = image[1] * h
            val dx = (wx - face[0]) / face[2] * 0.15f
            val dy = (wy - face[1]) / face[2] * 0.15f
            // Profundidad: la mano parece más grande cuanto más cerca de la cámara está
            val palmPx = dist2(image, 0, 9, w, h)
            val ratio = (palmPx / 0.09f) / (face[2] / 0.15f)
            val forward = ((1f - 1f / ratio.coerceAtLeast(0.5f)) * 0.5f).coerceIn(-0.05f, 0.45f)
            pos = V3(-dx, -dy, 0.15f + forward)
        }
        return HandData(true, image.copyOf(), bend, openness, palm, pos)
    }

    private fun dist2(img: FloatArray, a: Int, b: Int, w: Int, h: Int): Float {
        val dx = (img[a * 2] - img[b * 2]) * w
        val dy = (img[a * 2 + 1] - img[b * 2 + 1]) * h
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Pose de brazo y mano para un avatar VRM (rotaciones LOCALES, T-pose = identidad).
 * IK de dos huesos (hombro -> codo -> muñeca) con el codo apuntando hacia abajo y atrás.
 */
object ArmIK {
    private const val UPPER = 0.26f
    private const val LOWER = 0.25f
    private val HEAD = V3(0f, 1.45f, 0f)

    class Pose(val upper: Quat, val lower: Quat, val hand: Quat, val fingers: Array<Quat>)

    fun solve(hand: HandData, left: Boolean, reach: Float): Pose {
        val side = if (left) -1f else 1f
        val shoulder = V3(0.17f * side, 1.38f, 0f)
        val target = HEAD + V3(hand.pos.x * reach, hand.pos.y * reach, hand.pos.z)
        val rest = V3(side, 0f, 0f)

        var d = target - shoulder
        val dist = d.len().coerceIn(abs(UPPER - LOWER) + 0.01f, UPPER + LOWER - 0.005f)
        val u = d.norm()
        d = u * dist
        val cosA = ((UPPER * UPPER + dist * dist - LOWER * LOWER) / (2f * UPPER * dist)).coerceIn(-1f, 1f)
        val sinA = sqrt((1f - cosA * cosA).coerceAtLeast(0f))
        val pole = V3(0f, -1f, -0.4f)
        val pp = (pole - u * pole.dot(u)).norm().let { if (it.len() < 0.5f) V3(0f, -1f, 0f) else it }
        val elbow = shoulder + (u * cosA + pp * sinA) * UPPER
        val wrist = shoulder + d

        val qUpper = Quat.fromTo(rest, elbow - shoulder)
        val qLowerW = Quat.fromTo(qUpper.rotate(rest), wrist - elbow) * qUpper
        val qLower = qUpper.inverse() * qLowerW
        val qHand = qLowerW.inverse() * hand.palm

        // Dedos: los índice..meñique se doblan sobre Z (izq +, der -); el pulgar sobre Y
        val fingers = Array(15) { i ->
            val f = i / 3
            val deg = hand.bend[i]
            if (f == 0) Quat.axisAngle(V3(0f, 1f, 0f), deg * 0.8f * (if (left) -1f else 1f))
            else Quat.axisAngle(V3(0f, 0f, 1f), deg * (if (left) 1f else -1f))
        }
        return Pose(qUpper, qLower, qHand, fingers)
    }

    /** Brazo relajado hacia abajo (unos 70° desde la T-pose), mano y dedos en reposo. */
    fun rest(left: Boolean): Pose {
        val side = if (left) -1f else 1f
        val upper = Quat.fromTo(V3(side, 0f, 0f), V3(side * 0.34f, -0.94f, 0f).norm())
        return Pose(upper, Quat.IDENTITY, Quat.IDENTITY, Array(15) { Quat.IDENTITY })
    }

    /** Interpola entre dos poses (t = 0 -> a, 1 -> b). */
    fun blend(a: Pose, b: Pose, t: Float) = Pose(
        a.upper.slerpTo(b.upper, t), a.lower.slerpTo(b.lower, t), a.hand.slerpTo(b.hand, t),
        Array(15) { a.fingers[it].slerpTo(b.fingers[it], t) },
    )

    /** Nombres de huesos de VRM (humanoide) para cada articulación de dedo. */
    fun fingerBone(left: Boolean, i: Int): String {
        val side = if (left) "Left" else "Right"
        val finger = arrayOf("Thumb", "Index", "Middle", "Ring", "Little")[i / 3]
        val joint = arrayOf("Proximal", "Intermediate", "Distal")[i % 3]
        return "$side$finger$joint"
    }
}
