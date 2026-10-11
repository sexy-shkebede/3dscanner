package com.scanner3d.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software point-splat renderer used to create preview thumbnails without a GL context.
 * Returns ARGB pixels (transparent background).
 */
object ThumbnailRenderer {

    fun render(
        positions: FloatArray,
        colors: ByteArray,
        normals: FloatArray?,
        size: Int = 320,
    ): IntArray {
        val n = positions.size / 3
        val pixels = IntArray(size * size)
        if (n == 0) return pixels

        // Choose a horizontal viewing direction facing the bulk of the surface normals, tilted down.
        var ax = 0f; var az = 0f
        if (normals != null) {
            val stride = max(1, n / 20000)
            var i = 0
            while (i < n) { ax += normals[3 * i]; az += normals[3 * i + 2]; i += stride }
        }
        var len = sqrt(ax * ax + az * az)
        if (len < 1e-4f) { ax = 0f; az = 1f; len = 1f }
        ax /= len; az /= len
        val tilt = 0.45f
        // Camera looks along -forward; forward points from the object to the camera.
        val fx = ax * cos(tilt); val fy = sin(tilt); val fz = az * cos(tilt)
        // right = up(0,1,0) x forward
        var rx = fz; var rz = -fx
        val rl = sqrt(rx * rx + rz * rz)
        rx /= rl; rz /= rl
        // up = forward x right
        val ux = fy * rz - 0f
        val uy = fz * rx - fx * rz
        val uz = 0f - fy * rx

        // Robust extent: ignore 1% extremes.
        val us = FloatArray(n); val vs = FloatArray(n); val ds = FloatArray(n)
        for (i in 0 until n) {
            val x = positions[3 * i]; val y = positions[3 * i + 1]; val z = positions[3 * i + 2]
            us[i] = x * rx + z * rz
            vs[i] = x * ux + y * uy + z * uz
            ds[i] = x * fx + y * fy + z * fz
        }
        val su = us.copyOf().also { it.sort() }
        val sv = vs.copyOf().also { it.sort() }
        val lo = (n * 0.01f).toInt()
        val hi = n - 1 - lo
        val u0 = su[lo]; val u1 = su[hi]; val v0 = sv[lo]; val v1 = sv[hi]
        val extent = max(u1 - u0, v1 - v0).coerceAtLeast(1e-4f) * 1.1f
        val cu = (u0 + u1) / 2f; val cv = (v0 + v1) / 2f
        val scale = size / extent

        val zbuf = FloatArray(size * size) { -Float.MAX_VALUE }
        val splat = max(1, size / 160)
        for (i in 0 until n) {
            val px = ((us[i] - cu) * scale + size / 2f).toInt()
            val py = (size / 2f - (vs[i] - cv) * scale).toInt()
            val r = colors[3 * i].toInt() and 0xFF
            val g = colors[3 * i + 1].toInt() and 0xFF
            val b = colors[3 * i + 2].toInt() and 0xFF
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            for (dy in 0..splat) for (dx in 0..splat) {
                val x = px + dx; val y = py + dy
                if (x < 0 || y < 0 || x >= size || y >= size) continue
                val k = y * size + x
                if (ds[i] > zbuf[k]) { zbuf[k] = ds[i]; pixels[k] = argb }
            }
        }
        return pixels
    }
}
