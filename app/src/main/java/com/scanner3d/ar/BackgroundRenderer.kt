package com.scanner3d.ar

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.scanner3d.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws the ARCore camera image as a full-screen background. */
class BackgroundRenderer {
    var textureId = -1
        private set

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0
    private var uDim = 0

    private val quadNdc: FloatBuffer = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var texCoordsValid = false

    fun create() {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        textureId = tex[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(target, textureId)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        program = GlUtil.createProgram(VERTEX, FRAGMENT)
        aPosition = GLES30.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES30.glGetAttribLocation(program, "a_TexCoord")
        uTexture = GLES30.glGetUniformLocation(program, "u_Texture")
        uDim = GLES30.glGetUniformLocation(program, "u_Dim")
        texCoordsValid = false
    }

    /** Must be called every frame before [draw]. */
    fun update(frame: Frame) {
        if (frame.hasDisplayGeometryChanged() || !texCoordsValid) {
            quadNdc.position(0)
            texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords,
            )
            quadNdc.position(0)
            texCoords.position(0)
            texCoordsValid = true
        }
    }

    fun draw(frame: Frame, dim: Float) {
        if (frame.timestamp == 0L || !texCoordsValid) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glUniform1f(uDim, dim)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        quadNdc.position(0)
        texCoords.position(0)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, quadNdc)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform float u_Dim;
            void main() {
                vec4 c = texture2D(u_Texture, v_TexCoord);
                gl_FragColor = vec4(c.rgb * u_Dim, 1.0);
            }
        """
    }
}
