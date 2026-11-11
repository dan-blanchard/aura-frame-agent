package net.blanchard.frameagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The Thanksgiving cast (Dan, 2026-11-09; the Avatar turkey duck was cut). Names are the cmd/ghost `style` values. */
enum class TurkeyStyle {
    HOOTY, // The Owl House: his face is the rounded end of a long caramel-brown tube
    PUMPKIN, // Steven Universe, "Gem Harvest": pumpkin dog, dot eyes, big open mouth, vine tail, four orange stub legs
    FRANKLIN; // Animal Crossing: pink turkey, sleepy blue lids, red wattle, toque and chef's whites, yellow trousers, striped legs

    companion object {
        fun parse(name: String?): TurkeyStyle? = values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * A fixed border of autumn leaves, heaped in the corners, plus a character who pops out
 * of a corner or an edge every few minutes, or walks across: Hooty, Pumpkin or Franklin. HA can also send one across on demand, which is how a
 * character tours the room frame by frame (the same cmd/ghost legs as Halloween).
 * Characters are drawn under the leaves, so a corner visit looks like climbing out of a
 * leaf pile. Everything is drawn from paths, no assets. The border is drawn once into a
 * bitmap; between visits nothing animates, so the view only redraws while someone is on.
 */
class ThanksgivingView(context: Context) : View(context) {

    private val rng = Random(SystemClock.uptimeMillis())

    private var border: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var charH = 0f // a character's height on screen, px; local drawing units are 1 = charH

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tube = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HOOTY_INK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()
    private val tubePath = Path()
    private val rect = RectF()

    private val passes = mutableListOf<Pass>()

    // The frame never sends anyone on its own timer: HA picks when and which frame
    // (cmd/ghost peek), because Dan wants no two peeks on the wall within 5 minutes of each
    // other, and only HA sees all four frames. While HA's tour crosses the wall the frame
    // also refuses peeks (Dan: "Let's not have corner peeks happening at the same time as travels").
    private var quietUntil = 0L

    override fun onDetachedFromWindow() {
        border?.recycle()
        border = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val short = min(w, h).toFloat()
        charH = short * 0.25f
        bubbleText.textSize = charH * 0.15f
        border?.recycle()
        border = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawBorder(Canvas(it), w, h) }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        passes.toList().forEach { pass ->
            val elapsed = now - pass.startMs
            if (elapsed >= pass.totalMs) {
                passes.remove(pass)
            } else if (pass.style == TurkeyStyle.HOOTY && pass.kind == Kind.WALK) {
                drawHootyStretch(canvas, pass, elapsed, now)
            } else {
                drawPass(canvas, pass, elapsed.toFloat() / pass.durationMs, now)
            }
        }
        border?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        if (passes.isNotEmpty()) postInvalidateOnAnimation()
    }

    // --- leaves --------------------------------------------------------------

    private fun drawBorder(canvas: Canvas, w: Int, h: Int) {
        val short = min(w, h).toFloat()
        val leafRng = Random(1126) // fixed: the border is the same every boot
        val leaves = mutableListOf<Leaf>()
        // Heaps in the corners, big leaves over smaller ones.
        for ((cx, cy) in listOf(0f to 0f, w.toFloat() to 0f, 0f to h.toFloat(), w.toFloat() to h.toFloat())) {
            repeat(11) {
                val a = leafRng.nextFloat() * 2 * PI.toFloat()
                val r = short * 0.17f * kotlin.math.sqrt(leafRng.nextFloat())
                leaves += Leaf(cx + cos(a) * r, cy + sin(a) * r, short * (0.045f + leafRng.nextFloat() * 0.045f), leafRng)
            }
        }
        // A thinner run along each edge, mostly off the photo.
        val step = short * 0.075f
        for (edge in 0 until 4) {
            val length = if (edge < 2) w.toFloat() else h.toFloat()
            var s = short * 0.16f
            while (s < length - short * 0.16f) {
                val inset = short * (0.004f + leafRng.nextFloat() * 0.022f)
                val (x, y) = when (edge) {
                    0 -> s to inset
                    1 -> s to h - inset
                    2 -> inset to s
                    else -> w - inset to s
                }
                leaves += Leaf(x, y, short * (0.028f + leafRng.nextFloat() * 0.022f), leafRng)
                s += step * (0.7f + leafRng.nextFloat() * 0.6f)
            }
        }
        leaves.sortBy { it.size } // small first, so the big corner leaves sit on top
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 0, 0, 0) }
        for (leaf in leaves) {
            canvas.save()
            canvas.translate(leaf.x, leaf.y)
            canvas.rotate(leaf.angle)
            canvas.scale(leaf.size, leaf.size)
            leafPath(leaf.kind, path)
            canvas.save()
            canvas.translate(0.06f, 0.09f)
            canvas.drawPath(path, shadow)
            canvas.restore()
            fill.color = leaf.color
            canvas.drawPath(path, fill)
            ink.color = darken(leaf.color, 0.55f)
            ink.strokeWidth = 0.035f
            canvas.drawPath(path, ink)
            ink.color = darken(leaf.color, 0.7f)
            ink.strokeWidth = 0.045f
            leafVeins(leaf.kind, path)
            canvas.drawPath(path, ink)
            canvas.restore()
        }
    }

    private class Leaf(val x: Float, val y: Float, val size: Float, r: Random) {
        val kind = r.nextInt(3) // 0 maple, 1 oak, 2 plain
        val angle = r.nextFloat() * 360f
        val color = LEAF_COLORS[r.nextInt(LEAF_COLORS.size)]
    }

    /** Unit leaf, about 2 tall, tip at y = -1, stem pointing down. */
    private fun leafPath(kind: Int, p: Path) {
        p.reset()
        when (kind) {
            0 -> { // maple: five pointed lobes
                val right = listOf(
                    0f to -1f, 0.12f to -0.62f, 0.33f to -0.74f, 0.27f to -0.4f, 0.64f to -0.52f,
                    0.56f to -0.28f, 0.96f to -0.2f, 0.62f to 0.06f, 0.72f to 0.24f, 0.3f to 0.2f, 0.06f to 0.44f,
                )
                p.moveTo(right[0].first, right[0].second)
                right.drop(1).forEach { (x, y) -> p.lineTo(x, y) }
                right.reversed().drop(1).dropLast(1).forEach { (x, y) -> p.lineTo(-x, y) }
                p.close()
                p.moveTo(-0.025f, 0.4f) // stem
                p.lineTo(0.025f, 0.4f)
                p.lineTo(0.03f, 0.95f)
                p.lineTo(-0.03f, 0.95f)
                p.close()
            }
            1 -> { // oak: a long leaf with a wavy, lobed edge
                val n = 72
                for (i in 0..n) {
                    val a = 2 * PI * i / n
                    val wave = 1f + 0.2f * sin(a * 6).toFloat()
                    val x = 0.46f * wave * cos(a).toFloat()
                    val y = 0.92f * sin(a).toFloat() * (0.9f + 0.1f * wave)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            else -> { // a plain pointed leaf
                p.moveTo(0f, -1f)
                p.cubicTo(0.62f, -0.5f, 0.55f, 0.45f, 0f, 0.85f)
                p.cubicTo(-0.55f, 0.45f, -0.62f, -0.5f, 0f, -1f)
                p.close()
            }
        }
    }

    private fun leafVeins(kind: Int, p: Path) {
        p.reset()
        p.moveTo(0f, 0.75f)
        p.lineTo(0f, -0.8f)
        val ribs = if (kind == 0) listOf(0.2f to (0.7f to -0.25f), 0.25f to (0.45f to -0.55f)) else
            listOf(0.35f to (0.3f to 0.05f), 0f to (0.3f to -0.3f), -0.35f to (0.25f to -0.6f))
        for ((y0, end) in ribs) {
            p.moveTo(0f, y0)
            p.lineTo(end.first, end.second)
            p.moveTo(0f, y0)
            p.lineTo(-end.first, end.second)
        }
    }

    // --- visits --------------------------------------------------------------

    private enum class Kind { CORNER, SIDE, BOTTOM, WALK }

    private class Pass(
        val style: TurkeyStyle,
        val kind: Kind,
        var startMs: Long,
        var durationMs: Long,
        var faceRight: Boolean,
        // CORNER: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right.
        // SIDE: 0 left, 1 right, with anchor = y. BOTTOM: anchor = x.
        val where: Int = 0,
        val anchor: Float = 0.5f,
        // WALK and HA's tour legs run off-screen to off-screen, like the ghosts.
        var from: GhostEdge = GhostEdge.LEFT,
        var to: GhostEdge = GhostEdge.RIGHT,
        var fromPos: Float = 0.5f,
        var toPos: Float = 0.5f,
        val visitor: Boolean = false,
        // Hooty's walks only: his body stays stretched across after his head has gone, then
        // his head comes back the way it went. HA sets these on a tour so that he spans
        // every frame he has passed and pulls back through them in reverse.
        var holdMs: Long = 0,
        var returnMs: Long = 0,
    ) {
        /** How far along his walk Hooty's head starts back from: 1 unless he was hurried off. */
        var returnFrom = 1f
        val totalMs get() = durationMs + holdMs + returnMs
    }

    /** One leg of a tour: enter at one edge, leave at another, on HA's timing. */
    fun visit(
        style: TurkeyStyle?, from: GhostEdge, to: GhostEdge, fromPos: Float, toPos: Float, durationMs: Long,
        holdMs: Long = -1, returnMs: Long = -1,
    ) {
        val who = style ?: TurkeyStyle.values().random(rng)
        val hooty = who == TurkeyStyle.HOOTY
        passes += Pass(
            style = who,
            kind = Kind.WALK,
            startMs = SystemClock.uptimeMillis(),
            durationMs = durationMs,
            faceRight = edgeX(to, toPos) >= edgeX(from, fromPos),
            from = from,
            to = to,
            fromPos = fromPos,
            toPos = toPos,
            visitor = true,
            holdMs = if (!hooty) 0 else if (holdMs >= 0) holdMs else 1_500,
            returnMs = if (!hooty) 0 else if (returnMs >= 0) returnMs else durationMs * 6 / 10,
        )
        android.util.Log.i("FrameAgent", "turkey: ${passes.last().style} visit $from->$to")
        postInvalidateOnAnimation()
    }

    /**
     * One peek (or a walk across this frame), from HA's cmd/ghost peek or the adb hook.
     * Null style or kind means random. Ignored while one is already out or a tour is on.
     */
    fun summon(style: TurkeyStyle? = null, kind: String? = null) {
        if (SystemClock.uptimeMillis() < quietUntil) {
            android.util.Log.i("FrameAgent", "turkey: peek ignored, tour on")
            return
        }
        if (passes.none { !it.visitor }) startPass(style, Kind.values().firstOrNull { it.name.equals(kind, true) })
    }

    /**
     * HA is about to send a visitor across the wall (cmd/ghost quiet_ms): hurry our own
     * peek off and take no other peek for [ms].
     */
    fun quiet(ms: Long) {
        val now = SystemClock.uptimeMillis()
        quietUntil = max(quietUntil, now + ms)
        passes.filter { !it.visitor }.forEach { hurryOff(it, now) }
        android.util.Log.i("FrameAgent", "turkey: quiet for $ms ms")
        postInvalidateOnAnimation()
    }

    /** Ends a pass early but smoothly: peeks duck back, walkers leave by the nearer side. */
    private fun hurryOff(p: Pass, now: Long) {
        val elapsed = now - p.startMs
        when {
            p.style == TurkeyStyle.HOOTY && p.kind == Kind.WALK -> when {
                elapsed < p.durationMs -> { // head still going: turn back from here
                    p.returnFrom = elapsed.toFloat() / p.durationMs
                    p.durationMs = max(1L, elapsed)
                    p.holdMs = 0
                    p.returnMs = max(600L, (p.returnFrom * HURRY_HOOTY_MS).toLong())
                }
                elapsed < p.durationMs + p.holdMs -> { // stretched across, head gone: come back now
                    p.holdMs = elapsed - p.durationMs
                    p.returnMs = min(p.returnMs, HURRY_HOOTY_MS)
                }
            }
            p.kind == Kind.WALK -> {
                var t = (elapsed.toFloat() / p.durationMs).coerceIn(0f, 1f)
                if (t < 0.5f) { // nearer the side he came in by: turn round
                    p.from = p.to.also { p.to = p.from }
                    p.fromPos = p.toPos.also { p.toPos = p.fromPos }
                    p.faceRight = !p.faceRight
                    t = 1f - t
                }
                p.durationMs = HURRY_WALK_MS
                p.startMs = now - (t * HURRY_WALK_MS).toLong()
            }
            else -> {
                val t = elapsed.toFloat() / p.durationMs
                if (t >= 0.75f) return // already going
                // Jump to the point of the way back that is just as far out, then go back quickly.
                val out = peekCurve(t)
                val back = 0.75f + 0.25f * (acos(2 * out - 1) / PI.toFloat())
                p.durationMs = HURRY_PEEK_MS * 4
                p.startMs = now - (back * p.durationMs).toLong()
            }
        }
    }

    private fun startPass(style: TurkeyStyle? = null, forcedKind: Kind? = null) {
        val who = style ?: TurkeyStyle.values().random(rng)
        val roll = rng.nextInt(20)
        val kind = forcedKind ?: when {
            roll < 8 -> Kind.CORNER
            roll < 12 -> Kind.SIDE
            roll < 15 -> Kind.BOTTOM
            else -> Kind.WALK
        }
        // Only Hooty hangs from the top; the birds and Pumpkin stay the right way up.
        val corner = if (who == TurkeyStyle.HOOTY) rng.nextInt(4) else 2 + rng.nextInt(2)
        val side = rng.nextInt(2)
        val low = if (who == TurkeyStyle.HOOTY) 0.15f + rng.nextFloat() * 0.75f else 0.6f + rng.nextFloat() * 0.35f
        val nearCorner = if (rng.nextBoolean()) 0.06f + rng.nextFloat() * 0.2f else 0.74f + rng.nextFloat() * 0.2f
        val leftToRight = rng.nextBoolean()
        val walkY = if (who == TurkeyStyle.HOOTY) 0.2f + rng.nextFloat() * 0.7f else 0.7f + rng.nextFloat() * 0.25f
        val pass = Pass(
            style = who,
            kind = kind,
            startMs = SystemClock.uptimeMillis(),
            durationMs = when (kind) {
                Kind.WALK -> rng.nextLong(14_000, 19_000)
                else -> rng.nextLong(8_000, 11_000)
            },
            faceRight = when (kind) {
                Kind.CORNER -> corner % 2 == 0 // face into the picture
                Kind.SIDE -> side == 0
                Kind.BOTTOM -> nearCorner < 0.5f
                Kind.WALK -> leftToRight
            },
            where = if (kind == Kind.CORNER) corner else side,
            anchor = if (kind == Kind.BOTTOM) nearCorner else low,
            from = if (leftToRight) GhostEdge.LEFT else GhostEdge.RIGHT,
            to = if (leftToRight) GhostEdge.RIGHT else GhostEdge.LEFT,
            fromPos = walkY,
            toPos = (walkY + (rng.nextFloat() - 0.5f) * 0.1f).coerceIn(0.1f, 0.95f),
            holdMs = if (who == TurkeyStyle.HOOTY && kind == Kind.WALK) 2_500 else 0,
            returnMs = if (who == TurkeyStyle.HOOTY && kind == Kind.WALK) 6_000 else 0,
        )
        passes += pass
        android.util.Log.i("FrameAgent", "turkey: ${pass.style} ${pass.kind} where=${pass.where} anchor=${pass.anchor}")
        postInvalidateOnAnimation()
    }

    // Positions are a character's bottom centre, in px. Off-screen means fully hidden.
    private fun edgeX(edge: GhostEdge, pos: Float) = when (edge) {
        GhostEdge.LEFT -> -charH * 0.75f
        GhostEdge.RIGHT -> width + charH * 0.75f
        else -> charH * 0.5f + pos * (width - charH)
    }

    private fun edgeY(edge: GhostEdge, pos: Float) = when (edge) {
        GhostEdge.TOP -> -charH * 0.05f
        GhostEdge.BOTTOM -> height + charH * 1.05f
        else -> charH + pos * (height - charH)
    }

    private class Pose(val x: Float, val y: Float, val tilt: Float, val upsideDown: Boolean, val faceRight: Boolean)

    private fun poseAt(pass: Pass, t: Float): Pose {
        val w = width.toFloat()
        val h = height.toFloat()
        val out = peekCurve(t)
        // Peeking characters look the other way for a moment in the middle of the hold.
        val glance = pass.kind != Kind.WALK && t in 0.44f..0.56f
        val face = pass.faceRight != glance
        return when (pass.kind) {
            Kind.WALK -> {
                val fx = edgeX(pass.from, pass.fromPos)
                val fy = edgeY(pass.from, pass.fromPos)
                Pose(fx + t * (edgeX(pass.to, pass.toPos) - fx), fy + t * (edgeY(pass.to, pass.toPos) - fy), 0f, false, face)
            }
            Kind.SIDE -> {
                val y = charH + pass.anchor * (h - charH)
                val x = if (pass.where == 0) -charH * 0.75f + out * charH * 1.2f else w + charH * 0.75f - out * charH * 1.2f
                Pose(x, y, if (pass.where == 0) 8f else -8f, false, face)
            }
            Kind.BOTTOM -> {
                val x = charH * 0.5f + pass.anchor * (w - charH)
                Pose(x, h + charH * 1.05f - out * charH * 0.85f, 0f, false, face)
            }
            Kind.CORNER -> {
                val left = pass.where % 2 == 0
                val top = pass.where < 2
                // Out along the diagonal, leaning into the picture.
                val dx = if (left) 1f else -1f
                val dy = if (top) 1f else -1f
                val cx = if (left) 0f else w
                val cy = if (top) 0f else h
                val reach = -1.0f + out * 1.9f // in charH, along the diagonal from the corner
                val x = cx + dx * reach * charH * 0.7f
                val y = cy + dy * reach * charH * 0.7f + (if (top) -charH * 0.05f else charH * 0.3f)
                val lean = if (left) 22f else -22f
                Pose(x, y, if (top) 180f - lean else lean, top, face)
            }
        }
    }

    private fun drawPass(canvas: Canvas, pass: Pass, t: Float, now: Long) {
        val pose = poseAt(pass, t)
        val phase = (now - pass.startMs) / 1000f * 2 * PI.toFloat() * 1.6f // about a step and a half a second
        val moving = pass.kind == Kind.WALK || t < 0.25f || t > 0.75f
        var bounce = 0f
        var sway = 0f
        when (pass.style) {
            TurkeyStyle.PUMPKIN -> if (moving) bounce = -abs(sin(phase)) * 0.14f * charH
            TurkeyStyle.HOOTY -> sway = sin(phase * 0.6f) * 6f
            else -> if (moving) sway = sin(phase) * 5f
        }
        if (pass.style == TurkeyStyle.HOOTY) {
            // The tube must end where the head is actually drawn, after the lean and sway.
            val (hx, hy) = hootyHeadCentre(pose.x, pose.y, pose.tilt + sway)
            val start = poseAt(pass, 0f)
            val (sx, sy) = hootyHeadCentre(start.x, start.y, start.tilt)
            drawHootyTube(canvas, hx, hy, sx, sy, now)
            // His face stays the right way up, even hanging down from the top.
            canvas.save()
            canvas.translate(hx, hy)
            canvas.rotate(if (pose.upsideDown) pose.tilt - 180f + sway else pose.tilt + sway)
            canvas.scale(if (pose.faceRight) charH else -charH, charH)
            canvas.translate(0f, 0.5f)
            drawHootyHead(canvas)
            canvas.restore()
            if (pass.kind != Kind.WALK && t in 0.3f..0.72f) drawHoot(canvas, pose)
            return
        }
        canvas.save()
        canvas.translate(pose.x, pose.y + bounce)
        canvas.rotate(pose.tilt + sway)
        canvas.scale(if (pose.faceRight) charH else -charH, charH)
        val step = if (moving) sin(phase) else 0f
        when (pass.style) {
            TurkeyStyle.HOOTY -> Unit // drawn above, with his tube
            TurkeyStyle.PUMPKIN -> drawPumpkin(canvas, step)
            TurkeyStyle.FRANKLIN -> drawFranklin(canvas, step)
        }
        canvas.restore()
    }

    // --- the cast, each facing right, bottom centre at (0,0), about 1 tall ----

    private fun outline(width: Float, color: Int) {
        ink.strokeWidth = width
        ink.color = color
    }

    private fun paint(canvas: Canvas, color: Int, inkColor: Int, line: Float = 0.022f) {
        fill.color = color
        canvas.drawPath(path, fill)
        outline(line, inkColor)
        canvas.drawPath(path, ink)
    }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) {
        path.reset()
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        path.addOval(rect, Path.Direction.CW)
    }

    private fun eye(canvas: Canvas, cx: Float, cy: Float, r: Float, iris: Int = Color.rgb(24, 20, 22)) {
        fill.color = iris
        canvas.drawCircle(cx, cy, r, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.35f, r * 0.35f, fill)
    }

    /** Fan of rounded feathers spread over [startDeg, endDeg] around (px, py). */
    private fun fan(canvas: Canvas, px: Float, py: Float, length: Float, startDeg: Float, endDeg: Float, count: Int, colors: List<Int>, inkColor: Int) {
        for (i in 0 until count) {
            val a = Math.toRadians((startDeg + (endDeg - startDeg) * i / (count - 1)).toDouble())
            val ex = px + cos(a).toFloat() * length
            val ey = py + sin(a).toFloat() * length
            val nx = -sin(a).toFloat() * length * 0.2f
            val ny = cos(a).toFloat() * length * 0.2f
            path.reset()
            path.moveTo(px, py)
            path.quadTo(px + (ex - px) * 0.55f + nx, py + (ey - py) * 0.55f + ny, ex, ey)
            path.quadTo(px + (ex - px) * 0.55f - nx, py + (ey - py) * 0.55f - ny, px, py)
            path.close()
            paint(canvas, colors[i % colors.size], inkColor)
        }
    }

    /**
     * Pumpkin as drawn in Steven Universe: a round ribbed pumpkin, small cream dot eyes,
     * a huge open mouth (jagged cream top, cream fang edge, maroon inside, orange tongue),
     * four orange stub legs, a leaf on top and a vine tail ending in a leaf. Heavy black ink.
     */
    private fun drawPumpkin(canvas: Canvas, step: Float) {
        val inkC = Color.rgb(22, 12, 8)
        val orange = Color.rgb(250, 135, 34)
        // Far legs, then the vine and leaves, all behind the body.
        pumpkinLeg(canvas, -0.3f, -1f, max(0f, -step), orange, inkC) // far pair, splayed out
        pumpkinLeg(canvas, 0.33f, 1f, max(0f, step), orange, inkC)
        pumpkinLeg(canvas, -0.14f, -0.6f, max(0f, step), orange, inkC) // near pair
        pumpkinLeg(canvas, 0.18f, 0.6f, max(0f, -step), orange, inkC)
        outline(0.075f, inkC)
        path.reset()
        path.moveTo(-0.02f, -0.83f)
        path.cubicTo(-0.3f, -0.84f, -0.46f, -0.68f, -0.4f, -0.45f)
        path.moveTo(-0.33f, -0.3f)
        path.quadTo(-0.47f, -0.12f, -0.6f, -0.24f)
        canvas.drawPath(path, ink)
        outline(0.042f, PUMPKIN_GREEN)
        canvas.drawPath(path, ink)
        canvas.save() // tail leaf, an oak leaf like the border's
        canvas.translate(-0.68f, -0.36f)
        canvas.rotate(-35f)
        canvas.scale(0.11f, 0.11f)
        leafPath(1, path)
        fill.color = PUMPKIN_GREEN
        canvas.drawPath(path, fill)
        outline(0.22f, inkC)
        canvas.drawPath(path, ink)
        outline(0.12f, Color.rgb(40, 100, 45))
        canvas.drawLine(0f, 0.6f, 0f, -0.6f, ink)
        canvas.restore()
        path.reset() // the big leaf on top
        path.moveTo(-0.02f, -0.8f)
        path.quadTo(-0.2f, -0.98f, -0.32f, -0.98f)
        path.quadTo(-0.27f, -0.94f, -0.29f, -0.9f)
        path.quadTo(-0.08f, -1.12f, 0.12f, -0.86f)
        path.close()
        paint(canvas, PUMPKIN_GREEN, inkC, 0.022f)
        // Body, with a dip at the top.
        path.reset()
        path.moveTo(0.05f, -0.82f)
        path.cubicTo(0.3f, -0.9f, 0.46f, -0.7f, 0.43f, -0.45f)
        path.cubicTo(0.4f, -0.16f, 0.2f, -0.06f, 0.02f, -0.07f)
        path.cubicTo(-0.22f, -0.08f, -0.4f, -0.22f, -0.4f, -0.48f)
        path.cubicTo(-0.4f, -0.76f, -0.18f, -0.88f, 0.05f, -0.82f)
        path.close()
        paint(canvas, orange, inkC, 0.03f)
        outline(0.016f, Color.rgb(178, 72, 10)) // ribs
        path.reset()
        path.moveTo(-0.16f, -0.72f)
        path.quadTo(-0.3f, -0.45f, -0.14f, -0.13f)
        path.moveTo(0.36f, -0.6f)
        path.quadTo(0.44f, -0.38f, 0.3f, -0.16f)
        path.moveTo(0.06f, -0.76f)
        path.quadTo(0.1f, -0.68f, 0.09f, -0.6f)
        path.moveTo(0.1f, -0.2f)
        path.quadTo(0.1f, -0.13f, 0.06f, -0.08f)
        canvas.drawPath(path, ink)
        // Small cream dot eyes.
        for (x in listOf(0.07f, 0.29f)) {
            oval(x, -0.63f, 0.045f, 0.045f)
            paint(canvas, PUMPKIN_CREAM, Color.rgb(96, 30, 6), 0.014f)
        }
        // The mouth: cream first, then the dark inside inset from the top and right,
        // which leaves the jagged teeth along the top and the fang down the right side.
        path.reset()
        path.moveTo(0.0f, -0.52f)
        path.lineTo(0.08f, -0.49f)
        path.lineTo(0.15f, -0.55f)
        path.lineTo(0.22f, -0.5f)
        path.lineTo(0.28f, -0.54f)
        path.lineTo(0.33f, -0.5f)
        path.lineTo(0.38f, -0.55f)
        path.quadTo(0.44f, -0.42f, 0.33f, -0.25f)
        path.quadTo(0.28f, -0.21f, 0.22f, -0.24f)
        path.quadTo(0.12f, -0.28f, 0.06f, -0.27f)
        path.quadTo(0.0f, -0.3f, 0.0f, -0.52f)
        path.close()
        paint(canvas, PUMPKIN_CREAM, Color.rgb(96, 30, 6), 0.016f)
        val inside = Path().apply {
            moveTo(0.0f, -0.5f)
            lineTo(0.06f, -0.46f)
            lineTo(0.14f, -0.5f)
            lineTo(0.2f, -0.46f)
            lineTo(0.27f, -0.49f)
            lineTo(0.31f, -0.46f)
            lineTo(0.33f, -0.48f)
            quadTo(0.36f, -0.38f, 0.27f, -0.26f)
            quadTo(0.24f, -0.25f, 0.21f, -0.26f)
            quadTo(0.12f, -0.29f, 0.06f, -0.28f)
            quadTo(0.01f, -0.3f, 0.0f, -0.5f)
            close()
        }
        fill.color = Color.rgb(84, 0, 0)
        canvas.drawPath(inside, fill)
        canvas.save()
        canvas.clipPath(inside)
        path.reset() // tongue, with a dip in the middle
        path.moveTo(-0.02f, -0.34f)
        path.quadTo(0.08f, -0.4f, 0.15f, -0.33f)
        path.quadTo(0.24f, -0.39f, 0.32f, -0.3f)
        path.lineTo(0.32f, -0.2f)
        path.lineTo(-0.02f, -0.2f)
        path.close()
        fill.color = Color.rgb(203, 76, 4)
        canvas.drawPath(path, fill)
        canvas.restore()
        outline(0.014f, Color.rgb(96, 30, 6))
        canvas.drawPath(inside, ink)
    }

    /** A pointed orange stub from inside the body's bottom to the ground, leaning by `splay`. */
    private fun pumpkinLeg(canvas: Canvas, x: Float, splay: Float, lift: Float, orange: Int, inkC: Int) {
        val l = lift * 0.05f
        val tip = x + splay * 0.09f
        path.reset()
        path.moveTo(x - 0.075f, -0.2f)
        path.quadTo(tip - 0.07f, -0.07f - l, tip - 0.02f, -0.005f - l)
        path.quadTo(tip + 0.04f, 0.01f - l, tip + 0.06f, -0.04f - l)
        path.quadTo(x + 0.07f, -0.12f, x + 0.075f, -0.2f)
        path.close()
        paint(canvas, orange, inkC, 0.026f)
    }

    /**
     * Franklin as in New Horizons: tall white toque, pink head with sleepy blue lids in red
     * patches, light blue beak over a red wattle, frilled pink neck, double-breasted whites
     * with gold buttons, yellow trousers with a bow, pink-and-maroon striped legs, grey wings.
     */
    private fun drawFranklin(canvas: Canvas, step: Float) {
        val inkC = Color.rgb(74, 48, 64)
        val pink = Color.rgb(238, 156, 191)
        val red = Color.rgb(192, 64, 94)
        val grey = listOf(Color.rgb(214, 210, 226), Color.rgb(170, 165, 192))
        // Tail feathers behind.
        fan(canvas, -0.13f, -0.5f, 0.27f, 150f, 235f, 5, grey, inkC)
        // Striped legs and pink feet.
        for ((i, x) in listOf(-0.06f, 0.08f).withIndex()) {
            val l = (if (i == 0) max(0f, step) else max(0f, -step)) * 0.05f
            rect.set(x - 0.034f, -0.25f, x + 0.034f, -0.035f - l)
            fill.color = pink
            canvas.drawRect(rect, fill)
            fill.color = Color.rgb(158, 47, 74)
            for (y in listOf(-0.2f, -0.14f, -0.08f)) canvas.drawRect(x - 0.034f, y, x + 0.034f, y + 0.028f, fill)
            outline(0.014f, inkC)
            canvas.drawRect(rect, ink)
            oval(x + 0.035f, -0.025f - l, 0.07f, 0.026f)
            paint(canvas, pink, inkC, 0.014f)
        }
        // Yellow trousers.
        path.reset()
        path.moveTo(-0.17f, -0.4f)
        path.lineTo(0.18f, -0.4f)
        path.quadTo(0.2f, -0.28f, 0.15f, -0.22f)
        path.lineTo(-0.14f, -0.22f)
        path.quadTo(-0.19f, -0.28f, -0.17f, -0.4f)
        path.close()
        paint(canvas, Color.rgb(246, 216, 118), Color.rgb(150, 120, 40), 0.016f)
        // Back wing hanging at his side.
        fan(canvas, -0.13f, -0.6f, 0.23f, 105f, 150f, 4, grey, inkC)
        // Chef's whites, double-breasted.
        rect.set(-0.18f, -0.66f, 0.19f, -0.37f)
        fill.color = Color.rgb(250, 250, 247)
        canvas.drawRoundRect(rect, 0.08f, 0.08f, fill)
        outline(0.018f, inkC)
        canvas.drawRoundRect(rect, 0.08f, 0.08f, ink)
        fill.color = Color.rgb(200, 162, 74)
        for (y in listOf(-0.58f, -0.51f, -0.44f)) {
            canvas.drawCircle(0.03f, y, 0.015f, fill)
            canvas.drawCircle(0.11f, y, 0.015f, fill)
        }
        // Yellow bow at the waist.
        path.reset()
        path.moveTo(0.07f, -0.38f)
        path.lineTo(0.0f, -0.41f)
        path.lineTo(0.0f, -0.35f)
        path.close()
        path.moveTo(0.07f, -0.38f)
        path.lineTo(0.14f, -0.41f)
        path.lineTo(0.14f, -0.35f)
        path.close()
        paint(canvas, Color.rgb(240, 192, 48), Color.rgb(150, 110, 20), 0.012f)
        // Frilled pink neck.
        path.reset()
        path.moveTo(0.0f, -0.64f)
        path.lineTo(0.03f, -0.76f)
        path.lineTo(0.15f, -0.76f)
        path.lineTo(0.14f, -0.64f)
        path.close()
        paint(canvas, pink, inkC, 0.016f)
        for (x in listOf(-0.01f, 0.04f, 0.09f, 0.14f)) {
            oval(x, -0.645f, 0.034f, 0.026f)
            paint(canvas, Color.rgb(217, 112, 154), inkC, 0.012f)
        }
        // Front wing raised to his chin, as he stands in the game.
        fan(canvas, 0.15f, -0.56f, 0.17f, -115f, -70f, 4, grey, inkC)
        // Head, facing us as in the game: an eye either side of the beak.
        oval(0.06f, -0.82f, 0.15f, 0.13f)
        paint(canvas, pink, inkC, 0.018f)
        // Sleepy eyes: red patches, half-closed blue lids.
        for (x in listOf(-0.015f, 0.135f)) {
            oval(x, -0.85f, 0.05f, 0.044f)
            paint(canvas, red, inkC, 0.01f)
            fill.color = Color.WHITE
            canvas.drawCircle(x, -0.845f, 0.032f, fill)
            fill.color = Color.rgb(40, 60, 140)
            canvas.drawCircle(x, -0.837f, 0.015f, fill)
            rect.set(x - 0.034f, -0.88f, x + 0.034f, -0.812f)
            fill.color = Color.rgb(85, 102, 200)
            canvas.drawArc(rect, 180f, 180f, true, fill)
            outline(0.01f, inkC)
            canvas.drawLine(x - 0.034f, -0.846f, x + 0.034f, -0.846f, ink)
        }
        // Red wattle hanging from under the beak, down over the neck.
        path.reset()
        path.moveTo(0.03f, -0.79f)
        path.cubicTo(0.0f, -0.7f, 0.02f, -0.63f, 0.06f, -0.62f)
        path.cubicTo(0.1f, -0.63f, 0.12f, -0.7f, 0.09f, -0.79f)
        path.close()
        paint(canvas, red, inkC, 0.014f)
        // Light blue beak, in the middle, pointing down.
        path.reset()
        path.moveTo(0.02f, -0.825f)
        path.quadTo(0.06f, -0.84f, 0.1f, -0.825f)
        path.quadTo(0.09f, -0.77f, 0.06f, -0.75f)
        path.quadTo(0.03f, -0.77f, 0.02f, -0.825f)
        path.close()
        paint(canvas, Color.rgb(169, 192, 236), inkC, 0.012f)
        // Tall toque: ink drawn first and filled over, so the puffs read as one hat.
        val puffs = listOf(Triple(-0.02f, -1.03f, 0.075f), Triple(0.08f, -1.09f, 0.085f), Triple(0.17f, -1.03f, 0.07f))
        rect.set(-0.07f, -1.04f, 0.21f, -0.89f)
        outline(0.036f, inkC)
        for ((cx, cy, r) in puffs) canvas.drawCircle(cx, cy, r, ink)
        canvas.drawRoundRect(rect, 0.02f, 0.02f, ink)
        fill.color = Color.WHITE
        for ((cx, cy, r) in puffs) canvas.drawCircle(cx, cy, r, fill)
        canvas.drawRoundRect(rect, 0.02f, 0.02f, fill)
        outline(0.012f, Color.rgb(200, 200, 205)) // pleats and the band's edge
        canvas.drawLine(0.03f, -1.02f, 0.03f, -0.94f, ink)
        canvas.drawLine(0.12f, -1.02f, 0.12f, -0.94f, ink)
        canvas.drawLine(-0.07f, -0.93f, 0.21f, -0.93f, ink)
    }

    /** Where Hooty's head centre lands for a pose's bottom centre, after rotating by `deg`. */
    private fun hootyHeadCentre(x: Float, y: Float, deg: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return Pair(x + charH * 0.5f * sin(a).toFloat(), y - charH * 0.5f * cos(a).toFloat())
    }

    /**
     * Peeks: his tube runs from his head back past where he started (off the edge he came
     * in by), with a slow bend.
     */
    private fun drawHootyTube(canvas: Canvas, headX: Float, headY: Float, startX: Float, startY: Float, now: Long) {
        val wiggle = charH * 0.18f
        val ax = startX + (startX - headX) * 2f
        val ay = startY + (startY - headY) * 2f
        val mx = (ax + headX) / 2 + sin(now / 400f) * wiggle * 2
        val my = (ay + headY) / 2 + cos(now / 330f) * wiggle * 2
        tubePath.reset()
        tubePath.moveTo(ax, ay)
        tubePath.quadTo(mx, my, headX, headY)
        strokeTube(canvas)
    }

    /**
     * Hooty never shows his other end: on a walk his body runs from beyond the edge he
     * came in by all the way to his head, so it ends up stretched right across the frame.
     * It stays there after his head has left (on a tour, while he crosses the other frames),
     * then his head comes back and he pulls out the way he came.
     */
    private fun drawHootyStretch(canvas: Canvas, pass: Pass, elapsed: Long, now: Long) {
        val back = elapsed > pass.durationMs + pass.holdMs
        val u = when {
            elapsed < pass.durationMs -> elapsed.toFloat() / pass.durationMs
            !back -> 1f
            else -> pass.returnFrom * (1f - ease((elapsed - pass.durationMs - pass.holdMs).toFloat() / pass.returnMs))
        }
        val (hx, hy) = walkPoint(pass, u)
        val (ax, ay) = walkPoint(pass, -0.35f) // well beyond the entry edge
        val headX = hx
        val headY = hy - charH * 0.5f
        val anchorY = ay - charH * 0.5f
        val dist = hypot(headX - ax, headY - anchorY)
        tubePath.reset()
        if (dist > 1f) {
            // Gentle S-bends along the body, pinned at both ends, drifting slowly.
            val nx = -(headY - anchorY) / dist
            val ny = (headX - ax) / dist
            val waves = max(1f, dist / (charH * 4.5f))
            val n = 48
            for (i in 0..n) {
                val f = i / n.toFloat()
                val off = sin(f * waves * 2 * PI.toFloat() + now / 700f) * sin(f * PI.toFloat()) * charH * 0.35f
                val x = ax + (headX - ax) * f + nx * off
                val y = anchorY + (headY - anchorY) * f + ny * off
                if (i == 0) tubePath.moveTo(x, y) else tubePath.lineTo(x, y)
            }
            strokeTube(canvas)
        }
        val faceRight = (edgeX(pass.to, pass.toPos) >= edgeX(pass.from, pass.fromPos)) != back
        canvas.save()
        canvas.translate(headX, hy)
        canvas.rotate(sin(now / 300f) * 6f)
        canvas.scale(if (faceRight) charH else -charH, charH)
        drawHootyHead(canvas)
        canvas.restore()
    }

    private fun strokeTube(canvas: Canvas) {
        tube.strokeWidth = charH * 0.47f
        tube.color = HOOTY_INK
        canvas.drawPath(tubePath, tube)
        tube.strokeWidth = charH * 0.42f
        tube.color = HOOTY_TUBE
        canvas.drawPath(tubePath, tube)
    }

    private fun walkPoint(pass: Pass, t: Float): Pair<Float, Float> {
        val fx = edgeX(pass.from, pass.fromPos)
        val fy = edgeY(pass.from, pass.fromPos)
        return Pair(fx + t * (edgeX(pass.to, pass.toPos) - fx), fy + t * (edgeY(pass.to, pass.toPos) - fy))
    }

    /**
     * The rounded end of his tube, about as wide as it: caramel crown coming to a point
     * between the eyes, cream heart-shaped face, big dark eyes, small orange beak.
     */
    private fun drawHootyHead(canvas: Canvas) {
        oval(0f, -0.5f, 0.27f, 0.26f)
        paint(canvas, HOOTY_TUBE, HOOTY_INK, 0.026f)
        path.reset() // heart-shaped face: two lobes over the eyes, crown dips between them
        path.moveTo(0f, -0.57f)
        path.cubicTo(0.04f, -0.7f, 0.2f, -0.72f, 0.24f, -0.58f)
        path.cubicTo(0.27f, -0.44f, 0.16f, -0.29f, 0f, -0.27f)
        path.cubicTo(-0.16f, -0.29f, -0.27f, -0.44f, -0.24f, -0.58f)
        path.cubicTo(-0.2f, -0.72f, -0.04f, -0.7f, 0f, -0.57f)
        path.close()
        paint(canvas, HOOTY_FACE, Color.rgb(150, 100, 52), 0.014f)
        for (x in listOf(-0.105f, 0.105f)) {
            fill.color = Color.rgb(43, 26, 14)
            canvas.drawCircle(x, -0.535f, 0.068f, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(x + 0.022f, -0.56f, 0.024f, fill)
            canvas.drawCircle(x - 0.02f, -0.515f, 0.01f, fill)
        }
        path.reset()
        path.moveTo(-0.032f, -0.47f)
        path.lineTo(0.032f, -0.47f)
        path.lineTo(0f, -0.405f)
        path.close()
        paint(canvas, Color.rgb(236, 160, 58), HOOTY_INK, 0.012f)
        oval(0f, -0.375f, 0.022f, 0.016f) // little open mouth
        paint(canvas, Color.rgb(70, 30, 20), HOOTY_INK, 0.008f)
    }

    private fun drawHoot(canvas: Canvas, pose: Pose) {
        val text = "HOOT HOOT!"
        val tw = bubbleText.measureText(text)
        val pad = charH * 0.06f
        // Towards the middle of the picture, clear of his head.
        val bx = (pose.x + (if (pose.x < width / 2f) charH * 0.55f else -charH * 0.55f - tw)).coerceIn(pad, width - tw - pad)
        val by = (pose.y - charH * (if (pose.upsideDown) -0.2f else 1.1f)).coerceIn(charH * 0.3f, height - charH * 0.3f)
        rect.set(bx - pad, by - bubbleText.textSize - pad * 0.6f, bx + tw + pad, by + pad)
        fill.color = Color.argb(235, 255, 252, 240)
        canvas.drawRoundRect(rect, pad, pad, fill)
        outline(charH * 0.012f, HOOTY_INK)
        canvas.drawRoundRect(rect, pad, pad, ink)
        canvas.drawText(text, bx + tw / 2, by, bubbleText)
    }

    /** 0 -> 1 -> hold -> 0, eased: come out, look around, duck back in. */
    private fun peekCurve(t: Float): Float = when {
        t < 0.25f -> ease(t / 0.25f)
        t < 0.75f -> 1f
        else -> 1f - ease((t - 0.75f) / 0.25f)
    }

    private fun ease(x: Float) = (1 - cos(x * PI).toFloat()) / 2

    private fun darken(color: Int, f: Float) =
        Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    companion object {
        private val LEAF_COLORS = listOf(
            Color.rgb(192, 57, 43), Color.rgb(169, 50, 38), Color.rgb(211, 84, 0), Color.rgb(230, 126, 34),
            Color.rgb(241, 161, 10), Color.rgb(205, 160, 40), Color.rgb(142, 91, 44), Color.rgb(185, 119, 14),
        )
        private val PUMPKIN_GREEN = Color.rgb(78, 168, 83)
        private val PUMPKIN_CREAM = Color.rgb(253, 228, 153)
        private val HOOTY_FACE = Color.rgb(243, 223, 178)
        private val HOOTY_TUBE = Color.rgb(160, 108, 58)
        private val HOOTY_INK = Color.rgb(46, 29, 16)
        private const val HURRY_WALK_MS = 4_500L // a full crossing when hurried off: tour speed
        private const val HURRY_PEEK_MS = 1_000L // ducking back out of a peek
        private const val HURRY_HOOTY_MS = 3_000L // Hooty's head back across the whole frame; HA waits 3 s
    }
}
