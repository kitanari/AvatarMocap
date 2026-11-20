package com.avatarmocap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.avatarmocap.output.IFacialMocapServer
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.CalibStep
import com.avatarmocap.tracking.FaceGeometry
import com.avatarmocap.tracking.Pose
import com.avatarmocap.tracking.PreviewFrame
import com.avatarmocap.tracking.Vrm
import kotlin.math.max

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
        // ---------- Vista: la MISMA imagen que se analiza, con su malla encima (siempre alineadas)
        item {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(if (s.showPreview || ui.capture != null) 300.dp else 90.dp)
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

        // ---------- Calibración guiada
        item { CalibrationCard(ui, vm, s.quickCalibration) }

        // ---------- Boca
        item {
            Section("Boca") {
                Text("Límite de apertura: %.0f%%".format(s.mouthLimit * 100), fontSize = 13.sp)
                Slider(s.mouthLimit, { v -> vm.update { it.copy(mouthLimit = v) } }, valueRange = 0.4f..1.3f)
                ToggleRow("Rangos como iPhone (recomendado)", s.iphoneRanges) { v -> vm.update { it.copy(iphoneRanges = v) } }
                ToggleRow("Boca estable (filtra gestos con ruido)", s.mouthStable) { v -> vm.update { it.copy(mouthStable = v) } }
                Text("Simetría: %.0f%%".format(s.mouthSymmetry * 100), fontSize = 13.sp)
                Slider(s.mouthSymmetry, { v -> vm.update { it.copy(mouthSymmetry = v) } })
                Text("Mover la boca a los costados: %.0f%%".format(s.mouthSide * 100), fontSize = 13.sp)
                Slider(s.mouthSide, { v -> vm.update { it.copy(mouthSide = v) } })
                Text(
                    "Si la boca del avatar atraviesa la cara, bajá el límite de apertura. Si se ve torcida, subí la simetría.",
                    fontSize = 12.sp,
                )
            }
        }

        // ---------- Ajuste fino
        item {
            Section("Ajuste fino de expresiones") {
                GainSlider("Ojos (parpadeo)", s.gainEyes) { v -> vm.update { it.copy(gainEyes = v) } }
                GainSlider("Cejas", s.gainBrows) { v -> vm.update { it.copy(gainBrows = v) } }
                GainSlider("Boca abierta", s.gainMouthOpen) { v -> vm.update { it.copy(gainMouthOpen = v) } }
                GainSlider("Sonrisa", s.gainSmile) { v -> vm.update { it.copy(gainSmile = v) } }
                GainSlider("Cachetes inflados", s.gainCheeks) { v -> vm.update { it.copy(gainCheeks = v) } }
                GainSlider("Lengua", s.gainTongue) { v -> vm.update { it.copy(gainTongue = v) } }
                GainSlider("Emociones VRM (Joy, Angry…)", s.gainEmotions) { v -> vm.update { it.copy(gainEmotions = v) } }
                Text("Suavizado: %.0f%%".format(s.smoothing * 100), fontSize = 13.sp)
                Slider(s.smoothing, { v -> vm.update { it.copy(smoothing = v) } })
                ToggleRow("Precisión mejorada (parpadeo, boca y mirada desde la malla)", s.precise) { v ->
                    vm.update { it.copy(precise = v) }
                }
                ToggleRow("Auto-rango para gestos sin calibrar", s.autoRange) { v -> vm.update { it.copy(autoRange = v) } }
            }
        }

        // ---------- Lengua
        item {
            Section("Lengua") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("tongueOut %.2f".format(ui.tongueValue))
                        LinearProgressIndicator(
                            progress = { ui.tongueValue },
                            modifier = Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp),
                        )
                        Text("señal cruda %.2f".format(ui.tongueFeature), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            if (CalibStep.TONGUE in ui.calibratedSteps) "Color de tu lengua aprendido ✓"
                            else "Calibrá \"Lengua afuera\" para que aprenda el color de tu lengua.",
                            fontSize = 12.sp,
                        )
                    }
                    ui.tongueDebug?.let {
                        Spacer(Modifier.width(8.dp))
                        Image(it, null, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                    }
                }
                Text("En la cámara, la lengua detectada se marca con un contorno verde.", fontSize = 12.sp)
                ToggleRow("Ver recorte de depuración (verde = cuenta, naranja = descartado)", s.showTongueDebug) { v ->
                    vm.update { it.copy(showTongueDebug = v) }
                }
            }
        }

        // ---------- Expresiones VRM en vivo
        item {
            Section("Expresiones VRM") {
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
            Section("Rendimiento") {
                Text("Resolución de análisis (menos = más fps)", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Baja", "Media", "Alta").forEachIndexed { i, label ->
                        FilterChip(s.resolution == i, { vm.update { it.copy(resolution = i) } }, { Text(label) })
                    }
                }
                ToggleRow("Envío a 60 fps (interpola entre cuadros)", s.interpolate) { v -> vm.update { it.copy(interpolate = v) } }
                ToggleRow("Mostrar cámara (apagarla da más fps)", s.showPreview) { v -> vm.update { it.copy(showPreview = v) } }
                Text("Sobre la cara", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Nada", "Puntos", "Malla").forEachIndexed { i, label ->
                        FilterChip(s.meshMode == i, { vm.update { it.copy(meshMode = i) } }, { Text(label) })
                    }
                }
                Text("Procesador (se aplica al reiniciar la app)", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Automático", "GPU", "CPU").forEachIndexed { i, label ->
                        FilterChip(s.accel == i, { vm.update { it.copy(accel = i) } }, { Text(label) })
                    }
                }
            }
        }

        // ---------- VMC
        item {
            Section("Salida VMC (expresiones VRM)") {
                ToggleRow("Activada", s.vmcEnabled) { v -> vm.update { it.copy(vmcEnabled = v) } }
                Text(
                    "Envía A, I, U, E, O, parpadeo, emociones y TongueOut a los clips del modelo VRM. " +
                        "En VSeeFace: Configuración general → receptor del protocolo VMC, puerto 39539.",
                    fontSize = 12.sp,
                )
                EditableField("IP de la PC (vacío = la de iFacialMocap)", s.vmcHost) { v -> vm.update { it.copy(vmcHost = v) } }
                EditableField("Puerto", s.vmcPort.toString(), numeric = true) { v ->
                    v.toIntOrNull()?.let { p -> vm.update { it.copy(vmcPort = p) } }
                }
                ToggleRow("Nombres VRM 1.0 (aa, blink, happy…)", s.vmcVrm1) { v -> vm.update { it.copy(vmcVrm1 = v) } }
                ToggleRow("Enviar también ARKit (modelos perfect sync)", s.vmcArkit) { v -> vm.update { it.copy(vmcArkit = v) } }
                ToggleRow("Enviar cabeza y ojos (huesos)", s.vmcBones) { v -> vm.update { it.copy(vmcBones = v) } }
                StatusText(ui.vmcStatus)
            }
        }

        // ---------- iFacialMocap
        item {
            Section("Salida iFacialMocap (VSeeFace, VNyan, Warudo…)") {
                ToggleRow("Activada", s.ifmEnabled) { v -> vm.update { it.copy(ifmEnabled = v) } }
                Text(
                    "IP de este teléfono: ${ui.phoneIps.joinToString().ifEmpty { "sin red" }}  ·  puerto ${IFacialMocapServer.PORT}",
                    fontSize = 13.sp,
                )
                EditableField("IP fija de la PC (opcional)", s.ifmTargetIp) { v -> vm.update { it.copy(ifmTargetIp = v) } }
                StatusText(ui.ifmStatus)
            }
        }

        // ---------- VTube Studio
        item {
            Section("Salida VTube Studio (API de plugins)") {
                ToggleRow("Activada", s.vtsEnabled) { v -> vm.update { it.copy(vtsEnabled = v) } }
                EditableField("IP de la PC con VTube Studio", s.vtsHost) { v -> vm.update { it.copy(vtsHost = v) } }
                EditableField("Puerto API", s.vtsPort.toString(), numeric = true) { v ->
                    v.toIntOrNull()?.let { p -> vm.update { it.copy(vtsPort = p) } }
                }
                OutlinedButton({ vm.discoverVts() }) { Text("Buscar VTube Studio en la red") }
                StatusText(ui.vtsStatus)
            }
        }

        // ---------- Ejes
        item {
            Section("Ejes y orientación") {
                ToggleRow("Intercambiar izquierda/derecha", s.swapLeftRight) { v -> vm.update { it.copy(swapLeftRight = v) } }
                ToggleRow("Invertir giro (yaw)", s.invertYaw) { v -> vm.update { it.copy(invertYaw = v) } }
                ToggleRow("Invertir cabeceo (pitch)", s.invertPitch) { v -> vm.update { it.copy(invertPitch = v) } }
                ToggleRow("Invertir inclinación (roll)", s.invertRoll) { v -> vm.update { it.copy(invertRoll = v) } }
                ToggleRow("Invertir ojos horizontal", s.invertEyesX) { v -> vm.update { it.copy(invertEyesX = v) } }
                ToggleRow("Invertir ojos vertical", s.invertEyesY) { v -> vm.update { it.copy(invertEyesY = v) } }
            }
        }

        item {
            ToggleRow("Ver los 52 blendshapes", s.showBlendshapes) { v -> vm.update { it.copy(showBlendshapes = v) } }
        }
        if (s.showBlendshapes) {
            items(ArKit.COUNT) { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ArKit.NAMES[i], Modifier.width(170.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    LinearProgressIndicator(progress = { ui.blend[i] }, modifier = Modifier.weight(1f).height(8.dp))
                }
            }
        }
    }
}

/** Solo este bloque se redibuja con cada cuadro: el resto de la pantalla se actualiza ~10 veces/s. */
@Composable
private fun LivePreview(vm: TrackingViewModel, meshMode: Int) {
    val pf by vm.preview.collectAsStateWithLifecycle()
    val frame = pf ?: return
    // Espejado como una selfie: imagen y malla juntas
    Box(Modifier.fillMaxSize().graphicsLayer(scaleX = -1f)) {
        Image(frame.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        FaceOverlay(frame, meshMode, Modifier.fillMaxSize())
    }
}

/**
 * Malla / puntos / lengua sobre la imagen analizada, en coordenadas de la imagen con el mismo
 * recorte que ContentScale.Crop: coincide exactamente con la foto.
 */
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
            if (ui.faceFound) "● Cara detectada" else "○ Sin cara",
            color = if (ui.faceFound) Color(0xFF66FF99) else Color(0xFFFF6666),
        )
        Text(
            "tracking %.0f fps · cámara %.0f fps · envío %.0f fps".format(ui.fps, ui.cameraFps, ui.sendFps),
            fontSize = 12.sp, color = Color.White,
        )
        Text(
            "MediaPipe %.0f ms · lengua %.0f ms · total %.0f ms".format(ui.detectMs, ui.tongueMs, ui.processMs),
            fontSize = 12.sp, color = Color.White,
        )
        Text("conversión %.0f ms · %s".format(ui.convertMs, ui.accel), fontSize = 12.sp, color = Color.White)
    }
}

/** Indicación grande sobre la cámara durante la captura: qué gesto y hacia dónde mirar. */
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
    val text = c.pose?.prompt ?: "Poné el gesto: ${step?.title ?: ""}"
    Column(
        modifier.fillMaxWidth().background(Color(0xCC000000)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(arrow, fontSize = 40.sp, color = Color(0xFFFFCC00), fontWeight = FontWeight.Bold)
        Text(text, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        if (c.pose != null) Text("Mantené el gesto", color = Color(0xFFBBBBBB), fontSize = 12.sp)
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
            Text("Calibración de expresiones (VRM)", style = MaterialTheme.typography.titleMedium)
            if (step == null) {
                val n = ui.calibratedSteps.size
                Text(
                    if (n == 0) "Sin calibrar. Cada gesto se graba de frente y girando la cabeza " +
                        "(izquierda, derecha, arriba, abajo) para que funcione bien en cualquier ángulo. " +
                        "Unos 3 minutos; la app te va guiando con flechas sobre la cámara."
                    else "$n de ${CalibStep.entries.size} pasos calibrados. Tocá un paso para repetirlo.",
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
                ToggleRow("Calibración rápida (solo de frente)", quick) { v -> vm.update { it.copy(quickCalibration = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.startWizard() }, Modifier.weight(1f)) { Text(if (n == 0) "Empezar" else "Recalibrar todo") }
                    if (n > 0) OutlinedButton({ vm.resetCalibration() }) { Text("Borrar") }
                }
            } else {
                Text("Paso ${step.ordinal + 1} de ${CalibStep.entries.size}: ${step.title}", fontWeight = FontWeight.Bold)
                Text(step.instruction, fontSize = 15.sp)
                Text("Consejo: ${step.tip}", fontSize = 13.sp)
                if (!quick && step.multiAngle) {
                    Text(
                        "Mantené el gesto TODO el tiempo y girá la cabeza cuando lo indique la flecha: " +
                            "de frente → izquierda → derecha → arriba → abajo.",
                        fontSize = 12.sp,
                    )
                }
                if (ui.capture == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.captureCurrent() }, Modifier.weight(1f)) { Text("Capturar") }
                        TextButton({ vm.skipStep() }) { Text("Saltar") }
                        TextButton({ vm.closeWizard() }) { Text("Cerrar") }
                    }
                } else {
                    Text("Capturando… seguí las indicaciones sobre la cámara.", fontSize = 13.sp, color = Color(0xFFFFCC00))
                }
            }
        }
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
