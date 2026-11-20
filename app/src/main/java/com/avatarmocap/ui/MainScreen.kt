package com.avatarmocap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avatarmocap.TrackingViewModel
import com.avatarmocap.UiState
import com.avatarmocap.i18n.tr
import com.avatarmocap.output.IFacialMocapServer
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.CalibStep
import com.avatarmocap.tracking.FaceGeometry
import com.avatarmocap.tracking.Pose
import com.avatarmocap.tracking.PreviewFrame
import com.avatarmocap.tracking.Vrm
import kotlin.math.max

/** Grupos del editor de gestos. */
private val GESTURE_GROUPS: List<Pair<() -> String, List<String>>> = listOf(
    { tr("Ojos", "Eyes") } to listOf(
        "eyeBlinkLeft", "eyeBlinkRight", "eyeWideLeft", "eyeWideRight", "eyeSquintLeft", "eyeSquintRight",
    ),
    { tr("Boca", "Mouth") } to listOf(
        "jawOpen", "mouthClose", "mouthFunnel", "mouthPucker", "mouthLeft", "mouthRight",
        "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
        "mouthStretchLeft", "mouthStretchRight", "mouthLowerDownLeft", "mouthLowerDownRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "mouthRollLower", "mouthRollUpper",
        "mouthShrugLower", "mouthShrugUpper", "mouthPressLeft", "mouthPressRight",
        "mouthDimpleLeft", "mouthDimpleRight", "jawLeft", "jawRight", "jawForward",
    ),
    { tr("Cejas", "Brows") } to listOf("browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight"),
    { tr("Mejillas y nariz", "Cheeks & nose") } to listOf(
        "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "noseSneerLeft", "noseSneerRight",
    ),
    { tr("Mirada", "Gaze") } to listOf(
        "eyeLookUpLeft", "eyeLookDownLeft", "eyeLookInLeft", "eyeLookOutLeft",
        "eyeLookUpRight", "eyeLookDownRight", "eyeLookInRight", "eyeLookOutRight",
    ),
    { tr("Lengua", "Tongue") } to listOf("tongueOut"),
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: TrackingViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(s.resolution) { vm.startCamera(owner) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        // ---------- Vista: la MISMA imagen que se analiza, con su malla encima
        item {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(if (s.showPreview || ui.capture != null) 300.dp else 100.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF111111))
                ) {
                    if (s.showPreview) LivePreview(vm, s.meshMode)
                    StatusOverlay(ui, Modifier.align(Alignment.TopStart).padding(8.dp))
                    if (ui.capture != null) CaptureOverlay(ui, Modifier.align(Alignment.BottomCenter))
                }
                if (ui.message.isNotEmpty()) {
                    Text(ui.message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // ---------- Idioma
        item {
            Section(tr("Idioma", "Language")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Automático", "Automatic"), "Español", "English").forEachIndexed { i, label ->
                        FilterChip(s.language == i, { vm.update { it.copy(language = i) } }, { Text(label) })
                    }
                }
            }
        }

        // ---------- Calibración guiada
        item { CalibrationCard(ui, vm, s.quickCalibration) }

        // ---------- Perfiles
        item { ProfilesCard(ui, vm) }

        // ---------- Editor por gesto
        item { GestureEditor(ui, vm) }

        // ---------- Boca
        item {
            Section(tr("Boca", "Mouth")) {
                Text(tr("Límite de apertura: %.0f%%", "Opening limit: %.0f%%").format(s.mouthLimit * 100), fontSize = 13.sp)
                Slider(s.mouthLimit, { v -> vm.update { it.copy(mouthLimit = v) } }, valueRange = 0.4f..1.3f)
                ToggleRow(tr("Boca estable (filtra gestos con ruido)", "Stable mouth (filters noisy shapes)"), s.mouthStable) { v ->
                    vm.update { it.copy(mouthStable = v) }
                }
                Text(tr("Simetría: %.0f%%", "Symmetry: %.0f%%").format(s.mouthSymmetry * 100), fontSize = 13.sp)
                Slider(s.mouthSymmetry, { v -> vm.update { it.copy(mouthSymmetry = v) } })
                Text(tr("Mover la boca a los costados: %.0f%%", "Mouth sideways: %.0f%%").format(s.mouthSide * 100), fontSize = 13.sp)
                Slider(s.mouthSide, { v -> vm.update { it.copy(mouthSide = v) } })
                Text(
                    tr(
                        "Si la boca del avatar atraviesa la cara, bajá el límite de apertura. Si se ve torcida, subí la simetría.",
                        "If the avatar's mouth clips through the face, lower the opening limit. If it looks crooked, raise symmetry.",
                    ),
                    fontSize = 12.sp,
                )
            }
        }

        // ---------- Ajuste fino por grupo
        item {
            Section(tr("Intensidad por grupo", "Intensity per group")) {
                GainSlider(tr("Ojos (parpadeo)", "Eyes (blink)"), s.gainEyes) { v -> vm.update { it.copy(gainEyes = v) } }
                GainSlider(tr("Cejas", "Brows"), s.gainBrows) { v -> vm.update { it.copy(gainBrows = v) } }
                GainSlider(tr("Boca abierta", "Mouth open"), s.gainMouthOpen) { v -> vm.update { it.copy(gainMouthOpen = v) } }
                GainSlider(tr("Sonrisa", "Smile"), s.gainSmile) { v -> vm.update { it.copy(gainSmile = v) } }
                GainSlider(tr("Cachetes inflados", "Cheek puff"), s.gainCheeks) { v -> vm.update { it.copy(gainCheeks = v) } }
                GainSlider(tr("Lengua", "Tongue"), s.gainTongue) { v -> vm.update { it.copy(gainTongue = v) } }
                GainSlider(tr("Emociones VRM (Joy, Angry…)", "VRM emotions (Joy, Angry…)"), s.gainEmotions) { v ->
                    vm.update { it.copy(gainEmotions = v) }
                }
                Text(tr("Suavizado: %.0f%%", "Smoothing: %.0f%%").format(s.smoothing * 100), fontSize = 13.sp)
                Slider(s.smoothing, { v -> vm.update { it.copy(smoothing = v) } })
                ToggleRow(
                    tr("Precisión mejorada (parpadeo, boca y mirada desde la malla)", "Enhanced precision (blink, mouth and gaze from the mesh)"),
                    s.precise,
                ) { v -> vm.update { it.copy(precise = v) } }
                ToggleRow(tr("Auto-rango para gestos sin calibrar", "Auto-range for uncalibrated shapes"), s.autoRange) { v ->
                    vm.update { it.copy(autoRange = v) }
                }
            }
        }

        // ---------- Lengua
        item {
            Section(tr("Lengua", "Tongue")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("tongueOut %.2f".format(ui.tongueValue))
                        LinearProgressIndicator(
                            progress = { ui.tongueValue },
                            modifier = Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp),
                        )
                        Text(tr("señal cruda %.2f", "raw signal %.2f").format(ui.tongueFeature), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            if (CalibStep.TONGUE in ui.calibratedSteps) tr("Color de tu lengua aprendido ✓", "Tongue color learned ✓")
                            else tr("Calibrá \"Lengua afuera\" para que aprenda el color de tu lengua.", "Calibrate \"Tongue out\" so it learns your tongue color."),
                            fontSize = 12.sp,
                        )
                    }
                    ui.tongueDebug?.let {
                        Spacer(Modifier.width(8.dp))
                        Image(it, null, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                    }
                }
                Text(tr("En la cámara, la lengua detectada se marca con un contorno verde.", "On the camera, the detected tongue is outlined in green."), fontSize = 12.sp)
                ToggleRow(tr("Ver recorte de depuración", "Show debug crop"), s.showTongueDebug) { v -> vm.update { it.copy(showTongueDebug = v) } }
            }
        }

        // ---------- Expresiones VRM en vivo
        item {
            Section(tr("Expresiones VRM", "VRM expressions")) {
                for (i in 0 until Vrm.COUNT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Vrm.LABELS[i], Modifier.width(110.dp), fontSize = 12.sp)
                        LinearProgressIndicator(progress = { ui.vrm[i] }, modifier = Modifier.weight(1f).height(8.dp))
                    }
                }
            }
        }

        // ---------- Rendimiento
        item {
            Section(tr("Rendimiento", "Performance")) {
                Text(tr("Resolución de análisis (menos = más fps)", "Analysis resolution (lower = more fps)"), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Baja", "Low"), tr("Media", "Medium"), tr("Alta", "High")).forEachIndexed { i, label ->
                        FilterChip(s.resolution == i, { vm.update { it.copy(resolution = i) } }, { Text(label) })
                    }
                }
                ToggleRow(tr("Envío a 60 fps (interpola entre cuadros)", "Send at 60 fps (interpolates between frames)"), s.interpolate) { v ->
                    vm.update { it.copy(interpolate = v) }
                }
                ToggleRow(tr("Mostrar cámara (apagarla da más fps)", "Show camera (turning it off gives more fps)"), s.showPreview) { v ->
                    vm.update { it.copy(showPreview = v) }
                }
                Text(tr("Sobre la cara", "Face overlay"), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Nada", "None"), tr("Puntos", "Points"), tr("Malla", "Mesh")).forEachIndexed { i, label ->
                        FilterChip(s.meshMode == i, { vm.update { it.copy(meshMode = i) } }, { Text(label) })
                    }
                }
                Text(tr("Procesador (se aplica al reiniciar la app)", "Processor (applies after restarting the app)"), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Automático", "Automatic"), "GPU", "CPU").forEachIndexed { i, label ->
                        FilterChip(s.accel == i, { vm.update { it.copy(accel = i) } }, { Text(label) })
                    }
                }
            }
        }

        // ---------- VMC
        item {
            Section(tr("Salida VMC (expresiones VRM)", "VMC output (VRM expressions)")) {
                ToggleRow(tr("Activada", "Enabled"), s.vmcEnabled) { v -> vm.update { it.copy(vmcEnabled = v) } }
                Text(
                    tr(
                        "Envía A, I, U, E, O, parpadeo, emociones y TongueOut a los clips del modelo VRM. " +
                            "En VSeeFace: Configuración general → receptor del protocolo VMC, puerto 39539.",
                        "Sends A, I, U, E, O, blink, emotions and TongueOut to the VRM model's clips. " +
                            "In VSeeFace: General settings → VMC protocol receiver, port 39539.",
                    ),
                    fontSize = 12.sp,
                )
                EditableField(tr("IP de la PC (vacío = la de iFacialMocap)", "PC IP (empty = the iFacialMocap one)"), s.vmcHost) { v ->
                    vm.update { it.copy(vmcHost = v) }
                }
                EditableField(tr("Puerto", "Port"), s.vmcPort.toString(), numeric = true) { v ->
                    v.toIntOrNull()?.let { p -> vm.update { it.copy(vmcPort = p) } }
                }
                ToggleRow(tr("Nombres VRM 1.0 (aa, blink, happy…)", "VRM 1.0 names (aa, blink, happy…)"), s.vmcVrm1) { v -> vm.update { it.copy(vmcVrm1 = v) } }
                ToggleRow(tr("Enviar también ARKit (modelos perfect sync)", "Also send ARKit (perfect sync models)"), s.vmcArkit) { v ->
                    vm.update { it.copy(vmcArkit = v) }
                }
                ToggleRow(tr("Enviar cabeza y ojos (huesos)", "Send head and eyes (bones)"), s.vmcBones) { v -> vm.update { it.copy(vmcBones = v) } }
                StatusText(ui.vmcStatus)
            }
        }

        // ---------- iFacialMocap
        item {
            Section(tr("Salida iFacialMocap (VSeeFace, VNyan, Warudo…)", "iFacialMocap output (VSeeFace, VNyan, Warudo…)")) {
                ToggleRow(tr("Activada", "Enabled"), s.ifmEnabled) { v -> vm.update { it.copy(ifmEnabled = v) } }
                Text(
                    tr("IP de este teléfono: ", "This phone's IP: ") +
                        ui.phoneIps.joinToString().ifEmpty { tr("sin red", "no network") } + "  ·  port ${IFacialMocapServer.PORT}",
                    fontSize = 13.sp,
                )
                EditableField(tr("IP fija de la PC (opcional)", "Fixed PC IP (optional)"), s.ifmTargetIp) { v -> vm.update { it.copy(ifmTargetIp = v) } }
                StatusText(ui.ifmStatus)
            }
        }

        // ---------- VTube Studio
        item {
            Section(tr("Salida VTube Studio (API de plugins)", "VTube Studio output (plugin API)")) {
                ToggleRow(tr("Activada", "Enabled"), s.vtsEnabled) { v -> vm.update { it.copy(vtsEnabled = v) } }
                EditableField(tr("IP de la PC con VTube Studio", "IP of the PC running VTube Studio"), s.vtsHost) { v ->
                    vm.update { it.copy(vtsHost = v) }
                }
                EditableField(tr("Puerto API", "API port"), s.vtsPort.toString(), numeric = true) { v ->
                    v.toIntOrNull()?.let { p -> vm.update { it.copy(vtsPort = p) } }
                }
                OutlinedButton({ vm.discoverVts() }) { Text(tr("Buscar VTube Studio en la red", "Find VTube Studio on the network")) }
                StatusText(ui.vtsStatus)
            }
        }

        // ---------- Ejes
        item {
            Section(tr("Ejes y orientación", "Axes and orientation")) {
                ToggleRow(tr("Intercambiar izquierda/derecha", "Swap left/right"), s.swapLeftRight) { v -> vm.update { it.copy(swapLeftRight = v) } }
                ToggleRow(tr("Invertir giro (yaw)", "Invert yaw"), s.invertYaw) { v -> vm.update { it.copy(invertYaw = v) } }
                ToggleRow(tr("Invertir cabeceo (pitch)", "Invert pitch"), s.invertPitch) { v -> vm.update { it.copy(invertPitch = v) } }
                ToggleRow(tr("Invertir inclinación (roll)", "Invert roll"), s.invertRoll) { v -> vm.update { it.copy(invertRoll = v) } }
                ToggleRow(tr("Invertir ojos horizontal", "Invert eyes horizontal"), s.invertEyesX) { v -> vm.update { it.copy(invertEyesX = v) } }
                ToggleRow(tr("Invertir ojos vertical", "Invert eyes vertical"), s.invertEyesY) { v -> vm.update { it.copy(invertEyesY = v) } }
            }
        }
    }
}

/** Solo este bloque se redibuja con cada cuadro: el resto de la pantalla se actualiza ~10 veces/s. */
@Composable
private fun LivePreview(vm: TrackingViewModel, meshMode: Int) {
    val pf by vm.preview.collectAsStateWithLifecycle()
    val frame = pf ?: return
    Box(Modifier.fillMaxSize().graphicsLayer(scaleX = -1f)) {
        Image(frame.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        FaceOverlay(frame, meshMode, Modifier.fillMaxSize())
    }
}

@Composable
private fun FaceOverlay(pf: PreviewFrame, mode: Int, modifier: Modifier) {
    val iw = pf.bitmap.width.toFloat()
    val ih = pf.bitmap.height.toFloat()
    Canvas(modifier) {
        val scale = max(size.width / iw, size.height / ih)
        val dw = iw * scale
        val dh = ih * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f

        val pts = pf.landmarks
        if (pts != null && mode > 0) {
            val n = pts.size / 2
            fun px(i: Int) = ox + pts[i * 2] * dw
            fun py(i: Int) = oy + pts[i * 2 + 1] * dh
            if (mode == 2) {
                val edges = FaceGeometry.MESH_EDGES
                val path = Path()
                var e = 0
                while (e + 1 < edges.size) {
                    val a = edges[e]; val b = edges[e + 1]
                    if (a < n && b < n) { path.moveTo(px(a), py(a)); path.lineTo(px(b), py(b)) }
                    e += 2
                }
                drawPath(path, Color(0x994DD0E1), style = Stroke(width = 1f))
            } else {
                val face = FaceGeometry.OVERLAY_POINTS.filter { it < n }.map { Offset(px(it), py(it)) }
                drawPoints(face, PointMode.Points, Color(0xFF4DD0E1), strokeWidth = 6f, cap = StrokeCap.Round)
            }
            val lips = FaceGeometry.OVERLAY_LIPS.filter { it < n }.map { Offset(px(it), py(it)) }
            drawPoints(lips, PointMode.Points, Color(0xFFFF6699), strokeWidth = 5f, cap = StrokeCap.Round)
            if (n >= 478) for (c in intArrayOf(468, 473)) {
                drawCircle(Color(0xFFFFEB3B), radius = 4f, center = Offset(px(c), py(c)))
            }
        }

        val hull = pf.tongueHull
        if (hull != null && hull.size >= 6) {
            val path = Path()
            path.moveTo(ox + hull[0] * dw, oy + hull[1] * dh)
            var i = 2
            while (i + 1 < hull.size) { path.lineTo(ox + hull[i] * dw, oy + hull[i + 1] * dh); i += 2 }
            path.close()
            drawPath(path, Color(0x5500FF66))
            drawPath(path, Color(0xFF00FF66), style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun StatusOverlay(ui: UiState, modifier: Modifier) {
    Column(modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(
            if (ui.faceFound) tr("● Cara detectada", "● Face detected") else tr("○ Sin cara", "○ No face"),
            color = if (ui.faceFound) Color(0xFF66FF99) else Color(0xFFFF6666),
        )
        Text(
            tr("tracking %.0f fps · cámara %.0f fps · envío %.0f fps", "tracking %.0f fps · camera %.0f fps · send %.0f fps")
                .format(ui.fps, ui.cameraFps, ui.sendFps),
            fontSize = 12.sp, color = Color.White,
        )
        Text(
            tr("MediaPipe %.0f ms · lengua %.0f ms · total %.0f ms", "MediaPipe %.0f ms · tongue %.0f ms · total %.0f ms")
                .format(ui.detectMs, ui.tongueMs, ui.processMs),
            fontSize = 12.sp, color = Color.White,
        )
        Text(tr("conversión %.0f ms · %s", "conversion %.0f ms · %s").format(ui.convertMs, ui.accel), fontSize = 12.sp, color = Color.White)
    }
}

@Composable
private fun CaptureOverlay(ui: UiState, modifier: Modifier) {
    val c = ui.capture ?: return
    val step = ui.wizardStep
    val arrow = when (c.pose) {
        null, Pose.CENTER -> "●"
        Pose.LEFT -> "←"
        Pose.RIGHT -> "→"
        Pose.UP -> "↑"
        Pose.DOWN -> "↓"
    }
    val text = c.pose?.prompt ?: (tr("Poné el gesto: ", "Make the expression: ") + (step?.title ?: ""))
    Column(
        modifier.fillMaxWidth().background(Color(0xCC000000)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(arrow, fontSize = 40.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold)
        Text(text, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        if (c.pose != null) Text(tr("Mantené el gesto", "Hold the expression"), color = Color(0xFFBBBBBB), fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { c.phase }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Color(0xFFFFCC00),
        )
        LinearProgressIndicator(progress = { c.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalibrationCard(ui: UiState, vm: TrackingViewModel, quick: Boolean) {
    val step = ui.wizardStep
    Card(
        Modifier.fillMaxWidth(),
        colors = if (step != null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Calibración de expresiones (VRM)", "Expression calibration (VRM)"), style = MaterialTheme.typography.titleMedium)
            if (step == null) {
                val n = ui.calibratedSteps.size
                Text(
                    if (n == 0) tr(
                        "Sin calibrar. Cada gesto se graba de frente y girando la cabeza para que funcione en cualquier ángulo. " +
                            "Unos 3 minutos; la app te guía con flechas. Todo queda guardado.",
                        "Not calibrated. Each expression is recorded facing the camera and turning your head so it works at any angle. " +
                            "About 3 minutes; the app guides you with arrows. Everything is saved.",
                    )
                    else tr("$n de ${CalibStep.entries.size} pasos calibrados y guardados. Tocá uno para repetirlo.",
                        "$n of ${CalibStep.entries.size} steps calibrated and saved. Tap one to redo it."),
                    fontSize = 13.sp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalibStep.entries.forEach { st ->
                        AssistChip(
                            onClick = { vm.openStep(st) },
                            label = { Text((if (st in ui.calibratedSteps) "✓ " else "") + st.title, fontSize = 12.sp) },
                        )
                    }
                }
                ToggleRow(tr("Calibración rápida (solo de frente)", "Quick calibration (front only)"), quick) { v ->
                    vm.update { it.copy(quickCalibration = v) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.startWizard() }, Modifier.weight(1f)) {
                        Text(if (n == 0) tr("Empezar", "Start") else tr("Recalibrar todo", "Recalibrate all"))
                    }
                    if (n > 0) OutlinedButton({ vm.resetCalibration() }) { Text(tr("Borrar", "Clear")) }
                }
            } else {
                Text(tr("Paso ${step.ordinal + 1} de ${CalibStep.entries.size}: ", "Step ${step.ordinal + 1} of ${CalibStep.entries.size}: ") + step.title,
                    fontWeight = FontWeight.Bold)
                Text(step.instruction, fontSize = 15.sp)
                Text(tr("Consejo: ", "Tip: ") + step.tip, fontSize = 13.sp)
                if (!quick && step.multiAngle) {
                    Text(
                        tr(
                            "Mantené el gesto TODO el tiempo y girá la cabeza cuando lo indique la flecha: de frente → izquierda → derecha → arriba → abajo.",
                            "Hold the expression the WHOLE time and turn your head when the arrow says: front → left → right → up → down.",
                        ),
                        fontSize = 12.sp,
                    )
                }
                if (ui.capture == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.captureCurrent() }, Modifier.weight(1f)) { Text(tr("Capturar", "Capture")) }
                        TextButton({ vm.skipStep() }) { Text(tr("Saltar", "Skip")) }
                        TextButton({ vm.closeWizard() }) { Text(tr("Cerrar", "Close")) }
                    }
                } else {
                    Text(tr("Capturando… seguí las indicaciones sobre la cámara.", "Capturing… follow the prompts on the camera."),
                        fontSize = 13.sp, color = Color(0xFFFFCC00))
                }
            }
        }
    }
}

@Composable
private fun ProfilesCard(ui: UiState, vm: TrackingViewModel) {
    var name by remember { mutableStateOf("") }
    Section(tr("Perfiles", "Profiles")) {
        Text(
            tr(
                "Guardá tu calibración y tus ajustes de gestos con un nombre (por ejemplo, uno por persona o por avatar).",
                "Save your calibration and gesture tuning under a name (e.g. one per person or per avatar).",
            ),
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text(tr("Nombre", "Name")) }, singleLine = true)
            Spacer(Modifier.width(6.dp))
            Button({ vm.saveProfile(name); name = "" }, enabled = name.isNotBlank()) { Text(tr("Guardar", "Save")) }
        }
        for (p in ui.profiles) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p, Modifier.weight(1f))
                TextButton({ vm.loadProfile(p) }) { Text(tr("Cargar", "Load")) }
                TextButton({ vm.deleteProfile(p) }) { Text(tr("Borrar", "Delete")) }
            }
        }
    }
}

/**
 * Editor por gesto: valor en vivo (línea blanca), reposo (azul) y máximo (verde) de cada
 * blendshape. Tocá un gesto para ajustar su rango o su intensidad de salida.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GestureEditor(ui: UiState, vm: TrackingViewModel) {
    var group by remember { mutableIntStateOf(1) }
    var open by remember { mutableIntStateOf(-1) }
    @Suppress("UNUSED_VARIABLE") val version = ui.overridesVersion // redibujar al cambiar ajustes
    Section(tr("Ajuste por gesto", "Per-gesture tuning")) {
        Text(
            tr(
                "Línea blanca = tu valor ahora. Azul = reposo. Verde = máximo. Tocá un gesto para ajustarlo a tu cara. Se guarda solo.",
                "White line = your value now. Blue = rest. Green = maximum. Tap a gesture to fit it to your face. Saved automatically.",
            ),
            fontSize = 12.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GESTURE_GROUPS.forEachIndexed { i, g ->
                FilterChip(group == i, { group = i; open = -1 }, { Text(g.first()) })
            }
        }
        for (name in GESTURE_GROUPS[group].second) {
            val i = ArKit.idx(name)
            val mn = vm.effMin(i)
            val mx = vm.effMax(i)
            Column(Modifier.fillMaxWidth().clickable { open = if (open == i) -1 else i }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (vm.isOverridden(i)) "✎ " else "") + name,
                        Modifier.width(150.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                    RangeBar(ui.raw[i], mn, mx, ui.blend[i], Modifier.weight(1f).height(18.dp))
                }
            }
            if (open == i) {
                Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    if (i != ArKit.TONGUE_OUT) {
                        val hi = if (mx.isNaN()) 1f else mx
                        Text(tr("Rango: reposo %.2f → máximo %.2f", "Range: rest %.2f → max %.2f").format(mn, hi), fontSize = 12.sp)
                        RangeSlider(
                            value = mn..hi,
                            onValueChange = { r -> vm.setRange(i, r.start, r.endInclusive) },
                            valueRange = 0f..1f,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton({ vm.captureLimit(i, false) }) { Text(tr("Ahora = reposo", "Now = rest"), fontSize = 12.sp) }
                            OutlinedButton({ vm.captureLimit(i, true) }) { Text(tr("Ahora = máximo", "Now = max"), fontSize = 12.sp) }
                        }
                    }
                    Text(tr("Intensidad de salida: %.0f%%", "Output intensity: %.0f%%").format(vm.outMax(i) * 100), fontSize = 12.sp)
                    Slider(vm.outMax(i), { v -> vm.setOut(i, v) })
                    TextButton({ vm.resetGesture(i) }) { Text(tr("Restablecer este gesto", "Reset this gesture")) }
                }
            }
        }
    }
}

/** Barra: rango calibrado (fondo), valor crudo actual (línea blanca) y valor final (relleno). */
@Composable
private fun RangeBar(raw: Float, min: Float, max: Float, out: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF2A2A2A), size = size)
        drawRect(Color(0xFF6A5ACD), topLeft = Offset(0f, h * 0.65f), size = Size(w * out.coerceIn(0f, 1f), h * 0.35f))
        if (!max.isNaN()) {
            val x0 = w * min.coerceIn(0f, 1f)
            val x1 = w * max.coerceIn(0f, 1f)
            drawRect(Color(0x3366FF99), topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(1f), h * 0.6f))
            drawLine(Color(0xFF66FF99), Offset(x1, 0f), Offset(x1, h), strokeWidth = 3f)
        }
        drawLine(Color(0xFF4FA3FF), Offset(w * min.coerceIn(0f, 1f), 0f), Offset(w * min.coerceIn(0f, 1f), h), strokeWidth = 3f)
        val rx = w * raw.coerceIn(0f, 1f)
        drawLine(Color.White, Offset(rx, 0f), Offset(rx, h * 0.6f), strokeWidth = 3f)
    }
}

@Composable
private fun GainSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: %.0f%%".format(value * 100), fontSize = 13.sp)
        Slider(value, onChange, valueRange = 0.3f..2f)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange)
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
}

/** Campo de texto que solo aplica el valor al tocar "OK" (evita reconectar en cada tecla). */
@Composable
private fun EditableField(label: String, value: String, numeric: Boolean = false, onApply: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text, { text = it }, Modifier.weight(1f),
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri),
        )
        if (text != value) {
            Spacer(Modifier.width(6.dp))
            Button({ onApply(text) }) { Text("OK") }
        }
    }
}
