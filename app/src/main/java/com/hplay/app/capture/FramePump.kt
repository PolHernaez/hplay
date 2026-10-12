package com.hplay.app.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Pantalla virtual → SurfaceTexture → (OpenGL) → Surface del codificador.
 * Por qué: con la pantalla quieta Android deja de producir fotogramas (REPEAT_PREVIOUS_FRAME solo
 * repite unas pocas veces). En MSE y en las teles un hueco de vídeo congela la reproducción y
 * desincroniza el audio. Aquí se redibuja el último fotograma cada [idleMs] como mínimo: flujo continuo.
 */
class FramePump(encoderSurface: Surface, private val width: Int, private val height: Int, private val idleMs: Long = 100) {
    private val thread = HandlerThread("hplay-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var eglDisplay: EGLDisplay
    private lateinit var eglContext: EGLContext
    private lateinit var eglSurface: EGLSurface
    private lateinit var texture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set
    private var program = 0
    private var texId = 0
    private val matrix = FloatArray(16)
    private var hasFrame = false
    private var lastDrawNs = 0L
    @Volatile private var released = false

    private val repeat = object : Runnable {
        override fun run() {
            if (released) return
            if (hasFrame && System.nanoTime() - lastDrawNs >= idleMs * 1_000_000) draw(updateTex = false)
            handler.postDelayed(this, idleMs)
        }
    }

    init {
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try { setup(encoderSurface) } catch (t: Throwable) { error = t }
            ready.countDown()
        }
        ready.await()
        error?.let { release(); throw it }
        handler.postDelayed(repeat, idleMs)
    }

    private fun setup(out: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) { "eglInitialize" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], out, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

        program = link(VERTEX, FRAGMENT)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        texture = SurfaceTexture(texId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ if (!released) draw(updateTex = true) }, handler)
        }
        inputSurface = Surface(texture)
    }

    private fun draw(updateTex: Boolean) {
        if (updateTex) {
            texture.updateTexImage()
            texture.getTransformMatrix(matrix)
            hasFrame = true
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, QUAD.position(0))
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, QUAD.position(2))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        lastDrawNs = System.nanoTime()
        // Misma base de tiempo (CLOCK_MONOTONIC) que el audio: sincronía A/V.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, lastDrawNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        handler.post {
            runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
            runCatching { if (::texture.isInitialized) texture.release() }
            runCatching {
                if (::eglDisplay.isInitialized) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (::eglSurface.isInitialized) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (::eglContext.isInitialized) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            }
            done.countDown()
        }
        done.await()
        thread.quitSafely()
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val QUAD: java.nio.FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        }
        private const val VERTEX = """
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }"""
        private const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }"""
    }
}
