package com.poolaim.assist.physics

import android.graphics.RectF
import kotlin.math.*

/**
 * High-performance, real-time Billiard Physics Engine.
 * Calculates:
 * 1. Ray-to-Circle impact points (Ghost ball position).
 * 2. Cut angle & momentum transfer (Elastic collision).
 * 3. Target ball trajectory and pocket alignment.
 * 4. Cue ball deflection trajectory (Tangent vector).
 * 5. Cushion bank reflections (Wall bounces).
 * 6. Power & rolling friction simulation (Exact stopping position).
 */
class PoolPhysicsEngine {

    data class Ball(
        val id: Int,
        var position: Vector2D,
        var radius: Float = 22f,
        val isCueBall: Boolean = false
    )

    data class Pocket(
        val position: Vector2D,
        val radius: Float = 36f
    )

    data class TrajectoryResult(
        val cuePath: List<Vector2D>,
        val ghostBallPos: Vector2D?,
        val targetPath: List<Vector2D>,
        val hitBall: Ball?,
        val willPot: Boolean,
        val targetPocket: Pocket?,
        val cueStoppingPoint: Vector2D,
        val targetStoppingPoint: Vector2D?
    )

    // Table boundaries (cushions)
    var tableBounds: RectF = RectF(100f, 200f, 980f, 1800f)

    // Six standard pockets (Corners + Middles)
    var pockets: List<Pocket> = emptyList()

    // Physics parameters
    var ballRadius: Float = 22f
    var rollingFriction: Float = 0.055f // Deceleration rate
    var cushionRestitution: Float = 0.86f // Energy retained after wall bounce
    var maxBounces: Int = 2

    init {
        updatePocketsFromBounds()
    }

    fun updatePocketsFromBounds() {
        val left = tableBounds.left
        val right = tableBounds.right
        val top = tableBounds.top
        val bottom = tableBounds.bottom
        val midX = (left + right) / 2f
        val midY = (top + bottom) / 2f
        val pocketR = ballRadius * 1.6f

        pockets = listOf(
            Pocket(Vector2D(left, top), pocketR),         // Top-Left
            Pocket(Vector2D(midX, top), pocketR),         // Top-Center
            Pocket(Vector2D(right, top), pocketR),        // Top-Right
            Pocket(Vector2D(left, bottom), pocketR),      // Bottom-Left
            Pocket(Vector2D(midX, bottom), pocketR),      // Bottom-Center
            Pocket(Vector2D(right, bottom), pocketR)      // Bottom-Right
        )
    }

    /**
     * Simulates the shot in real time.
     * @param cueBall The white cue ball
     * @param targetBalls All other balls on table
     * @param aimDirection Normalized vector of cue aim
     * @param shotPower 0.0f to 1.0f (slider force)
     */
    fun calculateTrajectory(
        cueBall: Ball,
        targetBalls: List<Ball>,
        aimDirection: Vector2D,
        shotPower: Float = 0.7f
    ): TrajectoryResult {
        val dir = aimDirection.normalized()
        val cueStart = cueBall.position

        // 1. Calculate max travel distance based on shot power and friction
        // v = power * max_speed, distance = v^2 / (2 * friction)
        val maxSpeed = 3500f * shotPower.coerceIn(0.1f, 1.0f)
        val totalDistanceAvailable = (maxSpeed * maxSpeed) / (2f * rollingFriction * 1000f)

        // 2. Find closest target ball collision along the aim ray
        var closestHitBall: Ball? = null
        var minHitDistance = Float.MAX_VALUE
        var ghostBallCenter: Vector2D? = null

        val collisionDistance = ballRadius * 2f // Two balls touch when distance = 2R

        for (ball in targetBalls) {
            if (ball.isCueBall) continue

            // Vector from cue to object ball
            val toBall = ball.position - cueStart
            val projection = toBall.dot(dir)

            // Ball is behind the shot
            if (projection <= 0f) continue

            // Perpendicular distance from ray to ball center
            val perpDistSq = toBall.lengthSquared() - (projection * projection)
            val collisionDistSq = collisionDistance * collisionDistance

            if (perpDistSq < collisionDistSq) {
                // Ray intersects the 2R sphere around the target ball
                val backOffset = sqrt(collisionDistSq - perpDistSq)
                val hitDistance = projection - backOffset

                if (hitDistance in 0f..minHitDistance) {
                    minHitDistance = hitDistance
                    closestHitBall = ball
                    ghostBallCenter = cueStart + (dir * hitDistance)
                }
            }
        }

        // 3. Build Cue Ball Path
        val cuePath = mutableListOf<Vector2D>()
        cuePath.add(cueStart)

        var cueStoppingPos: Vector2D
        val targetPath = mutableListOf<Vector2D>()
        var targetStoppingPos: Vector2D? = null
        var willPot = false
        var targetPocket: Pocket? = null

        if (closestHitBall != null && ghostBallCenter != null) {
            // Path to Ghost Ball
            cuePath.add(ghostBallCenter)
            val distanceToGhost = minHitDistance
            val remainingDistance = (totalDistanceAvailable - distanceToGhost).coerceAtLeast(0f)

            // Line of Centers (direction target ball will travel)
            val lineOfCenters = (closestHitBall.position - ghostBallCenter).normalized()

            // Cut angle: cos(theta) = dir . lineOfCenters
            val cosTheta = dir.dot(lineOfCenters).coerceIn(-1f, 1f)
            val sinTheta = sqrt(1f - (cosTheta * cosTheta))

            // Energy & Distance distribution
            // Target ball receives energy proportional to cos^2(theta)
            val targetDistance = remainingDistance * (cosTheta * cosTheta)
            // Cue ball retains energy proportional to sin^2(theta)
            val cueDeflectionDistance = remainingDistance * (sinTheta * sinTheta)

            // 4. Trace Target Ball Path with Cushions and Pockets
            val targetSimulation = tracePathWithCushions(
                startPos = closestHitBall.position,
                direction = lineOfCenters,
                maxDistance = targetDistance,
                maxBounces = maxBounces
            )
            targetPath.addAll(targetSimulation.points)
            targetStoppingPos = targetSimulation.stoppingPoint

            // Check if target ball enters any pocket
            for (pt in targetPath) {
                for (pocket in pockets) {
                    if (pt.distanceTo(pocket.position) <= pocket.radius) {
                        willPot = true
                        targetPocket = pocket
                        break
                    }
                }
                if (willPot) break
            }

            // 5. Cue Ball Post-Impact Deflection (Tangent Vector, 90 degrees from line of centers)
            val tangentDir = (dir - (lineOfCenters * cosTheta)).normalized()
            val cueDeflectionSimulation = tracePathWithCushions(
                startPos = ghostBallCenter,
                direction = tangentDir,
                maxDistance = cueDeflectionDistance,
                maxBounces = maxBounces
            )
            cuePath.addAll(cueDeflectionSimulation.points.drop(1))
            cueStoppingPos = cueDeflectionSimulation.stoppingPoint

        } else {
            // No ball hit; cue ball travels until stopping or bouncing off cushions
            val directSimulation = tracePathWithCushions(
                startPos = cueStart,
                direction = dir,
                maxDistance = totalDistanceAvailable,
                maxBounces = maxBounces
            )
            cuePath.addAll(directSimulation.points.drop(1))
            cueStoppingPos = directSimulation.stoppingPoint
        }

        return TrajectoryResult(
            cuePath = cuePath,
            ghostBallPos = ghostBallCenter,
            targetPath = targetPath,
            hitBall = closestHitBall,
            willPot = willPot,
            targetPocket = targetPocket,
            cueStoppingPoint = cueStoppingPos,
            targetStoppingPoint = targetStoppingPos
        )
    }

    data class PathSimulation(
        val points: List<Vector2D>,
        val stoppingPoint: Vector2D
    )

    /**
     * Traces a trajectory reflecting off table cushions until distance runs out or max bounces reached.
     */
    private fun tracePathWithCushions(
        startPos: Vector2D,
        direction: Vector2D,
        maxDistance: Float,
        maxBounces: Int
    ): PathSimulation {
        val points = mutableListOf<Vector2D>()
        points.add(startPos)

        var currentPos = startPos
        var currentDir = direction.normalized()
        var remainingDist = maxDistance
        var bounces = 0

        val minX = tableBounds.left + ballRadius
        val maxX = tableBounds.right - ballRadius
        val minY = tableBounds.top + ballRadius
        val maxY = tableBounds.bottom - ballRadius

        while (remainingDist > 1f && bounces <= maxBounces) {
            // Find distance to each of the 4 cushions
            var distToWall = Float.MAX_VALUE
            var wallNormal = Vector2D(0f, 0f)

            // Right wall
            if (currentDir.x > 0.0001f) {
                val d = (maxX - currentPos.x) / currentDir.x
                if (d in 0.001f..distToWall) {
                    distToWall = d
                    wallNormal = Vector2D(-1f, 0f)
                }
            }
            // Left wall
            else if (currentDir.x < -0.0001f) {
                val d = (minX - currentPos.x) / currentDir.x
                if (d in 0.001f..distToWall) {
                    distToWall = d
                    wallNormal = Vector2D(1f, 0f)
                }
            }

            // Bottom wall
            if (currentDir.y > 0.0001f) {
                val d = (maxY - currentPos.y) / currentDir.y
                if (d in 0.001f..distToWall) {
                    distToWall = d
                    wallNormal = Vector2D(0f, -1f)
                }
            }
            // Top wall
            else if (currentDir.y < -0.0001f) {
                val d = (minY - currentPos.y) / currentDir.y
                if (d in 0.001f..distToWall) {
                    distToWall = d
                    wallNormal = Vector2D(0f, 1f)
                }
            }

            if (distToWall <= remainingDist) {
                // Ball hits cushion
                val hitPoint = currentPos + (currentDir * distToWall)
                points.add(hitPoint)

                currentPos = hitPoint
                remainingDist = (remainingDist - distToWall) * cushionRestitution
                currentDir = currentDir.reflect(wallNormal).normalized()
                bounces++
            } else {
                // Ball runs out of distance before reaching next wall (Exact stopping position)
                val stopPoint = currentPos + (currentDir * remainingDist)
                points.add(stopPoint)
                currentPos = stopPoint
                break
            }
        }

        return PathSimulation(points, currentPos)
    }
}
