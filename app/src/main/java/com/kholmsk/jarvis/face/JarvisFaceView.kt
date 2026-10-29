package com.kholmsk.jarvis.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.kholmsk.jarvis.vision.VisionAnalyzer.VisionResult
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Лицо Джарвиса: два глаза в стиле Anki Cozmo на чёрном фоне.
 * Моргает, следит за человеком (руки — приоритетнее), осматривается, когда скучно,
 * и засыпает, если никого нет 30 секунд. Тап — отладочная подпись.
 */
class JarvisFaceView(context: Context) : View(context) {

    // -------- ввод от камеры (приходит из фонового потока) --------
    @Volatile private var vision: VisionResult? = null
    @Volatile private var lastInteractionMs = 0L

    var debugOverlay = false
        private set

    fun onVisionResult(result: VisionResult) {
        vision = result
        if (result.facePresent || result.handPresent) {
            lastInteractionMs = SystemClock.uptimeMillis()
        }
    }

    fun toggleDebug() {
        debugOverlay = !debugOverlay
    }

    // -------- состояние --------
    private enum class State { AWAKE, SLEEPING }

    private var state = State.AWAKE
    private var lastFrameMs = 0L
    private var sleepStartMs = 0L

    // взгляд -1..1: текущий (сглаженный) и цель
    private var gazeX = 0f
    private var gazeY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var wanderUntilMs = 0L

    // моргание
    private var blinkStartMs = -1L      // -1 = сейчас не моргаем
    private var nextBlinkAtMs = 1500L
    private var blinksLeft = 0          // иногда двойное — так живее

    // геометрия глаз, пересчитывается при изменении размера экрана
    private var eyeW = 0f
    private var eyeH = 0f
    private var eyeGap = 0f
    private var eyeCy = 0f
    private var pupilSize = 0f

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
    }
    private val eyeRect = RectF()
    private val pupilRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()
        eyeW = fw * 0.24f
        eyeH = fh * 0.20f
        eyeGap = fw * 0.16f
        eyeCy = fh * 0.36f
        pupilSize = eyeW * 0.55f
        eyePaint.strokeWidth = eyeW * 0.09f
        textPaint.textSize = fh * 0.016f
        // мягкое свечение требует программный слой отрисовки
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // -------- логика, раз в кадр --------
    private fun update(nowMs: Long, dtMs: Float) {
        val v = vision
        val someoneHere = v?.facePresent == true || v?.handPresent == true

        // 1) куда смотрим: рука важнее лица (на руку реагируем мгновенным взглядом)
        if (v != null && v.handPresent) {
            targetX = ((v.handX - 0.5f) * 2.4f).coerceIn(-1f, 1f)
            targetY = ((v.handY - 0.5f) * 2.4f).coerceIn(-1f, 1f)
        } else if (v != null && v.facePresent) {
            targetX = ((v.faceX - 0.5f) * 1.7f).coerceIn(-1f, 1f)
            targetY = ((v.faceY - 0.5f) * 1.7f).coerceIn(-1f, 1f)
        } else if (nowMs >= wanderUntilMs) {
            // никого: осматриваемся, в основном у центра
            targetX = Random.nextFloat() * 1.3f - 0.65f
            targetY = Random.nextFloat() * 0.9f - 0.45f
            wanderUntilMs = nowMs + 1600L + Random.nextLong(2000L)
        }

        // 2) сон и пробуждение
        if (state == State.SLEEPING && someoneHere) {
            state = State.AWAKE
            blinkStartMs = nowMs
            blinksLeft = 2 // проснулся и моргнул дважды
        } else if (state == State.AWAKE && !someoneHere &&
            lastInteractionMs in 1..(nowMs - SLEEP_AFTER_MS)
        ) {
            state = State.SLEEPING
            sleepStartMs = nowMs
            blinkStartMs = -1L
        }

        // 3) плавный доворот взгляда (во сне — ленивый)
        val speed = if (state == State.SLEEPING) 1.6f else 6.5f
        val k = 1f - exp(-speed * dtMs / 1000f)
        gazeX += (targetX - gazeX) * k
        gazeY += (targetY - gazeY) * k

        // 4) расписание морганий (только бодрствуя)
        if (state == State.AWAKE) {
            if (blinkStartMs < 0L && nowMs >= nextBlinkAtMs) {
                blinkStartMs = nowMs
                blinksLeft = if (Random.nextFloat() < 0.15f) 2 else 1
            }
            if (blinkStartMs >= 0L && nowMs - blinkStartMs >= BLINK_MS) {
                if (blinksLeft > 1) {
                    blinksLeft--
                    blinkStartMs = nowMs
                } else {
                    blinkStartMs = -1L
                    nextBlinkAtMs = nowMs + 2000L + Random.nextLong(5000L)
                }
            }
        }
    }

    private fun blinkOpenness(nowMs: Long): Float {
        if (blinkStartMs < 0L) return 1f
        val p = ((nowMs - blinkStartMs) / BLINK_MS.toFloat()).coerceIn(0f, 1f)
        return 1f - sin(p * PI.toFloat()) // плавно: открыто -> закрыто -> открыто
    }

    // -------- отрисовка --------
    override fun onDraw(canvas: Canvas) {
        val nowMs = SystemClock.uptimeMillis()
        val dtMs =
            if (lastFrameMs == 0L) 16f
            else (nowMs - lastFrameMs).coerceAtMost(100L).toFloat()
        lastFrameMs = nowMs
        update(nowMs, dtMs)

        val sleeping = state == State.SLEEPING
        val sleepDepth = if (sleeping) ((nowMs - sleepStartMs) / 900f).coerceIn(0f, 1f) else 0f
        val openness = blinkOpenness(nowMs) * (1f - 0.78f * sleepDepth)
        val breathe = 1f + 0.02f * sin(nowMs / 1400f) // едва заметное «дыхание»
        val gx = gazeX
        val gy = gazeY + 0.35f * sleepDepth           // во сне взгляд утыкается вниз

        val eyeColor = if (sleeping) EYE_SLEEP else EYE_AWAKE
        val pupilColor = if (sleeping) PUPIL_SLEEP else PUPIL_AWAKE
        eyePaint.color = eyeColor
        eyePaint.setShadowLayer(eyeW * 0.15f, 0f, 0f, eyeColor)
        pupilPaint.color = pupilColor
        pupilPaint.setShadowLayer(pupilSize * 0.16f, 0f, 0f, pupilColor)

        val half = eyeGap / 2f + eyeW / 2f
        drawEye(canvas, width / 2f - half, openness * breathe, gx, gy)
        drawEye(canvas, width / 2f + half, openness * breathe, gx, gy)

        if (debugOverlay) drawDebug(canvas)
        postInvalidateOnAnimation() // следующий кадр анимации
    }

    private fun drawEye(canvas: Canvas, cx: Float, openScale: Float, gx: Float, gy: Float) {
        val h = eyeH * openScale
        if (h < 2f) return
        eyeRect.set(cx - eyeW / 2f, eyeCy - h / 2f, cx + eyeW / 2f, eyeCy + h / 2f)
        val r = min(eyeW * 0.35f, h / 2f)
        canvas.drawRoundRect(eyeRect, r, r, eyePaint)

        // зрачок скользит внутри глаза, не выходя за контур
        val ph = pupilSize * openScale
        if (ph < 1f) return
        val margin = eyePaint.strokeWidth * 0.7f
        val maxX = (eyeRect.width() - pupilSize) / 2f - margin
        val maxY = (h - ph) / 2f - margin
        val px = eyeRect.centerX() + gx * maxX.coerceAtLeast(0f)
        val py = eyeRect.centerY() + gy * maxY.coerceAtLeast(0f)
        pupilRect.set(px - pupilSize / 2f, py - ph / 2f, px + pupilSize / 2f, py + ph / 2f)
        val pr = min(pupilSize * 0.35f, ph / 2f)
        canvas.drawRoundRect(pupilRect, pr, pr, pupilPaint)
    }

    private fun drawDebug(canvas: Canvas) {
        val v = vision
        val lines = listOf(
            "ДЖАРВИС v0.1 · " + if (state == State.SLEEPING) "спит" else "бодрствует",
            "камера " + (v?.fps?.toInt() ?: 0) + " fps · лицо: " +
                (if (v?.facePresent == true) "да" else "нет") +
                " · рука: " + (if (v?.handPresent == true) "да" else "нет"),
            "взгляд (%.2f, %.2f)".format(gazeX, gazeY)
        )
        var y = textPaint.textSize * 1.6f
        for (line in lines) {
            canvas.drawText(line, 20f, y, textPaint)
            y += textPaint.textSize * 1.35f
        }
    }

    companion object {
        private const val BLINK_MS = 130L
        private const val SLEEP_AFTER_MS = 30_000L
        private val EYE_AWAKE = Color.parseColor("#5FD4FF")
        private val EYE_SLEEP = Color.parseColor("#27596E")
        private val PUPIL_AWAKE = Color.parseColor("#EAFAFF")
        private val PUPIL_SLEEP = Color.parseColor("#8FB8CC")
    }
}
