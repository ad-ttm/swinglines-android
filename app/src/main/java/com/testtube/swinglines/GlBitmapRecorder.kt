package com.testtube.swinglines

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders bitmaps onto a MediaRecorder input surface via OpenGL ES 2 - the
 * platform-supported way to feed composed frames to an encoder (a plain
 * Canvas lock on an encoder surface is explicitly unsupported). One textured
 * quad, one bitmap upload per frame, presentation time stamped per swap so
 * the recording plays at true speed.
 */
class GlBitmapRecorder(private val width: Int, private val height: Int) {

    private val thread = HandlerThread("SeePathGl").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var vertexBuf: FloatBuffer? = null

    /** interleaved x,y,u,v - full-screen strip, v flipped for bitmap origin */
    private val verts = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )

    fun start(surface: Surface, onReady: (Boolean) -> Unit) {
        handler.post {
            val ok = try {
                initEgl(surface)
                true
            } catch (e: Exception) {
                android.util.Log.e("SeePath", "GL recorder init failed", e)
                releaseEgl()
                false
            }
            onReady(ok)
        }
    }

    fun frame(bmp: Bitmap, ptsNs: Long, onDone: () -> Unit) {
        handler.post {
            if (program == 0) { onDone(); return@post }
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)
                val vb = vertexBuf!!
                val aPos = GLES20.glGetAttribLocation(program, "aPos")
                val aTex = GLES20.glGetAttribLocation(program, "aTex")
                vb.position(0)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb)
                GLES20.glEnableVertexAttribArray(aPos)
                vb.position(2)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vb)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (_: Exception) {
            }
            onDone()
        }
    }

    fun stop(onStopped: () -> Unit) {
        handler.post {
            releaseEgl()
            thread.quitSafely()
            onStopped()
        }
    }

    private fun initEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }
        val eglRecordable = 0x3142 // EGL_RECORDABLE_ANDROID
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            eglRecordable, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        if (num[0] <= 0) throw IllegalStateException("no EGL config")
        val config = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
        GLES20.glViewport(0, 0, width, height)

        val vs = compile(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;" +
                "void main() { gl_Position = aPos; vTex = aTex; }"
        )
        val fs = compile(
            GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;" +
                "void main() { gl_FragColor = texture2D(uTex, vTex); }"
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            program = 0
            throw IllegalStateException("shader link failed: $log")
        }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val bb = ByteBuffer.allocateDirect(verts.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(verts)
        fb.position(0)
        vertexBuf = fb
    }

    /**
     * Compiles a shader and throws if it failed. Without this check a driver that
     * rejects the source still links a dud program and the recording runs to
     * completion producing a silent black video, which is worse than not starting.
     */
    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw IllegalStateException("glCreateShader failed")
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private fun releaseEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Exception) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}
