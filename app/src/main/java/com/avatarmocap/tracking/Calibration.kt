package com.avatarmocap.tracking

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Pasos de la calibración guiada. [shapes] = blendshapes cuyo máximo se aprende en ese paso. */
enum class CalibStep(val title: String, val instruction: String, val shapes: Array<String>) {
    NEUTRAL("Cara neutral", "Relajá la cara y mirá a la cámara.", emptyArray()),
    EYES_CLOSED("Ojos cerrados", "Cerrá los dos ojos, sin apretar.", arrayOf("eyeBlinkLeft", "eyeBlinkRight")),
    SURPRISE(
        "Sorpresa", "Subí las cejas y abrí bien los ojos.",
        arrayOf("eyeWideLeft", "eyeWideRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight"),
    ),
    ANGRY(
        "Ceño fruncido", "Bajá y juntá las cejas, como enojado.",
        arrayOf("browDownLeft", "browDownRight", "noseSneerLeft", "noseSneerRight"),
    ),
    SMILE(
        "Sonrisa", "Sonreí bien grande.",
        arrayOf("mouthSmileLeft", "mouthSmileRight", "cheekSquintLeft", "cheekSquintRight", "mouthDimpleLeft", "mouthDimpleRight"),
    ),
    MOUTH_OPEN(
        "Boca abierta", "Abrí la boca al máximo, SIN sacar la lengua.",
        arrayOf("jawOpen", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthUpperUpLeft", "mouthUpperUpRight"),
    ),
    POUT("Beso", "Poné los labios en \"U\", como para dar un beso.", arrayOf("mouthPucker", "mouthFunnel")),
    PUFF("Cachetes inflados", "Inflá los cachetes con la boca cerrada.", arrayOf("cheekPuff")),
    TONGUE("Lengua afuera", "Sacá la lengua hacia abajo lo más que puedas.", emptyArray());

    val indices: IntArray by lazy { IntArray(shapes.size) { ArKit.idx(shapes[it]) } }
}

/**
 * Calibración por usuario (estilo VSeeFace):
 *  - Neutral: valor de reposo de cada blendshape + pose de la cabeza.
 *  - Expresiones: el máximo real que alcanza cada gesto en TU cara -> se re-escala a 0..1.
 *    Así la boca no se abre de más ni los ojos quedan a medio cerrar.
 *  - Lengua (f0/f1) y cachetes (geometría) también se calibran acá.
 *
 * La captura de cada paso dura [WARMUP_NS] de preparación + [CAPTURE_NS] midiendo.
 */
class Calibration {
    val neutral = FloatArray(ArKit.COUNT)
    val neutralHead = FloatArray(6)
    /** Máximo calibrado por blendshape (NaN = sin calibrar). */
    val maxV = FloatArray(ArKit.COUNT) { Float.NaN }
    @Volatile var puffNeutral = Float.NaN
    @Volatile var puffMax = Float.NaN
    @Volatile var tongueF0 = 0.08f
    @Volatile var tongueF1 = 0.45f
    @Volatile var hasNeutral = false
        private set
    val done: MutableSet<CalibStep> = java.util.Collections.synchronizedSet(mutableSetOf())

    private val autoMax = FloatArray(ArKit.COUNT)

    // ---- Captura
    @Volatile var capturing: CalibStep? = null
        private set
    private var capStart = 0L
    private val samples = ArrayList<FloatArray>()
    /** (paso, ok, mensaje) — se llama en el hilo de la cámara. */
    var onStepDone: ((CalibStep, Boolean, String) -> Unit)? = null

    @Synchronized
    fun startCapture(step: CalibStep, nowNanos: Long) {
        samples.clear()
        capStart = nowNanos
        capturing = step
    }

    /** -1 = sin captura; 0..1 = preparación; 1..2 = midiendo. */
    fun progress(nowNanos: Long): Float {
        if (capturing == null) return -1f
        val t = (nowNanos - capStart).toFloat()
        return if (t < WARMUP_NS) t / WARMUP_NS else 1f + min(1f, (t - WARMUP_NS) / CAPTURE_NS)
    }

    @Synchronized
    fun feed(raw: FloatArray, head: FloatArray, puff: Float, tongueFeature: Float, nowNanos: Long) {
        val step = capturing ?: return
        val t = nowNanos - capStart
        if (t < WARMUP_NS) return
        val s = FloatArray(ArKit.COUNT + 8)
        System.arraycopy(raw, 0, s, 0, ArKit.COUNT)
        System.arraycopy(head, 0, s, ArKit.COUNT, 6)
        s[ArKit.COUNT + 6] = puff
        s[ArKit.COUNT + 7] = tongueFeature
        samples.add(s)
        if (t >= WARMUP_NS + CAPTURE_NS && samples.size >= 5) {
            capturing = null
            finish(step)
        }
    }

    private fun col(c: Int) = FloatArray(samples.size) { samples[it][c] }

    private fun finish(step: CalibStep) {
        val puffCol = ArKit.COUNT + 6
        val tongueCol = ArKit.COUNT + 7
        var ok = true
        var msg = "${step.title} ✓"
        when (step) {
            CalibStep.NEUTRAL -> {
                for (i in 0 until ArKit.COUNT) neutral[i] = mean(col(i))
                for (i in 0 until 6) neutralHead[i] = mean(col(ArKit.COUNT + i))
                puffNeutral = mean(col(puffCol))
                hasNeutral = true
                autoMax.fill(0f)
            }
            CalibStep.TONGUE -> {
                tongueF1 = upperMean(col(tongueCol))
                if (tongueF1 - tongueF0 < 0.05f) {
                    ok = false
                    msg = "Casi no se distingue la lengua (%.2f vs %.2f). Probá con más luz de frente.".format(tongueF1, tongueF0)
                    tongueF1 = tongueF0 + 0.15f
                }
            }
            else -> {
                var weak = 0
                for (i in step.indices) {
                    val n = if (hasNeutral) neutral[i] else 0f
                    val top = upperMean(col(i))
                    if (top - n < MIN_SPAN) weak++
                    maxV[i] = max(top, n + MIN_SPAN) // nunca amplificar ruido
                }
                if (step == CalibStep.MOUTH_OPEN) tongueF0 = mean(col(tongueCol))
                if (step == CalibStep.PUFF) {
                    val p = upperMean(col(puffCol))
                    puffMax = if (!puffNeutral.isNaN()) max(p, puffNeutral + 0.01f) else p
                }
                if (weak == step.indices.size && step != CalibStep.PUFF) {
                    ok = false
                    msg = "${step.title}: casi no se detectó el gesto. Exageralo un poco más y repetí."
                }
            }
        }
        if (ok) done.add(step)
        onStepDone?.invoke(step, ok, msg)
    }

    fun apply(raw: FloatArray, out: FloatArray, autoRange: Boolean, gains: FloatArray) {
        for (i in 0 until ArKit.COUNT) {
            var v: Float
            if (i == ArKit.TONGUE_OUT) {
                v = raw[i] // la lengua ya viene calibrada desde el detector
            } else {
                val n = if (hasNeutral) min(neutral[i], 0.8f) else 0f
                val mx = maxV[i]
                v = when {
                    !mx.isNaN() -> (raw[i] - n) / max(mx - n, 0.05f)
                    autoRange -> {
                        val base = ((raw[i] - n) / (1f - n)).coerceIn(0f, 1f)
                        autoMax[i] = max(autoMax[i] * AUTO_DECAY, base)
                        base / max(autoMax[i], AUTO_MIN_RANGE)
                    }
                    else -> (raw[i] - n) / (1f - n)
                }
            }
            val g = ArKit.GROUP[i]
            if (g >= 0) v *= gains[g]
            out[i] = v.coerceIn(0f, 1f)
        }
    }

    /** Señal 0..1 de cachetes inflados a partir de la geometría. */
    fun puffSignal(ratio: Float): Float {
        if (puffNeutral.isNaN()) return 0f
        val span = if (puffMax.isNaN()) DEFAULT_PUFF_SPAN else max(puffMax - puffNeutral, 0.008f)
        return ((ratio - puffNeutral) / span).coerceIn(0f, 1f)
    }

    fun relativeHead(head: FloatArray, out: FloatArray) {
        for (i in 0 until 6) out[i] = if (hasNeutral) head[i] - neutralHead[i] else head[i]
        if (!hasNeutral) { out[3] = 0f; out[4] = 0f; out[5] = 0f }
    }

    fun resetAll() {
        neutral.fill(0f); neutralHead.fill(0f); maxV.fill(Float.NaN)
        puffNeutral = Float.NaN; puffMax = Float.NaN
        tongueF0 = 0.08f; tongueF1 = 0.45f
        hasNeutral = false
        done.clear()
        autoMax.fill(0f)
    }

    // ---- Persistencia (JSON; NaN se guarda como -1)
    fun toJson(): String {
        fun arr(a: FloatArray) = JSONArray().apply { a.forEach { put(if (it.isNaN()) -1.0 else it.toDouble()) } }
        return JSONObject()
            .put("v", 2)
            .put("hasNeutral", hasNeutral)
            .put("neutral", arr(neutral))
            .put("neutralHead", arr(neutralHead))
            .put("max", arr(maxV))
            .put("puffNeutral", if (puffNeutral.isNaN()) -1.0 else puffNeutral.toDouble())
            .put("puffMax", if (puffMax.isNaN()) -1.0 else puffMax.toDouble())
            .put("tongueF0", tongueF0.toDouble())
            .put("tongueF1", tongueF1.toDouble())
            .put("done", JSONArray(done.map { it.name }))
            .toString()
    }

    fun fromJson(s: String) {
        if (s.isBlank()) return
        try {
            val o = JSONObject(s)
            fun read(name: String, into: FloatArray, nanIfNeg: Boolean) {
                val a = o.getJSONArray(name)
                for (i in into.indices) {
                    val v = a.getDouble(i).toFloat()
                    into[i] = if (nanIfNeg && v < 0f) Float.NaN else v
                }
            }
            read("neutral", neutral, false)
            read("neutralHead", neutralHead, false)
            read("max", maxV, true)
            hasNeutral = o.optBoolean("hasNeutral")
            puffNeutral = o.optDouble("puffNeutral", -1.0).toFloat().let { if (it < 0f) Float.NaN else it }
            puffMax = o.optDouble("puffMax", -1.0).toFloat().let { if (it < 0f) Float.NaN else it }
            tongueF0 = o.optDouble("tongueF0", 0.08).toFloat()
            tongueF1 = o.optDouble("tongueF1", 0.45).toFloat()
            val d = o.optJSONArray("done")
            if (d != null) for (i in 0 until d.length()) {
                runCatching { CalibStep.valueOf(d.getString(i)) }.getOrNull()?.let { done.add(it) }
            }
        } catch (e: Exception) {
            resetAll()
        }
    }

    companion object {
        const val WARMUP_NS = 1_500_000_000L
        const val CAPTURE_NS = 1_500_000_000L
        private const val MIN_SPAN = 0.15f
        private const val AUTO_DECAY = 0.9995f
        private const val AUTO_MIN_RANGE = 0.6f
        private const val DEFAULT_PUFF_SPAN = 0.04f

        private fun mean(a: FloatArray) = if (a.isEmpty()) 0f else a.sum() / a.size

        /** Promedio de la mitad superior: robusto a los primeros cuadros antes de llegar al gesto. */
        private fun upperMean(a: FloatArray): Float {
            if (a.isEmpty()) return 0f
            val s = a.sortedArray()
            return mean(s.copyOfRange(s.size / 2, s.size))
        }
    }
}
