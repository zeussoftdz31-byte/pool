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

    var cueForceLevel: Int = 11

    private val dynamicYellowReboundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEE00") // Vivid Neon Yellow matching Aiming Master
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dynamicYellowGhostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEE00")
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val angleSplitBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }

    private val powerBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEE00")
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
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
        cueForceLevel = settings.cueForceLevel
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

        // AIMING MASTER MANDATORY RULE:
        // If player is NOT actively aiming (cue stick missing / player shot / balls moving / opponent turn) -> Draw NOTHING!
        if (!isAimingActive) return

        // =========================================================================
        // AIMING MASTER DIRECT VECTOR ENGINE (ZERO-FREEZE, ZERO-LAG)
        // Computes direct aim lines, Ghost Ball, 90° angle split, combination shots,
        // and Dynamic Yellow Rebound Line adjusted by shot power and cue force.
        // =========================================================================
        val dirCue = Vector2D(cos(aimAngleRad.toDouble()).toFloat(), sin(aimAngleRad.toDouble()).toFloat()).normalized()

        // 1. Raycast Cushion for table bounds
        val cushionHit = raycastCushion(cueBallPos, dirCue)

        // 2. Raycast Balls for object ball impact
        val ballHit = raycastBalls(cueBallPos, dirCue)

        val isDirectBallShot = (ballHit != null) && (cushionHit == null || ballHit.dist < cushionHit.dist)

        if (isDirectBallShot && ballHit != null) {
            // =====================================================================
            // SCENARIO 1: DIRECT SHOT ON OBJECT BALL (Images 2 & 3)
            // =====================================================================
            // 1. Solid line from cue ball to ghost impact point
            canvas.drawLine(cueBallPos.x, cueBallPos.y, ballHit.ghostCenter.x, ballHit.ghostCenter.y, directAimPaint)

            // 2. White Ghost Ball circle at impact position
            canvas.drawCircle(ballHit.ghostCenter.x, ballHit.ghostCenter.y, ballRadius, ghostBallPaint)
            canvas.drawCircle(ballHit.ghostCenter.x, ballHit.ghostCenter.y, 4f, cueAnchorCorePaint)

            // 3. Separation vectors: Normal (Target Ball) & Tangent (Cue Deflection)
            val dirTarget = (ballHit.ballCenter - ballHit.ghostCenter).normalized()
            val dot = dirCue.dot(dirTarget)
            val dirDeflect = (dirCue - dirTarget * dot).normalized()

            // 4. White 90° angle split bracket at impact point
            drawAngleBracket(canvas, ballHit.ghostCenter, dirTarget, dirDeflect)

            // 5. Cue Ball deflection tangent line
            if (enableCueBallPath) {
                val cueDeflectEnd = raycastCushion(ballHit.ghostCenter, dirDeflect)?.hitPoint ?: (ballHit.ghostCenter + dirDeflect * 350f)
                canvas.drawLine(ballHit.ghostCenter.x, ballHit.ghostCenter.y, cueDeflectEnd.x, cueDeflectEnd.y, cueTangentPaint)
            }

            // 6. Target Ball Path & Combination Shot
            val comboHit = if (enableComboShots) raycastBalls(ballHit.ballCenter, dirTarget, ignoreBall = ballHit.ballCenter) else null

            if (comboHit != null) {
                // Secondary Ball Collision (Combination Shot)
                canvas.drawLine(ballHit.ballCenter.x, ballHit.ballCenter.y, comboHit.ghostCenter.x, comboHit.ghostCenter.y, targetTrajectoryPaint)
                canvas.drawCircle(comboHit.ghostCenter.x, comboHit.ghostCenter.y, ballRadius, comboGhostBallPaint)

                val dirCombo = (comboHit.ballCenter - comboHit.ghostCenter).normalized()
                val comboEnd = raycastCushion(comboHit.ballCenter, dirCombo)?.hitPoint ?: (comboHit.ballCenter + dirCombo * 550f)
                canvas.drawLine(comboHit.ballCenter.x, comboHit.ballCenter.y, comboEnd.x, comboEnd.y, comboTrajectoryPaint)
            } else {
                // Single Ball Shot to Pocket or Cushion
                val targetCushion = raycastCushion(ballHit.ballCenter, dirTarget)
                val targetEnd = targetCushion?.hitPoint ?: (ballHit.ballCenter + dirTarget * 650f)
                canvas.drawLine(ballHit.ballCenter.x, ballHit.ballCenter.y, targetEnd.x, targetEnd.y, targetTrajectoryPaint)

                // Cushion Bank Shot & Dynamic Yellow Line
                if (enableBankShots && targetCushion != null) {
                    val (whiteRebound, yellowRebound) = computeCushionRebound(dirTarget, targetCushion.normal, shotPowerPercent, cueForceLevel)

                    // Reference white geometric rebound
                    val whiteEnd = raycastCushion(targetCushion.hitPoint, whiteRebound)?.hitPoint ?: (targetCushion.hitPoint + whiteRebound * 500f)
                    canvas.drawLine(targetCushion.hitPoint.x, targetCushion.hitPoint.y, whiteEnd.x, whiteEnd.y, firstBankPaint)

                    // DYNAMIC YELLOW LINE (Power & Force Adjusted Cushion Line - Only when pulling cue!)
                    if (shotPowerPercent > 0.08f) {
                        val yellowEnd = raycastCushion(targetCushion.hitPoint, yellowRebound)?.hitPoint ?: (targetCushion.hitPoint + yellowRebound * 500f)
                        canvas.drawLine(targetCushion.hitPoint.x, targetCushion.hitPoint.y, yellowEnd.x, yellowEnd.y, dynamicYellowReboundPaint)
                        canvas.drawCircle(yellowEnd.x, yellowEnd.y, ballRadius, dynamicYellowGhostPaint)
                    }
                }
            }
        } else if (cushionHit != null) {
            // =====================================================================
            // SCENARIO 2: KICK SHOT / CUSHION-FIRST SHOT (Images 4 & 5)
            // =====================================================================
            // 1. Line from cue ball to cushion contact point
            canvas.drawLine(cueBallPos.x, cueBallPos.y, cushionHit.hitPoint.x, cushionHit.hitPoint.y, directAimPaint)
            canvas.drawCircle(cushionHit.hitPoint.x, cushionHit.hitPoint.y, ballRadius * 0.9f, ghostBallPaint)

            val (whiteRebound, yellowRebound) = computeCushionRebound(dirCue, cushionHit.normal, shotPowerPercent, cueForceLevel)

            // 2. White Geometric Rebound Line
            val kickTarget = raycastBalls(cushionHit.hitPoint, whiteRebound)
            if (kickTarget != null) {
                // White path to target ball
                canvas.drawLine(cushionHit.hitPoint.x, cushionHit.hitPoint.y, kickTarget.ghostCenter.x, kickTarget.ghostCenter.y, directAimPaint)
                canvas.drawCircle(kickTarget.ghostCenter.x, kickTarget.ghostCenter.y, ballRadius, ghostBallPaint)

                // Target ball to pocket
                val dirToPocket = (kickTarget.ballCenter - kickTarget.ghostCenter).normalized()
                val pocketEnd = raycastCushion(kickTarget.ballCenter, dirToPocket)?.hitPoint ?: (kickTarget.ballCenter + dirToPocket * 550f)
                canvas.drawLine(kickTarget.ballCenter.x, kickTarget.ballCenter.y, pocketEnd.x, pocketEnd.y, directAimPaint)
            } else {
                val whiteEnd = raycastCushion(cushionHit.hitPoint, whiteRebound)?.hitPoint ?: (cushionHit.hitPoint + whiteRebound * 600f)
                canvas.drawLine(cushionHit.hitPoint.x, cushionHit.hitPoint.y, whiteEnd.x, whiteEnd.y, firstBankPaint)
            }

            // 3. DYNAMIC YELLOW REBOUND LINE (Moves with cue stick pull power!)
            if (shotPowerPercent > 0.08f) {
                val yellowTarget = raycastBalls(cushionHit.hitPoint, yellowRebound)
                if (yellowTarget != null) {
                    // Yellow path to target ball (offset based on power compression!)
                    canvas.drawLine(cushionHit.hitPoint.x, cushionHit.hitPoint.y, yellowTarget.ghostCenter.x, yellowTarget.ghostCenter.y, dynamicYellowReboundPaint)
                    canvas.drawCircle(yellowTarget.ghostCenter.x, yellowTarget.ghostCenter.y, ballRadius, dynamicYellowGhostPaint)

                    // Resultant yellow line from target ball
                    val dirYellowPocket = (yellowTarget.ballCenter - yellowTarget.ghostCenter).normalized()
                    val yEnd = raycastCushion(yellowTarget.ballCenter, dirYellowPocket)?.hitPoint ?: (yellowTarget.ballCenter + dirYellowPocket * 550f)
                    canvas.drawLine(yellowTarget.ballCenter.x, yellowTarget.ballCenter.y, yEnd.x, yEnd.y, dynamicYellowReboundPaint)
                } else {
                    val yellowEnd = raycastCushion(cushionHit.hitPoint, yellowRebound)?.hitPoint ?: (cushionHit.hitPoint + yellowRebound * 600f)
                    canvas.drawLine(cushionHit.hitPoint.x, cushionHit.hitPoint.y, yellowEnd.x, yellowEnd.y, dynamicYellowReboundPaint)
                    canvas.drawCircle(yellowEnd.x, yellowEnd.y, ballRadius, dynamicYellowGhostPaint)
                }
            }
        }

        // Draw In-Game Active Spin HUD Widget
        if (enableSpinPhysics && (abs(currentSpinX) > 0.05f || abs(currentSpinY) > 0.05f)) {
            val spinCenterR = ballRadius * 0.95f
            val spinHUDX = tableBounds.right - spinCenterR - 12f
            val spinHUDY = tableBounds.top + spinCenterR + 12f

            canvas.drawCircle(spinHUDX, spinHUDY, spinCenterR, spinWidgetBallPaint)
            canvas.drawCircle(spinHUDX, spinHUDY, spinCenterR, spinWidgetBorderPaint)

            val dotX = spinHUDX + currentSpinX * (spinCenterR * 0.65f)
            val dotY = spinHUDY + currentSpinY * (spinCenterR * 0.65f)
            canvas.drawCircle(dotX, dotY, 4.5f, spinWidgetDotPaint)
        }

        // Draw Cue Ball Origin Marker
        val ringRadius = if (isManualRingMode) ballRadius * 1.35f else ballRadius * 1.15f
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, ringRadius, cueAnchorRingPaint)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, ballRadius * 0.40f, ghostBallPaint)
        canvas.drawCircle(cueBallPos.x, cueBallPos.y, 4f, cueAnchorCorePaint)
    }

    private data class CushionRayHit(
        val hitPoint: Vector2D,
        val wallIndex: Int,
        val normal: Vector2D,
        val dist: Float
    )

    private fun raycastCushion(start: Vector2D, dir: Vector2D): CushionRayHit? {
        val r = ballRadius
        val minX = tableBounds.left + r
        val maxX = tableBounds.right - r
        val minY = tableBounds.top + r
        val maxY = tableBounds.bottom - r

        var bestT = Float.MAX_VALUE
        var bestHit: CushionRayHit? = null

        // Top Wall (y = minY, normal (0, 1))
        if (dir.y < -0.0001f) {
            val t = (minY - start.y) / dir.y
            if (t > 0.01f && t < bestT) {
                val hx = start.x + t * dir.x
                if (hx in minX..maxX) {
                    bestT = t
                    bestHit = CushionRayHit(Vector2D(hx, minY), 1, Vector2D(0f, 1f), t)
                }
            }
        }
        // Bottom Wall (y = maxY, normal (0, -1))
        if (dir.y > 0.0001f) {
            val t = (maxY - start.y) / dir.y
            if (t > 0.01f && t < bestT) {
                val hx = start.x + t * dir.x
                if (hx in minX..maxX) {
                    bestT = t
                    bestHit = CushionRayHit(Vector2D(hx, maxY), 2, Vector2D(0f, -1f), t)
                }
            }
        }
        // Left Wall (x = minX, normal (1, 0))
        if (dir.x < -0.0001f) {
            val t = (minX - start.x) / dir.x
            if (t > 0.01f && t < bestT) {
                val hy = start.y + t * dir.y
                if (hy in minY..maxY) {
                    bestT = t
                    bestHit = CushionRayHit(Vector2D(minX, hy), 3, Vector2D(1f, 0f), t)
                }
            }
        }
        // Right Wall (x = maxX, normal (-1, 0))
        if (dir.x > 0.0001f) {
            val t = (maxX - start.x) / dir.x
            if (t > 0.01f && t < bestT) {
                val hy = start.y + t * dir.y
                if (hy in minY..maxY) {
                    bestT = t
                    bestHit = CushionRayHit(Vector2D(maxX, hy), 4, Vector2D(-1f, 0f), t)
                }
            }
        }
        return bestHit
    }

    private data class BallRayHit(
        val ballCenter: Vector2D,
        val ghostCenter: Vector2D,
        val dist: Float
    )

    private fun raycastBalls(start: Vector2D, dir: Vector2D, ignoreBall: Vector2D? = null): BallRayHit? {
        val twoR = ballRadius * 2.0f
        val twoRSq = twoR * twoR
        var bestDist = Float.MAX_VALUE
        var bestHit: BallRayHit? = null

        for (b in allDetectedBalls) {
            if (ignoreBall != null && (b.x - ignoreBall.x) * (b.x - ignoreBall.x) + (b.y - ignoreBall.y) * (b.y - ignoreBall.y) < 25f) {
                continue
            }
            val wx = b.x - start.x
            val wy = b.y - start.y
            val dStartSq = wx * wx + wy * wy
            if (dStartSq < 100f) continue // Skip origin ball

            val proj = wx * dir.x + wy * dir.y
            if (proj > 1.0f) {
                val perpSq = dStartSq - proj * proj
                if (perpSq <= twoRSq && perpSq >= 0f) {
                    val halfChord = sqrt(maxOf(0f, twoRSq - perpSq))
                    val dHit = proj - halfChord
                    if (dHit > 0.5f && dHit < bestDist) {
                        bestDist = dHit
                        val gx = start.x + dHit * dir.x
                        val gy = start.y + dHit * dir.y
                        bestHit = BallRayHit(b, Vector2D(gx, gy), dHit)
                    }
                }
            }
        }
        return bestHit
    }

    private fun computeCushionRebound(incidentDir: Vector2D, normal: Vector2D, powerPercent: Float, forceLevel: Int): Pair<Vector2D, Vector2D> {
        // Specular Geometric Reflection (White Line)
        val dot = incidentDir.x * normal.x + incidentDir.y * normal.y
        val specX = incidentDir.x - 2f * dot * normal.x
        val specY = incidentDir.y - 2f * dot * normal.y
        val whiteDir = Vector2D(specX, specY).normalized()

        // Power-Adjusted Reflection (Yellow Line)
        val effPower = powerPercent.coerceIn(0.0f, 1.0f) * (forceLevel / 11.0f)
        val cosIncidence = abs(dot).coerceIn(0f, 1f)
        val sinIncidence = sqrt(maxOf(0f, 1f - cosIncidence * cosIncidence))

        // Angle narrows by deltaRad:
        val deltaRad = 0.12f * effPower * (2f * sinIncidence * cosIncidence)
        val cross = whiteDir.x * normal.y - whiteDir.y * normal.x
        val rotSign = if (cross > 0) -1f else 1f
        val cosD = cos((rotSign * deltaRad).toDouble()).toFloat()
        val sinD = sin((rotSign * deltaRad).toDouble()).toFloat()

        val yellowX = whiteDir.x * cosD - whiteDir.y * sinD
        val yellowY = whiteDir.x * sinD + whiteDir.y * cosD
        val yellowDir = Vector2D(yellowX, yellowY).normalized()

        return Pair(whiteDir, yellowDir)
    }

    private fun drawAngleBracket(canvas: Canvas, origin: Vector2D, dirTarget: Vector2D, dirDeflect: Vector2D) {
        val armLen = ballRadius * 0.70f
        val p1X = origin.x + dirTarget.x * armLen
        val p1Y = origin.y + dirTarget.y * armLen
        val p2X = origin.x + (dirTarget.x + dirDeflect.x) * armLen
        val p2Y = origin.y + (dirTarget.y + dirDeflect.y) * armLen
        val p3X = origin.x + dirDeflect.x * armLen
        val p3Y = origin.y + dirDeflect.y * armLen

        canvas.drawLine(origin.x, origin.y, p1X, p1Y, angleSplitBracketPaint)
        canvas.drawLine(p1X, p1Y, p2X, p2Y, angleSplitBracketPaint)
        canvas.drawLine(p2X, p2Y, p3X, p3Y, angleSplitBracketPaint)
        canvas.drawLine(origin.x, origin.y, p3X, p3Y, angleSplitBracketPaint)
    }
}
