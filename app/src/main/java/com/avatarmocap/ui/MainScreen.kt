package com.avatarmocap.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avatarmocap.TrackingViewModel
import com.avatarmocap.UiState
import com.avatarmocap.output.IFacialMocapServer
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.CalibStep
import com.avatarmocap.tracking.FaceGeometry
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.avatarmocap.tracking.Vrm
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: TrackingViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // Se vuelve a enlazar la cámara si cambia la resolución o se muestra/oculta la vista previa
    LaunchedEffect(s.resolution, s.showPreview) {
        vm.startCamera(owner, if (s.showPreview) previewView else null)
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        // ---------- Vista previa + estado
        item {
            Column {
                val tall = s.showPreview || s.meshMode > 0
                Box(
                    Modifier.fillMaxWidth().height(if (tall) 280.dp else 90.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF1A1A1A))
                ) {
                    if (s.showPreview) AndroidView({ previewView }, Modifier.fillMaxWidth().height(280.dp))
                    if (s.meshMode > 0) LandmarkOverlay(ui, s.meshMode, Modifier.fillMaxWidth().height(280.dp))
                    StatusOverlay(ui, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                if (ui.message.isNotEmpty()) {
                    Text(ui.message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // ---------- Calibración guiada
        item { CalibrationCard(ui, vm) }

        // ---------- Boca
        item {
            Section("Boca") {
                ToggleRow("Boca estable (filtra gestos con ruido)", s.mouthStable) { v -> vm.update { it.copy(mouthStable = v) } }
                Text("Simetría: %.0f%%".format(s.mouthSymmetry * 100), fontSize = 13.sp)
                Slider(s.mouthSymmetry, { v -> vm.update { it.copy(mouthSymmetry = v) } })
                Text("Mover la boca a los costados: %.0f%%".format(s.mouthSide * 100), fontSize = 13.sp)
                Slider(s.mouthSide, { v -> vm.update { it.copy(mouthSide = v) } })
                Text(
                    "Si la boca se ve torcida, subí la simetría. Si se abre de más, bajá \"Boca abierta\" en Ajuste fino.",
                    fontSize = 12.sp,
                )
            }
        }

        // ---------- Ajuste fino
        item {
            Section("Ajuste fino de expresiones") {
                Text("Multiplicá la intensidad de cada grupo después de calibrar.", fontSize = 12.sp)
                GainSlider("Ojos (parpadeo)", s.gainEyes) { v -> vm.update { it.copy(gainEyes = v) } }
                GainSlider("Cejas", s.gainBrows) { v -> vm.update { it.copy(gainBrows = v) } }
                GainSlider("Boca abierta", s.gainMouthOpen) { v -> vm.update { it.copy(gainMouthOpen = v) } }
                GainSlider("Sonrisa", s.gainSmile) { v -> vm.update { it.copy(gainSmile = v) } }
                GainSlider("Cachetes inflados", s.gainCheeks) { v -> vm.update { it.copy(gainCheeks = v) } }
                GainSlider("Lengua", s.gainTongue) { v -> vm.update { it.copy(gainTongue = v) } }
                GainSlider("Emociones VRM (Joy, Angry…)", s.gainEmotions) { v -> vm.update { it.copy(gainEmotions = v) } }
                ToggleRow("Precisión mejorada (parpadeo, boca y mirada desde la malla)", s.precise) { v ->
                    vm.update { it.copy(precise = v) }
                }
                Text("Suavizado: %.0f%%".format(s.smoothing * 100), fontSize = 13.sp)
                Slider(s.smoothing, { v -> vm.update { it.copy(smoothing = v) } })
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
                    }
                    ui.tongueDebug?.let {
                        Spacer(Modifier.width(8.dp))
                        Image(it, null, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                    }
                }
                ToggleRow("Ver recorte de la boca (verde = lengua)", s.showTongueDebug) { v ->
                    vm.update { it.copy(showTongueDebug = v) }
                }
            }
        }

        // ---------- Rendimiento
        item {
            Section("Rendimiento") {
                Text("Resolución de análisis (menos = más fps)", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Baja", "Media", "Alta").forEachIndexed { i, label ->
                        FilterChip(
                            selected = s.resolution == i,
                            onClick = { vm.update { it.copy(resolution = i) } },
                            label = { Text(label) },
                        )
                    }
                }
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
                Text(
                    "Automático mide GPU y CPU con tu cara durante los primeros segundos y se queda con el más rápido.",
                    fontSize = 12.sp,
                )
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

        // ---------- VMC
        item {
            Section("Salida VMC (expresiones VRM)") {
                ToggleRow("Activada", s.vmcEnabled) { v -> vm.update { it.copy(vmcEnabled = v) } }
                Text(
                    "Envía A, I, U, E, O, parpadeo y emociones calibradas a los clips del modelo VRM. " +
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

        // ---------- Blendshapes (oculto por defecto: dibujar 52 barras cuesta fps)
        item {
            ToggleRow("Ver los 52 blendshapes", s.showBlendshapes) { v -> vm.update { it.copy(showBlendshapes = v) } }
        }
        if (s.showBlendshapes) {
            items(ArKit.COUNT) { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ArKit.NAMES[i], Modifier.width(170.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    LinearProgressIndicator(
                        progress = { ui.blend[i] },
                        modifier = Modifier.weight(1f).height(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Dibuja los puntos detectados sobre la vista previa. La vista previa de la cámara frontal
 * está espejada y recortada al centro (FILL_CENTER), así que se aplica lo mismo a los puntos.
 */
@Composable
private fun LandmarkOverlay(ui: UiState, mode: Int, modifier: Modifier) {
    val pts = ui.landmarks ?: return
    if (ui.imgW <= 0 || ui.imgH <= 0) return
    val n = pts.size / 2
    Canvas(modifier) {
        val scale = max(size.width / ui.imgW, size.height / ui.imgH)
        val dw = ui.imgW * scale
        val dh = ui.imgH * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f
        fun px(i: Int) = ox + (1f - pts[i * 2]) * dw
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
            drawPath(path, Color(0x804DD0E1), style = Stroke(width = 1.2f))
        } else {
            val face = FaceGeometry.OVERLAY_POINTS.filter { it < n }.map { Offset(px(it), py(it)) }
            drawPoints(face, PointMode.Points, Color(0xFF4DD0E1), strokeWidth = 6f, cap = StrokeCap.Round)
        }
        val lips = FaceGeometry.OVERLAY_LIPS.filter { it < n }.map { Offset(px(it), py(it)) }
        drawPoints(lips, PointMode.Points, Color(0xFFFF6699), strokeWidth = 5f, cap = StrokeCap.Round)
        // Iris
        if (n >= 478) {
            for (c in intArrayOf(468, 473)) drawCircle(Color(0xFFFFEB3B), radius = 4f, center = Offset(px(c), py(c)))
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
            "tracking %.0f fps · cámara %.0f fps".format(ui.fps, ui.cameraFps),
            fontSize = 12.sp, color = Color.White,
        )
        Text(
            "MediaPipe %.0f ms · total %.0f ms · %s".format(ui.detectMs, ui.processMs, ui.accel),
            fontSize = 12.sp, color = Color.White,
        )
        Text(
            "cabeza p%.0f° y%.0f° r%.0f°".format(ui.headRot[0], ui.headRot[1], ui.headRot[2]),
            fontSize = 12.sp, color = Color.White,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalibrationCard(ui: UiState, vm: TrackingViewModel) {
    val step = ui.wizardStep
    Card(
        Modifier.fillMaxWidth(),
        colors = if (step != null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Calibración de expresiones", style = MaterialTheme.typography.titleMedium)
            if (step == null) {
                val n = ui.calibratedSteps.size
                Text(
                    if (n == 0) "Sin calibrar. Son las expresiones básicas de un modelo VRM (neutral, parpadeo, A I U E O, " +
                        "alegría, enojo, tristeza, diversión y sorpresa) + cachetes y lengua. Unos 2 minutos."
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.startWizard() }, Modifier.weight(1f)) { Text(if (n == 0) "Empezar" else "Recalibrar todo") }
                    if (n > 0) OutlinedButton({ vm.resetCalibration() }) { Text("Borrar") }
                }
            } else {
                Text("Paso ${step.ordinal + 1} de ${CalibStep.entries.size}: ${step.title}", fontWeight = FontWeight.Bold)
                Text(step.instruction, fontSize = 15.sp)
                val p = ui.captureProgress
                when {
                    p < 0f -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.captureCurrent() }, Modifier.weight(1f)) { Text("Capturar") }
                        TextButton({ vm.skipStep() }) { Text("Saltar") }
                        TextButton({ vm.closeWizard() }) { Text("Cerrar") }
                    }
                    p < 1f -> {
                        Text("Preparate y mantené el gesto…", fontSize = 13.sp)
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text("Midiendo… ¡no te muevas!", fontSize = 13.sp, color = Color(0xFFFFCC00))
                        LinearProgressIndicator(progress = { p - 1f }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFFFCC00))
                    }
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
