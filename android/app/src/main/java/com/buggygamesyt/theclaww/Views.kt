package com.buggygamesyt.theclaww

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** Simple stroked icon drawn to fill its box: "up" arrow, "plus", "back" chevron. */
class Glyph(ctx: Context, private val kind: String, color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.strokeWidth = w * 0.15f
        val i = p.strokeWidth / 2
        when (kind) {
            "up" -> {
                c.drawLine(w / 2, i, w / 2, h - i, p)
                c.drawPath(Path().apply { moveTo(i, h * 0.48f); lineTo(w / 2, i); lineTo(w - i, h * 0.48f) }, p)
            }
            "plus" -> { c.drawLine(w / 2, i, w / 2, h - i, p); c.drawLine(i, h / 2, w - i, h / 2, p) }
            "back" -> c.drawPath(Path().apply { moveTo(w * 0.68f, i); lineTo(w * 0.28f, h / 2); lineTo(w * 0.68f, h - i) }, p)
        }
    }
}

/** Horizontal pager: pages sit side by side and follow the finger; release snaps to the nearest page. */
class Pager(ctx: Context) : FrameLayout(ctx) {
    private val pages = mutableListOf<View>()
    var index = 1; private set
    var onChange: (Int) -> Unit = {}
    private var pos = 0f              // scroll position in px (index * width when settled)
    private var startPos = 0f
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    fun addPage(v: View) { pages.add(v); addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!dragging && anim?.isRunning != true) pos = index * width.toFloat()
        place()
    }

    private fun place() {
        val w = width.toFloat()
        pages.forEachIndexed { i, v ->
            v.translationX = i * w - pos
            v.visibility = if (abs(i * w - pos) < w) View.VISIBLE else View.INVISIBLE
        }
    }

    fun go(i: Int, animate: Boolean = true) {
        val target = i.coerceIn(0, pages.size - 1)
        val changed = target != index
        index = target
        anim?.cancel()
        val end = target * width.toFloat()
        if (!animate || width == 0) { pos = end; place() } else {
            anim = ValueAnimator.ofFloat(pos, end).apply {
                duration = 260; interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { pos = it.animatedValue as Float; place() }
                start()
            }
        }
        if (changed) onChange(target)
    }

    private fun startDrag(e: MotionEvent) {
        dragging = true; anim?.cancel(); startPos = pos; downX = e.x
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun wantsDrag(e: MotionEvent): Boolean {
        val dx = e.x - downX; val dy = e.y - downY
        return abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.4f
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                if (anim?.isRunning == true) { startDrag(e); return true }
            }
            MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); if (!dragging && wantsDrag(e)) { startDrag(e); return true } }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && wantsDrag(e)) startDrag(e)
                if (dragging) {
                    val max = (pages.size - 1) * width.toFloat()
                    var p = startPos - (e.x - downX)
                    if (p < 0) p /= 3f; if (p > max) p = max + (p - max) / 3f
                    pos = p; place()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    val w = width.toFloat()
                    val moved = pos - index * w
                    val target = when {
                        v < -900 || moved > w * 0.25f -> index + 1
                        v > 900 || moved < -w * 0.25f -> index - 1
                        else -> index
                    }
                    dragging = false
                    go(target)
                }
                vt?.recycle(); vt = null
            }
        }
        return true
    }
}

/** Procedural animated wallpaper. Runs ~30 fps and pauses whenever the window is hidden. */
class AnimatedWall(ctx: Context, private val kind: String, private val bg: Int, private val colors: IntArray) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()
    private var running = false
    private val rnd = java.util.Random(7)
    private val stars = Array(140) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.3f + rnd.nextFloat() * 1.4f, rnd.nextFloat() * 6.28f) }
    private val tick = object : Runnable { override fun run() { if (running) { invalidate(); postDelayed(this, 33) } } }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        running = v == View.VISIBLE
        removeCallbacks(tick); if (running) post(tick)
    }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = (System.nanoTime() - start) / 1e9f
        c.drawColor(bg)
        when (kind) {
            "stars" -> {
                p.shader = null; p.style = android.graphics.Paint.Style.FILL
                stars.forEach { st ->
                    val y = (st[1] + s * 0.01f * st[2]) % 1f
                    val tw = 0.45f + 0.55f * ((kotlin.math.sin(s * 1.7f + st[3]) + 1f) / 2f)
                    p.color = colors[0]; p.alpha = (tw * 230).toInt()
                    c.drawCircle(st[0] * w, y * h, st[2] * 1.6f * resources.displayMetrics.density, p)
                }
                // slow nebula glow
                glow(c, w * (0.5f + 0.3f * kotlin.math.sin(s * 0.07f)), h * 0.35f, w * 0.9f, colors[1], 70)
            }
            "waves" -> {
                for (k in 0 until 4) {
                    val path = Path()
                    val base = h * (0.55f + k * 0.11f)
                    val amp = h * (0.035f + k * 0.008f)
                    path.moveTo(0f, h)
                    var x = 0f
                    while (x <= w + 8) {
                        path.lineTo(x, base + amp * kotlin.math.sin(x / w * 6.28f * (1.2f + k * 0.3f) + s * (0.6f + k * 0.25f) + k))
                        x += 8f
                    }
                    path.lineTo(w, h); path.close()
                    p.shader = null; p.style = android.graphics.Paint.Style.FILL
                    p.color = colors[k % colors.size]; p.alpha = 70 + k * 35
                    c.drawPath(path, p)
                }
            }
            else -> { // "blobs": soft colored lights drifting on slow Lissajous paths
                colors.forEachIndexed { i, col ->
                    val a = s * (0.11f + i * 0.037f) + i * 2.1f
                    val x = w * (0.5f + 0.38f * kotlin.math.sin(a * 1.3f + i))
                    val y = h * (0.5f + 0.38f * kotlin.math.cos(a + i * 0.7f))
                    glow(c, x, y, maxOf(w, h) * (0.45f + 0.08f * kotlin.math.sin(a * 2f)), col, 200)
                }
            }
        }
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, col: Int, alpha: Int) {
        val center = (col and 0x00FFFFFF) or (alpha shl 24)
        p.style = android.graphics.Paint.Style.FILL
        p.shader = android.graphics.RadialGradient(x, y, r, intArrayOf(center, col and 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
        p.alpha = 255
        c.drawCircle(x, y, r, p)
        p.shader = null
    }
}

/** Looping, muted, center-cropped video wallpaper. */
class VideoWall(ctx: Context, private val uri: android.net.Uri) : android.view.TextureView(ctx), android.view.TextureView.SurfaceTextureListener {
    private var mp: android.media.MediaPlayer? = null
    init { surfaceTextureListener = this }
    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
        try {
            mp = android.media.MediaPlayer().apply {
                setDataSource(context, uri); setSurface(android.view.Surface(st))
                isLooping = true; setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, vw, vh -> crop(vw, vh) }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (_: Exception) {}
    }
    private fun crop(vw: Int, vh: Int) {
        if (vw == 0 || vh == 0 || width == 0) return
        val sx = width.toFloat() / vw; val sy = height.toFloat() / vh; val sc = maxOf(sx, sy)
        val m = android.graphics.Matrix()
        m.setScale(vw * sc / width, vh * sc / height, width / 2f, height / 2f)
        setTransform(m)
    }
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        try { if (v == View.VISIBLE) mp?.start() else mp?.pause() } catch (_: Exception) {}
    }
    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { mp?.let { crop(it.videoWidth, it.videoHeight) } }
    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { try { mp?.release() } catch (_: Exception) {}; mp = null; return true }
    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
}
