package com.avatarmocap.output

import android.os.Process
import android.os.SystemClock
import com.avatarmocap.tracking.FaceFrame
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Envía a la PC a 60 fps fijos, sea cual sea la velocidad del tracking.
 *
 * Si el teléfono analiza, por ejemplo, 15 cuadros por segundo, entre dos cuadros reales se
 * interpolan los valores. El avatar se mueve fluido (como con iFacialMocap a 60 fps) a cambio de
 * un retraso de un cuadro de tracking. Con el tracking a 30 fps o más casi no se nota.
 */
class OutputScheduler(private val sinks: CopyOnWriteArrayList<OutputSink>) {

    @Volatile var enabled = true
    @Volatile var sendFps = 0f
        private set

    private val lock = Any()
    private var prev: FaceFrame? = null
    private var last: FaceFrame? = null
    private var prevAt = 0L
    private var lastAt = 0L
    private var interval = 33_000_000L
    @Volatile private var running = true
    private var sent = 0
    private var window = 0L

    private val thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        loop()
    }, "output-60fps").also { it.isDaemon = true; it.start() }

    /** Llamado por el tracking con cada cuadro nuevo. */
    fun push(frame: FaceFrame) {
        if (!enabled) {
            for (s in sinks) s.send(frame)
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            if (lastAt != 0L) {
                val dt = (now - lastAt).coerceIn(10_000_000L, 200_000_000L)
                interval = (interval * 0.8 + dt * 0.2).toLong()
            }
            prev = last; prevAt = lastAt
            last = frame; lastAt = now
        }
    }

    private fun loop() {
        var next = SystemClock.elapsedRealtimeNanos()
        while (running) {
            next += PERIOD
            val sleep = (next - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
            if (sleep > 0) {
                try { Thread.sleep(sleep) } catch (e: InterruptedException) { return }
            } else {
                next = SystemClock.elapsedRealtimeNanos()
            }
            if (!enabled) continue

            val now = SystemClock.elapsedRealtimeNanos()
            val a: FaceFrame?; val b: FaceFrame?; val ta: Long; val tb: Long; val iv: Long
            synchronized(lock) { a = prev; b = last; ta = prevAt; tb = lastAt; iv = interval }
            if (b == null || now - tb > 500_000_000L) continue
            // Se reproduce "un cuadro atrás": entre el anterior y el último
            val frame = if (a == null || !a.faceFound || !b.faceFound || tb <= ta) b
            else {
                val t = ((now - iv - ta).toFloat() / (tb - ta)).coerceIn(0f, 1f)
                lerp(a, b, t, now)
            }
            for (s in sinks) s.send(frame)
            sent++
            if (window == 0L) window = now
            if (now - window >= 1_000_000_000L) { sendFps = sent * 1e9f / (now - window); sent = 0; window = now }
        }
    }

    fun close() {
        running = false
        thread.interrupt()
    }

    companion object {
        private const val PERIOD = 16_666_667L

        private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

        fun lerp(a: FaceFrame, b: FaceFrame, t: Float, nanos: Long) = FaceFrame(
            timestampNanos = nanos,
            faceFound = true,
            blend = mix(a.blend, b.blend, t),
            headRot = mix(a.headRot, b.headRot, t),
            headPos = mix(a.headPos, b.headPos, t),
            leftEye = mix(a.leftEye, b.leftEye, t),
            rightEye = mix(a.rightEye, b.rightEye, t),
            tongueX = a.tongueX + (b.tongueX - a.tongueX) * t,
            tongueY = a.tongueY + (b.tongueY - a.tongueY) * t,
            vrm = mix(a.vrm, b.vrm, t),
            hands = b.hands,
        )
    }
}
