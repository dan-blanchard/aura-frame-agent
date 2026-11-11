package net.blanchard.frameagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class GhostStyle(val color: Int = Color.WHITE) {
    CLASSIC,
    BOO,
    BLINKY(Color.rgb(235, 30, 30)),
    PINKY(Color.rgb(255, 170, 225)),
    INKY(Color.rgb(40, 225, 235)),
    CLYDE(Color.rgb(255, 170, 60));

    companion object {
        val PAC_MAN = listOf(BLINKY, PINKY, INKY, CLYDE)

        fun parse(name: String?): GhostStyle? = values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class GhostEdge {
    LEFT, RIGHT, TOP, BOTTOM;

    companion object {
        fun parse(name: String?): GhostEdge? = values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Cobwebs in two corners plus a translucent ghost that drifts across or peeks in
 * from an edge, in a random style: our own ghost, a Boo, or one of the Pac-Man ghosts.
 * Ghosts come only when HA sends them (cmd/ghost peek, or a tour's legs), so every
 * holiday shares one rate set in HA (Dan, 2026-11-10). Spooky mode adds a spider and a stuck fly to each web,
 * and makes half of HA's peeks a fly buzzing through or a spider dropping on its thread
 * instead of a ghost (Dan, 2026-11-10: the old 15-40 s per-frame critter timer was
 * "too busy"). Everything is
 * drawn from paths, no assets. Between visits nothing animates, so the view only redraws
 * while a ghost or a moving critter is on screen.
 */
class HalloweenView(context: Context) : View(context) {

    var spooky = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            critters.clear()
        }

    private val rng = Random(SystemClock.uptimeMillis())

    private val webPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 140
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
    }
    private val webPath = Path()

    private val vignettePaint = Paint()

    private val ghostBody = Path()
    private val booBody = Path()
    private val booArm = Path()
    private val booShade = Path()
    private val booMouth = Path()
    private val booTeeth = Path()
    private val booLines = Path()
    private val booLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BOO_INK
        strokeWidth = 0.055f // in Boo's own units, see buildBoo
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pacBody = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 35) }

    // A soft grey edge so a white ghost still reads over bright photos.
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(60, 60, 80)
        strokeWidth = 3f
    }
    private val ghostBounds = RectF()
    private var ghostW = 0f
    private var ghostH = 0f

    private val passes = mutableListOf<GhostPass>()

    private val spiderLegs = Path()
    private val critterInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CRITTER_INK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // A pale edge so a dark critter still reads over dark photos.
    private val critterHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(170, 235, 235, 245)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val threadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 190
        strokeWidth = 2f
    }
    private val critters = mutableListOf<Critter>()
    private var spiderSize = 0f
    private var flySize = 0f

    // No peeks while HA's tour crosses the wall (see ThanksgivingView.quiet).
    private var quietUntil = 0L

    /** HA is about to send a ghost across the wall: hurry our own ghost or critter off, take no peek for [ms]. */
    fun quiet(ms: Long) {
        val now = SystemClock.uptimeMillis()
        quietUntil = maxOf(quietUntil, now + ms)
        passes.filter { !it.visitor }.forEach { hurryOff(it, now) }
        critters.forEach { hurryOff(it, now) }
        android.util.Log.i("FrameAgent", "ghost: quiet for $ms ms")
        postInvalidateOnAnimation()
    }

    /** Peeks duck back within a second; drifters leave by the nearer side at tour speed. */
    private fun hurryOff(p: GhostPass, now: Long) {
        var t = ((now - p.startMs).toFloat() / p.durationMs).coerceIn(0f, 1f)
        if (p.kind == Kind.DRIFT) {
            if (t < 0.5f) {
                p.from = p.to.also { p.to = p.from }
                p.fromPos = p.toPos.also { p.toPos = p.fromPos }
                p.lookRight = !p.lookRight
                t = 1f - t
            }
            p.durationMs = HURRY_DRIFT_MS
            p.startMs = now - (t * HURRY_DRIFT_MS).toLong()
        } else if (t < 0.75f) {
            val back = 0.75f + 0.25f * (acos(2 * peekCurve(t) - 1) / PI.toFloat())
            p.durationMs = HURRY_PEEK_MS * 4
            p.startMs = now - (back * p.durationMs).toLong()
        }
    }

    /** A spider climbs back up within a second; a fly finishes its run in 1.5 s. */
    private fun hurryOff(c: Critter, now: Long) {
        val t = ((now - c.startMs).toFloat() / c.durationMs).coerceIn(0f, 1f)
        if (c.fly) {
            if (t >= 1f) return
            c.durationMs = (HURRY_FLY_MS / (1f - t)).toLong()
            c.startMs = now - (t * c.durationMs).toLong()
        } else if (t < 0.75f) {
            val back = 0.75f + 0.25f * (acos(2 * peekCurve(t) - 1) / PI.toFloat())
            c.durationMs = HURRY_PEEK_MS * 4
            c.startMs = now - (back * c.durationMs).toLong()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val short = min(w, h).toFloat()
        buildWeb(short * 0.30f)
        vignettePaint.shader = RadialGradient(
            w / 2f, h / 2f, hypot(w.toFloat(), h.toFloat()) / 2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        ghostW = short * 0.14f
        ghostH = ghostW * 1.3f
        buildGhost()
        spiderSize = short * 0.035f
        flySize = short * 0.02f
    }

    override fun onDraw(canvas: Canvas) {
        if (spooky) canvas.drawPaint(vignettePaint)

        // Top-left web, then the same web rotated into the bottom-right corner.
        canvas.drawPath(webPath, webPaint)
        canvas.save()
        canvas.rotate(180f, width / 2f, height / 2f)
        canvas.drawPath(webPath, webPaint)
        canvas.restore()

        val now = SystemClock.uptimeMillis()
        if (spooky) drawCritters(canvas, now)
        passes.toList().forEach { pass ->
            val t = (now - pass.startMs).toFloat() / pass.durationMs
            if (t >= 1f) {
                passes.remove(pass)
            } else {
                drawGhost(canvas, pass, t)
            }
        }
        if (passes.isNotEmpty() || critters.isNotEmpty()) postInvalidateOnAnimation()
    }

    // --- cobwebs -------------------------------------------------------------

    /** A corner web anchored at (0,0): radial spokes joined by sagging rings. */
    private fun buildWeb(radius: Float) {
        webPath.reset()
        val webRng = Random(31) // fixed seed: the web looks the same every boot
        val spokes = 7
        val angles = List(spokes) { i -> (PI / 2 * i / (spokes - 1)).toFloat() }
        val lengths = angles.map { radius * (0.9f + webRng.nextFloat() * 0.25f) }

        angles.forEachIndexed { i, a ->
            webPath.moveTo(0f, 0f)
            webPath.lineTo(cos(a) * lengths[i], sin(a) * lengths[i])
        }

        val rings = 6
        for (r in 1..rings) {
            val frac = r / (rings + 0.6f)
            for (i in 0 until spokes - 1) {
                val r0 = lengths[i] * frac * (0.95f + webRng.nextFloat() * 0.1f)
                val r1 = lengths[i + 1] * frac * (0.95f + webRng.nextFloat() * 0.1f)
                val a0 = angles[i]
                val a1 = angles[i + 1]
                val mid = (a0 + a1) / 2
                val sag = (r0 + r1) / 2 * 0.86f // pull the middle toward the corner
                webPath.moveTo(cos(a0) * r0, sin(a0) * r0)
                webPath.quadTo(cos(mid) * sag, sin(mid) * sag, cos(a1) * r1, sin(a1) * r1)
            }
        }
    }

    // --- ghost ---------------------------------------------------------------

    /** Ghost shape in a ghostW x ghostH box at the origin: domed head, wavy hem. */
    private fun buildGhost() {
        val w = ghostW
        val h = ghostH
        ghostBody.reset()
        ghostBody.moveTo(0f, w / 2)
        ghostBody.arcTo(RectF(0f, 0f, w, w), 180f, 180f)
        ghostBody.lineTo(w, h * 0.92f)
        val scallops = 4
        val step = w / scallops
        for (i in 0 until scallops) {
            val x0 = w - step * i
            val dip = if (i % 2 == 0) h else h * 0.86f
            ghostBody.quadTo(x0 - step / 2, dip, x0 - step, h * 0.92f)
        }
        ghostBody.close()
        ghostPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(255, 245, 245, 255), Color.argb(40, 230, 230, 255),
            Shader.TileMode.CLAMP,
        )
        // Boo is wider than the others.
        ghostBounds.set(-w * 0.2f, -4f, w * 1.2f, h + 4f)
        buildBoo()
        buildPac()
    }

    /**
     * The Super Mario Bros. 3 Boo, facing left. Traced in units where the body's radius
     * is about 1, centred on the origin; drawBoo scales it to the ghost's size.
     */
    private fun buildBoo() {
        booBody.reset()
        booBody.moveTo(0f, -1.02f)
        booBody.cubicTo(0.55f, -1.02f, 1.02f, -0.55f, 1.02f, 0.15f)
        booBody.quadTo(1.02f, 0.3f, 1.05f, 0.34f)
        booBody.quadTo(1.12f, 0.41f, 1.29f, 0.43f) // tail
        booBody.quadTo(1.15f, 0.85f, 0.35f, 1.03f)
        booBody.cubicTo(-0.25f, 1.12f, -0.93f, 0.65f, -0.93f, -0.02f)
        booBody.cubicTo(-0.93f, -0.6f, -0.55f, -1.02f, 0f, -1.02f)
        booBody.close()

        booArm.reset()
        booArm.moveTo(-0.89f, -0.4f)
        booArm.lineTo(-1.17f, -0.33f)
        booArm.quadTo(-1.13f, -0.12f, -0.95f, 0.01f)
        booArm.close()

        booShade.reset()
        booShade.moveTo(0.74f, -0.11f)
        booShade.quadTo(0.92f, 0.25f, 0.72f, 0.62f)
        booShade.quadTo(0.5f, 0.82f, 0.14f, 0.79f)
        booShade.quadTo(0.5f, 0.7f, 0.62f, 0.4f)
        booShade.quadTo(0.75f, 0.15f, 0.74f, -0.11f)
        booShade.close()

        booMouth.reset()
        booMouth.moveTo(-0.71f, -0.18f)
        booMouth.quadTo(-0.35f, -0.2f, -0.01f, -0.31f)
        booMouth.cubicTo(0.05f, 0.1f, -0.02f, 0.55f, -0.17f, 0.74f)
        booMouth.quadTo(-0.3f, 0.82f, -0.42f, 0.66f)
        booMouth.cubicTo(-0.55f, 0.5f, -0.67f, 0.2f, -0.71f, -0.18f)
        booMouth.close()

        booTeeth.reset()
        for (tooth in listOf(
            floatArrayOf(-0.71f, -0.18f, -0.5f, -0.2f, -0.58f, 0.2f),
            floatArrayOf(-0.5f, -0.2f, -0.37f, -0.23f, -0.46f, 0.0f),
            floatArrayOf(-0.37f, -0.23f, -0.24f, -0.26f, -0.33f, 0.03f),
            floatArrayOf(-0.24f, -0.26f, -0.01f, -0.31f, -0.07f, 0.11f),
        )) {
            booTeeth.moveTo(tooth[0], tooth[1])
            booTeeth.lineTo(tooth[2], tooth[3])
            booTeeth.lineTo(tooth[4], tooth[5])
            booTeeth.close()
        }

        // Brows and the curled arm are bare lines.
        booLines.reset()
        booLines.moveTo(-0.52f, -0.73f)
        booLines.quadTo(-0.55f, -0.6f, -0.48f, -0.53f)
        booLines.moveTo(-0.4f, -0.67f)
        booLines.quadTo(-0.42f, -0.57f, -0.34f, -0.51f)
        booLines.moveTo(-0.31f, -0.49f)
        booLines.quadTo(-0.12f, -0.56f, 0.01f, -0.73f)
        booLines.moveTo(0.29f, -0.13f)
        booLines.quadTo(0.47f, -0.36f, 0.78f, -0.37f)
        booLines.quadTo(0.7f, -0.14f, 0.54f, 0.07f)
    }

    /** Dome on top, straight sides, three points along the bottom. */
    private fun buildPac() {
        val w = ghostW
        val h = ghostH
        pacBody.reset()
        pacBody.moveTo(0f, w / 2)
        pacBody.arcTo(RectF(0f, 0f, w, w), 180f, 180f)
        pacBody.lineTo(w, h)
        val points = 3
        val step = w / points
        for (i in 0 until points) {
            val x = w - step * i
            pacBody.lineTo(x - step / 2, h * 0.87f)
            pacBody.lineTo(x - step, h)
        }
        pacBody.close()
    }

    private enum class Kind { DRIFT, PEEK_LEFT, PEEK_RIGHT, PEEK_BOTTOM, PEEK_TOP }

    private class GhostPass(
        val style: GhostStyle,
        val kind: Kind,
        var startMs: Long,
        var durationMs: Long,
        var lookRight: Boolean,
        val anchor: Float = 0f, // peeks: y from the sides, x from the top or bottom (0..1)
        // Drifts run off-screen to off-screen, between positions along two edges.
        var from: GhostEdge = GhostEdge.LEFT,
        var to: GhostEdge = GhostEdge.RIGHT,
        var fromPos: Float = 0.5f,
        var toPos: Float = 0.5f,
        val visitor: Boolean = false, // a tour leg, not a peek
    )

    private fun edgeX(edge: GhostEdge, pos: Float) = when (edge) {
        GhostEdge.LEFT -> -ghostW * 1.2f
        GhostEdge.RIGHT -> width + ghostW * 0.2f
        else -> pos * (width - ghostW)
    }

    private fun edgeY(edge: GhostEdge, pos: Float) = when (edge) {
        GhostEdge.TOP -> -ghostH
        GhostEdge.BOTTOM -> height.toFloat()
        else -> pos * (height - ghostH)
    }

    /** One leg of a tour: enter at one edge, leave at another, on HA's timing. */
    fun visit(style: GhostStyle?, from: GhostEdge, to: GhostEdge, fromPos: Float, toPos: Float, durationMs: Long) {
        passes += GhostPass(
            style = style ?: randomStyle(),
            kind = Kind.DRIFT,
            startMs = SystemClock.uptimeMillis(),
            durationMs = durationMs,
            lookRight = edgeX(to, toPos) >= edgeX(from, fromPos),
            from = from,
            to = to,
            fromPos = fromPos,
            toPos = toPos,
            visitor = true,
        )
        android.util.Log.i("FrameAgent", "ghost: ${passes.last().style} visit $from->$to")
        postInvalidateOnAnimation()
    }

    /**
     * One peek (or a drift across this frame), from HA's cmd/ghost peek or the adb hook.
     * In spooky mode half of them are a critter instead; [kind] "fly", "spider" or "ghost" picks.
     */
    fun summonGhost(style: GhostStyle? = null, kind: String? = null) {
        if (SystemClock.uptimeMillis() < quietUntil) {
            android.util.Log.i("FrameAgent", "ghost: peek ignored, tour on")
            return
        }
        if (passes.any { !it.visitor } || critters.isNotEmpty()) return
        val critter = when (kind?.lowercase()) {
            "fly" -> true
            "spider" -> false
            "ghost" -> null
            else -> if (spooky && style == null && rng.nextBoolean()) rng.nextInt(3) < 2 else null
        }
        if (critter == null || !spooky) startGhost(style) else startCritter(critter)  // critters draw only in spooky
    }

    /** Even odds of each kind of ghost, so the four Pac-Man colours don't dominate. */
    private fun randomStyle(): GhostStyle = when (rng.nextInt(3)) {
        0 -> GhostStyle.CLASSIC
        1 -> GhostStyle.BOO
        else -> GhostStyle.PAC_MAN.random(rng)
    }

    private fun startGhost(style: GhostStyle? = null) {
        val kind = Kind.values()[rng.nextInt(Kind.values().size)]
        val from = GhostEdge.values()[rng.nextInt(GhostEdge.values().size)]
        val to = GhostEdge.values().filter { it != from }.random(rng)
        val fromPos = 0.1f + rng.nextFloat() * 0.8f
        val toPos = 0.1f + rng.nextFloat() * 0.8f
        val pass = GhostPass(
            style = style ?: randomStyle(),
            kind = kind,
            startMs = SystemClock.uptimeMillis(),
            durationMs = if (kind == Kind.DRIFT) 16_000L else 9_000L,
            lookRight = when (kind) {
                Kind.DRIFT -> edgeX(to, toPos) >= edgeX(from, fromPos)
                Kind.PEEK_LEFT -> true
                Kind.PEEK_RIGHT -> false
                else -> rng.nextBoolean()
            },
            anchor = 0.1f + rng.nextFloat() * 0.8f,
            from = from,
            to = to,
            fromPos = fromPos,
            toPos = toPos,
        )
        passes += pass
        android.util.Log.i("FrameAgent", "ghost: ${pass.style} ${pass.kind} $from->$to anchor=${pass.anchor}")
        postInvalidateOnAnimation()
    }

    private fun drawGhost(canvas: Canvas, pass: GhostPass, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bob = sin(t * 2 * PI * 3).toFloat() * ghostW * 0.08f
        var x: Float
        var y: Float
        var tilt = 0f
        when (pass.kind) {
            Kind.DRIFT -> {
                val fromX = edgeX(pass.from, pass.fromPos)
                val fromY = edgeY(pass.from, pass.fromPos)
                x = fromX + t * (edgeX(pass.to, pass.toPos) - fromX)
                y = fromY + t * (edgeY(pass.to, pass.toPos) - fromY) + bob * 2
                tilt = if (pass.lookRight) 8f else -8f
            }
            Kind.PEEK_LEFT, Kind.PEEK_RIGHT -> {
                val out = peekCurve(t) * ghostW * 0.55f
                x = if (pass.kind == Kind.PEEK_LEFT) -ghostW + out else w - out
                y = pass.anchor * (h - ghostH) + bob
                tilt = if (pass.kind == Kind.PEEK_LEFT) 14f else -14f
            }
            Kind.PEEK_BOTTOM -> {
                x = pass.anchor * (w - ghostW)
                y = h - peekCurve(t) * ghostH * 0.6f + bob
            }
            Kind.PEEK_TOP -> {
                x = pass.anchor * (w - ghostW)
                y = -ghostH + peekCurve(t) * ghostH * 0.6f + bob
                tilt = 180f // hanging down from the top
            }
        }

        // Drawn opaque into a layer, then the whole ghost is faded at once, so
        // the eyes and outline don't show through the body.
        val alpha = (GHOST_OPACITY * 255).toInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(tilt, ghostW / 2, ghostH / 2)
        canvas.saveLayerAlpha(ghostBounds, alpha)
        when (pass.style) {
            GhostStyle.CLASSIC -> drawClassic(canvas, pass.lookRight)
            GhostStyle.BOO -> drawBoo(canvas, pass.lookRight)
            else -> drawPac(canvas, pass.style.color, pass.lookRight)
        }
        canvas.restore()
        canvas.restore()
    }

    private fun drawClassic(canvas: Canvas, lookRight: Boolean) {
        canvas.drawPath(ghostBody, ghostPaint)
        canvas.drawPath(ghostBody, outlinePaint)
        val eyeY = ghostW * 0.45f
        val eyeR = ghostW * 0.075f
        val lookShift = if (lookRight) eyeR * 0.5f else -eyeR * 0.5f
        canvas.drawOval(eyeRect(ghostW * 0.33f + lookShift, eyeY, eyeR), eyePaint)
        canvas.drawOval(eyeRect(ghostW * 0.62f + lookShift, eyeY, eyeR), eyePaint)
    }

    private fun drawBoo(canvas: Canvas, lookRight: Boolean) {
        val scale = ghostW * 0.5f
        canvas.save()
        canvas.translate(ghostW / 2, ghostH / 2)
        canvas.scale(if (lookRight) -scale else scale, scale)

        fill.color = Color.rgb(250, 250, 252)
        canvas.drawPath(booArm, fill)
        canvas.drawPath(booArm, booLine)
        canvas.drawPath(booBody, fill)
        fill.color = Color.rgb(205, 238, 240)
        canvas.drawPath(booShade, fill)
        canvas.drawPath(booBody, booLine)

        fill.color = Color.rgb(226, 30, 90)
        canvas.drawPath(booMouth, fill)
        fill.color = Color.WHITE
        canvas.drawPath(booTeeth, fill)
        canvas.drawPath(booTeeth, booLine)
        canvas.drawPath(booMouth, booLine)

        fill.color = BOO_INK
        canvas.drawCircle(-0.5f, -0.44f, 0.055f, fill)
        canvas.drawCircle(-0.27f, -0.43f, 0.055f, fill)
        canvas.drawPath(booLines, booLine)
        canvas.restore()
    }

    private fun drawPac(canvas: Canvas, color: Int, lookRight: Boolean) {
        fill.color = color
        canvas.drawPath(pacBody, fill)
        val eyeY = ghostW * 0.42f
        val shift = if (lookRight) ghostW * 0.05f else -ghostW * 0.05f
        for (x in listOf(ghostW * 0.3f, ghostW * 0.7f)) {
            fill.color = Color.WHITE
            eyeTmp.set(x - ghostW * 0.14f, eyeY - ghostW * 0.18f, x + ghostW * 0.14f, eyeY + ghostW * 0.18f)
            canvas.drawOval(eyeTmp, fill)
            fill.color = Color.rgb(30, 50, 200)
            canvas.drawCircle(x + shift, eyeY + ghostW * 0.05f, ghostW * 0.08f, fill)
        }
    }

    // --- spiders and flies ---------------------------------------------------

    private class Critter(
        val fly: Boolean,
        var startMs: Long,
        var durationMs: Long,
        val fromX: Float,
        val fromY: Float,
        val toX: Float = fromX, // flies only
        val toY: Float = fromY,
        val drop: Float = 0f, // spiders only: how far down the thread goes
        val fromWeb: Boolean = false, // the top-left web's own spider, so it isn't drawn at home
    )

    private fun startCritter(fly: Boolean) {
        val short = min(width, height).toFloat()
        val now = SystemClock.uptimeMillis()
        critters += if (fly) {
            val from = GhostEdge.values()[rng.nextInt(GhostEdge.values().size)]
            val to = GhostEdge.values().filter { it != from }.random(rng)
            Critter(
                fly = true,
                startMs = now,
                durationMs = rng.nextLong(7_000, 12_000),
                fromX = critterEdgeX(from),
                fromY = critterEdgeY(from),
                toX = critterEdgeX(to),
                toY = critterEdgeY(to),
            )
        } else {
            val fromWeb = critters.none { it.fromWeb } && rng.nextBoolean()
            Critter(
                fly = false,
                startMs = now,
                durationMs = rng.nextLong(14_000, 20_000),
                fromX = if (fromWeb) short * WEB_SPIDER else width * (0.15f + rng.nextFloat() * 0.7f),
                fromY = if (fromWeb) short * WEB_SPIDER else -spiderSize * 2,
                drop = short * (0.3f + rng.nextFloat() * 0.3f),
                fromWeb = fromWeb,
            )
        }
        android.util.Log.i("FrameAgent", "critter: ${if (critters.last().fly) "fly" else "spider"}")
        postInvalidateOnAnimation()
    }

    private fun critterEdgeX(edge: GhostEdge) = when (edge) {
        GhostEdge.LEFT -> -flySize * 3
        GhostEdge.RIGHT -> width + flySize * 3
        else -> width * (0.1f + rng.nextFloat() * 0.8f)
    }

    private fun critterEdgeY(edge: GhostEdge) = when (edge) {
        GhostEdge.TOP -> -flySize * 3
        GhostEdge.BOTTOM -> height + flySize * 3
        else -> height * (0.1f + rng.nextFloat() * 0.8f)
    }

    private fun drawCritters(canvas: Canvas, now: Long) {
        val short = min(width, height).toFloat()
        if (critters.none { it.fromWeb }) drawSpider(canvas, short * WEB_SPIDER, short * WEB_SPIDER, 0f, 1f)
        drawSpider(canvas, width - short * WEB_SPIDER, height - short * WEB_SPIDER, 0f, -1f)
        drawFly(canvas, short * 0.22f, short * 0.08f, 35f, 0f)
        drawFly(canvas, width - short * 0.08f, height - short * 0.21f, 200f, 0f)

        critters.toList().forEach { c ->
            val t = (now - c.startMs).toFloat() / c.durationMs
            when {
                t >= 1f -> critters.remove(c)
                c.fly -> {
                    val (x, y) = flyAt(c, t)
                    val (nextX, nextY) = flyAt(c, t + 0.004f)
                    val heading = Math.toDegrees(atan2(nextY - y, nextX - x).toDouble()).toFloat()
                    drawFly(canvas, x, y, heading, now / 40f)
                }
                else -> {
                    val y = c.fromY + c.drop * peekCurve(t)
                    val x = c.fromX + sin(now / 900.0).toFloat() * spiderSize * 0.3f * peekCurve(t)
                    canvas.drawLine(c.fromX, c.fromY, x, y - spiderSize * 0.4f, threadPaint)
                    drawSpider(canvas, x, y, sin(now / 180.0).toFloat(), if (x < width / 2) 1f else -1f)
                }
            }
        }
    }

    /** A straight run with a loopy wobble that fades out at both edges. */
    private fun flyAt(c: Critter, t: Float): Pair<Float, Float> {
        val wobble = sin(t * PI).toFloat() * min(width, height) * 0.07f
        val side = sin(t * 2 * PI * 5).toFloat() * wobble
        val along = cos(t * 2 * PI * 8).toFloat() * wobble * 0.5f
        return Pair(c.fromX + t * (c.toX - c.fromX) + side, c.fromY + t * (c.toY - c.fromY) + along)
    }

    /** Round and big-eyed, in units where the body's radius is about 0.5. */
    private fun drawSpider(canvas: Canvas, x: Float, y: Float, wiggle: Float, lookX: Float) {
        spiderLegs.reset()
        for (side in listOf(-1f, 1f)) {
            for (i in 0..3) {
                val rootY = -0.15f + i * 0.17f
                val kick = if ((i % 2 == 0) == (side > 0)) wiggle * 0.1f else -wiggle * 0.1f
                spiderLegs.moveTo(side * 0.35f, rootY)
                spiderLegs.lineTo(side * (0.8f + i * 0.04f), rootY - 0.38f + i * 0.13f + kick)
                spiderLegs.lineTo(side * (1.08f + i * 0.02f), rootY + 0.08f + i * 0.2f + kick)
            }
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(spiderSize, spiderSize)
        critterHalo.strokeWidth = 0.17f
        critterInk.strokeWidth = 0.09f
        canvas.drawPath(spiderLegs, critterHalo)
        canvas.drawCircle(0f, 0.05f, 0.54f, critterHalo)
        canvas.drawPath(spiderLegs, critterInk)
        fill.color = CRITTER_INK
        canvas.drawCircle(0f, 0.05f, 0.52f, fill)
        for (side in listOf(-1f, 1f)) {
            fill.color = Color.WHITE
            canvas.drawCircle(side * 0.2f, -0.08f, 0.18f, fill)
            fill.color = Color.BLACK
            canvas.drawCircle(side * 0.2f + lookX * 0.06f, -0.05f, 0.09f, fill)
        }
        critterInk.color = Color.WHITE
        critterInk.strokeWidth = 0.05f
        canvas.drawLine(-0.11f, 0.26f, -0.06f, 0.37f, critterInk) // fangs
        canvas.drawLine(0.11f, 0.26f, 0.06f, 0.37f, critterInk)
        critterInk.color = CRITTER_INK
        canvas.restore()
    }

    /** Seen from above, nose along +x. flap 0 leaves the wings still, as in a web. */
    private fun drawFly(canvas: Canvas, x: Float, y: Float, heading: Float, flap: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(heading)
        canvas.scale(flySize, flySize)
        val spread = if (flap == 0f) 25f else 25f + sin(flap).toFloat() * 22f
        fill.color = FLY_WING
        for (side in listOf(-1f, 1f)) {
            canvas.save()
            canvas.rotate(side * spread)
            eyeTmp.set(-1.45f, -0.3f, 0.05f, 0.3f)
            canvas.translate(0f, side * 0.25f)
            canvas.drawOval(eyeTmp, fill)
            canvas.restore()
        }
        critterHalo.strokeWidth = 0.22f
        eyeTmp.set(-0.8f, -0.42f, 0.5f, 0.42f)
        canvas.drawOval(eyeTmp, critterHalo)
        canvas.drawCircle(0.62f, 0f, 0.36f, critterHalo)
        fill.color = CRITTER_INK
        canvas.drawOval(eyeTmp, fill)
        canvas.drawCircle(0.62f, 0f, 0.34f, fill)
        for (side in listOf(-1f, 1f)) {
            fill.color = Color.rgb(225, 45, 45)
            canvas.drawCircle(0.72f, side * 0.24f, 0.2f, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(0.78f, side * 0.2f, 0.06f, fill)
        }
        canvas.restore()
    }

    private val eyeTmp = RectF()
    private fun eyeRect(cx: Float, cy: Float, r: Float) =
        eyeTmp.apply { set(cx - r, cy - r * 1.4f, cx + r, cy + r * 1.4f) }

    /** 0 -> 1 -> hold -> 0, eased: slide in, look around, duck back out. */
    private fun peekCurve(t: Float): Float = when {
        t < 0.25f -> ease(t / 0.25f)
        t < 0.75f -> 1f
        else -> 1f - ease((t - 0.75f) / 0.25f)
    }

    private fun ease(x: Float) = (1 - cos(x * PI).toFloat()) / 2

    companion object {
        private const val GHOST_OPACITY = 0.45f
        private const val HURRY_DRIFT_MS = 4_500L // a full crossing when hurried off: tour speed
        private const val HURRY_PEEK_MS = 1_000L // ducking back out of a peek
        private const val HURRY_FLY_MS = 1_500L // the rest of a fly's run when a tour starts
        private val BOO_INK = Color.rgb(30, 32, 48)
        private val CRITTER_INK = Color.rgb(28, 22, 38)
        private val FLY_WING = Color.argb(190, 205, 230, 245)
        private const val WEB_SPIDER = 0.13f // how far a spider sits from its web's corner, in short sides
    }
}
