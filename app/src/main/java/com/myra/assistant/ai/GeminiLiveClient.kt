package com.myra.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.myra.assistant.util.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini Live (BidiGenerateContent) WebSocket client.
 * UI callbacks run on the main thread, EXCEPT onAudioReceived (background thread, queue it directly).
 */
class GeminiLiveClient(private val context: Context) {

    companion object {
        private const val WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val SESSION_RENEW_AFTER_MS = 540_000L
        private const val KEEPALIVE_INTERVAL_MS = 8_000L
        private const val RECONNECT_DELAY_MS = 3_000L

        /** true = realtime_input.media_chunks (as in the spec). false = newer realtime_input.audio format. */
        private const val USE_LEGACY_MEDIA_CHUNKS = true
    }

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile var isReady = false
        private set

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var lastAudioSentAt = 0L
    private var generation = 0

    private val silentChunk: String = Base64.encodeToString(ByteArray(1024), Base64.NO_WRAP)

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (isReady && System.currentTimeMillis() - lastAudioSentAt >= KEEPALIVE_INTERVAL_MS - 500) {
                sendAudioBase64(silentChunk)
            }
            main.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    private val renewRunnable = Runnable {
        if (running) {
            closeSocket()
            scheduleReconnect(300)
        }
    }

    // ------------------------------------------------------------------ public API

    fun connect() {
        val key = Prefs.apiKey(context)
        if (key.isBlank()) {
            onError?.invoke("API key missing. Settings me daalo.")
            return
        }
        running = true
        openSocket(key)
    }

    fun disconnect() {
        running = false
        main.removeCallbacksAndMessages(null)
        closeSocket()
    }

    fun sendAudio(pcm: ByteArray) {
        if (!isReady) return
        sendAudioBase64(Base64.encodeToString(pcm, Base64.NO_WRAP))
    }

    fun sendText(text: String) {
        if (!isReady) return
        val msg = JSONObject().put(
            "client_content", JSONObject()
                .put(
                    "turns", JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                )
                .put("turn_complete", true)
        )
        ws?.send(msg.toString())
    }

    fun interrupt() {
        if (!isReady) return
        val msg = JSONObject().put(
            "client_content", JSONObject().put("turns", JSONArray()).put("turn_complete", true)
        )
        ws?.send(msg.toString())
    }

    // ------------------------------------------------------------------ internals

    private fun sendAudioBase64(b64: String) {
        lastAudioSentAt = System.currentTimeMillis()
        val msg = if (USE_LEGACY_MEDIA_CHUNKS) {
            JSONObject().put(
                "realtime_input", JSONObject().put(
                    "media_chunks", JSONArray().put(
                        JSONObject().put("mime_type", "audio/pcm;rate=16000").put("data", b64)
                    )
                )
            )
        } else {
            JSONObject().put(
                "realtimeInput", JSONObject().put(
                    "audio", JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
                )
            )
        }
        ws?.send(msg.toString())
    }

    private fun openSocket(key: String) {
        generation++
        val myGen = generation
        isReady = false
        val request = Request.Builder().url("$WS_BASE?key=$key").build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGen != generation) return
                webSocket.send(buildSetup().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGen != generation) return
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (myGen != generation) return
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGen != generation) return
                handleDrop("Closed $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGen != generation) return
                handleDrop(t.message ?: "WebSocket failure" + (response?.let { " (${it.code})" } ?: ""))
            }
        })
    }

    private fun handleDrop(reason: String) {
        isReady = false
        main.removeCallbacks(keepAliveRunnable)
        main.removeCallbacks(renewRunnable)
        main.post {
            onDisconnected?.invoke()
            if (running) {
                onError?.invoke("Disconnected: $reason — reconnecting…")
            }
        }
        if (running) scheduleReconnect(RECONNECT_DELAY_MS)
    }

    private fun scheduleReconnect(delayMs: Long) {
        main.postDelayed({
            if (running) openSocket(Prefs.apiKey(context))
        }, delayMs)
    }

    private fun closeSocket() {
        generation++ // invalidate old listener
        isReady = false
        main.removeCallbacks(keepAliveRunnable)
        main.removeCallbacks(renewRunnable)
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        ws = null
    }

    private fun buildSetup(): JSONObject {
        val setup = JSONObject()
            .put("model", Prefs.model(context))
            .put(
                "system_instruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", PromptBuilder.systemPrompt(context)))
                )
            )
            .put(
                "generation_config", JSONObject()
                    .put("response_modalities", JSONArray().put("AUDIO"))
                    .put(
                        "speech_config", JSONObject().put(
                            "voice_config", JSONObject().put(
                                "prebuilt_voice_config", JSONObject().put("voice_name", Prefs.voice(context))
                            )
                        )
                    )
                    .put("temperature", 0.9)
            )
            .put("output_audio_transcription", JSONObject())
            .put("input_audio_transcription", JSONObject())
        return JSONObject().put("setup", setup)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                isReady = true
                main.post {
                    main.removeCallbacks(keepAliveRunnable)
                    main.removeCallbacks(renewRunnable)
                    main.postDelayed(keepAliveRunnable, KEEPALIVE_INTERVAL_MS)
                    main.postDelayed(renewRunnable, SESSION_RENEW_AFTER_MS)
                    onConnected?.invoke()
                }
                return
            }

            if (json.has("error")) {
                val msg = json.optJSONObject("error")?.optString("message") ?: json.optString("error")
                main.post { onError?.invoke(msg) }
            }

            val sc = json.optJSONObject("serverContent") ?: return

            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!data.isNullOrEmpty()) {
                        onAudioReceived?.invoke(Base64.decode(data, Base64.DEFAULT))
                    }
                }
            }

            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
                main.post { onOutputTranscript?.invoke(t) }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
                main.post { onInputTranscript?.invoke(t) }
            }
            if (sc.optBoolean("interrupted", false)) {
                main.post { onInterrupted?.invoke() }
            }
            if (sc.optBoolean("turnComplete", false)) {
                main.post { onTurnComplete?.invoke() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
