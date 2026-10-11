package com.scanner3d.gl

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Turntable camera orbiting a target point; Y is up (ARCore worlds are gravity aligned). */
class OrbitCamera {
    private var targetX = 0f
    private var targetY = 0f
    private var targetZ = 0f
    private var distance = 1f
    private var yaw = 0.6f
    private var pitch = 0.35f

    private var homeTarget = floatArrayOf(0f, 0f, 0f)
    private var homeDistance = 1f
    private var homeYaw = 0.6f
    private var radius = 0.5f

    val fovYDeg = 45f

    @Synchronized
    fun frame(cx: Float, cy: Float, cz: Float, boundsRadius: Float, initialYaw: Float) {
        radius = max(boundsRadius, 0.01f)
        homeTarget = floatArrayOf(cx, cy, cz)
        homeDistance = radius / tan(Math.toRadians(fovYDeg / 2.0)).toFloat() * 1.15f
        homeYaw = initialYaw
        reset()
    }

    @Synchronized
    fun reset() {
        targetX = homeTarget[0]; targetY = homeTarget[1]; targetZ = homeTarget[2]
        distance = homeDistance
        yaw = homeYaw
        pitch = 0.35f
    }

    @Synchronized
    fun rotate(dxPixels: Float, dyPixels: Float, viewHeight: Int) {
        val k = (2f * PI.toFloat()) / max(viewHeight, 1)
        yaw -= dxPixels * k
        pitch = (pitch + dyPixels * k).coerceIn(-1.5f, 1.5f)
    }

    @Synchronized
    fun zoom(scale: Float) {
        distance = (distance / scale).coerceIn(radius * 0.05f, homeDistance * 8f)
    }

    @Synchronized
    fun pan(dxPixels: Float, dyPixels: Float, viewHeight: Int) {
        val worldPerPixel = 2f * distance * tan(Math.toRadians(fovYDeg / 2.0)).toFloat() / max(viewHeight, 1)
        // Camera basis.
        val fx = cos(pitch) * sin(yaw)
        val fz = cos(pitch) * cos(yaw)
        val rx = cos(yaw); val rz = -sin(yaw)
        val ux = -sin(pitch) * sin(yaw); val uy = cos(pitch); val uz = -sin(pitch) * cos(yaw)
        targetX += (-dxPixels * rx + dyPixels * ux) * worldPerPixel
        targetY += (dyPixels * uy) * worldPerPixel
        targetZ += (-dxPixels * rz + dyPixels * uz) * worldPerPixel
        if (fx.isNaN() || fz.isNaN()) reset()
    }

    /** Writes view and projection matrices. Returns camera distance for near/far decisions. */
    @Synchronized
    fun matrices(view: FloatArray, proj: FloatArray, aspect: Float): Float {
        val ex = targetX + distance * cos(pitch) * sin(yaw)
        val ey = targetY + distance * sin(pitch)
        val ez = targetZ + distance * cos(pitch) * cos(yaw)
        Matrix.setLookAtM(view, 0, ex, ey, ez, targetX, targetY, targetZ, 0f, 1f, 0f)
        val near = max(distance * 0.01f, 0.001f)
        val far = distance + radius * 10f + 10f
        Matrix.perspectiveM(proj, 0, fovYDeg, aspect, near, far)
        return distance
    }
}
