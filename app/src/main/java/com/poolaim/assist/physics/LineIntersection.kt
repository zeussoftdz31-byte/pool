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
 * Complete simulated trajectory of a ball until it stops completely due to friction
 * or falls into a pocket.
 */
data class BallTrajectory(
    val segments: List<TrajectorySegment>,
    val stopX: Float,
    val stopY: Float,
    val pottedInPocket: Boolean,
    val pocketIndex: Int = -1
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
         * Calculates reflected angle after hitting a cushion wall.
         */
        fun getReflectedAngle(incidentAngleDeg: Float, wall: Int): Float {
            var reflected = incidentAngleDeg
            when (wall) {
                WALL_TOP, WALL_BOTTOM -> reflected = 360f - incidentAngleDeg
                WALL_LEFT, WALL_RIGHT -> reflected = 180f - incidentAngleDeg
            }
            while (reflected < 0f) reflected += 360f
            while (reflected >= 360f) reflected -= 360f
            return reflected
        }

        /**
         * Traces the complete path of a ball until it stops completely due to friction
         * or drops into one of the 6 pockets, simulating exact Wizard Pool 8 mechanics.
         *
         * @param startX Starting X coordinate
         * @param startY Starting Y coordinate
         * @param angleDeg Direction in degrees
         * @param totalDistance Total distance the ball can roll based on strike power
         * @param maxBounces Maximum cushion reflections to trace (e.g. 2 or 3)
         * @param pockets List of 6 pocket coordinates
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
            pocketScale: Float = 1.0f
        ): BallTrajectory {
            val segments = mutableListOf<TrajectorySegment>()
            var currX = startX
            var currY = startY
            var currAngle = angleDeg
            var remainingDist = totalDistance
            // Realistic pocket jaw opening threshold (1.15x ball radius)
            val pocketThreshold = ballRadius * 1.15f * pocketScale

            var stopX = startX
            var stopY = startY
            var isPotted = false
            var potIndex = -1

            for (bounce in 0..maxBounces) {
                if (remainingDist <= 4f) break

                // Find ray intersection with cushion
                val hit = getLineIntersectionPoint(
                    currX, currY, currAngle,
                    tableLeft, tableTop, tableRight, tableBottom,
                    ballRadius
                )

                val distToCushion = hypot(hit.endX - currX, hit.endY - currY)

                // Check if segment crosses into any pocket
                var hitPocket = false
                var distToPocket = Float.MAX_VALUE
                var closestPocketIdx = -1

                for ((idx, p) in pockets.withIndex()) {
                    // Check projection of pocket on segment
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

                if (hitPocket) {
                    val p = pockets[closestPocketIdx]
                    segments.add(TrajectorySegment(currX, currY, p.x, p.y, WALL_NONE))
                    stopX = p.x
                    stopY = p.y
                    isPotted = true
                    potIndex = closestPocketIdx
                    break
                }

                // If ball doesn't reach the cushion with remaining energy
                if (distToCushion > remainingDist) {
                    val rad = Math.toRadians(currAngle.toDouble())
                    val dx = cos(rad).toFloat()
                    val dy = (-sin(rad)).toFloat()
                    val finalX = currX + remainingDist * dx
                    val finalY = currY + remainingDist * dy

                    segments.add(TrajectorySegment(currX, currY, finalX, finalY, WALL_NONE))
                    stopX = finalX
                    stopY = finalY
                    break
                } else {
                    // Ball reaches the cushion and bounces
                    segments.add(TrajectorySegment(currX, currY, hit.endX, hit.endY, hit.wallHit))
                    stopX = hit.endX
                    stopY = hit.endY

                    if (hit.wallHit == WALL_NONE) break

                    currX = hit.endX
                    currY = hit.endY
                    currAngle = getReflectedAngle(currAngle, hit.wallHit)
                    // Deduct distance and apply cushion restitution energy loss (approx 20% loss)
                    remainingDist = (remainingDist - distToCushion) * 0.80f
                }
            }

            return BallTrajectory(segments, stopX, stopY, isPotted, potIndex)
        }
    }
}
