package com.ai.assistance.operit.ui.videocall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.api.gemini.GeminiLiveVisionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

enum class CallState {
    LISTENING,  // 聆听中
    THINKING,   // 观察画面并思考中
    SPEAKING    // AI 正在说话
}

class VideoCallActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var geminiClient: GeminiLiveVisionClient
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var previewSurface: Surface? = null
    private var isUsingFrontCamera = false
    private var latestBitmap: Bitmap? = null
    private var lastFrameTime = 0L

    private val callState = mutableStateOf(CallState.LISTENING)
    private val isMuted = mutableStateOf(false)
    private val recognizedText = mutableStateOf("")
    private val aiResponseText = mutableStateOf("正在启动摄像头...")

    private val sentenceBuffer = StringBuilder()
    private val sentenceDelimiters = setOf('。', '！', '？', '，', '；', '\n', '.', '!', '?', ';')

    // 动态申请相机和麦克风权限
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted) {
            openCameraAsync()
        } else {
            Toast.makeText(this, "未授予相机权限，无法显示画面", Toast.LENGTH_LONG).show()
        }

        if (audioGranted) {
            setupSpeechRecognizer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultEncodedKey = "QVEuQWI4Uk42THZrcm04Sk53cEtJenhIRWZWWVdkTEI4OFFzVktaWEVqRlFYTEpMN3ZzYkE="
        val fallbackKey = try {
            String(Base64.decode(defaultEncodedKey, Base64.DEFAULT)).trim()
        } catch (_: Exception) { "" }

        val apiKey = intent.getStringExtra("GEMINI_API_KEY")
            ?: getSharedPreferences("gemini_config", Context.MODE_PRIVATE).getString("api_key", fallbackKey)
            ?: fallbackKey

        geminiClient = GeminiLiveVisionClient(apiKey)

        try {
            tts = TextToSpeech(this, this)
        } catch (_: Exception) {}

        setContent {
            VideoCallScreen(
                callState = callState.value,
                aiText = aiResponseText.value,
                userText = recognizedText.value,
                isMuted = isMuted.value,
                onToggleMute = { isMuted.value = !isMuted.value },
                onSwitchCamera = { switchCamera() },
                onEndCall = { finish() },
                onOrbClick = { onOrbTapped() },
                onTextureAvailable = { surface ->
                    previewSurface = surface
                    checkPermissionsAndStart()
                }
            )
        }
    }

    private fun checkPermissionsAndStart() {
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (!hasCamera || !hasAudio) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            openCameraAsync()
            setupSpeechRecognizer()
        }
    }

    private fun openCameraAsync() {
        val surface = previewSurface ?: return
        startBackgroundThread()

        backgroundHandler?.post {
            try {
                val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val targetFacing = if (isUsingFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                val cameraId = manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
                } ?: manager.cameraIdList.first()

                imageReader?.close()
                imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime >= 600) {
                            lastFrameTime = now
                            latestBitmap = imageToBitmap(image)
                        }
                        image.close()
                    }, backgroundHandler)
                }

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                imageReader?.surface?.let { addTarget(it) }
                            }
                            camera.createCaptureSession(
                                listOf(surface, imageReader!!.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        captureSession = session
                                        session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                                        runOnUiThread {
                                            aiResponseText.value = "AI 已连接画面！请对我说话，或点击光球提问"
                                        }
                                    }
                                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                                },
                                backgroundHandler
                            )
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            cameraDevice = null
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            cameraDevice = null
                        }
                    }, backgroundHandler)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@VideoCallActivity, "启动相机失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun switchCamera() {
        backgroundHandler?.post {
            captureSession?.close()
            cameraDevice?.close()
            cameraDevice = null
            captureSession = null

            isUsingFrontCamera = !isUsingFrontCamera
            openCameraAsync()
        }
    }

    private fun setupSpeechRecognizer() {
        runOnUiThread {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                aiResponseText.value = "摄像头已就绪！点击中央光球让 AI 看画面回答"
                return@runOnUiThread
            }

            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {
                            stopAndClearSpeech()
                            callState.value = CallState.LISTENING
                        }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            // 关键修复：绝对不在 onError 中同步死循环调用 startListening！
                            // 延时 2 秒后再尝试，彻底杜绝主线程卡死卡顿！
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (callState.value != CallState.SPEAKING && !isFinishing) {
                                    startListeningSafely()
                                }
                            }, 2000)
                        }
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            if (text.isNotBlank() && !isMuted.value) {
                                recognizedText.value = text
                                processQuery(text)
                            } else {
                                startListeningSafely()
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
                startListeningSafely()
            } catch (_: Exception) {}
        }
    }

    private fun startListeningSafely() {
        if (isFinishing || isMuted.value) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {}
    }

    // 用户点击光球主动触发提问或主动让 AI 看画面
    private fun onOrbTapped() {
        stopAndClearSpeech()
        callState.value = CallState.THINKING
        aiResponseText.value = "正在分析画面..."
        processQuery("请帮我看看当前画面里有什么，并用亲切生动的语音告诉我")
    }

    private fun processQuery(prompt: String) {
        callState.value = CallState.THINKING
        aiResponseText.value = "正在观察画面并思考..."
        sentenceBuffer.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = geminiClient.streamAnalyzeFrameAndPrompt(
                bitmap = latestBitmap,
                prompt = prompt
            ) { chunk ->
                runOnUiThread {
                    if (callState.value == CallState.THINKING) {
                        callState.value = CallState.SPEAKING
                        aiResponseText.value = ""
                    }
                    aiResponseText.value += chunk
                    handleStreamChunkForTTS(chunk)
                }
            }

            result.onSuccess {
                runOnUiThread {
                    val remaining = sentenceBuffer.toString().trim()
                    if (remaining.isNotEmpty()) {
                        tts?.speak(remaining, TextToSpeech.QUEUE_ADD, null, "FinalUtterance")
                        sentenceBuffer.clear()
                    }
                }
            }.onFailure { err ->
                runOnUiThread {
                    aiResponseText.value = "提示: ${err.localizedMessage}"
                    callState.value = CallState.LISTENING
                    startListeningSafely()
                }
            }
        }
    }

    private fun handleStreamChunkForTTS(chunk: String) {
        for (char in chunk) {
            sentenceBuffer.append(char)
            if (char in sentenceDelimiters && sentenceBuffer.length >= 4) {
                val sentence = sentenceBuffer.toString().trim()
                if (sentence.isNotEmpty()) {
                    tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, "Utterance_${System.currentTimeMillis()}")
                }
                sentenceBuffer.clear()
            }
        }
    }

    private fun stopAndClearSpeech() {
        sentenceBuffer.clear()
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CHINESE
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (utteranceId == "FinalUtterance" && callState.value == CallState.SPEAKING) {
                            callState.value = CallState.LISTENING
                            startListeningSafely()
                        }
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
            tts?.speak("你好，我已经连接到你的画面，请问有什么可以帮你？", TextToSpeech.QUEUE_FLUSH, null, "Welcome")
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 65, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAndClearSpeech()
        try { tts?.shutdown() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        stopBackgroundThread()
    }
}

@Composable
fun VideoCallScreen(
    callState: CallState,
    aiText: String,
    userText: String,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndCall: () -> Unit,
    onOrbClick: () -> Unit,
    onTextureAvailable: (Surface) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. 全屏摄像头取景预览（采用 TextureView，完美呈现画面）
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            onTextureAvailable(Surface(surface))
                        }
                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. 顶部状态与字幕提示卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 20.dp, end = 20.dp)
                .align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xD91E1E2E))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = when (callState) {
                            CallState.LISTENING -> "🟢 正在聆听您的声音 (可随时说话或点击光球)..."
                            CallState.THINKING -> "🟣 正在观察画面并思考..."
                            CallState.SPEAKING -> "🔵 AI 正在流式回答..."
                        },
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = aiText,
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    )
                    if (userText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "您: $userText",
                            color = Color(0xFF90CAF9),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // 3. 屏幕中央动态灵动光球动效（点击光球可直接让 AI 看画面回答）
        val orbColors = when (callState) {
            CallState.LISTENING -> listOf(Color(0xFF4CAF50), Color(0xFF81C784))
            CallState.THINKING -> listOf(Color(0xFF9C27B0), Color(0xFFBA68C8))
            CallState.SPEAKING -> listOf(Color(0xFF2196F3), Color(0xFF64B5F6))
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(140.dp)
                .scale(if (callState == CallState.SPEAKING || callState == CallState.THINKING) scale else 1.0f)
                .clip(CircleShape)
                .background(Brush.radialGradient(orbColors))
                .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .clickable { onOrbClick() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
                Text(
                    text = "点击提问",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }

        // 4. 底部通话控制栏（确保所有点击事件绝对灵敏、不卡顿）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 翻转镜头按钮
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onSwitchCamera, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "翻转镜头", tint = Color.White)
                }
            }

            // 静音按钮
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (isMuted) Color.Red else Color(0x88000000)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onToggleMute, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "静音",
                        tint = Color.White
                    )
                }
            }

            // 挂断按钮
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onEndCall, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.CallEnd, contentDescription = "挂断", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}