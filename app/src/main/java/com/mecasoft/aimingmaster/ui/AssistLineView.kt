package com.mecasoft.aimingmaster.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.mecasoft.aimingmaster.data.AimPreferences
import com.mecasoft.aimingmaster.engine.AimingMasterEngine
import com.mecasoft.aimingmaster.engine.TableGeometry
import com.mecasoft.aimingmaster.engine.Vector2D
import com.mecasoft.aimingmaster.model.AimModel
import kotlin.math.*

@SuppressLint("ViewConstructor")
class AssistLineView(context: Context) : View(context) {

    private val prefs = AimPreferences.get(context)
    var table: TableGeometry = TableGeometry.compute(1920f, 1080f)
    var currentModel: AimModel? = null

    // Draggable ball handles
    var cueBallHandle = Vector2D(500f, 600f)
    var targetBallHandle = Vector2D(1100f, 450f)
    private var draggedHandle: Int = 0 // 0 = none, 1 = cue, 2 = target

    // Paints matching decrypted Aiming Master specifications
    private val mainLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FEFDFF")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ghostUnderRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#11FDFDFD")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val bounceCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FEFDFF")
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
    }

    private val angleBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val cueDeflectLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FEFDFF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val dashedGhostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A1FEFEFD")
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val yellowFanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26FFE600")
        style = Paint.Style.FILL
    }

    private val handleCuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
    }

    private val handleTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100")
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
    }

    private val handleCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        mainLinePaint.strokeWidth = 1.2f * density
        updateTableGeometry()
    }

    fun updateTableGeometry() {
        val w = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        table = TableGeometry.compute(w, h, prefs.tableZoom, prefs.tableOffsetX, prefs.tableOffsetY)
        ghostUnderRayPaint.strokeWidth = table.ballRadius * 2f

        val bounds = table.playRect
        cueBallHandle = Vector2D(bounds.left + bounds.width() * 0.28f, bounds.centerY())
        targetBallHandle = Vector2D(bounds.left + bounds.width() * 0.65f, bounds.top + bounds.height() * 0.38f)

        recomputeTrajectory()
    }

    fun recomputeTrajectory() {
        val angle = (targetBallHandle - cueBallHandle).angle()
        val engine = AimingMasterEngine(table)
        currentModel = engine.computeTrajectory(
            cueBall = cueBallHandle,
            aimAngleRad = angle,
            targetBall = targetBallHandle,
            enableCushionShots = prefs.isCushionShotsEnabled,
            enableThreeLines = prefs.isThreeLinesEnabled,
            cueForceLevel = prefs.cueForceLevel
        )
        postInvalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!prefs.isInteractiveHandlesEnabled) {
            return false
        }

        val touchX = event.x
        val touchY = event.y
        val touchPos = Vector2D(touchX, touchY)
        val grabRadius = table.ballRadius * 2.2f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggedHandle = when {
                    touchPos.distanceTo(cueBallHandle) <= grabRadius -> 1
                    touchPos.distanceTo(targetBallHandle) <= grabRadius -> 2
                    else -> 0
                }
                return draggedHandle != 0
            }
            MotionEvent.ACTION_MOVE -> {
                when (draggedHandle) {
                    1 -> {
                        cueBallHandle = touchPos
                        recomputeTrajectory()
                        return true
                    }
                    2 -> {
                        targetBallHandle = touchPos
                        recomputeTrajectory()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggedHandle = 0
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!prefs.isGuidelinesEnabled) return

        if (currentModel == null) {
            recomputeTrajectory()
        }
        val model = currentModel ?: return

        // 1. Ghost Under-Ray (#11FDFDFD)
        for (seg in model.segments) {
            canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, ghostUnderRayPaint)
        }

        // 2. Main Laser Trajectory (#80FEFDFF)
        for (seg in model.segments) {
            canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, mainLinePaint)
        }

        // 3. Cushion Bounce Circles
        for (circle in model.bounceCircles) {
            canvas.drawCircle(circle.x, circle.y, table.ballRadius, bounceCirclePaint)
        }

        // 4. Ghost Ball at Impact Point
        model.ghostBallPos?.let { gb ->
            canvas.drawCircle(gb.x, gb.y, table.ballRadius, dashedGhostBallPaint)

            // 5. White 90° Angle Bracket
            if (model.targetBallPos != null) {
                val dirTarget = (model.targetBallPos - gb).normalized()
                val dirCue = Vector2D.fromAngle(model.aimAngleRad)
                val dot = dirCue.dot(dirTarget)
                val dirDeflect = (dirCue - dirTarget * dot).normalized()
                drawAngleBracket(canvas, gb, dirTarget, dirDeflect)
            }
        }

        // 6. Cue Ball Deflection Ray (3-Lines mode)
        if (prefs.isThreeLinesEnabled && model.ghostBallPos != null && model.cueDeflectEnd != null) {
            canvas.drawLine(
                model.ghostBallPos.x, model.ghostBallPos.y,
                model.cueDeflectEnd.x, model.cueDeflectEnd.y,
                cueDeflectLinePaint
            )
            canvas.drawCircle(model.cueDeflectEnd.x, model.cueDeflectEnd.y, table.ballRadius, dashedGhostBallPaint)
        }

        // 7. Interactive Ball Handles (Draggable Rings)
        if (prefs.isInteractiveHandlesEnabled) {
            drawInteractiveHandles(canvas)
        }
    }

    private fun drawAngleBracket(canvas: Canvas, origin: Vector2D, dirA: Vector2D, dirB: Vector2D) {
        val size = table.ballRadius * 0.9f
        val pA = origin + dirA * size
        val pB = origin + dirB * size
        val corner = origin + (dirA + dirB) * size

        val path = Path().apply {
            moveTo(pA.x, pA.y)
            lineTo(corner.x, corner.y)
            lineTo(pB.x, pB.y)
        }
        canvas.drawPath(path, angleBracketPaint)
    }

    private fun drawInteractiveHandles(canvas: Canvas) {
        val r = table.ballRadius
        val cross = r * 0.6f

        // 1. Cue Ball Handle (Cyan Ring + Crosshair)
        canvas.drawCircle(cueBallHandle.x, cueBallHandle.y, r, handleCuePaint)
        canvas.drawLine(cueBallHandle.x - cross, cueBallHandle.y, cueBallHandle.x + cross, cueBallHandle.y, handleCuePaint)
        canvas.drawLine(cueBallHandle.x, cueBallHandle.y - cross, cueBallHandle.x, cueBallHandle.y + cross, handleCuePaint)
        handleCenterPaint.color = Color.parseColor("#4400E5FF")
        canvas.drawCircle(cueBallHandle.x, cueBallHandle.y, cross * 0.5f, handleCenterPaint)

        // 2. Target Ball Handle (Orange Ring + Crosshair)
        canvas.drawCircle(targetBallHandle.x, targetBallHandle.y, r, handleTargetPaint)
        canvas.drawLine(targetBallHandle.x - cross, targetBallHandle.y, targetBallHandle.x + cross, targetBallHandle.y, handleTargetPaint)
        canvas.drawLine(targetBallHandle.x, targetBallHandle.y - cross, targetBallHandle.x, targetBallHandle.y + cross, handleTargetPaint)
        handleCenterPaint.color = Color.parseColor("#44FF9100")
        canvas.drawCircle(targetBallHandle.x, targetBallHandle.y, cross * 0.5f, handleCenterPaint)
    }
}
