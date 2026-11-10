package com.aether.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/**
 * Splash: the plain background image fades in first, then the gold "HELLO" image is wiped in
 * from left to right with a small blur on the leading edge. Held briefly, then faded out once
 * the channels are ready (never cut short before the animation has finished).
 */
class HelloView(ctx: Context) : View(ctx) {
    private val bg: Bitmap? = load(R.drawable.splash_a)
    private val hello: Bitmap? = load(R.drawable.splash_b)
    private val helloBlur: Bitmap? = load(R.drawable.splash_b_blur)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val maskShader = LinearGradient(0f, 0f, 1f, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val mat = Matrix()
    private val maskMat = Matrix()

    private val t0 = SystemClock.uptimeMillis()
    private var running = true
    private var dismissing = false

    private val fadeInMs = 450f
    private val wipeStart = 1000f
    private val wipeMs = 1900f
    private val minShowMs = 3500L

    init { setBackgroundColor(Color.BLACK) }

    private fun load(id: Int): Bitmap? = try { BitmapFactory.decodeResource(resources, id) } catch (_: Throwable) { null }

    /** centre-crop [b] to fill the view */
    private fun fit(b: Bitmap, w: Float, h: Float) {
        val s = maxOf(w / b.width, h / b.height)
        mat.setScale(s, s)
        mat.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
    }

    /** draw [b] revealed left-to-right up to a soft edge at [edge] (soft band is [soft] wide) */
    private fun wiped(canvas: Canvas, b: Bitmap, w: Float, h: Float, edge: Float, soft: Float) {
        val sc = canvas.saveLayer(0f, 0f, w, h, null)
        fit(b, w, h)
        canvas.drawBitmap(b, mat, paint)
        maskMat.setScale(soft, 1f)
        maskMat.postTranslate(edge - soft, 0f)
        maskShader.setLocalMatrix(maskMat)
        mask.shader = maskShader
        canvas.drawRect(0f, 0f, w, h, mask)
        canvas.restoreToCount(sc)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val el = (SystemClock.uptimeMillis() - t0).toFloat()

        // 1) first image comes first (soft fade-in from black)
        bg?.let {
            paint.alpha = (255 * (el / fadeInMs).coerceIn(0f, 1f)).toInt()
            fit(it, w, h)
            canvas.drawBitmap(it, mat, paint)
            paint.alpha = 255
        }

        // 2) second image wipes in left -> right, leading edge blurred then sharpening
        if (el >= wipeStart && hello != null) {
            val p = ((el - wipeStart) / wipeMs).coerceIn(0f, 1f)
            val e = p * p * (3f - 2f * p)
            val soft = w * 0.2f
            val lag = w * 0.12f
            val travel = w + soft + lag
            val pos = travel * e
            if (helloBlur != null) wiped(canvas, helloBlur, w, h, pos, soft)
            wiped(canvas, hello, w, h, pos - lag, soft)
        }

        if (running) postInvalidateOnAnimation()
    }

    /** Fade out and remove, but only after the animation has had its minimum time on screen. */
    fun dismiss(onDone: () -> Unit = {}) {
        if (dismissing) return
        dismissing = true
        val remain = minShowMs - (SystemClock.uptimeMillis() - t0)
        val go = Runnable {
            animate().alpha(0f).setDuration(320).withEndAction {
                running = false
                (parent as? ViewGroup)?.removeView(this)
                onDone()
            }.start()
        }
        if (remain > 0) postDelayed(go, remain) else go.run()
    }
}
