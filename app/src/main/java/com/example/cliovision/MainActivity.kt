package com.example.cliovision

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.core.types.*
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.PermissionError
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.widget.Button
import android.widget.TextView
import java.io.File

class MainActivity : ComponentActivity() {

    // ── Permissions ───────────────────────────────────────────────────────
    private val PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val PERMISSION_REQUEST_CODE = 100

    // ── Core components ───────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val geminiPipeline = GeminiPipeline()
    private lateinit var tts: TextToSpeech

    // ── State ─────────────────────────────────────────────────────────────
    private var lastCapturedBitmap: Bitmap? = null
    private var currentBuildingName: String? = null
    private var isPipelineRunning = false
    private var streamSession: StreamSession? = null
    private var isGlassesConnected = false
    private var latestBuildingContext: String = "Unknown location on campus"

    // ── UI ────────────────────────────────────────────────────────────────
    private lateinit var statusText: TextView
    private lateinit var testButton: Button
    private lateinit var connectMockButton: Button
    private lateinit var disconnectMockButton: Button
    private lateinit var connectGlassesButton: Button
    private lateinit var locationText: TextView

    private lateinit var responseText: TextView


    // ── Meta SDK ──────────────────────────────────────────────────────────
    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()

    private val permissionsResultLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrElse { PermissionStatus.Denied }
            permissionContinuation?.resume(status, onCancellation = null)
            permissionContinuation = null
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Wire up UI
        statusText = findViewById(R.id.statusText)
        testButton = findViewById(R.id.testButton)
        connectMockButton = findViewById(R.id.connectMockButton)
        disconnectMockButton = findViewById(R.id.disconnectMockButton)
        connectGlassesButton = findViewById(R.id.connectGlassesButton)
        locationText = findViewById(R.id.locationText)
        responseText = findViewById(R.id.responseText)

        setupButtons()
        setupTTS()
        scope.launch(Dispatchers.Default) {
            setupLocationTracking()
        }

        scope.launch(Dispatchers.Main) {
            handleMetaFlow()
        }

        if (!permissionsGranted()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }

    // ── Button Setup ──────────────────────────────────────────────────────
    private fun setupButtons() {

        // Mock connect — for emulator testing
        connectMockButton.setOnClickListener {
            MockDeviceManager.connectMockDevice(this)
            val testImage = loadRandomTestImage()
            if (testImage != null) {
                lastCapturedBitmap = testImage
                statusText.text = "Mock ready: $currentBuildingName"
                testButton.isEnabled = true
                disconnectMockButton.isEnabled = true
                connectMockButton.isEnabled = false
            } else {
                statusText.text = "No campus images found"
            }
        }

        // Mock disconnect
        disconnectMockButton.setOnClickListener {
            MockDeviceManager.disconnectMockDevice()
            lastCapturedBitmap = null
            currentBuildingName = null
            statusText.text = "Mock disconnected"
            connectMockButton.isEnabled = true
            testButton.isEnabled = false
            disconnectMockButton.isEnabled = false
        }

        // Real glasses connect — for field testing
        connectGlassesButton.setOnClickListener {
            if (!isGlassesConnected) {
                connectRealGlasses()
            } else {
                disconnectRealGlasses()
            }
        }

        // Run pipeline manually (mock testing)
        testButton.setOnClickListener {
            testButton.isEnabled = false
            runPipeline()
        }
    }

    // ── Real Glasses Connection ───────────────────────────────────────────
    private fun connectRealGlasses() {
        statusText.text = "Connecting to glasses..."
        connectGlassesButton.isEnabled = false

        scope.launch {
            try {
                val status = checkAndRequestCamera()
                if (status == PermissionStatus.Granted) {
                    // Start observing registration state
                    Wearables.registrationState.collect { state ->
                        when (state) {
                            is RegistrationState.Registered -> {
                                Log.d("Glasses", "Glasses registered!")
                                withContext(Dispatchers.Main) {
                                    isGlassesConnected = true
                                    statusText.text = "Glasses connected — listening..."
                                    connectGlassesButton.text = "Disconnect Glasses"
                                    connectGlassesButton.isEnabled = true
                                }
                                if (!isPipelineRunning) {
                                    isPipelineRunning = true
                                    Log.d("Glasses", "Starting pipeline for the first time")
                                    startGlassesStream()
                                    startListeningForVoiceCommands()
                                }
                            }
                            is RegistrationState.Available, is RegistrationState.Unavailable -> {
                                // Reset flag if we lose registration
                                isPipelineRunning = false
                                requestWearablesRegistration()
                            }
                            else -> {
                                Log.d("Glasses", "Registration state: $state")
                                isPipelineRunning = false
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Camera permission denied"
                        connectGlassesButton.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e("Glasses", "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Connection failed: ${e.message}"
                    connectGlassesButton.isEnabled = true
                }
            }
        }
    }

    private fun disconnectRealGlasses() {
        streamSession?.close()
        streamSession = null
        isGlassesConnected = false
        lastCapturedBitmap = null
        statusText.text = "Glasses disconnected"
        connectGlassesButton.text = "Connect Glasses"
        testButton.isEnabled = false
        Log.d("Glasses", "Glasses disconnected by user")
    }

    // ── Voice Command Listening ───────────────────────────────────────────
    // This runs continuously on real glasses — when audio is detected
    // above a silence threshold, it triggers the pipeline automatically
    private fun startListeningForVoiceCommands() {
        scope.launch(Dispatchers.IO) {
            Log.d("VoiceCommand", "Listening for voice commands...")
            while (isGlassesConnected) {
                // Wait for audio activity then trigger pipeline
                val audio = captureAudio(durationMs = 4000)
                if (audio != null && hasAudioActivity(audio)) {
                    Log.d("VoiceCommand", "Voice detected — running pipeline")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Question detected..."
                    }
                    runPipelineWithAudio(audio)
                }
                // Short pause between listening cycles
                delay(500)
            }
        }
    }

    // Simple silence detection — checks if audio has meaningful content
    private fun hasAudioActivity(audioBytes: ByteArray): Boolean {
        var sum = 0L
        for (i in audioBytes.indices step 2) {
            if (i + 1 < audioBytes.size) {
                val sample = (audioBytes[i + 1].toInt() shl 8) or
                        (audioBytes[i].toInt() and 0xFF)
                sum += sample * sample
            }
        }
        val rms = Math.sqrt(sum.toDouble() / (audioBytes.size / 2))
        // Threshold — adjust this if it's too sensitive or not sensitive enough
        return rms > 800
    }

    // ── Glasses Stream ────────────────────────────────────────────────────
    private fun startGlassesStream() {
        scope.launch {
            try {
                val session = Wearables.startStreamSession(
                    context = this@MainActivity,
                    deviceSelector = AutoDeviceSelector(),
                    streamConfiguration = StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
                        frameRate = 24
                    )
                )
                streamSession = session
                Log.d("Wearables", "Stream session established")

                launch {
                    session.videoStream.collect { frame ->
                        lastCapturedBitmap = decodeVideoFrame(frame)
                    }
                }

                launch {
                    session.state.collect { state ->
                        Log.d("Wearables", "Stream state: $state")
                        if (state == StreamSessionState.STOPPED) {
                            streamSession = null
                            if (isGlassesConnected) {
                                Log.d("Wearables", "Stream stopped — restarting...")
                                delay(1000)
                                startGlassesStream()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Wearables", "Stream failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Stream error: ${e.message}"
                }
            }
        }
    }

    private fun decodeVideoFrame(frame: VideoFrame): Bitmap? {
        val bytes = ByteArray(frame.buffer.remaining())
        frame.buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // ── Location Setup ────────────────────────────────────────────────────
    private fun setupLocationTracking() {
        scope.launch(Dispatchers.IO) {
            CampusLocationManager.loadBuildingsFromExcel(this@MainActivity)
        }
        if (ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
            CampusLocationManager.startTracking(this@MainActivity)
        }

        // Observe location changes and update UI
        scope.launch {
            CampusLocationManager.nearestLocation.collect { nearest ->
                nearest?.let {
                    locationText.text = "Near: ${it.name}"
                    Log.d("Location", "Near: ${it.name}")
                } ?: run {
                    locationText.text = "Locating..."
                }
            }
        }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────
    fun runPipeline() {
        if (isPipelineRunning) return

        val currentFrame = lastCapturedBitmap
        if (currentFrame == null) {
            statusText.text = "No image available"
            testButton.isEnabled = true
            return
        }

        isPipelineRunning = true
        statusText.text = "Analyzing..."

        val isEmulator = android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.PRODUCT.contains("sdk")

        if (isEmulator) {
            sendToLLM(currentFrame, null,
                "What building is this and what is it used for?")
        } else {
            scope.launch(Dispatchers.IO) {
                val audio = captureAudio(durationMs = 4000)
                sendToLLM(currentFrame, audio)
            }
        }
    }

    // Called from voice command listener with pre-captured audio
    private fun runPipelineWithAudio(audioBytes: ByteArray) {
        if (isPipelineRunning) return
        val currentFrame = lastCapturedBitmap ?: return

        isPipelineRunning = true
        sendToLLM(currentFrame, audioBytes)
    }

    private fun sendToLLM(
        imageBitmap: Bitmap?,
        audioBytes: ByteArray?,
        overrideQuestion: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // Step 1: Get the question
                val question = when {
                    overrideQuestion != null -> overrideQuestion
                    audioBytes != null -> {
                        geminiPipeline.transcribeAudio(audioBytes).getOrElse {
                            "What can you tell me about what I'm looking at?"
                        }
                    }
                    else -> "What can you tell me about what I'm looking at?"
                }

                // Step 2: Add location context from GPS
                val locationContext = CampusLocationManager.getLocationContext(
                    CampusLocationManager.currentLocation.value
                )

                // Step 3: Add building name hint if known (mock mode)
                val contextualQuestion = currentBuildingName?.let {
                    "$question (You are looking at $it)"
                } ?: question

                Log.d("Pipeline", "Question: $contextualQuestion")
                Log.d("Pipeline", "Location context: $locationContext")

                if (imageBitmap == null) return@launch

                // Step 4: Send to Gemini with location context
                geminiPipeline.sendImageAndQuestion(
                    bitmap = imageBitmap,
                    question = contextualQuestion,
                    locationContext = locationContext
                ).onSuccess { text ->
                    Log.d("Pipeline", "Response: $text")
                    withContext(Dispatchers.Main) {
                        speakResponse(text)
                        statusText.text = "Done"
                        responseText.text = text
                    }
                }.onFailure { e ->
                    Log.e("Pipeline", "Gemini failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Error"
                        responseText.text = "Something went wrong. Please try again."
                    }
                }

            } catch (e: Exception) {
                Log.e("Pipeline", "Unexpected error: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Unexpected error"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isPipelineRunning = false
                    testButton.isEnabled = true
                }
            }
        }
    }

    // ── Audio Capture ─────────────────────────────────────────────────────
    fun captureAudio(durationMs: Int = 3000): ByteArray? {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) return null

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        val audioData = ByteArray((sampleRate * (durationMs / 1000.0)).toInt() * 2)
        recorder.startRecording()
        recorder.read(audioData, 0, audioData.size)
        recorder.stop()
        recorder.release()
        return audioData
    }

    // ── TTS ───────────────────────────────────────────────────────────────
    private fun setupTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                Log.d("TTS", "TTS ready")
            } else {
                Log.e("TTS", "TTS init failed")
            }
        }
    }

    private fun speakResponse(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "response")
    }

    // ── Meta SDK Helpers ──────────────────────────────────────────────────
    private suspend fun handleMetaFlow() {
        val isEmulator = android.os.Build.PRODUCT.contains("sdk") ||
                android.os.Build.MODEL.contains("Emulator")
        if (isEmulator) {
            requestWearablesRegistration()
        }
        // On real device, connection is handled by connectRealGlasses()
    }

    private suspend fun checkAndRequestCamera(): PermissionStatus {
        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        val current = result.getOrNull()
        return if (current == PermissionStatus.Granted) {
            current
        } else {
            requestWearablesPermission(Permission.CAMERA)
        }
    }

    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                permissionsResultLauncher.launch(permission)
            }
        }
    }

    private fun permissionsGranted() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && permissionsGranted()) {
            setupLocationTracking()
        }
    }

    fun requestWearablesRegistration() {
        Wearables.startRegistration(this)
    }

    fun requestWearablesUnregistration() {
        Wearables.startUnregistration(this)
    }

    // ── Image Loading (mock testing) ──────────────────────────────────────
    private fun loadRandomTestImage(): Bitmap? {
        return try {
            val buildingFolders = assets.list("campus_images") ?: return null
            if (buildingFolders.isEmpty()) return null

            val randomFolder = buildingFolders.random()
            currentBuildingName = randomFolder
                .replace("_", " ")
                .replaceFirstChar { it.uppercase() }
            Log.d("TestImage", "Selected: $currentBuildingName")

            val images = assets.list("campus_images/$randomFolder") ?: return null
            if (images.isEmpty()) return null

            val randomImage = images.random()
            assets.open("campus_images/$randomFolder/$randomImage").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e("TestImage", "Failed: ${e.message}")
            null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onPause() {
        super.onPause()
        if (!isGlassesConnected) {
            streamSession?.close()
            streamSession = null
        }
        CampusLocationManager.stopTracking()
    }

    override fun onResume() {
        super.onResume()
        setupLocationTracking()
        try {
            if (isGlassesConnected &&
                Wearables.registrationState.value is RegistrationState.Registered &&
                streamSession == null) {
                startGlassesStream()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Wearables not ready: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CampusLocationManager.stopTracking()
        tts.stop()
        tts.shutdown()
        scope.cancel()
    }
}