package com.avatarmocap.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avatarmocap.TrackingViewModel
import com.avatarmocap.output.IFacialMocapServer
import com.avatarmocap.tracking.ArKit

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
    LaunchedEffect(previewView) { vm.startCamera(owner, previewView) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        // ---------- Vista previa + estado
        item { Column {
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp))) {
                AndroidView({ previewView }, Modifier.fillMaxWidth().height(300.dp))
                Column(
                    Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp)
                ) {
                    Text(
                        if (ui.faceFound) "● Cara detectada" else "○ Sin cara",
                        color = if (ui.faceFound) Color(0xFF66FF99) else Color(0xFFFF6666),
                    )
                    Text("%.0f fps · %s".format(ui.fps, if (ui.gpu) "GPU" else "CPU"), fontSize = 12.sp)
                    Text(
                        "cabeza p%.0f° y%.0f° r%.0f°".format(ui.headRot[0], ui.headRot[1], ui.headRot[2]),
                        fontSize = 12.sp,
                    )
                }
            }
            if (ui.message.isNotEmpty()) {
                Text(ui.message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
        } }

        // ---------- Lengua
        item {
            Section("Lengua (experimental)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("tongueOut %.2f".format(ui.tongueValue))
                        LinearProgressIndicator(
                            progress = { ui.tongueValue },
                            modifier = Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp),
                        )
                        Text(
                            "señal %.2f  (f0 %.2f → f1 %.2f)".format(ui.tongueFeature, ui.tongueF0, ui.tongueF1),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    ui.tongueDebug?.let {
                        Spacer(Modifier.width(8.dp))
                        Image(it, null, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                    }
                }
                Text(
                    "Calibrá en este orden, con buena luz de frente:",
                    fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.calibrateTongue(1) }, Modifier.weight(1f)) { Text("1. Boca abierta", fontSize = 12.sp) }
                    OutlinedButton({ vm.calibrateTongue(2) }, Modifier.weight(1f)) { Text("2. Lengua afuera", fontSize = 12.sp) }
                }
                ToggleRow("Mostrar recorte de depuración", s.showTongueDebug) { v -> vm.update { it.copy(showTongueDebug = v) } }
            }
        }

        // ---------- Calibración
        item {
            Section("Calibración de cara") {
                Text(
                    if (ui.neutralCalibrated) "Neutral calibrado ✓" else "Sin calibrar: la pose y los gestos pueden quedar desplazados",
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.calibrateNeutral() }, Modifier.weight(1f)) { Text("Calibrar neutral") }
                    OutlinedButton({ vm.resetAutoRange() }, Modifier.weight(1f)) { Text("Reiniciar rango") }
                }
                ToggleRow("Auto-rango (estira cada gesto hasta 100%)", s.autoRange) { v -> vm.update { it.copy(autoRange = v) } }
                Text("Suavizado: %.0f%%".format(s.smoothing * 100), fontSize = 13.sp)
                Slider(s.smoothing, { v -> vm.update { it.copy(smoothing = v) } })
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
                Text("Escribí esa IP en el programa de la PC eligiendo \"iFacialMocap\".", fontSize = 12.sp)
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

        // ---------- Ajustes de ejes
        item {
            Section("Ejes y orientación") {
                ToggleRow("Intercambiar izquierda/derecha", s.swapLeftRight) { v -> vm.update { it.copy(swapLeftRight = v) } }
                ToggleRow("Invertir giro (yaw)", s.invertYaw) { v -> vm.update { it.copy(invertYaw = v) } }
                ToggleRow("Invertir cabeceo (pitch)", s.invertPitch) { v -> vm.update { it.copy(invertPitch = v) } }
                ToggleRow("Invertir inclinación (roll)", s.invertRoll) { v -> vm.update { it.copy(invertRoll = v) } }
                ToggleRow("Invertir ojos horizontal", s.invertEyesX) { v -> vm.update { it.copy(invertEyesX = v) } }
                ToggleRow("Invertir ojos vertical", s.invertEyesY) { v -> vm.update { it.copy(invertEyesY = v) } }
                ToggleRow("Usar GPU (requiere reiniciar)", s.useGpu) { v -> vm.update { it.copy(useGpu = v) } }
            }
        }

        // ---------- Blendshapes
        item { Text("Blendshapes ARKit (52)", style = MaterialTheme.typography.titleMedium) }
        items(ArKit.COUNT) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ArKit.NAMES[i], Modifier.width(170.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                LinearProgressIndicator(
                    progress = { ui.blend[i] },
                    modifier = Modifier.weight(1f).height(8.dp),
                    color = if (i == ArKit.TONGUE_OUT) Color(0xFFFF6699) else MaterialTheme.colorScheme.primary,
                )
            }
        }
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
