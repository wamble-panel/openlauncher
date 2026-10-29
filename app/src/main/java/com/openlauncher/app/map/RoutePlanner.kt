package com.openlauncher.app.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

enum class ManeuverType {
    DEPART, STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT,
    UTURN, KEEP_LEFT, KEEP_RIGHT, ROUNDABOUT, ARRIVE,
}

data class Maneuver(
    val type: ManeuverType,
    val road: RoadName,
    val alongM: Double,
    val exit: Int = 0,
) {
    /** On-screen instruction. */
    val text: String get() = instruction(road.display)
    /** Spoken instruction (no Arabic script for an English voice). */
    val spokenText: String get() = instruction(road.spoken)

    private fun instruction(name: String): String {
        val onto = if (name.isNotEmpty()) " onto $name" else ""
        return when (type) {
            ManeuverType.DEPART -> if (name.isNotEmpty()) "Head along $name" else "Follow the route"
            ManeuverType.STRAIGHT -> "Continue straight$onto"
            ManeuverType.SLIGHT_LEFT -> "Bear left$onto"
            ManeuverType.SLIGHT_RIGHT -> "Bear right$onto"
            ManeuverType.LEFT -> "Turn left$onto"
            ManeuverType.RIGHT -> "Turn right$onto"
            ManeuverType.SHARP_LEFT -> "Turn sharp left$onto"
            ManeuverType.SHARP_RIGHT -> "Turn sharp right$onto"
            ManeuverType.UTURN -> "Make a U-turn$onto"
            ManeuverType.KEEP_LEFT -> "Keep left" + if (name.isNotEmpty()) " toward $name" else ""
            ManeuverType.KEEP_RIGHT -> "Keep right" + if (name.isNotEmpty()) " toward $name" else ""
            ManeuverType.ROUNDABOUT -> "At the roundabout, take the ${ordinal(exit)} exit$onto"
            ManeuverType.ARRIVE -> "You have arrived at your destination"
        }
    }

    private fun ordinal(n: Int) = when (n) {
        1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; else -> "${n}th"
    }
}

/** One stretch of the route on a single road segment, between two point indices. */
data class RoutePiece(val useg: Int, val firstPoint: Int, val lastPoint: Int)

class Route(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cumDist: DoubleArray,
    val cumTime: DoubleArray,
    val pieces: List<RoutePiece>,
    val maneuvers: List<Maneuver>,
) {
    val lengthM: Double get() = cumDist.last()
    val durationS: Double get() = cumTime.last()

    fun timeAt(alongM: Double): Double {
        var i = cumDist.binarySearch(alongM)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, cumDist.size - 2)
        val span = cumDist[i + 1] - cumDist[i]
        val t = if (span > 0) (alongM - cumDist[i]) / span else 0.0
        return cumTime[i] + (cumTime[i + 1] - cumTime[i]) * t.coerceIn(0.0, 1.0)
    }

    fun pieceAtPoint(pointIndex: Int): RoutePiece? = pieces.firstOrNull { pointIndex >= it.firstPoint && pointIndex < it.lastPoint }
}

/**
 * Time-based A* over the memory-mapped graph. Search state lives in reusable
 * primitive arrays stamped per run, so a route allocates almost nothing.
 */
class RoutePlanner(private val g: RoutingGraph) {
    private val stamp = IntArray(g.nodeCount)
    private val cost = FloatArray(g.nodeCount)
    private val parent = IntArray(g.nodeCount)
    private var run = 0
    private var heapNode = IntArray(4096)
    private var heapKey = FloatArray(4096)
    private var heapSize = 0

    fun plan(from: Snap, to: Snap, heading: Float?): Route? {
        val s = from.useg
        val t = to.useg
        val sLen = g.lengthM(s)
        val tLen = g.lengthM(t)

        // Destination on the very segment we start on, in an allowed direction.
        if (s == t) {
            val ahead = to.alongM >= from.alongM
            if ((ahead && g.forwardAllowed(s)) || (!ahead && g.backwardAllowed(s))) {
                return build(from, to, emptyList(), direct = true)
            }
        }

        run++
        heapSize = 0
        val targetLat = to.lat
        val targetLon = to.lon
        val cosLat = cos(Math.toRadians(targetLat))
        fun h(node: Int): Float {
            val dy = (g.latE6(node) / 1e6 - targetLat) * 111320.0
            val dx = (g.lonE6(node) / 1e6 - targetLon) * 111320.0 * cosLat
            return (sqrt(dx * dx + dy * dy) / 36.1).toFloat() // 130 km/h upper bound
        }
        fun seed(node: Int, seconds: Double) {
            val c = seconds.toFloat()
            if (stamp[node] != run || c < cost[node]) {
                stamp[node] = run
                cost[node] = c
                parent[node] = -1
                push(node, c + h(node))
            }
        }
        val headingPenalty = { forward: Boolean ->
            if (heading == null) 0.0 else {
                val dir = if (forward) from.bearing else (from.bearing + 180f) % 360f
                if (angleDiff(dir, heading) > 100f) 45.0 else 0.0
            }
        }
        if (g.forwardAllowed(s)) seed(g.to(s), g.travelSeconds(s, sLen - from.alongM) + headingPenalty(true))
        if (g.backwardAllowed(s)) seed(g.from(s), g.travelSeconds(s, from.alongM) + headingPenalty(false))

        val viaFrom = if (g.forwardAllowed(t)) g.travelSeconds(t, to.alongM) else -1.0
        val viaTo = if (g.backwardAllowed(t)) g.travelSeconds(t, tLen - to.alongM) else -1.0
        var best = Float.MAX_VALUE
        var bestEnd = -1
        var popped = 0

        while (heapSize > 0) {
            val node = heapNode[0]
            val f = heapKey[0]
            pop()
            if (f >= best) break
            val gCost = cost[node]
            if (f > gCost + h(node) + 0.01f) continue
            if (++popped > 4_000_000) return null
            if (node == g.from(t) && viaFrom >= 0 && gCost + viaFrom < best) { best = (gCost + viaFrom).toFloat(); bestEnd = node }
            if (node == g.to(t) && viaTo >= 0 && gCost + viaTo < best) { best = (gCost + viaTo).toFloat(); bestEnd = node }
            for (e in g.firstEdge(node) until g.endEdge(node)) {
                val v = g.edgeTarget(e)
                val u = g.edgeUseg(e)
                val c = gCost + g.travelSeconds(u, g.lengthM(u)).toFloat() + turnPenalty(e)
                if (stamp[v] != run || c < cost[v]) {
                    stamp[v] = run
                    cost[v] = c
                    parent[v] = e
                    push(v, c + h(v))
                }
            }
        }
        if (bestEnd < 0) return null

        val edges = ArrayList<Int>()
        var n = bestEnd
        while (parent[n] != -1) {
            val e = parent[n]
            edges.add(e)
            n = g.edgeSource(e)
        }
        edges.reverse()
        return build(from, to, edges, direct = false, startNode = n, endNode = bestEnd)
    }

    // Small cost for every junction so equal-time routes prefer fewer turns.
    private fun turnPenalty(@Suppress("UNUSED_PARAMETER") e: Int) = 1.5f

    private fun build(from: Snap, to: Snap, edges: List<Int>, direct: Boolean, startNode: Int = -1, endNode: Int = -1): Route {
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val pieces = ArrayList<RoutePiece>()

        fun appendPart(u: Int, fromAlong: Double, toAlong: Double) {
            val first = (lat.size - 1).coerceAtLeast(0)
            val (pLat, pLon) = g.polyline(u)
            val pts = subPolyline(u, pLat, pLon, fromAlong, toAlong)
            for ((i, p) in pts.withIndex()) {
                if (i == 0 && lat.isNotEmpty()) continue
                lat.add(p.first); lon.add(p.second)
            }
            if (lat.size - 1 > first) pieces += RoutePiece(u, first, lat.size - 1)
        }

        if (direct) {
            appendPart(from.useg, from.alongM, to.alongM)
        } else {
            val s = from.useg
            appendPart(s, from.alongM, if (startNode == g.to(s)) g.lengthM(s) else 0.0)
            for (e in edges) {
                val u = g.edgeUseg(e)
                if (g.edgeReversed(e)) appendPart(u, g.lengthM(u), 0.0) else appendPart(u, 0.0, g.lengthM(u))
            }
            val t = to.useg
            appendPart(t, if (endNode == g.from(t)) 0.0 else g.lengthM(t), to.alongM)
        }
        if (lat.size < 2) {
            lat.add(to.lat); lon.add(to.lon)
        }

        val n = lat.size
        val cumDist = DoubleArray(n)
        val cumTime = DoubleArray(n)
        var pi = 0
        for (i in 1 until n) {
            val d = distanceMeters(lat[i - 1], lon[i - 1], lat[i], lon[i])
            cumDist[i] = cumDist[i - 1] + d
            while (pi < pieces.size && i - 1 >= pieces[pi].lastPoint) pi++
            val piece = pieces.getOrNull(pi)
            cumTime[i] = cumTime[i - 1] + (piece?.let { g.travelSeconds(it.useg, d) } ?: (d / 8.0))
        }
        val latArr = lat.toDoubleArray()
        val lonArr = lon.toDoubleArray()
        return Route(latArr, lonArr, cumDist, cumTime, pieces, maneuvers(latArr, lonArr, cumDist, pieces))
    }

    /** Points of segment [u] between two along-distances, oriented from [fromAlong] to [toAlong]. */
    private fun subPolyline(u: Int, pLat: IntArray, pLon: IntArray, fromAlong: Double, toAlong: Double): List<Pair<Double, Double>> {
        val n = pLat.size
        val along = DoubleArray(n)
        for (i in 1 until n) {
            along[i] = along[i - 1] + distanceMeters(pLat[i - 1] / 1e6, pLon[i - 1] / 1e6, pLat[i] / 1e6, pLon[i] / 1e6)
        }
        val scale = if (along[n - 1] > 0) g.lengthM(u) / along[n - 1] else 1.0
        for (i in 0 until n) along[i] *= scale
        fun pointAt(a: Double): Pair<Double, Double> {
            var i = 0
            while (i < n - 2 && along[i + 1] < a) i++
            val span = along[i + 1] - along[i]
            val t = if (span > 0) ((a - along[i]) / span).coerceIn(0.0, 1.0) else 0.0
            return (pLat[i] + (pLat[i + 1] - pLat[i]) * t) / 1e6 to (pLon[i] + (pLon[i + 1] - pLon[i]) * t) / 1e6
        }
        val out = ArrayList<Pair<Double, Double>>()
        out += pointAt(fromAlong)
        if (toAlong >= fromAlong) {
            for (i in 0 until n) if (along[i] > fromAlong && along[i] < toAlong) out += pLat[i] / 1e6 to pLon[i] / 1e6
        } else {
            for (i in n - 1 downTo 0) if (along[i] < fromAlong && along[i] > toAlong) out += pLat[i] / 1e6 to pLon[i] / 1e6
        }
        out += pointAt(toAlong)
        return out
    }

    private fun maneuvers(lat: DoubleArray, lon: DoubleArray, cum: DoubleArray, pieces: List<RoutePiece>): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        if (pieces.isEmpty()) {
            out += Maneuver(ManeuverType.ARRIVE, RoadName.EMPTY, cum.last())
            return out
        }
        out += Maneuver(ManeuverType.DEPART, g.names(pieces.first().useg), 0.0)
        var i = 0
        while (i < pieces.size - 1) {
            val a = pieces[i]
            val b = pieces[i + 1]
            val j = a.lastPoint
            val inB = directionInto(lat, lon, j)
            val outB = directionOutOf(lat, lon, j)
            val turn = ((outB - inB + 540f) % 360f) - 180f
            val node = junctionNode(a.useg, lat[j], lon[j])
            val options = if (node >= 0) g.endEdge(node) - g.firstEdge(node) else 1
            val nameA = g.names(a.useg)
            val nameB = g.names(b.useg)

            if (!g.isRoundabout(a.useg) && g.isRoundabout(b.useg)) {
                var exitCount = 0
                var k = i + 1
                while (k < pieces.size - 1 && g.isRoundabout(pieces[k].useg)) {
                    val p = pieces[k]
                    val n = junctionNode(p.useg, lat[p.lastPoint], lon[p.lastPoint])
                    if (n >= 0) {
                        val exits = (g.firstEdge(n) until g.endEdge(n)).count { !g.isRoundabout(g.edgeUseg(it)) }
                        if (exits > 0) exitCount++
                    }
                    k++
                }
                val exitRoad = if (k < pieces.size) g.names(pieces[k].useg) else RoadName.EMPTY
                out += Maneuver(ManeuverType.ROUNDABOUT, exitRoad, cum[j], exit = exitCount.coerceAtLeast(1))
                i = k
                continue
            }

            val absTurn = abs(turn)
            val nameChanged = nameB.display != nameA.display && !nameB.isEmpty
            val type = when {
                options <= 2 && absTurn < 50f -> null
                absTurn < 12f && !nameChanged -> null
                absTurn < 25f && (g.isLink(b.useg) != g.isLink(a.useg)) -> if (turn < 0) ManeuverType.KEEP_LEFT else ManeuverType.KEEP_RIGHT
                absTurn < 25f -> if (nameChanged && options > 2) ManeuverType.STRAIGHT else null
                absTurn < 55f -> if (turn < 0) ManeuverType.SLIGHT_LEFT else ManeuverType.SLIGHT_RIGHT
                absTurn < 125f -> if (turn < 0) ManeuverType.LEFT else ManeuverType.RIGHT
                absTurn < 165f -> if (turn < 0) ManeuverType.SHARP_LEFT else ManeuverType.SHARP_RIGHT
                else -> ManeuverType.UTURN
            }
            if (type != null) out += Maneuver(type, nameB, cum[j])
            i++
        }
        out += Maneuver(ManeuverType.ARRIVE, RoadName.EMPTY, cum.last())
        return out
    }

    private fun junctionNode(useg: Int, lat: Double, lon: Double): Int {
        val a = g.from(useg)
        val b = g.to(useg)
        val da = abs(g.latE6(a) / 1e6 - lat) + abs(g.lonE6(a) / 1e6 - lon)
        val db = abs(g.latE6(b) / 1e6 - lat) + abs(g.lonE6(b) / 1e6 - lon)
        return when {
            da < 2e-6 -> a
            db < 2e-6 -> b
            else -> -1
        }
    }

    private fun directionInto(lat: DoubleArray, lon: DoubleArray, j: Int): Float {
        var k = j - 1
        while (k > 0 && distanceMeters(lat[k], lon[k], lat[j], lon[j]) < 8) k--
        return bearing(lat[k], lon[k], lat[j], lon[j])
    }

    private fun directionOutOf(lat: DoubleArray, lon: DoubleArray, j: Int): Float {
        var k = j + 1
        while (k < lat.size - 1 && distanceMeters(lat[j], lon[j], lat[k], lon[k]) < 8) k++
        return bearing(lat[j], lon[j], lat[k], lon[k])
    }

    // ── Binary min-heap on (key, node) ──
    private fun push(node: Int, key: Float) {
        if (heapSize == heapNode.size) {
            heapNode = heapNode.copyOf(heapSize * 2)
            heapKey = heapKey.copyOf(heapSize * 2)
        }
        var i = heapSize++
        while (i > 0) {
            val p = (i - 1) / 2
            if (heapKey[p] <= key) break
            heapNode[i] = heapNode[p]; heapKey[i] = heapKey[p]
            i = p
        }
        heapNode[i] = node; heapKey[i] = key
    }

    private fun pop() {
        heapSize--
        if (heapSize == 0) return
        val node = heapNode[heapSize]
        val key = heapKey[heapSize]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= heapSize) break
            if (c + 1 < heapSize && heapKey[c + 1] < heapKey[c]) c++
            if (heapKey[c] >= key) break
            heapNode[i] = heapNode[c]; heapKey[i] = heapKey[c]
            i = c
        }
        heapNode[i] = node; heapKey[i] = key
    }
}
