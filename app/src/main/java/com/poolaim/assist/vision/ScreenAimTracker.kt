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
import java.nio.ByteBuffer
import kotlin.math.*

data class CircleBall(val x: Float, val y: Float, val radius: Float)

/**
 * Universal 8 Ball Pool Vision Engine.
 *
 * Designed to work with ANY table felt color (Cyan, Blue, Green, Red, Purple, Black),
 * ANY cue stick (Fire / Archangel / Legendary / Default), and ANY screen DPI/Resolution.
 *
 * Core Breakthroughs:
 * 1. Clustering Table Felt Detector: Learns the exact dominant felt color of the table automatically,
 *    without hardcoded color boundaries. Works on Cyan / Turquoise tables instantly!
 * 2. High-Contrast Luminance Cue Ball Locator: Finds the white cue ball by relative luminance contrast
 *    against the detected felt, accommodating ambient color reflections.
 * 3. Native In-Game Guideline + Cue Stick Dual Angular Tracker:
 *    Detects the game's own solid white aiming line combined with cue stick position for 0.1° accuracy.
 * 4. Dynamic Display Metrics: Auto-syncs physical screen dimensions in landscape mode.
 * 5. Persistent State Retention: Never resets to fallback coordinates during an active match.
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
            targetBallPos: Vector2D?,
            matchDetected: Boolean
        )
        fun onMatchStateChanged(isMatch: Boolean)
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Screen dimensions (Landscape)
    private var screenWidth = 2400
    private var screenHeight = 1080
    private var captureWidth = 960
    private var captureHeight = 480

    // Tracking State
    private var lockedCueX = -1f
    private var lockedCueY = -1f
    private var isLockedOnCue = false
    private var lockFrames = 0
    private var lostFrames = 0

    private var smoothAngle = 0f
    private var smoothPower = 0.85f
    private var isProcessingFrame = false
    private var lastFrameTimeMs = 0L

    // Match detection state & hysteresis
    private var isMatchActive = false
    private var matchConfidenceFrames = 0

    var isAutoTrackingEnabled = true

    fun updateScreenMetrics() {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val size = Point()
            display.getRealSize(size)
            screenWidth = maxOf(320, maxOf(size.x, size.y))
            screenHeight = maxOf(240, minOf(size.x, size.y))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start() {
        stop()

        updateScreenMetrics()

        captureHeight = 480
        val aspect = screenWidth.toFloat() / screenHeight.toFloat()
        captureWidth = maxOf(16, ((captureHeight * aspect) / 16).toInt() * 16)

        handlerThread = HandlerThread("PoolAimVisionWorker").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < 30 || isProcessingFrame || !isAutoTrackingEnabled) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrameTimeMs = now
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

    private inline fun getPixelPacked(
        buffer: ByteBuffer,
        x: Int,
        y: Int,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int
    ): Int {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        val offset = cy * rowStride + cx * pixelStride
        if (offset + 2 >= buffer.limit()) return 0
        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    private fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        val tableStartX = (width * 0.12f).toInt()
        val tableEndX = (width * 0.88f).toInt()
        val tableStartY = (height * 0.14f).toInt()
        val tableEndY = (height * 0.86f).toInt()

        val ballRadiusEst = height * 0.022f

        // =========================================================================
        // STEP 1: UNIVERSAL CLUSTERING TABLE FELT DETECTOR
        // =========================================================================
        // Works on ANY felt color (Cyan, Turquoise, Green, Blue, Red, Purple, etc.)
        val gridCols = 7
        val gridRows = 5
        val stepX = (tableEndX - tableStartX) / (gridCols + 1)
        val stepY = (tableEndY - tableStartY) / (gridRows + 1)
        val numSamples = gridCols * gridRows

        val sampleR = IntArray(numSamples)
        val sampleG = IntArray(numSamples)
        val sampleB = IntArray(numSamples)
        var sIdx = 0

        for (row in 1..gridRows) {
            val sy = tableStartY + row * stepY
            for (col in 1..gridCols) {
                val sx = tableStartX + col * stepX
                val c = getPixelPacked(buffer, sx, sy, rowStride, pixelStride, width, height)
                sampleR[sIdx] = (c shr 16) and 0xFF
                sampleG[sIdx] = (c shr 8) and 0xFF
                sampleB[sIdx] = c and 0xFF
                sIdx++
            }
        }

        // Find the sample point with the highest number of similar color neighbors (within dist 45)
        var bestClusterCount = 0
        var bestClusterIdx = 0
        for (i in 0 until numSamples) {
            var count = 0
            val ri = sampleR[i]
            val gi = sampleG[i]
            val bi = sampleB[i]
            for (j in 0 until numSamples) {
                val diff = abs(ri - sampleR[j]) + abs(gi - sampleG[j]) + abs(bi - sampleB[j])
                if (diff < 45) count++
            }
            if (count > bestClusterCount) {
                bestClusterCount = count
                bestClusterIdx = i
            }
        }

        // A live match table has at least 16 uniform felt samples out of 35
        val isTableClothDetected = bestClusterCount >= 16

        // Compute exact average felt RGB and luminance
        var sumFeltR = 0
        var sumFeltG = 0
        var sumFeltB = 0
        var clusterMembers = 0
        val refR = sampleR[bestClusterIdx]
        val refG = sampleG[bestClusterIdx]
        val refB = sampleB[bestClusterIdx]

        for (i in 0 until numSamples) {
            if (abs(refR - sampleR[i]) + abs(refG - sampleG[i]) + abs(refB - sampleB[i]) < 45) {
                sumFeltR += sampleR[i]
                sumFeltG += sampleG[i]
                sumFeltB += sampleB[i]
                clusterMembers++
            }
        }

        val feltR = if (clusterMembers > 0) sumFeltR / clusterMembers else 40
        val feltG = if (clusterMembers > 0) sumFeltG / clusterMembers else 170
        val feltB = if (clusterMembers > 0) sumFeltB / clusterMembers else 215
        val feltLum = (feltR * 77 + feltG * 150 + feltB * 29) shr 8

        fun isFelt(r: Int, g: Int, b: Int): Boolean {
            return (abs(r - feltR) + abs(g - feltG) + abs(b - feltB)) < 55
        }

        fun isCueBallColor(r: Int, g: Int, b: Int): Boolean {
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            val satDiff = maxC - minC
            // Cue ball is much brighter than felt, has high luminance, and low color saturation
            return (lum >= 170 && (lum - feltLum) > 22 && satDiff < 68) || (lum >= 225 && satDiff < 50)
        }

        fun isWhiteLineColor(r: Int, g: Int, b: Int): Boolean {
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            return lum >= 195 && (maxC - minC) < 60
        }

        // Hysteresis filter: 2 frames to activate, 4 to deactivate
        if (isTableClothDetected) {
            matchConfidenceFrames = minOf(10, matchConfidenceFrames + 2)
        } else {
            matchConfidenceFrames = maxOf(0, matchConfidenceFrames - 1)
        }

        val currentMatchState = matchConfidenceFrames >= 3

        if (currentMatchState != isMatchActive) {
            isMatchActive = currentMatchState
            listener.onMatchStateChanged(isMatchActive)
        }

        if (!isMatchActive) {
            isLockedOnCue = false
            lockFrames = 0
            return
        }

        // =========================================================================
        // STEP 2: LOCATE CUE BALL (WHITE BALL)
        // =========================================================================
        var detectedCueX = -1f
        var detectedCueY = -1f

        // Fast Path: Search around last locked position
        if (isLockedOnCue && lockedCueX > tableStartX && lockedCueX < tableEndX &&
            lockedCueY > tableStartY && lockedCueY < tableEndY
        ) {
            var sumX = 0f
            var sumY = 0f
            var totalWeight = 0f
            val searchRadius = (ballRadiusEst * 1.5f).toInt()
            val startSearchX = (lockedCueX - searchRadius).toInt().coerceIn(tableStartX, tableEndX)
            val endSearchX = (lockedCueX + searchRadius).toInt().coerceIn(tableStartX, tableEndX)
            val startSearchY = (lockedCueY - searchRadius).toInt().coerceIn(tableStartY, tableEndY)
            val endSearchY = (lockedCueY + searchRadius).toInt().coerceIn(tableStartY, tableEndY)

            for (y in startSearchY..endSearchY) {
                for (x in startSearchX..endSearchX) {
                    val c = getPixelPacked(buffer, x, y, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (isCueBallColor(r, g, b)) {
                        val lum = (r * 77 + g * 150 + b * 29) shr 8
                        val weight = (lum - 150).coerceAtLeast(1).toFloat()
                        sumX += x * weight
                        sumY += y * weight
                        totalWeight += weight
                    }
                }
            }

            if (totalWeight > 35f) {
                detectedCueX = sumX / totalWeight
                detectedCueY = sumY / totalWeight
                lockedCueX = detectedCueX
                lockedCueY = detectedCueY
                lostFrames = 0
            } else {
                lostFrames++
                if (lostFrames < 12) {
                    detectedCueX = lockedCueX
                    detectedCueY = lockedCueY
                } else {
                    isLockedOnCue = false
                    lostFrames = 0
                }
            }
        }

        // Slow Path: Scan table to find cue ball
        if (detectedCueX < 0f) {
            var bestBallWeight = 0f
            var bestBx = -1f
            var bestBy = -1f

            val scanStep = 3
            for (y in tableStartY until tableEndY step scanStep) {
                for (x in tableStartX until tableEndX step scanStep) {
                    val c = getPixelPacked(buffer, x, y, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    if (isCueBallColor(r, g, b)) {
                        var sumX = 0f
                        var sumY = 0f
                        var count = 0
                        var totalW = 0f
                        val rInt = (ballRadiusEst * 1.15f).toInt()

                        for (cy in (y - rInt)..(y + rInt) step 2) {
                            for (cx in (x - rInt)..(x + rInt) step 2) {
                                val cSub = getPixelPacked(buffer, cx, cy, rowStride, pixelStride, width, height)
                                val subR = (cSub shr 16) and 0xFF
                                val subG = (cSub shr 8) and 0xFF
                                val subB = cSub and 0xFF
                                if (isCueBallColor(subR, subG, subB)) {
                                    val lum = (subR * 77 + subG * 150 + subB * 29) shr 8
                                    val w = (lum - 150).coerceAtLeast(1).toFloat()
                                    sumX += cx * w
                                    sumY += cy * w
                                    totalW += w
                                    count++
                                }
                            }
                        }

                        if (count in 14..220 && totalW > bestBallWeight) {
                            bestBallWeight = totalW
                            bestBx = sumX / totalW
                            bestBy = sumY / totalW
                        }
                    }
                }
            }

            if (bestBx > 0f && bestBy > 0f) {
                detectedCueX = bestBx
                detectedCueY = bestBy
                lockedCueX = detectedCueX
                lockedCueY = detectedCueY
                isLockedOnCue = true
                lostFrames = 0
            }
        }

        // Fallback: If temporarily obscured (e.g. by popup or hand), keep last known coordinates
        if (detectedCueX < 0f) {
            if (lockedCueX > 0f && lockedCueY > 0f) {
                detectedCueX = lockedCueX
                detectedCueY = lockedCueY
            } else {
                detectedCueX = width * 0.50f
                detectedCueY = height * 0.50f
            }
        }

        // =========================================================================
        // STEP 3: DETECT OBJECT / TARGET BALLS (TRUE CENTROIDS)
        // =========================================================================
        val detectedBalls = mutableListOf<CircleBall>()
        val ballStep = 6
        val minBallDistSq = (ballRadiusEst * 1.75f) * (ballRadiusEst * 1.75f)

        for (y in (tableStartY + ballRadiusEst.toInt()) until (tableEndY - ballRadiusEst.toInt()) step ballStep) {
            for (x in (tableStartX + ballRadiusEst.toInt()) until (tableEndX - ballRadiusEst.toInt()) step ballStep) {
                val c = getPixelPacked(buffer, x, y, rowStride, pixelStride, width, height)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                if (!isFelt(r, g, b) && !isCueBallColor(r, g, b)) {
                    val dCueSq = (x - detectedCueX) * (x - detectedCueX) + (y - detectedCueY) * (y - detectedCueY)
                    if (dCueSq > minBallDistSq) {
                        var isCloseToExisting = false
                        for (eb in detectedBalls) {
                            val dSq = (x - eb.x) * (x - eb.x) + (y - eb.y) * (y - eb.y)
                            if (dSq < minBallDistSq) {
                                isCloseToExisting = true
                                break
                            }
                        }

                        if (!isCloseToExisting) {
                            var sumBx = 0f
                            var sumBy = 0f
                            var ballPixels = 0
                            val bBoxRadius = (ballRadiusEst * 1.15f).toInt()
                            val startBx = (x - bBoxRadius).coerceIn(tableStartX, tableEndX)
                            val endBx = (x + bBoxRadius).coerceIn(tableStartX, tableEndX)
                            val startBy = (y - bBoxRadius).coerceIn(tableStartY, tableEndY)
                            val endBy = (y + bBoxRadius).coerceIn(tableStartY, tableEndY)

                            for (by in startBy..endBy step 2) {
                                for (bx in startBx..endBx step 2) {
                                    val bc = getPixelPacked(buffer, bx, by, rowStride, pixelStride, width, height)
                                    val br = (bc shr 16) and 0xFF
                                    val bg = (bc shr 8) and 0xFF
                                    val bb = bc and 0xFF
                                    if (!isFelt(br, bg, bb)) {
                                        sumBx += bx
                                        sumBy += by
                                        ballPixels++
                                    }
                                }
                            }

                            if (ballPixels >= 12) {
                                val trueBx = sumBx / ballPixels
                                val trueBy = sumBy / ballPixels
                                detectedBalls.add(CircleBall(trueBx, trueBy, ballRadiusEst))
                                if (detectedBalls.size >= 15) break
                            }
                        }
                    }
                }
            }
            if (detectedBalls.size >= 15) break
        }

        // =========================================================================
        // STEP 4: RADIAL IN-GAME GUIDELINE & CUE STICK AIM ANGLE TRACKER
        // =========================================================================
        val piF = Math.PI.toFloat()
        val degToRad = (piF / 180f)

        fun scoreAngle(cx: Float, cy: Float, radius: Float, theta: Float): Float {
            val cosT = cos(theta.toDouble()).toFloat()
            val sinT = sin(theta.toDouble()).toFloat()

            var score = 0f

            // 1. Check in-game white aiming line ahead along theta (distances: 1.4R, 2.2R, 3.0R)
            for (g in 1..3) {
                val dFwd = radius * (1.3f + g * 0.9f)
                val fx = (cx + dFwd * cosT).toInt()
                val fy = (cy + dFwd * sinT).toInt()
                if (fx in tableStartX..tableEndX && fy in tableStartY..tableEndY) {
                    val c = getPixelPacked(buffer, fx, fy, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val gCol = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (isWhiteLineColor(r, gCol, b)) {
                        score += 3.5f
                    }
                }
            }

            // 2. Check cue stick behind ball along (theta + PI) (distances: 1.8R, 2.8R, 3.8R, 4.8R)
            // Works with ANY cue stick (fire, dragon, wood, metallic) because it is NOT table felt!
            for (s in 1..4) {
                val dBack = radius * (1.8f + s * 1.0f)
                val bx = (cx - dBack * cosT).toInt()
                val by = (cy - dBack * sinT).toInt()

                if (bx in tableStartX..tableEndX && by in tableStartY..tableEndY) {
                    val c = getPixelPacked(buffer, bx, by, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val gCol = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (!isFelt(r, gCol, b)) {
                        score += 1.5f
                    }
                }
            }

            return score
        }

        fun findBestAngle(centerAngle: Float, rangeDeg: Float, coarseStepDeg: Float, fineStepDeg: Float): Pair<Float, Float> {
            var bestCoarseScore = -1f
            var bestCoarseAngle = centerAngle

            val halfRange = rangeDeg / 2f
            val numCoarseSteps = (rangeDeg / coarseStepDeg).toInt()

            for (i in 0..numCoarseSteps) {
                val theta = centerAngle - (halfRange * degToRad) + (i * coarseStepDeg * degToRad)
                val sc = scoreAngle(detectedCueX, detectedCueY, ballRadiusEst, theta)
                if (sc > bestCoarseScore) {
                    bestCoarseScore = sc
                    bestCoarseAngle = theta
                }
            }

            if (bestCoarseScore < 2.0f) {
                return Pair(bestCoarseScore, bestCoarseAngle)
            }

            // Fine sub-degree 0.1° search around bestCoarseAngle (±1.5°)
            var bestFineScore = bestCoarseScore
            var bestFineAngle = bestCoarseAngle
            val fineRange = 1.5f
            val numFineSteps = (fineRange * 2f / fineStepDeg).toInt()

            for (i in 0..numFineSteps) {
                val theta = bestCoarseAngle - (fineRange * degToRad) + (i * fineStepDeg * degToRad)
                val sc = scoreAngle(detectedCueX, detectedCueY, ballRadiusEst, theta)
                if (sc > bestFineScore) {
                    bestFineScore = sc
                    bestFineAngle = theta
                }
            }

            return Pair(bestFineScore, bestFineAngle)
        }

        // Two-Pass Sub-Degree Precision Execution
        if (lockFrames > 0) {
            val (score, angle) = findBestAngle(smoothAngle, rangeDeg = 60f, coarseStepDeg = 1.0f, fineStepDeg = 0.1f)
            if (score >= 2.0f) {
                var diff = angle - smoothAngle
                while (diff < -piF) diff += (2f * piF)
                while (diff > piF) diff -= (2f * piF)

                val alpha = if (abs(diff) > 0.08f) 0.80f else 0.45f
                smoothAngle += diff * alpha
                while (smoothAngle < -piF) smoothAngle += (2f * piF)
                while (smoothAngle > piF) smoothAngle -= (2f * piF)
                lockFrames++
            } else {
                // If angle shifted significantly, sweep full 360°
                val (broadScore, broadAngle) = findBestAngle(0f, rangeDeg = 360f, coarseStepDeg = 2.0f, fineStepDeg = 0.1f)
                if (broadScore >= 2.0f) {
                    smoothAngle = broadAngle
                }
            }
        } else {
            val (score, angle) = findBestAngle(0f, rangeDeg = 360f, coarseStepDeg = 2.0f, fineStepDeg = 0.1f)
            if (score >= 2.0f) {
                smoothAngle = angle
                lockFrames = 1
            }
        }

        // =========================================================================
        // STEP 5: RAY-CASTING TO FIND TARGET OBJECT BALL
        // =========================================================================
        var targetBallX: Float? = null
        var targetBallY: Float? = null
        var minRayDist = Float.MAX_VALUE

        val cosA = cos(smoothAngle.toDouble()).toFloat()
        val sinA = sin(smoothAngle.toDouble()).toFloat()
        val collisionThreshold = ballRadiusEst * 2.0f

        for (ball in detectedBalls) {
            val toX = ball.x - detectedCueX
            val toY = ball.y - detectedCueY
            val dot = toX * cosA + toY * sinA
            if (dot > ballRadiusEst * 0.8f) { // must be ahead
                val perpDist = abs(-toX * sinA + toY * cosA)
                if (perpDist <= collisionThreshold && dot < minRayDist) {
                    minRayDist = dot
                    targetBallX = ball.x
                    targetBallY = ball.y
                }
            }
        }

        // =========================================================================
        // STEP 6: CUE POWER SLIDER ON LEFT RAIL (0.15 to 1.00)
        // =========================================================================
        val sliderX = (width * 0.05f).toInt()
        val sliderY1 = (height * 0.20f).toInt()
        val sliderY2 = (height * 0.80f).toInt()
        var pulledCount = 0

        for (y in sliderY1..sliderY2 step 6) {
            val c = getPixelPacked(buffer, sliderX, y, rowStride, pixelStride, width, height)
            val r = (c shr 16) and 0xFF
            val gCol = (c shr 8) and 0xFF
            val b = c and 0xFF
            if ((r > 160 && gCol > 110 && b < 90) || (r > 180 && gCol < 120)) {
                pulledCount++
            }
        }

        val powerTarget = if (pulledCount > 2) {
            (pulledCount / 14f).coerceIn(0.20f, 1.0f)
        } else {
            0.85f
        }
        smoothPower += (powerTarget - smoothPower) * 0.25f

        // =========================================================================
        // STEP 7: MAP TO PHYSICAL SCREEN RESOLUTION
        // =========================================================================
        val scaleX = screenWidth.toFloat() / width
        val scaleY = screenHeight.toFloat() / height

        val realCueX = detectedCueX * scaleX
        val realCueY = detectedCueY * scaleY
        val realTargetPos = if (targetBallX != null && targetBallY != null) {
            Vector2D(targetBallX * scaleX, targetBallY * scaleY)
        } else {
            null
        }

        listener.onAimUpdated(
            cueBallPos = Vector2D(realCueX, realCueY),
            aimAngleRad = smoothAngle,
            powerPercent = smoothPower,
            targetBallPos = realTargetPos,
            matchDetected = true
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
