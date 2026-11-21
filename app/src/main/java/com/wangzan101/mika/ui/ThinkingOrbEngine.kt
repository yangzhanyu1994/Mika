package com.wangzan101.mika.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry port of https://github.com/Jakubantalik/thinking-orbs (MIT, © 2026 Jakub Antalik).
 *
 * Nine modes, two density presets. Output is a z-sorted list of dots (and lines for `web`).
 * The painter adds Mika's cyan lighting; this file keeps the upstream motion.
 * No smiley, no filled disc: every mark is a dot or a thin edge.
 */
internal enum class ThinkingOrbMode {
    Orbits, Globe, Rubik, Wave, Web, Braid, Ribbon, Ring, Morph,
}

internal enum class ThinkingOrbSize { Large, Small }

internal data class OrbDot(
    val x: Float, val y: Float, val z: Float, val r: Float, val white: Float, val a: Float = 1f,
)

internal data class OrbLine(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float, val white: Float, val a: Float, val w: Float,
)

internal data class OrbFrame(val dots: List<OrbDot>, val lines: List<OrbLine>)

internal class ModeOpts(values: Map<String, Float> = emptyMap()) {
    private val values = HashMap(values)
    operator fun get(key: String): Float? = values[key]
    fun set(key: String, value: Float) { values[key] = value }
    fun copy(): ModeOpts = ModeOpts(values)
}

internal object ThinkingOrbEngine {
    private val COUNT_PAIRS = listOf("latRings" to "lonDensity", "rings" to "lonDensity", "lanes" to "segs")
    private val COUNT_KEYS = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
    private val RADIUS_KEYS = listOf("rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth")

    private fun base(vararg pairs: Pair<String, Float>) = ModeOpts(mapOf(*pairs))

    private val BASE = mapOf(
        ThinkingOrbMode.Globe to base(
            "latRings" to 17f, "lonDensity" to 44f, "rBase" to 0.6f, "rDepth" to 1.7f, "rBoost" to 1f,
            "inkFar" to 0.62f, "inkSpan" to 0.54f, "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Orbits to base(
            "orbitN" to 12f, "ghostN" to 40f, "ghostR" to 0.9f, "ghostA" to 0.5f, "particles" to 3f,
            "partR" to 1.2f, "partRDepth" to 1.6f, "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Rubik to base(
            "latRings" to 15f, "lonDensity" to 40f, "moveCount" to 14f, "rBase" to 0.6f, "rDepth" to 1.7f,
            "rActive" to 0.3f, "inkFar" to 0.62f, "inkSpan" to 0.54f, "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Wave to base(
            "rings" to 15f, "lonDensity" to 40f, "rBase" to 0.6f, "rDepth" to 1.7f, "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Web to base(
            "nodeN" to 30f, "thr" to 0.72f, "signals" to 5f, "nodeR" to 1.4f, "nodeRDepth" to 1.8f,
            "lineW" to 0.8f, "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Braid to base(
            "strandN" to 52f, "turns" to 3f, "ghostN" to 150f, "rBase" to 1.2f, "rDepth" to 1.8f,
            "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Ribbon to base(
            "lanes" to 5f, "segs" to 88f, "ghostN" to 150f, "rBase" to 1.1f, "rDepth" to 1.7f,
            "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Ring to base(
            "lanes" to 5f, "segs" to 88f, "ghostN" to 0f, "faceOn" to 1f, "rBase" to 1.1f, "rDepth" to 1.7f,
            "rsPow" to 0.6f, "rMin" to 0.3f,
        ),
        ThinkingOrbMode.Morph to base("rDot" to 0.021f, "iconD" to 1f, "rMin" to 0.25f),
    )

    private data class Preset(val speed: Float, val count: Float, val size: Float, val extra: Map<String, Float> = emptyMap())

    private val PRESETS: Map<ThinkingOrbMode, Map<ThinkingOrbSize, Preset>> = mapOf(
        ThinkingOrbMode.Orbits to mapOf(
            ThinkingOrbSize.Large to Preset(1.885f, 1f, 1f),
            ThinkingOrbSize.Small to Preset(3.9f, 0.238f, 2.4f),
        ),
        ThinkingOrbMode.Globe to mapOf(
            ThinkingOrbSize.Large to Preset(2.015f, 0.42f, 1.15f, mapOf("scanMul" to 4.08f, "dimBase" to 0.45f)),
            ThinkingOrbSize.Small to Preset(2.665f, 0.105f, 1.75f, mapOf("scanMul" to 4.335f, "dimBase" to 0.45f)),
        ),
        ThinkingOrbMode.Rubik to mapOf(
            ThinkingOrbSize.Large to Preset(1.82f, 0.35f, 1.05f),
            ThinkingOrbSize.Small to Preset(1.95f, 0.088f, 1.9f),
        ),
        ThinkingOrbMode.Wave to mapOf(
            ThinkingOrbSize.Large to Preset(4.388f, 0.341f, 1f),
            ThinkingOrbSize.Small to Preset(3.998f, 0.105f, 1.6f),
        ),
        ThinkingOrbMode.Web to mapOf(
            ThinkingOrbSize.Large to Preset(3.315f, 1.35f, 0.95f),
            ThinkingOrbSize.Small to Preset(6.63f, 0.25f, 1.52f),
        ),
        ThinkingOrbMode.Braid to mapOf(
            ThinkingOrbSize.Large to Preset(1.625f, 0.5f, 1f),
            ThinkingOrbSize.Small to Preset(2.75f, 0.1125f, 1.36f),
        ),
        ThinkingOrbMode.Ribbon to mapOf(
            ThinkingOrbSize.Large to Preset(2.34f, 0.25f, 0.85f, mapOf("spin" to 0f, "bandMul" to 3.9f, "wobMul" to 1f)),
            ThinkingOrbSize.Small to Preset(3.12f, 0.051f, 1.073f, mapOf("spin" to 0f, "bandMul" to 4.94f, "wobMul" to 1f)),
        ),
        ThinkingOrbMode.Ring to mapOf(
            ThinkingOrbSize.Large to Preset(3.24f, 0.25f, 0.956f, mapOf("spin" to 0f, "bandMul" to 3.627f, "wobMul" to 0.368f)),
            ThinkingOrbSize.Small to Preset(3.78f, 0.028f, 1.622f, mapOf("spin" to 0f, "bandMul" to 3.968f, "wobMul" to 0.565f)),
        ),
        ThinkingOrbMode.Morph to mapOf(
            ThinkingOrbSize.Large to Preset(2.405f, 0.702f, 0.395f, mapOf("spread" to 1.45f)),
            ThinkingOrbSize.Small to Preset(2.08f, 0.53f, 1.011f, mapOf("spread" to 1.45f)),
        ),
    )

    fun speed(mode: ThinkingOrbMode, size: ThinkingOrbSize): Float = PRESETS.getValue(mode).getValue(size).speed

    fun opts(mode: ThinkingOrbMode, size: ThinkingOrbSize): ModeOpts {
        val preset = PRESETS.getValue(mode).getValue(size)
        var out = scaleCounts(BASE.getValue(mode).copy(), preset.count)
        out = scaleRadii(out, preset.size)
        preset.extra.forEach { (k, v) -> out.set(k, v) }
        return out
    }

    fun frame(mode: ThinkingOrbMode, sizePx: Float, t: Float, preset: ThinkingOrbSize): OrbFrame {
        val o = opts(mode, preset)
        return when (mode) {
            ThinkingOrbMode.Orbits -> frameOrbits(sizePx, t, o)
            ThinkingOrbMode.Globe -> frameGlobe(sizePx, t, o)
            ThinkingOrbMode.Rubik -> frameRubik(sizePx, t, o)
            ThinkingOrbMode.Wave -> frameWave(sizePx, t, o)
            ThinkingOrbMode.Web -> frameWeb(sizePx, t, o)
            ThinkingOrbMode.Braid -> frameBraid(sizePx, t, o)
            ThinkingOrbMode.Ribbon, ThinkingOrbMode.Ring -> frameRibbon(sizePx, t, o)
            ThinkingOrbMode.Morph -> frameMorph(sizePx, t, o)
        }
    }

    /** Wide cyan lobe for the call page. `white` is paper ink (0 = darkest). Dark backgrounds invert it. */
    fun callLight(white: Float): Float = (1f - white.coerceIn(0f, 1f)).pow(0.62f)

    private fun scaleCounts(opts: ModeOpts, scale: Float): ModeOpts {
        if (scale == 1f) return opts
        val done = HashSet<String>()
        val rt = sqrt(scale)
        for ((a, b) in COUNT_PAIRS) {
            val va = opts[a]
            val vb = opts[b]
            if (va != null && vb != null && a !in done && b !in done) {
                opts.set(a, max(2f, (va * rt).roundToInt().toFloat()))
                opts.set(b, max(2f, (vb * rt).roundToInt().toFloat()))
                done += a
                done += b
            }
        }
        for (k in COUNT_KEYS) {
            val v = opts[k]
            if (v != null && v != 0f && k !in done) opts.set(k, max(1f, (v * scale).roundToInt().toFloat()))
        }
        opts["iconD"]?.let { opts.set("iconD", max(0.02f, it * scale)) }
        return opts
    }

    private fun scaleRadii(opts: ModeOpts, scale: Float): ModeOpts {
        if (scale == 1f) return opts
        for (k in RADIUS_KEYS) opts[k]?.let { opts.set(k, it * scale) }
        opts.set("rSizeMul", (opts["rSizeMul"] ?: 1f) * scale)
        return opts
    }

    private fun hashD(a: Float, b: Float): Float {
        val h = sin(a * 12.9898f + b * 78.233f) * 43758.5453f
        return h - floor(h)
    }

    private fun frac(x: Float) = x - floor(x)
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun radiusScale(size: Float, pow: Float) = (size / 300f).pow(pow)
    private fun angleDelta(a: Float, b: Float) = atan2(sin(a - b), cos(a - b))

    private fun fibDir(i: Int, n: Int): FloatArray {
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        val y = 1f - (2f * (i + 0.5f)) / n
        val rad = sqrt(max(0f, 1f - y * y))
        val a = i * golden
        return floatArrayOf(rad * cos(a), y, rad * sin(a))
    }

    private fun vnoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        var fx = x - xi
        var fy = y - yi
        fx = fx * fx * (3f - 2f * fx)
        fy = fy * fy * (3f - 2f * fy)
        val a = hashD(xi.toFloat(), yi.toFloat())
        val b = hashD((xi + 1).toFloat(), yi.toFloat())
        val c = hashD(xi.toFloat(), (yi + 1).toFloat())
        val d = hashD((xi + 1).toFloat(), (yi + 1).toFloat())
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }

    private class Proj(yaw: Float, tilt: Float, val cx: Float, val cy: Float, val scale: Float) {
        private val st = sin(tilt)
        private val ct = cos(tilt)
        private val sy = sin(yaw)
        private val cyw = cos(yaw)
        fun apply(x: Float, y: Float, z: Float): FloatArray {
            val x1 = x * cyw + z * sy
            val z1 = -x * sy + z * cyw
            val y1 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            return floatArrayOf(cx + x1 * scale, cy - y1 * scale, z2)
        }
    }

    private fun finalize(dots: MutableList<OrbDot>, lines: List<OrbLine>, rMin: Float): OrbFrame {
        val visible = ArrayList<OrbDot>(dots.size)
        for (d in dots) {
            if (d.a < 0.02f) continue
            visible += if (d.r < rMin) d.copy(r = rMin) else d
        }
        visible.sortBy { it.z }
        return OrbFrame(visible, lines.filter { it.a >= 0.02f })
    }

    private fun frameOrbits(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val cx = size / 2f
        val cy = size / 2f
        val r = (size / 2f) * 0.82f
        val pt = Proj(t * 0.12f, 0.3f, cx, cy, 1f)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6f)
        val dots = ArrayList<OrbDot>()
        val orbitN = (o["orbitN"] ?: 12f).toInt()
        val ghostN = (o["ghostN"] ?: 40f).toInt()
        val particles = (o["particles"] ?: 3f).toInt()
        for (orb in 0 until orbitN) {
            val h1 = hashD(orb.toFloat(), 1.7f)
            val h2 = hashD(orb.toFloat(), 5.2f)
            val h3 = hashD(orb.toFloat(), 8.9f)
            val ro = r * (0.45f + 0.52f * h1)
            val th = h1 * 2f * PI.toFloat()
            val phi = acos((2f * h2 - 1f).coerceIn(-1f, 1f))
            val nx = sin(phi) * cos(th)
            val ny = cos(phi)
            val nz = sin(phi) * sin(th)
            var ux = -ny
            var uy = nx
            val uz = 0f
            val ul = max(1e-6f, sqrt(ux * ux + uy * uy))
            ux /= ul
            uy /= ul
            val vx = ny * uz - nz * uy
            val vy = nz * ux - nx * uz
            val vz = nx * uy - ny * ux
            val speed = (0.25f + 0.55f * h3) * if (h3 > 0.5f) 1f else -1f
            for (k in 0 until ghostN) {
                val a = (k.toFloat() / ghostN) * 2f * PI.toFloat()
                val p = pt.apply((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                val depth = (p[2] / ro + 1f) / 2f
                dots += OrbDot(p[0], p[1], p[2], (o["ghostR"] ?: 0.9f) * rs, 0.72f, (o["ghostA"] ?: 0.5f) * (0.4f + 0.6f * depth))
            }
            for (m in 0 until particles) {
                val a = t * speed + (m.toFloat() / particles) * 2f * PI.toFloat() + h2 * 6f
                val p = pt.apply((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                val depth = (p[2] / ro + 1f) / 2f
                dots += OrbDot(p[0], p[1], p[2], ((o["partR"] ?: 1.2f) + (o["partRDepth"] ?: 1.6f) * depth) * rs, 0.3f - 0.22f * depth)
            }
        }
        return finalize(dots, emptyList(), o["rMin"] ?: 0.3f)
    }

    private fun frameGlobe(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val spin = 0.5f
        val radius = (size / 2f) * 0.82f
        val tilt = 0.4f + 0.06f * sin(t * 0.35f)
        val pt = Proj(t * spin, tilt, size / 2f, size / 2f, radius)
        val scan = t * (spin + (1.7f - spin) * (o["scanMul"] ?: 1f))
        val rs = radiusScale(size, o["rsPow"] ?: 0.6f)
        val dimBase = o["dimBase"] ?: 1f
        val dots = ArrayList<OrbDot>()
        val latRings = (o["latRings"] ?: 17f).toInt()
        val lonDensity = (o["lonDensity"] ?: 44f).toInt()
        for (li in 0..latRings) {
            val lat = -PI.toFloat() / 2f + (li.toFloat() / latRings) * PI.toFloat()
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = (lj.toFloat() / lonCount) * 2f * PI.toFloat()
                val p = pt.apply(cosLat * cos(lon), sinLat, cosLat * sin(lon))
                val depth = (p[2] + 1f) / 2f
                val d = angleDelta(lon + t * spin, scan)
                val boost = exp(-(d * d) / 0.18f) * max(0f, p[2])
                dots += OrbDot(
                    p[0], p[1], p[2],
                    ((o["rBase"] ?: 0.6f) + (o["rDepth"] ?: 1.7f) * depth + (o["rBoost"] ?: 1f) * boost) * rs,
                    (o["inkFar"] ?: 0.62f) - (o["inkSpan"] ?: 0.54f) * depth,
                    dimBase + (1f - dimBase) * min(1f, boost),
                )
            }
        }
        return finalize(dots, emptyList(), o["rMin"] ?: 0.3f)
    }

    private data class Move(val axis: Int, val lo: Float, val hi: Float, val ang: Float)

    private fun makeMoves(count: Int): List<Move> = List(count) { i ->
        val axis = min(2, floor(hashD(i.toFloat(), 2.3f) * 3f).toInt())
        val lo = -1f + 0.5f * min(3, floor(hashD(i.toFloat(), 5.9f) * 4f).toInt())
        val dir = if (hashD(i.toFloat(), 7.7f) < 0.5f) 1f else -1f
        Move(axis, lo, lo + 0.5f, dir * PI.toFloat() / 2f)
    }

    private fun solveCycle(time: Float, count: Int, slotDur: Float, rest: Float): Pair<FloatArray, Int> {
        val cyc = 2f * count * slotDur + rest
        val tc = time % cyc
        val amount = FloatArray(count)
        var active = -1
        if (tc < 2f * count * slotDur) {
            val slot = floor(tc / slotDur).toInt()
            val p = (tc - slot * slotDur) / slotDur
            val cl = min(1f, p / 0.7f)
            val ep = 1f - (1f - cl).pow(3f)
            if (slot < count) {
                for (i in 0 until slot) amount[i] = 1f
                amount[slot] = ep
                active = slot
            } else {
                val u = 2 * count - 1 - slot
                for (i in 0 until u) amount[i] = 1f
                amount[u] = 1f - ep
                active = u
            }
        }
        return amount to active
    }

    private fun applyMoves(pt: FloatArray, moves: List<Move>, amount: FloatArray, active: Int): Pair<FloatArray, Boolean> {
        var x = pt[0]; var y = pt[1]; var z = pt[2]
        var inActive = false
        for (i in moves.indices) {
            if (amount[i] <= 0f) continue
            val mv = moves[i]
            val coord = when (mv.axis) { 0 -> x; 1 -> y; else -> z }
            if (coord < mv.lo || coord >= mv.hi) continue
            if (i == active) inActive = true
            val a = mv.ang * amount[i]
            val ca = cos(a); val sa = sin(a)
            when (mv.axis) {
                0 -> { val y2 = y * ca - z * sa; z = y * sa + z * ca; y = y2 }
                1 -> { val x2 = x * ca + z * sa; z = -x * sa + z * ca; x = x2 }
                else -> { val x2 = x * ca - y * sa; y = x * sa + y * ca; x = x2 }
            }
        }
        return floatArrayOf(x, y, z) to inActive
    }

    private fun frameRubik(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val r = (size / 2f) * 0.82f
        val pt = Proj(t * 0.55f, 0.35f + 0.1f * sin(t * 0.9f), size / 2f, size / 2f, r)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6f)
        val moveCount = (o["moveCount"] ?: 14f).toInt()
        val moves = makeMoves(moveCount)
        val (amount, active) = solveCycle(t, moveCount, 0.42f, 1.2f)
        val dots = ArrayList<OrbDot>()
        val latRings = (o["latRings"] ?: 15f).toInt()
        val lonDensity = (o["lonDensity"] ?: 40f).toInt()
        for (li in 0..latRings) {
            val lat = -PI.toFloat() / 2f + (li.toFloat() / latRings) * PI.toFloat()
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = (lj.toFloat() / lonCount) * 2f * PI.toFloat()
                val (xyz, inActive) = applyMoves(floatArrayOf(cosLat * cos(lon), sinLat, cosLat * sin(lon)), moves, amount, active)
                val p = pt.apply(xyz[0], xyz[1], xyz[2])
                val depth = (p[2] + 1f) / 2f
                dots += OrbDot(
                    p[0], p[1], p[2],
                    ((o["rBase"] ?: 0.6f) + (o["rDepth"] ?: 1.7f) * depth + if (inActive) (o["rActive"] ?: 0.3f) else 0f) * rs,
                    (o["inkFar"] ?: 0.62f) - (o["inkSpan"] ?: 0.54f) * depth - if (inActive) 0.14f else 0f,
                )
            }
        }
        return finalize(dots, emptyList(), o["rMin"] ?: 0.3f)
    }

    private fun frameWave(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val r = (size / 2f) * 0.874f
        val pt = Proj(t * 0.18f, 0.38f, size / 2f, size / 2f, 1f)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6f)
        val dots = ArrayList<OrbDot>()
        val rings = (o["rings"] ?: 15f).toInt()
        val lonDensity = (o["lonDensity"] ?: 40f).toInt()
        for (ri in 0..rings) {
            val lat = -PI.toFloat() / 2f + (ri.toFloat() / rings) * PI.toFloat()
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val w = 0.62f * sin(t * 2.1f - ri * 0.52f) + 0.38f * sin(t * 1.27f + ri * 0.83f)
            val rr = r * (0.88f + 0.105f * w)
            val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = (lj.toFloat() / lonCount) * 2f * PI.toFloat()
                val p = pt.apply(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
                val depth = (p[2] / r + 1f) / 2f
                val crest = max(0f, w)
                dots += OrbDot(
                    p[0], p[1], p[2],
                    ((o["rBase"] ?: 0.6f) + (o["rDepth"] ?: 1.7f) * depth) * (1f + 0.4f * crest) * rs,
                    0.66f - 0.56f * depth - 0.1f * crest,
                )
            }
        }
        return finalize(dots, emptyList(), o["rMin"] ?: 0.3f)
    }

    private fun frameWeb(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val r = (size / 2f) * 0.8f * (o["spread"] ?: 1f)
        val pt = Proj(t * 0.12f, 0.32f, size / 2f, size / 2f, r)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6f)
        val nodeN = (o["nodeN"] ?: 30f).toInt()
        val thr = o["thr"] ?: 0.72f
        val nodeR = o["nodeR"] ?: 1.4f
        val nodeRDepth = o["nodeRDepth"] ?: 1.8f
        val nodes = Array(nodeN) { i ->
            val d = fibDir(i, nodeN)
            val x = d[0] + 0.3f * (vnoise(i * 0.31f + 9f, t * 0.24f) - 0.5f) * 2f
            val y = d[1] + 0.3f * (vnoise(i * 0.53f + 27f, t * 0.21f) - 0.5f) * 2f
            val z = d[2] + 0.3f * (vnoise(i * 0.77f + 55f, t * 0.27f) - 0.5f) * 2f
            val l = sqrt(x * x + y * y + z * z)
            floatArrayOf(x / l, y / l, z / l)
        }
        val lines = ArrayList<OrbLine>()
        val dots = ArrayList<OrbDot>()
        for (i in 0 until nodeN) for (j in i + 1 until nodeN) {
            val dx = nodes[i][0] - nodes[j][0]
            val dy = nodes[i][1] - nodes[j][1]
            val dz = nodes[i][2] - nodes[j][2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist >= thr) continue
            val a = pt.apply(nodes[i][0], nodes[i][1], nodes[i][2])
            val b = pt.apply(nodes[j][0], nodes[j][1], nodes[j][2])
            val depth = ((a[2] + b[2]) / 2f + 1f) / 2f
            lines += OrbLine(a[0], a[1], b[0], b[1], 0.42f, (1f - dist / thr) * (0.3f + 0.55f * depth), max(0.6f, (o["lineW"] ?: 0.8f) * rs))
        }
        for (i in 0 until nodeN) {
            val p = pt.apply(nodes[i][0], nodes[i][1], nodes[i][2])
            val depth = (p[2] + 1f) / 2f
            val pulse = 1f + 0.25f * sin(t * 1.4f + i * 2.7f)
            dots += OrbDot(p[0], p[1], p[2], (nodeR + nodeRDepth * depth) * pulse * rs, 0.55f - 0.45f * depth)
        }
        val signals = (o["signals"] ?: 5f).toInt()
        for (s in 0 until signals) {
            val seg = floor(t * 0.55f + s * 7.31f)
            val a = floor(hashD(seg, s * 3.1f + 1.7f) * nodeN).toInt()
            val b = floor(hashD(seg, s * 5.7f + 4.2f) * nodeN).toInt()
            if (a == b || a !in 0 until nodeN || b !in 0 until nodeN) continue
            val f = frac(t * 0.55f + s * 7.31f)
            val x = lerp(nodes[a][0], nodes[b][0], f)
            val y = lerp(nodes[a][1], nodes[b][1], f)
            val z = lerp(nodes[a][2], nodes[b][2], f)
            val l = max(1e-6f, sqrt(x * x + y * y + z * z))
            val p = pt.apply(x / l, y / l, z / l)
            val depth = (p[2] + 1f) / 2f
            dots += OrbDot(p[0], p[1], p[2], (nodeR * 1.5f + nodeRDepth * depth) * rs, 0.05f, 0.5f + 0.5f * depth)
        }
        return finalize(dots, lines, o["rMin"] ?: 0.3f)
    }

    private fun frameBraid(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val r = (size / 2f) * 0.76f
        val pt = Proj(t * 0.4f, 0.3f, size / 2f, size / 2f, 1f)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6f)
        val dots = ArrayList<OrbDot>()
        val ghostN = (o["ghostN"] ?: 150f).toInt()
        for (i in 0 until ghostN) {
            val d = fibDir(i, ghostN)
            val p = pt.apply(d[0] * r, d[1] * r, d[2] * r)
            val depth = (p[2] / r + 1f) / 2f
            dots += OrbDot(p[0], p[1], p[2], 0.8f * rs, 0.78f, 0.1f + 0.22f * depth)
        }
        val strandN = (o["strandN"] ?: 52f).toInt()
        val turns = o["turns"] ?: 3f
        for (s in 0 until 3) {
            val phase = (s / 3f) * 2f * PI.toFloat()
            for (i in 0 until strandN) {
                val u = (frac(i.toFloat() / strandN + t * 0.045f) * 2f - 1f) * 0.96f
                val surf = sqrt(max(0f, 1f - u * u))
                val endFade = min(1f, (1f - abs(u)) / 0.1f)
                val a = u * PI.toFloat() * turns + phase
                val weave = 1f + 0.075f * sin(u * PI.toFloat() * turns * 2f + phase * 2f + t * 0.8f)
                val rr = surf * r * weave
                val p = pt.apply(cos(a) * rr, u * r * weave, sin(a) * rr)
                val depth = (p[2] / r + 1f) / 2f
                dots += OrbDot(
                    p[0], p[1], p[2],
                    ((o["rBase"] ?: 1.2f) + (o["rDepth"] ?: 1.8f) * depth) * rs,
                    0.55f - 0.45f * depth,
                    endFade * (0.45f + 0.55f * depth),
                )
            }
        }
        return finalize(dots, emptyList(), o["rMin"] ?: 0.3f)
    }

    private fun frameRibbon(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val r = (size / 2f) * 0.78f
        val spin = o["spin"] ?: 1f
        val camTilt = 0.3f
        val pt = Proj(t * 0.1f * spin, camTilt, size / 2f, size / 2f, 1f)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6f)
        val dots = ArrayList<OrbDot>()
        val ghostN = (o["ghostN"] ?: 150f).toInt()
        for (i in 0 until ghostN) {
            val d = fibDir(i, max(1, ghostN))
            val p = pt.apply(d[0] * r, d[1] * r, d[2] * r)
            val depth = (p[2] / r + 1f) / 2f
            dots += OrbDot(p[0], p[1], p[2], 0.8f * rs, 0.78f, 0.1f + 0.22f * depth)
        }
        val ya = t * 0.24f * spin
        val faceOn = (o["faceOn"] ?: 0f) != 0f
        val ta = if (faceOn) -camTilt else 0.55f + 0.3f * sin(t * 0.18f) * spin
        val ux = cos(ya); val uy = 0f; val uz = sin(ya)
        val vx = -uz * sin(ta); val vy = cos(ta); val vz = ux * sin(ta)
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val wobAmp = 0.23f * (o["wobMul"] ?: 1f)
        val baseR = if (faceOn) r / (1f + 0.85f * wobAmp) else r
        val baseLanes = o["lanes"] ?: 5f
        val segs = (o["segs"] ?: 88f).toInt().coerceAtLeast(1)
        val lanes = max(1, (baseLanes * (o["bandMul"] ?: 1f)).roundToInt())
        for (w in 0 until lanes) {
            val laneOff = (w - (lanes - 1) / 2f) * 0.075f
            val edge = abs(w - (lanes - 1) / 2f) / max(1f, (lanes - 1) / 2f)
            for (k in 0 until segs) {
                val a = (k.toFloat() / segs) * 2f * PI.toFloat()
                val wob = (0.16f * sin(a * 3f - t * 1.7f + w * 0.22f) + 0.07f * sin(a * 5f + t * 1.1f)) * (o["wobMul"] ?: 1f)
                val radial = if (faceOn) 1f + wob else 1f
                val off = if (faceOn) laneOff else laneOff + wob
                val x = ux * cos(a) + vx * sin(a) + nx * off
                val y = uy * cos(a) + vy * sin(a) + ny * off
                val z = uz * cos(a) + vz * sin(a) + nz * off
                val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
                val rr = baseR * radial
                val p = pt.apply((x / l) * rr, (y / l) * rr, (z / l) * rr)
                val depth = (p[2] / r + 1f) / 2f
                dots += OrbDot(
                    p[0], p[1], p[2],
                    ((o["rBase"] ?: 1.1f) + (o["rDepth"] ?: 1.7f) * depth) * (1f - 0.25f * edge) * rs,
                    0.52f - 0.44f * depth + 0.18f * edge,
                    0.4f + 0.6f * depth,
                )
            }
        }
        return finalize(dots, emptyList(), o["rMin"] ?: 0.3f)
    }

    private fun smoothE(x: Float) = x * x * (3f - 2f * x)
    private fun morphN(d: Float) = max(6, (34f * d).roundToInt())

    private fun frameMorph(size: Float, t: Float, o: ModeOpts): OrbFrame {
        val hold = 1.4f
        val morph = 0.9f
        val seg = hold + morph
        val cycle = 3
        val tc = ((t % (seg * cycle)) + (seg * cycle)) % (seg * cycle)
        val k = floor(tc / seg).toInt().coerceIn(0, cycle - 1)
        val local = tc - k * seg
        val m = if (local > hold) smoothE((local - hold) / morph) else 0f
        val sprd = o["spread"] ?: 1f
        val samples = 160
        val pts = Array(samples) { i ->
            val f = i.toFloat() / samples
            val a = morphPoint(k, f)
            val b = morphPoint((k + 1) % cycle, f)
            floatArrayOf((a[0] + (b[0] - a[0]) * m) * sprd, (a[1] + (b[1] - a[1]) * m) * sprd)
        }
        val lengths = FloatArray(samples)
        var total = 0f
        for (i in 0 until samples) {
            val a = pts[i]
            val b = pts[(i + 1) % samples]
            val l = hypot(b[0] - a[0], b[1] - a[1])
            lengths[i] = l
            total += l
        }
        val n = morphN(o["iconD"] ?: 1f)
        val re = (o["rDot"] ?: 0.021f) * 1.35f * sprd
        val pulse = 1f + 0.02f * sin(local * 3.1f)
        val dots = ArrayList<OrbDot>(n)
        val c2 = size / 2f
        var segment = 0
        var acc = 0f
        for (index in 0 until n) {
            val target = (index.toFloat() / n) * total
            while (acc + lengths[segment] < target && segment < samples - 1) {
                acc += lengths[segment]
                segment++
            }
            val a = pts[segment]
            val b = pts[(segment + 1) % samples]
            val f = if (lengths[segment] == 0f) 0f else min(1f, (target - acc) / lengths[segment])
            val x = (a[0] + (b[0] - a[0]) * f) * pulse
            val y = (a[1] + (b[1] - a[1]) * f) * pulse
            dots += OrbDot(c2 + x * size, c2 + y * size, 0f, max(0.35f, re * size), 0.1f)
        }
        return finalize(dots, emptyList(), o["rMin"] ?: 0.25f)
    }

    private fun morphPoint(shape: Int, f: Float): FloatArray = when (shape) {
        0 -> {
            val a = -PI.toFloat() / 2f + f * 2f * PI.toFloat()
            floatArrayOf(cos(a) * 0.24f, sin(a) * 0.24f)
        }
        1 -> polyPoint(f, floatArrayOf(0f, -0.26f, 0.24f, 0.16f, -0.24f, 0.16f))
        else -> polyPoint(f, floatArrayOf(0f, -0.2f, 0.2f, -0.2f, 0.2f, 0.2f, -0.2f, 0.2f, -0.2f, -0.2f))
    }

    private fun polyPoint(f: Float, verts: FloatArray): FloatArray {
        val n = verts.size / 2
        val lengths = FloatArray(n)
        var total = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val l = hypot(verts[j * 2] - verts[i * 2], verts[j * 2 + 1] - verts[i * 2 + 1])
            lengths[i] = l
            total += l
        }
        var target = f * total
        var i = 0
        while (target > lengths[i] && i < n - 1) {
            target -= lengths[i]
            i++
        }
        val j = (i + 1) % n
        val ff = if (lengths[i] == 0f) 0f else min(1f, target / lengths[i])
        return floatArrayOf(verts[i * 2] + (verts[j * 2] - verts[i * 2]) * ff, verts[i * 2 + 1] + (verts[j * 2 + 1] - verts[i * 2 + 1]) * ff)
    }
}
