package com.myra.assistant.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {

    companion object {
        const val MIC_RATE = 16000
        const val SPEAKER_RATE = 24000
        const val CHUNK_SIZE = 1024
    }

    var onMicChunk: ((ByteArray) -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>()

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    @Volatile private var recording = false
    @Volatile private var playing = false
    @Volatile var isMuted = false
        private set
    @Volatile var isSpeaking = false
        private set

    fun setMuted(m: Boolean) { isMuted = m }

    // ------------------------------------------------------------ recording
    fun startRecording() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val minBuf = AudioRecord.getMinBufferSize(
            MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, CHUNK_SIZE * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }

        record = rec
        recording = true
        rec.startRecording()
        recordThread = Thread {
            val buf = ByteArray(CHUNK_SIZE)
            while (recording) {
                val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) continue
                val chunk = buf.copyOf(n)
                if (!isMuted && !isSpeaking) {
                    postAmplitude(rms(chunk))
                    onMicChunk?.invoke(chunk)
                }
            }
        }.also { it.name = "myra-mic"; it.start() }
    }

    fun stopRecording() {
        recording = false
        try { recordThread?.join(300) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        recordThread = null
    }

    // ------------------------------------------------------------ playback
    fun startPlayback() {
        if (playing) return
        val minBuf = AudioTrack.getMinBufferSize(
            SPEAKER_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SPEAKER_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SPEAKER_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        playing = true
        t.play()
        playThread = Thread {
            var lastWrite = 0L
            while (playing) {
                val chunk = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }
                if (chunk != null) {
                    if (!isSpeaking) {
                        isSpeaking = true
                        main.post { onSpeakingStarted?.invoke() }
                    }
                    postAmplitude(rms(chunk))
                    try { t.write(chunk, 0, chunk.size) } catch (_: Exception) {}
                    lastWrite = System.currentTimeMillis()
                } else if (isSpeaking && System.currentTimeMillis() - lastWrite > 700) {
                    isSpeaking = false
                    postAmplitude(0f)
                    main.post { onSpeakingStopped?.invoke() }
                }
            }
        }.also { it.name = "myra-speaker"; it.start() }
    }

    fun queueAudio(pcm: ByteArray) { if (playing) queue.offer(pcm) }

    /** Stop MYRA mid-sentence. */
    fun interrupt() {
        queue.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Exception) {}
        if (isSpeaking) {
            isSpeaking = false
            postAmplitude(0f)
            main.post { onSpeakingStopped?.invoke() }
        }
    }

    fun release() {
        playing = false
        stopRecording()
        queue.clear()
        try { playThread?.join(300) } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        isSpeaking = false
    }

    // ------------------------------------------------------------ helpers
    private fun postAmplitude(v: Float) { main.post { onAmplitudeChanged?.invoke(v) } }

    private fun rms(b: ByteArray): Float {
        var sum = 0.0
        var i = 0
        val count = b.size / 2
        if (count == 0) return 0f
        while (i + 1 < b.size) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        return (sqrt(sum / count) / 8000.0).toFloat().coerceIn(0f, 1f)
    }
}
