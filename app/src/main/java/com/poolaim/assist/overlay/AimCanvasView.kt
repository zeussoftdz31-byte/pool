package com.poolaim.assist.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import androidx.core.content.ContextCompat
import com.poolaim.assist.R
import com.poolaim.assist.AppSettings
import com.poolaim.assist.physics.BallTrajectory
import com.poolaim.assist.physics.LineIntersection
import com.poolaim.assist.physics.MultiBallSimulator
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
    var isAimingActive = false // Only true when game's white line from cue stick appears!
    var isManualRingMode = false // Option 3: Magnetic Cue Ring Mode
    var cueBallPos = Vector2D(500f, 500f)
    var aimAngleRad = 0f
    var shotPowerPercent = 0.85f
    var userSelectedPower: Float? = null
    var targetBallPos: Vector2D? = null

    // Multi-Ball Collision & Spin State
    var allDetectedBalls: List<Vector2D> = emptyList()
    var currentSpinX: Float = 0f // English / Side-spin (-1.0 to 1.0)
    var currentSpinY: Float = 0f // Vertical spin (-1.0: Top/Follow, 1.0: Back/Draw)
    var enableComboShots = true
    var enableSpinPhysics = true

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

    private val comboTrajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100") // Vivid Neon Amber / Gold
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    private val comboGhostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val comboStopMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val tertiaryTrajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E040FB") // Vivid Neon Magenta / Purple
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val tertiaryStopMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E040FB")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val spinWidgetBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEFFFFFF")
        style = Paint.Style.FILL
    }

    private val spinWidgetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val spinWidgetDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.FILL
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
        color = Color.parseColor("#FF1744") // Vivid Neon Red Cushion Outline matching user drawing
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
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

    // Interactive Calibration Paints (Matching user red diagram)
    private val calibBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Vivid Neon Red
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
    }

    private val calibPocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Vivid Neon Red
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val calibPocketFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FF1744") // Translucent red
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
        color = Color.parseColor("#FF5252")
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
    var isUserCalibrationSaved = false

    private var viewWidth = 0
    private var viewHeight = 0

    init {
        loadCalibration()
    }

    fun loadCalibration() {
        try {
            val prefs = context.getSharedPreferences("pool_wizard_calibration", Context.MODE_PRIVATE)
            offsetX = prefs.getFloat("offset_x", 0f)
            offsetY = prefs.getFloat("offset_y", 0f)
            tableZoom = prefs.getFloat("table_zoom", 1.0f)
            tableScaleX = prefs.getFloat("scale_x", 1.0f)
            tableScaleY = prefs.getFloat("scale_y", 1.0f)
            pocketScale = prefs.getFloat("pocket_scale", 1.0f)
            showTableCushions = prefs.getBoolean("show_cushions", true)
            isUserCalibrationSaved = prefs.getBoolean("user_calibration_saved", false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveCalibration(markAsUserSaved: Boolean = false) {
        try {
            if (markAsUserSaved) {
                isUserCalibrationSaved = true
            }
            val prefs = context.getSharedPreferences("pool_wizard_calibration", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("offset_x", offsetX)
                .putFloat("offset_y", offsetY)
                .putFloat("table_zoom", tableZoom)
                .putFloat("scale_x", tableScaleX)
                .putFloat("scale_y", tableScaleY)
                .putFloat("pocket_scale", pocketScale)
                .putBoolean("show_cushions", showTableCushions)
                .putBoolean("user_calibration_saved", isUserCalibrationSaved)
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
        isUserCalibrationSaved = false
        val prefs = context.getSharedPreferences("pool_wizard_calibration", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("user_calibration_saved", false).apply()
        saveCalibration(markAsUserSaved = false)
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

        val (baseTableW, baseTableH) = if (isLandscape) {
            val th = screenH * 0.75f
            Pair(th * 2.0f, th)
        } else {
            val tw = screenW * 0.92f
            Pair(tw, tw * 0.50f)
        }

        val tableW = baseTableW * tableScaleX * tableZoom
        val tableH = baseTableH * tableScaleY * tableZoom

        val tableLeft = (screenW - tableW) / 2f + offsetX
        val tableRight = tableLeft + tableW
        val tableTop = (screenH - tableH) / 2f + offsetY
        val tableBottom = tableTop + tableH

        tableBounds.set(tableLeft, tableTop, tableRight, tableBottom)
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
        allBalls: List<Vector2D> = emptyList(),
        spinX: Float = 0f,
        spinY: Float = 0f,
        matchActive: Boolean = true,
        detectedBounds: RectF? = null,
        isAiming: Boolean = true
    ) {
        isMatchDetected = matchActive
        isAimingActive = isAiming
        if (!isMasterOverlayEnabled) return

        allDetectedBalls = allBalls
        if (enableSpinPhysics) {
            currentSpinX = spinX
            currentSpinY = spinY
        }

        if (!isUserCalibrationSaved && detectedBounds != null && detectedBounds.width() > 100f && detectedBounds.height() > 80f) {
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
        if (userSelectedPower != null && abs(power - 0.85f) < 0.08f) {
            shotPowerPercent = userSelectedPower!!
        } else {
            shotPowerPercent = power
        }
        targetBallPos = target
        postInvalidate()
    }

    fun setMatchActive(active: Boolean) {
        if (isMatchDetected != active) {
            isMatchDetected = active
            if (!active) {
                isAimingActive = false
            }
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
        // 1. Draw outer table border with vivid red neon rectangle (matching user diagram)
        canvas.drawRect(tableBounds, calibBorderPaint)

        // Draw segmented cushions matching pool table rails
        val l = tableBounds.left
        val r = tableBounds.right
        val t = tableBounds.top
        val b = tableBounds.bottom
        val midX = (l + r) / 2f
        val pocketIndent = ballRadius * 1.6f * pocketScale

        // Top rails (left & right separated by center pocket)
        canvas.drawLine(l + pocketIndent, t, midX - pocketIndent * 0.7f, t, calibBorderPaint)
        canvas.drawLine(midX + pocketIndent * 0.7f, t, r - pocketIndent, t, calibBorderPaint)

        // Bottom rails
        canvas.drawLine(l + pocketIndent, b, midX - pocketIndent * 0.7f, b, calibBorderPaint)
        canvas.drawLine(midX + pocketIndent * 0.7f, b, r - pocketIndent, b, calibBorderPaint)

        // Left & Right rails
        canvas.drawLine(l, t + pocketIndent, l, b - pocketIndent, calibBorderPaint)
        canvas.drawLine(r, t + pocketIndent, r, b - pocketIndent, calibBorderPaint)

        // 2. Center division lines (Axes)
        canvas.drawLine(tableBounds.left, tableBounds.centerY(), tableBounds.right, tableBounds.centerY(), pocketGuidePaint)
        canvas.drawLine(tableBounds.centerX(), tableBounds.top, tableBounds.centerX(), tableBounds.bottom, pocketGuidePaint)

        // 3. Highlight 6 pockets with numbers & red outlines
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
        canvas.drawText("🎯 وضع إعداد ومعايرة الطاولة (Setup Mode)", cx, cy - 22f, calibTextPaint)
        canvas.drawText("طابق الحواف الحمراء والجيوب مع وضع التمرين ثم اضغط '💾 حفظ وتثبيت'", cx, cy + 24f, calibSubTextPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Master Switch: Stop drawing if disabled
        if (!isMasterOverlayEnabled) return

        // Interactive Calibration Mode (Setup Mode)
        if (isCalibrationModeActive) {
            drawCalibrationOverlay(canvas)
            canvas.drawRect(tableBounds, cushionBorderPaint)
            val pocketVisualRadius = ballRadius * 1.55f * pocketScale
            for (p in pockets) {
                canvas.drawCircle(p.x, p.y, pocketVisualRadius, pocketInnerFillPaint)
                canvas.drawCircle(p.x, p.y, pocketVisualRadius, pocketMarkerPaint)
                canvas.drawCircle(p.x, p.y, 4f, pocketMarkerPaint)
            }
            return // During setup, only display table calibration guides!
        }

        // 1. Draw Table Cushions (Rails where bank shots bounce!)
        if (showTableCushions) {
            canvas.drawRect(tableBounds, cushionBorderPaint)
        }

        // 2. Draw 6 Pockets Rim (Scales with table and pocketScale)
        val pocketVisualRadius = ballRadius * 1.55f * pocketScale
        for (p in pockets) {
            canvas.drawCircle(p.x, p.y, pocketVisualRadius, pocketInnerFillPaint)
            canvas.drawCircle(p.x, p.y, pocketVisualRadius, pocketMarkerPaint)
            canvas.drawCircle(p.x, p.y, 4f, pocketMarkerPaint)
        }

        // =========================================================================
        // REAL-TIME MULTI-BODY BILLIARDS SIMULATOR
        // Simulates Cue Ball, Primary Object Ball, Secondary Combo (Carom),
        // Tertiary balls, felt friction, spin follow/draw/throw, and cushion banks.
        // =========================================================================
        val simResult = MultiBallSimulator.simulate(
            cuePos = cueBallPos,
            aimAngleRad = aimAngleRad,
            powerPercent = shotPowerPercent,
            spinX = currentSpinX,
            spinY = currentSpinY,
            objectBalls = allDetectedBalls,
            tableBounds = tableBounds,
            ballRadius = ballRadius,
            pockets = pockets,
            pocketScale = pocketScale,
            enableBankShots = enableBankShots,
            enableComboShots = enableComboShots,
            enableSpinPhysics = enableSpinPhysics
        )

        // Render each active ball's simulated trajectory
        for (path in simResult.activePaths) {
            if (path.points.size < 2) continue

            // Select distinctive color per ball role
            val linePaint = when (path.roleOrder) {
                0 -> directAimPaint // Cue Ball: Vivid Neon Cyan
                1 -> targetTrajectoryPaint // Primary Hit Ball: Vivid Neon Green
                2 -> comboTrajectoryPaint // Secondary Combo Ball: Vivid Neon Amber / Orange
                else -> tertiaryTrajectoryPaint // Tertiary / Scatter: Vivid Neon Magenta / Purple
            }

            val stopPaint = when (path.roleOrder) {
                0 -> cueStopMarkerPaint
                1 -> targetStopMarkerPaint
                2 -> comboStopMarkerPaint
                else -> tertiaryStopMarkerPaint
            }

            // 1. Draw multi-segment trajectory path
            for (i in 0 until path.points.size - 1) {
                val p1 = path.points[i]
                val p2 = path.points[i + 1]
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint)
            }

            // 2. Draw ghost balls at all collision impact points
            for (g in path.ghosts) {
                val gPaint = if (path.roleOrder <= 1) ghostBallPaint else comboGhostBallPaint
                canvas.drawCircle(g.x, g.y, ballRadius, gPaint)
            }

            // 3. Draw final resting stop position or pocket entry
            if (path.isPotted) {
                if (enablePocketGlow && path.potIndex in pockets.indices) {
                    val p = pockets[path.potIndex]
                    if (path.isCueBall) {
                        // SCRATCH WARNING: Red alert on potted pocket
                        canvas.drawCircle(p.x, p.y, ballRadius * 1.8f * pocketScale, scratchWarningPaint)
                        canvas.drawCircle(p.x, p.y, ballRadius * 2.5f * pocketScale, scratchWarningPaint)
                        canvas.drawLine(p.x - 14f, p.y - 14f, p.x + 14f, p.y + 14f, scratchWarningPaint)
                        canvas.drawLine(p.x - 14f, p.y + 14f, p.x + 14f, p.y - 14f, scratchWarningPaint)
                    } else {
                        // Safe ball pot: double glowing rings
                        canvas.drawCircle(p.x, p.y, ballRadius * 1.6f * pocketScale, pocketGlowPaint)
                        canvas.drawCircle(p.x, p.y, ballRadius * 2.3f * pocketScale, pocketGlowPaint)
                    }
                }
            } else {
                // Stopped on table: draw stop circle + center dot + glowing halo ring
                canvas.drawCircle(path.stopX, path.stopY, ballRadius, stopPaint)
                stopCenterDotPaint.color = linePaint.color
                canvas.drawCircle(path.stopX, path.stopY, 4f, stopCenterDotPaint)

                stopGlowRingPaint.color = linePaint.color
                canvas.drawCircle(path.stopX, path.stopY, ballRadius * 1.35f, stopGlowRingPaint)
            }
        }

        // Optional 6-Pocket Guide Lines
        if (showPocketGuides && targetBallPos != null) {
            for (p in pockets) {
                canvas.drawLine(targetBallPos!!.x, targetBallPos!!.y, p.x, p.y, pocketGuidePaint)
            }
        }

        // 3. Draw In-Game Active Spin HUD Widget (دائرة الـ Spin الحالية مع موضع النقطة الحمراء)
        if (enableSpinPhysics && (abs(currentSpinX) > 0.05f || abs(currentSpinY) > 0.05f)) {
            val spinCenterR = ballRadius * 0.95f
            val spinHUDX = tableBounds.right - spinCenterR - 12f
            val spinHUDY = tableBounds.top + spinCenterR + 12f

            canvas.drawCircle(spinHUDX, spinHUDY, spinCenterR, spinWidgetBallPaint)
            canvas.drawCircle(spinHUDX, spinHUDY, spinCenterR, spinWidgetBorderPaint)

            // Red dot indicating strike point
            val dotX = spinHUDX + currentSpinX * (spinCenterR * 0.65f)
            val dotY = spinHUDY + currentSpinY * (spinCenterR * 0.65f)
            canvas.drawCircle(dotX, dotY, 4.5f, spinWidgetDotPaint)
        }

        // Draw Cue Ball Origin Marker (الكرة البيضاء هي الأساس الثابت وقاعدة انطلاق الخطوط)
        val ringRadius = if (isManualRingMode) ballRadius * 1.35f else ballRadius * 1.15f
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, ringRadius, cueAnchorRingPaint)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, ballRadius * 0.40f, ghostBallPaint)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, 4f, cueAnchorCorePaint)
    }
}
