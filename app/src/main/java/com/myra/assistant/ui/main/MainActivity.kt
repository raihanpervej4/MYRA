package com.myra.assistant.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.myra.assistant.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.PromptBuilder
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.util.Prefs
import com.myra.assistant.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var orb: OrbAnimationView
    private lateinit var waveform: WaveformView
    private lateinit var statusText: TextView
    private lateinit var redOverlay: View
    private lateinit var micButton: ImageButton
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView
    private lateinit var chatRecycler: RecyclerView
    private val chatAdapter = ChatAdapter()

    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null

    private var isMuted = false
    private var isActive = false
    private var isInCallMode = false
    private var greeted = false
    private var pendingCaller: String? = null
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    private var recognizer: SpeechRecognizer? = null
    private var recognizeTries = 0

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startSystemServices()
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) audioEngine?.startRecording()
    }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { endCallMode() }
    }

    private val statusRunnable = object : Runnable {
        override fun run() { updateStatusBar(); handler.postDelayed(this, 30_000) }
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        initViews()
        checkPermissions()
        startSystemServices()
        handler.post(statusRunnable)
        ContextCompat.registerReceiver(
            this, callEndedReceiver, IntentFilter(CallMonitorService.ACTION_CALL_ENDED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.postDelayed({ initGeminiLive() }, 300)
        handleIncomingCallIntent(intent)

        viewModel.commandResult.observe(this) { res ->
            if (res != null) {
                geminiLive?.sendText("(system) Phone action result: $res — user ko ek chhote sentence me batao.")
                viewModel.clearResult()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    override fun onPause() { super.onPause(); audioEngine?.setMuted(true) }
    override fun onResume() { super.onResume(); if (!isMuted && !isInCallMode) audioEngine?.setMuted(false) }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        geminiLive?.disconnect()
        audioEngine?.release()
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ init
    private fun initViews() {
        orb = findViewById(R.id.orbView)
        waveform = findViewById(R.id.waveformView)
        statusText = findViewById(R.id.statusText)
        redOverlay = findViewById(R.id.redOverlay)
        micButton = findViewById(R.id.micButton)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        timeText = findViewById(R.id.timeText)
        chatRecycler = findViewById(R.id.chatRecycler)

        chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatRecycler.adapter = chatAdapter

        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        micButton.setOnClickListener { setActiveMode(isMuted || !isActive) }
        micButton.setOnLongClickListener {
            audioEngine?.interrupt()
            geminiLive?.interrupt()
            status("Ruk gayi 🤐")
            true
        }
    }

    private fun initGeminiLive() {
        if (Prefs.apiKey(this).isBlank()) {
            status("Settings me API key daalo ⚙️")
            Toast.makeText(this, "Pehle Settings me Gemini API key daalo", Toast.LENGTH_LONG).show()
            return
        }
        val live = GeminiLiveClient(this)
        val audio = AudioEngine(this)
        geminiLive = live
        audioEngine = audio

        audio.onMicChunk = { bytes ->
            if (!isMuted && !isInCallMode && live.isReady) live.sendAudio(bytes)
        }
        audio.onAmplitudeChanged = { a -> orb.setAmplitude(a); waveform.setAmplitude(a) }
        audio.onSpeakingStarted = {
            orb.setState(OrbAnimationView.State.SPEAKING)
            status("Bol rahi hoon...")
        }
        audio.onSpeakingStopped = {
            if (isActive) { orb.setState(OrbAnimationView.State.LISTENING); status("Sun rahi hoon...") }
            else orb.setState(OrbAnimationView.State.IDLE)
        }

        live.onAudioReceived = { pcm -> audio.queueAudio(pcm) }
        live.onInputTranscript = { t ->
            inputBuffer.append(t)
            if (!audio.isSpeaking) orb.setState(OrbAnimationView.State.THINKING)
        }
        live.onOutputTranscript = { t -> outputBuffer.append(t) }
        live.onInterrupted = { audio.interrupt() }
        live.onTurnComplete = { flushTranscripts() }
        live.onError = { msg -> status(msg.take(60)) }
        live.onDisconnected = { status("Reconnecting…") }
        live.onConnected = {
            audio.startPlayback()
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) audio.startRecording()
            if (!greeted) setActiveMode(true)
            val caller = pendingCaller
            if (caller != null) {
                pendingCaller = null
                greeted = true
                announceCall(caller)
            } else if (!greeted) {
                greeted = true
                handler.postDelayed({ sendGreeting() }, 600)
            }
        }
        status("Connecting…")
        live.connect()
    }

    private fun sendGreeting() {
        geminiLive?.sendText(
            "(system) Say this greeting aloud in your own voice, exactly once, nothing more: \"${PromptBuilder.greeting(this)}\""
        )
    }

    // ------------------------------------------------------------------ transcripts / commands
    private fun flushTranscripts() {
        val user = inputBuffer.toString().trim()
        val myra = outputBuffer.toString().trim()
        inputBuffer.clear(); outputBuffer.clear()

        if (user.isNotEmpty()) {
            chatAdapter.addMessage(ChatMessage(user, true))
            CommandParser.parse(user)?.let { viewModel.execute(it) }
        }
        if (myra.isNotEmpty() && chatAdapter.addMessage(ChatMessage(myra, false))) {
            // added
        }
        chatRecycler.scrollToPosition(maxOf(chatAdapter.itemCount - 1, 0))
    }

    // ------------------------------------------------------------------ active mode / UI state
    private fun setActiveMode(active: Boolean) {
        isActive = active
        isMuted = !active
        audioEngine?.setMuted(isMuted)
        micButton.setImageResource(if (active) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
        redOverlay.animate().alpha(if (active) 0.08f else 0f).setDuration(if (active) 300 else 500).start()
        orb.setState(if (active) OrbAnimationView.State.LISTENING else OrbAnimationView.State.IDLE)
        status(if (active) "Sun rahi hoon..." else "Tap karke bolo 💬")
    }

    private fun status(s: String) { statusText.text = s }

    private fun updateStatusBar() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        batteryText.text = "BAT ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val used = ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt()
        ramText.text = "RAM $used%"
        timeText.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    // ------------------------------------------------------------------ incoming call
    private fun handleIncomingCallIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra(CallMonitorService.EXTRA_INCOMING, false)) return
        val name = i.getStringExtra(CallMonitorService.EXTRA_CALLER) ?: "Unknown"
        i.removeExtra(CallMonitorService.EXTRA_INCOMING)
        if (geminiLive?.isReady == true) announceCall(name) else pendingCaller = name
    }

    private fun announceCall(name: String) {
        isInCallMode = true
        recognizeTries = 0
        audioEngine?.setMuted(false)
        geminiLive?.sendText(
            "(system) Say this aloud right now, exactly: \"Sir, $name ka call aa raha hai. Uthau ya reject karu?\""
        )
        status("Call: $name")
        handler.postDelayed({ startCallRecognition() }, 4500)
    }

    private fun startCallRecognition() {
        if (!isInCallMode) return
        if (!SpeechRecognizer.isRecognitionAvailable(this) || !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            status("Speech recognition available nahi"); return
        }
        audioEngine?.stopRecording() // free the mic for SpeechRecognizer
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ") ?: ""
                    handleCallDecision(said)
                }
                override fun onError(error: Int) { retryRecognition() }
                override fun onReadyForSpeech(params: Bundle?) { status("Bolo: uthao / reject 🎤") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
            )
        }
    }

    private fun retryRecognition() {
        if (!isInCallMode) return
        if (++recognizeTries <= 2) handler.postDelayed({ startCallRecognition() }, 600)
        else { status("Call: samajh nahi aaya"); endCallMode() }
    }

    private fun handleCallDecision(said: String) {
        val t = said.lowercase()
        val reject = Regex("reject|nahi|nahin|mat|cut|kaat|decline|\\bno\\b").containsMatchIn(t)
        val accept = Regex("uth|utha|haan|\\bhan\\b|\\bha\\b|yes|accept|receive|pick").containsMatchIn(t)
        when {
            reject -> {
                viewModel.rejectCall()
                geminiLive?.sendText("(system) Call reject kar di. Bolo: \"Theek hai, call reject kar di.\"")
                endCallMode()
            }
            accept -> {
                viewModel.acceptCall()
                geminiLive?.sendText("(system) Call utha li. Bolo: \"Ok, call utha li.\"")
                endCallMode()
            }
            else -> retryRecognition()
        }
    }

    private fun endCallMode() {
        if (!isInCallMode) return
        isInCallMode = false
        handler.removeCallbacksAndMessages(null)
        handler.post(statusRunnable)
        recognizer?.destroy(); recognizer = null
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) audioEngine?.startRecording()
        setActiveMode(false) // standby
    }

    // ------------------------------------------------------------------ permissions & services
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { !hasPerm(it) }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun startSystemServices() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, CallMonitorService::class.java))
        } catch (e: Exception) { e.printStackTrace() }
    }
}
