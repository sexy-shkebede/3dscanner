package com.scanner3d.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

object GlUtil {
    private const val TAG = "GlUtil"

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    fun createProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        return program
    }

    fun genBuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        return ids[0]
    }

    fun deleteBuffer(id: Int) {
        if (id != 0) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    fun intBuffer(data: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
            put(data); position(0)
        }

    fun byteBuffer(data: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).apply {
            put(data); position(0)
        }

    fun checkError(op: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "$op: glError 0x${Integer.toHexString(err)}")
    }
}
