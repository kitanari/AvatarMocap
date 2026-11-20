package com.avatarmocap.tracking

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Pasos de la calibración: las expresiones básicas de un modelo VRM + extras.
 *  - [shapes]: blendshapes ARKit cuyo máximo se aprende en ese paso.
 *  - [viseme] / [emotion]: casilla VRM cuya "plantilla" se aprende (o -1).
 */
enum class CalibStep(
    val title: String,
    val instruction: String,
    val shapes: Array<String> = emptyArray(),
    val viseme: Int = -1,
    val emotion: Int = -1,
) {
    NEUTRAL("Neutral", "Relajá la cara y mirá al centro de la cámara."),
    BLINK("Parpadeo", "Cerrá los dos ojos, sin apretar.", arrayOf("eyeBlinkLeft", "eyeBlinkRight")),
    A("A", "Decí \"A\" con la boca bien abierta y mantenela.",
        arrayOf("jawOpen", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthUpperUpLeft", "mouthUpperUpRight"), viseme = 0),
    I("I", "Decí \"I\" estirando los labios a los costados.", arrayOf("mouthStretchLeft", "mouthStretchRight"), viseme = 1),
    U("U", "Decí \"U\" con los labios hacia adelante.", arrayOf("mouthPucker"), viseme = 2),
    E("E", "Decí \"E\" y mantenela.", viseme = 3),
    O("O", "Decí \"O\" con la boca redonda.", arrayOf("mouthFunnel"), viseme = 4),
    JOY("Alegría (Joy)", "Sonreí bien grande.",
        arrayOf("mouthSmileLeft", "mouthSmileRight", "cheekSquintLeft", "cheekSquintRight", "mouthDimpleLeft", "mouthDimpleRight"),
        emotion = 0),
    ANGRY("Enojo (Angry)", "Fruncí el ceño: cejas abajo y juntas.",
        arrayOf("browDownLeft", "browDownRight", "noseSneerLeft", "noseSneerRight"), emotion = 1),
    SORROW("Tristeza (Sorrow)", "Cejas arriba en el centro y comisuras hacia abajo.",
        arrayOf("browInnerUp", "mouthFrownLeft", "mouthFrownRight"), emotion = 2),
    FUN("Diversión (Fun)", "Sonrisa suave con la boca cerrada y ojos relajados.", emotion = 3),
    SURPRISED("Sorpresa", "Cejas arriba y ojos bien abiertos.",
        arrayOf("eyeWideLeft", "eyeWideRight", "browOuterUpLeft", "browOuterUpRight"), emotion = 4),
    PUFF("Cachetes inflados", "Inflá los cachetes con la boca cerrada.", arrayOf("cheekPuff")),
    TONGUE("Lengua afuera", "Sacá la lengua hacia abajo lo más que puedas.");

    val indices: IntArray by lazy { IntArray(shapes.size) { ArKit.idx(shapes[it]) } }
}

/** Lo capturado en un paso. */
class StepData(
    val mean: FloatArray,      // 52 blendshapes crudos (promedio)
    val upper: FloatArray,     // 52 (promedio de la mitad superior)
    val head: FloatArray,      // 6
    val featMean: FloatArray,  // Feat.COUNT
    val featUpper: FloatArray,
    val featLower: FloatArray,
)

/**
 * Calibración por usuario. Guarda los datos crudos de cada paso y de ahí deriva todo:
 * neutral, máximos por blendshape, parpadeo/boca/mirada geométricos, cachetes, lengua y
 * las plantillas de las expresiones VRM.
 */
class Calibration {
    private val steps = java.util.concurrent.ConcurrentHashMap<CalibStep, StepData>()
    val done: Set<CalibStep> get() = steps.keys.toSet()

    // ---- Derivados (se recalculan con recompute())
    val neutral = FloatArray(ArKit.COUNT)
    val neutralHead = FloatArray(6)
    val maxV = FloatArray(ArKit.COUNT) { Float.NaN }
    val featNeutral = FloatArray(Feat.COUNT) { Float.NaN }
    val earClosed = floatArrayOf(Float.NaN, Float.NaN)   // L, R
    @Volatile var gapMax = Float.NaN
    val puffMax = floatArrayOf(Float.NaN, Float.NaN)
    @Volatile var tongueF0 = 0.08f
    @Volatile var tongueF1 = 0.45f
    val visemeT = arrayOfNulls<FloatArray>(5)
    val emotionT = arrayOfNulls<FloatArray>(5)
    @Volatile var hasNeutral = false
        private set

    private val autoMax = FloatArray(ArKit.COUNT)

    // ---- Captura
    @Volatile var capturing: CalibStep? = null
        private set
    private var capStart = 0L
    private val samples = ArrayList<FloatArray>()
    var onStepDone: ((CalibStep, Boolean, String) -> Unit)? = null

    @Synchronized
    fun startCapture(step: CalibStep, nowNanos: Long) {
        samples.clear(); capStart = nowNanos; capturing = step
    }

    fun progress(nowNanos: Long): Float {
        if (capturing == null) return -1f
        val t = (nowNanos - capStart).toFloat()
        return if (t < WARMUP_NS) t / WARMUP_NS else 1f + min(1f, (t - WARMUP_NS) / CAPTURE_NS)
    }

    @Synchronized
    fun feed(raw: FloatArray, head: FloatArray, feat: FloatArray, nowNanos: Long) {
        val step = capturing ?: return
        val t = nowNanos - capStart
        if (t < WARMUP_NS) return
        val s = FloatArray(ArKit.COUNT + 6 + Feat.COUNT)
        System.arraycopy(raw, 0, s, 0, ArKit.COUNT)
        System.arraycopy(head, 0, s, ArKit.COUNT, 6)
        System.arraycopy(feat, 0, s, ArKit.COUNT + 6, Feat.COUNT)
        samples.add(s)
        if (t >= WARMUP_NS + CAPTURE_NS && samples.size >= 5) {
            capturing = null
            finish(step)
        }
    }

    private fun col(c: Int) = FloatArray(samples.size) { samples[it][c] }

    private fun finish(step: CalibStep) {
        val fo = ArKit.COUNT + 6
        val data = StepData(
            mean = FloatArray(ArKit.COUNT) { mean(col(it)) },
            upper = FloatArray(ArKit.COUNT) { upperMean(col(it)) },
            head = FloatArray(6) { mean(col(ArKit.COUNT + it)) },
            featMean = FloatArray(Feat.COUNT) { mean(col(fo + it)) },
            featUpper = FloatArray(Feat.COUNT) { upperMean(col(fo + it)) },
            featLower = FloatArray(Feat.COUNT) { lowerMean(col(fo + it)) },
        )
        val (ok, msg) = validate(step, data)
        if (ok) {
            steps[step] = data
            recompute()
        }
        onStepDone?.invoke(step, ok, msg)
    }

    private fun validate(step: CalibStep, d: StepData): Pair<Boolean, String> {
        if (step == CalibStep.NEUTRAL) return true to "Neutral ✓"
        val n = steps[CalibStep.NEUTRAL] ?: return false to "Primero calibrá \"Neutral\"."
        when (step) {
            CalibStep.BLINK -> {
                val dl = n.featMean[Feat.EAR_L] - d.featMean[Feat.EAR_L]
                val dr = n.featMean[Feat.EAR_R] - d.featMean[Feat.EAR_R]
                if (dl < 0.03f || dr < 0.03f) return false to "No se detectaron los ojos cerrados. Repetí con más luz."
            }
            CalibStep.TONGUE -> {
                val f0 = steps[CalibStep.A]?.featMean?.get(Feat.TONGUE) ?: tongueF0
                if (d.featUpper[Feat.TONGUE] - f0 < 0.05f) {
                    return false to "Casi no se distingue la lengua. Sacala más y probá con más luz de frente."
                }
            }
            CalibStep.PUFF -> {
                val pl = d.featUpper[Feat.PUFF_L] - n.featMean[Feat.PUFF_L]
                val pr = d.featUpper[Feat.PUFF_R] - n.featMean[Feat.PUFF_R]
                if (min(pl, pr) < 0.004f && d.upper[ArKit.CHEEK_PUFF] - n.mean[ArKit.CHEEK_PUFF] < 0.1f) {
                    return false to "No se notó el inflado. Inflá más los cachetes."
                }
            }
            else -> {
                var diff = 0f
                for (i in 0 until ArKit.COUNT) diff += max(0f, d.mean[i] - n.mean[i])
                if (diff < 0.25f) return false to "${step.title}: el gesto casi no cambió. Exageralo un poco más."
            }
        }
        return true to "${step.title} ✓"
    }

    @Synchronized
    fun recompute() {
        val n = steps[CalibStep.NEUTRAL]
        hasNeutral = n != null
        if (n != null) {
            System.arraycopy(n.mean, 0, neutral, 0, ArKit.COUNT)
            System.arraycopy(n.head, 0, neutralHead, 0, 6)
            System.arraycopy(n.featMean, 0, featNeutral, 0, Feat.COUNT)
        } else {
            neutral.fill(0f); neutralHead.fill(0f); featNeutral.fill(Float.NaN)
        }
        maxV.fill(Float.NaN)
        for ((step, d) in steps) for (i in step.indices) {
            val v = max(d.upper[i], neutral[i] + MIN_SPAN)
            maxV[i] = if (maxV[i].isNaN()) v else max(maxV[i], v)
        }
        val b = steps[CalibStep.BLINK]
        earClosed[0] = b?.featMean?.get(Feat.EAR_L) ?: Float.NaN
        earClosed[1] = b?.featMean?.get(Feat.EAR_R) ?: Float.NaN
        gapMax = steps[CalibStep.A]?.featUpper?.get(Feat.GAP) ?: Float.NaN
        val p = steps[CalibStep.PUFF]
        puffMax[0] = p?.featUpper?.get(Feat.PUFF_L) ?: Float.NaN
        puffMax[1] = p?.featUpper?.get(Feat.PUFF_R) ?: Float.NaN
        tongueF0 = steps[CalibStep.A]?.featMean?.get(Feat.TONGUE) ?: 0.08f
        tongueF1 = steps[CalibStep.TONGUE]?.featUpper?.get(Feat.TONGUE) ?: max(0.45f, tongueF0 + 0.2f)
        visemeT.fill(null); emotionT.fill(null)
        for ((step, d) in steps) {
            if (step.viseme < 0 && step.emotion < 0) continue
            val t = FloatArray(ArKit.COUNT) { max(0f, d.mean[it] - neutral[it]) }
            if (step.viseme >= 0) visemeT[step.viseme] = t
            if (step.emotion >= 0) emotionT[step.emotion] = t
        }
        autoMax.fill(0f)
    }

    // ---------- Aplicación en tiempo real

    /** Re-escala cada blendshape entre su neutral y su máximo calibrado. */
    fun apply(raw: FloatArray, out: FloatArray, autoRange: Boolean) {
        for (i in 0 until ArKit.COUNT) {
            if (i == ArKit.TONGUE_OUT) { out[i] = raw[i]; continue }
            val n = if (hasNeutral) min(neutral[i], 0.8f) else 0f
            val mx = maxV[i]
            val v = when {
                !mx.isNaN() -> (raw[i] - n) / max(mx - n, 0.05f)
                autoRange -> {
                    val base = ((raw[i] - n) / (1f - n)).coerceIn(0f, 1f)
                    autoMax[i] = max(autoMax[i] * AUTO_DECAY, base)
                    base / max(autoMax[i], AUTO_MIN_RANGE)
                }
                else -> (raw[i] - n) / (1f - n)
            }
            out[i] = v.coerceIn(0f, 1f)
        }
    }

    /** Parpadeo geométrico 0..1 (k = 0 izq, 1 der), o NaN si no está calibrado. */
    fun blinkGeo(k: Int, ear: Float): Float {
        val open = featNeutral[if (k == 0) Feat.EAR_L else Feat.EAR_R]
        val closed = earClosed[k]
        if (open.isNaN() || closed.isNaN()) return Float.NaN
        return ((open - ear) / max(open - closed, 0.02f)).coerceIn(0f, 1f)
    }

    /** Apertura de boca geométrica 0..1, o NaN. */
    fun jawGeo(gap: Float): Float {
        val n = featNeutral[Feat.GAP]
        if (n.isNaN() || gapMax.isNaN()) return Float.NaN
        return ((gap - n) / max(gapMax - n, 0.02f)).coerceIn(0f, 1f)
    }

    /** Mirada desde el iris: out = (h, v) en -1..1 relativo a la mirada neutral. */
    fun gaze(feat: FloatArray, left: Boolean, out: FloatArray): Boolean {
        val hi = if (left) Feat.IRIS_HL else Feat.IRIS_HR
        val vi = if (left) Feat.IRIS_VL else Feat.IRIS_VR
        val h0 = featNeutral[hi]; val v0 = featNeutral[vi]
        if (h0.isNaN() || v0.isNaN()) return false
        out[0] = ((feat[hi] - h0) / IRIS_H_RANGE).coerceIn(-1f, 1f)
        out[1] = ((feat[vi] - v0) / IRIS_V_RANGE).coerceIn(-1f, 1f)
        return true
    }

    /** Cachetes inflados geométrico: mínimo de ambos lados (al mover la boca solo cambia uno). */
    fun puffSignal(feat: FloatArray): Float {
        var sig = 1f
        val idx = intArrayOf(Feat.PUFF_L, Feat.PUFF_R)
        for (k in 0..1) {
            val n = featNeutral[idx[k]]
            if (n.isNaN()) return 0f
            val span = if (puffMax[k].isNaN()) DEFAULT_PUFF_SPAN else max(puffMax[k] - n, 0.005f)
            sig = min(sig, ((feat[idx[k]] - n) / span).coerceIn(0f, 1f))
        }
        return sig
    }

    fun relativeHead(head: FloatArray, out: FloatArray) {
        for (i in 0 until 6) out[i] = if (hasNeutral) head[i] - neutralHead[i] else head[i]
        if (!hasNeutral) { out[3] = 0f; out[4] = 0f; out[5] = 0f }
    }

    fun resetAll() {
        steps.clear()
        recompute()
    }

    // ---------- Persistencia

    fun toJson(): String {
        fun arr(a: FloatArray) = JSONArray().apply { a.forEach { put(if (it.isNaN()) 0.0 else it.toDouble()) } }
        val st = JSONObject()
        for ((step, d) in steps) {
            st.put(step.name, JSONObject()
                .put("mean", arr(d.mean)).put("upper", arr(d.upper)).put("head", arr(d.head))
                .put("featMean", arr(d.featMean)).put("featUpper", arr(d.featUpper)).put("featLower", arr(d.featLower)))
        }
        return JSONObject().put("v", 3).put("steps", st).toString()
    }

    fun fromJson(s: String) {
        steps.clear()
        try {
            if (s.isNotBlank()) {
                val o = JSONObject(s)
                if (o.optInt("v") == 3) {
                    val st = o.getJSONObject("steps")
                    for (name in st.keys()) {
                        val step = runCatching { CalibStep.valueOf(name) }.getOrNull() ?: continue
                        val d = st.getJSONObject(name)
                        fun a(k: String, n: Int): FloatArray {
                            val ja = d.getJSONArray(k)
                            return FloatArray(n) { ja.getDouble(it).toFloat() }
                        }
                        steps[step] = StepData(
                            a("mean", ArKit.COUNT), a("upper", ArKit.COUNT), a("head", 6),
                            a("featMean", Feat.COUNT), a("featUpper", Feat.COUNT), a("featLower", Feat.COUNT),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            steps.clear()
        }
        recompute()
    }

    companion object {
        const val WARMUP_NS = 1_500_000_000L
        const val CAPTURE_NS = 1_500_000_000L
        private const val MIN_SPAN = 0.15f
        private const val AUTO_DECAY = 0.9995f
        private const val AUTO_MIN_RANGE = 0.6f
        private const val DEFAULT_PUFF_SPAN = 0.025f
        private const val IRIS_H_RANGE = 0.18f
        private const val IRIS_V_RANGE = 0.12f

        private fun mean(a: FloatArray) = if (a.isEmpty()) 0f else a.sum() / a.size
        private fun upperMean(a: FloatArray): Float {
            if (a.isEmpty()) return 0f
            val s = a.sortedArray(); return mean(s.copyOfRange(s.size / 2, s.size))
        }
        private fun lowerMean(a: FloatArray): Float {
            if (a.isEmpty()) return 0f
            val s = a.sortedArray(); return mean(s.copyOfRange(0, (s.size + 1) / 2))
        }
    }
}
