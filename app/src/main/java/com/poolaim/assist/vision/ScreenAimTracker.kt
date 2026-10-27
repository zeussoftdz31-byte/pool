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
         * Checks whether candidate center (cx, cy) resembles a white pool ball:
         * 1. Rejects colored striped balls (balls 9-15) via equatorial saturation.
         * 2. Rejects out-of-table elements via felt neighborhood check.
         */
        fun isCandidateBallSurface(cx: Float, cy: Float, radius: Float): Boolean {
            var coloredPoints = 0
            val perimeterSamples = 14
            for (i in 0 until perimeterSamples) {
                val angle = i * (2f * piF) / perimeterSamples
                val px = (cx + radius * 0.82f * cos(angle.toDouble()).toFloat()).toInt()
                val py = (cy + radius * 0.82f * sin(angle.toDouble()).toFloat()).toInt()
                val rgb = getPixelRgb(px, py)
                if (getSaturation(rgb) > 0.28f) {
                    coloredPoints++
                }
            }
            if (coloredPoints > 2) return false

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
            return feltSurroundPoints >= 6
        }

        /**
         * CUE STICK DETECTION & SUB-PIXEL ANCHOR:
         * Uses the physical cue stick (المضرب) as the absolute guide to confirm the Cue Ball.
         *
         * Key Properties:
         * 1. Only the true Cue Ball has a cue stick positioned behind it pointing at its center.
         * 2. The cue stick shaft forms a distinct non-felt strip flanked by table felt on both sides (+-8px).
         * 3. The forward guideline extends ahead of the ball flanked by felt (+-4px).
         * 4. Sub-pixel precision: Refines the cue ball center along and across the stick axis.
         */
        class CueStickEvidence(
            val hasCueStick: Boolean,
            val angleRad: Float,
            val confidence: Float,
            val refinedCenterX: Float,
            val refinedCenterY: Float
        )

        fun inspectCueStickAndGuideline(
            cx: Float,
            cy: Float,
            radius: Float
        ): CueStickEvidence {
            var bestScore = -1f
            var bestAngle = smoothAngle
            val angleSteps = 120 // 3-degree resolution (360 degrees)

            for (i in 0 until angleSteps) {
                val theta = i * (2f * piF) / angleSteps
                val cosT = cos(theta.toDouble()).toFloat()
                val sinT = sin(theta.toDouble()).toFloat()
                val perpX = -sinT
                val perpY = cosT

                var stickScore = 0f
                var guideScore = 0f

                // 1. Backward Cue Stick Check (along direction theta + pi)
                // Tests 5 radial points along the cue stick shaft behind the ball
                for (s in 1..5) {
                    val distBack = radius * (1.7f + s * 1.15f)
                    val bx = (cx - distBack * cosT).toInt()
                    val by = (cy - distBack * sinT).toInt()

                    if (bx in tableStartX..tableEndX && by in tableStartY..tableEndY) {
                        val centerRgb = getPixelRgb(bx, by)
                        // Cue stick body is non-felt
                        if (!isFeltPixel(centerRgb)) {
                            // Cue stick is flanked by table felt on left & right (+-8px)
                            val leftRgb = getPixelRgb((bx - 8 * perpX).toInt(), (by - 8 * perpY).toInt())
                            val rightRgb = getPixelRgb((bx + 8 * perpX).toInt(), (by + 8 * perpY).toInt())
                            if (isFeltPixel(leftRgb) && isFeltPixel(rightRgb)) {
                                stickScore += 1.5f
                            }
                        }
                    }
                }

                // 2. Forward Guideline Check (along direction theta ahead of the ball)
                for (g in 1..3) {
                    val distFwd = radius * (1.6f + g * 0.9f)
                    val fx = (cx + distFwd * cosT).toInt()
                    val fy = (cy + distFwd * sinT).toInt()

                    if (fx in tableStartX..tableEndX && fy in tableStartY..tableEndY) {
                        val centerRgb = getPixelRgb(fx, fy)
                        val dr = abs(centerRgb[0] - feltR)
                        val dg = abs(centerRgb[1] - feltG)
                        val db = abs(centerRgb[2] - feltB)
                        val centerContrast = dr + dg + db
                        if (isWhitePixel(centerRgb) || centerContrast > 60) {
                            val leftRgb = getPixelRgb((fx - 4 * perpX).toInt(), (fy - 4 * perpY).toInt())
                            val rightRgb = getPixelRgb((fx + 4 * perpX).toInt(), (fy + 4 * perpY).toInt())
                            if (isFeltPixel(leftRgb) && isFeltPixel(rightRgb)) {
                                guideScore += 1.0f
                            }
                        }
                    }
                }

                val totalScore = stickScore + guideScore
                if (totalScore > bestScore) {
                    bestScore = totalScore
                    bestAngle = theta
                }
            }

            val hasCueStick = bestScore >= 2.8f

            // Refine Cue Ball center with sub-pixel precision along the stick vector
            var refinedX = cx
            var refinedY = cy
            if (hasCueStick) {
                val cosA = cos(bestAngle.toDouble()).toFloat()
                val sinA = sin(bestAngle.toDouble()).toFloat()
                val perpX = -sinA
                val perpY = cosA

                val maxSpan = (radius * 1.35f).toInt()
                var minT = 0f
                var maxT = 0f
                var foundBack = false
                var foundFront = false

                // Step along cue stick axis
                for (t in -maxSpan..maxSpan) {
                    val px = (cx + t * cosA).toInt()
                    val py = (cy + t * sinA).toInt()
                    if (isWhitePixel(getPixelRgb(px, py))) {
                        if (!foundBack) {
                            minT = t.toFloat()
                            foundBack = true
                        }
                        maxT = t.toFloat()
                        foundFront = true
                    }
                }

                // Step perpendicular to cue stick axis
                var minS = 0f
                var maxS = 0f
                var foundLeft = false
                var foundRight = false

                for (s in -maxSpan..maxSpan) {
                    val px = (cx + s * perpX).toInt()
                    val py = (cy + s * perpY).toInt()
                    if (isWhitePixel(getPixelRgb(px, py))) {
                        if (!foundLeft) {
                            minS = s.toFloat()
                            foundLeft = true
                        }
                        maxS = s.toFloat()
                        foundRight = true
                    }
                }

                if (foundBack && foundFront && foundLeft && foundRight) {
                    val deltaT = (minT + maxT) / 2f
                    val deltaS = (minS + maxS) / 2f
                    refinedX = cx + deltaT * cosA + deltaS * perpX
                    refinedY = cy + deltaT * sinA + deltaS * perpY
                }
            }

            return CueStickEvidence(
                hasCueStick = hasCueStick,
                angleRad = bestAngle,
                confidence = bestScore,
                refinedCenterX = refinedX,
                refinedCenterY = refinedY
            )
        }

        // ========================================================
        // 2. Locate & Lock True Cue Ball using Cue Stick Anchor
        // ========================================================
        var detectedCueX = -1f
        var detectedCueY = -1f

        if (isLockedOnCue && lockedCueX > 0 && lockedCueY > 0) {
            // Already locked: directly verify cue stick & guideline at current locked position
            val evidence = inspectCueStickAndGuideline(lockedCueX, lockedCueY, ballRadiusEst)

            if (evidence.hasCueStick) {
                // Rock-solid smooth anchor: adapt micro-shifts without jitter
                lockedCueX += (evidence.refinedCenterX - lockedCueX) * 0.20f
                lockedCueY += (evidence.refinedCenterY - lockedCueY) * 0.20f
                detectedCueX = lockedCueX
                detectedCueY = lockedCueY

                var diff = evidence.angleRad - smoothAngle
                while (diff < -piF) diff += (2f * piF)
                while (diff > piF) diff -= (2f * piF)
                smoothAngle += diff * 0.40f
                while (smoothAngle < -piF) smoothAngle += (2f * piF)
                while (smoothAngle > piF) smoothAngle -= (2f * piF)

                lostFrames = 0
                lockFrames++
            } else {
                // Check tight local neighborhood (+-24px) in case cue ball shifted slightly
                var foundNearby = false
                val searchOffsets = arrayOf(
                    Pair(-12f, 0f), Pair(12f, 0f), Pair(0f, -12f), Pair(0f, 12f),
                    Pair(-20f, 0f), Pair(20f, 0f), Pair(0f, -20f), Pair(0f, 20f)
                )
                for (offset in searchOffsets) {
                    val nearEvidence = inspectCueStickAndGuideline(
                        lockedCueX + offset.first,
                        lockedCueY + offset.second,
                        ballRadiusEst
                    )
                    if (nearEvidence.hasCueStick) {
                        lockedCueX += (nearEvidence.refinedCenterX - lockedCueX) * 0.35f
                        lockedCueY += (nearEvidence.refinedCenterY - lockedCueY) * 0.35f
                        detectedCueX = lockedCueX
                        detectedCueY = lockedCueY

                        var diff = nearEvidence.angleRad - smoothAngle
                        while (diff < -piF) diff += (2f * piF)
                        while (diff > piF) diff -= (2f * piF)
                        smoothAngle += diff * 0.40f
                        while (smoothAngle < -piF) smoothAngle += (2f * piF)
                        while (smoothAngle > piF) smoothAngle -= (2f * piF)

                        lostFrames = 0
                        lockFrames++
                        foundNearby = true
                        break
                    }
                }

                if (!foundNearby) {
                    lostFrames++
                    // Maintain stable position during shot release or brief occlusion (up to 16 frames)
                    if (lostFrames in 1..16) {
                        detectedCueX = lockedCueX
                        detectedCueY = lockedCueY
                    } else if (lostFrames > 16) {
                        isLockedOnCue = false
                        lockFrames = 0
                    }
                }
            }
        }

        // Full table scan if cue ball is not currently locked
        if (!isLockedOnCue) {
            val candidates = mutableListOf<Pair<Float, Float>>()

            // Scan table playfield for clusters of white ball pixels
            for (y in tableStartY until tableEndY step 6) {
                for (x in tableStartX until tableEndX step 6) {
                    val rgb = getPixelRgb(x, y)
                    if (isWhitePixel(rgb)) {
                        var clusterCount = 0
                        var cSumX = 0f
                        var cSumY = 0f

                        for (dy in -8..8 step 2) {
                            for (dx in -8..8 step 2) {
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

                        if (clusterCount in 14..320) {
                            val cx = cSumX / clusterCount
                            val cy = cSumY / clusterCount

                            // Avoid duplicate candidates within 22px
                            val isDuplicate = candidates.any { (kX, kY) ->
                                hypot(cx - kX, cy - kY) < ballRadiusEst * 1.5f
                            }
                            if (!isDuplicate && isCandidateBallSurface(cx, cy, ballRadiusEst)) {
                                candidates.add(Pair(cx, cy))
                            }
                        }
                    }
                }
            }

            // CRITICAL STEP: Evaluate each candidate with Cue Stick Evidence
            // Only the true Cue Ball has a physical cue stick pointing at its center!
            var bestCandidateScore = -1f
            var bestCandidateEvidence: CueStickEvidence? = null

            for (cand in candidates) {
                val evidence = inspectCueStickAndGuideline(cand.first, cand.second, ballRadiusEst)
                if (evidence.hasCueStick && evidence.confidence > bestCandidateScore) {
                    bestCandidateScore = evidence.confidence
                    bestCandidateEvidence = evidence
                }
            }

            if (bestCandidateEvidence != null && bestCandidateScore >= 2.8f) {
                lockedCueX = bestCandidateEvidence.refinedCenterX
                lockedCueY = bestCandidateEvidence.refinedCenterY
                detectedCueX = lockedCueX
                detectedCueY = lockedCueY
                smoothAngle = bestCandidateEvidence.angleRad
                isLockedOnCue = true
                lostFrames = 0
                lockFrames = 1
            }
        }

        // If no confirmed cue ball is found, do not emit spurious lines
        if (detectedCueX <= 0 || detectedCueY <= 0) {
            return
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
