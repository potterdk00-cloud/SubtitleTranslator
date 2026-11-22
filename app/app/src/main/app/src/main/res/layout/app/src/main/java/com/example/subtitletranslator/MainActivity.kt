package com.example.subtitletranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var tvSubtitle: TextView
    private var tts: TextToSpeech? = null
    private var translator: Translator? = null
    private var recognizer: TextRecognizer? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var isTranslatorReady = false
    private var lastEnglishText = ""
    private var lastProcessTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        tvSubtitle = findViewById(R.id.tvSubtitle)

        try {
            tts = TextToSpeech(this, this)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build()
            translator = Translation.getClient(options)

            tvSubtitle.text = "กำลังดาวน์โหลดไฟล์ภาษาไทย..."
            val conditions = DownloadConditions.Builder().build()
            translator?.downloadModelIfNeeded(conditions)
                ?.addOnSuccessListener {
                    isTranslatorReady = true
                    tvSubtitle.text = "พร้อมแปล! ส่องกล้องไปที่ซับไตเติ้ลได้เลย"
                }
                ?.addOnFailureListener { e ->
                    tvSubtitle.text = "โหลดภาษาไม่สำเร็จ: ${e.message}"
                }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            }
        } catch (e: Exception) {
            tvSubtitle.text = "เกิดข้อผิดพลาด: ${e.message}"
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("th", "TH")
            tts?.setSpeechRate(1.15f)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                runOnUiThread {
                    tvSubtitle.text = "เปิดกล้องไม่สำเร็จ: ${e.message}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (!isTranslatorReady || currentTime - lastProcessTime < 500) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        lastProcessTime = currentTime
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val imageHeight = image.height

        recognizer?.process(image)
            ?.addOnSuccessListener { visionText ->
                val subtitleText = visionText.textBlocks
                    .filter { block ->
                        val top = block.boundingBox?.top ?: 0
                        top > (imageHeight * 0.65)
                    }
                    .joinToString(" ") { it.text }
                    .replace("\n", " ")
                    .trim()

                if (subtitleText.length > 2 && !isSimilar(subtitleText, lastEnglishText)) {
                    lastEnglishText = subtitleText
                    translateAndSpeak(subtitleText)
                }
            }
            ?.addOnCompleteListener {
                imageProxy.close()
            } ?: imageProxy.close()
    }

    private fun translateAndSpeak(englishText: String) {
        translator?.translate(englishText)
            ?.addOnSuccessListener { thaiText ->
                runOnUiThread {
                    tvSubtitle.text = thaiText
                }
                tts?.speak(thaiText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
    }

    private fun isSimilar(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val wordsA = a.lowercase().split(" ").toSet()
        val wordsB = b.lowercase().split(" ").toSet()
        val overlap = wordsA.intersect(wordsB).size
        val maxLen = maxOf(wordsA.size, wordsB.size)
        return maxLen > 0 && (overlap.toFloat() / maxLen) > 0.75f
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            tvSubtitle.text = "กรุณาอนุญาตสิทธิ์กล้องเพื่อใช้งาน"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        translator?.close()
    }
}

