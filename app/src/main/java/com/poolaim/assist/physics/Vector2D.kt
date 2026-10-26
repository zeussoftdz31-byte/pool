package com.poolaim.assist.physics

import kotlin.math.*

/**
 * 2D Vector representation for high-precision billiard physics and trajectory calculations.
 */
data class Vector2D(var x: Float = 0f, var y: Float = 0f) {

    operator fun plus(other: Vector2D): Vector2D = Vector2D(x + other.x, y + other.y)

    operator fun minus(other: Vector2D): Vector2D = Vector2D(x - other.x, y - other.y)

    operator fun times(scalar: Float): Vector2D = Vector2D(x * scalar, y * scalar)

    operator fun div(scalar: Float): Vector2D = if (scalar != 0f) Vector2D(x / scalar, y / scalar) else Vector2D()

    fun length(): Float = sqrt(x * x + y * y)

    fun lengthSquared(): Float = x * x + y * y

    fun distanceTo(other: Vector2D): Float = (this - other).length()

    fun dot(other: Vector2D): Float = x * other.x + y * other.y

    fun normalized(): Vector2D {
        val len = length()
        return if (len > 0.0001f) this / len else Vector2D(0f, 0f)
    }

    /**
     * Returns a perpendicular vector (normal)
     */
    fun perpendicular(): Vector2D = Vector2D(-y, x)

    /**
     * Reflects this vector off a surface with normal vector n
     * Formula: r = v - 2*(v . n)*n
     */
    fun reflect(normal: Vector2D): Vector2D {
        val norm = normal.normalized()
        return this - (norm * (2f * this.dot(norm)))
    }

    /**
     * Angle in radians (-PI to PI)
     */
    fun angle(): Float = atan2(y, x)

    companion object {
        fun fromAngle(radians: Float, length: Float = 1f): Vector2D {
            return Vector2D(cos(radians) * length, sin(radians) * length)
        }
    }
}
