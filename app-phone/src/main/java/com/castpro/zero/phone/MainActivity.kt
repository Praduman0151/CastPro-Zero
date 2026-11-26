package com.castpro.zero.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import androidx.compose.foundation.layout.height
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
import com.castpro.zero.phone.BitrateSessionState
import com.castpro.zero.phone.scan.OfflineQrAnalyzer
import com.castpro.zero.protocol.SenderStats
import com.castpro.zero.protocol.TvPairingPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private data class PhoneUiState(
    val devices: Map<String, TvPairingPayload> = emptyMap(),
    val selectedIp: String = "",
    val token: String = "",
    val manualIp: String = "",
    val tcpPort: String = "45670",
    val udpPort: String = "45671",
    val bitrateMbps: Int = BitrateOptions.DEFAULT_MBPS,
    val sessionState: BitrateSessionState = BitrateSessionState(),
    val stats: SenderStats = SenderStats(),
    val streaming: Boolean = false,
    val scanner: Boolean = false,
    val message: String = "Discover a TV on this LAN or scan its offline QR code."
)

class MainActivity : ComponentActivity() {
    private val ui = mutableStateOf(PhoneUiState())
    private var discovery: PhoneDiscovery? = null
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var continueAfterNotificationPrompt = false

    private val requestCameraPermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ui.value = if (granted) {
                ui.value.copy(scanner = true, message = "Point the camera at the TV QR code.")
            } else {
                ui.value.copy(message = "Camera permission is required to scan a QR code.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                ui.value = ui.value.copy(message = "Screen capture consent was cancelled.")
                return@registerForActivityResult
            }
            val state = ui.value
            startForegroundService(Intent(this, PhoneCaptureService::class.java).apply {
                putExtra(PhoneCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PhoneCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(PhoneCaptureService.EXTRA_TV_IP, state.selectedIp)
                putExtra(PhoneCaptureService.EXTRA_PAIRING_TOKEN, state.token)
                putExtra(PhoneCaptureService.EXTRA_TCP_PORT, state.tcpPort.toInt())
                putExtra(PhoneCaptureService.EXTRA_UDP_PORT, state.udpPort.toInt())
                putExtra(PhoneCaptureService.EXTRA_BITRATE, state.bitrateMbps)
            })
        }
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (continueAfterNotificationPrompt) {
                    continueAfterNotificationPrompt = false
                    if (!granted) ui.value = ui.value.copy(
                        message = "Notifications are disabled; continuing with the foreground capture service."
                    )
                    requestProjectionConsent()
                }
            }

        setContent {
            MaterialTheme {
                val state by ui
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (state.streaming) {
                        StreamingScreen(state) {
                            startService(Intent(this, PhoneCaptureService::class.java)
                                .setAction(PhoneCaptureService.ACTION_STOP))
                        }
                    } else {
                        SetupScreen(
                            state = state,
                            lifecycleOwner = this,
                            onDiscover = { discovery?.scan() },
                            onSelect = { payload ->
                                ui.value = ui.value.copy(
                                    selectedIp = payload.ipAddress,
                                    manualIp = payload.ipAddress,
                                    token = payload.pairingToken,
                                    tcpPort = payload.tcpPort.toString(),
                                    udpPort = payload.udpPort.toString(),
                                    message = "Selected TV at ${payload.ipAddress}"
                                )
                            },
                            onManualIp = { value -> ui.value = ui.value.copy(manualIp = value, selectedIp = value) },
                            onTcpPort = { value -> ui.value = ui.value.copy(tcpPort = value) },
                            onUdpPort = { value -> ui.value = ui.value.copy(udpPort = value) },
                            onToken = { value -> ui.value = ui.value.copy(token = value) },
                            onBitrate = { value -> ui.value = ui.value.copy(bitrateMbps = value, sessionState = ui.value.sessionState.select(value)) },
                            onToggleScanner = { open ->
                                if (open) requestCameraPermission.launch(Manifest.permission.CAMERA)
                                else ui.value = ui.value.copy(scanner = false)
                            },
                            onQr = { acceptQr(it) },
                            onStart = { validateAndStart() }
                        )
                    }
                }
            }
        }
    }

    private fun acceptQr(text: String) {
        try {
            val target = PhoneTargetValidator.qr(text, System.currentTimeMillis() / 1000L)
            ui.value = ui.value.copy(
                selectedIp = target.ipAddress,
                manualIp = target.ipAddress,
                token = target.pairingToken,
                tcpPort = target.tcpPort.toString(),
                udpPort = target.udpPort.toString(),
                scanner = false,
                message = "Paired with ${target.ipAddress}. Select a bitrate and start casting."
            )
        } catch (e: Exception) {
            ui.value = ui.value.copy(message = "Invalid or expired pairing QR: ${e.message}")
        }
    }

    private fun validateAndStart() {
        try {
            val state = ui.value
            val target = PhoneTargetValidator.manual(
                state.selectedIp.ifBlank { state.manualIp },
                state.tcpPort,
                state.udpPort,
                state.token
            )
            ui.value = state.copy(
                selectedIp = target.ipAddress,
                manualIp = target.ipAddress,
                message = "Requesting screen capture permission…"
            )
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                continueAfterNotificationPrompt = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestProjectionConsent()
            }
        } catch (e: Exception) {
            ui.value = ui.value.copy(message = "Receiver details are invalid: ${e.message}")
        }
    }

    private fun requestProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onStart() {
        super.onStart()
        PhoneSessionBus.onStats = { snapshot -> runOnUiThread { ui.value = ui.value.copy(stats = snapshot) } }
        PhoneSessionBus.onStreamingChanged = { active ->
            runOnUiThread { ui.value = ui.value.copy(streaming = active, sessionState = if (active) ui.value.sessionState.connectedNow() else ui.value.sessionState.stopped()) }
        }
        try {
            discovery = PhoneDiscovery(this, LocalNetworkBinding(this)) { payload ->
                runOnUiThread {
                    val current = ui.value
                    ui.value = current.copy(
                        devices = current.devices + (payload.ipAddress to payload),
                        message = "Found TV at ${payload.ipAddress}"
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
    onSelect: (TvPairingPayload) -> Unit,
    onManualIp: (String) -> Unit,
    onTcpPort: (String) -> Unit,
    onUdpPort: (String) -> Unit,
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
        Text("CastPro-Zero", style = MaterialTheme.typography.headlineMedium)
        Text(state.message)
        if (state.scanner) {
            QrScanner(lifecycleOwner, onQr)
            Button(onClick = { onToggleScanner(false) }) { Text("Close QR scanner") }
        } else {
            Button(onClick = { onToggleScanner(true) }) { Text("Scan TV QR code (offline)") }
        }
        Button(onClick = onDiscover) { Text("Discover TVs on this LAN") }
        state.devices.values.forEach { device ->
            Button(onClick = { onSelect(device) }, modifier = Modifier.fillMaxWidth()) {
                Text("${device.ipAddress} · TCP ${device.tcpPort} / UDP ${device.udpPort}")
            }
        }
        OutlinedTextField(
            value = state.manualIp, onValueChange = onManualIp,
            label = { Text("Manual TV private IPv4") }, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.tcpPort, onValueChange = onTcpPort,
                label = { Text("TCP port") }, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = state.udpPort, onValueChange = onUdpPort,
                label = { Text("UDP port") }, modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = state.token, onValueChange = onToken,
            label = { Text("Pairing token") }, modifier = Modifier.fillMaxWidth()
        )
        Text("Video bitrate: ${state.bitrateMbps} Mbps")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BitrateOptions.optionsMbps.forEach { value ->
                Button(onClick = { onBitrate(value) }, enabled = value != state.bitrateMbps) {
                    Text("$value Mbps")
                }
            }
        }
        Button(
            onClick = onStart,
            enabled = state.selectedIp.isNotBlank() || state.manualIp.isNotBlank(),
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
        Text("${stats.fps} fps · ${String.format("%.2f", stats.mbps)} Mbps · ${stats.codec} · ${stats.resolution}")
        Text("TCP RTT: ${if (stats.rttMs < 0) "—" else "${stats.rttMs} ms"} · PLI: ${stats.pliCount}")
        Text("Frames sent: ${stats.framesSent} · bytes: ${stats.bytesSent}")
        Button(onClick = onStop) { Text("Stop Casting") }
    }
}

@Composable
private fun QrScanner(lifecycleOwner: LifecycleOwner, onQr: (String) -> Unit) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val delivered = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, OfflineQrAnalyzer { value ->
                    if (delivered.compareAndSet(false, true)) onQr(value)
                })
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.w("CPZ-Phone-UI", "Offline QR camera setup failed", e)
            }
        }, mainExecutor)
        onDispose {
            try { providerFuture.get().unbindAll() } catch (_: Exception) {}
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(300.dp))
}
