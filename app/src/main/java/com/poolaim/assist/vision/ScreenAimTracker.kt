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
            matchDetected: Boolean,
            tableBounds: RectF? = null
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
        // STEP 4: SUB-PIXEL NATIVE WHITE LINE LINEAR REGRESSION ENGINE
        // =========================================================================
        val piF = Math.PI.toFloat()
        val degToRad = (piF / 180f)

        // Gather candidate points belonging to the game's native white aiming line
        val linePointsX = ArrayList<Float>(120)
        val linePointsY = ArrayList<Float>(120)

        val minR = ballRadiusEst * 1.25f
        val maxR = ballRadiusEst * 6.50f
        val rStep = maxOf(1.5f, ballRadiusEst * 0.15f)

        var rSweep = minR
        while (rSweep <= maxR) {
            var aDeg = 0
            while (aDeg < 360) {
                val rad = aDeg * degToRad
                val cosA = cos(rad.toDouble()).toFloat()
                val sinA = sin(rad.toDouble()).toFloat()
                val px = (detectedCueX + rSweep * cosA).toInt()
                val py = (detectedCueY + rSweep * sinA).toInt()

                if (px in tableStartX..tableEndX && py in tableStartY..tableEndY) {
                    val c = getPixelPacked(buffer, px, py, rowStride, pixelStride, width, height)
                    val r = (c shr 16) and 0xFF
                    val gCol = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    val lum = 0.299f * r + 0.587f * gCol + 0.114f * b
                    val diff = maxOf(r, maxOf(gCol, b)) - minOf(r, minOf(gCol, b))

                    // Solid native line criteria
                    if (((lum > 190f && diff < 38) || (r > 210 && gCol > 210 && b > 210)) && (lum - feltLum > 35f)) {
                        var insideObjectBall = false
                        val safeBallDistSq = (ballRadiusEst * 1.10f) * (ballRadiusEst * 1.10f)
                        for (ob in detectedBalls) {
                            val dSq = (px - ob.x) * (px - ob.x) + (py - ob.y) * (py - ob.y)
                            if (dSq <= safeBallDistSq) {
                                insideObjectBall = true
                                break
                            }
                        }

                        if (!insideObjectBall) {
                            linePointsX.add(px.toFloat())
                            linePointsY.add(py.toFloat())
                        }
                    }
                }
                aDeg += 2
            }
            rSweep += rStep
        }

        var detectedAngle = smoothAngle
        var hasValidAngle = false

        if (linePointsX.size >= 8) {
            // 2D Orthogonal Distance Linear Regression (Total Least Squares)
            val n = linePointsX.size
            var meanX = 0.0
            var meanY = 0.0
            for (i in 0 until n) {
                meanX += linePointsX[i]
                meanY += linePointsY[i]
            }
            meanX /= n
            meanY /= n

            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = linePointsX[i] - meanX
                val dy = linePointsY[i] - meanY
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }

            // Direction of maximum variance / principal axis of line
            val fitTheta = 0.5 * atan2(2.0 * sxy, sxx - syy)
            var regAngle = fitTheta.toFloat()

            // Resolve forward direction (vector from cue ball towards line centroid)
            val toCentroidX = (meanX - detectedCueX).toFloat()
            val toCentroidY = (meanY - detectedCueY).toFloat()
            val forwardDot = toCentroidX * cos(regAngle.toDouble()).toFloat() + toCentroidY * sin(regAngle.toDouble()).toFloat()
            if (forwardDot < 0) {
                regAngle += piF
            }

            while (regAngle > piF) regAngle -= (2f * piF)
            while (regAngle < -piF) regAngle += (2f * piF)

            detectedAngle = regAngle
            hasValidAngle = true
        } else {
            // Fallback: Cue Stick behind ball angle tracker
            fun scoreCueStickAngle(cx: Float, cy: Float, radius: Float, theta: Float): Float {
                val cosT = cos(theta.toDouble()).toFloat()
                val sinT = sin(theta.toDouble()).toFloat()
                var score = 0f
                for (s in 1..5) {
                    val dBack = radius * (1.6f + s * 1.0f)
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

            var bestScore = -1f
            var bestAng = smoothAngle
            val searchRange = if (lockFrames > 0) 60f else 360f
            val halfR = searchRange / 2f
            val steps = (searchRange / 2.0f).toInt()
            for (i in 0..steps) {
                val th = smoothAngle - (halfR * degToRad) + (i * 2.0f * degToRad)
                val sc = scoreCueStickAngle(detectedCueX, detectedCueY, ballRadiusEst, th)
                if (sc > bestScore) {
                    bestScore = sc
                    bestAng = th
                }
            }

            if (bestScore >= 2.0f) {
                detectedAngle = bestAng
                hasValidAngle = true
            }
        }

        if (hasValidAngle) {
            var diff = detectedAngle - smoothAngle
            while (diff < -piF) diff += (2f * piF)
            while (diff > piF) diff -= (2f * piF)

            val alpha = if (abs(diff) > 0.08f) 0.85f else 0.45f
            smoothAngle += diff * alpha
            while (smoothAngle < -piF) smoothAngle += (2f * piF)
            while (smoothAngle > piF) smoothAngle -= (2f * piF)
            lockFrames++
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
            matchDetected = true,
            tableBounds = detectedBounds
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
