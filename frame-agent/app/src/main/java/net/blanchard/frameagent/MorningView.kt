package net.blanchard.frameagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * Draws one moment of the morning display. Holds no timing logic: MorningOverlay
 * hands it the kids' stages once a second. Sizes are fractions of the short side
 * so one layout reads across the room on both the portrait and landscape frames.
 */
class MorningView(
    context: Context,
    private val layout: String,
    private val zone: ZoneId,
) : View(context) {

    private var now: Instant = Instant.now()
    private var events: List<MorningEvent> = emptyList()
    private var stages: Map<MorningKid, KidStage> = emptyMap()

    private val clockFormat = DateTimeFormatter.ofPattern("h:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("EEE MMM d")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0))
    }
    private val rect = RectF()

    fun show(now: Instant, events: List<MorningEvent>, stages: Map<MorningKid, KidStage>) {
        this.now = now
        this.events = events
        this.stages = stages
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val short = min(width, height).toFloat()
        val alerting = kidsAt(KidStage.ALERT)
        if (alerting.isNotEmpty()) {
            drawAlert(canvas, alerting, short)
            return
        }
        val final = kidsAt(KidStage.FINAL)
        if (layout == MorningLayout.BAND) {
            val bandTop = drawBand(canvas, short)
            drawFinalCards(canvas, final, bandTop, short)
        } else if (final.isNotEmpty()) {
            drawFinalCards(canvas, final, height.toFloat(), short)
        } else {
            kidsAt(KidStage.COUNTDOWN).firstOrNull()?.let { drawCornerCard(canvas, it, short) }
        }
    }

    private fun kidsAt(stage: KidStage) = stages.filterValues { it == stage }.keys.toList()

    // --- full-screen alert ---------------------------------------------------

    private fun drawAlert(canvas: Canvas, kids: List<MorningKid>, short: Float) {
        canvas.drawColor(withAlpha(kidColor(kids.first()), 245))
        val maxWidth = width * 0.88f
        val cx = width / 2f
        val mid = height / 2f
        centered(canvas, "Time to go!", cx, mid - short * 0.04f, maxWidth, short * 0.17f, true)
        centered(canvas, kids.joinToString(" & ") { it.name }, cx, mid + short * 0.14f, maxWidth, short * 0.13f, true)
        centered(canvas, "Touch the bar on the frame when you're off", cx, height - short * 0.07f, maxWidth, short * 0.032f, false)
    }

    // --- band ----------------------------------------------------------------

    /** Returns the top edge of the band, so cards can sit above it. */
    private fun drawBand(canvas: Canvas, short: Float): Float {
        val w = width.toFloat()
        val h = height.toFloat()
        val landscape = w > h
        val bandH = short * if (landscape) 0.22f else 0.36f
        val top = h - bandH
        fill.color = Color.argb(215, 12, 14, 20)
        canvas.drawRect(0f, top, w, h, fill)

        val pad = short * 0.025f
        val headerH = if (landscape) bandH else bandH * 0.48f
        val clockW = if (landscape) w * 0.19f else w * 0.36f
        val infoRight = if (landscape) w * 0.56f else w

        val localNow = now.atZone(zone)
        val clockSize = min(headerH * 0.46f, clockW * 0.34f)
        text.typeface = Typeface.DEFAULT_BOLD
        fitted(canvas, localNow.format(clockFormat), pad, top + pad + clockSize * 0.85f, clockW - pad, clockSize)
        text.typeface = Typeface.DEFAULT
        fitted(canvas, localNow.format(dateFormat), pad, top + pad + clockSize * 1.45f, clockW - pad, clockSize * 0.36f)

        // Up to three of today's events, next to the clock.
        val lineH = (headerH - pad * 2) / 3
        events.take(MAX_EVENTS).forEachIndexed { i, e ->
            val y = top + pad + lineH * (i + 0.72f)
            fitted(canvas, "${e.start}  ${e.title}".trim(), clockW + pad, y, infoRight - clockW - pad * 2, lineH * 0.72f)
        }

        val chipTop = if (landscape) top + pad else top + headerH
        val chipLeft = if (landscape) infoRight else pad
        rect.set(chipLeft, chipTop, w - pad, h - pad)
        drawChips(canvas, rect.left, rect.top, rect.right, rect.bottom, pad)
        return top
    }

    private fun drawChips(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, gap: Float) {
        val kids = stages.filterValues { it != KidStage.DONE }.keys.filter { it.countdown }.sortedBy { it.leaveAt }
        if (kids.isEmpty()) return
        val chipW = (right - left - gap * (kids.size - 1)) / kids.size
        val chipH = bottom - top
        kids.forEachIndexed { i, kid ->
            val x = left + i * (chipW + gap)
            rect.set(x, top, x + chipW, bottom)
            fill.color = kidColor(kid)
            canvas.drawRoundRect(rect, chipH * 0.14f, chipH * 0.14f, fill)
            val cx = x + chipW / 2
            centered(canvas, kid.name, cx, top + chipH * 0.42f, chipW * 0.9f, chipH * 0.28f, false)
            centered(canvas, MorningClock.countdown(now, kid.leaveAt), cx, top + chipH * 0.82f, chipW * 0.9f, chipH * 0.34f, true)
        }
    }

    // --- cards ---------------------------------------------------------------

    /** The T-5 card. Several at once share the space above [bottom]. */
    private fun drawFinalCards(canvas: Canvas, kids: List<MorningKid>, bottom: Float, short: Float) {
        if (kids.isEmpty()) return
        val cardW = min(short * 0.82f, width * 0.86f)
        val available = bottom - short * 0.04f
        val cardH = min(short * 0.5f, available / kids.size - short * 0.03f)
        val left = (width - cardW) / 2
        val stackH = kids.size * cardH + (kids.size - 1) * short * 0.03f
        var y = (available - stackH) / 2
        kids.forEach { kid ->
            drawCard(canvas, kid, left, y, cardW, cardH)
            y += cardH + short * 0.03f
        }
    }

    /** The T-15 card. */
    private fun drawCornerCard(canvas: Canvas, kid: MorningKid, short: Float) {
        val cardW = short * 0.46f
        val cardH = short * 0.3f
        val margin = short * 0.035f
        drawCard(canvas, kid, width - cardW - margin, height - cardH - margin, cardW, cardH)
    }

    private fun drawCard(canvas: Canvas, kid: MorningKid, x: Float, y: Float, w: Float, h: Float) {
        rect.set(x, y, x + w, y + h)
        fill.color = withAlpha(kidColor(kid), 240)
        canvas.drawRoundRect(rect, h * 0.09f, h * 0.09f, fill)
        val cx = x + w / 2
        val maxWidth = w * 0.9f
        val leaves = kid.leaveAt.atZone(zone).format(clockFormat)
        centered(canvas, kid.name, cx, y + h * 0.27f, maxWidth, h * 0.2f, false)
        centered(canvas, MorningClock.countdown(now, kid.leaveAt), cx, y + h * 0.62f, maxWidth, h * 0.36f, true)
        val footer = if (kid.note.isEmpty()) "Leave at $leaves" else "Leave at $leaves · ${kid.note}"
        centered(canvas, footer, cx, y + h * 0.86f, maxWidth, h * 0.11f, false)
    }

    // --- helpers -------------------------------------------------------------

    private fun centered(canvas: Canvas, s: String, cx: Float, y: Float, maxWidth: Float, size: Float, bold: Boolean) {
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.textSize = size
        val measured = text.measureText(s)
        if (measured > maxWidth) text.textSize = size * maxWidth / measured
        canvas.drawText(s, cx - text.measureText(s) / 2, y, text)
    }

    /** Shrinks to stay readable across the room, then cuts the end off with an ellipsis. */
    private fun fitted(canvas: Canvas, s: String, x: Float, y: Float, maxWidth: Float, size: Float) {
        text.textSize = size
        val measured = text.measureText(s)
        if (measured > maxWidth) text.textSize = maxOf(size * MIN_FIT, size * maxWidth / measured)
        val shown = TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, x, y, text)
    }

    private fun kidColor(kid: MorningKid): Int =
        runCatching { Color.parseColor(kid.color) }.getOrDefault(DEFAULT_KID_COLOR)

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val MAX_EVENTS = 3
        private const val MIN_FIT = 0.75f
        private val DEFAULT_KID_COLOR = Color.rgb(70, 90, 120)
    }
}
