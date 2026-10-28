package com.poolaim.assist.physics

import kotlin.math.*

/**
 * Trajectory segment representation for a single leg of a ball's path.
 */
data class TrajectorySegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val wallHit: Int // 0: None, 1: Top, 2: Bottom, 3: Left, 4: Right
)

/**
 * Secondary ball collision (Carom / Combination shot) data.
 */
data class ComboCollision(
    val secondBallPos: Vector2D,
    val ghostBallPos: Vector2D,
    val secondBallTrajectory: BallTrajectory
)

/**
 * Complete simulated trajectory of a ball until it stops completely due to friction
 * or falls into a pocket.
 */
data class BallTrajectory(
    val segments: List<TrajectorySegment>,
    val stopX: Float,
    val stopY: Float,
    val pottedInPocket: Boolean,
    val pocketIndex: Int = -1,
    val comboCollision: ComboCollision? = null
)

/**
 * Exact mathematical ray-box cushion intersection and trajectory stopping engine.
 * Inspired by professional tools like Wizard Pool 8 and Jonathan Silva architecture.
 */
class LineIntersection(
    val endX: Float,
    val endY: Float,
    val wallHit: Int
) {
    companion object {
        const val WALL_NONE = 0
        const val WALL_TOP = 1
        const val WALL_BOTTOM = 2
        const val WALL_LEFT = 3
        const val WALL_RIGHT = 4

        /**
         * Calculates the collision point of a ray with the table cushions considering ball radius.
         */
        fun getLineIntersectionPoint(
            startX: Float,
            startY: Float,
            angleDeg: Float,
            tableLeft: Float,
            tableTop: Float,
            tableRight: Float,
            tableBottom: Float,
            ballRadius: Float
        ): LineIntersection {
            val radAngle = Math.toRadians(angleDeg.toDouble())

            // Direction vector components. Android Y increases downwards
            val dx = cos(radAngle).toFloat()
            val dy = (-sin(radAngle)).toFloat()

            var distanceParameter = Float.MAX_VALUE
            var wall = WALL_NONE

            // Effective cushion boundaries considering ball radius
            val effectiveLeftWall = tableLeft + ballRadius
            val effectiveRightWall = tableRight - ballRadius
            val effectiveTopWall = tableTop + ballRadius
            val effectiveBottomWall = tableBottom - ballRadius

            // 1. Moving Left
            if (dx < -0.0001f) {
                val distLeft = (effectiveLeftWall - startX) / dx
                if (distLeft >= -0.001f && distLeft < distanceParameter) {
                    val intersectY = startY + distLeft * dy
                    if (intersectY in effectiveTopWall..effectiveBottomWall) {
                        distanceParameter = distLeft
                        wall = WALL_LEFT
                    }
                }
            }

            // 2. Moving Right
            if (dx > 0.0001f) {
                val distRight = (effectiveRightWall - startX) / dx
                if (distRight >= -0.001f && distRight < distanceParameter) {
                    val intersectY = startY + distRight * dy
                    if (intersectY in effectiveTopWall..effectiveBottomWall) {
                        distanceParameter = distRight
                        wall = WALL_RIGHT
                    }
                }
            }

            // 3. Moving Up
            if (dy < -0.0001f) {
                val distTop = (effectiveTopWall - startY) / dy
                if (distTop >= -0.001f && distTop < distanceParameter) {
                    val intersectX = startX + distTop * dx
                    if (intersectX in effectiveLeftWall..effectiveRightWall) {
                        distanceParameter = distTop
                        wall = WALL_TOP
                    }
                }
            }

            // 4. Moving Down
            if (dy > 0.0001f) {
                val distBottom = (effectiveBottomWall - startY) / dy
                if (distBottom >= -0.001f && distBottom < distanceParameter) {
                    val intersectX = startX + distBottom * dx
                    if (intersectX in effectiveLeftWall..effectiveRightWall) {
                        distanceParameter = distBottom
                        wall = WALL_BOTTOM
                    }
                }
            }

            if (distanceParameter == Float.MAX_VALUE) {
                distanceParameter = max(tableRight - tableLeft, tableBottom - tableTop)
            }

            val endX = startX + distanceParameter * dx
            val endY = startY + distanceParameter * dy

            return LineIntersection(endX, endY, wall)
        }

        /**
         * Calculates reflected angle after hitting a cushion wall, modified by side spin (English).
         */
        fun getReflectedAngle(incidentAngleDeg: Float, wall: Int, sideSpin: Float = 0f): Float {
            var reflected = incidentAngleDeg
            when (wall) {
                WALL_TOP, WALL_BOTTOM -> reflected = 360f - incidentAngleDeg
                WALL_LEFT, WALL_RIGHT -> reflected = 180f - incidentAngleDeg
            }
            if (sideSpin != 0f) {
                val spinEffect = when (wall) {
                    WALL_TOP -> -sideSpin * 16f
                    WALL_BOTTOM -> sideSpin * 16f
                    WALL_LEFT -> -sideSpin * 16f
                    WALL_RIGHT -> sideSpin * 16f
                    else -> 0f
                }
                reflected += spinEffect
            }
            while (reflected < 0f) reflected += 360f
            while (reflected >= 360f) reflected -= 360f
            return reflected
        }

        /**
         * Traces the complete path of a ball until it stops completely due to friction,
         * drops into one of the 6 pockets, or collides with another ball (Combo / Carom shot).
         */
        fun traceBallPath(
            startX: Float,
            startY: Float,
            angleDeg: Float,
            totalDistance: Float,
            maxBounces: Int,
            tableLeft: Float,
            tableTop: Float,
            tableRight: Float,
            tableBottom: Float,
            ballRadius: Float,
            pockets: List<Vector2D>,
            pocketScale: Float = 1.0f,
            otherBalls: List<Vector2D> = emptyList(),
            sideSpin: Float = 0f,
            depth: Int = 0
        ): BallTrajectory {
            val segments = mutableListOf<TrajectorySegment>()
            var currX = startX
            var currY = startY
            var currAngle = angleDeg
            var remainingDist = totalDistance
            val pocketThreshold = ballRadius * 1.15f * pocketScale

            var stopX = startX
            var stopY = startY
            var isPotted = false
            var potIndex = -1
            var comboHit: ComboCollision? = null

            val twoR = ballRadius * 2.0f
            val twoRSq = twoR * twoR

            for (bounce in 0..maxBounces) {
                if (remainingDist <= 4f) break

                val hit = getLineIntersectionPoint(
                    currX, currY, currAngle,
                    tableLeft, tableTop, tableRight, tableBottom,
                    ballRadius
                )

                val distToCushion = hypot(hit.endX - currX, hit.endY - currY)
                val rad = Math.toRadians(currAngle.toDouble())
                val dx = cos(rad).toFloat()
                val dy = (-sin(rad)).toFloat()

                // Check for Pocket Intersection
                var hitPocket = false
                var distToPocket = Float.MAX_VALUE
                var closestPocketIdx = -1

                for ((idx, p) in pockets.withIndex()) {
                    val segDx = hit.endX - currX
                    val segDy = hit.endY - currY
                    val segLenSq = segDx * segDx + segDy * segDy
                    if (segLenSq > 0.001f) {
                        val t = (((p.x - currX) * segDx + (p.y - currY) * segDy) / segLenSq).coerceIn(0f, 1f)
                        val projX = currX + t * segDx
                        val projY = currY + t * segDy
                        val dist = hypot(p.x - projX, p.y - projY)

                        if (dist <= pocketThreshold) {
                            val dFromStart = hypot(p.x - currX, p.y - currY)
                            if (dFromStart < distToPocket && dFromStart <= remainingDist) {
                                distToPocket = dFromStart
                                hitPocket = true
                                closestPocketIdx = idx
                            }
                        }
                    }
                }

                // Check for Ball-to-Ball Collision (Multi-Ball Carom / Combo)
                var hitOtherBall: Vector2D? = null
                var minBallHitDist = Float.MAX_VALUE

                if (otherBalls.isNotEmpty() && depth == 0) {
                    for (ob in otherBalls) {
                        // Skip if it's the start point itself
                        val deltaX = ob.x - currX
                        val deltaY = ob.y - currY
                        val proj = deltaX * dx + deltaY * dy

                        if (proj > ballRadius * 0.5f && proj < distToCushion && proj < distToPocket && proj < remainingDist) {
                            val distSq = deltaX * deltaX + deltaY * deltaY
                            val perpSq = max(0f, distSq - proj * proj)

                            if (perpSq <= twoRSq) {
                                val hitDist = max(0f, proj - sqrt(twoRSq - perpSq))
                                if (hitDist in 0.1f..minBallHitDist) {
                                    minBallHitDist = hitDist
                                    hitOtherBall = ob
                                }
                            }
                        }
                    }
                }

                // Decision hierarchy: Pocket vs Ball vs Cushion vs Distance Expiry
                if (hitOtherBall != null && minBallHitDist < distToPocket && minBallHitDist < distToCushion) {
                    // Ball hits another object ball! (Combo Shot)
                    val ghostX = currX + dx * minBallHitDist
                    val ghostY = currY + dy * minBallHitDist

                    segments.add(TrajectorySegment(currX, currY, ghostX, ghostY, WALL_NONE))
                    stopX = ghostX
                    stopY = ghostY

                    val exitDx = hitOtherBall.x - ghostX
                    val exitDy = hitOtherBall.y - ghostY
                    val exitLen = max(0.001f, hypot(exitDx, exitDy))
                    var secondBallAngle = Math.toDegrees(atan2(-exitDy.toDouble(), exitDx.toDouble())).toFloat()
                    if (secondBallAngle < 0f) secondBallAngle += 360f

                    val cosCut = ((dx * exitDx + dy * exitDy) / exitLen).coerceIn(0f, 1f)
                    val remainingEnergy = max(0f, remainingDist - minBallHitDist)
                    val secondBallEnergy = remainingEnergy * (cosCut * cosCut)

                    // Trace second ball path
                    val secondBallTrajectory = traceBallPath(
                        startX = hitOtherBall.x,
                        startY = hitOtherBall.y,
                        angleDeg = secondBallAngle,
                        totalDistance = secondBallEnergy,
                        maxBounces = 2,
                        tableLeft = tableLeft,
                        tableTop = tableTop,
                        tableRight = tableRight,
                        tableBottom = tableBottom,
                        ballRadius = ballRadius,
                        pockets = pockets,
                        pocketScale = pocketScale,
                        otherBalls = emptyList(),
                        sideSpin = 0f,
                        depth = 1
                    )

                    comboHit = ComboCollision(hitOtherBall, Vector2D(ghostX, ghostY), secondBallTrajectory)
                    break
                } else if (hitPocket) {
                    val p = pockets[closestPocketIdx]
                    segments.add(TrajectorySegment(currX, currY, p.x, p.y, WALL_NONE))
                    stopX = p.x
                    stopY = p.y
                    isPotted = true
                    potIndex = closestPocketIdx
                    break
                } else if (distToCushion > remainingDist) {
                    val finalX = currX + remainingDist * dx
                    val finalY = currY + remainingDist * dy

                    segments.add(TrajectorySegment(currX, currY, finalX, finalY, WALL_NONE))
                    stopX = finalX
                    stopY = finalY
                    break
                } else {
                    segments.add(TrajectorySegment(currX, currY, hit.endX, hit.endY, hit.wallHit))
                    stopX = hit.endX
                    stopY = hit.endY

                    if (hit.wallHit == WALL_NONE) break

                    currX = hit.endX
                    currY = hit.endY
                    currAngle = getReflectedAngle(currAngle, hit.wallHit, sideSpin)
                    remainingDist = (remainingDist - distToCushion) * 0.82f
                }
            }

            return BallTrajectory(segments, stopX, stopY, isPotted, potIndex, comboHit)
        }
    }
}
