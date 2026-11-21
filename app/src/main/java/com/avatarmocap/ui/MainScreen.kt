package com.avatarmocap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avatarmocap.R
import com.avatarmocap.TrackingViewModel
import androidx.compose.ui.res.painterResource
import com.avatarmocap.UiState
import com.avatarmocap.i18n.tr
import com.avatarmocap.output.IFacialMocapServer
import com.avatarmocap.settings.AppSettings
import com.avatarmocap.tracking.ArKit
import com.avatarmocap.tracking.CalibStep
import com.avatarmocap.tracking.Pose
import com.avatarmocap.tracking.Vrm

@Composable
fun MainScreen(vm: TrackingViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(s.resolution, s.rearCamera) { vm.startCamera(owner) }

    var tab by remember { mutableIntStateOf(0) }
    var compact by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    // Durante la calibración siempre se muestra la pestaña de la cara
    if (ui.wizardStep != null && tab == 2) tab = 0

    Box(Modifier.fillMaxSize().background(Ui.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
            TopBar(onSettings = { tab = 1; compact = false }, onHelp = { help = true }, onMinimize = { compact = !compact })
            if (!compact) {
                Tabs(tab) { tab = it }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> ExpressionsTab(ui, s, vm)
                        1 -> SettingsTab(ui, s, vm)
                        else -> AdvancedTab(ui, s, vm)
                    }
                }
            } else {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        tr("Modo compacto: el tracking sigue funcionando.\nTocá — para volver.",
                            "Compact mode: tracking keeps running.\nTap — to go back."),
                        color = Ui.Muted, textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            BottomBar(ui, s, vm) { tab = 0; compact = false; vm.startWizard() }
            Spacer(Modifier.height(8.dp))
        }
        if (help) HelpDialog { help = false }
    }
}

// ======================================================================= barra superior y pestañas

@Composable
private fun TopBar(onSettings: () -> Unit, onHelp: () -> Unit, onMinimize: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_moonyface_logo), null, Modifier.size(42.dp))
        Spacer(Modifier.width(8.dp))
        Text("MoonyFace", color = Ui.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        RoundIcon("⚙", onSettings)
        Spacer(Modifier.width(8.dp))
        RoundIcon("?", onHelp)
        Spacer(Modifier.width(8.dp))
        RoundIcon("—", onMinimize)
    }
}

@Composable
private fun RoundIcon(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Ui.Card)
            .border(BorderStroke(1.dp, Ui.CardBorder), RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Ui.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("✦" to tr("Expresiones", "Expressions"), "☰" to tr("Ajustes", "Settings"), "☆" to tr("Avanzado", "Advanced"))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ui.Card)
            .border(BorderStroke(1.dp, Ui.CardBorder), RoundedCornerShape(18.dp)).padding(4.dp),
    ) {
        items.forEachIndexed { i, (icon, label) ->
            val sel = i == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Color(0xFF3A2670) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(icon, color = if (sel) Ui.Accent else Ui.Muted, fontSize = 20.sp)
                Text(label, color = if (sel) Ui.Text else Ui.Muted, fontSize = 13.sp)
                if (sel) Box(Modifier.padding(top = 4.dp).width(48.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Ui.Accent))
            }
        }
    }
}

// ======================================================================= pestaña Expresiones

@Composable
private fun ExpressionsTab(ui: UiState, s: AppSettings, vm: TrackingViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item { FaceCard(ui, s, vm) }
        if (ui.wizardStep != null || ui.calibratedSteps.isEmpty()) item { CalibrationCard(ui, vm, s.quickCalibration) }
        if (ui.message.isNotEmpty()) item { Text(ui.message, color = Ui.Accent, fontSize = 14.sp) }
        item { LiveExpressions(ui, vm) }
    }
}

@Composable
private fun FaceCard(ui: UiState, s: AppSettings, vm: TrackingViewModel) {
    PurpleCard(tr("Vista 3D (malla facial)", "3D view (face mesh)"), "☆") {
        Row {
            Box(
                Modifier.weight(1f).height(300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF110A22))
                    .border(BorderStroke(1.dp, Ui.CardBorder), RoundedCornerShape(14.dp)),
            ) {
                if (s.showPreview) LiveFace(vm, s)
                Corners(Modifier.fillMaxSize())
                if (!ui.faceFound) {
                    Text(
                        if (ui.paused) tr("En pausa", "Paused") else tr("Buscando tu cara…", "Looking for your face…"),
                        color = Ui.Muted, modifier = Modifier.align(Alignment.Center),
                    )
                }
                Text(
                    "%.0f fps".format(ui.fps), color = Ui.Muted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
                if (ui.capture != null) CaptureOverlay(ui, Modifier.align(Alignment.BottomCenter))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(104.dp), verticalArrangement = Arrangement.Center) {
                ViewCheck(tr("Malla", "Mesh"), s.viewMesh) { v -> vm.update { it.copy(viewMesh = v) } }
                ViewCheck(tr("Puntos", "Points"), s.viewPoints) { v -> vm.update { it.copy(viewPoints = v) } }
                ViewCheck(tr("Ojos", "Eyes"), s.viewEyes) { v -> vm.update { it.copy(viewEyes = v) } }
                ViewCheck(tr("Boca", "Mouth"), s.viewMouth) { v -> vm.update { it.copy(viewMouth = v) } }
                ViewCheck(tr("Cámara", "Camera"), s.viewCamera) { v -> vm.update { it.copy(viewCamera = v) } }
            }
        }
    }
}

@Composable
private fun LiveFace(vm: TrackingViewModel, s: AppSettings) {
    val pf by vm.preview.collectAsStateWithLifecycle()
    FaceView(
        pf,
        FaceViewOptions(
            mesh = s.viewMesh, points = s.viewPoints, eyes = s.viewEyes, mouth = s.viewMouth,
            camera = s.viewCamera, mirror = !s.rearCamera,
        ),
        Modifier.fillMaxSize(),
    )
}

/** Esquinas del visor, como en el diseño. */
@Composable
private fun Corners(modifier: Modifier) {
    Canvas(modifier.padding(10.dp)) {
        val l = 22.dp.toPx(); val c = Ui.Muted.copy(alpha = 0.7f); val w = 2.dp.toPx()
        val W = size.width; val H = size.height
        drawLine(c, Offset(0f, 0f), Offset(l, 0f), w); drawLine(c, Offset(0f, 0f), Offset(0f, l), w)
        drawLine(c, Offset(W, 0f), Offset(W - l, 0f), w); drawLine(c, Offset(W, 0f), Offset(W, l), w)
        drawLine(c, Offset(0f, H), Offset(l, H), w); drawLine(c, Offset(0f, H), Offset(0f, H - l), w)
        drawLine(c, Offset(W, H), Offset(W - l, H), w); drawLine(c, Offset(W, H), Offset(W, H - l), w)
    }
}

@Composable
private fun ViewCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(checked, onChange)
        Text(label, color = Ui.Text, fontSize = 13.sp)
    }
}

@Composable
private fun LiveExpressions(ui: UiState, vm: TrackingViewModel) {
    val b = ui.blend
    val v = ui.vrm
    fun avg(a: String, c: String) = (b[ArKit.idx(a)] + b[ArKit.idx(c)]) / 2f
    val jaw = ArKit.JAW_OPEN
    // Las 5 emociones básicas de VRM primero
    val emotions = listOf(
        tr("Alegría", "Joy") to maxOf(v[Vrm.JOY], avg("mouthSmileLeft", "mouthSmileRight")),
        tr("Enojo", "Angry") to maxOf(v[Vrm.ANGRY], avg("browDownLeft", "browDownRight") * 0.8f),
        tr("Tristeza", "Sorrow") to maxOf(v[Vrm.SORROW], minOf(b[ArKit.BROW_INNER_UP], avg("mouthFrownLeft", "mouthFrownRight") * 1.5f)),
        tr("Diversión", "Fun") to v[Vrm.FUN],
        tr("Sorpresa", "Surprised") to maxOf(v[Vrm.SURPRISED], minOf(avg("eyeWideLeft", "eyeWideRight"), b[ArKit.BROW_INNER_UP])),
    )
    val others = listOf(
        tr("Parpadeo", "Blink") to avg("eyeBlinkLeft", "eyeBlinkRight"),
        tr("Boca abierta", "Mouth open") to b[jaw] / vm.outMax(jaw).coerceAtLeast(0.05f),
        tr("Cachetes inflados", "Cheek puff") to b[ArKit.CHEEK_PUFF] / vm.outMax(ArKit.CHEEK_PUFF).coerceAtLeast(0.05f),
        tr("Lengua", "Tongue") to b[ArKit.TONGUE_OUT],
    )
    PurpleCard(tr("Expresiones VRM en tiempo real", "Real-time VRM expressions"), "☆") {
        for ((label, value) in emotions) ExpressionRow(label, value)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.CardBorder))
        // Vocales (A I U E O) en una sola fila
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in Vrm.A..Vrm.O) VowelChip(Vrm.LABELS[k], v[k], Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.CardBorder))
        for ((label, value) in others) ExpressionRow(label, value)
    }
}

@Composable
private fun VowelChip(label: String, value: Float, modifier: Modifier) {
    val a = value.coerceIn(0f, 1f)
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Ui.Track)
            .border(BorderStroke(1.dp, Ui.Accent.copy(alpha = 0.2f + 0.8f * a)), RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Ui.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("%.0f%%".format(a * 100), color = Ui.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ExpressionRow(label: String, value: Float) {
    val v = value.coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ui.Text, fontSize = 14.sp, modifier = Modifier.width(120.dp))
        PurpleBar(v, Modifier.weight(1f))
        Text("%.0f%%".format(v * 100), color = Ui.Text, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
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
        modifier.fillMaxWidth().background(Color(0xDD120A24)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(arrow, fontSize = 40.sp, color = Color(0xFFFFCC66), fontWeight = FontWeight.Bold)
        Text(text, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (c.pose != null) Text(tr("Mantené el gesto", "Hold the expression"), color = Ui.Muted, fontSize = 12.sp)
        PurpleBar(c.phase, Modifier.fillMaxWidth().padding(top = 6.dp))
        PurpleBar(c.total, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

// ======================================================================= pestaña Ajustes (simple)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsTab(ui: UiState, s: AppSettings, vm: TrackingViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item { CalibrationCard(ui, vm, s.quickCalibration) }
        if (ui.message.isNotEmpty()) item { Text(ui.message, color = Ui.Accent, fontSize = 14.sp) }
        item { ConnectionCard(ui, s, vm) }
        item {
            PurpleCard(tr("Intensidad", "Intensity"), "♥") {
                GainSlider(tr("Ojos", "Eyes"), s.gainEyes) { v -> vm.update { it.copy(gainEyes = v) } }
                GainSlider(tr("Cejas", "Brows"), s.gainBrows) { v -> vm.update { it.copy(gainBrows = v) } }
                GainSlider(tr("Boca", "Mouth"), s.gainMouthOpen) { v -> vm.update { it.copy(gainMouthOpen = v) } }
                GainSlider(tr("Sonrisa", "Smile"), s.gainSmile) { v -> vm.update { it.copy(gainSmile = v) } }
                GainSlider(tr("Cachetes", "Cheeks"), s.gainCheeks) { v -> vm.update { it.copy(gainCheeks = v) } }
                GainSlider(tr("Lengua", "Tongue"), s.gainTongue) { v -> vm.update { it.copy(gainTongue = v) } }
                Text(tr("Suavidad del movimiento: %.0f%%", "Motion smoothness: %.0f%%").format(s.smoothing * 100), color = Ui.Text, fontSize = 13.sp)
                Slider(s.smoothing, { v -> vm.update { it.copy(smoothing = v) } })
            }
        }
        item { ProfilesCard(ui, vm) }
        item {
            PurpleCard(tr("Idioma", "Language"), "✎") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Automático", "Automatic"), "Español", "English").forEachIndexed { i, label ->
                        FilterChip(s.language == i, { vm.update { it.copy(language = i) } }, { Text(label) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(ui: UiState, s: AppSettings, vm: TrackingViewModel) {
    PurpleCard(tr("Conexión con la PC", "PC connection"), "⇄") {
        Text(tr("¿Dónde usás tu avatar?", "Where do you use your avatar?"), color = Ui.Muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!s.vtsEnabled, {
                vm.update { it.copy(ifmEnabled = true, vmcEnabled = true, vtsEnabled = false) }
            }, { Text(tr("Avatar 3D (VRM)", "3D avatar (VRM)")) })
            FilterChip(s.vtsEnabled, { vm.update { it.copy(vtsEnabled = true) } }, { Text(tr("Avatar 2D (Live2D)", "2D avatar (Live2D)")) })
        }
        Text(
            tr("IP de este teléfono", "This phone's IP") + ": " + ui.phoneIps.joinToString().ifEmpty { tr("sin Wi-Fi", "no Wi-Fi") },
            color = Ui.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        if (!s.vtsEnabled) {
            Text(
                tr(
                    "En tu programa de avatar elegí el tracking facial por teléfono en red (puerto 49983) y escribí esta IP. " +
                        "Para las expresiones VRM activá también la recepción por el puerto 39539.",
                    "In your avatar app choose phone face tracking over the network (port 49983) and type this IP. " +
                        "For VRM expressions also enable receiving on port 39539.",
                ),
                color = Ui.Muted, fontSize = 12.sp,
            )
        } else {
            Text(
                tr("En tu programa Live2D activá la API de plugins (puerto 8001) y aceptá el aviso de MoonyFace.", "In your Live2D app enable the plugin API (port 8001) and accept the MoonyFace popup."),
                color = Ui.Muted, fontSize = 12.sp,
            )
            OutlinedButton({ vm.discoverVts() }) { Text(tr("Buscar en la red", "Search the network")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ui.connected) Ui.Good else Ui.Bad))
            Spacer(Modifier.width(8.dp))
            Text(
                if (ui.connected) tr("PC conectada", "PC connected") else tr("Esperando a la PC…", "Waiting for the PC…"),
                color = Ui.Text, fontSize = 13.sp,
            )
        }
    }
}

// ======================================================================= pestaña Avanzado

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AdvancedTab(ui: UiState, s: AppSettings, vm: TrackingViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item { GestureEditor(ui, vm) }
        item {
            PurpleCard(tr("Boca", "Mouth")) {
                Text(tr("Límite de apertura: %.0f%%", "Opening limit: %.0f%%").format(s.mouthLimit * 100), color = Ui.Text, fontSize = 13.sp)
                Slider(s.mouthLimit, { v -> vm.update { it.copy(mouthLimit = v) } }, valueRange = 0.4f..1.3f)
                ToggleRow(tr("Boca estable (filtra ruido)", "Stable mouth (filters noise)"), s.mouthStable) { v -> vm.update { it.copy(mouthStable = v) } }
                Text(tr("Simetría: %.0f%%", "Symmetry: %.0f%%").format(s.mouthSymmetry * 100), color = Ui.Text, fontSize = 13.sp)
                Slider(s.mouthSymmetry, { v -> vm.update { it.copy(mouthSymmetry = v) } })
                Text(tr("Boca a los costados: %.0f%%", "Mouth sideways: %.0f%%").format(s.mouthSide * 100), color = Ui.Text, fontSize = 13.sp)
                Slider(s.mouthSide, { v -> vm.update { it.copy(mouthSide = v) } })
            }
        }
        item {
            PurpleCard(tr("Tracking", "Tracking")) {
                GainSlider(tr("Emociones VRM", "VRM emotions"), s.gainEmotions) { v -> vm.update { it.copy(gainEmotions = v) } }
                ToggleRow(tr("Precisión mejorada (malla)", "Enhanced precision (mesh)"), s.precise) { v -> vm.update { it.copy(precise = v) } }
                ToggleRow(tr("Auto-rango para gestos sin calibrar", "Auto-range for uncalibrated shapes"), s.autoRange) { v -> vm.update { it.copy(autoRange = v) } }
                ToggleRow(tr("Envío a 60 fps (interpolado)", "Send at 60 fps (interpolated)"), s.interpolate) { v -> vm.update { it.copy(interpolate = v) } }
                ToggleRow(tr("Ver recorte de la lengua", "Show tongue debug crop"), s.showTongueDebug) { v -> vm.update { it.copy(showTongueDebug = v) } }
                ui.tongueDebug?.let { Image(it, null, Modifier.size(110.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit) }
                Text(tr("Señal de lengua: %.2f", "Tongue signal: %.2f").format(ui.tongueFeature), color = Ui.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                val d = ui.puffDiag
                Text(
                    tr("Cachetes → base %.2f · malla %.2f · surco %.2f · patrón %.2f · permitido %.2f",
                        "Cheeks → base %.2f · mesh %.2f · fold %.2f · pattern %.2f · allowed %.2f")
                        .format(d[0], d[1], d[2], d[3], d[4]),
                    color = Ui.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        item {
            PurpleCard(tr("Rendimiento", "Performance")) {
                Text(
                    tr("tracking %.0f fps · cámara %.0f fps · envío %.0f fps", "tracking %.0f fps · camera %.0f fps · send %.0f fps")
                        .format(ui.fps, ui.cameraFps, ui.sendFps) +
                        "\n%s %.0f ms · %s %.0f ms · total %.0f ms".format(tr("motor", "engine"), ui.detectMs, tr("lengua", "tongue"), ui.tongueMs, ui.processMs) +
                        "\n%s · %s".format(ui.accel, ui.exposure),
                    color = Ui.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
                Text(tr("Resolución de análisis", "Analysis resolution"), color = Ui.Text, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Baja", "Low"), tr("Media", "Medium"), tr("Alta", "High")).forEachIndexed { i, label ->
                        FilterChip(s.resolution == i, { vm.update { it.copy(resolution = i) } }, { Text(label) })
                    }
                }
                ToggleRow(tr("Mostrar la vista de la cara", "Show the face view"), s.showPreview) { v -> vm.update { it.copy(showPreview = v) } }
                Text(tr("Procesador (al reiniciar la app)", "Processor (after restarting the app)"), color = Ui.Text, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(tr("Automático", "Automatic"), "GPU", "CPU").forEachIndexed { i, label ->
                        FilterChip(s.accel == i, { vm.update { it.copy(accel = i) } }, { Text(label) })
                    }
                }
            }
        }
        item {
            PurpleCard(tr("Salidas", "Outputs")) {
                ToggleRow(tr("Salida de rostro (puerto 49983)", "Face output (port 49983)"), s.ifmEnabled) { v -> vm.update { it.copy(ifmEnabled = v) } }
                EditableField(tr("IP fija de la PC (opcional)", "Fixed PC IP (optional)"), s.ifmTargetIp) { v -> vm.update { it.copy(ifmTargetIp = v) } }
                StatusText(ui.ifmStatus)
                ToggleRow(tr("Salida de expresiones VRM", "VRM expressions output"), s.vmcEnabled) { v -> vm.update { it.copy(vmcEnabled = v) } }
                EditableField(tr("IP de la PC (vacío = la misma que la salida de rostro)", "PC IP (empty = same as the face output)"), s.vmcHost) { v -> vm.update { it.copy(vmcHost = v) } }
                EditableField(tr("Puerto de expresiones", "Expressions port"), s.vmcPort.toString(), numeric = true) { v ->
                    v.toIntOrNull()?.let { p -> vm.update { it.copy(vmcPort = p) } }
                }
                ToggleRow(tr("Nombres VRM 1.0", "VRM 1.0 names"), s.vmcVrm1) { v -> vm.update { it.copy(vmcVrm1 = v) } }
                ToggleRow(tr("Enviar también los 52 gestos (perfect sync)", "Also send all 52 shapes (perfect sync)"), s.vmcArkit) { v -> vm.update { it.copy(vmcArkit = v) } }
                ToggleRow(tr("Enviar cabeza y ojos (huesos)", "Send head and eyes (bones)"), s.vmcBones) { v -> vm.update { it.copy(vmcBones = v) } }
                StatusText(ui.vmcStatus)
                ToggleRow(tr("Salida Live2D (API de plugins)", "Live2D output (plugin API)"), s.vtsEnabled) { v -> vm.update { it.copy(vtsEnabled = v) } }
                EditableField(tr("IP de la PC con Live2D", "IP of the Live2D PC"), s.vtsHost) { v -> vm.update { it.copy(vtsHost = v) } }
                EditableField(tr("Puerto API", "API port"), s.vtsPort.toString(), numeric = true) { v ->
                    v.toIntOrNull()?.let { p -> vm.update { it.copy(vtsPort = p) } }
                }
                StatusText(ui.vtsStatus)
            }
        }
        item {
            PurpleCard(tr("Ejes", "Axes")) {
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

// ======================================================================= barra inferior

@Composable
private fun BottomBar(ui: UiState, s: AppSettings, vm: TrackingViewModel, onCalibrate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(22.dp)).background(Ui.Card)
            .border(BorderStroke(1.dp, Ui.CardBorder), RoundedCornerShape(22.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Estado
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▂▄▆", color = if (ui.faceFound) Ui.Accent else Ui.Muted, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ui.connected) tr("Conectado", "Connected") else tr("Sin PC", "No PC"), color = Ui.Text, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (ui.connected) Ui.Good else Ui.Bad))
            }
            Text("MoonyFace", color = Ui.Muted, fontSize = 10.sp)
        }
        Divider()
        // Cámara
        Column(Modifier.weight(1f).clickable { vm.switchCamera() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▣", color = Ui.Accent, fontSize = 16.sp)
            Text(tr("Cámara", "Camera"), color = Ui.Text, fontSize = 12.sp)
            Text((if (s.rearCamera) tr("Trasera", "Rear") else tr("Frontal", "Front")) + " ⌄", color = Ui.Muted, fontSize = 11.sp)
        }
        Divider()
        // Pausa / reanudar
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).border(BorderStroke(3.dp, Ui.Accent), CircleShape)
                    .background(Color(0xFF2E2056)).clickable { vm.togglePause() },
                contentAlignment = Alignment.Center,
            ) { PlayPauseIcon(ui.paused) }
        }
        Divider()
        // Reiniciar
        Column(Modifier.weight(1f).clickable { vm.restartTracking() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⟳", color = Ui.Accent, fontSize = 20.sp)
            Text(tr("Reiniciar", "Restart"), color = Ui.Text, fontSize = 12.sp)
        }
        Divider()
        // Estrella: calibrar
        Column(Modifier.weight(1f).clickable { onCalibrate() }, horizontalAlignment = Alignment.CenterHorizontally) {
            StarIcon(Modifier.size(26.dp))
            Text(tr("Calibrar", "Calibrate"), color = Ui.Text, fontSize = 12.sp)
        }
    }
}

/** Estrella de cuatro puntas (la del logo). */
@Composable
fun StarIcon(modifier: Modifier, color: Color = Ui.Accent) {
    Canvas(modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f; val r = size.minDimension / 2f; val k = r * 0.14f
        val p = Path()
        p.moveTo(cx, cy - r)
        p.cubicTo(cx + k, cy - k, cx + k, cy - k, cx + r, cy)
        p.cubicTo(cx + k, cy + k, cx + k, cy + k, cx, cy + r)
        p.cubicTo(cx - k, cy + k, cx - k, cy + k, cx - r, cy)
        p.cubicTo(cx - k, cy - k, cx - k, cy - k, cx, cy - r)
        p.close()
        drawPath(p, color)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(44.dp).background(Ui.CardBorder))
}

@Composable
private fun PlayPauseIcon(paused: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        if (paused) {
            val p = Path()
            p.moveTo(size.width * 0.2f, 0f); p.lineTo(size.width, size.height / 2f); p.lineTo(size.width * 0.2f, size.height); p.close()
            drawPath(p, Ui.Text)
        } else {
            val w = size.width * 0.3f
            drawRoundRect(Ui.Text, topLeft = Offset(size.width * 0.1f, 0f), size = Size(w, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
            drawRoundRect(Ui.Text, topLeft = Offset(size.width * 0.6f, 0f), size = Size(w, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
        }
    }
}

// ======================================================================= tarjetas compartidas

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalibrationCard(ui: UiState, vm: TrackingViewModel, quick: Boolean) {
    val step = ui.wizardStep
    PurpleCard(tr("Calibrá tu cara", "Calibrate your face"), "✧") {
        if (step == null) {
            val n = ui.calibratedSteps.size
            Text(
                if (n == 0) tr(
                    "La app aprende TUS gestos: neutral, parpadeo, A I U E O, emociones, cachetes y lengua. " +
                        "Te guía con flechas y todo queda guardado.",
                    "The app learns YOUR expressions: neutral, blink, A I U E O, emotions, cheeks and tongue. " +
                        "It guides you with arrows and everything is saved.",
                )
                else tr("$n de ${CalibStep.entries.size} gestos calibrados. Tocá uno para repetirlo.",
                    "$n of ${CalibStep.entries.size} expressions calibrated. Tap one to redo it."),
                color = Ui.Muted, fontSize = 13.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CalibStep.entries.forEach { st ->
                    AssistChip(
                        onClick = { vm.openStep(st) },
                        label = { Text((if (st in ui.calibratedSteps) "✓ " else "") + st.title, fontSize = 12.sp) },
                    )
                }
            }
            ToggleRow(tr("Rápida (solo de frente)", "Quick (front only)"), quick) { v -> vm.update { it.copy(quickCalibration = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.startWizard() }, Modifier.weight(1f)) {
                    Text(if (n == 0) tr("Empezar", "Start") else tr("Recalibrar todo", "Recalibrate all"))
                }
                if (n > 0) OutlinedButton({ vm.resetCalibration() }) { Text(tr("Borrar", "Clear")) }
            }
        } else {
            Text(tr("Paso ${step.ordinal + 1} de ${CalibStep.entries.size}: ", "Step ${step.ordinal + 1} of ${CalibStep.entries.size}: ") + step.title,
                color = Ui.Text, fontWeight = FontWeight.Bold)
            Text(step.instruction, color = Ui.Text, fontSize = 15.sp)
            Text(tr("Consejo: ", "Tip: ") + step.tip, color = Ui.Muted, fontSize = 13.sp)
            if (!quick && step.multiAngle) {
                Text(
                    tr("Mantené el gesto y girá la cabeza según la flecha: frente → izquierda → derecha → arriba → abajo.",
                        "Hold the expression and turn your head as the arrow says: front → left → right → up → down."),
                    color = Ui.Muted, fontSize = 12.sp,
                )
            }
            if (ui.capture == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.captureCurrent() }, Modifier.weight(1f)) { Text(tr("Capturar", "Capture")) }
                    TextButton({ vm.skipStep() }) { Text(tr("Saltar", "Skip")) }
                    TextButton({ vm.closeWizard() }) { Text(tr("Cerrar", "Close")) }
                }
            } else {
                Text(tr("Capturando… mirá las indicaciones sobre la cara.", "Capturing… follow the prompts on the face view."),
                    fontSize = 13.sp, color = Color(0xFFFFCC66))
            }
        }
    }
}

@Composable
private fun ProfilesCard(ui: UiState, vm: TrackingViewModel) {
    var name by remember { mutableStateOf("") }
    PurpleCard(tr("Perfiles", "Profiles"), "☺") {
        Text(tr("Guardá tu calibración y ajustes con un nombre (uno por persona o avatar).",
            "Save your calibration and tuning under a name (one per person or avatar)."), color = Ui.Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text(tr("Nombre", "Name")) }, singleLine = true)
            Spacer(Modifier.width(6.dp))
            Button({ vm.saveProfile(name); name = "" }, enabled = name.isNotBlank()) { Text(tr("Guardar", "Save")) }
        }
        for (p in ui.profiles) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p, color = Ui.Text, modifier = Modifier.weight(1f))
                TextButton({ vm.loadProfile(p) }) { Text(tr("Cargar", "Load")) }
                TextButton({ vm.deleteProfile(p) }) { Text(tr("Borrar", "Delete")) }
            }
        }
    }
}

/** Grupos del editor de gestos. */
private val GESTURE_GROUPS: List<Pair<() -> String, List<String>>> = listOf(
    { tr("Ojos", "Eyes") } to listOf("eyeBlinkLeft", "eyeBlinkRight", "eyeWideLeft", "eyeWideRight", "eyeSquintLeft", "eyeSquintRight"),
    { tr("Boca", "Mouth") } to listOf(
        "jawOpen", "mouthClose", "mouthFunnel", "mouthPucker", "mouthLeft", "mouthRight",
        "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
        "mouthStretchLeft", "mouthStretchRight", "mouthLowerDownLeft", "mouthLowerDownRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "mouthRollLower", "mouthRollUpper",
        "mouthShrugLower", "mouthShrugUpper", "mouthPressLeft", "mouthPressRight",
        "mouthDimpleLeft", "mouthDimpleRight", "jawLeft", "jawRight", "jawForward",
    ),
    { tr("Cejas", "Brows") } to listOf("browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight"),
    { tr("Mejillas y nariz", "Cheeks & nose") } to listOf("cheekPuff", "cheekSquintLeft", "cheekSquintRight", "noseSneerLeft", "noseSneerRight"),
    { tr("Mirada", "Gaze") } to listOf(
        "eyeLookUpLeft", "eyeLookDownLeft", "eyeLookInLeft", "eyeLookOutLeft",
        "eyeLookUpRight", "eyeLookDownRight", "eyeLookInRight", "eyeLookOutRight",
    ),
    { tr("Lengua", "Tongue") } to listOf("tongueOut"),
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GestureEditor(ui: UiState, vm: TrackingViewModel) {
    var group by remember { mutableIntStateOf(1) }
    var open by remember { mutableIntStateOf(-1) }
    @Suppress("UNUSED_VARIABLE") val version = ui.overridesVersion
    PurpleCard(tr("Ajuste por gesto", "Per-gesture tuning"), "✎") {
        Text(
            tr("Blanco = tu valor ahora · azul = reposo · verde = máximo. Tocá un gesto para ajustarlo. Se guarda solo.",
                "White = your value now · blue = rest · green = max. Tap a gesture to tune it. Saved automatically."),
            color = Ui.Muted, fontSize = 12.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GESTURE_GROUPS.forEachIndexed { i, g -> FilterChip(group == i, { group = i; open = -1 }, { Text(g.first()) }) }
        }
        for (name in GESTURE_GROUPS[group].second) {
            val i = ArKit.idx(name)
            val mn = vm.effMin(i)
            val mx = vm.effMax(i)
            Row(Modifier.fillMaxWidth().clickable { open = if (open == i) -1 else i }, verticalAlignment = Alignment.CenterVertically) {
                Text((if (vm.isOverridden(i)) "✎ " else "") + name, Modifier.width(150.dp), color = Ui.Text,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                RangeBar(ui.raw[i], mn, mx, ui.blend[i], Modifier.weight(1f).height(18.dp))
            }
            if (open == i) {
                Column(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    if (i != ArKit.TONGUE_OUT) {
                        val hi = if (mx.isNaN()) 1f else mx
                        Text(tr("Rango: reposo %.2f → máximo %.2f", "Range: rest %.2f → max %.2f").format(mn, hi), color = Ui.Muted, fontSize = 12.sp)
                        RangeSlider(value = mn..hi, onValueChange = { r -> vm.setRange(i, r.start, r.endInclusive) }, valueRange = 0f..1f)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton({ vm.captureLimit(i, false) }) { Text(tr("Ahora = reposo", "Now = rest"), fontSize = 12.sp) }
                            OutlinedButton({ vm.captureLimit(i, true) }) { Text(tr("Ahora = máximo", "Now = max"), fontSize = 12.sp) }
                        }
                    }
                    Text(tr("Intensidad de salida: %.0f%%", "Output intensity: %.0f%%").format(vm.outMax(i) * 100), color = Ui.Muted, fontSize = 12.sp)
                    Slider(vm.outMax(i), { v -> vm.setOut(i, v) })
                    TextButton({ vm.resetGesture(i) }) { Text(tr("Restablecer este gesto", "Reset this gesture")) }
                }
            }
        }
    }
}

@Composable
private fun RangeBar(raw: Float, min: Float, max: Float, out: Float, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        val w = size.width; val h = size.height
        drawRect(Ui.Track, size = size)
        drawRect(Ui.Accent, topLeft = Offset(0f, h * 0.65f), size = Size(w * out.coerceIn(0f, 1f), h * 0.35f))
        if (!max.isNaN()) {
            val x0 = w * min.coerceIn(0f, 1f); val x1 = w * max.coerceIn(0f, 1f)
            drawRect(Color(0x3366FF99), topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(1f), h * 0.6f))
            drawLine(Color(0xFF66FF99), Offset(x1, 0f), Offset(x1, h), strokeWidth = 3f)
        }
        drawLine(Color(0xFF4FA3FF), Offset(w * min.coerceIn(0f, 1f), 0f), Offset(w * min.coerceIn(0f, 1f), h), strokeWidth = 3f)
        val rx = w * raw.coerceIn(0f, 1f)
        drawLine(Color.White, Offset(rx, 0f), Offset(rx, h * 0.6f), strokeWidth = 3f)
    }
}

@Composable
private fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("OK") } },
        title = { Text(tr("Cómo usar MoonyFace", "How to use MoonyFace")) },
        text = {
            Text(
                tr(
                    "1. Apoyá el teléfono a la altura de los ojos, con luz de frente.\n" +
                        "2. Ajustes → Calibrá tu cara (unos 3 minutos).\n" +
                        "3. Ajustes → Conexión: elegí tu programa y escribí la IP del teléfono en la PC.\n" +
                        "4. Si algún gesto queda mal, ajustalo en Avanzado → Ajuste por gesto.\n\n" +
                        "Botones de abajo: pausar, cambiar de cámara y reiniciar el tracking.",
                    "1. Place the phone at eye level, with light in front of you.\n" +
                        "2. Settings → Calibrate your face (about 3 minutes).\n" +
                        "3. Settings → Connection: pick your app and type the phone IP on the PC.\n" +
                        "4. If an expression looks off, tune it in Advanced → Per-gesture tuning.\n\n" +
                        "Bottom buttons: pause, switch camera and restart tracking.",
                ),
            )
        },
    )
}

@Composable
private fun GainSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: %.0f%%".format(value * 100), color = Ui.Text, fontSize = 13.sp)
        Slider(value, onChange, valueRange = 0.3f..2f)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Ui.Text, fontSize = 14.sp)
        Switch(checked, onChange)
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ui.Muted)
}

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
