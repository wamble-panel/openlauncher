package com.openlauncher.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The navigation map, built for speed: flat lines straight from the offline road
 * network, drawn by OpenGL on its own thread. Nothing else on screen can make it
 * stutter, and parked it draws nothing at all.
 *
 * Roads near the car are turned into line geometry once (in the background) and
 * uploaded to the GPU; each frame only moves the camera. Between GPS fixes the car
 * is carried forward from its speed and heading, so it glides instead of hopping.
 */
@SuppressLint("ViewConstructor")
class RetroMapView(context: Context) : GLSurfaceView(context) {

    /** Called (main thread) when the driver drags the map away from the car. */
    var onPan: (() -> Unit)? = null

    // Written on the main thread, read on the GL thread.
    @Volatile private var fixLat = Double.NaN
    @Volatile private var fixLon = 0.0
    @Volatile private var fixSpeed = 0.0
    @Volatile private var fixCourse = 0f
    @Volatile private var fixAt = 0L
    @Volatile var following = true
    @Volatile var northUp = false
    /** Metres shown from top to bottom of the screen. */
    @Volatile var spanMeters = 900.0
    @Volatile private var route: Route? = null
    @Volatile private var directTo: DoubleArray? = null
    @Volatile private var destLat = Double.NaN
    @Volatile private var destLon = 0.0
    @Volatile private var routeVersion = 0

    private val renderer = Renderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        // Frames only on request; while anything moves each frame asks for the next.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setFix(lat: Double, lon: Double, speedMps: Double, course: Float) {
        fixLat = lat; fixLon = lon; fixSpeed = speedMps; fixCourse = course
        fixAt = SystemClock.uptimeMillis()
        wake()
    }

    fun setRoute(r: Route?, destination: Place?, from: DoubleArray?) {
        route = r
        destLat = destination?.lat ?: Double.NaN
        destLon = destination?.lon ?: 0.0
        directTo = if (r == null && destination != null && from != null) doubleArrayOf(from[0], from[1], destination.lat, destination.lon) else null
        routeVersion++
        wake()
    }

    fun recenter() {
        following = true
        wake()
    }

    fun wake() = requestRender()

    // ── Panning ──────────────────────────────────────────────────────────────
    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (following && hypot(dx, dy) < 12f) return true
                lastX = e.x; lastY = e.y
                if (following) { following = false; onPan?.invoke() }
                renderer.pan(dx, dy)
                wake()
            }
        }
        return true
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var aPos = 0
        private var aSide = 0
        private var uMvp = 0
        private var uHw = 0
        private var uColor = 0
        private var width = 1
        private var height = 1

        // Roads: one buffer, drawn in tiers (thin to thick).
        private var roadVbo = 0
        private var tierStart = IntArray(TIERS)
        private var tierCount = IntArray(TIERS)
        private var routeVbo = 0
        private var routeCount = 0
        private var markerVbo = 0

        // Geometry is in metres around this origin.
        private var originLat = Double.NaN
        private var originLon = 0.0
        private var mx = 111_320.0
        private val my = 110_574.0
        private val builder = Executors.newSingleThreadExecutor()
        @Volatile private var building = false
        @Volatile private var builtRoads: Built? = null
        @Volatile private var builtRoute: BuiltRoute? = null
        private var haveRoads = false
        private var routeShown = -1

        // Camera, in metres from the origin.
        private var camX = 0.0
        private var camY = 0.0
        private var camBearing = 0f
        private var carX = 0.0
        private var carY = 0.0
        private var carCourse = 0f
        private var scale = 1.0
        private var lastFrame = 0L
        private var camReady = false
        @Volatile private var panDx = 0f
        @Volatile private var panDy = 0f

        fun pan(dx: Float, dy: Float) { panDx += dx; panDy += dy }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VERTEX, FRAGMENT)
            aPos = GLES20.glGetAttribLocation(program, "a_pd")
            aSide = GLES20.glGetAttribLocation(program, "a_side")
            uMvp = GLES20.glGetUniformLocation(program, "u_mvp")
            uHw = GLES20.glGetUniformLocation(program, "u_hw")
            uColor = GLES20.glGetUniformLocation(program, "u_color")
            val ids = IntArray(3)
            GLES20.glGenBuffers(3, ids, 0)
            roadVbo = ids[0]; routeVbo = ids[1]; markerVbo = ids[2]
            tierCount.fill(0); routeCount = 0; routeShown = -1
            // A context loss drops the buffers: rebuild around the car.
            originLat = Double.NaN
            haveRoads = false
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1000.0).coerceIn(0.0, 0.1)
            lastFrame = now
            GLES20.glClearColor(BG[0], BG[1], BG[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val lat = fixLat
            if (lat.isNaN()) { lastFrame = 0L; return }
            val lon = fixLon
            if (originLat.isNaN()) setOrigin(lat, lon)

            // Where the car is now: last fix carried forward along its heading.
            val age = ((now - fixAt + FIX_LATENCY_MS) / 1000.0).coerceIn(0.0, 2.0)
            val ahead = if (fixSpeed > 1.0) fixSpeed * age else 0.0
            val c = Math.toRadians(fixCourse.toDouble())
            val tx = (lon - originLon) * mx + ahead * sin(c)
            val ty = (lat - originLat) * my + ahead * cos(c)
            // Moves with the car's own speed every frame and only eases out the
            // difference to each new fix, so it never trails behind while driving.
            if (camReady && fixSpeed > 1.0) {
                carX += fixSpeed * sin(c) * dt
                carY += fixSpeed * cos(c) * dt
            }
            val k = if (camReady) 1.0 - exp(-dt / 0.35) else 1.0
            val jump = hypot(tx - carX, ty - carY) > 300.0
            carX = if (jump) tx else carX + (tx - carX) * k
            carY = if (jump) ty else carY + (ty - carY) * k
            carCourse = turnToward(carCourse, fixCourse, if (camReady) (1.0 - exp(-dt / 0.4)).toFloat() else 1f)

            val targetScale = height / spanMeters
            scale = if (camReady) scale + (targetScale - scale) * (1.0 - exp(-dt / 0.3)) else targetScale
            val bearingTarget = if (northUp) 0f else carCourse
            if (following) {
                camX = carX; camY = carY
                camBearing = turnToward(camBearing, bearingTarget, if (camReady) (1.0 - exp(-dt / 0.3)).toFloat() else 1f)
            } else {
                // Drag: screen pixels back into metres, undoing the map rotation.
                val px = panDx; val py = panDy
                panDx = 0f; panDy = 0f
                if (px != 0f || py != 0f) {
                    val b = Math.toRadians(camBearing.toDouble())
                    val ex = -px / scale; val ey = py / scale
                    camX += ex * cos(b) + ey * sin(b)
                    camY += -ex * sin(b) + ey * cos(b)
                }
            }
            camReady = true

            // Keep road geometry around the camera; rebuild when it drifts away.
            if (!building && (!haveRoads || hypot(camX, camY) > REBUILD_M)) requestRoads(originLat + camY / my, originLon + camX / mx)
            builtRoads?.let { uploadRoads(it) }
            if (routeShown != routeVersion && builtRoute == null) requestRoute()
            builtRoute?.let { uploadRoute(it) }

            GLES20.glUseProgram(program)
            val mvp = worldMatrix()
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, roadVbo)
            bindAttribs()
            for (t in 0 until TIERS) {
                if (tierCount[t] == 0) continue
                GLES20.glUniform1f(uHw, (TIER_WIDTH_PX[t] / 2.0 / scale).toFloat())
                GLES20.glUniform4fv(uColor, 1, TIER_COLOR[t], 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, tierStart[t], tierCount[t])
            }
            if (routeCount > 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, routeVbo)
                bindAttribs()
                GLES20.glUniform1f(uHw, (ROUTE_WIDTH_PX / 2.0 / scale).toFloat())
                GLES20.glUniform4fv(uColor, 1, ROUTE_COLOR, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, routeCount)
            }
            drawMarkers(mvp)

            // Parked and settled: stop drawing until something changes.
            val settled = fixSpeed < 1.0 && hypot(tx - carX, ty - carY) < 0.05 &&
                kotlin.math.abs(angle(camBearing, bearingTarget)) < 0.1f && kotlin.math.abs(targetScale - scale) < targetScale * 0.002 &&
                panDx == 0f && panDy == 0f && !building && builtRoads == null && builtRoute == null && routeShown == routeVersion
            when {
                !settled -> requestRender()
                // Road data still loading: look again in a second instead of drawing non-stop.
                !haveRoads -> { lastFrame = 0L; postDelayed({ requestRender() }, 1000) }
                else -> lastFrame = 0L
            }
        }

        private fun setOrigin(lat: Double, lon: Double) {
            originLat = lat; originLon = lon
            mx = 111_320.0 * cos(Math.toRadians(lat))
            camX = 0.0; camY = 0.0; carX = 0.0; carY = 0.0
            camReady = false
            tierCount.fill(0); routeCount = 0; routeShown = -1
            haveRoads = false
        }

        /** World metres -> screen: rotate by the bearing, scale, car low on the screen. */
        private fun worldMatrix(): FloatArray {
            val b = Math.toRadians(camBearing.toDouble())
            val a = (scale * 2.0 / width)
            val cc = (scale * 2.0 / height)
            val cs = cos(b); val sn = sin(b)
            val anchorY = if (northUp) 0.0 else ANCHOR_Y
            val m = FloatArray(16)
            m[0] = (a * cs).toFloat(); m[1] = (cc * sn).toFloat()
            m[4] = (-a * sn).toFloat(); m[5] = (cc * cs).toFloat()
            m[10] = 1f; m[15] = 1f
            m[12] = (-(a * cs * camX - a * sn * camY)).toFloat()
            m[13] = (-(cc * sn * camX + cc * cs * camY) + anchorY).toFloat()
            return m
        }

        private val marker = FloatArray(12 * FLOATS)
        private val markerBuf = ByteBuffer.allocateDirect(marker.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val pixelMatrix = FloatArray(16)

        private fun drawMarkers(world: FloatArray) {
            // Pixel-sized shapes placed at projected world points: destination square, car arrow.
            var n = 0
            fun v(x: Float, y: Float) { marker[n * FLOATS] = x; marker[n * FLOATS + 1] = y; n++ }
            val dLat = destLat
            if (!dLat.isNaN()) {
                val (x, y) = project(world, (destLon - originLon) * mx, (dLat - originLat) * my)
                val r = 11f
                v(x - r, y - r); v(x + r, y - r); v(x + r, y + r)
                v(x - r, y - r); v(x + r, y + r); v(x - r, y + r)
            }
            val dest = n
            val (cx, cy) = project(world, carX, carY)
            // The arrow points along the car's heading as seen on screen.
            val rot = Math.toRadians((carCourse - camBearing).toDouble())
            val sn = sin(rot).toFloat(); val c = cos(rot).toFloat()
            fun pt(px: Float, py: Float) = v(cx + (px * c + py * sn), cy + (-px * sn + py * c))
            pt(0f, 26f); pt(17f, -18f); pt(0f, -8f)
            pt(0f, 26f); pt(0f, -8f); pt(-17f, -18f)
            markerBuf.position(0)
            markerBuf.put(marker, 0, n * FLOATS)
            markerBuf.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, markerVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * STRIDE, markerBuf, GLES20.GL_STREAM_DRAW)
            bindAttribs()
            pixelMatrix.fill(0f)
            pixelMatrix[0] = 2f / width; pixelMatrix[5] = 2f / height; pixelMatrix[10] = 1f
            pixelMatrix[12] = -1f; pixelMatrix[13] = -1f; pixelMatrix[15] = 1f
            GLES20.glUniformMatrix4fv(uMvp, 1, false, pixelMatrix, 0)
            GLES20.glUniform1f(uHw, 0f)
            if (dest > 0) {
                GLES20.glUniform4fv(uColor, 1, DEST_COLOR, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, dest)
            }
            GLES20.glUniform4fv(uColor, 1, CAR_COLOR, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, dest, n - dest)
        }

        private fun project(m: FloatArray, x: Double, y: Double): Pair<Float, Float> {
            val nx = m[0] * x + m[4] * y + m[12]
            val ny = m[1] * x + m[5] * y + m[13]
            return ((nx + 1.0) / 2.0 * width).toFloat() to ((ny + 1.0) / 2.0 * height).toFloat()
        }

        private fun bindAttribs() {
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, STRIDE, 0)
            GLES20.glEnableVertexAttribArray(aSide)
            GLES20.glVertexAttribPointer(aSide, 2, GLES20.GL_FLOAT, false, STRIDE, 16)
        }

        // ── Background geometry ─────────────────────────────────────────────

        private inner class BuiltRoute(val version: Int, val lat: Double, val lon: Double, val data: FloatArray)

        private inner class Built(val lat: Double, val lon: Double, val data: FloatArray, val start: IntArray, val count: IntArray)

        private fun requestRoads(lat: Double, lon: Double) {
            val graph = NavigationEngine.roads ?: return
            building = true
            builder.execute {
                val built = runCatching { buildRoads(graph, lat, lon) }.getOrNull()
                builtRoads = built
                building = false
                wake()
            }
        }

        private fun buildRoads(g: RoutingGraph, lat: Double, lon: Double): Built {
            val lx = 111_320.0 * cos(Math.toRadians(lat))
            val tiers = Array(TIERS) { FloatList() }
            g.forEachSegmentNear(lat, lon, MAJOR_RADIUS_M) { u ->
                val tier = TIER_OF_CLASS.getOrElse(g.roadClass(u)) { 1 }
                val (pLat, pLon) = g.polyline(u)
                val near = tier >= 2 || run {
                    val dy = (pLat[0] / 1e6 - lat) * my
                    val dx = (pLon[0] / 1e6 - lon) * lx
                    dx * dx + dy * dy < MINOR_RADIUS_M * MINOR_RADIUS_M
                }
                if (!near) return@forEachSegmentNear
                val out = tiers[tier]
                for (i in 0 until pLat.size - 1) {
                    segment(
                        out,
                        ((pLon[i] / 1e6 - lon) * lx).toFloat(), ((pLat[i] / 1e6 - lat) * my).toFloat(),
                        ((pLon[i + 1] / 1e6 - lon) * lx).toFloat(), ((pLat[i + 1] / 1e6 - lat) * my).toFloat(),
                    )
                }
            }
            val total = tiers.sumOf { it.size }
            val data = FloatArray(total)
            val start = IntArray(TIERS)
            val count = IntArray(TIERS)
            var at = 0
            for (t in 0 until TIERS) {
                start[t] = at / FLOATS
                count[t] = tiers[t].size / FLOATS
                System.arraycopy(tiers[t].items, 0, data, at, tiers[t].size)
                at += tiers[t].size
            }
            return Built(lat, lon, data, start, count)
        }

        private fun uploadRoads(b: Built) {
            builtRoads = null
            // Move the origin to the new geometry, keeping camera and car where they are.
            val newMx = 111_320.0 * cos(Math.toRadians(b.lat))
            fun reX(x: Double) = ((originLon + x / mx) - b.lon) * newMx
            fun reY(y: Double) = ((originLat + y / my) - b.lat) * my
            camX = reX(camX); camY = reY(camY); carX = reX(carX); carY = reY(carY)
            originLat = b.lat; originLon = b.lon; mx = newMx
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, roadVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, b.data.size * 4, floats(b.data), GLES20.GL_STATIC_DRAW)
            tierStart = b.start; tierCount = b.count
            haveRoads = true
            routeShown = -1 // the route is in the old origin's metres
        }

        private fun requestRoute() {
            val version = routeVersion
            val r = route
            val direct = directTo
            val oLat = originLat; val oLon = originLon; val lx = mx
            builtRoute = null
            routeShown = version
            builder.execute {
                val out = FloatList()
                if (r != null) {
                    for (i in 0 until r.lat.size - 1) segment(
                        out,
                        ((r.lon[i] - oLon) * lx).toFloat(), ((r.lat[i] - oLat) * my).toFloat(),
                        ((r.lon[i + 1] - oLon) * lx).toFloat(), ((r.lat[i + 1] - oLat) * my).toFloat(),
                    )
                } else if (direct != null) {
                    segment(
                        out,
                        ((direct[1] - oLon) * lx).toFloat(), ((direct[0] - oLat) * my).toFloat(),
                        ((direct[3] - oLon) * lx).toFloat(), ((direct[2] - oLat) * my).toFloat(),
                    )
                }
                builtRoute = BuiltRoute(version, oLat, oLon, out.items.copyOf(out.size))
                wake()
            }
        }

        private fun uploadRoute(p: BuiltRoute) {
            builtRoute = null
            // Built for an older route or around an older origin: ask again.
            if (p.version != routeVersion || p.lat != originLat || p.lon != originLon) { routeShown = -1; return }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, routeVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, p.data.size * 4, floats(p.data), GLES20.GL_STATIC_DRAW)
            routeCount = p.data.size / FLOATS
        }
    }

    private class FloatList {
        var items = FloatArray(4096)
        var size = 0
        fun add6(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
            if (size + 6 > items.size) items = items.copyOf(items.size * 2)
            items[size] = a; items[size + 1] = b; items[size + 2] = c
            items[size + 3] = d; items[size + 4] = e; items[size + 5] = f
            size += 6
        }
    }

    companion object {
        private const val FLOATS = 6
        private const val STRIDE = FLOATS * 4
        private const val TIERS = 5
        private const val MAJOR_RADIUS_M = 4000.0
        private const val MINOR_RADIUS_M = 1600.0
        private const val REBUILD_M = 1100.0
        private const val FIX_LATENCY_MS = 500L
        private const val ANCHOR_Y = -0.42
        private const val ROUTE_WIDTH_PX = 10.0

        // Road classes from the routing data -> draw tier (0 thin ... 4 thick).
        // motorway, link, trunk, link, primary, link, secondary, link, tertiary, link,
        // unclassified, residential, living_street, service, road
        private val TIER_OF_CLASS = intArrayOf(4, 4, 4, 4, 3, 3, 3, 3, 2, 2, 1, 1, 0, 0, 1)
        private val TIER_WIDTH_PX = doubleArrayOf(2.0, 3.0, 5.0, 7.0, 9.0)

        private fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 0xFF) / 255f, ((hex shr 8) and 0xFF) / 255f, (hex and 0xFF) / 255f, 1f)
        private val BG = rgb(0x070D12)
        private val TIER_COLOR = arrayOf(rgb(0x1F3340), rgb(0x34505F), rgb(0x6F8C99), rgb(0xD9A441), rgb(0xE8743B))
        private val ROUTE_COLOR = rgb(0x3EE6FF)
        private val CAR_COLOR = rgb(0xFFFFFF)
        private val DEST_COLOR = rgb(0xFF3B6B)

        /** One segment as two triangles; the shader widens it (square ends fill the bends). */
        private fun segment(out: FloatList, x0: Float, y0: Float, x1: Float, y1: Float) {
            val len = hypot(x1 - x0, y1 - y0)
            if (len < 0.01f) return
            val dx = (x1 - x0) / len
            val dy = (y1 - y0) / len
            out.add6(x0, y0, dx, dy, -1f, -1f)
            out.add6(x0, y0, dx, dy, 1f, -1f)
            out.add6(x1, y1, dx, dy, 1f, 1f)
            out.add6(x0, y0, dx, dy, -1f, -1f)
            out.add6(x1, y1, dx, dy, 1f, 1f)
            out.add6(x1, y1, dx, dy, -1f, 1f)
        }

        private fun angle(a: Float, b: Float) = ((b - a) % 360f + 540f) % 360f - 180f
        private fun turnToward(from: Float, to: Float, k: Float) = ((from + angle(from, to) * k) % 360f + 360f) % 360f

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private const val VERTEX = """
            uniform mat4 u_mvp;
            uniform float u_hw;
            attribute vec4 a_pd;
            attribute vec2 a_side;
            void main() {
                vec2 dir = a_pd.zw;
                vec2 nrm = vec2(-dir.y, dir.x);
                vec2 p = a_pd.xy + (nrm * a_side.x + dir * a_side.y) * u_hw;
                gl_Position = u_mvp * vec4(p, 0.0, 1.0);
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            uniform vec4 u_color;
            void main() { gl_FragColor = u_color; }
        """

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src)
                GLES20.glCompileShader(it)
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }
}
