package com.avatarmocap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.avatarmocap.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val vm: TrackingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mientras se trackea la pantalla no debe apagarse.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    CameraPermissionGate { MainScreen(vm) }
                }
            }
        }
    }

    @Composable
    private fun CameraPermissionGate(content: @Composable () -> Unit) {
        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }
        LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

        if (granted) content() else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("AvatarMocap necesita la cámara frontal para rastrear tu cara.")
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp)) {
                    Text("Dar permiso")
                }
            }
        }
    }
}
