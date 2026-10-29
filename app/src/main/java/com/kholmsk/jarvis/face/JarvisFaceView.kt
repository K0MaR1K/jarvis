package com.kholmsk.jarvis.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.kholmsk.jarvis.vision.VisionAnalyzer.VisionResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Лицо Джарвиса. Пять стилей (свайп — сменить) и движок эмоций в духе Cozmo/Vector:
 * каждая эмоция — пресет из высоты, наклона, ширины глаз и размера зрачков
 * (по мотивам инструментов аниматоров Anki и проекта esp32-eyes),
 * а переходы едет пружиной — с лёгким «боингом», как у настоящего Козмо.
 *
 * Жесты: свайп — сменить мордочку, тап — отладка, долгое нажатие — демо эмоций.
 */
class JarvisFaceView(context: Context) : View(context) {

    enum class FaceStyle(val title: String) {
        COZMO("Козмо"),
        EVE("ЕВА"),
        HUD("ДЖАРВИС"),
        HAL("ХЭЛ"),
        BB8("Би-Би 8")
    }

    enum class Emotion(val title: String) {
        NEUTRAL("Нейтрально"),
        HAPPY("Радость"),
        SAD("Грусть"),
        ANGRY("Злость"),
        SURPRISED("Удивление"),
        EXCITED("Восторг"),
        SQUINT("Прищур"),
        THINKING("Думаю"),
        LOVE("Любовь"),
        DEAD("Трупик")
    }

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

    // -------- выбор мордочки --------
    private val prefs = context.getSharedPreferences("jarvis_face", Context.MODE_PRIVATE)
    private var style =
        FaceStyle.entries[prefs.getInt(KEY_STYLE, 0).coerceIn(0, FaceStyle.entries.lastIndex)]
    private var labelUntilMs = 0L

    private fun setStyle(s: FaceStyle) {
        style = s
        prefs.edit().putInt(KEY_STYLE, s.ordinal).apply()
        labelUntilMs = SystemClock.uptimeMillis() + 1600L
    }

    // -------- жесты --------
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 500f) {
                val dir = if (vx < 0) 1 else -1
                val idx = (style.ordinal + dir + FaceStyle.entries.size) % FaceStyle.entries.size
                setStyle(FaceStyle.entries[idx])
                return true
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleDebug()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // демо-карусель эмоций
            demoIndex = (demoIndex + 1) % DEMO.size
            setEmotion(DEMO[demoIndex], if (DEMO[demoIndex] == Emotion.NEUTRAL) 0 else 4000)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    // -------- эмоции --------
    private var emotion = Emotion.NEUTRAL
    private var emotionUntilMs = Long.MAX_VALUE
    private var pendingEmotion: Emotion? = null
    private var pendingAtMs = 0L
    private var nextIdleEmotionMs = 0L
    private var handWasPresent = false
    private var demoIndex = 0

    private val DEMO = arrayOf(
        Emotion.NEUTRAL, Emotion.HAPPY, Emotion.SAD, Emotion.ANGRY, Emotion.SURPRISED,
        Emotion.EXCITED, Emotion.SQUINT, Emotion.THINKING, Emotion.LOVE, Emotion.DEAD
    )

    /** Параметры эмоции: высота, ширина, наклон (градусы, + = уголки вверх), зрачок, сдвиг вниз. */
    private class Params(val h: Float, val w: Float, val tilt: Float, val pupil: Float, val y: Float)

    private fun preset(e: Emotion) = when (e) {
        Emotion.NEUTRAL -> Params(1.00f, 1.00f, 0f, 1.00f, 0.00f)
        Emotion.HAPPY -> Params(0.72f, 1.12f, 14f, 1.05f, -0.06f)   // глаза-улыбки
        Emotion.SAD -> Params(0.72f, 0.95f, -15f, 1.25f, 0.08f)     // щенячьи глаза
        Emotion.ANGRY -> Params(0.50f, 1.10f, 22f, 0.50f, 0.06f)    // сердитый прищур
        Emotion.SURPRISED -> Params(1.28f, 1.02f, 0f, 1.55f, -0.08f) // глаза-тарелки
        Emotion.EXCITED -> Params(1.14f, 1.18f, 8f, 1.45f, -0.05f)  // прыгает от радости
        Emotion.SQUINT -> Params(0.38f, 0.95f, 6f, 0.85f, 0.03f)    // не доверяю
        Emotion.THINKING -> Params(0.85f, 1.00f, -8f, 0.75f, 0.02f) // взгляд в потолок
        Emotion.LOVE -> Params(0.75f, 1.10f, 15f, 1.10f, -0.06f)    // + сердечки
        Emotion.DEAD -> Params(0.90f, 0.95f, -12f, 0.00f, 0.05f)    // крестики, без зрачков
    }

    private fun setEmotion(e: Emotion, durationMs: Long) {
        emotion = e
        emotionUntilMs = if (durationMs <= 0) Long.MAX_VALUE else SystemClock.uptimeMillis() + durationMs
        labelUntilMs = SystemClock.uptimeMillis() + 1500
    }

    /** Пружина с лёгким недодемпфированием — тот самый «боинг» Козмо. */
    private class Spring(var pos: Float) {
        var vel = 0f
        fun spring(target: Float, dt: Float, stiff: Float = 120f, damp: Float = 9f) {
            vel += (target - pos) * stiff * dt
            vel *= max(0f, 1f - damp * dt)
            pos += vel * dt
        }
    }

    private val spH = Spring(1f)
    private val spW = Spring(1f)
    private val spTilt = Spring(0f)
    private val spPupil = Spring(1f)
    private val spY = Spring(0f)

    // -------- состояние --------
    private enum class State { AWAKE, SLEEPING }

    private var state = State.AWAKE
    private var lastFrameMs = 0L
    private var sleepStartMs = 0L

    private var gazeX = 0f
    private var gazeY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var wanderUntilMs = 0L

    private var blinkStartMs = -1L
    private var nextBlinkAtMs = 1500L
    private var blinksLeft = 0

    private var eyeW = 0f
    private var eyeH = 0f
    private var eyeGap = 0f
    private var eyeCy = 0f
    private var pupilSize = 0f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
    }
    private val eyeRect = RectF()
    private val arcRect = RectF()
    private val heartPath = Path()
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // сердечки для эмоции «любовь»
    private class Heart {
        var x = 0f; var y = 0f; var vy = 0f; var sway = 0f
        var phase = 0f; var size = 0f; var alpha = 0f
    }

    private var hearts: Array<Heart> = emptyArray()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()
        eyeW = fw * 0.24f
        eyeH = fh * 0.20f
        eyeGap = fw * 0.16f
        eyeCy = fh * 0.36f
        pupilSize = eyeW * 0.55f
        textPaint.textSize = fh * 0.016f
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        hearts = Array(9) {
            Heart().apply {
                x = fw * (0.25f + Random.nextFloat() * 0.5f)
                y = fh * (0.3f + Random.nextFloat() * 0.5f)
                vy = -(fh * (0.05f + Random.nextFloat() * 0.06f)) / 60f
                sway = fw * (0.004f + Random.nextFloat() * 0.008f)
                phase = Random.nextFloat() * 6.28f
                size = fw * (0.02f + Random.nextFloat() * 0.03f)
                alpha = 0.4f + Random.nextFloat() * 0.6f
            }
        }
    }

    // -------- логика, раз в кадр --------
    private fun update(nowMs: Long, dtMs: Float) {
        val v = vision
        val someoneHere = v?.facePresent == true || v?.handPresent == true
        val dt = dtMs / 1000f

        // 1) куда смотрим: рука важнее лица
        if (v != null && v.handPresent) {
            targetX = ((v.handX - 0.5f) * 2.4f).coerceIn(-1f, 1f)
            targetY = ((v.handY - 0.5f) * 2.4f).coerceIn(-1f, 1f)
        } else if (v != null && v.facePresent) {
            targetX = ((v.faceX - 0.5f) * 1.7f).coerceIn(-1f, 1f)
            targetY = ((v.faceY - 0.5f) * 1.7f).coerceIn(-1f, 1f)
        } else if (nowMs >= wanderUntilMs) {
            targetX = Random.nextFloat() * 1.3f - 0.65f
            targetY = Random.nextFloat() * 0.9f - 0.45f
            wanderUntilMs = nowMs + 1600L + Random.nextLong(2000L)
        }
        // «думаю» — взгляд гуляет по потолку
        if (emotion == Emotion.THINKING && nowMs >= wanderUntilMs) {
            targetX = if (Random.nextFloat() < 0.5f) -0.55f else 0.55f
            targetY = -0.4f
            wanderUntilMs = nowMs + 700L + Random.nextLong(500L)
        }

        // 2) сон и пробуждение (+ сценарий: удивление -> радость)
        if (state == State.SLEEPING && someoneHere) {
            state = State.AWAKE
            blinkStartMs = nowMs
            blinksLeft = 2
            setEmotion(Emotion.SURPRISED, 650)
            pendingEmotion = Emotion.HAPPY
            pendingAtMs = nowMs + 650
        } else if (state == State.AWAKE && !someoneHere &&
            lastInteractionMs in 1..(nowMs - SLEEP_AFTER_MS)
        ) {
            state = State.SLEEPING
            sleepStartMs = nowMs
            blinkStartMs = -1L
            emotion = Emotion.NEUTRAL
            emotionUntilMs = Long.MAX_VALUE
            pendingEmotion = null
        }

        // 3) эмоции: конец показа, отложенная, реакция на руку, микромооды при скуке
        if (nowMs > emotionUntilMs) {
            emotion = Emotion.NEUTRAL
            emotionUntilMs = Long.MAX_VALUE
        }
        val pend = pendingEmotion
        if (pend != null && nowMs >= pendingAtMs) {
            setEmotion(pend, 1300)
            pendingEmotion = null
        }
        if (v != null && state == State.AWAKE && v.handPresent && !handWasPresent &&
            emotion == Emotion.NEUTRAL
        ) {
            setEmotion(Emotion.SURPRISED, 450)
        }
        handWasPresent = v?.handPresent == true
        if (state == State.AWAKE && someoneHere && emotion == Emotion.NEUTRAL &&
            nowMs >= nextIdleEmotionMs
        ) {
            when (Random.nextInt(3)) {
                0 -> setEmotion(Emotion.THINKING, 2400)
                1 -> setEmotion(Emotion.SQUINT, 1700)
                else -> setEmotion(Emotion.HAPPY, 1400)
            }
            nextIdleEmotionMs = nowMs + 15_000L + Random.nextLong(20_000L)
        }

        // 4) пружины к пресету текущей эмоции
        val p = preset(emotion)
        spH.spring(p.h, dt)
        spW.spring(p.w, dt)
        spTilt.spring(p.tilt, dt)
        spPupil.spring(p.pupil, dt)
        spY.spring(p.y, dt)

        // 5) плавный доворот взгляда (во сне — ленивый)
        val speed = if (state == State.SLEEPING) 1.6f else 6.5f
        val k = 1f - exp(-speed * dtMs / 1000f)
        gazeX += (targetX - gazeX) * k
        gazeY += (targetY - gazeY) * k

        // 6) моргание (кроме «трупика»)
        if (state == State.AWAKE && emotion != Emotion.DEAD) {
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

        // 7) сердечки летят
        if (emotion == Emotion.LOVE) {
            for (hrt in hearts) {
                hrt.y += hrt.vy * dtMs
                hrt.alpha -= 0.0035f * dtMs
                if (hrt.y < height * 0.12f || hrt.alpha <= 0f) {
                    hrt.y = height * 0.85f
                    hrt.x = width * (0.2f + Random.nextFloat() * 0.6f)
                    hrt.alpha = 1f
                }
            }
        }
    }

    private fun blinkOpenness(nowMs: Long): Float {
        if (blinkStartMs < 0L) return 1f
        val p = ((nowMs - blinkStartMs) / BLINK_MS.toFloat()).coerceIn(0f, 1f)
        return 1f - sin(p * PI.toFloat())
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
        val dim = if (sleeping) ((nowMs - sleepStartMs) / 900f).coerceIn(0f, 1f) else 0f
        val openness = blinkOpenness(nowMs) * (1f - 0.78f * dim)
        val breathe = 1f + 0.02f * sin(nowMs / 1400f)
        val gx = gazeX
        val gy = gazeY + 0.35f * dim

        // текущие (пружинные) параметры эмоции
        val h = spH.pos * breathe
        val w = spW.pos
        val tilt = spTilt.pos
        val pupil = spPupil.pos
        val yOff = spY.pos
        val dead = emotion == Emotion.DEAD

        when (style) {
            FaceStyle.COZMO -> drawCozmo(canvas, gx, gy, openness, dim, h, w, tilt, pupil, yOff, dead)
            FaceStyle.EVE -> drawEve(canvas, gx, gy, openness, dim, h, w, tilt, pupil, yOff, dead)
            FaceStyle.HUD -> drawHud(canvas, nowMs, gx, gy, dim, breathe, pupil, tilt, dead)
            FaceStyle.HAL -> drawHal(canvas, gx, gy, openness, dim, h, pupil, tilt, dead)
            FaceStyle.BB8 -> drawBb8(canvas, gx, gy, openness, dim, h, pupil, tilt, dead)
        }

        if (emotion == Emotion.LOVE) drawHearts(canvas, nowMs)
        if (labelUntilMs > nowMs) drawLabel(canvas)
        if (debugOverlay) drawDebug(canvas)
        postInvalidateOnAnimation()
    }

    private fun alphaFor(dim: Float): Int = (255f * (1f - 0.55f * dim)).toInt().coerceIn(0, 255)

    /** Поворот глаза: у левого «+», у правого зеркально — эмоциональный наклон. */
    private fun drawTilted(canvas: Canvas, cx: Float, cy: Float, tiltDeg: Float, block: () -> Unit) {
        canvas.save()
        canvas.rotate(tiltDeg, cx, cy)
        block()
        canvas.restore()
    }

    private fun drawX(canvas: Canvas, r: RectF) {
        val insetX = r.width() * 0.22f
        val insetY = r.height() * 0.22f
        strokePaint.strokeWidth = eyeW * 0.06f
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(r.left + insetX, r.top + insetY, r.right - insetX, r.bottom - insetY, strokePaint)
        canvas.drawLine(r.right - insetX, r.top + insetY, r.left + insetX, r.bottom - insetY, strokePaint)
    }

    /** Козмо: два скруглённых прямоугольника, зрачки-квадраты, эмоции формой и наклоном. */
    private fun drawCozmo(
        canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float,
        h: Float, w: Float, tilt: Float, pupil: Float, yOff: Float, dead: Boolean
    ) {
        val eW = eyeW * w
        val eH = eyeH * h
        if (eH < 4f) return
        val cy = eyeCy + yOff * eyeH
        val half = eyeGap / 2f + eW / 2f
        val pw = pupilSize * pupil

        val eyeColor = when {
            dead -> DEAD_EYE
            emotion == Emotion.ANGRY -> ANGRY_EYE
            else -> COZMO_EYE
        }
        val pupilColor = when {
            dead -> DEAD_EYE
            emotion == Emotion.ANGRY -> ANGRY_PUPIL
            else -> COZMO_PUPIL
        }

        strokePaint.strokeWidth = eW * 0.09f
        strokePaint.color = eyeColor
        strokePaint.alpha = alphaFor(dim)
        strokePaint.setShadowLayer(eW * 0.15f, 0f, 0f, eyeColor)
        fillPaint.color = pupilColor
        fillPaint.alpha = alphaFor(dim)
        fillPaint.setShadowLayer(pw * 0.16f, 0f, 0f, pupilColor)

        for (side in intArrayOf(-1, 1)) {
            val cx = width / 2f + side * half
            drawTilted(canvas, cx, cy, tilt * side) {
                val hh = eH * openScale
                eyeRect.set(cx - eW / 2f, cy - hh / 2f, cx + eW / 2f, cy + hh / 2f)
                val r = min(eW * 0.35f, hh / 2f)
                canvas.drawRoundRect(eyeRect, r, r, strokePaint)

                if (dead) {
                    drawX(canvas, eyeRect)
                } else {
                    val phh = pw * openScale
                    if (phh > 1f) {
                        val margin = strokePaint.strokeWidth * 0.7f
                        val maxX = (eyeRect.width() - pw) / 2f - margin
                        val maxY = (hh - phh) / 2f - margin
                        val px = eyeRect.centerX() + gx * maxX.coerceAtLeast(0f)
                        val py = eyeRect.centerY() + gy * maxY.coerceAtLeast(0f)
                        eyeRect.set(px - pw / 2f, py - phh / 2f, px + pw / 2f, py + phh / 2f)
                        canvas.drawRoundRect(eyeRect, min(pw * 0.35f, phh / 2f), min(pw * 0.35f, phh / 2f), fillPaint)
                    }
                }
            }
        }
    }

    /** ЕВА: капсулы, эмоции высотой и наклоном, зрачок-линза. */
    private fun drawEve(
        canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float,
        h: Float, w: Float, tilt: Float, pupil: Float, yOff: Float, dead: Boolean
    ) {
        val cw = eyeW * 0.62f * w
        val chh = eyeH * 1.5f * h * openScale
        if (chh < 4f) return
        val cy = eyeCy + yOff * eyeH
        val half = eyeGap * 0.75f / 2f + cw / 2f
        val lw = cw * 0.42f * pupil
        val lh = chh * 0.55f

        for (side in intArrayOf(-1, 1)) {
            val cx = width / 2f + side * half
            drawTilted(canvas, cx, cy, tilt * side) {
                fillPaint.color = if (dead) DEAD_EYE else EVE_OUTER
                fillPaint.alpha = alphaFor(dim)
                fillPaint.setShadowLayer(cw * 0.5f, 0f, 0f, if (dead) DEAD_EYE else EVE_GLOW)
                eyeRect.set(cx - cw / 2f, cy - chh / 2f, cx + cw / 2f, cy + chh / 2f)
                canvas.drawRoundRect(eyeRect, cw / 2f, cw / 2f, fillPaint)

                if (!dead && lw > 2f) {
                    val lx = cx + gx * (cw - lw) / 2f * 0.8f
                    val ly = cy + gy * (chh - lh) / 2f * 0.8f
                    fillPaint.color = EVE_INNER
                    fillPaint.alpha = alphaFor(dim)
                    fillPaint.setShadowLayer(lw * 0.8f, 0f, 0f, EVE_INNER)
                    eyeRect.set(lx - lw / 2f, ly - lh / 2f, lx + lw / 2f, ly + lh / 2f)
                    canvas.drawRoundRect(eyeRect, lw / 2f, lw / 2f, fillPaint)
                }
            }
        }
    }

    /** HUD: кольца, ядро и сканер; эмоции — скоростью колец и ядром. */
    private fun drawHud(
        canvas: Canvas, nowMs: Long, gx: Float, gy: Float, dim: Float,
        breathe: Float, pupil: Float, tilt: Float, dead: Boolean
    ) {
        val c = width / 2f
        val cy = eyeCy
        val R = eyeH * 1.05f * breathe
        val energy = 0.35f + pupil * 0.75f
        val spin = nowMs / 1000f * (1f - dim * 0.85f) * energy

        drawTilted(canvas, c, cy, tilt * 0.4f) {
            strokePaint.strokeWidth = eyeW * 0.02f
            strokePaint.color = if (dead) DEAD_EYE else HUD_ARC
            strokePaint.alpha = (alphaFor(dim) * 0.25f).toInt()
            strokePaint.setShadowLayer(0f, 0f, 0f, 0)
            canvas.drawCircle(c, cy, R, strokePaint)

            strokePaint.strokeWidth = eyeW * 0.045f
            strokePaint.alpha = alphaFor(dim)
            strokePaint.setShadowLayer(eyeW * 0.12f, 0f, 0f, if (dead) DEAD_EYE else HUD_ARC)
            arcRect.set(c - R, cy - R, c + R, cy + R)
            canvas.drawArc(arcRect, spin * 40f % 360f, 90f, false, strokePaint)

            val r2 = R * 0.72f
            strokePaint.strokeWidth = eyeW * 0.03f
            strokePaint.alpha = (alphaFor(dim) * 0.8f).toInt()
            strokePaint.setShadowLayer(0f, 0f, 0f, 0)
            arcRect.set(c - r2, cy - r2, c + r2, cy + r2)
            canvas.drawArc(arcRect, -spin * 70f % 360f, 200f, false, strokePaint)

            val coreR = eyeW * 0.16f * (0.6f + pupil * 0.5f) * (1f + 0.06f * sin(nowMs / 500f))
            fillPaint.color = if (dead) DEAD_EYE else HUD_CORE
            fillPaint.alpha = alphaFor(dim)
            fillPaint.setShadowLayer(coreR * 1.4f, 0f, 0f, if (dead) DEAD_EYE else HUD_CORE)
            canvas.drawCircle(c, cy, coreR, fillPaint)

            fillPaint.color = if (dead) DEAD_EYE else HUD_ARC
            fillPaint.setShadowLayer(eyeW * 0.1f, 0f, 0f, if (dead) DEAD_EYE else HUD_ARC)
            canvas.drawCircle(c + gx * r2 * 0.85f, cy + gy * r2 * 0.85f, eyeW * 0.05f, fillPaint)
        }
    }

    /** ХЭЛ: один красный глаз, эмоции — прищуром и зрачком. */
    private fun drawHal(
        canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float,
        h: Float, pupil: Float, tilt: Float, dead: Boolean
    ) {
        val c = width / 2f
        val R = eyeH * 0.55f
        drawTilted(canvas, c, eyeCy, tilt * 0.6f) {
            canvas.save()
            canvas.scale(1f, max(openScale * h, 0.05f), c, eyeCy)

            fillPaint.color = HAL_DARK
            fillPaint.alpha = 255
            fillPaint.setShadowLayer(0f, 0f, 0f, 0)
            canvas.drawCircle(c, eyeCy, R, fillPaint)

            strokePaint.strokeWidth = eyeW * 0.06f
            strokePaint.color = if (dead) DEAD_EYE else HAL_RING
            strokePaint.alpha = alphaFor(dim)
            strokePaint.setShadowLayer(eyeW * 0.2f, 0f, 0f, if (dead) DEAD_EYE else HAL_RING)
            canvas.drawCircle(c, eyeCy, R, strokePaint)

            val pr = R * 0.28f * pupil
            if (pr > 2f) {
                fillPaint.color = if (dead) DEAD_EYE else HAL_CORE
                fillPaint.alpha = alphaFor(dim)
                fillPaint.setShadowLayer(R * 0.5f, 0f, 0f, if (dead) DEAD_EYE else HAL_CORE)
                canvas.drawCircle(c + gx * R * 0.35f, eyeCy + gy * R * 0.35f, pr, fillPaint)
            }
            canvas.restore()
        }
    }

    /** Би-Би 8: объектив, эмоции — прищуром и зрачком. */
    private fun drawBb8(
        canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float,
        h: Float, pupil: Float, tilt: Float, dead: Boolean
    ) {
        val c = width / 2f
        val R = eyeH * 0.55f
        drawTilted(canvas, c, eyeCy, tilt * 0.6f) {
            canvas.save()
            canvas.scale(1f, max(openScale * h, 0.05f), c, eyeCy)

            strokePaint.strokeWidth = eyeW * 0.055f
            strokePaint.color = if (dead) DEAD_EYE else BB_RING
            strokePaint.alpha = alphaFor(dim)
            strokePaint.setShadowLayer(eyeW * 0.1f, 0f, 0f, if (dead) DEAD_EYE else BB_RING)
            canvas.drawCircle(c, eyeCy, R, strokePaint)

            strokePaint.strokeWidth = eyeW * 0.04f
            strokePaint.alpha = (alphaFor(dim) * 0.8f).toInt()
            strokePaint.setShadowLayer(0f, 0f, 0f, 0)
            canvas.drawCircle(c, eyeCy, R * 0.55f, strokePaint)

            val mr = R * 0.55f
            val pr = R * 0.18f * pupil
            if (pr > 2f) {
                fillPaint.color = if (dead) DEAD_EYE else BB_PUPIL
                fillPaint.alpha = alphaFor(dim)
                fillPaint.setShadowLayer(mr * 0.5f, 0f, 0f, if (dead) DEAD_EYE else BB_PUPIL)
                canvas.drawCircle(c + gx * mr * 0.55f, eyeCy + gy * mr * 0.55f, pr, fillPaint)
            }

            fillPaint.color = BB_ACCENT
            fillPaint.alpha = alphaFor(dim)
            fillPaint.setShadowLayer(R * 0.15f, 0f, 0f, BB_ACCENT)
            canvas.drawCircle(c + R * 0.72f, eyeCy - R * 0.72f * 0.6f, R * 0.09f, fillPaint)
            canvas.restore()
        }
    }

    private fun drawHearts(canvas: Canvas, nowMs: Long) {
        heartPaint.color = HEART
        for (hrt in hearts) {
            val x = hrt.x + sin(nowMs / 400f + hrt.phase) * hrt.sway
            heartPaint.alpha = (hrt.alpha.coerceIn(0f, 1f) * 255).toInt()
            heartPaint.setShadowLayer(hrt.size * 0.6f, 0f, 0f, HEART)
            heartPath.reset()
            heartPath.moveTo(0f, 0.35f)
            heartPath.cubicTo(-1f, -0.4f, -0.45f, -1.1f, 0f, -0.45f)
            heartPath.cubicTo(0.45f, -1.1f, 1f, -0.4f, 0f, 0.35f)
            canvas.save()
            canvas.translate(x, hrt.y)
            canvas.scale(hrt.size, hrt.size)
            canvas.drawPath(heartPath, heartPaint)
            canvas.restore()
        }
    }

    private fun drawLabel(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("${style.title} · ${emotion.title}", width / 2f, height * 0.9f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawDebug(canvas: Canvas) {
        val v = vision
        val lines = listOf(
            "ДЖАРВИС v0.3 · ${style.title} · ${emotion.title} · " +
                if (state == State.SLEEPING) "спит" else "бодрствует",
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
        private const val KEY_STYLE = "style_index"
        private val COZMO_EYE = Color.parseColor("#5FD4FF")
        private val COZMO_PUPIL = Color.parseColor("#EAFAFF")
        private val EVE_OUTER = Color.parseColor("#D9F2FF")
        private val EVE_INNER = Color.parseColor("#47B6F0")
        private val EVE_GLOW = Color.parseColor("#9CD8FF")
        private val HUD_ARC = Color.parseColor("#5FD4FF")
        private val HUD_CORE = Color.parseColor("#EAFAFF")
        private val HAL_RING = Color.parseColor("#E23B2E")
        private val HAL_CORE = Color.parseColor("#FF6B5A")
        private val HAL_DARK = Color.parseColor("#1A0404")
        private val BB_RING = Color.parseColor("#C9D1D9")
        private val BB_PUPIL = Color.parseColor("#DFE6EC")
        private val BB_ACCENT = Color.parseColor("#FF9A3C")
        private val ANGRY_EYE = Color.parseColor("#FF8A70")
        private val ANGRY_PUPIL = Color.parseColor("#FFD9D2")
        private val DEAD_EYE = Color.parseColor("#8C9BB0")
        private val HEART = Color.parseColor("#FF6B9A")
    }
}
