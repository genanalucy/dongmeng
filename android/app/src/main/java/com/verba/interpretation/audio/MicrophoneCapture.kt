package com.verba.interpretation.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

sealed interface CaptureResult {
    data object Started : CaptureResult
    data object AlreadyRunning : CaptureResult
    data object Stopped : CaptureResult
    data class Error(val message: String, val cause: Throwable? = null) : CaptureResult
}

class MicrophoneCapture(private val context: Context) {
    private val lock = Any()
    private var active: AtomicBoolean? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    fun start(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit): CaptureResult = synchronized(lock) {
        if (active?.get() == true) return CaptureResult.AlreadyRunning
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return CaptureResult.Error("未授予麦克风权限。")
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            PcmPacketizer.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return CaptureResult.Error("设备不支持 16 kHz 单声道 PCM16 录音。")
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PcmPacketizer.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, PcmPacketizer.PACKET_BYTES * 2),
            )
        } catch (error: SecurityException) {
            return CaptureResult.Error("麦克风权限被系统拒绝。", error)
        } catch (error: IllegalArgumentException) {
            return CaptureResult.Error("无法创建录音设备。", error)
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return CaptureResult.Error("录音设备初始化失败。")
        }
        try {
            audioRecord.startRecording()
        } catch (error: SecurityException) {
            audioRecord.release()
            return CaptureResult.Error("麦克风权限被系统拒绝。", error)
        } catch (error: IllegalStateException) {
            audioRecord.release()
            return CaptureResult.Error("录音设备无法启动。", error)
        }
        val sessionActive = AtomicBoolean(true)
        recorder = audioRecord
        active = sessionActive
        captureThread = thread(name = "verba-audio-capture", isDaemon = true) {
            val packetizer = PcmPacketizer(onPacket = onPacket)
            val buffer = ByteArray(PcmPacketizer.PACKET_BYTES)
            try {
                while (sessionActive.get()) {
                    val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        read > 0 -> packetizer.offer(buffer, read)
                        read == AudioRecord.ERROR_DEAD_OBJECT -> throw IllegalStateException("录音设备已断开。")
                        read < 0 -> throw IllegalStateException("录音读取失败：$read")
                    }
                }
            } catch (error: Throwable) {
                if (sessionActive.getAndSet(false)) onError(error.message ?: "录音发生未知错误。")
            } finally {
                synchronized(lock) {
                    if (recorder === audioRecord) recorder = null
                    if (active === sessionActive) active = null
                    if (captureThread === Thread.currentThread()) captureThread = null
                }
                runCatching { audioRecord.stop() }
                audioRecord.release()
            }
        }
        CaptureResult.Started
    }

    fun stop(): CaptureResult {
        val record: AudioRecord?
        val worker: Thread?
        synchronized(lock) {
            val sessionActive = active
            if (sessionActive == null && recorder == null) return CaptureResult.Stopped
            sessionActive?.set(false)
            record = recorder
            worker = captureThread
            active = null
            recorder = null
            captureThread = null
        }
        runCatching { record?.stop() }
        if (worker !== Thread.currentThread()) worker?.join(1_000)
        return CaptureResult.Stopped
    }
}
