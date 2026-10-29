package com.mecasoft.aimingmaster.vision

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.mecasoft.aimingmaster.data.AimPreferences
import com.mecasoft.aimingmaster.engine.AimingMasterEngine
import com.mecasoft.aimingmaster.engine.TableGeometry
import com.mecasoft.aimingmaster.engine.Vector2D
import com.mecasoft.aimingmaster.model.AimModel
import kotlin.math.*

class AimVisionDetector(
    private val prefs: AimPreferences,
    private val onTrajectoryComputed: (AimModel) -> Unit
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            processCurrentFrame()
            handler.postDelayed(this, 250L) // 4 FPS exact Aiming Master loop
        }
    }

    fun start(mediaProjection: MediaProjection, width: Int, height: Int, densityDpi: Int) {
        if (isRunning) return
        isRunning = true

        val captureW = maxOf(width, height).coerceIn(800, 1920)
        val captureH = minOf(width, height).coerceIn(450, 1080)

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AimingMasterCapture",
            captureW, captureH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        handler.post(captureRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(captureRunnable)
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processCurrentFrame() {
        val reader = imageReader ?: return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val imgW = image.width
            val imgH = image.height

            val table = TableGeometry.compute(imgW.toFloat(), imgH.toFloat(), prefs.tableZoom, prefs.tableOffsetX, prefs.tableOffsetY)
            val r = table.playRect
            val step = 6

            val startX = maxOf(0, r.left.toInt())
            val endX = minOf(imgW - 1, r.right.toInt())
            val startY = maxOf(0, r.top.toInt())
            val endY = minOf(imgH - 1, r.bottom.toInt())

            var whiteCount = 0
            var sumX = 0f
            var sumY = 0f

            for (y in startY until endY step step) {
                val rowOffset = y * rowStride
                for (x in startX until endX step step) {
                    val index = rowOffset + x * pixelStride
                    if (index + 2 >= buffer.remaining()) continue

                    val red = buffer.get(index).toInt() and 0xFF
                    val green = buffer.get(index + 1).toInt() and 0xFF
                    val blue = buffer.get(index + 2).toInt() and 0xFF

                    val lum = 0.299f * red + 0.587f * green + 0.114f * blue
                    val maxDiff = maxOf(abs(red - green), abs(green - blue), abs(red - blue))

                    if (lum > 225 && maxDiff < 30) {
                        sumX += x
                        sumY += y
                        whiteCount++
                    }
                }
            }

            if (whiteCount in 10..400) {
                val detectedCueX = sumX / whiteCount
                val detectedCueY = sumY / whiteCount
                val cueBall = Vector2D(detectedCueX, detectedCueY)
                val dir = Vector2D.fromAngle(0.4f)
                val targetBall = cueBall + dir * (table.ballRadius * 6f)

                val engine = AimingMasterEngine(table)
                val model = engine.computeTrajectory(
                    cueBall = cueBall,
                    aimAngleRad = dir.angle(),
                    targetBall = targetBall,
                    enableCushionShots = prefs.isCushionShotsEnabled,
                    enableThreeLines = prefs.isThreeLinesEnabled,
                    cueForceLevel = prefs.cueForceLevel
                )
                onTrajectoryComputed(model)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image?.close()
        }
    }
}
