package com.openlauncher.app.ui.screen

import android.graphics.ImageFormat
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

/** Whether MMI's reverse view is on screen; opened from Settings, or on reverse gear when enabled. */
internal object ReverseView {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("mmi_car", android.content.Context.MODE_PRIVATE)
    fun auto(c: android.content.Context) = prefs(c).getBoolean("mmi_reverse_auto", false)
    fun setAuto(c: android.content.Context, on: Boolean) = prefs(c).edit().putBoolean("mmi_reverse_auto", on).apply()
}

/** One detected object, as fractions of the camera frame. */
private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String?)

/**
 * MMI's reverse view, Audi style: the rear camera full screen with fixed guide
 * lines (the Astra's CAN box sends no steering angle, so they don't bend), the
 * parking-sensor distance from MMI Car Core, and on-device object detection
 * (people, cars, obstacles) a few times a second. The camera is the same one
 * the vendor reverse screen opens through the standard Android camera API.
 */
@Composable
internal fun MmiReversePage(onClose: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var boxes by remember { mutableStateOf<List<Box>>(emptyList()) }
    var frame by remember { mutableStateOf(1280 to 720) }
    val car by com.openlauncher.app.util.CarCore.data.collectAsStateWithLifecycle()

    val detector = remember {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
    }
    val camRef = remember { arrayOfNulls<Camera>(1) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { camRef[0]?.setPreviewCallbackWithBuffer(null); camRef[0]?.stopPreview(); camRef[0]?.release() }
            camRef[0] = null
            detector.close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            val cam = openRearCamera() ?: run {
                                error = "The camera is in use by the car's own reverse screen. MMI's view takes over once that is switched off (laptop session)."
                                return
                            }
                            camRef[0] = cam
                            runCatching {
                                val p = cam.parameters
                                // Sharpest picture the unit offers, up to full HD.
                                val size = p.supportedPreviewSizes
                                    .filter { it.width <= 1920 && it.height <= 1088 }
                                    .maxByOrNull { it.width * it.height }
                                if (size != null) p.setPreviewSize(size.width, size.height)
                                p.previewFormat = ImageFormat.NV21
                                cam.parameters = p
                                val s = cam.parameters.previewSize
                                frame = s.width to s.height
                                val buf = ByteArray(s.width * s.height * 3 / 2)
                                cam.addCallbackBuffer(buf)
                                var busyUntil = 0L
                                cam.setPreviewCallbackWithBuffer { data, c ->
                                    val now = System.currentTimeMillis()
                                    if (now < busyUntil) { c.addCallbackBuffer(data); return@setPreviewCallbackWithBuffer }
                                    // ~4 detections a second, fewer when the chip is busy (PerfGovernor).
                                    busyUntil = now + com.openlauncher.app.util.PerfGovernor.detectionIntervalMs
                                    val image = InputImage.fromByteArray(data, s.width, s.height, 0, InputImage.IMAGE_FORMAT_NV21)
                                    detector.process(image)
                                        .addOnSuccessListener { list ->
                                            boxes = list.map { o ->
                                                val r = o.boundingBox
                                                Box(
                                                    r.left / s.width.toFloat(), r.top / s.height.toFloat(),
                                                    r.right / s.width.toFloat(), r.bottom / s.height.toFloat(),
                                                    o.labels.maxByOrNull { it.confidence }?.text,
                                                )
                                            }
                                        }
                                        .addOnCompleteListener { c.addCallbackBuffer(data) }
                                }
                                cam.setPreviewDisplay(h)
                                cam.startPreview()
                            }.onFailure { e -> error = "Camera error: ${e.message}" }
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {}
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Guide lines, objects and parking-sensor distance.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Fixed perspective path of the car's width behind it.
            val nearL = w * 0.18f; val nearR = w * 0.82f; val farL = w * 0.38f; val farR = w * 0.62f
            val nearY = h * 0.98f; val farY = h * 0.42f
            fun at(t: Float) = Triple(nearL + (farL - nearL) * t, nearR + (farR - nearR) * t, nearY + (farY - nearY) * t)
            val lane = Path().apply {
                moveTo(nearL, nearY); lineTo(farL, farY); lineTo(farR, farY); lineTo(nearR, nearY); close()
            }
            drawPath(lane, Color(0x332E7CF6))
            drawLine(Color(0xFFFF9D2E), Offset(nearL, nearY), Offset(farL, farY), 5.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFFFF9D2E), Offset(nearR, nearY), Offset(farR, farY), 5.dp.toPx(), StrokeCap.Round)
            // Distance marks: red ~0.5 m, yellow ~1 m, green ~2 m.
            listOf(0.18f to Color(0xFFE53935), 0.42f to Color(0xFFFFC93C), 0.75f to Color(0xFF4CD964)).forEach { (t, c) ->
                val (l, r, y) = at(t)
                drawLine(c, Offset(l, y), Offset(r, y), 4.dp.toPx(), StrokeCap.Round)
            }
            // Detected objects: framed in cyan, red when inside the car's path near the bumper.
            boxes.forEach { b ->
                val inPath = b.bottom > 0.55f && b.right > 0.25f && b.left < 0.75f
                drawRect(
                    if (inPath) Color(0xFFE53935) else MmiCyan,
                    topLeft = Offset(b.left * w, b.top * h), size = Size((b.right - b.left) * w, (b.bottom - b.top) * h),
                    style = Stroke(3.dp.toPx()),
                )
            }
        }

        // Parking-sensor readout (MMI Car Core), Audi-style panel on the right.
        car.rearCm?.let { cm ->
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC0B0C0E)).padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (cm < 30) "STOP" else "%.1f m".format(cm / 100f), color = if (cm < 50) Color(0xFFE53935) else MmiInk, fontFamily = MmiFont, fontSize = 34.sp)
                Text("rear", color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp)
            }
        }

        val danger = boxes.any { it.bottom > 0.55f && it.right > 0.25f && it.left < 0.75f }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xE6101215)).border(1.dp, if (danger) Color(0xFFE53935) else Color(0xFF3A3F46), RoundedCornerShape(6.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠", color = Color(0xFFE53935), fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (danger) "Object behind the car" else "Look! Safe to move?",
                color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp,
            )
        }

        Box(
            Modifier.align(Alignment.TopStart).padding(16.dp).size(56.dp).clip(RoundedCornerShape(28.dp))
                .background(Color(0x99000000)).pressable(onClose),
            contentAlignment = Alignment.Center,
        ) { MmiLineIcon(MmiLine.CLOSE, MmiInk, Modifier.size(22.dp)) }

        error?.let {
            Text(
                it, color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center).padding(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xE6101215)).padding(20.dp),
            )
        }
    }
}

/**
 * The rear camera: the vendor's code uses id 0, 1 or 4 depending on the unit's
 * camera setting; MMI tries the back-facing one first, then every id.
 */
private fun openRearCamera(): Camera? {
    val n = runCatching { Camera.getNumberOfCameras() }.getOrDefault(0)
    val order = (0 until maxOf(n, 5)).sortedBy { id ->
        runCatching { Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }.facing != Camera.CameraInfo.CAMERA_FACING_BACK }.getOrDefault(true)
    }
    for (id in order) runCatching { return Camera.open(id) }
    return null
}
