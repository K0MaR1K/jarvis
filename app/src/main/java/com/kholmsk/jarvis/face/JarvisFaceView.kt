package com.kholmsk.jarvis.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Лицо Джарвиса. Пять стилей на выбор (свайп влево/вправо — сменить):
 * Козмо, ЕВА, HUD, ХЭЛ, Би-Би 8. Общие мозги для всех: моргание,
 * слежение за человеком (руки приоритетнее), осматривание, сон и пробуждение.
 * Тап — отладочная строка. Выбор мордочки запоминается.
 */
class JarvisFaceView(context: Context) : View(context) {

    enum class FaceStyle(val title: String) {
        COZMO("Козмо"),
        EVE("ЕВА"),
        HUD("ДЖАРВИС"),
        HAL("ХЭЛ"),
        BB8("Би-Би 8")
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
    private var styleLabelUntilMs = 0L

    private fun setStyle(s: FaceStyle) {
        style = s
        prefs.edit().putInt(KEY_STYLE, s.ordinal).apply()
        styleLabelUntilMs = SystemClock.uptimeMillis() + 1600L
    }

    // -------- жесты: свайп = смена мордочки, тап = отладка --------
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
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
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

    // геометрия, пересчитывается при изменении размера экрана
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
        val dim = if (sleeping) ((nowMs - sleepStartMs) / 900f).coerceIn(0f, 1f) else 0f
        val openness = blinkOpenness(nowMs) * (1f - 0.78f * dim)
        val breathe = 1f + 0.02f * sin(nowMs / 1400f) // едва заметное «дыхание»
        val gx = gazeX
        val gy = gazeY + 0.35f * dim                 // во сне взгляд утыкается вниз

        when (style) {
            FaceStyle.COZMO -> drawCozmo(canvas, gx, gy, openness * breathe, dim)
            FaceStyle.EVE -> drawEve(canvas, gx, gy, openness * breathe, dim)
            FaceStyle.HUD -> drawHud(canvas, nowMs, gx, gy, dim, breathe)
            FaceStyle.HAL -> drawHal(canvas, gx, gy, openness * breathe, dim)
            FaceStyle.BB8 -> drawBb8(canvas, gx, gy, openness * breathe, dim)
        }

        if (styleLabelUntilMs > nowMs) drawStyleLabel(canvas)
        if (debugOverlay) drawDebug(canvas)
        postInvalidateOnAnimation() // следующий кадр анимации
    }

    private fun alphaFor(dim: Float): Int = (255f * (1f - 0.55f * dim)).toInt().coerceIn(0, 255)

    /** Стиль «Козмо»: два скруглённых прямоугольника со квадратными зрачками. */
    private fun drawCozmo(canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float) {
        strokePaint.strokeWidth = eyeW * 0.09f
        strokePaint.color = COZMO_EYE
        strokePaint.alpha = alphaFor(dim)
        strokePaint.setShadowLayer(eyeW * 0.15f, 0f, 0f, COZMO_EYE)
        fillPaint.color = COZMO_PUPIL
        fillPaint.alpha = alphaFor(dim)
        fillPaint.setShadowLayer(pupilSize * 0.16f, 0f, 0f, COZMO_PUPIL)

        val half = eyeGap / 2f + eyeW / 2f
        drawCozmoEye(canvas, width / 2f - half, gx, gy, openScale)
        drawCozmoEye(canvas, width / 2f + half, gx, gy, openScale)
    }

    private fun drawCozmoEye(canvas: Canvas, cx: Float, gx: Float, gy: Float, openScale: Float) {
        val h = eyeH * openScale
        if (h < 2f) return
        eyeRect.set(cx - eyeW / 2f, eyeCy - h / 2f, cx + eyeW / 2f, eyeCy + h / 2f)
        val r = min(eyeW * 0.35f, h / 2f)
        canvas.drawRoundRect(eyeRect, r, r, strokePaint)

        // зрачок скользит внутри глаза, не выходя за контур
        val ph = pupilSize * openScale
        if (ph < 1f) return
        val margin = strokePaint.strokeWidth * 0.7f
        val maxX = (eyeRect.width() - pupilSize) / 2f - margin
        val maxY = (h - ph) / 2f - margin
        val px = eyeRect.centerX() + gx * maxX.coerceAtLeast(0f)
        val py = eyeRect.centerY() + gy * maxY.coerceAtLeast(0f)
        pupilRectSet(px, py, pupilSize, ph)
        canvas.drawRoundRect(eyeRect, min(pupilSize * 0.35f, ph / 2f), min(pupilSize * 0.35f, ph / 2f), fillPaint)
    }

    /** Стиль «ЕВА»: две вытянутые капсулы, взгляд ездит внутренней линзой. */
    private fun drawEve(canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float) {
        val w = eyeW * 0.62f
        val h = eyeH * 1.5f * openScale
        if (h < 4f) return
        val half = eyeGap * 0.75f / 2f + w / 2f

        for (side in intArrayOf(-1, 1)) {
            val cx = width / 2f + side * half
            fillPaint.color = EVE_OUTER
            fillPaint.alpha = alphaFor(dim)
            fillPaint.setShadowLayer(w * 0.5f, 0f, 0f, EVE_GLOW)
            eyeRect.set(cx - w / 2f, eyeCy - h / 2f, cx + w / 2f, eyeCy + h / 2f)
            canvas.drawRoundRect(eyeRect, w / 2f, w / 2f, fillPaint)

            // внутренняя линза — тёмно-голубая, смещается по взгляду
            val lw = w * 0.42f
            val lh = h * 0.55f
            val lx = cx + gx * (w - lw) / 2f * 0.8f
            val ly = eyeCy + gy * (h - lh) / 2f * 0.8f
            fillPaint.color = EVE_INNER
            fillPaint.alpha = alphaFor(dim)
            fillPaint.setShadowLayer(lw * 0.8f, 0f, 0f, EVE_INNER)
            eyeRect.set(lx - lw / 2f, ly - lh / 2f, lx + lw / 2f, ly + lh / 2f)
            canvas.drawRoundRect(eyeRect, lw / 2f, lw / 2f, fillPaint)
        }
    }

    /** Стиль «HUD Джарвиса»: вращающиеся кольца, ядро и сканер-точка. */
    private fun drawHud(canvas: Canvas, nowMs: Long, gx: Float, gy: Float, dim: Float, breathe: Float) {
        val c = width / 2f
        val cy = eyeCy
        val R = eyeH * 1.05f * breathe
        val spin = nowMs / 1000f * (1f - dim * 0.85f)

        // бледное статичное кольцо-основа
        strokePaint.strokeWidth = eyeW * 0.02f
        strokePaint.color = HUD_ARC
        strokePaint.alpha = (alphaFor(dim) * 0.25f).toInt()
        strokePaint.setShadowLayer(0f, 0f, 0f, 0)
        canvas.drawCircle(c, cy, R, strokePaint)

        // внешняя дуга (со свечением), крутится в одну сторону
        strokePaint.strokeWidth = eyeW * 0.045f
        strokePaint.alpha = alphaFor(dim)
        strokePaint.setShadowLayer(eyeW * 0.12f, 0f, 0f, HUD_ARC)
        arcRect.set(c - R, cy - R, c + R, cy + R)
        canvas.drawArc(arcRect, spin * 40f % 360f, 90f, false, strokePaint)

        // внутренняя дуга — в другую, потише
        val r2 = R * 0.72f
        strokePaint.strokeWidth = eyeW * 0.03f
        strokePaint.alpha = (alphaFor(dim) * 0.8f).toInt()
        strokePaint.setShadowLayer(0f, 0f, 0f, 0)
        arcRect.set(c - r2, cy - r2, c + r2, cy + r2)
        canvas.drawArc(arcRect, -spin * 70f % 360f, 200f, false, strokePaint)

        // ядро «дышит»
        val coreR = eyeW * 0.16f * (1f + 0.06f * sin(nowMs / 500f))
        fillPaint.color = HUD_CORE
        fillPaint.alpha = alphaFor(dim)
        fillPaint.setShadowLayer(coreR * 1.4f, 0f, 0f, HUD_CORE)
        canvas.drawCircle(c, cy, coreR, fillPaint)

        // сканер-точка на внутреннем кольце, уезжает к взгляду
        fillPaint.color = HUD_ARC
        fillPaint.setShadowLayer(eyeW * 0.1f, 0f, 0f, HUD_ARC)
        canvas.drawCircle(c + gx * r2 * 0.85f, cy + gy * r2 * 0.85f, eyeW * 0.05f, fillPaint)
    }

    /** Стиль «ХЭЛ»: один красный глаз, моргает «веком» (сжатием). */
    private fun drawHal(canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float) {
        val c = width / 2f
        val R = eyeH * 0.55f
        canvas.save()
        canvas.scale(1f, max(openScale, 0.05f), c, eyeCy)

        fillPaint.color = HAL_DARK
        fillPaint.alpha = 255
        fillPaint.setShadowLayer(0f, 0f, 0f, 0)
        canvas.drawCircle(c, eyeCy, R, fillPaint)

        strokePaint.strokeWidth = eyeW * 0.06f
        strokePaint.color = HAL_RING
        strokePaint.alpha = alphaFor(dim)
        strokePaint.setShadowLayer(eyeW * 0.2f, 0f, 0f, HAL_RING)
        canvas.drawCircle(c, eyeCy, R, strokePaint)

        fillPaint.color = HAL_CORE
        fillPaint.alpha = alphaFor(dim)
        fillPaint.setShadowLayer(R * 0.5f, 0f, 0f, HAL_CORE)
        canvas.drawCircle(c + gx * R * 0.35f, eyeCy + gy * R * 0.35f, R * 0.28f, fillPaint)
        canvas.restore()
    }

    /** Стиль «Би-Би 8»: круглый объектив с гуляющим серебряным зрачком. */
    private fun drawBb8(canvas: Canvas, gx: Float, gy: Float, openScale: Float, dim: Float) {
        val c = width / 2f
        val R = eyeH * 0.55f
        canvas.save()
        canvas.scale(1f, max(openScale, 0.05f), c, eyeCy)

        strokePaint.strokeWidth = eyeW * 0.055f
        strokePaint.color = BB_RING
        strokePaint.alpha = alphaFor(dim)
        strokePaint.setShadowLayer(eyeW * 0.1f, 0f, 0f, BB_RING)
        canvas.drawCircle(c, eyeCy, R, strokePaint)

        strokePaint.strokeWidth = eyeW * 0.04f
        strokePaint.alpha = (alphaFor(dim) * 0.8f).toInt()
        strokePaint.setShadowLayer(0f, 0f, 0f, 0)
        canvas.drawCircle(c, eyeCy, R * 0.55f, strokePaint)

        val mr = R * 0.55f
        fillPaint.color = BB_PUPIL
        fillPaint.alpha = alphaFor(dim)
        fillPaint.setShadowLayer(mr * 0.5f, 0f, 0f, BB_PUPIL)
        canvas.drawCircle(c + gx * mr * 0.55f, eyeCy + gy * mr * 0.55f, R * 0.18f, fillPaint)

        fillPaint.color = BB_ACCENT
        fillPaint.alpha = alphaFor(dim)
        fillPaint.setShadowLayer(R * 0.15f, 0f, 0f, BB_ACCENT)
        canvas.drawCircle(c + R * 0.72f, eyeCy - R * 0.72f, R * 0.09f, fillPaint)
        canvas.restore()
    }

    private fun pupilRectSet(px: Float, py: Float, pw: Float, ph: Float) {
        eyeRect.set(px - pw / 2f, py - ph / 2f, px + pw / 2f, py + ph / 2f)
    }

    private fun drawStyleLabel(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(style.title, width / 2f, height * 0.9f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawDebug(canvas: Canvas) {
        val v = vision
        val lines = listOf(
            "ДЖАРВИС v0.2 · ${style.title} · " +
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
    }
}
