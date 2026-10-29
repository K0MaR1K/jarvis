package com.kholmsk.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kholmsk.jarvis.face.JarvisFaceView
import com.kholmsk.jarvis.vision.VisionAnalyzer
import java.util.concurrent.Executors

/**
 * Экран Джарвиса: только лицо, никакого интерфейса.
 * Камера работает в фоне и кормит взгляд данными.
 */
class MainActivity : ComponentActivity() {

    private lateinit var faceView: JarvisFaceView
    private var analyzer: VisionAnalyzer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            // без камеры глаза просто будут осматриваться и спать
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // экран не гаснет никогда
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        faceView = JarvisFaceView(this)
        setContentView(faceView)
        // жесты (свайп — сменить мордочку, тап — отладка) обрабатываются внутри view

        // полноэкранный режим, без статус-бара и кнопок
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val a = VisionAnalyzer(this) { result -> faceView.onVisionResult(result) }
            analyzer = a
            analysis.setAnalyzer(cameraExecutor, a)

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.release()
        analyzer = null
        cameraExecutor.shutdown()
    }
}
