package com.avatarmocap.tracking

import com.avatarmocap.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pasos de la calibración: las expresiones básicas de un modelo VRM + extras.
 *  - [shapes]: blendshapes ARKit cuyo máximo se aprende en ese paso.
 *  - [viseme] / [emotion]: casilla VRM cuya "plantilla" se aprende (o -1).
 *  - [tip]: consejo de cómo posar.
 */
enum class CalibStep(
    private val titleEs: String,
    private val titleEn: String,
    private val instructionEs: String,
    private val instructionEn: String,
    private val tipEs: String,
    private val tipEn: String,
    val shapes: Array<String> = emptyArray(),
    val viseme: Int = -1,
    val emotion: Int = -1,
    /** false = solo de frente (girar la cabeza no aporta o es incómodo). */
    val multiAngle: Boolean = true,
) {
    NEUTRAL("Neutral", "Neutral",
        "Cara relajada, boca cerrada, mirando a la cámara.", "Relaxed face, mouth closed, looking at the camera.",
        "Como cuando mirás una pantalla sin pensar en nada.", "Like when you stare at a screen without thinking."),
    BLINK("Parpadeo", "Blink",
        "Cerrá los dos ojos suavemente.", "Gently close both eyes.",
        "Sin apretar los párpados. Cerralos apenas tocás Capturar y abrilos a los 4 segundos.",
        "Don't squeeze. Close them right after tapping Capture and open them after 4 seconds.",
        arrayOf("eyeBlinkLeft", "eyeBlinkRight"), multiAngle = false),
    A("A", "A", "Decí \"A\" con la boca bien abierta.", "Say \"A\" (ah) with your mouth wide open.",
        "Como en el dentista: mandíbula abajo, lengua quieta adentro.", "Like at the dentist: jaw down, tongue resting inside.",
        arrayOf("jawOpen", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthUpperUpLeft", "mouthUpperUpRight"), viseme = 0),
    I("I", "I", "Decí \"I\" estirando los labios a los costados.", "Say \"I\" (ee) stretching your lips sideways.",
        "Dientes casi juntos, como una sonrisa forzada.", "Teeth almost together, like a forced smile.",
        arrayOf("mouthStretchLeft", "mouthStretchRight"), viseme = 1),
    U("U", "U", "Decí \"U\" con los labios hacia adelante.", "Say \"U\" (oo) pushing your lips forward.",
        "Labios redondos y chiquitos, como para silbar.", "Small round lips, like whistling.", arrayOf("mouthPucker"), viseme = 2),
    E("E", "E", "Decí \"E\" y mantenela.", "Say \"E\" (eh) and hold it.",
        "Boca entreabierta y un poco estirada.", "Mouth half open and slightly stretched.", viseme = 3),
    O("O", "O", "Decí \"O\" con la boca redonda.", "Say \"O\" (oh) with a round mouth.",
        "Más abierta que la U, como un aro.", "More open than U, like a ring.", arrayOf("mouthFunnel"), viseme = 4),
    JOY("Alegría (Joy)", "Joy", "Sonreí bien grande.", "Give a big smile.",
        "Que se te achinen un poco los ojos, como una sonrisa real.", "Let your eyes narrow a bit, like a real smile.",
        arrayOf("mouthSmileLeft", "mouthSmileRight", "cheekSquintLeft", "cheekSquintRight", "mouthDimpleLeft", "mouthDimpleRight"),
        emotion = 0),
    ANGRY("Enojo (Angry)", "Angry", "Fruncí el ceño: cejas abajo y juntas.", "Frown: brows down and together.",
        "Podés arrugar un poco la nariz.", "You can wrinkle your nose a little.",
        arrayOf("browDownLeft", "browDownRight", "noseSneerLeft", "noseSneerRight"), emotion = 1),
    SORROW("Tristeza (Sorrow)", "Sorrow", "Cejas arriba en el centro y comisuras hacia abajo.",
        "Inner brows up and mouth corners down.",
        "Como un \"pobrecito\": el centro de las cejas sube.", "Like a sad puppy: the inner brows go up.",
        arrayOf("browInnerUp", "mouthFrownLeft", "mouthFrownRight"), emotion = 2),
    FUN("Diversión (Fun)", "Fun", "Sonrisa suave con la boca cerrada y ojos relajados.",
        "Soft closed-mouth smile with relaxed eyes.",
        "Más tranquila que Alegría: media sonrisa.", "Calmer than Joy: a half smile.", emotion = 3),
    SURPRISED("Sorpresa", "Surprised", "Cejas arriba y ojos bien abiertos.", "Brows up and eyes wide open.",
        "Sin abrir la boca (o apenas).", "Keep your mouth closed (or barely open).",
        arrayOf("eyeWideLeft", "eyeWideRight", "browOuterUpLeft", "browOuterUpRight"), emotion = 4),
    MOUTH_LEFT("Boca a tu izquierda", "Mouth to your left",
        "Mové la boca (labios juntos) hacia TU IZQUIERDA.", "Move your mouth (lips together) to YOUR LEFT.",
        "Solo los labios, sin girar la cabeza.", "Only your lips, don't turn your head.", arrayOf("mouthLeft"), multiAngle = false),
    MOUTH_RIGHT("Boca a tu derecha", "Mouth to your right",
        "Mové la boca (labios juntos) hacia TU DERECHA.", "Move your mouth (lips together) to YOUR RIGHT.",
        "Solo los labios, sin girar la cabeza.", "Only your lips, don't turn your head.", arrayOf("mouthRight"), multiAngle = false),
    PUFF("Cachetes inflados", "Puffed cheeks", "Inflá los cachetes con la boca cerrada.", "Puff your cheeks with your mouth closed.",
        "Como un pez globo, sin mover la boca a los costados.", "Like a pufferfish, without moving your mouth sideways.",
        arrayOf("cheekPuff")),
    TONGUE("Lengua afuera", "Tongue out", "Sacá la lengua hacia abajo lo más que puedas.",
        "Stick your tongue out and down as far as you can.",
        "Con luz de frente. La app aprende el color de tu lengua.", "With light in front. The app learns your tongue color.",
        multiAngle = false);

    val title: String get() = tr(titleEs, titleEn)
    val instruction: String get() = tr(instructionEs, instructionEn)
    val tip: String get() = tr(tipEs, tipEn)
    val indices: IntArray by lazy { IntArray(shapes.size) { ArKit.idx(shapes[it]) } }
}

/** Ángulos en los que se repite cada gesto. */
enum class Pose(private val es: String, private val en: String) {
    CENTER("Mirá DE FRENTE a la cámara", "Look STRAIGHT at the camera"),
    LEFT("Girá la cabeza hacia TU IZQUIERDA", "Turn your head to YOUR LEFT"),
    RIGHT("Girá la cabeza hacia TU DERECHA", "Turn your head to YOUR RIGHT"),
    UP("Levantá la cabeza: mirá hacia ARRIBA", "Tilt your head UP"),
    DOWN("Bajá la cabeza: mirá hacia ABAJO", "Tilt your head DOWN");

    val prompt: String get() = tr(es, en)
}

/** Lo capturado en un paso, en un ángulo. */
class BinData(
    val mean: FloatArray,      // 52 blendshapes crudos
    val upper: FloatArray,     // 52 (promedio de la mitad superior)
    val head: FloatArray,      // 6: pitch, yaw, roll (°), x, y, z (cm)
    val featMean: FloatArray,  // Feat.COUNT
    val featUpper: FloatArray,
)

/** Estado de una captura en curso, para mostrar en la UI. */
class CaptureStatus(val pose: Pose?, val phase: Float, val total: Float)

/**
 * Calibración por usuario, por ángulo de la cabeza.
 *
 * Cada gesto se graba de frente y girando la cabeza (izquierda, derecha, arriba, abajo) porque
 * MediaPipe da valores distintos según el ángulo: por ejemplo, mirando hacia abajo los ojos
 * parecen más cerrados. En tiempo real se interpola entre los ángulos grabados según hacia
 * dónde esté mirando tu cabeza.
 */
class Calibration {
    private val steps = java.util.concurrent.ConcurrentHashMap<CalibStep, EnumMap<Pose, BinData>>()
    val done: Set<CalibStep> get() = steps.keys.toSet()

    @Volatile var quickMode = false

    // ---- Valores ACTUALES (interpolados según la pose de la cabeza), leídos por el pipeline
    val neutral = FloatArray(ArKit.COUNT)
    val neutralHead = FloatArray(6)
    val maxV = FloatArray(ArKit.COUNT) { Float.NaN }
    val featNeutral = FloatArray(Feat.COUNT) { Float.NaN }
    val earClosed = floatArrayOf(Float.NaN, Float.NaN)
    @Volatile var gapMax = Float.NaN
    val puffMax = floatArrayOf(Float.NaN, Float.NaN)
    @Volatile var tongueF0 = 0.05f
    @Volatile var tongueF1 = 0.35f
    /** Color aprendido de la lengua (Cr, Cb, Y relativos a la piel) o NaN. */
    val tongueColor = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    /** Color aprendido del labio inferior (en "Neutral") o NaN. */
    val lipColor = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    /** Cuánto se mueve TU boca a cada lado (unidad de Feat.MOUTH_X). */
    @Volatile var mouthSpanL = DEFAULT_MOUTH_SPAN
    @Volatile var mouthSpanR = DEFAULT_MOUTH_SPAN
    val visemeT = Array(5) { FloatArray(ArKit.COUNT) }
    val emotionT = Array(5) { FloatArray(ArKit.COUNT) }
    val hasViseme = BooleanArray(5)
    val hasEmotion = BooleanArray(5)
    @Volatile var hasNeutral = false
        private set

    // ---- Tablas por ángulo (derivadas en recompute)
    private class PoseTable {
        val neutral = FloatArray(ArKit.COUNT)
        val featNeutral = FloatArray(Feat.COUNT) { Float.NaN }
        val maxV = FloatArray(ArKit.COUNT) { Float.NaN }
        val earClosed = floatArrayOf(Float.NaN, Float.NaN)
        var gapMax = Float.NaN
        val puffMax = floatArrayOf(Float.NaN, Float.NaN)
        val visemeT = Array(5) { FloatArray(ArKit.COUNT) }
        val emotionT = Array(5) { FloatArray(ArKit.COUNT) }
        var head: FloatArray? = null
    }
    private val tables = EnumMap<Pose, PoseTable>(Pose::class.java)

    private val autoMax = FloatArray(ArKit.COUNT)

    // ---- Ajustes manuales por gesto (NaN = automático). Se guardan aparte de la calibración.
    val ovMin = FloatArray(ArKit.COUNT) { Float.NaN }
    val ovMax = FloatArray(ArKit.COUNT) { Float.NaN }
    val ovOut = FloatArray(ArKit.COUNT) { Float.NaN }

    fun outMax(i: Int): Float = if (ovOut[i].isNaN()) ArKit.DEFAULT_OUT[i] else ovOut[i]
    /** Mínimo efectivo (reposo) del gesto ahora mismo. */
    fun effMin(i: Int): Float = if (!ovMin[i].isNaN()) ovMin[i] else if (hasNeutral) min(neutral[i], 0.8f) else 0f
    /** Máximo efectivo del gesto ahora mismo (NaN = sin calibrar). */
    fun effMax(i: Int): Float = if (!ovMax[i].isNaN()) ovMax[i] else maxV[i]

    fun overridesJson(): String {
        fun arr(a: FloatArray) = JSONArray().apply { a.forEach { put(if (it.isNaN()) -999.0 else it.toDouble()) } }
        return JSONObject().put("min", arr(ovMin)).put("max", arr(ovMax)).put("out", arr(ovOut)).toString()
    }

    fun overridesFromJson(s: String) {
        ovMin.fill(Float.NaN); ovMax.fill(Float.NaN); ovOut.fill(Float.NaN)
        if (s.isBlank()) return
        try {
            val o = JSONObject(s)
            for ((k, a) in listOf("min" to ovMin, "max" to ovMax, "out" to ovOut)) {
                val ja = o.optJSONArray(k) ?: continue
                for (i in 0 until minOf(ja.length(), a.size)) {
                    val v = ja.getDouble(i).toFloat(); a[i] = if (v <= -998f) Float.NaN else v
                }
            }
        } catch (e: Exception) { /* se ignoran ajustes corruptos */ }
    }

    /** Puntos rígidos de la cara neutral (para la pose de cabeza sin influencia de la boca). */
    @Volatile var rigidRef: FloatArray? = null
        private set
    private val rigidSum = FloatArray(RigidHead.COUNT * 3)
    private var rigidN = 0

    // ---------- Captura guiada

    @Volatile var capturing: CalibStep? = null
        private set
    private var capStart = 0L
    private var schedule: List<Pair<Pose, Long>> = emptyList()
    private val samples = EnumMap<Pose, ArrayList<FloatArray>>(Pose::class.java)
    var onStepDone: ((CalibStep, Boolean, String) -> Unit)? = null

    @Synchronized
    fun startCapture(step: CalibStep, nowNanos: Long) {
        samples.clear()
        rigidSum.fill(0f); rigidN = 0
        val poses = if (quickMode || !step.multiAngle) listOf(Pose.CENTER) else Pose.entries
        schedule = poses.map { it to (if (it == Pose.CENTER) CENTER_NS else POSE_NS) }
        capStart = nowNanos
        capturing = step
    }

    /** Estado actual de la captura (o null). */
    fun captureStatus(nowNanos: Long): CaptureStatus? {
        if (capturing == null) return null
        val total = PREP_NS + schedule.sumOf { it.second }
        var t = nowNanos - capStart
        val totalP = (t.toFloat() / total).coerceIn(0f, 1f)
        if (t < PREP_NS) return CaptureStatus(null, t.toFloat() / PREP_NS, totalP)
        t -= PREP_NS
        for ((pose, dur) in schedule) {
            if (t < dur) return CaptureStatus(pose, t.toFloat() / dur, totalP)
            t -= dur
        }
        return CaptureStatus(Pose.CENTER, 1f, 1f)
    }

    @Synchronized
    fun feed(raw: FloatArray, head: FloatArray, feat: FloatArray, rigid: FloatArray, nowNanos: Long) {
        val step = capturing ?: return
        var t = nowNanos - capStart - PREP_NS
        if (t < 0) return
        for ((pose, dur) in schedule) {
            if (t < dur) {
                // Se descarta el comienzo de cada ángulo: es el momento en que girás la cabeza
                if (t >= TRANSITION_NS) {
                    val s = FloatArray(ArKit.COUNT + 6 + Feat.COUNT)
                    System.arraycopy(raw, 0, s, 0, ArKit.COUNT)
                    System.arraycopy(head, 0, s, ArKit.COUNT, 6)
                    System.arraycopy(feat, 0, s, ArKit.COUNT + 6, Feat.COUNT)
                    samples.getOrPut(pose) { ArrayList() }.add(s)
                    if (step == CalibStep.NEUTRAL && pose == Pose.CENTER) {
                        for (i in rigidSum.indices) rigidSum[i] += rigid[i]
                        rigidN++
                    }
                }
                return
            }
            t -= dur
        }
        capturing = null
        finish(step)
    }

    private fun finish(step: CalibStep) {
        val bins = EnumMap<Pose, BinData>(Pose::class.java)
        for ((pose, list) in samples) {
            if (list.size < 3) continue
            fun col(c: Int) = FloatArray(list.size) { list[it][c] }
            val fo = ArKit.COUNT + 6
            bins[pose] = BinData(
                mean = FloatArray(ArKit.COUNT) { mean(col(it)) },
                upper = FloatArray(ArKit.COUNT) { upperMean(col(it)) },
                head = FloatArray(6) { mean(col(ArKit.COUNT + it)) },
                featMean = FloatArray(Feat.COUNT) { meanFinite(col(fo + it)) },
                featUpper = FloatArray(Feat.COUNT) { upperMean(col(fo + it)) },
            )
        }
        val center = bins[Pose.CENTER]
        if (center == null) {
            onStepDone?.invoke(step, false, tr("No se vio tu cara de frente. Repetí ${step.title}.", "Your face wasn't seen from the front. Repeat ${step.title}."))
            return
        }
        // Ángulos en los que no giraste lo suficiente: se descartan (se usa el de frente)
        val c = center.head
        var weakPoses = 0
        for (pose in listOf(Pose.LEFT, Pose.RIGHT, Pose.UP, Pose.DOWN)) {
            val b = bins[pose] ?: continue
            val moved = if (pose == Pose.LEFT || pose == Pose.RIGHT) abs(b.head[1] - c[1]) else abs(b.head[0] - c[0])
            if (moved < MIN_TURN_DEG) { bins.remove(pose); weakPoses++ }
        }
        val (ok, msg) = validate(step, center)
        if (ok) {
            steps[step] = bins
            if (step == CalibStep.NEUTRAL && rigidN > 0) rigidRef = FloatArray(rigidSum.size) { rigidSum[it] / rigidN }
            recompute()
        }
        val extra = if (ok && weakPoses > 0) tr(" (en $weakPoses ángulo(s) casi no giraste la cabeza; se usó el de frente)", " (you barely turned your head in $weakPoses angle(s); the front one was used)") else ""
        onStepDone?.invoke(step, ok, msg + extra)
    }

    private fun validate(step: CalibStep, d: BinData): Pair<Boolean, String> {
        if (step == CalibStep.NEUTRAL) return true to "${step.title} ✓"
        val n = steps[CalibStep.NEUTRAL]?.get(Pose.CENTER) ?: return false to tr("Primero calibrá \"Neutral\".", "Calibrate \"Neutral\" first.")
        when (step) {
            CalibStep.BLINK -> {
                val dl = n.featMean[Feat.EAR_L] - d.featMean[Feat.EAR_L]
                val dr = n.featMean[Feat.EAR_R] - d.featMean[Feat.EAR_R]
                if (dl < 0.03f || dr < 0.03f) return false to tr("No se detectaron los ojos cerrados. Repetí con más luz.", "Closed eyes weren't detected. Repeat with more light.")
            }
            CalibStep.TONGUE -> {
                val f0 = steps[CalibStep.A]?.get(Pose.CENTER)?.featMean?.get(Feat.TONGUE) ?: tongueF0
                if (d.featUpper[Feat.TONGUE] - f0 < 0.05f || d.featMean[Feat.TONGUE_CR].isNaN()) {
                    return false to tr("Casi no se distingue la lengua. Sacala más, hacia abajo, con luz de frente.", "The tongue is hard to see. Stick it out further, downward, with light in front.")
                }
            }
            CalibStep.MOUTH_LEFT, CalibStep.MOUTH_RIGHT -> {
                val dx = d.featMean[Feat.MOUTH_X] - n.featMean[Feat.MOUTH_X]
                val okDir = if (step == CalibStep.MOUTH_LEFT) dx > 0.015f else dx < -0.015f
                if (dx.isNaN() || !okDir) {
                    return false to tr("No se notó la boca moviéndose a ese lado. Movela más (sin girar la cabeza).", "The mouth didn't move to that side. Move it more (without turning your head).")
                }
            }
            CalibStep.PUFF -> {
                val pl = d.featUpper[Feat.PUFF_L] - n.featMean[Feat.PUFF_L]
                val pr = d.featUpper[Feat.PUFF_R] - n.featMean[Feat.PUFF_R]
                if (min(pl, pr) < 0.004f && d.upper[ArKit.CHEEK_PUFF] - n.mean[ArKit.CHEEK_PUFF] < 0.1f) {
                    return false to tr("No se notó el inflado. Inflá más los cachetes.", "No puff detected. Puff your cheeks more.")
                }
            }
            else -> {
                var diff = 0f
                for (i in 0 until ArKit.COUNT) diff += max(0f, d.mean[i] - n.mean[i])
                if (diff < 0.25f) return false to tr("${step.title}: el gesto casi no cambió. Exageralo un poco más.", "${step.title}: the expression barely changed. Exaggerate it a bit more.")
            }
        }
        return true to "${step.title} ✓"
    }

    /** Recalcula las tablas por ángulo a partir de lo grabado. */
    @Synchronized
    fun recompute() {
        tables.clear()
        val neutralBins = steps[CalibStep.NEUTRAL]
        hasNeutral = neutralBins?.get(Pose.CENTER) != null
        for (pose in Pose.entries) {
            val tb = PoseTable()
            fun bin(step: CalibStep): BinData? = steps[step]?.let { it[pose] ?: it[Pose.CENTER] }
            val nb = bin(CalibStep.NEUTRAL)
            if (nb != null) {
                System.arraycopy(nb.mean, 0, tb.neutral, 0, ArKit.COUNT)
                System.arraycopy(nb.featMean, 0, tb.featNeutral, 0, Feat.COUNT)
            }
            tb.head = neutralBins?.get(pose)?.head
            for ((step, _) in steps) {
                val b = bin(step) ?: continue
                for (i in step.indices) {
                    val v = max(b.upper[i], tb.neutral[i] + MIN_SPAN)
                    tb.maxV[i] = if (tb.maxV[i].isNaN()) v else max(tb.maxV[i], v)
                }
                if (step.viseme >= 0 || step.emotion >= 0) {
                    val t = if (step.viseme >= 0) tb.visemeT[step.viseme] else tb.emotionT[step.emotion]
                    for (i in 0 until ArKit.COUNT) t[i] = max(0f, b.mean[i] - tb.neutral[i])
                }
            }
            bin(CalibStep.BLINK)?.let { tb.earClosed[0] = it.featMean[Feat.EAR_L]; tb.earClosed[1] = it.featMean[Feat.EAR_R] }
            bin(CalibStep.A)?.let { tb.gapMax = it.featUpper[Feat.GAP] }
            bin(CalibStep.PUFF)?.let { tb.puffMax[0] = it.featUpper[Feat.PUFF_L]; tb.puffMax[1] = it.featUpper[Feat.PUFF_R] }
            tables[pose] = tb
        }
        for (k in 0 until 5) {
            hasViseme[k] = steps.keys.any { it.viseme == k }
            hasEmotion[k] = steps.keys.any { it.emotion == k }
        }
        tongueF0 = steps[CalibStep.A]?.get(Pose.CENTER)?.featMean?.get(Feat.TONGUE)?.takeIf { !it.isNaN() } ?: 0.05f
        val tb = steps[CalibStep.TONGUE]?.get(Pose.CENTER)
        tongueF1 = tb?.featUpper?.get(Feat.TONGUE)?.takeIf { !it.isNaN() } ?: max(0.35f, tongueF0 + 0.2f)
        val nc = neutralBins?.get(Pose.CENTER)
        lipColor[0] = nc?.featMean?.get(Feat.LIP_CR) ?: Float.NaN
        lipColor[1] = nc?.featMean?.get(Feat.LIP_CB) ?: Float.NaN
        lipColor[2] = nc?.featMean?.get(Feat.LIP_Y) ?: Float.NaN
        val n0 = nc?.featMean?.get(Feat.MOUTH_X)
        val ml = steps[CalibStep.MOUTH_LEFT]?.get(Pose.CENTER)?.featMean?.get(Feat.MOUTH_X)
        val mr = steps[CalibStep.MOUTH_RIGHT]?.get(Pose.CENTER)?.featMean?.get(Feat.MOUTH_X)
        mouthSpanL = if (n0 != null && ml != null && !ml.isNaN() && !n0.isNaN()) max(ml - n0, 0.01f) else DEFAULT_MOUTH_SPAN
        mouthSpanR = if (n0 != null && mr != null && !mr.isNaN() && !n0.isNaN()) max(n0 - mr, 0.01f) else DEFAULT_MOUTH_SPAN
        tongueColor[0] = tb?.featMean?.get(Feat.TONGUE_CR) ?: Float.NaN
        tongueColor[1] = tb?.featMean?.get(Feat.TONGUE_CB) ?: Float.NaN
        tongueColor[2] = tb?.featMean?.get(Feat.TONGUE_Y) ?: Float.NaN
        val ch = neutralBins?.get(Pose.CENTER)?.head
        if (ch != null) System.arraycopy(ch, 0, neutralHead, 0, 6) else neutralHead.fill(0f)
        autoMax.fill(0f)
        updatePose(0f, 0f, force = true)
    }

    private var lastPitch = Float.NaN
    private var lastYaw = Float.NaN

    /**
     * Interpola las tablas según la pose ACTUAL de la cabeza (grados absolutos de MediaPipe):
     * valor = frente + a·(lateral − frente) + b·(vertical − frente).
     */
    @Synchronized
    fun updatePose(pitch: Float, yaw: Float, force: Boolean = false) {
        if (!force && abs(pitch - lastPitch) < 0.5f && abs(yaw - lastYaw) < 0.5f) return
        lastPitch = pitch; lastYaw = yaw
        val c = tables[Pose.CENTER] ?: return
        val ch = c.head
        var side: PoseTable? = null; var a = 0f
        var vert: PoseTable? = null; var b = 0f
        if (ch != null) {
            for (pose in listOf(Pose.LEFT, Pose.RIGHT)) {
                val t = tables[pose] ?: continue
                val h = t.head ?: continue
                val span = h[1] - ch[1]
                if (abs(span) < MIN_TURN_DEG) continue
                val f = (yaw - ch[1]) / span
                if (f > 0f) { side = t; a = f.coerceAtMost(1f) }
            }
            for (pose in listOf(Pose.UP, Pose.DOWN)) {
                val t = tables[pose] ?: continue
                val h = t.head ?: continue
                val span = h[0] - ch[0]
                if (abs(span) < MIN_TURN_DEG) continue
                val f = (pitch - ch[0]) / span
                if (f > 0f) { vert = t; b = f.coerceAtMost(1f) }
            }
        }
        fun mix(cv: Float, sv: Float?, vv: Float?): Float {
            var r = cv
            if (cv.isNaN()) return cv
            if (sv != null && !sv.isNaN()) r += a * (sv - cv)
            if (vv != null && !vv.isNaN()) r += b * (vv - cv)
            return r
        }
        for (i in 0 until ArKit.COUNT) {
            neutral[i] = mix(c.neutral[i], side?.neutral?.get(i), vert?.neutral?.get(i))
            maxV[i] = mix(c.maxV[i], side?.maxV?.get(i), vert?.maxV?.get(i))
        }
        for (i in 0 until Feat.COUNT) featNeutral[i] = mix(c.featNeutral[i], side?.featNeutral?.get(i), vert?.featNeutral?.get(i))
        for (k in 0..1) {
            earClosed[k] = mix(c.earClosed[k], side?.earClosed?.get(k), vert?.earClosed?.get(k))
            puffMax[k] = mix(c.puffMax[k], side?.puffMax?.get(k), vert?.puffMax?.get(k))
        }
        gapMax = mix(c.gapMax, side?.gapMax, vert?.gapMax)
        for (k in 0 until 5) for (i in 0 until ArKit.COUNT) {
            visemeT[k][i] = mix(c.visemeT[k][i], side?.visemeT?.get(k)?.get(i), vert?.visemeT?.get(k)?.get(i))
            emotionT[k][i] = mix(c.emotionT[k][i], side?.emotionT?.get(k)?.get(i), vert?.emotionT?.get(k)?.get(i))
        }
    }

    // ---------- Aplicación en tiempo real

    /** Re-escala cada blendshape entre su neutral y su máximo calibrado (0..1). */
    @Synchronized
    fun apply(raw: FloatArray, out: FloatArray, autoRange: Boolean) {
        for (i in 0 until ArKit.COUNT) {
            if (i == ArKit.TONGUE_OUT) { out[i] = raw[i]; continue }
            val n = effMin(i)
            val mx = effMax(i)
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

    fun blinkGeo(k: Int, ear: Float): Float {
        val open = featNeutral[if (k == 0) Feat.EAR_L else Feat.EAR_R]
        val closed = earClosed[k]
        if (open.isNaN() || closed.isNaN()) return Float.NaN
        return ((open - ear) / max(open - closed, 0.02f)).coerceIn(0f, 1f)
    }

    fun jawGeo(gap: Float): Float {
        val n = featNeutral[Feat.GAP]
        if (n.isNaN() || gapMax.isNaN()) return Float.NaN
        return ((gap - n) / max(gapMax - n, 0.02f)).coerceIn(0f, 1f)
    }

    fun gaze(feat: FloatArray, left: Boolean, out: FloatArray): Boolean {
        val hi = if (left) Feat.IRIS_HL else Feat.IRIS_HR
        val vi = if (left) Feat.IRIS_VL else Feat.IRIS_VR
        val h0 = featNeutral[hi]; val v0 = featNeutral[vi]
        if (h0.isNaN() || v0.isNaN()) return false
        out[0] = ((feat[hi] - h0) / IRIS_H_RANGE).coerceIn(-1f, 1f)
        out[1] = ((feat[vi] - v0) / IRIS_V_RANGE).coerceIn(-1f, 1f)
        return true
    }

    /** Boca a los costados desde la malla: out = (izquierda, derecha) 0..1. */
    fun mouthSide(feat: FloatArray, out: FloatArray): Boolean {
        val n = featNeutral[Feat.MOUTH_X]
        if (n.isNaN() || feat[Feat.MOUTH_X].isNaN()) return false
        val dx = feat[Feat.MOUTH_X] - n
        // Pequeña zona muerta (el ruido de la malla) y luego lineal hasta tu máximo
        out[0] = ((dx - MOUTH_DEAD) / (mouthSpanL - MOUTH_DEAD).coerceAtLeast(0.005f)).coerceIn(0f, 1f)
        out[1] = ((-dx - MOUTH_DEAD) / (mouthSpanR - MOUTH_DEAD).coerceAtLeast(0.005f)).coerceIn(0f, 1f)
        return true
    }

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
        rigidRef = null
        recompute()
    }

    // ---------- Persistencia

    fun toJson(): String {
        fun arr(a: FloatArray) = JSONArray().apply { a.forEach { put(if (it.isNaN()) -999.0 else it.toDouble()) } }
        val st = JSONObject()
        for ((step, bins) in steps) {
            val bo = JSONObject()
            for ((pose, d) in bins) {
                bo.put(pose.name, JSONObject()
                    .put("mean", arr(d.mean)).put("upper", arr(d.upper)).put("head", arr(d.head))
                    .put("featMean", arr(d.featMean)).put("featUpper", arr(d.featUpper)))
            }
            st.put(step.name, bo)
        }
        val o = JSONObject().put("v", 4).put("steps", st)
        rigidRef?.let { o.put("rigidRef", arr(it)) }
        return o.toString()
    }

    fun fromJson(s: String) {
        steps.clear()
        try {
            if (s.isNotBlank()) {
                val o = JSONObject(s)
                if (o.optInt("v") == 4) {
                    o.optJSONArray("rigidRef")?.let { ja ->
                        if (ja.length() == RigidHead.COUNT * 3) rigidRef = FloatArray(ja.length()) { ja.getDouble(it).toFloat() }
                    }
                    val st = o.getJSONObject("steps")
                    for (name in st.keys()) {
                        val step = runCatching { CalibStep.valueOf(name) }.getOrNull() ?: continue
                        val bo = st.getJSONObject(name)
                        val bins = EnumMap<Pose, BinData>(Pose::class.java)
                        for (pn in bo.keys()) {
                            val pose = runCatching { Pose.valueOf(pn) }.getOrNull() ?: continue
                            val d = bo.getJSONObject(pn)
                            fun a(k: String, n: Int): FloatArray {
                                val ja = d.getJSONArray(k)
                                return FloatArray(n) { i ->
                                    if (i < ja.length()) ja.getDouble(i).toFloat().let { if (it <= -998f) Float.NaN else it }
                                    else Float.NaN
                                }
                            }
                            bins[pose] = BinData(
                                a("mean", ArKit.COUNT), a("upper", ArKit.COUNT), a("head", 6),
                                a("featMean", Feat.COUNT), a("featUpper", Feat.COUNT),
                            )
                        }
                        if (bins[Pose.CENTER] != null) steps[step] = bins
                    }
                }
            }
        } catch (e: Exception) {
            steps.clear()
        }
        recompute()
    }

    companion object {
        const val PREP_NS = 2_000_000_000L
        const val CENTER_NS = 1_800_000_000L
        const val POSE_NS = 1_800_000_000L
        const val TRANSITION_NS = 700_000_000L
        private const val MIN_TURN_DEG = 8f
        private const val MIN_SPAN = 0.15f
        private const val AUTO_DECAY = 0.9995f
        private const val AUTO_MIN_RANGE = 0.6f
        private const val DEFAULT_PUFF_SPAN = 0.025f
        private const val DEFAULT_MOUTH_SPAN = 0.08f
        private const val MOUTH_DEAD = 0.006f
        private const val IRIS_H_RANGE = 0.18f
        private const val IRIS_V_RANGE = 0.12f

        private fun mean(a: FloatArray) = if (a.isEmpty()) 0f else a.sum() / a.size
        /** Promedio ignorando NaN (p.ej. color de lengua cuando no hay lengua). */
        private fun meanFinite(a: FloatArray): Float {
            var s = 0f; var n = 0
            for (v in a) if (!v.isNaN()) { s += v; n++ }
            return if (n == 0) Float.NaN else s / n
        }
        private fun upperMean(a: FloatArray): Float {
            val f = a.filter { !it.isNaN() }.sorted()
            if (f.isEmpty()) return Float.NaN
            return f.subList(f.size / 2, f.size).average().toFloat()
        }
    }
}
