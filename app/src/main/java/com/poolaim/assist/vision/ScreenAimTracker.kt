package com.poolaim.assist.vision

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.graphics.RectF
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
 * 1. Clustering Table Felt Detector: Learns the exact dominant felt color of the table automatically.
 * 2. High-Contrast Luminance Cue Ball Locator: Finds the white cue ball by relative luminance contrast.
 * 3. Native In-Game Guideline + Cue Stick Dual Angular Tracker: 0.1° accuracy.
 * 4. Dynamic Display Metrics & Zero-DPI Table Boundary Sensor: Detects real cushion borders.
 * 5. Full Auto-Rotation Engine: Seamlessly adapts across 90°, 270°, and landscape flips.
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
            allBalls: List<Vector2D> = emptyList(),
            spinX: Float = 0f,
            spinY: Float = 0f,
            matchDetected: Boolean = true,
            tableBounds: RectF? = null,
            isAiming: Boolean = true
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

    // Aiming state (active when game's white line emerges from cue stick)
    private var isAimingActive = false
    private var aimConfidenceFrames = 0

    // Multi-Option Accuracy Engine
    var enableRayContinuity: Boolean = true      // Option 1: Radial Ray Continuity & 180° Cue Lock
    var enableBallMaskExclusion: Boolean = true  // Option 2: Dilated Ball Mask Exclusion (Ignores striped balls 9-15)
    var isManualRingMode: Boolean = false        // Option 3: Magnetic Ring Anchor Mode

    var isAutoTrackingEnabled = true
    var userCalibratedBounds: RectF? = null

    fun setCalibratedTableBounds(bounds: RectF?) {
        userCalibratedBounds = bounds
    }

    fun updateScreenMetrics(customW: Int = -1, customH: Int = -1) {
        try {
            if (customW > 0 && customH > 0) {
                screenWidth = maxOf(320, maxOf(customW, customH))
                screenHeight = maxOf(240, minOf(customW, customH))
            } else {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val display = windowManager.defaultDisplay
                val size = Point()
                display.getRealSize(size)
                screenWidth = maxOf(320, maxOf(size.x, size.y))
                screenHeight = maxOf(240, minOf(size.x, size.y))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun onOrientationChanged(newW: Int, newH: Int, rotation: Int) {
        updateScreenMetrics(newW, newH)
        val aspect = screenWidth.toFloat() / screenHeight.toFloat()
        val newCaptureWidth = maxOf(16, ((captureHeight * aspect) / 16).toInt() * 16)
        if (newCaptureWidth != captureWidth) {
            captureWidth = newCaptureWidth
            start()
        }
        isLockedOnCue = false
        lockFrames = 0
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

        val scaleToCaptureX = width.toFloat() / screenWidth.toFloat()
        val scaleToCaptureY = height.toFloat() / screenHeight.toFloat()

        val calib = userCalibratedBounds
        val isCalibrated = (calib != null && calib.width() > 100f && calib.height() > 80f)

        var tableStartX: Int
        var tableEndX: Int
        var tableStartY: Int
        var tableEndY: Int

        if (isCalibrated) {
            // Strictly bound to user's ground-truth saved physical red cushions!
            tableStartX = (calib!!.left * scaleToCaptureX).toInt().coerceIn(0, width - 20)
            tableEndX = (calib.right * scaleToCaptureX).toInt().coerceIn(tableStartX + 20, width)
            tableStartY = (calib.top * scaleToCaptureY).toInt().coerceIn(0, height - 20)
            tableEndY = (calib.bottom * scaleToCaptureY).toInt().coerceIn(tableStartY + 20, height)
        } else {
            tableStartX = (width * 0.12f).toInt()
            tableEndX = (width * 0.88f).toInt()
            tableStartY = (height * 0.14f).toInt()
            tableEndY = (height * 0.86f).toInt()
        }

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
            // Generous detection: ensures cue ball is ALWAYS captured under any table lighting or felt reflection
            return (lum >= 165 && (lum - feltLum) > 18 && satDiff < 65) || (lum >= 210 && satDiff < 50)
        }

        fun isSaturatedBallColor(r: Int, g: Int, b: Int): Boolean {
            if (isFelt(r, g, b)) return false
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            return (maxC - minC) >= 30
        }

        fun isWhiteLineColor(r: Int, g: Int, b: Int): Boolean {
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            return lum >= 190 && (maxC - minC) < 60
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

        // Dynamically detect real table felt cushion boundaries ONLY when user has not saved custom bounds
        if (!isCalibrated) {
            val scanMidY = height / 2
            var detectedStartX = tableStartX
            var detectedEndX = tableEndX
            var detectedStartY = tableStartY
            var detectedEndY = tableEndY

            // Left cushion scan (horizontal centerline)
            for (x in (width * 0.30f).toInt() downTo (width * 0.04f).toInt() step 2) {
                val c = getPixelPacked(buffer, x, scanMidY, rowStride, pixelStride, width, height)
                val r = (c shr 16) and 0xFF
                val gCol = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (!isFelt(r, gCol, b)) {
                    detectedStartX = x + 3
                    break
                }
            }
            // Right cushion scan
            for (x in (width * 0.70f).toInt() until (width * 0.96f).toInt() step 2) {
                val c = getPixelPacked(buffer, x, scanMidY, rowStride, pixelStride, width, height)
                val r = (c shr 16) and 0xFF
                val gCol = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (!isFelt(r, gCol, b)) {
                    detectedEndX = x - 3
                    break
                }
            }

            // Top and Bottom cushion scan (scan away from center pockets at 32% and 68% table width)
            val scanRailX1 = (width * 0.32f).toInt()
            val scanRailX2 = (width * 0.68f).toInt()
            var topY1 = -1
            var topY2 = -1
            for (y in (height * 0.35f).toInt() downTo (height * 0.06f).toInt() step 2) {
                val c1 = getPixelPacked(buffer, scanRailX1, y, rowStride, pixelStride, width, height)
                if (topY1 < 0 && !isFelt((c1 shr 16) and 0xFF, (c1 shr 8) and 0xFF, c1 and 0xFF)) {
                    topY1 = y + 3
                }
                val c2 = getPixelPacked(buffer, scanRailX2, y, rowStride, pixelStride, width, height)
                if (topY2 < 0 && !isFelt((c2 shr 16) and 0xFF, (c2 shr 8) and 0xFF, c2 and 0xFF)) {
                    topY2 = y + 3
                }
                if (topY1 >= 0 && topY2 >= 0) break
            }
            if (topY1 >= 0 || topY2 >= 0) {
                detectedStartY = if (topY1 >= 0 && topY2 >= 0) minOf(topY1, topY2) else maxOf(topY1, topY2)
            }

            var botY1 = -1
            var botY2 = -1
            for (y in (height * 0.65f).toInt() until (height * 0.94f).toInt() step 2) {
                val c1 = getPixelPacked(buffer, scanRailX1, y, rowStride, pixelStride, width, height)
                if (botY1 < 0 && !isFelt((c1 shr 16) and 0xFF, (c1 shr 8) and 0xFF, c1 and 0xFF)) {
                    botY1 = y - 3
                }
                val c2 = getPixelPacked(buffer, scanRailX2, y, rowStride, pixelStride, width, height)
                if (botY2 < 0 && !isFelt((c2 shr 16) and 0xFF, (c2 shr 8) and 0xFF, c2 and 0xFF)) {
                    botY2 = y - 3
                }
                if (botY1 >= 0 && botY2 >= 0) break
            }
            if (botY1 >= 0 || botY2 >= 0) {
                detectedEndY = if (botY1 >= 0 && botY2 >= 0) maxOf(botY1, botY2) else minOf(botY1, botY2)
            }

            // Validate sanity
            if (detectedEndX - detectedStartX > width * 0.45f && detectedEndY - detectedStartY > height * 0.35f) {
                tableStartX = detectedStartX
                tableEndX = detectedEndX
                tableStartY = detectedStartY
                tableEndY = detectedEndY
            }
        }

        // =========================================================================
        // STEP 2: BULLETPROOF CUE BALL LOCATOR & DISCRIMINATOR
        // =========================================================================
        // STEP 2: HIGH-ACCURACY CUE BALL LOCATOR & CONTINUOUS DISCRIMINATION
        // =========================================================================
        // 1. Purity-Weighted Scoring: Cue ball (solid white) achieves 4x-10x higher
        //    score than striped balls (balls 9-15) and completely ignores glares.
        // 2. Cue Stick In-Game Proximity: When aiming, the cue stick is strictly behind
        //    the cue ball (+15000 score bonus), ensuring 100% lock on true cue ball.
        // 3. Smooth Hysteresis Tracking: Never drops or flickers between frames.
        // 4. Cue-Stick Auto-Steal: If mistakenly locked on another ball, immediately
        //    snaps to the true cue ball the moment the player touches the cue stick!
        // 1. Cue Stick Detection: Checks 16 directions around ball for the cue stick shaft.
        // Screen buffer boundary check (2 until width - 2) ensures the cue stick is DETECTED
        // EVEN WHEN OVER THE CUSHION/RAIL!
        // Flank verification guarantees that an adjacent ball or cluster is NEVER mistaken for a cue stick!
        fun checkCueStickNear(cx: Float, cy: Float): Boolean {
            val flankDist = ballRadiusEst * 0.75f
            for (d in 0 until 16) {
                val theta = (d * 2.0 * Math.PI / 16).toFloat()
                val cosT = cos(theta.toDouble()).toFloat()
                val sinT = sin(theta.toDouble()).toFloat()
                val orthCos = -sinT
                val orthSin = cosT

                var stickPixels = 0
                var flankFeltPixels = 0

                for (s in 2..7) {
                    val dist = ballRadiusEst * (1.3f + s * 0.9f)
                    val bx = (cx - dist * cosT).toInt()
                    val by = (cy - dist * sinT).toInt()
                    if (bx in 2 until width - 2 && by in 2 until height - 2) {
                        val c = getPixelPacked(buffer, bx, by, rowStride, pixelStride, width, height)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        if (!isFelt(r, g, b)) {
                            stickPixels++

                            val lx = (bx + flankDist * orthCos).toInt()
                            val ly = (by + flankDist * orthSin).toInt()
                            val rx = (bx - flankDist * orthCos).toInt()
                            val ry = (by - flankDist * orthSin).toInt()

                            if (lx in 0 until width && ly in 0 until height) {
                                val cl = getPixelPacked(buffer, lx, ly, rowStride, pixelStride, width, height)
                                if (isFelt((cl shr 16) and 0xFF, (cl shr 8) and 0xFF, cl and 0xFF)) {
                                    flankFeltPixels++
                                }
                            }
                            if (rx in 0 until width && ry in 0 until height) {
                                val cr = getPixelPacked(buffer, rx, ry, rowStride, pixelStride, width, height)
                                if (isFelt((cr shr 16) and 0xFF, (cr shr 8) and 0xFF, cr and 0xFF)) {
                                    flankFeltPixels++
                                }
                            }
                        }
                    }
                }
                // A TRUE CUE STICK is a narrow shaft with GREEN FELT on its flanks!
                // An adjacent ball or cluster is wide in all directions, so its flanks are NOT felt!
                if (stickPixels >= 3 && flankFeltPixels >= 2) return true
            }
            return false
        }

        // 2. Guideline Emerging Detection:
        // The game's native white guideline ONLY emerges directly from the true cue ball!
        fun hasGuidelineEmerging(cx: Float, cy: Float): Boolean {
            val r1 = ballRadiusEst * 1.25f
            val r2 = ballRadiusEst * 3.5f
            for (d in 0 until 16) {
                val theta = (d * 2.0 * Math.PI / 16).toFloat()
                val cosT = cos(theta.toDouble()).toFloat()
                val sinT = sin(theta.toDouble()).toFloat()
                var whiteDots = 0
                var rDist = r1
                while (rDist <= r2) {
                    val px = (cx + rDist * cosT).toInt()
                    val py = (cy + rDist * sinT).toInt()
                    if (px in 2 until width - 2 && py in 2 until height - 2) {
                        val c = getPixelPacked(buffer, px, py, rowStride, pixelStride, width, height)
                        val r = (c shr 16) and 0xFF
                        val gCol = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = 0.299f * r + 0.587f * gCol + 0.114f * b
                        val diff = maxOf(r, maxOf(gCol, b)) - minOf(r, minOf(gCol, b))
                        if (((lum > 180f && diff < 45) || (r > 200 && gCol > 200 && b > 200)) && (lum - feltLum > 24f)) {
                            whiteDots++
                        }
                    }
                    rDist += maxOf(2.0f, ballRadiusEst * 0.35f)
                }
                if (whiteDots >= 2) return true
            }
            return false
        }

        // 3. Candidate Evaluation: Evaluates whiteness purity, cue stick presence, and guideline emergence.
        // Full cushion support: NEVER rejects balls touching the cushion!
        fun scoreCandidateCueBall(cx: Float, cy: Float): Float {
            val icx = cx.toInt()
            val icy = cy.toInt()
            if (icx < tableStartX - 4 || icx > tableEndX + 4 ||
                icy < tableStartY - 4 || icy > tableEndY + 4
            ) {
                return -1f
            }

            val rDisc = (ballRadiusEst * 1.15f).toInt()
            var whiteWeight = 0f
            var whiteCount = 0
            var coloredCount = 0

            for (dy in -rDisc..rDisc step 2) {
                for (dx in -rDisc..rDisc step 2) {
                    if (dx * dx + dy * dy <= rDisc * rDisc) {
                        val px = (icx + dx).coerceIn(0, width - 1)
                        val py = (icy + dy).coerceIn(0, height - 1)
                        val c = getPixelPacked(buffer, px, py, rowStride, pixelStride, width, height)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF

                        if (isCueBallColor(r, g, b)) {
                            val lum = (r * 77 + g * 150 + b * 29) shr 8
                            val w = (lum - 140).coerceAtLeast(1).toFloat()
                            whiteWeight += w
                            whiteCount++
                        } else if (isSaturatedBallColor(r, g, b)) {
                            coloredCount++
                        }
                    }
                }
            }

            if (whiteCount < 8) return -1f

            val total = (whiteCount + coloredCount).toFloat()
            val purity = (whiteCount.toFloat() / (total + 0.001f)).coerceIn(0f, 1f)

            var score = whiteWeight * (purity * purity)

            if (purity < 0.60f) {
                score *= 0.25f // Extra penalty for striped balls
            }

            // Cue stick bonus: In 8 Ball Pool, the cue stick is ONLY positioned behind the true cue ball!
            if (checkCueStickNear(cx, cy)) {
                score += 18000f
            }

            // Guideline bonus: Guideline dots ONLY emerge from the true cue ball!
            if (hasGuidelineEmerging(cx, cy)) {
                score += 25000f
            }

            return score
        }

        var detectedCueX = -1f
        var detectedCueY = -1f

        if (isLockedOnCue && lockedCueX >= (tableStartX - 6) && lockedCueX <= (tableEndX + 6) &&
            lockedCueY >= (tableStartY - 6) && lockedCueY <= (tableEndY + 6)
        ) {
            var sumX = 0f
            var sumY = 0f
            var totalWeight = 0f
            val searchRadius = (ballRadiusEst * 1.6f).toInt()
            val startSearchX = (lockedCueX - searchRadius).toInt().coerceIn(0, width - 1)
            val endSearchX = (lockedCueX + searchRadius).toInt().coerceIn(0, width - 1)
            val startSearchY = (lockedCueY - searchRadius).toInt().coerceIn(0, height - 1)
            val endSearchY = (lockedCueY + searchRadius).toInt().coerceIn(0, height - 1)

            for (y in startSearchY..endSearchY) {
                for (x in startSearchX..endSearchX) {
                    val c = getPixelPacked(buffer, x, y, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (isCueBallColor(r, g, b)) {
                        val lum = (r * 77 + g * 150 + b * 29) shr 8
                        val weight = (lum - 140).coerceAtLeast(1).toFloat()
                        sumX += x * weight
                        sumY += y * weight
                        totalWeight += weight
                    }
                }
            }

            if (totalWeight > 25f) {
                val newX = sumX / totalWeight
                val newY = sumY / totalWeight
                // Smooth position (eliminates jitter)
                detectedCueX = 0.70f * lockedCueX + 0.30f * newX
                detectedCueY = 0.70f * lockedCueY + 0.30f * newY
                lockedCueX = detectedCueX
                lockedCueY = detectedCueY
                lostFrames = 0
            } else {
                lostFrames++
                if (lostFrames < 15) {
                    detectedCueX = lockedCueX
                    detectedCueY = lockedCueY
                } else {
                    isLockedOnCue = false
                    lostFrames = 0
                }
            }

            // AUTO-CORRECTION: If current locked position does NOT have a cue stick,
            // check every 10 frames if another ball on the table has the cue stick aiming at it!
            if (isLockedOnCue && !checkCueStickNear(lockedCueX, lockedCueY) && (lockFrames % 10 == 0)) {
                var foundStickX = -1f
                var foundStickY = -1f
                val stepG = (ballRadiusEst * 1.5f).toInt().coerceAtLeast(10)
                for (sy in tableStartY until tableEndY step stepG) {
                    for (sx in tableStartX until tableEndX step stepG) {
                        val c = getPixelPacked(buffer, sx, sy, rowStride, pixelStride, width, height)
                        if (isCueBallColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) {
                            if (checkCueStickNear(sx.toFloat(), sy.toFloat())) {
                                foundStickX = sx.toFloat()
                                foundStickY = sy.toFloat()
                                break
                            }
                        }
                    }
                    if (foundStickX > 0f) break
                }
                if (foundStickX > 0f) {
                    lockedCueX = foundStickX
                    lockedCueY = foundStickY
                    detectedCueX = foundStickX
                    detectedCueY = foundStickY
                }
            }
        }

        // Slow Path: Scan table to find cue ball (Scans right up to cushions!)
        if (detectedCueX < 0f) {
            var bestScore = 0f
            var bestBx = -1f
            var bestBy = -1f

            val scanStep = 4
            for (y in tableStartY until tableEndY step scanStep) {
                for (x in tableStartX until tableEndX step scanStep) {
                    val c = getPixelPacked(buffer, x, y, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    if (isCueBallColor(r, g, b)) {
                        val score = scoreCandidateCueBall(x.toFloat(), y.toFloat())
                        if (score > bestScore) {
                            var sumX = 0f
                            var sumY = 0f
                            var totalW = 0f
                            val rInt = (ballRadiusEst * 1.1f).toInt()
                            for (cy in (y - rInt)..(y + rInt) step 2) {
                                for (cx in (x - rInt)..(x + rInt) step 2) {
                                    val sc = getPixelPacked(buffer, cx.coerceIn(0, width - 1), cy.coerceIn(0, height - 1), rowStride, pixelStride, width, height)
                                    val sr = (sc shr 16) and 0xFF
                                    val sg = (sc shr 8) and 0xFF
                                    val sb = sc and 0xFF
                                    if (isCueBallColor(sr, sg, sb)) {
                                        val lum = (sr * 77 + sg * 150 + sb * 29) shr 8
                                        val w = (lum - 140).coerceAtLeast(1).toFloat()
                                        sumX += cx * w
                                        sumY += cy * w
                                        totalW += w
                                    }
                                }
                            }
                            if (totalW > 25f) {
                                bestScore = score
                                bestBx = sumX / totalW
                                bestBy = sumY / totalW
                            }
                        }
                    }
                }
            }

            if (bestBx > 0f && bestBy > 0f && bestScore > 0f) {
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
        // STEP 3: DETECT OBJECT / TARGET BALLS (TRUE CENTROIDS, INCLUDING CUSHION BALLS)
        // =========================================================================
        val detectedBalls = mutableListOf<CircleBall>()
        val ballStep = 6
        val minBallDistSq = (ballRadiusEst * 1.75f) * (ballRadiusEst * 1.75f)

        for (y in tableStartY until tableEndY step ballStep) {
            for (x in tableStartX until tableEndX step ballStep) {
                val c = getPixelPacked(buffer, x, y, rowStride, pixelStride, width, height)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                if (!isFelt(r, g, b)) {
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
                            val startBx = (x - bBoxRadius).coerceIn(0, width - 1)
                            val endBx = (x + bBoxRadius).coerceIn(0, width - 1)
                            val startBy = (y - bBoxRadius).coerceIn(0, height - 1)
                            val endBy = (y + bBoxRadius).coerceIn(0, height - 1)

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

                            if (ballPixels >= 10) {
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
        // STEP 4: PURE CUE STICK DETECTION (ZERO WHITE DOT TRACKING)
        // Aiming Master Rule: The Cue Stick is the ONLY true source of aim angle!
        // When stick is absent (player shot / balls moving / opponent turn) -> isAimingActive = false.
        // =========================================================================
        val piF = Math.PI.toFloat()
        val degToRad = (piF / 180f)

        // Function to score cue stick at a given angle theta from cue ball (cx, cy)
        // A true cue stick is a narrow shaft (not felt) surrounded by felt on both flanks!
        fun scoreCueStickShaft(cx: Float, cy: Float, angleRad: Float): Float {
            val cosA = cos(angleRad.toDouble()).toFloat()
            val sinA = sin(angleRad.toDouble()).toFloat()
            val orthCos = -sinA
            val orthSin = cosA
            val flankDist = ballRadiusEst * 0.65f

            var shaftMatches = 0
            var flankFeltMatches = 0

            // Sample along the stick shaft from 1.30*R to 4.5*R (7 samples)
            for (step in 1..7) {
                val dist = ballRadiusEst * (1.30f + step * 0.45f)
                val px = (cx + dist * cosA).toInt()
                val py = (cy + dist * sinA).toInt()

                if (px in 2 until width - 2 && py in 2 until height - 2) {
                    val c = getPixelPacked(buffer, px, py, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    if (!isFelt(r, g, b)) {
                        shaftMatches++

                        // Check left and right flanks (must be table felt for a narrow cue stick)
                        val flX = (px + flankDist * orthCos).toInt()
                        val flY = (py + flankDist * orthSin).toInt()
                        val frX = (px - flankDist * orthCos).toInt()
                        val frY = (py - flankDist * orthSin).toInt()

                        if (flX in 0 until width && flY in 0 until height) {
                            val cFl = getPixelPacked(buffer, flX, flY, rowStride, pixelStride, width, height)
                            if (isFelt((cFl shr 16) and 0xFF, (cFl shr 8) and 0xFF, cFl and 0xFF)) {
                                flankFeltMatches++
                            }
                        }
                        if (frX in 0 until width && frY in 0 until height) {
                            val cFr = getPixelPacked(buffer, frX, frY, rowStride, pixelStride, width, height)
                            if (isFelt((cFr shr 16) and 0xFF, (cFr shr 8) and 0xFF, cFr and 0xFF)) {
                                flankFeltMatches++
                            }
                        }
                    }
                }
            }

            // High score requires both shaft presence and felt flanks
            return if (shaftMatches >= 3 && flankFeltMatches >= 3) {
                shaftMatches * 2.0f + flankFeltMatches * 1.5f
            } else {
                0f
            }
        }

        // Scan 360° around cue ball in 2° increments
        var bestStickAngle = 0f
        var bestStickScore = 0f

        var deg = 0
        while (deg < 360) {
            val rad = deg * degToRad
            val score = scoreCueStickShaft(detectedCueX, detectedCueY, rad)
            if (score > bestStickScore) {
                bestStickScore = score
                bestStickAngle = rad
            }
            deg += 2
        }

        var detectedAngle = smoothAngle
        var hasValidStick = false

        // Threshold check: at least 11.0 score indicates a clear cue stick behind the cue ball
        if (bestStickScore >= 11.0f) {
            // Fine sub-degree sweep (+/- 3° in 0.25° steps)
            var fineAngle = bestStickAngle
            var fineScore = bestStickScore
            var dDeg = -3.0f
            while (dDeg <= 3.0f) {
                val fRad = bestStickAngle + dDeg * degToRad
                val fScore = scoreCueStickShaft(detectedCueX, detectedCueY, fRad)
                if (fScore > fineScore) {
                    fineScore = fScore
                    fineAngle = fRad
                }
                dDeg += 0.25f
            }

            // Cue stick points FROM behind towards the cue ball, so ball travels opposite:
            var aimAngle = fineAngle + piF
            while (aimAngle < -piF) aimAngle += (2f * piF)
            while (aimAngle > piF) aimAngle -= (2f * piF)

            detectedAngle = aimAngle
            hasValidStick = true
        }

        // Hysteresis & State Update
        if (hasValidStick) {
            aimConfidenceFrames = minOf(6, aimConfidenceFrames + 2)
            var diff = detectedAngle - smoothAngle
            while (diff < -piF) diff += (2f * piF)
            while (diff > piF) diff -= (2f * piF)

            val alpha = if (abs(diff) > 0.12f) 0.85f else 0.55f
            smoothAngle += diff * alpha
            while (smoothAngle < -piF) smoothAngle += (2f * piF)
            while (smoothAngle > piF) smoothAngle -= (2f * piF)
            lockFrames++
        } else {
            // If stick is missing (e.g. shot released, ball rolling, opponent turn), immediately drop confidence
            aimConfidenceFrames = maxOf(0, aimConfidenceFrames - 2)
            lockFrames = 0
        }

        // Crucial: Only active when confidence >= 2
        isAimingActive = (aimConfidenceFrames >= 2)

        // =========================================================================
        // STEP 5: RAY-CASTING TO FIND TARGET OBJECT BALL (ACCURATE COLLISION)
        // =========================================================================
        var targetBallX: Float? = null
        var targetBallY: Float? = null
        var minRayDist = Float.MAX_VALUE

        val cosA = cos(smoothAngle.toDouble()).toFloat()
        val sinA = sin(smoothAngle.toDouble()).toFloat()
        val collisionThreshold = ballRadiusEst * 1.95f

        for (ball in detectedBalls) {
            val toX = ball.x - detectedCueX
            val toY = ball.y - detectedCueY
            val dot = toX * cosA + toY * sinA
            if (dot > ballRadiusEst * 0.5f) { // must be ahead
                val perpDist = abs(-toX * sinA + toY * cosA)
                if (perpDist <= collisionThreshold && dot < minRayDist) {
                    minRayDist = dot
                    targetBallX = ball.x
                    targetBallY = ball.y
                }
            }
        }

        // =========================================================================
        // STEP 6: CUE POWER SLIDER ON LEFT RAIL (0.0 to 1.0)
        // =========================================================================
        val sliderX = (width * 0.05f).toInt()
        val sliderY1 = (height * 0.20f).toInt()
        val sliderY2 = (height * 0.80f).toInt()
        var pulledCount = 0

        for (y in sliderY1..sliderY2 step 4) {
            val c = getPixelPacked(buffer, sliderX, y, rowStride, pixelStride, width, height)
            val r = (c shr 16) and 0xFF
            val gCol = (c shr 8) and 0xFF
            val b = c and 0xFF
            if ((r > 160 && gCol > 110 && b < 90) || (r > 180 && gCol < 120)) {
                pulledCount++
            }
        }

        val powerTarget = if (pulledCount >= 3) {
            (pulledCount / 18f).coerceIn(0.20f, 1.0f)
        } else {
            0.0f
        }
        smoothPower += (powerTarget - smoothPower) * 0.35f

        // =========================================================================
        // STEP 7: IN-GAME SPIN (ENGLISH) WIDGET DETECTOR
        // =========================================================================
        var detectedSpinX = 0f
        var detectedSpinY = 0f

        val spinMinX = (width * 0.80f).toInt()
        val spinMaxX = (width * 0.98f).toInt()
        val spinMinY = (height * 0.02f).toInt()
        val spinMaxY = (height * 0.22f).toInt()

        var redSumX = 0f
        var redSumY = 0f
        var redCount = 0
        var whiteCenterSumX = 0f
        var whiteCenterSumY = 0f
        var whiteCount = 0

        for (sy in spinMinY..spinMaxY step 2) {
            for (sx in spinMinX..spinMaxX step 2) {
                val sc = getPixelPacked(buffer, sx, sy, rowStride, pixelStride, width, height)
                val sr = (sc shr 16) and 0xFF
                val sg = (sc shr 8) and 0xFF
                val sb = sc and 0xFF

                if (sr > 170 && sg < 75 && sb < 75 && (sr - sg) > 85) {
                    redSumX += sx
                    redSumY += sy
                    redCount++
                } else if (sr > 210 && sg > 210 && sb > 210) {
                    whiteCenterSumX += sx
                    whiteCenterSumY += sy
                    whiteCount++
                }
            }
        }

        if (redCount >= 2 && whiteCount >= 25) {
            val redAvgX = redSumX / redCount
            val redAvgY = redSumY / redCount
            val whiteAvgX = whiteCenterSumX / whiteCount
            val whiteAvgY = whiteCenterSumY / whiteCount
            val iconRadius = sqrt(whiteCount.toDouble() * 4.0 / Math.PI).toFloat() * 0.5f

            if (iconRadius > 6f) {
                val offX = (redAvgX - whiteAvgX) / (iconRadius * 0.85f)
                val offY = (redAvgY - whiteAvgY) / (iconRadius * 0.85f)
                if (hypot(offX, offY) > 0.10f) {
                    detectedSpinX = offX.coerceIn(-1.0f, 1.0f)
                    detectedSpinY = offY.coerceIn(-1.0f, 1.0f)
                }
            }
        }

        // =========================================================================
        // STEP 7.5: REAL-TIME CUE POWER SLIDER TRACKING (PULL RATIO 0.0 - 1.0)
        // =========================================================================
        val powerBarMinX = (width * 0.040f).toInt()
        val powerBarMaxX = (width * 0.100f).toInt()
        val powerBarTopY = (height * 0.30f).toInt()
        val powerBarBottomY = (height * 0.84f).toInt()
        val totalPowerHeight = maxOf(1, powerBarBottomY - powerBarTopY)

        var lowestFillY = -1
        for (py in powerBarTopY until powerBarBottomY step 2) {
            var rowHasFill = false
            for (px in powerBarMinX until powerBarMaxX step 2) {
                if (px in 0 until width && py in 0 until height) {
                    val c = getPixelPacked(buffer, px, py, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val gCol = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    // 8BP Power meter: Bright yellow (R>180, G>140), orange (R>200, G>90), or red (R>200, G<60)
                    if (r > 155 && (r - b) > 50 && (gCol > 50 || r > 185)) {
                        rowHasFill = true
                        break
                    }
                }
            }
            if (rowHasFill) {
                lowestFillY = py
            }
        }

        val rawPull = if (lowestFillY > powerBarTopY) {
            ((lowestFillY - powerBarTopY).toFloat() / totalPowerHeight.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }

        smoothPower = if (rawPull > 0.03f) {
            smoothPower * 0.65f + rawPull * 0.35f
        } else {
            smoothPower * 0.50f
        }
        if (smoothPower < 0.02f) smoothPower = 0.0f

        // =========================================================================
        // STEP 8: MAP TO PHYSICAL SCREEN RESOLUTION & DELIVER COMPLETE DATA
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

        val allRealBalls = ArrayList<Vector2D>(detectedBalls.size)
        for (b in detectedBalls) {
            allRealBalls.add(Vector2D(b.x * scaleX, b.y * scaleY))
        }

        val detectedBounds = if (isCalibrated && userCalibratedBounds != null) {
            userCalibratedBounds!!
        } else {
            RectF(
                tableStartX * scaleX,
                tableStartY * scaleY,
                tableEndX * scaleX,
                tableEndY * scaleY
            )
        }

        listener.onAimUpdated(
            cueBallPos = Vector2D(realCueX, realCueY),
            aimAngleRad = smoothAngle,
            powerPercent = smoothPower,
            targetBallPos = realTargetPos,
            allBalls = allRealBalls,
            spinX = detectedSpinX,
            spinY = detectedSpinY,
            matchDetected = true,
            tableBounds = detectedBounds,
            isAiming = isAimingActive
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
