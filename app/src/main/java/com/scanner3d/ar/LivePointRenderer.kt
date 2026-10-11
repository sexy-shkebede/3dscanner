package com.scanner3d.ar

import android.opengl.GLES30
import com.scanner3d.core.DisplayBuffer
import com.scanner3d.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the growing point map as round, distance-scaled sprites. New points are streamed into a
 * preallocated VBO incrementally, so each frame only uploads what was added since the last one.
 */
class LivePointRenderer(private val display: DisplayBuffer) {
    private var program = 0
    private var vbo = 0
    private var featureVbo = 0
    private var uploaded = 0
    private var uploadedGeneration = -1
    private val source: ByteBuffer = display.buffer.duplicate()

    private var uView = 0
    private var uProj = 0
    private var uWorldSize = 0
    private var uViewportScale = 0
    private var uMinSize = 0
    private var uTint = 0

    private var featureData: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 4 * 4096).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun create() {
        program = GlUtil.createProgram(VERTEX, FRAGMENT)
        uView = GLES30.glGetUniformLocation(program, "u_View")
        uProj = GLES30.glGetUniformLocation(program, "u_Proj")
        uWorldSize = GLES30.glGetUniformLocation(program, "u_WorldSize")
        uViewportScale = GLES30.glGetUniformLocation(program, "u_ViewportScale")
        uMinSize = GLES30.glGetUniformLocation(program, "u_MinSize")
        uTint = GLES30.glGetUniformLocation(program, "u_Tint")
        vbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, display.capacity * DisplayBuffer.STRIDE, null, GLES30.GL_DYNAMIC_DRAW)
        featureVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        uploaded = 0
        uploadedGeneration = -1
    }

    /** Streams newly published points to the GPU. */
    fun sync() {
        val gen = display.generation
        if (gen != uploadedGeneration) {
            uploadedGeneration = gen
            uploaded = 0
        }
        val published = display.published
        if (published <= uploaded) return
        val count = published - uploaded
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        source.limit(published * DisplayBuffer.STRIDE)
        source.position(uploaded * DisplayBuffer.STRIDE)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, uploaded * DisplayBuffer.STRIDE, count * DisplayBuffer.STRIDE, source,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        uploaded = published
    }

    val pointCount: Int get() = uploaded

    /**
     * @param viewportScale viewportHeight * proj[5] / 2, converts world size at distance 1 to pixels.
     * @param tint rgb + mix amount in alpha.
     */
    fun draw(view: FloatArray, proj: FloatArray, worldSize: Float, viewportScale: Float, tint: FloatArray) {
        if (uploaded == 0) return
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uView, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(uProj, 1, false, proj, 0)
        GLES30.glUniform1f(uWorldSize, worldSize)
        GLES30.glUniform1f(uViewportScale, viewportScale)
        GLES30.glUniform1f(uMinSize, 2.5f)
        GLES30.glUniform4fv(uTint, 1, tint, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, DisplayBuffer.STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, DisplayBuffer.STRIDE, 12)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, uploaded)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Draws ARCore feature points (x, y, z, confidence) with a flat colour. */
    fun drawFeatures(points: FloatBuffer, view: FloatArray, proj: FloatArray, viewportScale: Float, rgb: FloatArray) {
        val count = points.remaining() / 4
        if (count == 0) return
        if (featureData.capacity() < count * 4) {
            featureData = ByteBuffer.allocateDirect(count * 4 * 4 * 2).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        featureData.clear()
        featureData.put(points)
        featureData.flip()
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uView, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(uProj, 1, false, proj, 0)
        GLES30.glUniform1f(uWorldSize, 0.012f)
        GLES30.glUniform1f(uViewportScale, viewportScale)
        GLES30.glUniform1f(uMinSize, 4f)
        GLES30.glUniform4f(uTint, rgb[0], rgb[1], rgb[2], 1f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, featureVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 16, featureData, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glVertexAttrib4f(1, 1f, 1f, 1f, 1f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        val VERTEX = """
            #version 300 es
            layout(location = 0) in vec3 a_Position;
            layout(location = 1) in vec4 a_Color;
            uniform mat4 u_View;
            uniform mat4 u_Proj;
            uniform float u_WorldSize;
            uniform float u_ViewportScale;
            uniform float u_MinSize;
            uniform vec4 u_Tint;
            out vec3 v_Color;
            void main() {
                vec4 vp = u_View * vec4(a_Position, 1.0);
                gl_Position = u_Proj * vp;
                float dist = max(-vp.z, 0.05);
                gl_PointSize = clamp(u_WorldSize * u_ViewportScale / dist, u_MinSize, 48.0);
                v_Color = mix(a_Color.rgb, u_Tint.rgb, u_Tint.a);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 300 es
            precision mediump float;
            in vec3 v_Color;
            out vec4 o_Color;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                float r2 = dot(c, c);
                if (r2 > 0.25) discard;
                float shade = 1.0 - 0.6 * r2;
                o_Color = vec4(v_Color * shade, 1.0);
            }
        """.trimIndent()
    }
}
