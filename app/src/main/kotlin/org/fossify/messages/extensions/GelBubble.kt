package org.fossify.messages.extensions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import org.fossify.commons.extensions.rotateHue
import kotlin.math.min

fun Context.getGelBubbleEffectiveColor(baseColor: Int): Int =
    baseColor.rotateHue(config.gelBubbleHueShift)

/**
 * Gel bubble v2 (Oct 19, 2026).
 * Shape (body + stem) is a signed distance field measured in pixels, so corners,
 * stem, rim and highlight margins keep their size at any bubble length; only the
 * straight runs get longer. Lighting follows the real silhouette, including the stem.
 * Android 13+ draws everything in AGSL. Older versions use a Path/gradient fallback.
 */
fun Context.createGelBubbleDrawable(baseColor: Int, isSent: Boolean): Drawable =
    GelBubbleDrawableV2(getGelBubbleEffectiveColor(baseColor), isSent, resources.displayMetrics.density)

private class GelBubbleDrawableV2(
    private val color: Int,
    private val sent: Boolean,
    private val density: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
            try {
                drawShader(canvas, w, h)
            } catch (e: Throwable) {
                shaderFailed = true
                drawFallback(canvas, w, h)
            }
        } else {
            drawFallback(canvas, w, h)
        }
        canvas.restoreToCount(saved)
    }

    @RequiresApi(33)
    private fun drawShader(canvas: Canvas, w: Float, h: Float) {
        val s = sharedShader()
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("baseColor", Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        s.setFloatUniform("dp", density)
        s.setFloatUniform("sent", if (sent) 1f else 0f)
        paint.shader = s
        paint.alpha = alphaValue
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    // Pre-Android 13: same layout, single unioned outline (no seam), flat gradients.
    private fun drawFallback(canvas: Canvas, w: Float, h: Float) {
        val dp = density
        val tail = min(10f * dp, w * .15f)
        val bottom = h - 4f * dp
        val left = if (sent) 0f else tail
        val right = if (sent) w - tail else w
        val radius = min(20f * dp, bottom * .5f)
        val body = Path().apply { addRoundRect(RectF(left, 0f, right, bottom), radius, radius, Path.Direction.CW) }
        val stem = Path()
        if (sent) {
            stem.moveTo(right - radius * .9f, bottom - radius * 1.1f)
            stem.quadTo(right, bottom - radius * .2f, w - dp, bottom)
            stem.lineTo(right - radius * .9f, bottom)
        } else {
            stem.moveTo(left + radius * .9f, bottom - radius * 1.1f)
            stem.quadTo(left, bottom - radius * .2f, dp, bottom)
            stem.lineTo(left + radius * .9f, bottom)
        }
        stem.close()
        val outline = Path()
        outline.op(body, stem, Path.Op.UNION)

        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        paint.alpha = alphaValue
        paint.shader = LinearGradient(0f, 0f, 0f, bottom,
            intArrayOf(Color.rgb(mix(r, 0, .30f), mix(g, 0, .30f), mix(b, 0, .30f)), color,
                Color.rgb(mix(r, 255, .25f), mix(g, 255, .25f), mix(b, 255, .25f))),
            floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(outline, paint)
        // Inset highlight with fixed pixel margins, so it does not stretch at the ends.
        val inset = radius * .32f
        val hlRect = RectF(left + inset, radius * .10f, right - inset, bottom * .56f)
        paint.shader = LinearGradient(0f, hlRect.top, 0f, hlRect.bottom,
            Color.argb(185, 255, 255, 255), Color.argb(10, 255, 255, 255), Shader.TileMode.CLAMP)
        val hlR = min(radius * .85f, hlRect.height() / 2f)
        canvas.drawRoundRect(hlRect, hlR, hlR, paint)
        paint.shader = null
        rim.strokeWidth = 1f * dp
        rim.color = Color.argb(alphaValue * 60 / 255, 0, 0, 0)
        canvas.drawPath(outline, rim)
    }

    private fun mix(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        @Volatile private var shaderFailed = false
        private var cached: Any? = null

        // One compiled shader shared by every bubble. Uniform changes snapshot a new
        // native instance at record time, so sharing it across bubbles is safe.
        @RequiresApi(33)
        private fun sharedShader(): RuntimeShader =
            (cached as? RuntimeShader) ?: RuntimeShader(AGSL).also { cached = it }

        private const val AGSL = """
uniform float2 size;
uniform float3 baseColor;
uniform float dp;
uniform float sent;

float sdRoundBox(float2 p, float2 c, float2 b, float r) {
    float2 q = abs(p - c) - b + r;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float sdTri(float2 p, float2 a, float2 b, float2 c) {
    float2 e0 = b - a; float2 e1 = c - b; float2 e2 = a - c;
    float2 v0 = p - a; float2 v1 = p - b; float2 v2 = p - c;
    float2 q0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
    float2 q1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
    float2 q2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
    float s = sign(e0.x * e2.y - e0.y * e2.x);
    float2 d = min(min(float2(dot(q0, q0), s * (v0.x * e0.y - v0.y * e0.x)),
                       float2(dot(q1, q1), s * (v1.x * e1.y - v1.y * e1.x))),
                       float2(dot(q2, q2), s * (v2.x * e2.y - v2.y * e2.x)));
    return -sqrt(d.x) * sign(d.y);
}

float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float shapeSdf(float2 p) {
    float W = size.x; float H = size.y;
    float t = min(10.0 * dp, W * 0.15);
    float y1 = H - 4.0 * dp;
    float x0 = sent > 0.5 ? 0.0 : t;
    float x1 = sent > 0.5 ? W - t : W;
    float r = min(20.0 * dp, y1 * 0.5);
    float d = sdRoundBox(p, float2((x0 + x1) * 0.5, y1 * 0.5), float2((x1 - x0) * 0.5, y1 * 0.5), r);
    float tri;
    if (sent > 0.5) {
        tri = sdTri(p, float2(x1 - r * 0.9, y1 - r * 1.1), float2(x1 - r * 0.9, y1), float2(W - dp, y1));
    } else {
        tri = sdTri(p, float2(x0 + r * 0.9, y1 - r * 1.1), float2(dp, y1), float2(x0 + r * 0.9, y1));
    }
    return smin(d, tri - 1.2 * dp, r * 0.75);
}

half4 main(float2 p) {
    float W = size.x; float H = size.y;
    float t = min(10.0 * dp, W * 0.15);
    float y1 = H - 4.0 * dp;
    float x0 = sent > 0.5 ? 0.0 : t;
    float x1 = sent > 0.5 ? W - t : W;
    float bh = y1;
    float r = min(20.0 * dp, bh * 0.5);
    float R = r * 0.95;

    float d = shapeSdf(p);
    float e = 1.2 * dp;
    float2 g = float2(shapeSdf(p + float2(e, 0.0)) - shapeSdf(p - float2(e, 0.0)),
                      shapeSdf(p + float2(0.0, e)) - shapeSdf(p - float2(0.0, e)));
    g = g / (length(g) + 1e-5);

    // Inflated cross-section: circular dome over the bevel width, flat beyond it.
    float s = clamp(-d / R, 0.0, 1.0);
    float z = sqrt(max(1.0 - (1.0 - s) * (1.0 - s), 1e-4));
    float slope = (1.0 - s) / z;
    float3 n = normalize(float3(g * slope, 1.0));

    float3 base = baseColor;
    float lum = dot(base, float3(0.299, 0.587, 0.114));
    float3 deep = clamp(mix(float3(lum), base, 1.35) * 0.62, 0.0, 1.0);
    float3 light = mix(base, float3(1.0), 0.45);
    float3 col = mix(deep, base * 1.04, smoothstep(0.0, 1.0, z));

    // Transmitted light pooling along the lower inside edge.
    float pool = smoothstep(0.05, 0.85, clamp(n.y, 0.0, 1.0)) * pow(max(1.0 - z, 0.0), 0.6);
    col = mix(col, light, pool * 0.75);

    // Broad inner glow low in the body.
    float iy = (p.y - (y1 - bh * 0.28)) / (bh * 0.30);
    col += (light - base) * exp(-iy * iy) * z * 0.45;

    // Top highlight: inset box with fixed pixel margins, so its ends never stretch.
    float hx0 = x0 + R * 0.32; float hx1 = x1 - R * 0.32;
    float hy0 = R * 0.10; float hy1 = bh * 0.56;
    float hd = sdRoundBox(p, float2((hx0 + hx1) * 0.5, (hy0 + hy1) * 0.5),
                          float2((hx1 - hx0) * 0.5, (hy1 - hy0) * 0.5), min(R * 0.85, (hy1 - hy0) * 0.5));
    float hmask = smoothstep(2.5 * dp, -7.0 * dp, hd);
    float vfade = clamp(1.0 - (p.y - hy0) / (hy1 - hy0), 0.0, 1.0);
    col = mix(col, float3(1.0), hmask * (0.05 + 0.72 * pow(vfade, 2.2)));

    // Sharp specular rim.
    float3 Hh = normalize(normalize(float3(-0.35, -0.75, 0.56)) + float3(0.0, 0.0, 1.0));
    col += pow(max(dot(n, Hh), 0.0), 40.0) * 0.55;
    col = clamp(col, 0.0, 1.0);

    float cov = clamp(0.5 - d, 0.0, 1.0);
    float aIn = cov * mix(0.88, 0.97, z);

    // Soft coloured glow under the bubble, faded out before the view edge.
    float dg = shapeSdf(p - float2(0.0, 1.5 * dp));
    float edge = smoothstep(0.0, 2.5 * dp, min(min(p.x, W - p.x), min(p.y, H - p.y)));
    float glow = exp(-max(dg, 0.0) / (1.8 * dp)) * 0.40 * (1.0 - cov) * edge;

    float3 rgb = col * aIn + base * 0.9 * glow;
    return half4(half3(rgb), half(clamp(aIn + glow, 0.0, 1.0)));
}
"""
    }
}

// ---- v1 renderer (commit aed787e), kept for reference, no longer used ----
// private class GelBubbleDrawable(
//     private val color: Int,
//     private val sent: Boolean,
//     private val density: Float
// ) : Drawable() {
//     private val body = Paint(Paint.ANTI_ALIAS_FLAG)
//     private val detail = Paint(Paint.ANTI_ALIAS_FLAG)
//     private val outline = Path()
//     private val rect = RectF()
//     private var shader: android.graphics.RuntimeShader? = null
//     private var shaderFailed = false
//
//     override fun draw(canvas: Canvas) {
//         val w = bounds.width().toFloat()
//         val h = bounds.height().toFloat()
//         if (w <= 0f || h <= 0f) return
//         val saved = canvas.save()
//         canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
//         val tail = min(9f * density, w * .12f)
//         val radius = min(min(19f * density, h * .47f), (w - tail) * .40f)
//         val left = if (sent) 0f else tail
//         val right = if (sent) w - tail else w
//         rect.set(left, 0f, right, h)
//         outline.reset()
//         outline.addRoundRect(rect, radius, radius, Path.Direction.CW)
//         // Continuous curved tail, joined to the bottom of the body.
//         val stem = Path()
//         if (sent) {
//             stem.moveTo(right - radius * .55f, h - 1f)
//             stem.quadTo(right + tail * .20f, h - 1f, w, h - 1f)
//             stem.quadTo(right + tail * .22f, h - radius * .52f, right - radius * .12f, h - radius * .95f)
//         } else {
//             stem.moveTo(left + radius * .55f, h - 1f)
//             stem.quadTo(left - tail * .20f, h - 1f, 0f, h - 1f)
//             stem.quadTo(left - tail * .22f, h - radius * .52f, left + radius * .12f, h - radius * .95f)
//         }
//         stem.close()
//         outline.addPath(stem)
//
//         // The translucent shadow is restrained so adjacent messages remain readable.
//         body.reset()
//         body.isAntiAlias = true
//         body.color = Color.argb(35, Color.red(color), Color.green(color), Color.blue(color))
//         body.setShadowLayer(3f * density, 0f, 1.5f * density, Color.argb(55, 0, 0, 0))
//         canvas.drawPath(outline, body)
//         body.clearShadowLayer()
//
//         canvas.save()
//         canvas.clipPath(outline)
//         if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
//             try {
//                 if (shader == null) shader = android.graphics.RuntimeShader(AGSL)
//                 val s = requireNotNull(shader)
//                 s.setFloatUniform("size", w, h)
//                 s.setFloatUniform("baseColor", Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
//                 body.shader = s
//                 canvas.drawRect(0f, 0f, w, h, body)
//                 body.shader = null
//             } catch (_: Exception) {
//                 shaderFailed = true
//                 drawFallback(canvas, w, h)
//             }
//         } else {
//             drawFallback(canvas, w, h)
//         }
//
//         // Narrow rim light follows the true bubble silhouette, including its tail.
//         detail.reset()
//         detail.isAntiAlias = true
//         detail.style = Paint.Style.STROKE
//         detail.strokeWidth = 1.25f * density
//         detail.color = Color.argb(75, 255, 255, 255)
//         canvas.drawPath(outline, detail)
//         canvas.restore()
//         canvas.restoreToCount(saved)
//     }
//
//     private fun drawFallback(canvas: Canvas, w: Float, h: Float) {
//         val r = Color.red(color)
//         val g = Color.green(color)
//         val b = Color.blue(color)
//         body.shader = LinearGradient(0f, 0f, 0f, h,
//             intArrayOf(Color.rgb(mix(r,255,.32f), mix(g,255,.32f), mix(b,255,.32f)),
//                 color, Color.rgb(mix(r,0,.24f), mix(g,0,.24f), mix(b,0,.24f))),
//             floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
//         canvas.drawRect(0f, 0f, w, h, body)
//         body.shader = RadialGradient(w * .38f, h * .03f, maxOf(w * .66f, h * .50f),
//             intArrayOf(Color.argb(95,255,255,255), Color.TRANSPARENT),
//             null, Shader.TileMode.CLAMP)
//         canvas.drawRect(0f, 0f, w, h, body)
//         body.shader = null
//     }
//
//     private fun mix(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0,255)
//     override fun setAlpha(alpha: Int) { body.alpha = alpha; invalidateSelf() }
//     @Suppress("DEPRECATION")
//     override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { body.colorFilter = colorFilter; invalidateSelf() }
//     @Suppress("DEPRECATION")
//     override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
//
//     companion object {
//         // A gently inflated cross-section with diffuse transmitted light,
//         // rim darkening and broad specular reflections (no painted oval).
//         private const val AGSL = """
// uniform float2 size;
// uniform float3 baseColor;
// half4 main(float2 p) {
//     float2 uv = p / max(size, float2(1.0));
//     float edge = min(min(uv.x, 1.0-uv.x), min(uv.y, 1.0-uv.y));
//     float rim = 1.0 - smoothstep(0.0, 0.16, edge);
//     float dome = sqrt(max(0.0, 1.0 - pow(2.0*uv.y-1.0, 2.0)));
//     float topLight = exp(-pow((uv.y-0.08)/0.23, 2.0));
//     float reflection = exp(-pow((uv.x-0.28)/0.46, 2.0) - pow((uv.y-0.055)/0.12, 2.0));
//     float lowerBounce = exp(-pow((uv.y-0.94)/0.10, 2.0));
//     float3 c = baseColor * (0.77 + 0.24*dome + 0.12*topLight - 0.22*rim);
//     c = mix(c, float3(1.0), 0.24*reflection + 0.10*lowerBounce);
//     c += 0.08*pow(max(0.0, 1.0-uv.x), 2.0);
//     return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
// }
// """
//     }
// }
