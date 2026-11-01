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
