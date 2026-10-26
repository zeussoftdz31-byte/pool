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
 * Engineered for Rock-Solid Stability:
 * 1. Automatic Table Felt Color Sampling (supports any felt color: Green, Blue, Red, Purple).
 * 2. Strict True Cue Ball Locking:
 *    - Rejects striped balls 9-15 via equatorial color saturation checks.
 *    - Rejects touch gestures, UI dialogs, and avatars via 360° surrounding felt verification.
 *    - Spatial hysteresis locking: locks onto the cue ball position while aiming and ignores touches.
 * 3. Dual-Axis Cue Stick & Guideline Angle Tracking:
 *    - Detects the forward aim guideline (flanked by felt at ±4px).
 *    - Detects the backward cue stick (flanked by felt at ±8px).
 *    - Rejects false rotations when score is below confidence threshold.
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

    // State Tracking & Anchored Locking
    private var lockedCueX = -1f
    private var lockedCueY = -1f
    private var isLockedOnCue = false
    private var lockFrames = 0
    private var lostFrames = 0

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
            return lum > 185f && sat < 0.16f
        }

        val ballRadiusEst = width * 0.0145f // ~14px in 960x540
        val piF = Math.PI.toFloat()

        /**
         * Validates that candidate center (cx, cy) is a genuine white cue ball:
         * 1. Equatorial perimeter has no colored stripes (rejects balls 9 to 15).
         * 2. Outer perimeter (1.45R) is surrounded by table felt (rejects UI buttons, avatars, touches).
         */
        fun isTrueCueBall(cx: Float, cy: Float, radius: Float): Boolean {
            // A. Check for absence of colored stripe
            var coloredPoints = 0
            val perimeterSamples = 16
            for (i in 0 until perimeterSamples) {
                val angle = i * (2f * piF) / perimeterSamples
                val px = (cx + radius * 0.85f * cos(angle.toDouble()).toFloat()).toInt()
                val py = (cy + radius * 0.85f * sin(angle.toDouble()).toFloat()).toInt()
                val rgb = getPixelRgb(px, py)
                val sat = getSaturation(rgb)
                if (sat > 0.28f) {
                    coloredPoints++
                }
            }
            if (coloredPoints > 1) return false

            // B. Check that object is resting on table felt (rejects menus, chalk, chat, fingers)
            var feltSurroundPoints = 0
            val surroundSamples = 12
            for (i in 0 until surroundSamples) {
                val angle = i * (2f * piF) / surroundSamples
                val px = (cx + radius * 1.45f * cos(angle.toDouble()).toFloat()).toInt()
                val py = (cy + radius * 1.45f * sin(angle.toDouble()).toFloat()).toInt()
                if (isFeltPixel(getPixelRgb(px, py))) {
                    feltSurroundPoints++
                }
            }

            return feltSurroundPoints >= 7
        }

        // ==========================================
        // 2. Locate & Lock Cue Ball with Hysteresis
        // ==========================================
        var detectedCueX = -1f
        var detectedCueY = -1f

        if (isLockedOnCue && lockedCueX > 0 && lockedCueY > 0) {
            // Search in tight neighborhood (+-22px) around locked cue ball
            val searchRadius = (ballRadiusEst * 1.6f).toInt()
            var sumX = 0f
            var sumY = 0f
            var count = 0

            val startX = (lockedCueX - searchRadius).toInt().coerceIn(tableStartX, tableEndX)
            val endX = (lockedCueX + searchRadius).toInt().coerceIn(tableStartX, tableEndX)
            val startY = (lockedCueY - searchRadius).toInt().coerceIn(tableStartY, tableEndY)
            val endY = (lockedCueY + searchRadius).toInt().coerceIn(tableStartY, tableEndY)

            for (y in startY..endY step 2) {
                for (x in startX..endX step 2) {
                    if (isWhitePixel(getPixelRgb(x, y))) {
                        sumX += x
                        sumY += y
                        count++
                    }
                }
            }

            if (count in 14..280) {
                val candidateX = sumX / count
                val candidateY = sumY / count
                if (isTrueCueBall(candidateX, candidateY, ballRadiusEst)) {
                    // Smooth micro-anchor to eliminate jitter
                    lockedCueX += (candidateX - lockedCueX) * 0.18f
                    lockedCueY += (candidateY - lockedCueY) * 0.18f
                    detectedCueX = lockedCueX
                    detectedCueY = lockedCueY
                    lostFrames = 0
                    lockFrames++
                } else {
                    lostFrames++
                }
            } else {
                lostFrames++
            }

            // Keep lock for up to 14 missing frames (e.g. finger/cue momentarily over cue ball)
            if (lostFrames in 1..14) {
                detectedCueX = lockedCueX
                detectedCueY = lockedCueY
            } else if (lostFrames > 14) {
                isLockedOnCue = false
                lockFrames = 0
            }
        }

        // Full table scan if not locked
        if (!isLockedOnCue) {
            var bestScore = -1f
            var bestCenterX = -1f
            var bestCenterY = -1f

            for (y in tableStartY until tableEndY step 4) {
                for (x in tableStartX until tableEndX step 4) {
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
                lockedCueX = bestCenterX
                lockedCueY = bestCenterY
                detectedCueX = bestCenterX
                detectedCueY = bestCenterY
                isLockedOnCue = true
                lostFrames = 0
                lockFrames = 1
            }
        }

        // If no cue ball can be locked, do not emit spurious data
        if (detectedCueX <= 0 || detectedCueY <= 0) {
            return
        }

        // ==========================================
        // 3. Dual-Axis Cue Stick & Guideline Angle Tracking (360° Scan)
        // ==========================================
        // Forward guideline is at angle theta (flanked by felt at +-4px)
        // Cue stick is at angle theta + pi (flanked by felt at +-8px)
        val angleSamples = 120 // 3-degree resolution
        var bestAngleScore = -1f
        var detectedAngle = smoothAngle

        for (i in 0 until angleSamples) {
            val theta = i * (2f * piF) / angleSamples
            val cosT = cos(theta.toDouble()).toFloat()
            val sinT = sin(theta.toDouble()).toFloat()

            // Perpendicular unit vector (-sin, cos)
            val perpX = -sinT
            val perpY = cosT

            var forwardGuidelineScore = 0f
            var backwardCueStickScore = 0f

            // A. Forward Guideline Check: 3 radial points along forward ray
            for (step in 1..3) {
                val distFwd = ballRadiusEst * (1.8f + step * 0.9f)
                val fx = (detectedCueX + distFwd * cosT).toInt()
                val fy = (detectedCueY + distFwd * sinT).toInt()

                val centerRgb = getPixelRgb(fx, fy)
                val dr = abs(centerRgb[0] - feltR)
                val dg = abs(centerRgb[1] - feltG)
                val db = abs(centerRgb[2] - feltB)
                val centerContrast = dr + dg + db

                if (isWhitePixel(centerRgb) || centerContrast > 65) {
                    // Guideline must be thin: verify felt flanks at +-4px
                    val leftRgb = getPixelRgb((fx - 4 * perpX).toInt(), (fy - 4 * perpY).toInt())
                    val rightRgb = getPixelRgb((fx + 4 * perpX).toInt(), (fy + 4 * perpY).toInt())
                    if (isFeltPixel(leftRgb) && isFeltPixel(rightRgb)) {
                        forwardGuidelineScore += 1.0f
                    }
                }
            }

            // B. Backward Cue Stick Check: 4 radial points behind cue ball
            for (step in 1..4) {
                val distBack = ballRadiusEst * (2.8f + step * 1.2f)
                val bx = (detectedCueX - distBack * cosT).toInt()
                val by = (detectedCueY - distBack * sinT).toInt()

                val stickCenterRgb = getPixelRgb(bx, by)
                // Cue stick body is non-felt
                if (!isFeltPixel(stickCenterRgb)) {
                    // Cue stick is flanked by felt at +-8px
                    val leftRgb = getPixelRgb((bx - 8 * perpX).toInt(), (by - 8 * perpY).toInt())
                    val rightRgb = getPixelRgb((bx + 8 * perpX).toInt(), (by + 8 * perpY).toInt())
                    if (isFeltPixel(leftRgb) && isFeltPixel(rightRgb)) {
                        backwardCueStickScore += 1.2f
                    }
                }
            }

            val totalScore = forwardGuidelineScore + backwardCueStickScore
            if (totalScore > bestAngleScore) {
                bestAngleScore = totalScore
                detectedAngle = theta
            }
        }

        // Strict Confidence Threshold: Ignore false touch motions if no cue stick / line is seen
        if (bestAngleScore >= 3.2f) {
            var diff = detectedAngle - smoothAngle
            while (diff < -piF) diff += (2f * piF)
            while (diff > piF) diff -= (2f * piF)
            smoothAngle += diff * 0.35f
            while (smoothAngle < -piF) smoothAngle += (2f * piF)
            while (smoothAngle > piF) smoothAngle -= (2f * piF)
        }

        // ==========================================
        // 4. March Forward along Aim Ray to Find Target Ball
        // ==========================================
        var targetBallX: Float? = null
        var targetBallY: Float? = null
        val rayCos = cos(smoothAngle.toDouble()).toFloat()
        val raySin = sin(smoothAngle.toDouble()).toFloat()

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
            0.85f // Default power if idle
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
