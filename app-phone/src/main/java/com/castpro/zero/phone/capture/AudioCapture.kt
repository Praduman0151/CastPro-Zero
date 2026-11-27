package com.castpro.zero.phone.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import com.castpro.zero.phone.net.PhoneSenderTransport
import com.castpro.zero.phone.net.SendFailurePolicy
import com.castpro.zero.phone.net.SendOutcome
import com.castpro.zero.protocol.AudioFrame
import com.castpro.zero.protocol.ControlMessage

/**
 * Captures device playback audio from the same MediaProjection used for video and
 * sends 48 kHz stereo PCM16 over the authenticated audio UDP channel. Failure is
 * reported to the caller but never tears down the video cast.
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val sender: PhoneSenderTransport,
    private val onState: (Boolean) -> Unit = {}
) : AutoCloseable {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    fun isActive(): Boolean = running

    fun start() {
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioCapturePolicy.SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioPlaybackCapture could not initialize" }
            record = rec
            sender.sendAudioConfig(ControlMessage.AudioConfig(
                AudioCapturePolicy.SAMPLE_RATE_HZ,
                AudioCapturePolicy.CHANNEL_COUNT,
                AudioCapturePolicy.BIT_DEPTH,
                AudioCapturePolicy.ENCODING_PCM_16BIT
            ))
            rec.startRecording()
            // Report "on" only after recording actually started.
            running = AudioCapturePolicy.shouldReportActive(true)
            onState(running)
            thread = Thread({ pump(rec) }, "CPZ-audio-capture").also { it.start() }
            Log.i(TAG, "Device audio capture started: 48 kHz stereo PCM16 on UDP port ${sender.audioPort}")
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    private fun pump(rec: AudioRecord) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val buffer = ByteArray(AudioCapturePolicy.CHUNK_BYTES)
        val session = sender.sessionId()
        var sequence = 0L
        while (running) {
            val read = try {
                rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture read failed", e)
                break
            }
            if (read <= 0) break
            val timestampNs = AudioCapturePolicy.safeTimestamp(System.nanoTime())
            var fatalSendFailure = false
            try {
                for (chunk in AudioCapturePolicy.chunkPcm(buffer.copyOf(read))) {
                    val outcome = sender.sendAudioFrame(AudioFrame(session, sequence++, timestampNs, chunk))
                    if (outcome is SendOutcome.NotSent && SendFailurePolicy.isFatal(outcome.failure)) {
                        Log.w(TAG, "Audio transmission stopped: ${SendFailurePolicy.message(outcome.failure)}")
                        fatalSendFailure = true
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio transmission failed", e)
                break
            }
            if (fatalSendFailure) break
        }
        running = false
        onState(false)
        Log.i(TAG, "Device audio capture stopped after $sequence datagrams")
    }

    override fun close() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        val worker = thread
        thread = null
        worker?.interrupt()
        try { worker?.join(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { record?.release() } catch (_: Exception) {}
        record = null
        onState(false)
    }

    private companion object { const val TAG = "CPZ-Audio" }
}
