package com.poolaim.assist.physics

import android.graphics.RectF
import kotlin.math.*

/**
 * High-performance deterministic 2D Multi-Body Billiards Physics Engine.
 * Simulates simultaneous collisions, momentum exchange, spin follow/draw/throw,
 * felt rolling friction, cushion damping, and pocketing for all balls on the table.
 */
object MultiBallSimulator {

    data class TrajectoryPoint(
        val x: Float,
        val y: Float
    )

    data class BallTrajectoryPath(
        val ballId: Int, // 0: Cue Ball, 1..N: Object Balls
        val initialPos: Vector2D,
        val points: List<TrajectoryPoint>,
        val stopX: Float,
        val stopY: Float,
        val isPotted: Boolean,
        val potIndex: Int,
        val ghosts: List<Vector2D>,
        val isCueBall: Boolean,
        val roleOrder: Int // 0: Cue, 1: Primary Target, 2: Secondary Combo, 3+: Tertiary / Scatter
    )

    data class SimulationResult(
        val activePaths: List<BallTrajectoryPath>,
        val cueBallScratch: Boolean,
        val scratchPocketIndex: Int
    )

    private class SimBall(
        val id: Int,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var spinX: Float = 0f,
        var spinY: Float = 0f,
        val initialPos: Vector2D,
        val isCue: Boolean
    ) {
        var isPotted = false
        var potIndex = -1
        var totalDistMoved = 0f
        var roleOrder = if (isCue) 0 else -1
        val points = mutableListOf<TrajectoryPoint>()
        val ghosts = mutableListOf<Vector2D>()
        var lastRecordedX = x
        var lastRecordedY = y

        fun recordPoint(px: Float, py: Float, force: Boolean = false) {
            val dSq = (px - lastRecordedX) * (px - lastRecordedX) + (py - lastRecordedY) * (py - lastRecordedY)
            if (force || dSq >= 36f) { // ~6px threshold
                points.add(TrajectoryPoint(px, py))
                lastRecordedX = px
                lastRecordedY = py
            }
        }
    }

    /**
     * Executes the full multi-body physics simulation.
     */
    fun simulate(
        cuePos: Vector2D,
        aimAngleRad: Float,
        powerPercent: Float,
        spinX: Float,
        spinY: Float,
        objectBalls: List<Vector2D>,
        tableBounds: RectF,
        ballRadius: Float,
        pockets: List<Vector2D>,
        pocketScale: Float = 1.0f,
        enableBankShots: Boolean = true,
        enableComboShots: Boolean = true,
        enableSpinPhysics: Boolean = true
    ): SimulationResult {
        val tableW = tableBounds.width()
        val tableH = tableBounds.height()
        if (tableW <= 10f || tableH <= 10f || ballRadius <= 1f) {
            return SimulationResult(emptyList(), false, -1)
        }

        // 1. Initial Velocity & Direction
        val safePower = powerPercent.coerceIn(0.15f, 1.0f)
        val maxSpeed = tableW * 2.8f
        val cueSpeed = maxSpeed * (0.30f + 0.70f * safePower)

        val initDirX = cos(aimAngleRad.toDouble()).toFloat()
        val initDirY = sin(aimAngleRad.toDouble()).toFloat()

        // 2. Initialize Cue Ball
        val simBalls = mutableListOf<SimBall>()
        val cueBall = SimBall(
            id = 0,
            x = cuePos.x,
            y = cuePos.y,
            vx = initDirX * cueSpeed,
            vy = initDirY * cueSpeed,
            spinX = if (enableSpinPhysics) spinX else 0f,
            spinY = if (enableSpinPhysics) spinY else 0f,
            initialPos = Vector2D(cuePos.x, cuePos.y),
            isCue = true
        )
        cueBall.points.add(TrajectoryPoint(cuePos.x, cuePos.y))
        simBalls.add(cueBall)

        // 3. Initialize Object Balls (Filter out any invalid or overlapping with cue)
        val minCueDistSq = (ballRadius * 1.5f) * (ballRadius * 1.5f)
        val margin = ballRadius * 3.0f
        val validLeft = tableBounds.left - margin
        val validRight = tableBounds.right + margin
        val validTop = tableBounds.top - margin
        val validBottom = tableBounds.bottom + margin

        var objIdCounter = 1
        for (ob in objectBalls) {
            val dCueSq = (ob.x - cuePos.x) * (ob.x - cuePos.x) + (ob.y - cuePos.y) * (ob.y - cuePos.y)
            if (dCueSq > minCueDistSq && ob.x in validLeft..validRight && ob.y in validTop..validBottom) {
                val ball = SimBall(
                    id = objIdCounter++,
                    x = ob.x,
                    y = ob.y,
                    vx = 0f,
                    vy = 0f,
                    initialPos = Vector2D(ob.x, ob.y),
                    isCue = false
                )
                ball.points.add(TrajectoryPoint(ob.x, ob.y))
                simBalls.add(ball)
            }
        }

        // 4. Boundaries & Pocket Thresholds
        val effLeft = tableBounds.left + ballRadius
        val effRight = tableBounds.right - ballRadius
        val effTop = tableBounds.top + ballRadius
        val effBottom = tableBounds.bottom - ballRadius
        val pocketThreshold = ballRadius * 1.25f * pocketScale
        val pocketThresholdSq = pocketThreshold * pocketThreshold

        val twoR = ballRadius * 2.0f
        val twoRSq = twoR * twoR
        val restitution = 0.95f
        val cushionDamping = 0.82f
        val feltFrictionRate = 520f // pixels per second^2 deceleration

        val dt = 0.0035f // 3.5ms time slice
        val maxSteps = 220

        var cueScratch = false
        var scratchPocket = -1

        // 5. Physics Sub-Stepping Loop
        for (step in 0 until maxSteps) {
            var anyMoving = false

            // A. Move each active ball & check pockets / cushions
            for (ball in simBalls) {
                if (ball.isPotted) continue

                val speed = hypot(ball.vx, ball.vy)
                if (speed > 1.5f) {
                    anyMoving = true
                    ball.totalDistMoved += speed * dt

                    // Update Position
                    ball.x += ball.vx * dt
                    ball.y += ball.vy * dt

                    // Check Pockets
                    for ((pIdx, p) in pockets.withIndex()) {
                        val dSq = (ball.x - p.x) * (ball.x - p.x) + (ball.y - p.y) * (ball.y - p.y)
                        if (dSq <= pocketThresholdSq) {
                            ball.isPotted = true
                            ball.potIndex = pIdx
                            ball.vx = 0f
                            ball.vy = 0f
                            ball.recordPoint(p.x, p.y, force = true)
                            if (ball.isCue) {
                                cueScratch = true
                                scratchPocket = pIdx
                            }
                            break
                        }
                    }
                    if (ball.isPotted) continue

                    // Check Cushion Rebounds
                    if (enableBankShots || !ball.isCue) {
                        if (ball.x <= effLeft && ball.vx < 0f) {
                            ball.x = effLeft
                            ball.vx = -ball.vx * cushionDamping
                            if (ball.isCue && enableSpinPhysics) {
                                ball.vy -= ball.spinX * 0.20f * abs(ball.vx)
                            }
                            ball.recordPoint(ball.x, ball.y, force = true)
                        } else if (ball.x >= effRight && ball.vx > 0f) {
                            ball.x = effRight
                            ball.vx = -ball.vx * cushionDamping
                            if (ball.isCue && enableSpinPhysics) {
                                ball.vy += ball.spinX * 0.20f * abs(ball.vx)
                            }
                            ball.recordPoint(ball.x, ball.y, force = true)
                        }

                        if (ball.y <= effTop && ball.vy < 0f) {
                            ball.y = effTop
                            ball.vy = -ball.vy * cushionDamping
                            if (ball.isCue && enableSpinPhysics) {
                                ball.vx += ball.spinX * 0.20f * abs(ball.vy)
                            }
                            ball.recordPoint(ball.x, ball.y, force = true)
                        } else if (ball.y >= effBottom && ball.vy > 0f) {
                            ball.y = effBottom
                            ball.vy = -ball.vy * cushionDamping
                            if (ball.isCue && enableSpinPhysics) {
                                ball.vx -= ball.spinX * 0.20f * abs(ball.vy)
                            }
                            ball.recordPoint(ball.x, ball.y, force = true)
                        }
                    }

                    // Felt Rolling Friction
                    val frictionDecel = feltFrictionRate * dt
                    if (speed <= frictionDecel) {
                        ball.vx = 0f
                        ball.vy = 0f
                    } else {
                        val factor = (speed - frictionDecel) / speed
                        ball.vx *= factor
                        ball.vy *= factor
                    }

                    ball.recordPoint(ball.x, ball.y)
                } else {
                    ball.vx = 0f
                    ball.vy = 0f
                }
            }

            if (!anyMoving) break

            // B. Check Ball-to-Ball Collisions
            val ballCount = simBalls.size
            for (i in 0 until ballCount) {
                val b1 = simBalls[i]
                if (b1.isPotted) continue

                for (j in i + 1 until ballCount) {
                    val b2 = simBalls[j]
                    if (b2.isPotted) continue

                    // If combo shots are disabled, only check collisions involving cue ball
                    if (!enableComboShots && !b1.isCue && !b2.isCue) continue

                    val dx = b2.x - b1.x
                    val dy = b2.y - b1.y
                    val distSq = dx * dx + dy * dy

                    if (distSq <= twoRSq) {
                        val dist = max(0.0001f, sqrt(distSq))
                        val nx = dx / dist
                        val ny = dy / dist

                        val relVx = b1.vx - b2.vx
                        val relVy = b1.vy - b2.vy
                        val velAlongNormal = relVx * nx + relVy * ny

                        if (velAlongNormal > 0f) { // Moving toward each other
                            // Assign hierarchical role order
                            if (b1.roleOrder >= 0 && b2.roleOrder == -1) {
                                b2.roleOrder = b1.roleOrder + 1
                            } else if (b2.roleOrder >= 0 && b1.roleOrder == -1) {
                                b1.roleOrder = b2.roleOrder + 1
                            }

                            // Momentum exchange along normal
                            val impulse = ((1f + restitution) * velAlongNormal) * 0.5f
                            b1.vx -= impulse * nx
                            b1.vy -= impulse * ny
                            b2.vx += impulse * nx
                            b2.vy += impulse * ny

                            // Record Ghost Ball at contact point
                            b1.ghosts.add(Vector2D(b1.x, b1.y))
                            b1.recordPoint(b1.x, b1.y, force = true)
                            b2.recordPoint(b2.x, b2.y, force = true)

                            // Apply Cue Ball Spin physics upon collision
                            if (b1.isCue && enableSpinPhysics) {
                                if (b1.spinY < -0.05f) {
                                    // Top Spin: follow-through forward boost
                                    val follow = (-b1.spinY) * impulse * 0.70f
                                    b1.vx += initDirX * follow
                                    b1.vy += initDirY * follow
                                } else if (b1.spinY > 0.05f) {
                                    // Back Spin: draw-back reverse impulse
                                    val draw = b1.spinY * impulse * 0.80f
                                    b1.vx -= initDirX * draw
                                    b1.vy -= initDirY * draw
                                }
                                // Side Spin: cut throw angle on target ball
                                if (abs(b1.spinX) > 0.05f) {
                                    val throwF = b1.spinX * impulse * 0.10f
                                    b2.vx -= ny * throwF
                                    b2.vy += nx * throwF
                                }
                            }

                            // Overlap correction (prevent sticking)
                            val overlap = twoR - dist
                            b1.x -= nx * overlap * 0.51f
                            b1.y -= ny * overlap * 0.51f
                            b2.x += nx * overlap * 0.51f
                            b2.y += ny * overlap * 0.51f
                        }
                    }
                }
            }
        }

        // 6. Assemble Result Paths
        val activePaths = mutableListOf<BallTrajectoryPath>()
        for (ball in simBalls) {
            if (ball.totalDistMoved > ballRadius * 0.15f || ball.isCue) {
                // Ensure final stop point is recorded
                ball.recordPoint(ball.x, ball.y, force = true)
                if (ball.points.size < 2) {
                    ball.points.add(TrajectoryPoint(ball.x, ball.y))
                }

                activePaths.add(
                    BallTrajectoryPath(
                        ballId = ball.id,
                        initialPos = ball.initialPos,
                        points = ball.points,
                        stopX = ball.x,
                        stopY = ball.y,
                        isPotted = ball.isPotted,
                        potIndex = ball.potIndex,
                        ghosts = ball.ghosts,
                        isCueBall = ball.isCue,
                        roleOrder = ball.roleOrder
                    )
                )
            }
        }

        // Sort paths so Cue Ball is first, then Primary (1), Secondary (2), etc.
        activePaths.sortBy { it.roleOrder }

        return SimulationResult(
            activePaths = activePaths,
            cueBallScratch = cueScratch,
            scratchPocketIndex = scratchPocket
        )
    }
}
