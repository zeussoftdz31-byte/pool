package com.poolaim.assist.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import androidx.core.content.ContextCompat
import com.poolaim.assist.R
import com.poolaim.assist.AppSettings
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
    var isMatchDetected = false // Only true during a live 8 Ball Pool match!
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
        color = Color.parseColor("#E0FFD600") // Vivid Gold Neon
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private val pocketInnerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44000000") // Dark translucent hole center
        style = Paint.Style.FILL
    }

    private val cushionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA00E5FF") // Glowing Laser Cyan Cushions
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }

    private val pocketGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val cueAnchorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val cueAnchorCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
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
    var tableZoom = 1.0f
    var tableScaleX = 1.0f
    var tableScaleY = 1.0f
    var pocketScale = 1.0f
    var showTableCushions = true

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
            tableZoom = prefs.getFloat("table_zoom", 1.0f)
            tableScaleX = prefs.getFloat("scale_x", 1.0f)
            tableScaleY = prefs.getFloat("scale_y", 1.0f)
            pocketScale = prefs.getFloat("pocket_scale", 1.0f)
            showTableCushions = prefs.getBoolean("show_cushions", true)
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
                .putFloat("table_zoom", tableZoom)
                .putFloat("scale_x", tableScaleX)
                .putFloat("scale_y", tableScaleY)
                .putFloat("pocket_scale", pocketScale)
                .putBoolean("show_cushions", showTableCushions)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun applySettings(settings: AppSettings) {
        isMasterOverlayEnabled = settings.isGuidelinesEnabled
        enableBankShots = settings.isCushionShotsEnabled
        enableCueBallPath = settings.isThreeLinesEnabled
        enablePocketGlow = settings.isPocketLockEnabled
        pocketScale = settings.pocketScale
        if (settings.tableOffsetX != 0f || settings.tableOffsetY != 0f) {
            offsetX = settings.tableOffsetX
            offsetY = settings.tableOffsetY
        }

        val chosenColor = settings.lineColor
        directAimPaint.color = chosenColor
        directAimPaint.strokeWidth = settings.lineThickness + 1.5f
        targetTrajectoryPaint.strokeWidth = settings.lineThickness + 1.0f
        firstBankPaint.strokeWidth = settings.lineThickness
        secondBankPaint.strokeWidth = settings.lineThickness - 0.5f
        ghostBallPaint.color = chosenColor

        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
        postInvalidate()
    }

    fun adjustCalibration(
        dOffsetX: Float,
        dOffsetY: Float,
        dScaleX: Float,
        dScaleY: Float = 0f,
        dPocketScale: Float = 0f,
        dTableZoom: Float = 0f
    ) {
        offsetX += dOffsetX
        offsetY += dOffsetY
        tableScaleX = (tableScaleX + dScaleX).coerceIn(0.40f, 1.80f)
        tableScaleY = (tableScaleY + dScaleY).coerceIn(0.40f, 1.80f)
        pocketScale = (pocketScale + dPocketScale).coerceIn(0.40f, 2.50f)
        tableZoom = (tableZoom + dTableZoom).coerceIn(0.35f, 1.70f)
        saveCalibration()
        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
    }

    fun setTableZoomValue(zoom: Float) {
        tableZoom = zoom.coerceIn(0.35f, 1.70f)
        saveCalibration()
        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
    }

    fun setPocketScaleValue(scale: Float) {
        pocketScale = scale.coerceIn(0.40f, 2.50f)
        saveCalibration()
        postInvalidate()
    }

    fun setTableWidthScale(scale: Float) {
        tableScaleX = scale.coerceIn(0.40f, 1.80f)
        saveCalibration()
        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
    }

    fun setTableHeightScale(scale: Float) {
        tableScaleY = scale.coerceIn(0.40f, 1.80f)
        saveCalibration()
        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
    }

    fun resetCalibration() {
        offsetX = 0f
        offsetY = 0f
        tableZoom = 1.0f
        tableScaleX = 1.0f
        tableScaleY = 1.0f
        pocketScale = 1.0f
        showTableCushions = true
        saveCalibration()
        if (viewWidth > 0 && viewHeight > 0) {
            recalculateTableBounds(viewWidth, viewHeight)
        }
    }

    fun autoFitToScreen(w: Int, h: Int) {
        val isLandscape = w > h
        val screenW = if (isLandscape) w.toFloat() else h.toFloat()
        val screenH = if (isLandscape) h.toFloat() else w.toFloat()

        offsetX = 0f
        offsetY = 0f
        tableZoom = 1.0f
        tableScaleX = 1.0f
        tableScaleY = 1.0f
        pocketScale = 1.0f
        showTableCushions = true
        saveCalibration()
        recalculateTableBounds(w, h)
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
        if (w <= 0 || h <= 0) return
        val isLandscape = w > h

        val screenW = w.toFloat()
        val screenH = h.toFloat()

        // In landscape (8 Ball Pool game mode): table height occupies ~75% of screen height (2:1 aspect ratio)
        // In portrait (testing/launcher mode): table width occupies ~92% of screen width (2:1 aspect ratio)
        val (baseTableW, baseTableH) = if (isLandscape) {
            val th = screenH * 0.75f
            Pair(th * 2.0f, th)
        } else {
            val tw = screenW * 0.92f
            Pair(tw, tw * 0.50f)
        }

        val tableW = baseTableW * tableScaleX * tableZoom
        val tableH = baseTableH * tableScaleY * tableZoom

        // Horizontally and vertically centered across the physical display with offset calibration
        val tableLeft = (screenW - tableW) / 2f + offsetX
        val tableRight = tableLeft + tableW
        val tableTop = (screenH - tableH) / 2f + offsetY
        val tableBottom = tableTop + tableH

        tableBounds.set(tableLeft, tableTop, tableRight, tableBottom)

        // Exact physical ball radius: in 8 Ball Pool, 16.5 balls fit vertically (radius = tableH / 33.0)
        ballRadius = tableH / 33.0f

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

        // Pocket mouth centers aligned with cushion corners
        pockets.add(Vector2D(l, t))       // 0: Top-Left
        pockets.add(Vector2D(midX, t))    // 1: Top-Center
        pockets.add(Vector2D(r, t))       // 2: Top-Right
        pockets.add(Vector2D(l, b))       // 3: Bottom-Left
        pockets.add(Vector2D(midX, b))    // 4: Bottom-Center
        pockets.add(Vector2D(r, b))       // 5: Bottom-Right
    }

    fun updateAutoAim(
        cue: Vector2D,
        angleRad: Float,
        power: Float,
        target: Vector2D?,
        matchActive: Boolean = true,
        detectedBounds: RectF? = null
    ) {
        isMatchDetected = matchActive
        if (!isMasterOverlayEnabled) return

        if (detectedBounds != null && detectedBounds.width() > 100f && detectedBounds.height() > 80f) {
            tableBounds.set(
                detectedBounds.left + offsetX,
                detectedBounds.top + offsetY,
                detectedBounds.right + offsetX,
                detectedBounds.bottom + offsetY
            )
            ballRadius = tableBounds.height() / 33.0f
            updatePockets()
        }

        isAutoAimActive = true
        cueBallPos = cue
        aimAngleRad = angleRad
        shotPowerPercent = power
        targetBallPos = target
        postInvalidate()
    }

    fun setMatchActive(active: Boolean) {
        if (isMatchDetected != active) {
            isMatchDetected = active
            postInvalidate()
        }
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

        // CRITICAL: DO NOT DRAW LINES OR POCKETS UNLESS A MATCH IS ACTIVE!
        // Outside of a live match (in lobby, phone launcher, rewards), draw NOTHING!
        if (!isMatchDetected && !isCalibrationModeActive) {
            return
        }

        // Interactive Calibration Mode
        if (isCalibrationModeActive) {
            drawCalibrationOverlay(canvas)
        }

        // 1. Draw Table Cushions (Rails where bank shots bounce!)
        if (showTableCushions || isCalibrationModeActive) {
            canvas.drawRect(tableBounds, cushionBorderPaint)
        }

        // 2. Draw 6 Pockets Rim (Scales with table and pocketScale)
        val pocketVisualRadius = ballRadius * 1.55f * pocketScale
        for (p in pockets) {
            canvas.drawCircle(p.x, p.y, pocketVisualRadius, pocketInnerFillPaint)
            canvas.drawCircle(p.x, p.y, pocketVisualRadius, pocketMarkerPaint)
            canvas.drawCircle(p.x, p.y, 4f, pocketMarkerPaint)
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
        var isTargetHit = false
        var ghostX = 0f
        var ghostY = 0f
        var ghostDist = 0f
        var exitDx = 0f
        var exitDy = 0f
        var exitLen = 0.001f
        var exitAngleDeg = 0f
        var targetDistance = 0f
        var cueDeflectionDistance = 0f

        if (target != null) {
            val deltaX = target.x - cueBallPos.x
            val deltaY = target.y - cueBallPos.y
            val proj = deltaX * dirX + deltaY * dirY

            val twoR = ballRadius * 2.0f
            val twoRSq = twoR * twoR
            val distSq = deltaX * deltaX + deltaY * deltaY
            val perpSq = max(0f, distSq - proj * proj)

            // Exact mathematical condition for cue ball colliding with target ball along ray
            if (proj > ballRadius * 0.4f && perpSq <= twoRSq) {
                isTargetHit = true
                ghostDist = max(0f, proj - sqrt(twoRSq - perpSq))
                ghostX = cueBallPos.x + dirX * ghostDist
                ghostY = cueBallPos.y + dirY * ghostDist

                // Vector from ghost ball center to target ball center at impact
                exitDx = target.x - ghostX
                exitDy = target.y - ghostY
                exitLen = max(0.001f, hypot(exitDx, exitDy))

                exitAngleDeg = Math.toDegrees(atan2(-exitDy.toDouble(), exitDx.toDouble())).toFloat()
                if (exitAngleDeg < 0f) exitAngleDeg += 360f

                // Cut Angle & Momentum Transfer:
                // Dot product between cue incoming vector and exit vector gives cos(cutAngle)
                val cosCut = ((dirX * exitDx + dirY * exitDy) / exitLen).coerceIn(0f, 1f)
                val sinCut = sqrt(max(0f, 1f - cosCut * cosCut))

                // Energy distributed between target ball and cue ball deflection
                val targetEnergyRatio = cosCut * cosCut
                val cueEnergyRatio = sinCut * sinCut

                val remainingEnergyDist = max(0f, totalDistance - ghostDist)
                targetDistance = remainingEnergyDist * targetEnergyRatio
                cueDeflectionDistance = remainingEnergyDist * cueEnergyRatio
            }
        }

        if (isTargetHit && target != null) {
            // ========================================================
            // SCENARIO A: Target Ball is Hit along Aim Line
            // ========================================================

            // Draw line from cue ball to ghost ball
            canvas.drawLine(cueBallPos.x, cueBallPos.y, ghostX, ghostY, directAimPaint)
            canvas.drawCircle(ghostX, ghostY, ballRadius, ghostBallPaint)

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

        // Draw Cue Ball Origin Marker (الكرة البيضاء هي الأساس الثابت وقاعدة انطلاق الخطوط)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, ballRadius * 1.15f, cueAnchorRingPaint)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, ballRadius * 0.40f, ghostBallPaint)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, 4f, cueAnchorCorePaint)
    }
}
