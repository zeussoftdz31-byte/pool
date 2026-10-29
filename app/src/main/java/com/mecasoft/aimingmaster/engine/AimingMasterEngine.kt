package com.mecasoft.aimingmaster.engine

import android.graphics.RectF
import com.mecasoft.aimingmaster.model.AimModel
import com.mecasoft.aimingmaster.model.LineSegment
import kotlin.math.*

class AimingMasterEngine(private val table: TableGeometry) {

    fun computeTrajectory(
        cueBall: Vector2D,
        aimAngleRad: Float,
        targetBall: Vector2D?,
        enableCushionShots: Boolean = true,
        enableThreeLines: Boolean = true,
        cueForceLevel: Int = 5
    ): AimModel {
        val segments = mutableListOf<LineSegment>()
        val bounceCircles = mutableListOf<Vector2D>()

        val aimDir = Vector2D.fromAngle(aimAngleRad)
        val ballR = table.ballRadius

        if (targetBall == null) {
            // Direct shot without target ball (Bank shot / free cue)
            val hit1 = raycastCushion(cueBall, aimDir, table.innerRect)
            if (hit1 != null) {
                segments.add(LineSegment(cueBall, hit1.point, true))
                if (enableCushionShots && !table.isPocketZone(hit1.point.x, hit1.point.y)) {
                    bounceCircles.add(hit1.point)
                    val thetaDeg = (aimAngleRad * 180f / Math.PI.toFloat())
                    val theta2 = AimingMasterPhysics.computeDoubleAngle(thetaDeg, hit1.isHorizontal)
                    val dPow = AimingMasterPhysics.applyPolynomialCorrection(4, theta2, 90f, hit1.isHorizontal)
                    val refAngleDeg = AimingMasterPhysics.computeReflectedAngle(dPow, hit1.isHorizontal)
                    val refDir = Vector2D.fromAngle(refAngleDeg * Math.PI.toFloat() / 180f)

                    val hit2 = raycastCushion(hit1.point, refDir, table.innerRect)
                    if (hit2 != null) {
                        segments.add(LineSegment(hit1.point, hit2.point, false))
                    }
                }
            }
            return AimModel(
                cueBallPos = cueBall,
                aimAngleRad = aimAngleRad,
                targetBallPos = null,
                ghostBallPos = null,
                segments = segments,
                bounceCircles = bounceCircles
            )
        }

        // Target Ball Contact Calculation
        val toTarget = targetBall - cueBall
        val proj = toTarget.dot(aimDir)
        val perpDistSq = toTarget.lengthSquared() - proj * proj
        val twoR = ballR * 2.0f

        val ghostBallPos: Vector2D
        if (proj > 0f && perpDistSq < twoR * twoR) {
            val offset = sqrt(max(0f, twoR * twoR - perpDistSq))
            val contactDist = proj - offset
            ghostBallPos = cueBall + aimDir * contactDist
        } else {
            // Virtual contact point
            ghostBallPos = targetBall - aimDir * twoR
        }

        // Primary Ray: Cue Ball -> Ghost Ball
        segments.add(LineSegment(cueBall, ghostBallPos, true))

        // Target Ball Direction
        val dirTarget = (targetBall - ghostBallPos).normalized()
        val targetAngleRad = dirTarget.angle()
        val lineAngleDeg = aimAngleRad * 180f / Math.PI.toFloat()
        val targetAngleDeg = targetAngleRad * 180f / Math.PI.toFloat()
        val fCos = AimingMasterPhysics.computeVelocityFactor(lineAngleDeg, targetAngleDeg)

        // Cue Deflection Ray
        val dot = aimDir.dot(dirTarget)
        val dirDeflect = (aimDir - dirTarget * dot).normalized()
        val deflectDist = ballR * (2.0f + cueForceLevel * 0.8f)
        val cueDeflectEnd = ghostBallPos + dirDeflect * deflectDist

        // Raycast Target Ball to Cushion 1
        val hit1 = raycastCushion(targetBall, dirTarget, table.innerRect)
        if (hit1 != null) {
            segments.add(LineSegment(targetBall, hit1.point, false))

            val inPocket1 = table.isPocketZone(hit1.point.x, hit1.point.y)
            if (enableCushionShots && !inPocket1) {
                bounceCircles.add(hit1.point)

                val theta2 = AimingMasterPhysics.computeDoubleAngle(targetAngleDeg, hit1.isHorizontal)
                val dPow1 = AimingMasterPhysics.applyPolynomialCorrection(2, theta2, fCos, hit1.isHorizontal)
                val refAngleDeg1 = AimingMasterPhysics.computeReflectedAngle(dPow1, hit1.isHorizontal)
                val refDir1 = Vector2D.fromAngle(refAngleDeg1 * Math.PI.toFloat() / 180f)

                // Cushion 2 Rebound
                val hit2 = raycastCushion(hit1.point, refDir1, table.innerRect)
                if (hit2 != null) {
                    segments.add(LineSegment(hit1.point, hit2.point, false))

                    val inPocket2 = table.isPocketZone(hit2.point.x, hit2.point.y)
                    if (enableThreeLines && !inPocket2) {
                        bounceCircles.add(hit2.point)

                        val theta2_2 = AimingMasterPhysics.computeDoubleAngle(refAngleDeg1, hit2.isHorizontal)
                        val dPow2 = AimingMasterPhysics.applyPolynomialCorrection(
                            3, theta2_2, fCos, hit2.isHorizontal,
                            lastWasHorizontal = hit1.isHorizontal,
                            lastTheta2 = theta2
                        )
                        val refAngleDeg2 = AimingMasterPhysics.computeReflectedAngle(dPow2, hit2.isHorizontal)
                        val refDir2 = Vector2D.fromAngle(refAngleDeg2 * Math.PI.toFloat() / 180f)

                        val hit3 = raycastCushion(hit2.point, refDir2, table.innerRect)
                        if (hit3 != null) {
                            segments.add(LineSegment(hit2.point, hit3.point, false))
                        }
                    }
                }
            }
        }

        return AimModel(
            cueBallPos = cueBall,
            aimAngleRad = aimAngleRad,
            targetBallPos = targetBall,
            ghostBallPos = ghostBallPos,
            cueDeflectEnd = cueDeflectEnd,
            fanPoint = ghostBallPos,
            segments = segments,
            bounceCircles = bounceCircles
        )
    }

    private data class RayHit(val point: Vector2D, val isHorizontal: Boolean)

    private fun raycastCushion(start: Vector2D, dir: Vector2D, rect: RectF): RayHit? {
        var minT = Float.MAX_VALUE
        var hitX = 0f
        var hitY = 0f
        var isH = false

        if (dir.x > 0.00001f) {
            val t = (rect.right - start.x) / dir.x
            if (t in 0.001f..<minT) {
                val y = start.y + dir.y * t
                if (y in rect.top..rect.bottom) {
                    minT = t; hitX = rect.right; hitY = y; isH = false
                }
            }
        } else if (dir.x < -0.00001f) {
            val t = (rect.left - start.x) / dir.x
            if (t in 0.001f..<minT) {
                val y = start.y + dir.y * t
                if (y in rect.top..rect.bottom) {
                    minT = t; hitX = rect.left; hitY = y; isH = false
                }
            }
        }

        if (dir.y > 0.00001f) {
            val t = (rect.bottom - start.y) / dir.y
            if (t in 0.001f..<minT) {
                val x = start.x + dir.x * t
                if (x in rect.left..rect.right) {
                    minT = t; hitX = x; hitY = rect.bottom; isH = true
                }
            }
        } else if (dir.y < -0.00001f) {
            val t = (rect.top - start.y) / dir.y
            if (t in 0.001f..<minT) {
                val x = start.x + dir.x * t
                if (x in rect.left..rect.right) {
                    minT = t; hitX = x; hitY = rect.top; isH = true
                }
            }
        }

        return if (minT < Float.MAX_VALUE) RayHit(Vector2D(hitX, hitY), isH) else null
    }
}
