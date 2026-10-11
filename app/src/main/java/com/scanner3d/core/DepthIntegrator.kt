package com.scanner3d.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * CPU copy of everything needed to integrate one AR frame. Buffers are reused between frames.
 * Camera space follows the OpenGL convention: +X right, +Y up, -Z forward.
 */
class FrameData {
    enum class Kind { DEPTH, FEATURES }

    var kind = Kind.DEPTH
    var timestampNs = 0L

    /** Camera-to-world transform, column-major 4x4. */
    val pose = FloatArray(16)

    // Depth image (millimetres), row stride in elements.
    var depthWidth = 0
    var depthHeight = 0
    var depthStride = 0
    var depth = ShortArray(0)
    var hasConfidence = false
    var confidenceStride = 0
    var confidence = ByteArray(0)

    /** Depth intrinsics in depth-image pixels. */
    var dfx = 0f; var dfy = 0f; var dcx = 0f; var dcy = 0f

    // Colour image (YUV_420_888 planes copied verbatim).
    var imageWidth = 0
    var imageHeight = 0
    var yRowStride = 0
    var uvRowStride = 0
    var uvPixelStride = 0
    var y = ByteArray(0)
    var u = ByteArray(0)
    var v = ByteArray(0)
    var hasImage = false

    /** Colour image intrinsics in image pixels. */
    var ifx = 0f; var ify = 0f; var icx = 0f; var icy = 0f

    // Feature points (world space): x, y, z, confidence.
    var featureCount = 0
    var features = FloatArray(0)

    fun ensureDepth(size: Int) { if (depth.size < size) depth = ShortArray(size) }
    fun ensureConfidence(size: Int) { if (confidence.size < size) confidence = ByteArray(size) }
    fun ensureFeatures(count: Int) { if (features.size < count * 4) features = FloatArray(count * 4) }
    fun ensureY(size: Int) { if (y.size < size) y = ByteArray(size) }
    fun ensureU(size: Int) { if (u.size < size) u = ByteArray(size) }
    fun ensureV(size: Int) { if (v.size < size) v = ByteArray(size) }
}

data class IntegrationSettings(
    val minDepth: Float = 0.15f,
    val maxDepth: Float = 2.5f,
    /** Minimum raw-depth confidence in 0..1. */
    val minConfidence: Float = 0.5f,
    /** Upper bound on depth samples taken per frame. */
    val maxSamplesPerFrame: Int = 40_000,
)

/** Fuses depth or feature-point frames into a [PointMap]. */
class DepthIntegrator(private val map: PointMap, var settings: IntegrationSettings) {

    private val rgb = IntArray(3)

    /** Returns the number of samples added. */
    fun integrate(f: FrameData): Int {
        map.beginFrame()
        return when (f.kind) {
            FrameData.Kind.DEPTH -> integrateDepth(f)
            FrameData.Kind.FEATURES -> integrateFeatures(f)
        }
    }

    private fun integrateDepth(f: FrameData): Int {
        val w = f.depthWidth
        val h = f.depthHeight
        if (w <= 2 || h <= 2) return 0
        var step = 1
        while ((w / step) * (h / step) > settings.maxSamplesPerFrame) step++
        val m = f.pose
        val camX = m[12]; val camY = m[13]; val camZ = m[14]
        val minD = settings.minDepth
        val maxD = settings.maxDepth
        val minConf = (settings.minConfidence * 255f).toInt()
        var added = 0

        var py = step
        while (py < h - step) {
            var px = step
            while (px < w - step) {
                val d = depthAt(f, px, py)
                if (d < minD || d > maxD) { px += step; continue }
                if (f.hasConfidence) {
                    val c = f.confidence[py * f.confidenceStride + px].toInt() and 0xFF
                    if (c < minConf) { px += step; continue }
                }
                // Neighbours for normals and edge (flying pixel) rejection.
                val jump = (0.03f + 0.015f * step) * d
                val dl = depthAt(f, px - step, py)
                val dr = depthAt(f, px + step, py)
                val du = depthAt(f, px, py - step)
                val dd = depthAt(f, px, py + step)
                if ((dl > 0f && abs(dl - d) > jump) || (dr > 0f && abs(dr - d) > jump) ||
                    (du > 0f && abs(du - d) > jump) || (dd > 0f && abs(dd - d) > jump)
                ) { px += step; continue }

                // Camera-space point.
                val cx = (px - f.dcx) * d / f.dfx
                val cy = (f.dcy - py) * d / f.dfy
                val cz = -d

                // Tangents along image x and y (central differences when possible).
                var nx = 0f; var ny = 0f; var nz = 0f
                var cosA = 0.6f
                val txOk = dl > 0f || dr > 0f
                val tyOk = du > 0f || dd > 0f
                if (txOk && tyOk) {
                    val ax: Float; val ay: Float; val az: Float
                    if (dl > 0f && dr > 0f) {
                        ax = ((px + step - f.dcx) * dr - (px - step - f.dcx) * dl) / f.dfx
                        ay = ((f.dcy - py) * dr - (f.dcy - py) * dl) / f.dfy
                        az = -dr + dl
                    } else if (dr > 0f) {
                        ax = (px + step - f.dcx) * dr / f.dfx - cx
                        ay = (f.dcy - py) * dr / f.dfy - cy
                        az = -dr - cz
                    } else {
                        ax = cx - (px - step - f.dcx) * dl / f.dfx
                        ay = cy - (f.dcy - py) * dl / f.dfy
                        az = cz + dl
                    }
                    val bx: Float; val by: Float; val bz: Float
                    if (du > 0f && dd > 0f) {
                        bx = ((px - f.dcx) * dd - (px - f.dcx) * du) / f.dfx
                        by = ((f.dcy - py - step) * dd - (f.dcy - py + step) * du) / f.dfy
                        bz = -dd + du
                    } else if (dd > 0f) {
                        bx = (px - f.dcx) * dd / f.dfx - cx
                        by = (f.dcy - py - step) * dd / f.dfy - cy
                        bz = -dd - cz
                    } else {
                        bx = cx - (px - f.dcx) * du / f.dfx
                        by = cy - (f.dcy - py + step) * du / f.dfy
                        bz = cz + du
                    }
                    nx = ay * bz - az * by
                    ny = az * bx - ax * bz
                    nz = ax * by - ay * bx
                    val nl = sqrt(nx * nx + ny * ny + nz * nz)
                    if (nl > 1e-9f) {
                        nx /= nl; ny /= nl; nz /= nl
                        // Orient towards the camera (origin).
                        val pl = sqrt(cx * cx + cy * cy + cz * cz)
                        var dot = -(nx * cx + ny * cy + nz * cz) / pl
                        if (dot < 0f) { nx = -nx; ny = -ny; nz = -nz; dot = -dot }
                        if (dot < 0.17f) { px += step; continue } // grazing angle: unreliable
                        cosA = dot
                    } else {
                        nx = 0f; ny = 0f; nz = 0f
                    }
                }

                // To world space.
                val wx = m[0] * cx + m[4] * cy + m[8] * cz + m[12]
                val wy = m[1] * cx + m[5] * cy + m[9] * cz + m[13]
                val wz = m[2] * cx + m[6] * cy + m[10] * cz + m[14]
                val wnx = m[0] * nx + m[4] * ny + m[8] * nz
                val wny = m[1] * nx + m[5] * ny + m[9] * nz
                val wnz = m[2] * nx + m[6] * ny + m[10] * nz
                var vx = camX - wx; var vy = camY - wy; var vz = camZ - wz
                val vl = sqrt(vx * vx + vy * vy + vz * vz)
                vx /= vl; vy /= vl; vz /= vl

                sampleColor(f, cx, cy, d)
                val weight = cosA / (0.25f + d)
                map.add(
                    wx, wy, wz, wnx, wny, wnz, vx, vy, vz,
                    rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat(), weight,
                )
                added++
                px += step
            }
            py += step
        }
        return added
    }

    private fun integrateFeatures(f: FrameData): Int {
        val m = f.pose
        val camX = m[12]; val camY = m[13]; val camZ = m[14]
        var added = 0
        for (i in 0 until f.featureCount) {
            val x = f.features[4 * i]; val y = f.features[4 * i + 1]; val z = f.features[4 * i + 2]
            val conf = f.features[4 * i + 3]
            if (conf < 0.15f) continue
            // World -> camera: R^T (p - t).
            val dx = x - camX; val dy = y - camY; val dz = z - camZ
            val cx = m[0] * dx + m[1] * dy + m[2] * dz
            val cy = m[4] * dx + m[5] * dy + m[6] * dz
            val cz = m[8] * dx + m[9] * dy + m[10] * dz
            val d = -cz
            if (d < settings.minDepth || d > settings.maxDepth) continue
            val vl = sqrt(dx * dx + dy * dy + dz * dz)
            sampleColor(f, cx, cy, d)
            map.add(
                x, y, z, 0f, 0f, 0f, -dx / vl, -dy / vl, -dz / vl,
                rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat(), max(conf, 0.2f),
            )
            added++
        }
        return added
    }

    private fun depthAt(f: FrameData, x: Int, y: Int): Float {
        if (x < 0 || y < 0 || x >= f.depthWidth || y >= f.depthHeight) return 0f
        val mm = f.depth[y * f.depthStride + x].toInt() and 0xFFFF
        return mm * 0.001f
    }

    /** Projects a camera-space point into the colour image and samples RGB into [rgb]. */
    private fun sampleColor(f: FrameData, cx: Float, cy: Float, d: Float) {
        if (!f.hasImage) {
            rgb[0] = 200; rgb[1] = 200; rgb[2] = 200
            return
        }
        val u = (f.icx + f.ifx * cx / d).toInt().coerceIn(0, f.imageWidth - 1)
        val v = (f.icy - f.ify * cy / d).toInt().coerceIn(0, f.imageHeight - 1)
        val yy = (f.y[v * f.yRowStride + u].toInt() and 0xFF).toFloat()
        val uvIndex = (v shr 1) * f.uvRowStride + (u shr 1) * f.uvPixelStride
        val uu = if (uvIndex < f.u.size) (f.u[uvIndex].toInt() and 0xFF) - 128f else 0f
        val vv = if (uvIndex < f.v.size) (f.v[uvIndex].toInt() and 0xFF) - 128f else 0f
        rgb[0] = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
        rgb[1] = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
        rgb[2] = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
    }
}
