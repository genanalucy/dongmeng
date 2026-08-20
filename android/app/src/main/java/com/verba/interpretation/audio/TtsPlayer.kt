package com.verba.interpretation.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class TtsPlayer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var writtenFrames = 0L
    private var renderedFrames = 0L
    private var lastPlaybackHead = 0L

    fun play(monoPcm: ByteArray, route: PlaybackRoute): Result<Unit> = runCatching {
        val stereo = StereoRouter.routeMonoPcm16(monoPcm, route)
        if (stereo.isEmpty()) return@runCatching
        synchronized(lock) {
            val audioTrack = track ?: createTrack().also {
                track = it
                it.play()
            }
            val written = audioTrack.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
            check(written == stereo.size) { "TTS 音频写入不完整：$written/${stereo.size}" }
            writtenFrames += written / (Short.SIZE_BYTES * 2)
        }
    }

    /** Waits until AudioTrack has rendered every frame accepted by [play]. */
    fun awaitDrained(): Result<Unit> = runCatching {
        while (true) {
            val drained = synchronized(lock) {
                val audioTrack = track ?: return@runCatching
                val currentHead = Integer.toUnsignedLong(audioTrack.playbackHeadPosition)
                val renderedDelta = (currentHead - lastPlaybackHead) and UINT32_MASK
                renderedFrames += renderedDelta
                lastPlaybackHead = currentHead
                renderedFrames >= writtenFrames
            }
            if (drained) return@runCatching
            Thread.sleep(10)
        }
    }

    fun stop() = synchronized(lock) {
        track?.let { audioTrack ->
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            audioTrack.release()
        }
        track = null
        writtenFrames = 0L
        renderedFrames = 0L
        lastPlaybackHead = 0L
    }

    private fun createTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(PcmPacketizer.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            PcmPacketizer.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "设备不支持 16 kHz 立体声 PCM16 播放。" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, PcmPacketizer.PACKET_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val UINT32_MASK = 0xffff_ffffL
    }
}
