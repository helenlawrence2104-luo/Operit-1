package com.ai.assistance.operit.ui.videocall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.ai.assistance.operit.api.gemini.GeminiLiveVisionClient
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

enum class CallState {
    LISTENING,  // 聆听中
    THINKING,   // 思考/看画面中
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

    private var isUsingFrontCamera = false
    private var latestBitmap: Bitmap? = null

    private val callState = mutableStateOf(CallState.LISTENING)
    private val isMuted = mutableStateOf(false)
    private val recognizedText = mutableStateOf("")
    private val aiResponseText = mutableStateOf("正在连接 AI 视觉通话...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apiKey = intent.getStringExtra("GEMINI_API_KEY") 
            ?: getSharedPreferences("gemini_config", Context.MODE_PRIVATE).getString("api_key", "") 
            ?: ""
        geminiClient = GeminiLiveVisionClient(apiKey)

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()

        setContent {
            VideoCallScreen(
                callState = callState.value,
                aiText = aiResponseText.value,
                userText = recognizedText.value,
                isMuted = isMuted.value,
                onToggleMute = { isMuted.value = !isMuted.value },
                onSwitchCamera = { switchCamera() },
                onEndCall = { finish() },
                onCameraSurfaceReady = { surface -> startCamera(surface) }
            )
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {
                    // 智能打断：用户一说话，立即停止当前 AI 播报
                    if (tts?.isSpeaking == true) {
                        tts?.stop()
                    }
                    callState.value = CallState.LISTENING
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    startListening()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank() && !isMuted.value) {
                        recognizedText.value = text
                        processUserQuery(text)
                    } else {
                        startListening()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processUserQuery(prompt: String) {
        callState.value = CallState.THINKING
        aiResponseText.value = "正在观察并思考..."

        lifecycleScope.launch {
            val result = geminiClient.analyzeFrameAndPrompt(latestBitmap, prompt)
            result.onSuccess { reply ->
                aiResponseText.value = reply
                callState.value = CallState.SPEAKING
                speakText(reply)
            }.onFailure { err ->
                aiResponseText.value = "提示: ${err.localizedMessage}"
                callState.value = CallState.LISTENING
                startListening()
            }
        }
    }

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VideoCallTTS")
        Handler(mainLooper).postDelayed({
            if (callState.value == CallState.SPEAKING) {
                callState.value = CallState.LISTENING
                startListening()
            }
        }, ((text.length * 200).coerceAtLeast(2000)).toLong())
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CHINESE
            aiResponseText.value = "AI 已就绪，请直接和我说话！"
            speakText("你好，我已经看到你的画面了，请问有什么可以帮你？")
        }
    }

    private fun startCamera(surface: SurfaceHolder) {
        startBackgroundThread()
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = if (isUsingFrontCamera) {
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        } else {
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        } ?: manager.cameraIdList.first()

        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                latestBitmap = imageToBitmap(image)
                image.close()
            }, backgroundHandler)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface.surface)
                        imageReader?.surface?.let { addTarget(it) }
                    }
                    camera.createCaptureSession(
                        listOf(surface.surface, imageReader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                        },
                        backgroundHandler
                    )
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)
        }
    }

    private fun switchCamera() {
        cameraDevice?.close()
        isUsingFrontCamera = !isUsingFrontCamera
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
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
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
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
    onCameraSurfaceReady: (SurfaceHolder) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. 全屏摄像头取景预览
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onCameraSurfaceReady(holder)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. 顶部状态与字幕显示卡片
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
                    .background(Color(0xCC1E1E2E))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = when (callState) {
                            CallState.LISTENING -> "🟢 正在聆听您的声音..."
                            CallState.THINKING -> "🟣 正在观察画面并思考..."
                            CallState.SPEAKING -> "🔵 AI 正在回答..."
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

        // 3. 屏幕中央动态灵动光球动效
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
                .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        // 4. 底部通话控制栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 翻转镜头
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000))
                    .clickable { onSwitchCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "翻转镜头", tint = Color.White)
            }

            // 静音开关
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (isMuted) Color.Red else Color(0x88000000))
                    .clickable { onToggleMute() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "静音",
                    tint = Color.White
                )
            }

            // 挂断按钮
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935))
                    .clickable { onEndCall() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "挂断", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}