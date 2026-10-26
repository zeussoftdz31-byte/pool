package com.poolaim.assist.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import androidx.core.content.ContextCompat
import com.poolaim.assist.R
import com.poolaim.assist.physics.BallTrajectory
import com.poolaim.assist.physics.LineIntersection
import com.poolaim.assist.physics.Vector2D
import kotlin.math.*

/**
 * Ultra-Responsive Full-Screen Aiming Canvas.
 * Inspired by professional tools like Wizard Pool 8 / Cheto / Aim Assist Pro.
 *
 * Real-Time Physics Capabilities:
 * 1. Live Cue Stick Angle Tracking with dynamic guideline projection.
 * 2. Real-time Cue Power Slider scaling: Lines grow dynamically as cue stick is pulled down.
 * 3. Exact Ball Stopping Locations: Calculates friction deceleration and renders
 *    glowing Stopping Circles at the precise resting locations of both balls.
 * 4. Cut-Angle Momentum Transfer: Splits energy into Target Ball (cos²θ) and Cue Deflection (sin²θ).
 * 5. Multi-Cushion Bank Shots (1st yellow bank, 2nd cyan double-bank).
 * 6. Scratch Warning: Illuminates pocket in red if cue ball is on track to scratch.
 * 7. Pocket Lock: Glowing green double-rings when target ball is potted.
 * 8. Mod Menu Theme & Configuration Toggles.
 */
class AimCanvasView(context: Context) : View(context) {

    // Live Aim State from Computer Vision
    var isAutoAimActive = false
    var cueBallPos = Vector2D(500f, 500f)
    var aimAngleRad = 0f
    var shotPowerPercent = 0.85f
    var targetBallPos: Vector2D? = null

    // Mod Menu Configurable Flags
    var isMasterOverlayEnabled = true
    var isCalibrationModeActive = false
    var enablePowerSimulation = true
    var enableBankShots = true
    var enableCueBallPath = true
    var showPocketGuides = false
    var enablePocketGlow = true
    var themeColorIndex = 0 // 0: Green, 1: Cyan, 2: Gold, 3: Red

    var ballRadius = 24f

    // Table Bounds & Pockets (Dynamically computed based on landscape screen size)
    val tableBounds = RectF(160f, 90f, 2240f, 990f)
    val pockets = mutableListOf<Vector2D>()

    // Core Paints
    private val directAimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val targetTrajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.laser_green)
        strokeWidth = 5.0f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val firstBankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f)
    }

    private val secondBankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.laser_cyan)
        strokeWidth = 4.0f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val cueTangentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f, 12f), 0f)
    }

    private val ghostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.laser_cyan)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val targetStopMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.laser_green)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val cueStopMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val stopCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val stopGlowRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val pocketGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.laser_green)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val scratchWarningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.laser_red)
        style = Paint.Style.STROKE
        strokeWidth = 7f
    }

    private val pocketMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val pocketGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    // Interactive Calibration Paints
    private val calibBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Laser cyan
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
    }

    private val calibPocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Laser gold
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val calibPocketFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFD600")
        style = Paint.Style.FILL
    }

    private val calibTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val calibSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 20f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    // Device & Calibration Adjustments (Persistent via SharedPreferences)
    var offsetX = 0f
    var offsetY = 0f
    var tableScaleX = 1.0f
    var tableScaleY = 1.0f
    var pocketScale = 1.0f

    private var viewWidth = 0
    private var viewHeight = 0

    init {
        loadCalibration()
    }

    private fun loadCalibration() {
        try {
            val prefs = context.getSharedPreferences("pool_wizard_calibration", Context.MODE_PRIVATE)
            offsetX = prefs.getFloat("offset_x", 0f)
            offsetY = prefs.getFloat("offset_y", 0f)
            tableScaleX = prefs.getFloat("scale_x", 1.0f)
            tableScaleY = prefs.getFloat("scale_y", 1.0f)
            pocketScale = prefs.getFloat("pocket_scale", 1.0f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveCalibration() {
        try {
            val prefs = context.getSharedPreferences("pool_wizard_calibration", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("offset_x", offsetX)
                .putFloat("offset_y", offsetY)
                .putFloat("scale_x", tableScaleX)
                .putFloat("scale_y", tableScaleY)
                .putFloat("pocket_scale", pocketScale)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun adjustCalibration(
        dOffsetX: Float,
        dOffsetY: Float,
        dScaleX: Float,
        dScaleY: Float = 0f,
        dPocketScale: Float = 0f
    ) {
        offsetX += dOffsetX
        offsetY += dOffsetY
        tableScaleX = (tableScaleX + dScaleX).coerceIn(0.50f, 1.60f)
        tableScaleY = (tableScaleY + dScaleY).coerceIn(0.50f, 1.60f)
        pocketScale = (pocketScale + dPocketScale).coerceIn(0.50f, 1.80f)
        saveCalibration()
        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
    }

    fun resetCalibration() {
        offsetX = 0f
        offsetY = 0f
        tableScaleX = 1.0f
        tableScaleY = 1.0f
        pocketScale = 1.0f
        saveCalibration()
        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        if (w > 0 && h > 0) {
            recalculateTableBounds(w, h)
        }
    }

    fun recalculateTableBounds(w: Int, h: Int) {
        val isLandscape = w > h
        val screenW = if (isLandscape) w.toFloat() else h.toFloat()
        val screenH = if (isLandscape) h.toFloat() else w.toFloat()

        // 8 Ball Pool table playfield has an exact aspect ratio of 2.0 : 1.0
        // Playable table height occupies ~78.2% of landscape screen height
        // Leaving ~13.6% at the top for player avatars/timer, and ~8.2% at the bottom
        val tableH = screenH * 0.782f * tableScaleY
        val tableW = tableH * 2.0f * tableScaleX

        // Horizontally centered across the physical display with offset calibration
        val tableLeft = (screenW - tableW) / 2f + offsetX
        val tableRight = tableLeft + tableW
        val tableTop = screenH * 0.136f + offsetY
        val tableBottom = tableTop + tableH

        tableBounds.set(tableLeft, tableTop, tableRight, tableBottom)

        // Exact physical ball radius: a standard pool ball is 1/18.5 of table height
        ballRadius = tableH / 18.5f

        updatePockets()

        if (!isAutoAimActive) {
            cueBallPos.x = tableBounds.left + tableBounds.width() * 0.30f
            cueBallPos.y = tableBounds.centerY()
        }

        postInvalidate()
    }

    private fun updatePockets() {
        pockets.clear()
        val l = tableBounds.left
        val r = tableBounds.right
        val t = tableBounds.top
        val b = tableBounds.bottom
        val midX = (l + r) / 2f

        // Corner pockets are slightly inset along diagonal into the funnel mouth
        val cornerInset = ballRadius * 0.15f

        pockets.add(Vector2D(l + cornerInset, t + cornerInset))       // 0: Top-Left
        pockets.add(Vector2D(midX, t))                                 // 1: Top-Center
        pockets.add(Vector2D(r - cornerInset, t + cornerInset))       // 2: Top-Right
        pockets.add(Vector2D(l + cornerInset, b - cornerInset))       // 3: Bottom-Left
        pockets.add(Vector2D(midX, b))                                 // 4: Bottom-Center
        pockets.add(Vector2D(r - cornerInset, b - cornerInset))       // 5: Bottom-Right
    }

    fun updateAutoAim(
        cue: Vector2D,
        angleRad: Float,
        power: Float,
        target: Vector2D?
    ) {
        isAutoAimActive = true
        cueBallPos = cue
        aimAngleRad = angleRad
        shotPowerPercent = power
        targetBallPos = target
        postInvalidate()
    }

    fun setThemeColor(colorIdx: Int) {
        themeColorIndex = colorIdx
        val primaryColor = when (colorIdx) {
            1 -> ContextCompat.getColor(context, R.color.laser_cyan)
            2 -> ContextCompat.getColor(context, R.color.laser_yellow)
            3 -> ContextCompat.getColor(context, R.color.laser_red)
            else -> ContextCompat.getColor(context, R.color.laser_green)
        }
        targetTrajectoryPaint.color = primaryColor
        targetStopMarkerPaint.color = primaryColor
        pocketGlowPaint.color = primaryColor
        postInvalidate()
    }

    private fun drawCalibrationOverlay(canvas: Canvas) {
        // 1. Draw outer table border with laser cyan rectangle
        canvas.drawRect(tableBounds, calibBorderPaint)

        // 2. Center division lines (Axes)
        canvas.drawLine(tableBounds.left, tableBounds.centerY(), tableBounds.right, tableBounds.centerY(), pocketGuidePaint)
        canvas.drawLine(tableBounds.centerX(), tableBounds.top, tableBounds.centerX(), tableBounds.bottom, pocketGuidePaint)

        // 3. Highlight 6 pockets with numbers & labels
        val pocketLabels = arrayOf(
            "1. جيب علوي يسار",
            "2. جيب علوي وسط",
            "3. جيب علوي يمين",
            "4. جيب سفلي يسار",
            "5. جيب سفلي وسط",
            "6. جيب سفلي يمين"
        )

        val calibPocketRadius = ballRadius * 1.8f * pocketScale
        for ((i, p) in pockets.withIndex()) {
            canvas.drawCircle(p.x, p.y, calibPocketRadius, calibPocketFillPaint)
            canvas.drawCircle(p.x, p.y, calibPocketRadius, calibPocketPaint)
            canvas.drawCircle(p.x, p.y, 5f, calibPocketPaint)

            val textY = if (i < 3) p.y + calibPocketRadius + 22f else p.y - calibPocketRadius - 10f
            val label = if (i < pocketLabels.size) pocketLabels[i] else "P${i + 1}"
            canvas.drawText(label, p.x, textY, calibTextPaint)
        }

        // 4. Instructions in table center
        val cx = tableBounds.centerX()
        val cy = tableBounds.centerY()
        canvas.drawText("🎯 وضع معايرة الجيوب وأبعاد الطاولة", cx, cy - 22f, calibTextPaint)
        canvas.drawText("اضبط العرض والارتفاع وحجم الثقوب لتطابق فتحات اللعبة تماماً", cx, cy + 24f, calibSubTextPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Master Switch: Stop drawing if disabled
        if (!isMasterOverlayEnabled) return

        // Interactive Calibration Mode
        if (isCalibrationModeActive) {
            drawCalibrationOverlay(canvas)
        }

        // 1. Draw Pockets Rim (Scales with table and pocketScale)
        for (p in pockets) {
            canvas.drawCircle(p.x, p.y, ballRadius * 1.5f * pocketScale, pocketMarkerPaint)
        }

        val dirX = cos(aimAngleRad.toDouble()).toFloat()
        val dirY = sin(aimAngleRad.toDouble()).toFloat()

        var angleDeg = Math.toDegrees(atan2(-dirY.toDouble(), dirX.toDouble())).toFloat()
        if (angleDeg < 0f) angleDeg += 360f

        // Calculate available travel distance based on cue pull power (Wizard Pool 8 mechanics)
        val tableWidth = tableBounds.width()
        val totalDistance = if (enablePowerSimulation) {
            tableWidth * (0.30f + 2.70f * shotPowerPercent.toDouble().pow(1.35).toFloat())
        } else {
            tableWidth * 3.5f // Full table length
        }

        val target = targetBallPos

        if (target != null) {
            // ========================================================
            // SCENARIO A: Target Ball is Detected along Aim Line
            // ========================================================

            val deltaX = target.x - cueBallPos.x
            val deltaY = target.y - cueBallPos.y
            val distToTarget = hypot(deltaX, deltaY)

            val ghostDist = max(0f, distToTarget - ballRadius * 2.0f)
            val ghostX = cueBallPos.x + dirX * ghostDist
            val ghostY = cueBallPos.y + dirY * ghostDist

            // Draw line from cue ball to ghost ball
            canvas.drawLine(cueBallPos.x, cueBallPos.y, ghostX, ghostY, directAimPaint)
            canvas.drawCircle(ghostX, ghostY, ballRadius, ghostBallPaint)

            // Cut Angle & Momentum Transfer:
            // Vector from ghost ball center to target ball center
            val exitDx = target.x - ghostX
            val exitDy = target.y - ghostY
            val exitLen = max(0.001f, hypot(exitDx, exitDy))

            var exitAngleDeg = Math.toDegrees(atan2(-exitDy.toDouble(), exitDx.toDouble())).toFloat()
            if (exitAngleDeg < 0f) exitAngleDeg += 360f

            // Dot product between cue incoming vector and exit vector gives cos(cutAngle)
            val cosCut = ((dirX * exitDx + dirY * exitDy) / exitLen).coerceIn(0f, 1f)
            val sinCut = sqrt(max(0f, 1f - cosCut * cosCut))

            // Energy distributed between target ball and cue ball deflection
            val targetEnergyRatio = cosCut * cosCut
            val cueEnergyRatio = sinCut * sinCut

            val remainingEnergyDist = max(0f, totalDistance - ghostDist)
            val targetDistance = remainingEnergyDist * targetEnergyRatio
            val cueDeflectionDistance = remainingEnergyDist * cueEnergyRatio

            val maxBounces = if (enableBankShots) 4 else 0

            // 1. Trace & Render Target Ball Trajectory
            val targetTrajectory = LineIntersection.traceBallPath(
                startX = target.x,
                startY = target.y,
                angleDeg = exitAngleDeg,
                totalDistance = targetDistance,
                maxBounces = maxBounces,
                tableLeft = tableBounds.left,
                tableTop = tableBounds.top,
                tableRight = tableBounds.right,
                tableBottom = tableBounds.bottom,
                ballRadius = ballRadius,
                pockets = pockets,
                pocketScale = pocketScale
            )

            // Render Target Ball Segments
            for ((idx, seg) in targetTrajectory.segments.withIndex()) {
                val paint = when (idx) {
                    0 -> targetTrajectoryPaint
                    1 -> firstBankPaint
                    else -> secondBankPaint
                }
                canvas.drawLine(seg.startX, seg.startY, seg.endX, seg.endY, paint)
            }

            // Render Target Ball Stop Circle (موقع توقف كرة الهدف)
            if (!targetTrajectory.pottedInPocket) {
                canvas.drawCircle(targetTrajectory.stopX, targetTrajectory.stopY, ballRadius, targetStopMarkerPaint)
                stopCenterDotPaint.color = targetTrajectoryPaint.color
                canvas.drawCircle(targetTrajectory.stopX, targetTrajectory.stopY, 4f, stopCenterDotPaint)

                // Outer halo stopping ring
                stopGlowRingPaint.color = targetTrajectoryPaint.color
                canvas.drawCircle(targetTrajectory.stopX, targetTrajectory.stopY, ballRadius * 1.35f, stopGlowRingPaint)
            } else if (enablePocketGlow) {
                // Neon Double Ring Glow on potted pocket
                val p = pockets[targetTrajectory.pocketIndex]
                canvas.drawCircle(p.x, p.y, ballRadius * 1.6f * pocketScale, pocketGlowPaint)
                canvas.drawCircle(p.x, p.y, ballRadius * 2.3f * pocketScale, pocketGlowPaint)
            }

            // 2. Trace & Render Cue Ball Tangent Deflection (مسار الكرة البيضاء ومكان توقفها)
            if (enableCueBallPath && cueDeflectionDistance > 10f) {
                // Tangent vector is perpendicular to impact vector
                val perpX = -exitDy / exitLen
                val perpY = exitDx / exitLen

                val dot = dirX * perpX + dirY * perpY
                val sign = if (dot >= 0) 1f else -1f
                val tanDx = perpX * sign
                val tanDy = perpY * sign

                var tanAngleDeg = Math.toDegrees(atan2(-tanDy.toDouble(), tanDx.toDouble())).toFloat()
                if (tanAngleDeg < 0f) tanAngleDeg += 360f

                val cueTrajectory = LineIntersection.traceBallPath(
                    startX = ghostX,
                    startY = ghostY,
                    angleDeg = tanAngleDeg,
                    totalDistance = cueDeflectionDistance,
                    maxBounces = if (enableBankShots) 3 else 0,
                    tableLeft = tableBounds.left,
                    tableTop = tableBounds.top,
                    tableRight = tableBounds.right,
                    tableBottom = tableBounds.bottom,
                    ballRadius = ballRadius,
                    pockets = pockets,
                    pocketScale = pocketScale
                )

                for (seg in cueTrajectory.segments) {
                    canvas.drawLine(seg.startX, seg.startY, seg.endX, seg.endY, cueTangentPaint)
                }

                // Render Cue Ball Stop Circle (موقع توقف الكرة البيضاء)
                if (!cueTrajectory.pottedInPocket) {
                    canvas.drawCircle(cueTrajectory.stopX, cueTrajectory.stopY, ballRadius, cueStopMarkerPaint)
                    stopCenterDotPaint.color = Color.WHITE
                    canvas.drawCircle(cueTrajectory.stopX, cueTrajectory.stopY, 4f, stopCenterDotPaint)

                    stopGlowRingPaint.color = Color.WHITE
                    canvas.drawCircle(cueTrajectory.stopX, cueTrajectory.stopY, ballRadius * 1.35f, stopGlowRingPaint)
                } else {
                    // SCRATCH WARNING: Cue ball enters pocket! Show RED alert!
                    val p = pockets[cueTrajectory.pocketIndex]
                    canvas.drawCircle(p.x, p.y, ballRadius * 1.8f * pocketScale, scratchWarningPaint)
                    canvas.drawCircle(p.x, p.y, ballRadius * 2.5f * pocketScale, scratchWarningPaint)
                    canvas.drawLine(p.x - 14f, p.y - 14f, p.x + 14f, p.y + 14f, scratchWarningPaint)
                    canvas.drawLine(p.x - 14f, p.y + 14f, p.x + 14f, p.y - 14f, scratchWarningPaint)
                }
            }

            // Optional 6-Pocket Guide Lines
            if (showPocketGuides) {
                for (p in pockets) {
                    canvas.drawLine(target.x, target.y, p.x, p.y, pocketGuidePaint)
                }
            }

        } else {
            // ========================================================
            // SCENARIO B: Direct Bank Shot (Cue Ball directly to Cushion)
            // ========================================================

            val maxBounces = if (enableBankShots) 4 else 0

            val cueDirectTrajectory = LineIntersection.traceBallPath(
                startX = cueBallPos.x,
                startY = cueBallPos.y,
                angleDeg = angleDeg,
                totalDistance = totalDistance,
                maxBounces = maxBounces,
                tableLeft = tableBounds.left,
                tableTop = tableBounds.top,
                tableRight = tableBounds.right,
                tableBottom = tableBounds.bottom,
                ballRadius = ballRadius,
                pockets = pockets,
                pocketScale = pocketScale
            )

            for ((idx, seg) in cueDirectTrajectory.segments.withIndex()) {
                val paint = when (idx) {
                    0 -> directAimPaint
                    1 -> firstBankPaint
                    else -> secondBankPaint
                }
                canvas.drawLine(seg.startX, seg.startY, seg.endX, seg.endY, paint)
            }

            // Cue Ball Stopping Circle
            if (!cueDirectTrajectory.pottedInPocket) {
                canvas.drawCircle(cueDirectTrajectory.stopX, cueDirectTrajectory.stopY, ballRadius, cueStopMarkerPaint)
                stopCenterDotPaint.color = Color.WHITE
                canvas.drawCircle(cueDirectTrajectory.stopX, cueDirectTrajectory.stopY, 4f, stopCenterDotPaint)
            } else if (enablePocketGlow) {
                val p = pockets[cueDirectTrajectory.pocketIndex]
                canvas.drawCircle(p.x, p.y, ballRadius * 1.6f * pocketScale, pocketGlowPaint)
                canvas.drawCircle(p.x, p.y, ballRadius * 2.3f * pocketScale, pocketGlowPaint)
            }
        }

        // Draw Cue Ball Origin Marker
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, ballRadius * 1.25f, ghostBallPaint)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, 4f, directAimPaint)
    }
}
