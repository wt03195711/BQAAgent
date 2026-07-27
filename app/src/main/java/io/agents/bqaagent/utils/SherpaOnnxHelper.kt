package io.agents.bqaagent.utils

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SherpaOnnxHelper(private val context: Context) {

    companion object {
        private const val TAG = "SherpaOnnxHelper"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val MODEL_DIR = "sherpa_models"
        private val MODEL_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )
    }

    interface AsrCallback {
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onError(errorMsg: String)
    }

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val recording = AtomicBoolean(false)
    private var modelInitialized = false

    val isModelReady: Boolean get() = modelInitialized

    fun isRecording(): Boolean = recording.get()

    private fun getModelDir(): File = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }

    suspend fun ensureModel(onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (modelInitialized) return@withContext true
        try {
            val dir = getModelDir()

            val totalFiles = MODEL_FILES.size
            MODEL_FILES.forEachIndexed { index, filename ->
                val destFile = File(dir, filename)
                val assetSize = context.assets.open("sherpa_models/$filename").use { it.available().toLong() }
                val needCopy = !destFile.exists() || Math.abs(destFile.length() - assetSize) > assetSize * 0.01

                if (needCopy) {
                    Log.i(TAG, "Copying ${index + 1}/$totalFiles: $filename")
                    context.assets.open("sherpa_models/$filename").use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                onProgress(((index + 1) * 100 / totalFiles))
            }

            Log.i(TAG, "Initializing recognizer...")
            initRecognizer(dir)
            modelInitialized = true
            Log.i(TAG, "Recognizer initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model initialization failed: ${e.message}", e)
            false
        }
    }

    private fun initRecognizer(modelDir: File) {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                decoder = File(modelDir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath,
                joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer",
        )

        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            enableEndpoint = true,
        )

        recognizer = OnlineRecognizer(
            assetManager = null,
            config = config,
        )
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecordingLoop(cb: AsrCallback) {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            cb.onError("无法初始化音频录制")
            recording.set(false)
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            cb.onError("麦克风初始化失败")
            recording.set(false)
            audioRecord?.release()
            audioRecord = null
            return
        }

        val rec = recognizer ?: return
        val stream: OnlineStream = rec.createStream()
        val samples = ShortArray(1600)
        audioRecord!!.startRecording()

        recordingThread = Thread {
            try {
                while (recording.get()) {
                    val read = audioRecord!!.read(samples, 0, samples.size)
                    if (read <= 0) continue

                    val floatSamples = FloatArray(read) { samples[it] / 32768.0f }
                    stream.acceptWaveform(floatSamples, SAMPLE_RATE)

                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }

                    val text = rec.getResult(stream).text
                    if (text.isNotBlank()) {
                        cb.onPartialText(text.trim())
                    }

                    if (rec.isEndpoint(stream)) {
                        if (text.isNotBlank()) {
                            cb.onFinalText(text.trim())
                        }
                        rec.reset(stream)
                    }
                }

                val finalText = rec.getResult(stream).text.trim()
                if (finalText.isNotEmpty()) {
                    cb.onFinalText(finalText)
                }
            } catch (e: Exception) {
                if (recording.get()) cb.onError("识别异常：${e.message}")
            } finally {
                recording.set(false)
                stream.release()
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
        }.also { it.start() }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecognition(cb: AsrCallback) {
        if (recording.get()) return
        if (!modelInitialized) { cb.onError("语音模型未就绪"); return }
        recording.set(true)
        startRecordingLoop(cb)
    }

    fun stopRecognition() {
        if (!recording.get()) return
        recording.set(false)
        recordingThread?.join(3000)
        recordingThread = null
    }

    fun release() {
        stopRecognition()
        recognizer?.release()
        recognizer = null
        modelInitialized = false
    }
}
