package com.kholmsk.jarvis.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.max

/**
 * «Зрение» Джарвиса: офлайн-распознавание лица и рук на кадрах фронтальной камеры.
 * Работает в фоновом потоке CameraX, результаты отдаёт колбэком на каждый кадр.
 */
class VisionAnalyzer(
    context: Context,
    private val onResult: (VisionResult) -> Unit
) : ImageAnalysis.Analyzer {

    /** Нормализованные координаты 0..1 уже в «зеркальном» пространстве, как видит пользователь. */
    data class VisionResult(
        val facePresent: Boolean,
        val faceX: Float,
        val faceY: Float,
        val faceSize: Float,    // высота рамки лица / высота кадра (близость)
        val handPresent: Boolean,
        val handX: Float,
        val handY: Float,
        val fps: Float
    )

    private val faceDetector: FaceDetector
    private val handLandmarker: HandLandmarker

    private var lastTimestamp = 0L
    private var frames = 0
    private var fpsWindowStartNs = System.nanoTime()
    private var fps = 0f

    init {
        val faceOptions = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath(MODEL_FACE).build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setMinDetectionConfidence(0.4f)
            .build()
        faceDetector = FaceDetector.createFromOptions(context, faceOptions)

        val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath(MODEL_HANDS).build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.4f)
            .setMinHandPresenceConfidence(0.4f)
            .build()
        handLandmarker = HandLandmarker.createFromOptions(context, handOptions)
    }

    override fun analyze(image: ImageProxy) {
        try {
            // 1) Кадр -> Bitmap: поворачиваем вертикально и зеркалим (селфи-камера),
            //    заодно уменьшаем до 320px по ширине — инференсу хватает, телефону легче.
            val rotation = image.imageInfo.rotationDegrees
            val raw = image.toBitmap()
            val m = Matrix()
            if (rotation != 0) m.postRotate(rotation.toFloat())
            m.postScale(-1f, 1f) // зеркало, чтобы «лево-право» совпадало с реальностью
            val scale = INFERENCE_WIDTH.toFloat() / raw.width
            m.postScale(scale, scale)
            val frame = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)

            // 2) MediaPipe требует строго растущие таймстампы
            val now = SystemClock.uptimeMillis()
            val ts = max(lastTimestamp + 1, now)
            lastTimestamp = ts
            val mpImage = BitmapImageBuilder(frame).build()

            // 3) Лица: берём самую крупную рамку (ближнего человека)
            var facePresent = false
            var faceX = 0.5f
            var faceY = 0.5f
            var faceSize = 0f
            try {
                val faces = faceDetector.detectForVideo(mpImage, ts)
                var bestArea = 0f
                for (d in faces.detections()) {
                    val box = d.boundingBox()
                    val area = box.width() * box.height()
                    if (area > bestArea) {
                        bestArea = area
                        facePresent = true
                        faceX = box.centerX() / frame.width
                        faceY = box.centerY() / frame.height
                        faceSize = box.height() / frame.height
                    }
                }
            } catch (_: Exception) {
                // редкий сбой кадра — просто пропустим
            }

            // 4) Руки: интересует центр ладони (точка 9 из 21)
            var handPresent = false
            var handX = 0.5f
            var handY = 0.5f
            try {
                val hands = handLandmarker.detectForVideo(mpImage, ts)
                if (hands.landmarks().isNotEmpty()) {
                    val palm = hands.landmarks()[0][9]
                    handPresent = true
                    handX = palm.x()
                    handY = palm.y()
                }
            } catch (_: Exception) {
            }

            // 5) Счётчик кадров в секунду
            frames++
            val nowNs = System.nanoTime()
            if (nowNs - fpsWindowStartNs >= 1_000_000_000L) {
                fps = frames * 1e9f / (nowNs - fpsWindowStartNs)
                frames = 0
                fpsWindowStartNs = nowNs
            }

            onResult(
                VisionResult(facePresent, faceX, faceY, faceSize, handPresent, handX, handY, fps)
            )
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            image.close()
        }
    }

    fun release() {
        faceDetector.close()
        handLandmarker.close()
    }

    companion object {
        private const val MODEL_FACE = "blaze_face_short_range.tflite"
        private const val MODEL_HANDS = "hand_landmarker.task"
        private const val INFERENCE_WIDTH = 320
    }
}
