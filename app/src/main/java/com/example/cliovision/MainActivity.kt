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
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
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

    // Permissions your app needs
    private val PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val PERMISSION_REQUEST_CODE = 100

    // Coroutine scope tied to the Activity's lifecycle
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val geminiPipeline = GeminiPipeline()
    private lateinit var tts: TextToSpeech
    private var lastCapturedBitmap: Bitmap? = null

    private lateinit var statusText: TextView
    private lateinit var testButton: Button

    private var streamSession: StreamSession? = null

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()

    private lateinit var connectMockButton: Button
    private lateinit var disconnectMockButton: Button

    private var currentBuildingName: String? = null

    private var isPipelineRunning = false

    private val permissionsResultLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrElse { PermissionStatus.Denied }
            permissionContinuation?.resume(status, onCancellation = null)
            permissionContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Wire up UI
        statusText = findViewById(R.id.statusText)
        testButton = findViewById(R.id.testButton)
        connectMockButton = findViewById(R.id.connectMockButton)
        disconnectMockButton = findViewById(R.id.disconnectMockButton)

        connectMockButton.setOnClickListener {
            MockDeviceManager.connectMockDevice(this)
            statusText.text = "Mock glasses connected"

            val testImage = loadRandomTestImage()
            if (testImage != null) {
                lastCapturedBitmap = testImage
                statusText.text = "Test image loaded — ready"
                testButton.isEnabled = true
                disconnectMockButton.isEnabled = true
                connectMockButton.isEnabled = false
            } else {
                statusText.text = "No campus images found in assets"
            }
        }

        disconnectMockButton.setOnClickListener {
            MockDeviceManager.disconnectMockDevice()
            statusText.text = "Mock glasses disconnected"
            connectMockButton.isEnabled = true
            testButton.isEnabled = false
            disconnectMockButton.isEnabled = false
        }

        // Test button triggers the full pipeline
        testButton.setOnClickListener {
            statusText.text = "Capturing..."
            testButton.isEnabled = false
            runPipeline()
        }

        //Set up TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                Log.d("TTS", "Text-to-speech ready")
            } else {
                Log.e("TTS", "Text-to-speech init failed")
            }
        }

        scope.launch {
            handleMetaFlow()
        }

        if (!permissionsGranted()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }
    private suspend fun handleMetaFlow() {
        if (android.os.Build.PRODUCT.contains("sdk") || android.os.Build.MODEL.contains("Emulator")) {
            requestWearablesRegistration() // Jump straight to this
        } else {
            // 1. Check/Request Camera Permission using the sequential method
            val status = checkAndRequestCamera()

            if (status == PermissionStatus.Granted) {
                // 2. Observe Registration and Start Stream
                Wearables.registrationState.collect { state ->
                    when (state) {
                        is RegistrationState.Registered -> startGlassesStream()
                        is RegistrationState.Available -> requestWearablesRegistration()
                        else -> Log.d("Wearables", "State: $state")
                    }
                }
            } else {
                statusText.text = "Meta Camera Permission Denied"
            }
        }
    }

    private suspend fun checkAndRequestCamera(): PermissionStatus {
        // Check current status
        val currentStatus = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
        return if (currentStatus == PermissionStatus.Granted) {
            currentStatus
        } else {
            requestWearablesPermission(Permission.CAMERA)
        }
    }

    // Convenience method provided by Meta documentation
    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                permissionsResultLauncher.launch(permission)
            }
        }
    }

    // Check if all required permissions are already granted
    private fun permissionsGranted() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // Start a stream of glasses video frames and decode each frame
    private fun startGlassesStream() {
        scope.launch {
            try {
                // 1. Direct implementation matching official Meta patterns
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

                // 2. Collect Video Stream (for your LLM pipeline)
                launch {
                    session.videoStream.collect { frame ->
                        // This updates the bitmap your Gemini pipeline uses
                        Log.d("Wearables", "Received a frame!")
                        lastCapturedBitmap = decodeVideoFrame(frame)
                    }
                }

                // 3. Collect Session State (to handle disconnections)
                launch {
                    session.state.collect { state ->
                        Log.d("Wearables", "Stream state: $state")
                        // If using MockDevice, this will trigger when you call .reset()
                        if (state == StreamSessionState.STOPPED) {
                            streamSession = null
                        }
                    }
                }

            } catch (e: Exception) {
                // Replaces .onFailure
                Log.e("Wearables", "Failed to start stream: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Stream Error: ${e.message}"
                }
            }
        }
    }

    private fun decodeVideoFrame(frame: VideoFrame): Bitmap? {
        // In 0.5.0, we convert the ByteBuffer to a Bitmap
        val bytes = ByteArray(frame.buffer.remaining())
        frame.buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // Record a short audio clip on a background thread, return raw PCM bytes
    fun captureAudio(durationMs: Int = 3000): ByteArray? {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
            ) return null
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        val audioData = ByteArray((sampleRate * (durationMs / 1000.0)).toInt() * 2)
        recorder.startRecording()
        recorder.read(audioData, 0, audioData.size)
        recorder.stop()
        recorder.release()
        return audioData
    }

    private fun sendToLLM(
        imageBitmap: Bitmap?,
        audioBytes: ByteArray?,
        overrideQuestion: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                // Step 1: Get the question (transcribe audio OR use override)
                val question = when {
                    overrideQuestion != null -> overrideQuestion
                    audioBytes != null -> {
                        geminiPipeline.transcribeAudio(audioBytes).getOrElse {
                            Log.e("Pipeline", "Transcription failed, using fallback")
                            "What can you tell me about what I'm looking at?"
                        }
                    }
                    else -> "What can you tell me about what I'm looking at?"
                }

                // Step 2: Add building context hint
                val contextualQuestion = currentBuildingName?.let {
                    "$question (You are looking at $it)"
                } ?: question

                Log.d("Pipeline", "Question: $contextualQuestion")

                // Step 3: Send image + question to Gemini (only once)
                if (imageBitmap == null) {
                    Log.e("Pipeline", "No image available")
                    return@launch
                }

                geminiPipeline.sendImageAndQuestion(imageBitmap, contextualQuestion)
                    .onSuccess { text ->
                        Log.d("Pipeline", "Response: $text")
                        withContext(Dispatchers.Main) {
                            speakResponse(text)
                            statusText.text = "Done — check Logcat"
                        }
                    }
                    .onFailure { e ->
                        Log.e("Pipeline", "Gemini failed: ${e.message}")
                        withContext(Dispatchers.Main) {
                            statusText.text = "Error: ${e.message?.take(50)}"
                        }
                    }

            } catch (e: Exception) {
                Log.e("Pipeline", "Unexpected error: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Unexpected error"
                }
            } finally {
                // Always reset the guard and re-enable the button
                withContext(Dispatchers.Main) {
                    isPipelineRunning = false
                    testButton.isEnabled = true
                }
            }
        }
    }
    private fun speakResponse(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "response")
    }

    fun runPipeline() {
        // Prevent double execution
        if (isPipelineRunning) {
            Log.d("Pipeline", "Already running, ignoring tap")
            return
        }

        val currentFrame = lastCapturedBitmap
        if (currentFrame == null) {
            statusText.text = "No image loaded"
            return
        }

        isPipelineRunning = true
        statusText.text = "Analyzing..."
        testButton.isEnabled = false

        val isEmulator = android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.PRODUCT.contains("sdk")

        if (isEmulator) {
            sendToLLM(currentFrame, null, "What building is this and what is it used for?")
        } else {
            scope.launch(Dispatchers.IO) {
                val audio = captureAudio(durationMs = 4000)
                sendToLLM(currentFrame, audio)
            }
        }
    }

    private fun loadRandomTestImage(): Bitmap? {
        return try {
            val buildingFolders = assets.list("campus_images") ?: return null
            if (buildingFolders.isEmpty()) return null

            val randomFolder = buildingFolders.random()
            currentBuildingName = randomFolder
                .replace("_", " ")
                .replaceFirstChar { it.uppercase() }
            Log.d("TestImage", "Selected building: $currentBuildingName")

            val images = assets.list("campus_images/$randomFolder") ?: return null
            if (images.isEmpty()) return null

            val randomImage = images.random()
            assets.open("campus_images/$randomFolder/$randomImage").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e("TestImage", "Failed to load image: ${e.message}")
            null
        }
    }

    fun requestWearablesRegistration() {
        Wearables.startRegistration(this)
    }

    fun requestWearablesUnregistration() {
        Wearables.startUnregistration(this)
    }

    override fun onPause() {
        super.onPause()
        streamSession?.close()
        streamSession = null
    }

    override fun onResume() {
        super.onResume()
        // Restart the stream if we are already registered
        if (Wearables.registrationState.value is RegistrationState.Registered) {
            startGlassesStream()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        scope.cancel()
    }
}