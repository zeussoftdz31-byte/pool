package com.mecasoft.aimingmaster.engine

import kotlin.math.*

object AimingMasterPhysics {

    fun computeVelocityFactor(lineAngleDeg: Float, secondaryAngleDeg: Float): Float {
        val diffRad = (lineAngleDeg - secondaryAngleDeg) * (Math.PI.toFloat() / 180f)
        return cos(diffRad) * 90f
    }

    fun computeDoubleAngle(thetaDeg: Float, isHorizontal: Boolean): Float {
        val t = (thetaDeg % 360f + 360f) % 360f
        return if (isHorizontal) {
            when {
                t < 90f -> 180f - 2f * t
                t < 180f -> 2f * t - 180f
                t < 270f -> 540f - 2f * t
                else -> 2f * t - 540f
            }
        } else {
            when {
                t < 90f -> 2f * t
                t < 180f -> 360f - 2f * t
                t < 270f -> 2f * t - 360f
                else -> 720f - 2f * t
            }
        }
    }

    fun applyPolynomialCorrection(
        mode: Int,
        theta2: Float,
        fCos: Float,
        isHorizontal: Boolean,
        lastWasHorizontal: Boolean = false,
        lastTheta2: Float = 0f
    ): Float {
        val dPow: Float = when (mode) {
            4, 5 -> { // Bank shot from cue ball
                3.0e-4f * theta2 * theta2 + 1.0005f * theta2
            }
            2 -> { // First cushion rebound of ball
                val factor = if (isHorizontal) {
                    val fc2 = fCos * fCos
                    val fc3 = fc2 * fCos
                    val fc4 = fc3 * fCos
                    (6.36e-8 * fc4 - 1.83873e-5 * fc3 + 0.0020056917 * fc2 - 0.0974962959 * fCos + 2.7922389487).toFloat()
                } else {
                    val fc2 = fCos * fCos
                    (9.2393e-6 * fc2 - 6.002806e-4 * fCos + 0.9999949915).toFloat()
                }
                factor * theta2
            }
            3 -> { // Second cushion rebound (3-Lines mode)
                val t = if (lastTheta2 > 0f) lastTheta2 else theta2
                val t2 = t * t
                val t3 = t2 * t
                val t4 = t3 * t

                when {
                    lastWasHorizontal && isHorizontal -> { // H -> H
                        (-1.5698e-6 * t2 + 2.811864e-4 * t3 - 0.0107807312 * t2 + 0.8822089285 * t).toFloat()
                    }
                    !lastWasHorizontal && !isHorizontal -> { // V -> V
                        (0.9359630168 * t - 0.0071650614 * t2).toFloat()
                    }
                    !lastWasHorizontal && isHorizontal -> { // V -> H
                        (-7.948e-7 * t4 + 1.8661e-4 * t3 - 0.0135976114 * t2 - 0.5269061041 * t + 179.8054699962).toFloat()
                    }
                    else -> { // H -> V
                        (-7.48091e-5 * t3 + 0.0307228063 * t2 - 5.2072128783 * t + 378.1665339508).toFloat()
                    }
                }
            }
            else -> theta2
        }

        return when {
            dPow < 0f -> 0f
            dPow > 180f -> theta2
            else -> dPow
        }
    }

    fun computeReflectedAngle(dPow: Float, isHorizontal: Boolean): Float {
        val reflected = dPow / 2f
        var finalAngle = if (isHorizontal) 360f - reflected else 180f - reflected
        while (finalAngle < 0f) finalAngle += 360f
        while (finalAngle >= 360f) finalAngle -= 360f
        return finalAngle
    }
}
