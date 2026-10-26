package com.poolaim.assist.vision

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import com.poolaim.assist.physics.Vector2D
import kotlin.math.*

/**
 * Ultra-fast, high-precision computer vision engine for 8 Ball Pool auto-aim tracking.
 *
 * Automatic Features:
 * 1. Automatic Table Felt Color Sampling (supports any table felt: Green, Blue, Purple, Red).
 * 2. True White Cue Ball Identification (strictly rejects striped balls 9-15 via perimeter saturation checks).
 * 3. 360-Degree Radial Guideline & Cue Stick Angle Tracking with silky smooth interpolation.
 * 4. Ray-march Target Ball Detection (identifies target ball along aim path).
 * 5. Left Rail Power Gauge Metering (tracks cue stick pull-down 0% to 100%).
 */
class ScreenAimTracker(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val listener: OnAimDataListener
) {

    interface OnAimDataListener {
        fun onAimUpdated(
            cueBallPos: Vector2D,
            aimAngleRad: Float,
            powerPercent: Float,
            targetBallPos: Vector2D?
        )
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Screen dimensions (Forced Landscape for 8 Ball Pool)
    private var screenWidth = 2400
    private var screenHeight = 1080
    private val captureWidth = 960
    private val captureHeight = 540

    // Cached & smoothed state
    private var lastCueX = -1f
    private var lastCueY = -1f
    private var smoothAngle = 0f
    private var smoothPower = 0.85f
    private var isProcessingFrame = false

    var isAutoTrackingEnabled = true

    fun start() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)

        // 8 Ball Pool runs in Landscape: width is the longer axis, height is shorter
        screenWidth = maxOf(size.x, size.y)
        screenHeight = minOf(size.x, size.y)

        handlerThread = HandlerThread("PoolAimVisionWorker").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            if (isProcessingFrame || !isAutoTrackingEnabled) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessingFrame = true

            backgroundHandler?.post {
                try {
                    processFrame(image)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                    isProcessingFrame = false
                }
            }
        }, backgroundHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PoolAimScreenReader",
            captureWidth,
            captureHeight,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )
    }

    private fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        fun getPixelRgb(x: Int, y: Int): IntArray {
            if (x < 0 || x >= width || y < 0 || y >= height) return intArrayOf(0, 0, 0)
            val offset = y * rowStride + x * pixelStride
            val r = buffer.get(offset).toInt() and 0xFF
            val g = buffer.get(offset + 1).toInt() and 0xFF
            val b = buffer.get(offset + 2).toInt() and 0xFF
            return intArrayOf(r, g, b)
        }

        fun getSaturation(rgb: IntArray): Float {
            val r = rgb[0]
            val g = rgb[1]
            val b = rgb[2]
            val max = maxOf(r, maxOf(g, b)).toFloat()
            val min = minOf(r, minOf(g, b)).toFloat()
            return if (max == 0f) 0f else (max - min) / max
        }

        fun getLuminance(rgb: IntArray): Float {
            return (rgb[0] * 0.299f + rgb[1] * 0.587f + rgb[2] * 0.114f)
        }

        // Active pool table bounds in landscape (960x540)
        val tableStartX = (width * 0.12f).toInt()
        val tableEndX = (width * 0.88f).toInt()
        val tableStartY = (height * 0.12f).toInt()
        val tableEndY = (height * 0.88f).toInt()

        // 1. Dynamic Table Felt Color Sampling (sample 9 points across table center)
        var sumFeltR = 0
        var sumFeltG = 0
        var sumFeltB = 0
        var feltSampleCount = 0

        val cx1 = (width * 0.35f).toInt()
        val cx2 = (width * 0.50f).toInt()
        val cx3 = (width * 0.65f).toInt()
        val cy1 = (height * 0.40f).toInt()
        val cy2 = (height * 0.50f).toInt()
        val cy3 = (height * 0.60f).toInt()

        val samplePoints = arrayOf(
            Pair(cx1, cy1), Pair(cx2, cy1), Pair(cx3, cy1),
            Pair(cx1, cy2), Pair(cx2, cy2), Pair(cx3, cy2),
            Pair(cx1, cy3), Pair(cx2, cy3), Pair(cx3, cy3)
        )

        for (pt in samplePoints) {
            val rgb = getPixelRgb(pt.first, pt.second)
            sumFeltR += rgb[0]
            sumFeltG += rgb[1]
            sumFeltB += rgb[2]
            feltSampleCount++
        }

        val feltR = sumFeltR / feltSampleCount
        val feltG = sumFeltG / feltSampleCount
        val feltB = sumFeltB / feltSampleCount

        fun isFeltPixel(rgb: IntArray): Boolean {
            val dr = abs(rgb[0] - feltR)
            val dg = abs(rgb[1] - feltG)
            val db = abs(rgb[2] - feltB)
            return (dr + dg + db) < 65
        }

        fun isWhitePixel(rgb: IntArray): Boolean {
            val lum = getLuminance(rgb)
            val sat = getSaturation(rgb)
            return lum > 190f && sat < 0.15f
        }

        fun isTrueCueBall(cx: Float, cy: Float, radius: Float): Boolean {
            var coloredPoints = 0
            val perimeterSamples = 16
            val piF = Math.PI.toFloat()

            for (i in 0 until perimeterSamples) {
                val angle = i * (2f * piF) / perimeterSamples
                val px = (cx + radius * 0.88f * cos(angle.toDouble()).toFloat()).toInt()
                val py = (cy + radius * 0.88f * sin(angle.toDouble()).toFloat()).toInt()
                val rgb = getPixelRgb(px, py)
                val sat = getSaturation(rgb)

                if (sat > 0.30f) {
                    coloredPoints++
                }
            }

            return coloredPoints == 0
        }

        val ballRadiusEst = width * 0.0145f // ~14px in 960x540

        // ==========================================
        // 2. Locate White Cue Ball (Stationary Turn Detection)
        // ==========================================
        var detectedCueX = -1f
        var detectedCueY = -1f

        // Fast check on cached cue ball location
        if (lastCueX > 0 && lastCueY > 0) {
            val searchRadius = (ballRadiusEst * 2.2f).toInt()
            var sumX = 0f
            var sumY = 0f
            var count = 0

            val startX = (lastCueX - searchRadius).toInt().coerceIn(tableStartX, tableEndX)
            val endX = (lastCueX + searchRadius).toInt().coerceIn(tableStartX, tableEndX)
            val startY = (lastCueY - searchRadius).toInt().coerceIn(tableStartY, tableEndY)
            val endY = (lastCueY + searchRadius).toInt().coerceIn(tableStartY, tableEndY)

            for (y in startY..endY step 2) {
                for (x in startX..endX step 2) {
                    if (isWhitePixel(getPixelRgb(x, y))) {
                        sumX += x
                        sumY += y
                        count++
                    }
                }
            }

            if (count in 15..280) {
                val candidateX = sumX / count
                val candidateY = sumY / count
                if (isTrueCueBall(candidateX, candidateY, ballRadiusEst)) {
                    detectedCueX = candidateX
                    detectedCueY = candidateY
                }
            }
        }

        // Full table scan if fast cache search missed
        if (detectedCueX < 0) {
            var bestScore = -1f
            var bestCenterX = -1f
            var bestCenterY = -1f

            for (y in tableStartY until tableEndY step 5) {
                for (x in tableStartX until tableEndX step 5) {
                    val rgb = getPixelRgb(x, y)
                    if (isWhitePixel(rgb)) {
                        var clusterCount = 0
                        var cSumX = 0f
                        var cSumY = 0f

                        for (dy in -7..7 step 2) {
                            for (dx in -7..7 step 2) {
                                val nx = x + dx
                                val ny = y + dy
                                val nRgb = getPixelRgb(nx, ny)
                                if (isWhitePixel(nRgb)) {
                                    clusterCount++
                                    cSumX += nx
                                    cSumY += ny
                                }
                            }
                        }

                        if (clusterCount in 16..260) {
                            val cx = cSumX / clusterCount
                            val cy = cSumY / clusterCount

                            if (isTrueCueBall(cx, cy, ballRadiusEst)) {
                                val score = clusterCount.toFloat()
                                if (score > bestScore) {
                                    bestScore = score
                                    bestCenterX = cx
                                    bestCenterY = cy
                                }
                            }
                        }
                    }
                }
            }

            if (bestScore > 0) {
                detectedCueX = bestCenterX
                detectedCueY = bestCenterY
            }
        }

        if (detectedCueX > 0 && detectedCueY > 0) {
            lastCueX = detectedCueX
            lastCueY = detectedCueY
        } else if (lastCueX > 0) {
            detectedCueX = lastCueX
            detectedCueY = lastCueY
        } else {
            return
        }

        // ==========================================
        // 3. Detect In-Game Aim Guideline & Cue Stick Angle (360° Scan)
        // ==========================================
        val ringRadiusInner = ballRadiusEst * 2.2f
        val ringRadiusOuter = ballRadiusEst * 4.2f

        var maxLineContrast = -1f
        var detectedAngle = smoothAngle
        val angleSamples = 120 // 3-degree resolution

        for (i in 0 until angleSamples) {
            val angle = (i * (2f * Math.PI.toFloat()) / angleSamples)
            val cosA = cos(angle)
            val sinA = sin(angle)

            var lineContrastSum = 0f
            val sampleSteps = 5
            for (s in 1..sampleSteps) {
                val r = ringRadiusInner + (ringRadiusOuter - ringRadiusInner) * (s.toFloat() / sampleSteps)
                val sx = (detectedCueX + r * cosA).toInt()
                val sy = (detectedCueY + r * sinA).toInt()
                val rgb = getPixelRgb(sx, sy)

                // Aim guideline is high contrast against table felt
                val dr = abs(rgb[0] - feltR)
                val dg = abs(rgb[1] - feltG)
                val db = abs(rgb[2] - feltB)
                val contrast = dr + dg + db

                // Guideline is bright white or glowing laser
                if (isWhitePixel(rgb) || contrast > 75) {
                    lineContrastSum += contrast
                }
            }

            if (lineContrastSum > maxLineContrast) {
                maxLineContrast = lineContrastSum
                detectedAngle = angle
            }
        }

        // Silky smooth angular interpolation (handles wrap-around)
        if (maxLineContrast > 80f) {
            val piF = Math.PI.toFloat()
            var diff = detectedAngle - smoothAngle
            while (diff < -piF) diff += (2f * piF)
            while (diff > piF) diff -= (2f * piF)
            smoothAngle += diff * 0.40f
        }

        // ==========================================
        // 4. March Forward along Aim Ray to Find Target Ball
        // ==========================================
        var targetBallX: Float? = null
        var targetBallY: Float? = null
        val rayCos = cos(smoothAngle)
        val raySin = sin(smoothAngle)

        val maxMarchDistance = (width * 0.72f).toInt()
        var march = (ballRadiusEst * 2.5f).toInt()

        while (march < maxMarchDistance) {
            val mx = (detectedCueX + march * rayCos).toInt()
            val my = (detectedCueY + march * raySin).toInt()

            if (mx !in tableStartX..tableEndX || my !in tableStartY..tableEndY) break

            val rgb = getPixelRgb(mx, my)
            if (!isFeltPixel(rgb) && !isWhitePixel(rgb)) {
                // Confirm solid or striped target ball
                var nonFeltCount = 0
                for (check in -4..4 step 2) {
                    val cx = (mx + check * rayCos).toInt()
                    val cy = (my + check * raySin).toInt()
                    if (!isFeltPixel(getPixelRgb(cx, cy))) nonFeltCount++
                }

                if (nonFeltCount >= 3) {
                    targetBallX = mx + (ballRadiusEst * 0.85f * rayCos)
                    targetBallY = my + (ballRadiusEst * 0.85f * raySin)
                    break
                }
            }
            march += 3
        }

        // ==========================================
        // 5. Track Shot Power on Left Rail Slider
        // ==========================================
        val sliderXStart = (width * 0.02f).toInt()
        val sliderXEnd = (width * 0.07f).toInt()
        val sliderYStart = (height * 0.20f).toInt()
        val sliderYEnd = (height * 0.80f).toInt()

        var pulledPixels = 0
        var totalSliderSamples = 0

        for (y in sliderYStart until sliderYEnd step 3) {
            for (x in sliderXStart until sliderXEnd step 2) {
                val rgb = getPixelRgb(x, y)
                totalSliderSamples++
                val r = rgb[0]
                val g = rgb[1]
                val b = rgb[2]
                // Detect cue stick grip or slider fill color
                if ((r > 150 && g > 100 && b < 80) || (r > 175 && g < 110)) {
                    pulledPixels++
                }
            }
        }

        val rawPowerRatio = if (totalSliderSamples > 0) {
            pulledPixels.toFloat() / (totalSliderSamples * 0.35f)
        } else {
            0f
        }

        val targetPower = if (rawPowerRatio > 0.12f) {
            rawPowerRatio.coerceIn(0.20f, 1.0f)
        } else {
            0.85f // Default full power if idle
        }
        smoothPower += (targetPower - smoothPower) * 0.25f

        // ==========================================
        // 6. Project Coordinates to Real Screen Display
        // ==========================================
        val scaleX = screenWidth.toFloat() / width
        val scaleY = screenHeight.toFloat() / height

        val realCueX = detectedCueX * scaleX
        val realCueY = detectedCueY * scaleY
        val realTargetX = targetBallX?.let { it * scaleX }
        val realTargetY = targetBallY?.let { it * scaleY }

        val realTargetPos = if (realTargetX != null && realTargetY != null) {
            Vector2D(realTargetX, realTargetY)
        } else {
            null
        }

        listener.onAimUpdated(
            cueBallPos = Vector2D(realCueX, realCueY),
            aimAngleRad = smoothAngle,
            powerPercent = smoothPower,
            targetBallPos = realTargetPos
        )
    }



    fun stop() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            handlerThread?.quitSafely()
            handlerThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
