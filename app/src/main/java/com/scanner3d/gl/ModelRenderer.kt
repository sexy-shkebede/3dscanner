package com.scanner3d.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.scanner3d.core.Mesh
import com.scanner3d.core.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class RenderMode { POINTS, COLOR, CLAY, WIRE }

/** Renders a point cloud or a mesh with an orbit camera. */
class ModelRenderer(private val camera: OrbitCamera) : GLSurfaceView.Renderer {

    @Volatile var mode: RenderMode = RenderMode.POINTS
    @Volatile var clearColor = floatArrayOf(0.1f, 0.1f, 0.12f, 1f)
    @Volatile var pointScale = 1.3f

    private val lock = Any()
    private var cloud: PointCloud? = null
    private var mesh: Mesh? = null
    private var cloudDirty = false
    private var meshDirty = false

    private var meshProgram = 0
    private var pointProgram = 0
    private var lineProgram = 0

    private var cloudPos = 0
    private var cloudCol = 0
    private var cloudCount = 0
    private var cloudVoxel = 0.01f

    private var meshPos = 0
    private var meshNrm = 0
    private var meshCol = 0
    private var meshIdx = 0
    private var meshIndexCount = 0
    private var lineIdx = 0
    private var lineIndexCount = -1
    private var meshForLines: Mesh? = null

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private var width = 1
    private var height = 1

    fun setCloud(c: PointCloud?) = synchronized(lock) { cloud = c; cloudDirty = true }
    fun setMesh(m: Mesh?) = synchronized(lock) { mesh = m; meshDirty = true }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshProgram = GlUtil.createProgram(MESH_VS, MESH_FS)
        pointProgram = GlUtil.createProgram(POINT_VS, POINT_FS)
        lineProgram = GlUtil.createProgram(LINE_VS, LINE_FS)
        cloudPos = 0; cloudCol = 0; meshPos = 0; meshNrm = 0; meshCol = 0; meshIdx = 0; lineIdx = 0
        lineIndexCount = -1
        synchronized(lock) { cloudDirty = true; meshDirty = true }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadIfNeeded()
        val c = clearColor
        GLES30.glClearColor(c[0], c[1], c[2], c[3])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        camera.matrices(view, proj, width.toFloat() / height.coerceAtLeast(1))

        val m = mode
        if (m == RenderMode.POINTS || meshIndexCount == 0) {
            drawPoints()
        } else {
            drawMesh(m)
        }
    }

    private fun uploadIfNeeded() {
        var c: PointCloud? = null
        var m: Mesh? = null
        var uploadCloud: Boolean
        var uploadMesh: Boolean
        synchronized(lock) {
            uploadCloud = cloudDirty; uploadMesh = meshDirty
            if (uploadCloud) c = cloud
            if (uploadMesh) m = mesh
            cloudDirty = false; meshDirty = false
        }
        if (uploadCloud) {
            GlUtil.deleteBuffer(cloudPos); GlUtil.deleteBuffer(cloudCol)
            cloudPos = 0; cloudCol = 0; cloudCount = 0
            c?.let { cl ->
                cloudPos = GlUtil.genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudPos)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cl.size * 12, GlUtil.floatBuffer(cl.positions), GLES30.GL_STATIC_DRAW)
                cloudCol = GlUtil.genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudCol)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cl.size * 4, rgba(cl.colors, cl.size), GLES30.GL_STATIC_DRAW)
                cloudCount = cl.size
                cloudVoxel = cl.voxelSize
            }
        }
        if (uploadMesh) {
            GlUtil.deleteBuffer(meshPos); GlUtil.deleteBuffer(meshNrm); GlUtil.deleteBuffer(meshCol)
            GlUtil.deleteBuffer(meshIdx); GlUtil.deleteBuffer(lineIdx)
            meshPos = 0; meshNrm = 0; meshCol = 0; meshIdx = 0; lineIdx = 0
            meshIndexCount = 0; lineIndexCount = -1
            meshForLines = m
            m?.takeIf { it.triangleCount > 0 }?.let { me ->
                meshPos = GlUtil.genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshPos)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, me.vertexCount * 12, GlUtil.floatBuffer(me.positions), GLES30.GL_STATIC_DRAW)
                meshNrm = GlUtil.genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshNrm)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, me.vertexCount * 12, GlUtil.floatBuffer(me.normals), GLES30.GL_STATIC_DRAW)
                meshCol = GlUtil.genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshCol)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, me.vertexCount * 4, rgba(me.colors, me.vertexCount), GLES30.GL_STATIC_DRAW)
                meshIdx = GlUtil.genBuffer()
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIdx)
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, me.indices.size * 4, GlUtil.intBuffer(me.indices), GLES30.GL_STATIC_DRAW)
                meshIndexCount = me.indices.size
            }
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun rgba(rgb: ByteArray, n: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            b.put(rgb[3 * i]); b.put(rgb[3 * i + 1]); b.put(rgb[3 * i + 2]); b.put(255.toByte())
        }
        b.position(0)
        return b
    }

    private fun drawPoints() {
        if (cloudCount == 0) return
        GLES30.glUseProgram(pointProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pointProgram, "u_View"), 1, false, view, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pointProgram, "u_Proj"), 1, false, proj, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProgram, "u_WorldSize"), cloudVoxel * pointScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProgram, "u_ViewportScale"), height * proj[5] / 2f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudPos)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudCol)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, 4, 0)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, cloudCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawMesh(m: RenderMode) {
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(meshProgram, "u_View"), 1, false, view, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(meshProgram, "u_Proj"), 1, false, proj, 0)
        val useColor = if (m == RenderMode.COLOR) 1f else 0f
        val lighting = if (m == RenderMode.COLOR) 0.3f else 1f
        GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "u_UseColor"), useColor)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "u_Lighting"), lighting)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshPos)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshNrm)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshCol)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, 4, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIdx)
        if (m == RenderMode.WIRE) {
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(1f, 1f)
        }
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, meshIndexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)

        if (m == RenderMode.WIRE) {
            ensureLines()
            if (lineIndexCount > 0) {
                GLES30.glUseProgram(lineProgram)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(lineProgram, "u_View"), 1, false, view, 0)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(lineProgram, "u_Proj"), 1, false, proj, 0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, lineIdx)
                GLES30.glDrawElements(GLES30.GL_LINES, lineIndexCount, GLES30.GL_UNSIGNED_INT, 0)
            }
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun ensureLines() {
        if (lineIndexCount >= 0) return
        val me = meshForLines ?: run { lineIndexCount = 0; return }
        val idx = me.indices
        val tc = idx.size / 3
        val out = IntArray(tc * 6)
        var n = 0
        for (t in 0 until tc) {
            val a = idx[3 * t]; val b = idx[3 * t + 1]; val c = idx[3 * t + 2]
            if (a < b) { out[n++] = a; out[n++] = b }
            if (b < c) { out[n++] = b; out[n++] = c }
            if (c < a) { out[n++] = c; out[n++] = a }
        }
        lineIdx = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, lineIdx)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, n * 4, GlUtil.intBuffer(out.copyOf(n)), GLES30.GL_STATIC_DRAW)
        lineIndexCount = n
    }

    private companion object {
        val MESH_VS = """
            #version 300 es
            layout(location = 0) in vec3 a_Position;
            layout(location = 1) in vec3 a_Normal;
            layout(location = 2) in vec4 a_Color;
            uniform mat4 u_View;
            uniform mat4 u_Proj;
            uniform float u_UseColor;
            out vec3 v_Normal;
            out vec3 v_Color;
            out vec3 v_ViewPos;
            void main() {
                vec4 vp = u_View * vec4(a_Position, 1.0);
                v_ViewPos = vp.xyz;
                v_Normal = mat3(u_View) * a_Normal;
                v_Color = mix(vec3(0.80, 0.78, 0.76), a_Color.rgb, u_UseColor);
                gl_Position = u_Proj * vp;
            }
        """.trimIndent()

        val MESH_FS = """
            #version 300 es
            precision mediump float;
            in vec3 v_Normal;
            in vec3 v_Color;
            in vec3 v_ViewPos;
            uniform float u_Lighting;
            out vec4 o_Color;
            void main() {
                vec3 n = normalize(v_Normal);
                vec3 v = normalize(-v_ViewPos);
                if (dot(n, v) < 0.0) n = -n;
                vec3 l = normalize(vec3(0.35, 0.65, 1.0));
                float diff = max(dot(n, l), 0.0);
                float fill = max(dot(n, normalize(vec3(-0.6, -0.2, 0.5))), 0.0);
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
                float light = 0.28 + 0.62 * diff + 0.18 * fill;
                vec3 c = v_Color * mix(1.0, light, u_Lighting) + vec3(rim * 0.10 * u_Lighting);
                o_Color = vec4(c, 1.0);
            }
        """.trimIndent()

        val POINT_VS = """
            #version 300 es
            layout(location = 0) in vec3 a_Position;
            layout(location = 1) in vec4 a_Color;
            uniform mat4 u_View;
            uniform mat4 u_Proj;
            uniform float u_WorldSize;
            uniform float u_ViewportScale;
            out vec3 v_Color;
            void main() {
                vec4 vp = u_View * vec4(a_Position, 1.0);
                gl_Position = u_Proj * vp;
                gl_PointSize = clamp(u_WorldSize * u_ViewportScale / max(-vp.z, 0.001), 1.5, 64.0);
                v_Color = a_Color.rgb;
            }
        """.trimIndent()

        val POINT_FS = """
            #version 300 es
            precision mediump float;
            in vec3 v_Color;
            out vec4 o_Color;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                float r2 = dot(c, c);
                if (r2 > 0.25) discard;
                o_Color = vec4(v_Color * (1.0 - 0.5 * r2), 1.0);
            }
        """.trimIndent()

        val LINE_VS = """
            #version 300 es
            layout(location = 0) in vec3 a_Position;
            uniform mat4 u_View;
            uniform mat4 u_Proj;
            void main() {
                gl_Position = u_Proj * u_View * vec4(a_Position, 1.0);
            }
        """.trimIndent()

        val LINE_FS = """
            #version 300 es
            precision mediump float;
            out vec4 o_Color;
            void main() {
                o_Color = vec4(0.12, 0.12, 0.16, 1.0);
            }
        """.trimIndent()
    }
}
