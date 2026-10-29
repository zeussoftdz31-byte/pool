package com.mecasoft.aimingmaster.engine

import kotlin.math.*

data class Vector2D(val x: Float, val y: Float) {
    operator fun plus(other: Vector2D) = Vector2D(x + other.x, y + other.y)
    operator fun minus(other: Vector2D) = Vector2D(x - other.x, y - other.y)
    operator fun times(scalar: Float) = Vector2D(x * scalar, y * scalar)
    operator fun div(scalar: Float) = Vector2D(x / scalar, y / scalar)
    operator fun unaryMinus() = Vector2D(-x, -y)

    fun length(): Float = sqrt(x * x + y * y)
    fun lengthSquared(): Float = x * x + y * y
    fun distanceTo(other: Vector2D): Float = (this - other).length()

    fun normalized(): Vector2D {
        val l = length()
        return if (l > 0.00001f) this / l else Vector2D(1f, 0f)
    }

    fun dot(other: Vector2D): Float = x * other.x + y * other.y
    fun cross(other: Vector2D): Float = x * other.y - y * other.x
    fun angle(): Float = atan2(y, x)

    companion object {
        val ZERO = Vector2D(0f, 0f)
        fun fromAngle(angleRad: Float): Vector2D = Vector2D(cos(angleRad), sin(angleRad))
    }
}
