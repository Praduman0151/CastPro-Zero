package com.castpro.zero.phone

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.castpro.zero.phone.capture.BitrateOptions
import com.castpro.zero.phone.capture.PhoneCaptureService
import com.castpro.zero.phone.net.LocalNetworkBinding
import com.castpro.zero.phone.net.PhoneDiscovery
import com.castpro.zero.phone.scan.OfflineQrAnalyzer
import com.castpro.zero.protocol.PairingPayloadCodec
import com.castpro.zero.protocol.ProtocolException
import com.castpro.zero.protocol.SenderStats

private data class TvDevice(val ip: String, val token: String, val tcpPort: Int, val udpPort: Int)

private data class PhoneUiState(
    val devices: Map<String, TvDevice> = emptyMap(),
    val selectedIp: String = "",
    val token: String = "",
    val manualIp: String = "",
    val bitrateMbps: Int = BitrateOptions.DEFAULT_MBPS,
    val stats: SenderStats = SenderStats(),
    val streaming: Boolean = false,
    val scanner: Boolean = false,
    val message: String = "Scan the TV QR code or discover a receiver on this LAN."
)

class MainActivity : ComponentActivity() {
    private val ui = mutableStateOf(PhoneUiState())
    private var discovery: PhoneDiscovery? = null
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    private val requestCameraPermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ui.value = if (granted) {
                ui.value.copy(scanner = true, message = "Point the camera at the TV QR code.")
            } else {
                ui.value.copy(message = "Camera permission is needed for QR pairing.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) {
                ui.value = ui.value.copy(message = "Screen capture consent was cancelled.")
                return@registerForActivityResult
            }
            val ip = ui.value.selectedIp.ifBlank { ui.value.manualIp }
            val token = ui.value.token
            if (ip.isBlank() || token.isBlank()) {
                ui.value = ui.value.copy(message = "Choose a receiver and pair before casting.")
                return@registerForActivityResult
            }
            val device = ui.value.devices[ip]
            startForegroundService(Intent(this, PhoneCaptureService::class.java).apply {
                putExtra(PhoneCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PhoneCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(PhoneCaptureService.EXTRA_TV_IP, ip)
                putExtra(PhoneCaptureService.EXTRA_PAIRING_TOKEN, token)
                putExtra(PhoneCaptureService.EXTRA_TCP_PORT, device?.tcpPort ?: 45670)
                putExtra(PhoneCaptureService.EXTRA_UDP_PORT, device?.udpPort ?: 45671)
                putExtra(PhoneCaptureService.EXTRA_BITRATE, ui.value.bitrateMbps)
            })
        }
        setContent {
            MaterialTheme {
                val state by ui
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (state.streaming) {
                        StreamingScreen(state, onStop = { stopService(Intent(this, PhoneCaptureService::class.java)) })
                    } else {
                        SetupScreen(
                            state = state,
                            lifecycleOwner = this,
                            onDiscover = { discovery?.scan() },
                            onSelect = { ip -> ui.value = ui.value.copy(selectedIp = ip) },
                            onManualIp = { value -> ui.value = ui.value.copy(manualIp = value, selectedIp = value) },
                            onToken = { value -> ui.value = ui.value.copy(token = value) },
                            onBitrate = { value -> ui.value = ui.value.copy(bitrateMbps = value) },
                            onToggleScanner = { open ->
                                if (open) requestCameraPermission.launch(Manifest.permission.CAMERA)
                                else ui.value = ui.value.copy(scanner = false)
                            },
                            onQr = { text -> acceptQr(text) },
                            onStart = {
                                val manager = getSystemService(MediaProjectionManager::class.java)
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
                            }
                        )
                    }
                }
            }
        }
    }

    private fun acceptQr(text: String) {
        try {
            val payload = PairingPayloadCodec.decode(text)
            ui.value = ui.value.copy(
                selectedIp = payload.ipAddress,
                token = payload.pairingToken,
                scanner = false,
                message = "Paired with ${payload.ipAddress}. Choose a bitrate and start casting."
            )
        } catch (e: ProtocolException) {
            ui.value = ui.value.copy(message = "Invalid or expired pairing QR: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneSessionBus.onStats = { snapshot -> runOnUiThread { ui.value = ui.value.copy(stats = snapshot) } }
        PhoneSessionBus.onStreamingChanged = { active -> runOnUiThread { ui.value = ui.value.copy(streaming = active) } }
        try {
            discovery = PhoneDiscovery(LocalNetworkBinding(this)) { payload ->
                val device = TvDevice(payload.ipAddress, payload.pairingToken, payload.tcpPort, payload.udpPort)
                runOnUiThread {
                    val current = ui.value
                    ui.value = current.copy(
                        devices = current.devices + (device.ip to device),
                        selectedIp = current.selectedIp.ifBlank { device.ip },
                        token = current.token.ifBlank { device.token },
                        message = "Found TV at ${device.ip}"
                    )
                }
            }
            discovery?.scan()
        } catch (e: Exception) {
            ui.value = ui.value.copy(message = "LAN discovery unavailable: ${e.message}")
        }
    }

    override fun onStop() {
        discovery?.close()
        discovery = null
        PhoneSessionBus.onStats = null
        PhoneSessionBus.onStreamingChanged = null
        super.onStop()
    }
}

@Composable
private fun SetupScreen(
    state: PhoneUiState,
    lifecycleOwner: LifecycleOwner,
    onDiscover: () -> Unit,
    onSelect: (String) -> Unit,
    onManualIp: (String) -> Unit,
    onToken: (String) -> Unit,
    onBitrate: (Int) -> Unit,
    onToggleScanner: (Boolean) -> Unit,
    onQr: (String) -> Unit,
    onStart: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CastPro-Zero Phone", style = MaterialTheme.typography.headlineMedium)
        Text(state.message)
        if (state.scanner) {
            QrScanner(lifecycleOwner, onQr)
            Button(onClick = { onToggleScanner(false) }) { Text("Close scanner") }
        } else {
            Button(onClick = { onToggleScanner(true) }) { Text("Scan TV QR code") }
        }
        Button(onClick = onDiscover) { Text("Discover TVs on this LAN") }
        state.devices.values.forEach { device ->
            Button(onClick = { onSelect(device.ip) }, modifier = Modifier.fillMaxWidth()) {
                Text("${device.ip}  ·  TCP ${device.tcpPort} / UDP ${device.udpPort}")
            }
        }
        OutlinedTextField(
            value = state.manualIp,
            onValueChange = onManualIp,
            label = { Text("Manual TV local IP") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.token,
            onValueChange = onToken,
            label = { Text("Pairing token") },
            modifier = Modifier.fillMaxWidth()
        )
        Text("Bitrate: ${state.bitrateMbps} Mbps")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BitrateOptions.optionsMbps.forEach { value ->
                Button(onClick = { onBitrate(value) }, enabled = value != state.bitrateMbps) {
                    Text("$value")
                }
            }
        }
        Button(
            onClick = onStart,
            enabled = state.selectedIp.isNotBlank() && state.token.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start casting") }
    }
}

@Composable
private fun StreamingScreen(state: PhoneUiState, onStop: () -> Unit) {
    val stats = state.stats
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Casting to ${state.selectedIp}", style = MaterialTheme.typography.headlineMedium)
        Text("${stats.fps} fps · ${String.format("%.2f", stats.mbps)} Mbps · ${stats.codec}")
        Text("RTT: ${if (stats.rttMs < 0) "—" else "${stats.rttMs} ms"} · PLI: ${stats.pliCount}")
        Text("Frames sent: ${stats.framesSent} · bytes: ${stats.bytesSent}")
        Button(onClick = onStop) { Text("Stop") }
    }
}

@Composable
private fun QrScanner(lifecycleOwner: LifecycleOwner, onQr: (String) -> Unit) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, OfflineQrAnalyzer(onQr)) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.w("CPZ-Phone-UI", "QR scanner setup failed", e)
            }
        }
        providerFuture.addListener(listener, executor)
        onDispose {
            try { providerFuture.get().unbindAll() } catch (_: Exception) {}
        }
    }
    AndroidView({ previewView }, Modifier.fillMaxWidth().padding(top = 8.dp))
}
