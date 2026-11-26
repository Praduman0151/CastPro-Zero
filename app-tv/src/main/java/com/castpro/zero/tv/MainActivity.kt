package com.castpro.zero.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.castpro.zero.protocol.ControlMessage
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var receiver: TvCastServer
    private lateinit var connectivity: ConnectivityManager
    private var surfaceView: AspectRatioSurfaceView? = null
    private var qr by mutableStateOf<android.graphics.Bitmap?>(null)
    private var address by mutableStateOf("Searching for private Wi-Fi / Ethernet…")
    private var ipAddress by mutableStateOf<String?>(null)
    private var status by mutableStateOf("Starting TV receiver…")
    private var telemetry by mutableStateOf("Waiting for receiver telemetry")
    private var showHud by mutableStateOf(false)
    private var hasRendered by mutableStateOf(false)
    private var streamWidth by mutableIntStateOf(16)
    private var streamHeight by mutableIntStateOf(9)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (::receiver.isInitialized) receiver.refreshNetwork() }
        override fun onLost(network: Network) {
            runOnUiThread {
                ipAddress = null
                qr = null
                address = "Searching for private Wi-Fi / Ethernet…"
            }
            if (::receiver.isInitialized) receiver.refreshNetwork()
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            if (::receiver.isInitialized) receiver.refreshNetwork()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        receiver = TvCastServer(object : TvCastServer.Callbacks {
            override fun onStatus(value: String) { runOnUiThread { status = value } }
            override fun onAddress(ip: String, token: String) {
                runOnUiThread {
                    ipAddress = ip
                    address = "IP  $ip     TCP  45670     UDP  45671"
                    qr = QrCode.pairingBitmap(ip, 45670, 45671, token,
                        System.currentTimeMillis() / 1000L + 3600L)
                }
            }
            override fun onStreamConfig(config: ControlMessage.StreamConfig) {
                runOnUiThread {
                    streamWidth = config.width.coerceAtLeast(1)
                    streamHeight = config.height.coerceAtLeast(1)
                    surfaceView?.setVideoAspect(streamWidth, streamHeight)
                }
            }
            override fun onFirstFrameRendered() { runOnUiThread { hasRendered = true } }
            override fun onTelemetry(value: String) { runOnUiThread { telemetry = value } }
            override fun onSessionIdle() { runOnUiThread { hasRendered = false } }
        })
        receiver.start()
        try { connectivity.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) { }
        setContent {
            LaunchedEffect(ipAddress) {
                while (true) {
                    ipAddress?.let { ip -> qr = QrCode.pairingBitmap(ip, 45670, 45671, receiver.pairingToken,
                        System.currentTimeMillis() / 1000L + 3600L) }
                    delay(60_000)
                }
            }
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { context ->
                            AspectRatioSurfaceView(context).also { view ->
                                surfaceView = view
                                view.holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        if (holder.surface.isValid) receiver.attachSurface(holder.surface)
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        if (holder.surface.isValid) receiver.attachSurface(holder.surface)
                                    }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { receiver.detachSurface() }
                                })
                            }
                        },
                        modifier = Modifier.align(Alignment.Center).fillMaxSize()
                    )
                    if (!hasRendered) {
                        Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("CastPro-Zero", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                            qr?.let { Image(it.asImageBitmap(), "Offline pairing QR code", Modifier.size(260.dp)) }
                            Text(address, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(status, color = Color.LightGray)
                            Text("Scan this QR code from your phone on the same LAN", color = Color.LightGray)
                        }
                    }
                    Column(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
                        Button(onClick = { showHud = !showHud }, modifier = Modifier.focusable()) {
                            Text(if (showHud) "Hide HUD" else "Info / HUD")
                        }
                        if (showHud) Surface(color = Color(0xDD101820), shape = MaterialTheme.shapes.medium) {
                            Text(telemetry, color = Color.White, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO)) {
            showHud = !showHud
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
        receiver.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

private class AspectRatioSurfaceView(context: Context) : SurfaceView(context) {
    @Volatile private var videoWidth = 16
    @Volatile private var videoHeight = 9
    fun setVideoAspect(width: Int, height: Int) {
        if (width > 0 && height > 0) { videoWidth = width; videoHeight = height; requestLayout() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec); return
        }
        val fitted = SurfaceLayoutPolicy.fit(availableWidth, availableHeight, videoWidth, videoHeight)
        setMeasuredDimension(fitted.width, fitted.height)
    }
}