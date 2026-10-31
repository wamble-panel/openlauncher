package com.openlauncher.app.map

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Memory-mapped reader for egypt-routing.graph (format documented in
 * tools/map/build_routing_graph.py). Nothing is loaded into the Java heap;
 * pages are read on demand by the OS, which keeps RAM use low on 2 GB units.
 */
class RoutingGraph private constructor(private val buf: ByteBuffer) {
    val nodeCount = header(2)
    val usegCount = header(3)
    private val nameCount = header(6)
    private val gridKeyCount = header(8)
    private val gridMinLat = header(10)
    private val gridMinLon = header(11)
    private val gridCell = header(12)
    private val gridCols = header(13)

    private val nodeLat = ints(0, nodeCount)
    private val nodeLon = ints(1, nodeCount)
    private val nodeFirst = ints(2, nodeCount + 1)
    private val dedgeTarget = ints(3, header(4))
    private val dedgeUseg = ints(4, header(4))
    private val usegFrom = ints(5, usegCount)
    private val usegTo = ints(6, usegCount)
    private val usegLen = ints(7, usegCount)
    private val usegGeomStart = ints(8, usegCount)
    private val usegGeomCount = ints(9, usegCount)
    private val usegName = ints(10, usegCount)
    private val usegPacked = ints(11, usegCount)
    private val geomLat = ints(12, header(5))
    private val geomLon = ints(13, header(5))
    private val nameOff = ints(14, nameCount + 1)
    private val nameBlobOffset = sectionOffset(15)
    private val gridKeys = ints(16, gridKeyCount)
    private val gridOff = ints(17, gridKeyCount + 1)
    private val gridEntries = ints(18, header(9))

    private fun header(i: Int) = buf.getInt(i * 4)
    private fun sectionOffset(i: Int) = buf.getInt(56 + i * 4)
    private fun ints(section: Int, count: Int): IntBuffer {
        val d = buf.duplicate()
        d.position(sectionOffset(section))
        val s = d.slice().order(ByteOrder.LITTLE_ENDIAN)
        s.limit(count * 4)
        return s.asIntBuffer()
    }

    fun latE6(node: Int) = nodeLat.get(node)
    fun lonE6(node: Int) = nodeLon.get(node)
    fun firstEdge(node: Int) = nodeFirst.get(node)
    fun endEdge(node: Int) = nodeFirst.get(node + 1)
    fun edgeTarget(e: Int) = dedgeTarget.get(e)
    fun edgeUseg(e: Int) = dedgeUseg.get(e) and 0x7FFFFFFF
    fun edgeReversed(e: Int) = dedgeUseg.get(e) < 0
    fun edgeSource(e: Int) = if (edgeReversed(e)) usegTo.get(edgeUseg(e)) else usegFrom.get(edgeUseg(e))

    fun from(u: Int) = usegFrom.get(u)
    fun to(u: Int) = usegTo.get(u)
    fun lengthM(u: Int) = usegLen.get(u) / 10.0
    fun speedKmh(u: Int) = usegPacked.get(u) and 0xFF
    fun roadClass(u: Int) = (usegPacked.get(u) ushr 8) and 0xFF
    fun flags(u: Int) = (usegPacked.get(u) ushr 16) and 0xFF
    fun maxspeed(u: Int) = (usegPacked.get(u) ushr 24) and 0xFF
    fun forwardAllowed(u: Int) = flags(u) and 2 == 0
    fun backwardAllowed(u: Int) = flags(u) and 1 == 0
    fun isRoundabout(u: Int) = flags(u) and 4 != 0
    fun isLink(u: Int) = flags(u) and 8 != 0
    fun travelSeconds(u: Int, meters: Double) = meters / (speedKmh(u).coerceAtLeast(5) / 3.6)

    /** Road name parts: local name, English name, reference (e.g. a ring-road number). */
    fun names(u: Int): RoadName {
        val i = usegName.get(u)
        val start = nameOff.get(i)
        val end = nameOff.get(i + 1)
        if (end <= start) return RoadName.EMPTY
        val bytes = ByteArray(end - start)
        val d = buf.duplicate()
        d.position(nameBlobOffset + start)
        d.get(bytes)
        val parts = String(bytes, Charsets.UTF_8).split('\u001f')
        return RoadName(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    /** Polyline of a road segment from its `from` node to its `to` node, in e6 degrees. */
    fun polyline(u: Int): Pair<IntArray, IntArray> {
        val start = usegGeomStart.get(u)
        val count = usegGeomCount.get(u)
        val lat = IntArray(count + 2)
        val lon = IntArray(count + 2)
        lat[0] = nodeLat.get(usegFrom.get(u)); lon[0] = nodeLon.get(usegFrom.get(u))
        for (i in 0 until count) {
            lat[i + 1] = geomLat.get(start + i)
            lon[i + 1] = geomLon.get(start + i)
        }
        lat[count + 1] = nodeLat.get(usegTo.get(u)); lon[count + 1] = nodeLon.get(usegTo.get(u))
        return lat to lon
    }

    /** Every road segment with a point within [radiusM] of a position (each once). */
    fun forEachSegmentNear(lat: Double, lon: Double, radiusM: Double, block: (Int) -> Unit) {
        val latE6 = (lat * 1e6).toInt()
        val lonE6 = (lon * 1e6).toInt()
        val dLat = (radiusM / 111_320.0 * 1e6).toInt()
        val dLon = (radiusM / (111_320.0 * cos(Math.toRadians(lat))) * 1e6).toInt()
        val r0 = (latE6 - dLat - gridMinLat) / gridCell
        val r1 = (latE6 + dLat - gridMinLat) / gridCell
        val c0 = (lonE6 - dLon - gridMinLon) / gridCell
        val c1 = (lonE6 + dLon - gridMinLon) / gridCell
        val seen = HashSet<Int>()
        for (row in r0..r1) for (col in c0..c1) {
            if (col < 0 || col >= gridCols) continue
            val k = binarySearch(gridKeys, gridKeyCount, row * gridCols + col)
            if (k < 0) continue
            for (i in gridOff.get(k) until gridOff.get(k + 1)) {
                val u = gridEntries.get(i)
                if (seen.add(u)) block(u)
            }
        }
    }

    /**
     * Nearest road to a position. With a heading (degrees), roads pointing the
     * other way get a penalty so a fix on a dual carriageway picks your side.
     */
    fun snap(lat: Double, lon: Double, heading: Float? = null, maxMeters: Double = 120.0): Snap? {
        val latE6 = (lat * 1e6).toInt()
        val lonE6 = (lon * 1e6).toInt()
        val row = (latE6 - gridMinLat) / gridCell
        val col = (lonE6 - gridMinLon) / gridCell
        var best: Snap? = null
        var bestScore = Double.MAX_VALUE
        val seen = HashSet<Int>()
        for (dr in -1..1) for (dc in -1..1) {
            val key = (row + dr) * gridCols + (col + dc)
            val k = binarySearch(gridKeys, gridKeyCount, key)
            if (k < 0) continue
            for (i in gridOff.get(k) until gridOff.get(k + 1)) {
                val u = gridEntries.get(i)
                if (!seen.add(u)) continue
                val s = projectOnto(u, lat, lon) ?: continue
                var score = s.distanceM
                if (heading != null && s.distanceM < maxMeters) {
                    val segBearing = s.bearing
                    val fwd = forwardAllowed(u) && angleDiff(segBearing, heading) < 75f
                    val bwd = backwardAllowed(u) && angleDiff((segBearing + 180f) % 360f, heading) < 75f
                    if (!fwd && !bwd) score += 35.0
                }
                if (score < bestScore) {
                    bestScore = score
                    best = s
                }
            }
        }
        return best?.takeIf { it.distanceM <= maxMeters }
    }

    private fun projectOnto(u: Int, lat: Double, lon: Double): Snap? {
        val (pLat, pLon) = polyline(u)
        val cosLat = cos(Math.toRadians(lat))
        var bestD = Double.MAX_VALUE
        var bestIdx = -1
        var bestT = 0.0
        var cumulative = 0.0
        var bestAlongLocal = 0.0
        var totalLocal = 0.0
        val segLens = DoubleArray(pLat.size - 1)
        for (i in 0 until pLat.size - 1) {
            val ax = (pLon[i] / 1e6 - lon) * cosLat * 111320.0
            val ay = (pLat[i] / 1e6 - lat) * 111320.0
            val bx = (pLon[i + 1] / 1e6 - lon) * cosLat * 111320.0
            val by = (pLat[i + 1] / 1e6 - lat) * 111320.0
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val segLen = sqrt(len2)
            segLens[i] = segLen
            val t = if (len2 > 0) ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) {
                bestD = d
                bestIdx = i
                bestT = t
                bestAlongLocal = cumulative + t * segLen
            }
            cumulative += segLen
        }
        totalLocal = cumulative
        if (bestIdx < 0) return null
        val a = bestIdx
        val pointLat = (pLat[a] + (pLat[a + 1] - pLat[a]) * bestT) / 1e6
        val pointLon = (pLon[a] + (pLon[a + 1] - pLon[a]) * bestT) / 1e6
        val along = if (totalLocal > 0) bestAlongLocal / totalLocal * lengthM(u) else 0.0
        val bearing = bearing(pLat[a] / 1e6, pLon[a] / 1e6, pLat[a + 1] / 1e6, pLon[a + 1] / 1e6)
        return Snap(u, along, pointLat, pointLon, bestD, bearing)
    }

    companion object {
        fun open(file: File): RoutingGraph? = runCatching {
            if (!file.exists()) return null
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                map.order(ByteOrder.LITTLE_ENDIAN)
                if (map.getInt(0) != 0x52494D4D) return null // "MMIR"
                RoutingGraph(map)
            }
        }.getOrNull()

        private fun binarySearch(buf: IntBuffer, count: Int, key: Int): Int {
            var lo = 0
            var hi = count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = buf.get(mid)
                when {
                    v < key -> lo = mid + 1
                    v > key -> hi = mid - 1
                    else -> return mid
                }
            }
            return -1
        }
    }
}

data class RoadName(val local: String, val english: String, val ref: String) {
    /** English name, then local name, then road number. */
    val display: String get() = english.ifEmpty { local }.ifEmpty { ref }
    /** What a voice in English can actually pronounce. */
    val spoken: String get() = english.ifEmpty { if (local.all { it.code < 0x0600 }) local else "" }.ifEmpty { ref }
    val isEmpty: Boolean get() = local.isEmpty() && english.isEmpty() && ref.isEmpty()

    companion object {
        val EMPTY = RoadName("", "", "")
    }
}

/** A position projected onto road segment [useg], [alongM] metres from its `from` node. */
data class Snap(
    val useg: Int,
    val alongM: Double,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val bearing: Float,
)

fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dl) * cos(p2)
    val x = cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
}

fun angleDiff(a: Float, b: Float): Float {
    val d = abs(((a - b) % 360f + 540f) % 360f - 180f)
    return d
}
