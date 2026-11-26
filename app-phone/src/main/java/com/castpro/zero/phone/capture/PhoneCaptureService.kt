package com.castpro.zero.phone.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.view.Surface
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean

class PhoneCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val active = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("CastPro-Zero").setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        ServiceCompat.startForeground(this, 41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val consent = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code == 0 || consent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            // Fresh one-use consent data is required for every projection session.
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, consent)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopCapture()
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 1 && height > 1) display?.resize(width, height, resources.displayMetrics.densityDpi)
                }
            }, Handler(mainLooper))
            startEncoder(intent.getIntExtra(EXTRA_BITRATE, 20_000_000))
        } catch (e: Exception) { Log.e(TAG, "Projection startup failed", e); stopCapture() }
        return START_NOT_STICKY
    }

    private fun startEncoder(bitrate: Int) {
        val wifi = getSystemService(WifiManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CPZ-low-latency").also { it.acquire() }
        val format = EncoderFormatPolicy.build(1920, 1080, bitrate.coerceIn(10_000_000, 30_000_000), 60, true)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        catch (_: IllegalArgumentException) {
            enc.configure(EncoderFormatPolicy.build(1920, 1080, bitrate, 60, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codec = enc
        surface = enc.createInputSurface()
        enc.start()
        display = projection?.createVirtualDisplay("CastPro-Zero", 1920, 1080, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        active.set(true)
        Thread({ drain(enc) }, "CPZ-encoder-drain").start()
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (active.get()) {
            val index = try { enc.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            if (index >= 0) {
                // Output is intentionally discarded until an authenticated LAN session is paired.
                // No media is sent to cellular or an internet endpoint.
                enc.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun stopCapture() {
        active.set(false)
        try { display?.release() } catch (_: Exception) {}; display = null
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}; codec = null
        try { surface?.release() } catch (_: Exception) {}; surface = null
        try { projection?.stop() } catch (_: Exception) {}; projection = null
        try { wifiLock?.release() } catch (_: Exception) {}; wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW))
    }
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_BITRATE = "bitrate"
        private const val CHANNEL = "cpz_capture"
        private const val TAG = "CPZ-Capture"
    }
}
