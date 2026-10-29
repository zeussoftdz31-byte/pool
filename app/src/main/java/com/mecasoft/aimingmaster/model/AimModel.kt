package com.mecasoft.aimingmaster.model

import com.mecasoft.aimingmaster.engine.Vector2D

data class LineSegment(
    val start: Vector2D,
    val end: Vector2D,
    val isPrimary: Boolean = true
) {
    val x1: Float get() = start.x
    val y1: Float get() = start.y
    val x2: Float get() = end.x
    val y2: Float get() = end.y
}

data class AimModel(
    val cueBallPos: Vector2D,
    val aimAngleRad: Float,
    val targetBallPos: Vector2D?,
    val ghostBallPos: Vector2D?,
    val cueDeflectEnd: Vector2D? = null,
    val fanPoint: Vector2D? = null,
    val segments: List<LineSegment> = emptyList(),
    val bounceCircles: List<Vector2D> = emptyList()
)
