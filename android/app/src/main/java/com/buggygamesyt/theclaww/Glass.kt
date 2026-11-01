package com.buggygamesyt.theclaww

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View

/**
 * A low-res copy of whatever wallpaper is behind the UI (gradient, photo, animated, or video),
 * which liquid-glass surfaces sample and refract. Animated/video wallpapers refresh it ~30 fps.
 */
object Backdrop {
    const val SCALE = 0.4f
    var bitmap: Bitmap? = null; private set
    var root: View? = null
    var dimColor = Color.BLACK
    var dimAlpha = 0f
    var active = false
    private val clients = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    fun ensure(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val bw = maxOf(1, (w * SCALE).toInt()); val bh = maxOf(1, (h * SCALE).toInt())
        val b = bitmap
        if (b != null && b.width == bw && b.height == bh) return b
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }

    /** Paint the backdrop with [draw] (in full-size screen coordinates) and refresh every glass surface. */
    fun render(w: Int, h: Int, draw: (Canvas) -> Unit) {
        if (!active) return
        val b = ensure(w, h) ?: return
        val c = Canvas(b); c.save(); c.scale(b.width.toFloat() / w, b.height.toFloat() / h); draw(c); c.restore()
        root?.post { changed() }
    }

    fun register(v: View) { clients.add(v) }
    /** Content behind or around glass moved (wallpaper frame, scroll, page swipe): redraw the glass. */
    fun changed() { if (active) clients.toList().forEach { it.invalidate() } }
    fun reset() { clients.clear(); bitmap = null; root = null; active = false }
}

/**
 * Apple-style Liquid Glass: a rounded lens that refracts and magnifies the wallpaper toward its edges,
 * with slight chromatic fringing, a light tint, a bright specular rim and a top sheen. Android 13+ (AGSL).
 */
@android.annotation.TargetApi(33)
class LiquidGlass(private val radius: Float, private val tint: Int, private val tintAmount: Float, private val density: Float) : Drawable() {
    private val shader = android.graphics.RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2); private val rloc = IntArray(2)
    private var boundBitmap: Bitmap? = null
    private var registered = false

    override fun draw(canvas: Canvas) {
        val v = callback as? View ?: return
        if (!registered) { Backdrop.register(v); registered = true }
        val bmp = Backdrop.bitmap ?: return
        if (bmp !== boundBitmap) {
            shader.setInputShader("wall", BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)); boundBitmap = bmp
        }
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        v.getLocationInWindow(loc); (Backdrop.root ?: v.rootView).getLocationInWindow(rloc)
        shader.setFloatUniform("size", b.width().toFloat(), b.height().toFloat())
        shader.setFloatUniform("origin", (loc[0] - rloc[0] + b.left).toFloat(), (loc[1] - rloc[1] + b.top).toFloat())
        shader.setFloatUniform("scale", bmp.width.toFloat() / maxOf(1, (Backdrop.root?.width ?: bmp.width)), bmp.height.toFloat() / maxOf(1, (Backdrop.root?.height ?: bmp.height)))
        shader.setFloatUniform("radius", minOf(radius, b.width() / 2f, b.height() / 2f))
        shader.setFloatUniform("bend", 14f * density)
        shader.setFloatUniform("band", minOf(22f * density, minOf(b.width(), b.height()) * 0.45f))
        shader.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAmount.coerceIn(0f, 0.9f))
        shader.setFloatUniform("dim", Color.red(Backdrop.dimColor) / 255f, Color.green(Backdrop.dimColor) / 255f, Color.blue(Backdrop.dimColor) / 255f, Backdrop.dimAlpha)
        shader.setFloatUniform("px", density)
        paint.shader = shader
        canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRect(0f, 0f, b.width().toFloat(), b.height().toFloat(), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        val supported get() = Build.VERSION.SDK_INT >= 33
        private const val SRC = """
uniform shader wall;
uniform float2 size;
uniform float2 origin;
uniform float2 scale;
uniform float radius;
uniform float bend;
uniform float band;
uniform float4 tint;
uniform float4 dim;
uniform float px;

float sdBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}
half3 bg(float2 s) {
    half3 c = wall.eval(s * scale).rgb;
    return mix(c, half3(dim.rgb), half(dim.a));
}
half4 main(float2 xy) {
    float2 hs = size * 0.5;
    float2 p = xy - hs;
    float d = sdBox(p, hs, radius);
    if (d > 1.0) return half4(0.0);
    float2 e = float2(1.0, 0.0);
    float2 n = float2(sdBox(p + e.xy, hs, radius) - sdBox(p - e.xy, hs, radius),
                      sdBox(p + e.yx, hs, radius) - sdBox(p - e.yx, hs, radius));
    n = n / max(length(n), 1e-4);
    // 0 at the rim -> 1 deep inside; refraction is strongest in the edge band, like a thick lens
    float t = clamp(-d / band, 0.0, 1.0);
    float k = pow(1.0 - t, 2.2);
    float2 off = -n * k * bend;               // pull the image inward at the edges
    float2 s = origin + hs + p * 0.94 + off;  // gentle overall magnification
    float2 ca = n * k * 2.2 * px;             // chromatic fringe near the rim
    half3 col = half3(bg(s + ca).r, bg(s).g, bg(s - ca).b);
    // tint and a touch of brightness
    col = mix(col, half3(tint.rgb), half(tint.a));
    col = col * 1.04 + 0.03;
    // top sheen
    float sheen = (1.0 - smoothstep(0.0, size.y * 0.55, xy.y)) * 0.10;
    // specular rim: bright where the edge faces the light (top-left), softer on the far side
    float rimBand = 1.0 - smoothstep(0.0, 2.2 * px, -d);
    float light = max(dot(n, normalize(float2(-0.55, -0.85))), 0.0);
    float back = max(dot(n, normalize(float2(0.55, 0.85))), 0.0);
    float rim = rimBand * (0.30 + 0.65 * light + 0.30 * back);
    float inner = (1.0 - smoothstep(0.0, 7.0 * px, -d)) * 0.06;
    col = col + half3(sheen + rim + inner);
    float a = 1.0 - smoothstep(-0.75, 0.75, d);
    return half4(clamp(col, 0.0, 1.0) * half(a), half(a));
}
"""
    }
}
