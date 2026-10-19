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
import android.widget.TextView
import androidx.annotation.RequiresApi
import org.fossify.commons.extensions.rotateHue
import kotlin.math.min

fun Context.getGelBubbleEffectiveColor(baseColor: Int): Int =
    baseColor.rotateHue(config.gelBubbleHueShift)

/**
 * Gel bubble v7 (Oct 19, 2026): reflected light sources have defined edges (soft
 * gaussian falloff read as airbrush); higher contrast between deep edges and core.
 * Gel bubble v6 (Oct 19, 2026): looser, organic look. No outline-tracing bands; the
 * surface reflects a soft studio (overhead softbox + uneven windows) through its own
 * curvature, light soaks through as one soft uneven glow, low-frequency noise breaks
 * up perfect evenness.
 * Gel bubble v5 (Oct 19, 2026): rounded surface instead of flat: stronger top-lit
 * shading, darker dip under the bulge, gloss streak driven by surface curvature.
 * Gel bubble v4 (Oct 19, 2026): true pill (radius = half height, up to 40dp), whole body
 * domed, broad soft sheen + wrapping end reflections, lit stem, and glow/shadow drawn
 * outside the view (item_message.xml / activity_thread.xml set clipChildren=false).
 * Gel bubble v3 (Oct 19, 2026): pill body, tapered rounded stem, deep saturated edges,
 * internal caustic glow, top specular line + end crescents, coloured light cast below.
 * Gel bubble v2 (Oct 19, 2026).
 * Shape (body + stem) is a signed distance field measured in pixels, so corners,
 * stem, rim and highlight margins keep their size at any bubble length; only the
 * straight runs get longer. Lighting follows the real silhouette, including the stem.
 * Android 13+ draws everything in AGSL. Older versions use a Path/gradient fallback.
 */
fun Context.createGelBubbleDrawable(baseColor: Int, isSent: Boolean): Drawable =
    GelBubbleDrawableV2(getGelBubbleEffectiveColor(baseColor), isSent, resources.displayMetrics.density)

// Geometry the shader and the fallback share, in dp. The view padding below
// adds these so text stays centred inside the visible bubble.
// private const val GEL_TAIL_DP = 10f   // v3
// private const val GEL_TOP_DP = 2f
// private const val GEL_BOTTOM_DP = 10f
// private const val GEL_SIDE_DP = 1f
// private const val GEL_MAX_RADIUS_DP = 26f
private const val GEL_TAIL_DP = 9f
private const val GEL_TOP_DP = 0f
private const val GEL_BOTTOM_DP = 0f
private const val GEL_SIDE_DP = 0f
private const val GEL_MAX_RADIUS_DP = 40f
// Extra horizontal text inset so text clears the fully round ends.
private const val GEL_END_INSET_DP = 8f
// How far glow/shadow may spill outside the view.
private const val GEL_SPILL_SIDE_DP = 14f
private const val GEL_SPILL_TOP_DP = 8f
private const val GEL_SPILL_BOTTOM_DP = 26f

/** Gel mode: padding that clears the stem and glow margin, white text with a soft shadow. */
fun TextView.applyGelBubbleText(isSent: Boolean) {
    val d = resources.displayMetrics.density
    val base = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_margin)
    val tail = (GEL_TAIL_DP * d).toInt()
    val side = (GEL_SIDE_DP * d).toInt()
    val inset = (GEL_END_INSET_DP * d).toInt()
    val start = base + inset + if (isSent) side else tail
    val end = base + inset + if (isSent) tail else side
    setPaddingRelative(start, base + (GEL_TOP_DP * d).toInt(), end, base + (GEL_BOTTOM_DP * d).toInt())
    setTextColor(Color.WHITE)
    setLinkTextColor(Color.WHITE)
    setShadowLayer(2.5f * d, 0f, 1.2f * d, Color.argb(110, 0, 0, 0))
}

/** Normal mode: undo the gel padding and shadow (views are recycled). */
fun TextView.clearGelBubbleText() {
    val base = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_margin)
    setPaddingRelative(base, base, base, base)
    setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
}

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
        // canvas.drawRect(0f, 0f, w, h, paint)   // v3: glow confined to the view
        val d = density
        canvas.drawRect(-GEL_SPILL_SIDE_DP * d, -GEL_SPILL_TOP_DP * d,
            w + GEL_SPILL_SIDE_DP * d, h + GEL_SPILL_BOTTOM_DP * d, paint)
        paint.shader = null
    }

    // Pre-Android 13: same layout, single unioned outline (no seam), flat gradients.
    private fun drawFallback(canvas: Canvas, w: Float, h: Float) {
        val dp = density
        // val tail = min(10f * dp, w * .15f)   // v2 geometry
        // val bottom = h - 4f * dp
        // val left = if (sent) 0f else tail
        // val right = if (sent) w - tail else w
        // val radius = min(20f * dp, bottom * .5f)
        val tail = GEL_TAIL_DP * dp
        val top = GEL_TOP_DP * dp
        val bottom = h - GEL_BOTTOM_DP * dp
        val left = if (sent) GEL_SIDE_DP * dp else tail
        val right = if (sent) w - tail else w - GEL_SIDE_DP * dp
        val radius = min(GEL_MAX_RADIUS_DP * dp, (bottom - top) * .5f)
        val body = Path().apply { addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW) }
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

        private const val AGSL = """uniform float2 size;
uniform float3 baseColor;
uniform float dp;
uniform float sent;

// Coordinates are the view's own: (0,0)-(size). The bubble fills that box except
// the stem column; glow and shadow are drawn OUTSIDE it (parents don't clip).

float sdRoundBox(float2 p, float2 c, float2 b, float r) {
    float2 q = abs(p - c) - b + r;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float cro(float2 a, float2 b) { return a.x * b.y - a.y * b.x; }

// Tapered capsule (iq): thick where it joins the body, thin at the tip.
float sdUnevenCapsule(float2 p, float2 pa, float2 pb, float ra, float rb) {
    p -= pa; pb -= pa;
    float h = dot(pb, pb);
    float2 q = float2(dot(p, float2(pb.y, -pb.x)), dot(p, pb)) / h;
    q.x = abs(q.x);
    float b = ra - rb;
    float2 c = float2(sqrt(max(h - b * b, 1e-4)), b);
    float k = cro(c, q);
    float m = dot(c, q);
    float n = dot(q, q);
    if (k < 0.0) return sqrt(h * n) - ra;
    if (k > c.x) return sqrt(h * (q.x * q.x + (q.y - 1.0) * (q.y - 1.0))) - rb;
    return m - ra;
}

// Smooth value noise, measured in pixels, for organic unevenness.
float hash(float2 q) { return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453); }
float vnoise(float2 q) {
    float2 i = floor(q); float2 f = fract(q);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}

// What the gel reflects: a soft studio, not lines. Two large irregular window
// lights up top, a bright sky gradient, coloured bounce from below.
// What the gel reflects: real light sources with defined edges. Soft gaussian
// falloff is what reads as airbrush; hard-edged shapes read as gloss.
float boxIn(float2 q, float2 c, float2 h, float rad, float soft) {
    float2 k = abs(q - c) - h + rad;
    float dd = length(max(k, float2(0.0))) + min(max(k.x, k.y), 0.0) - rad;
    return 1.0 - smoothstep(-soft, soft, dd);
}

float3 envLight(float3 r, float3 tint, float wob) {
    float2 q = r.xy;
    // Long overhead softbox -> crisp glossy band riding the top curve.
    float win0 = boxIn(q, float2(-0.05, -0.90), float2(0.78, 0.16), 0.12, 0.03) * (0.55 + 0.45 * smoothstep(0.7, -0.5, q.x));
    // Upper-left window (brightest), upper-right smaller one.
    float win1 = boxIn(q, float2(-0.64, -0.56), float2(0.17, 0.14), 0.08, 0.02 + wob * 0.01);
    float win2 = boxIn(q, float2(0.68, -0.50), float2(0.09, 0.08), 0.05, 0.02);
    // Faint secondary glow around the windows (bloom), much weaker.
    float bloom = exp(-dot(q - float2(-0.4, -0.7), q - float2(-0.4, -0.7)) * 3.0) * 0.12;
    float sky = smoothstep(0.10, -0.70, q.y) * 0.10;
    float bounce = smoothstep(0.10, 0.80, q.y);
    return float3(1.0) * (win0 * 0.70 + win1 * 1.20 + win2 * 1.0 + bloom + sky) + tint * bounce * 0.60;
}

float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float radiusFor(float bh) { return min(bh * 0.5, 40.0 * dp); }

float bodySdf(float2 p) {
    float W = size.x; float H = size.y;
    float t = 9.0 * dp;
    float x0 = sent > 0.5 ? 0.0 : t;
    float x1 = sent > 0.5 ? W - t : W;
    return sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), radiusFor(H));
}

float shapeSdf(float2 p) {
    float W = size.x; float H = size.y;
    float t = 9.0 * dp;
    float x0 = sent > 0.5 ? 0.0 : t;
    float x1 = sent > 0.5 ? W - t : W;
    float r = radiusFor(H);
    float d = sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), r);
    // Stem grows out of the lower corner like a drip of the same gel.
    float sr = min(r * 0.55, 11.0 * dp);
    float stem;
    if (sent > 0.5) {
        stem = sdUnevenCapsule(p, float2(x1 - sr * 1.1, H - sr * 1.05), float2(W - 2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
    } else {
        stem = sdUnevenCapsule(p, float2(x0 + sr * 1.1, H - sr * 1.05), float2(2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
    }
    return smin(d, stem, 9.0 * dp);
}

half4 main(float2 p) {
    float W = size.x; float H = size.y;
    float R = radiusFor(H);

    float d = shapeSdf(p);
    float e = 1.0 * dp;
    float2 g = float2(shapeSdf(p + float2(e, 0.0)) - shapeSdf(p - float2(e, 0.0)),
                      shapeSdf(p + float2(0.0, e)) - shapeSdf(p - float2(0.0, e)));
    g = g / (length(g) + 1e-5);

    // Inflated surface across the full radius.
    float s = clamp(-d / R, 0.0, 1.0);
    float z = sqrt(max(1.0 - (1.0 - s) * (1.0 - s), 1e-4));
    float slope = (1.0 - s) / z;
    float3 n = normalize(float3(g * slope, 1.0));

    // Low-frequency wobble so nothing is perfectly even (pixel-scaled).
    float nz1 = vnoise(p / (22.0 * dp));
    float nz2 = vnoise(p / (9.0 * dp) + float2(7.3, 1.9));
    float wob = (nz1 - 0.5) * 0.9 + (nz2 - 0.5) * 0.35;

    float3 base = clamp(baseColor, 0.02, 1.0);
    float3 absorb = -log(base);
    float3 glowC = clamp(base * 1.35 + float3(0.18), 0.0, 1.0);

    // Body colour from how much gel the eye looks through: smooth, no bands.
    float thick = mix(3.0, 0.70, pow(z, 0.85));
    float3 col = mix(exp(-absorb * thick), base * mix(0.5, 1.08, z), 0.30);

    // Light soaking through and gathering low and slightly to one side,
    // as one soft uneven glow rather than a ring.
    float vy = clamp(p.y / H, 0.0, 1.0);
    float2 gc = float2(W * (sent > 0.5 ? 0.42 : 0.58), H * 0.92);
    float2 gq = (p - gc) / float2(max(W * 0.55, 30.0 * dp), H * 0.55);
    float soak = exp(-dot(gq, gq)) * (0.75 + wob * 0.5);
    float down = smoothstep(-0.1, 0.9, n.y) * (1.0 - z * 0.6);
    col = mix(col, glowC, clamp(soak * 0.45 + down * 0.40, 0.0, 0.85));

    // Top-lit roundness, kept gentle.
    float lam = dot(n, normalize(float3(-0.3, -0.7, 0.65)));
    col *= mix(0.80, 1.10, smoothstep(-0.4, 0.95, lam));

    // Reflections come from the environment through the curved surface, so
    // they bend, pool and fade on their own; Fresnel makes the edges glossier.
    float3 rr = 2.0 * n.z * n - float3(0.0, 0.0, 1.0);
    float F = 0.06 + 0.94 * pow(1.0 - n.z, 4.0);
    float onStem = smoothstep(0.5 * dp, 4.0 * dp, bodySdf(p) - d);
    float3 refl = envLight(rr, glowC, wob);
    col += refl * mix(0.55, 1.0, F) * (1.0 - onStem * 0.7);
    col = clamp(col, 0.0, 1.0);

    float cov = clamp(0.5 - d, 0.0, 1.0);
    float aIn = cov * mix(0.90, 0.97, z);

    // Outside: contact shadow, coloured light cast below, wide soft halo.
    float ds = shapeSdf(p - float2(0.0, 2.5 * dp));
    float shadow = (1.0 - smoothstep(-3.0 * dp, 6.0 * dp, ds)) * 0.12;
    float dc = shapeSdf(p - float2(0.0, 5.0 * dp));
    float below = smoothstep(H * 0.35, H, p.y);
    float caustic = (1.0 - smoothstep(-6.0 * dp, 10.0 * dp, dc)) * 0.50 * below * (0.8 + wob * 0.4);
    float dh = shapeSdf(p - float2(0.0, 6.0 * dp));
    float halo = (1.0 - smoothstep(-2.0 * dp, 12.0 * dp, dh)) * 0.20;
    float outA = clamp(shadow + caustic + halo, 0.0, 1.0) * (1.0 - cov);
    float3 outRgb = glowC * (caustic + halo) * (1.0 - cov);

    float3 rgb = col * aIn + outRgb * (1.0 - aIn);
    return half4(half3(rgb), half(clamp(aIn + outA * (1.0 - aIn), 0.0, 1.0)));
}
"""
    }
}

// ---- v6 shader (commit 82b1a72), kept for reference, no longer used ----
//         private const val AGSL = """uniform float2 size;
// uniform float3 baseColor;
// uniform float dp;
// uniform float sent;
//
// // Coordinates are the view's own: (0,0)-(size). The bubble fills that box except
// // the stem column; glow and shadow are drawn OUTSIDE it (parents don't clip).
//
// float sdRoundBox(float2 p, float2 c, float2 b, float r) {
//     float2 q = abs(p - c) - b + r;
//     return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
// }
//
// float cro(float2 a, float2 b) { return a.x * b.y - a.y * b.x; }
//
// // Tapered capsule (iq): thick where it joins the body, thin at the tip.
// float sdUnevenCapsule(float2 p, float2 pa, float2 pb, float ra, float rb) {
//     p -= pa; pb -= pa;
//     float h = dot(pb, pb);
//     float2 q = float2(dot(p, float2(pb.y, -pb.x)), dot(p, pb)) / h;
//     q.x = abs(q.x);
//     float b = ra - rb;
//     float2 c = float2(sqrt(max(h - b * b, 1e-4)), b);
//     float k = cro(c, q);
//     float m = dot(c, q);
//     float n = dot(q, q);
//     if (k < 0.0) return sqrt(h * n) - ra;
//     if (k > c.x) return sqrt(h * (q.x * q.x + (q.y - 1.0) * (q.y - 1.0))) - rb;
//     return m - ra;
// }
//
// // Smooth value noise, measured in pixels, for organic unevenness.
// float hash(float2 q) { return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453); }
// float vnoise(float2 q) {
//     float2 i = floor(q); float2 f = fract(q);
//     f = f * f * (3.0 - 2.0 * f);
//     return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
//                mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
// }
//
// // What the gel reflects: a soft studio, not lines. Two large irregular window
// // lights up top, a bright sky gradient, coloured bounce from below.
// float3 envLight(float3 r, float3 tint, float wob) {
//     float sky = smoothstep(0.15, -0.65, r.y);
//     // Long overhead softbox: becomes a glossy band riding the top curve.
//     float2 w0 = (r.xy - float2(-0.08, -0.80)) / float2(0.95, 0.20);
//     float win0 = exp(-pow(dot(w0, w0), 1.5) * (1.4 + wob * 0.8));
//     // Big window upper-left, smaller one upper-right: uneven, like a real room.
//     float2 w1 = (r.xy - float2(-0.62, -0.55)) / float2(0.30, 0.26);
//     float win1 = exp(-pow(dot(w1, w1), 1.3) * (1.3 + wob));
//     float2 w2 = (r.xy - float2(0.66, -0.50)) / float2(0.17, 0.15);
//     float win2 = exp(-pow(dot(w2, w2), 1.5) * (1.8 - wob));
//     float bounce = smoothstep(0.05, 0.75, r.y);
//     return float3(1.0) * (win0 * 0.85 + win1 * 1.10 + win2 * 0.85 + sky * 0.14) + tint * bounce * 0.55;
// }
//
// float smin(float a, float b, float k) {
//     float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
//     return mix(b, a, h) - k * h * (1.0 - h);
// }
//
// float radiusFor(float bh) { return min(bh * 0.5, 40.0 * dp); }
//
// float bodySdf(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = 9.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     return sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), radiusFor(H));
// }
//
// float shapeSdf(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = 9.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     float r = radiusFor(H);
//     float d = sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), r);
//     // Stem grows out of the lower corner like a drip of the same gel.
//     float sr = min(r * 0.55, 11.0 * dp);
//     float stem;
//     if (sent > 0.5) {
//         stem = sdUnevenCapsule(p, float2(x1 - sr * 1.1, H - sr * 1.05), float2(W - 2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
//     } else {
//         stem = sdUnevenCapsule(p, float2(x0 + sr * 1.1, H - sr * 1.05), float2(2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
//     }
//     return smin(d, stem, 9.0 * dp);
// }
//
// half4 main(float2 p) {
//     float W = size.x; float H = size.y;
//     float R = radiusFor(H);
//
//     float d = shapeSdf(p);
//     float e = 1.0 * dp;
//     float2 g = float2(shapeSdf(p + float2(e, 0.0)) - shapeSdf(p - float2(e, 0.0)),
//                       shapeSdf(p + float2(0.0, e)) - shapeSdf(p - float2(0.0, e)));
//     g = g / (length(g) + 1e-5);
//
//     // Inflated surface across the full radius.
//     float s = clamp(-d / R, 0.0, 1.0);
//     float z = sqrt(max(1.0 - (1.0 - s) * (1.0 - s), 1e-4));
//     float slope = (1.0 - s) / z;
//     float3 n = normalize(float3(g * slope, 1.0));
//
//     // Low-frequency wobble so nothing is perfectly even (pixel-scaled).
//     float nz1 = vnoise(p / (22.0 * dp));
//     float nz2 = vnoise(p / (9.0 * dp) + float2(7.3, 1.9));
//     float wob = (nz1 - 0.5) * 0.9 + (nz2 - 0.5) * 0.35;
//
//     float3 base = clamp(baseColor, 0.02, 1.0);
//     float3 absorb = -log(base);
//     float3 glowC = clamp(base * 1.35 + float3(0.18), 0.0, 1.0);
//
//     // Body colour from how much gel the eye looks through: smooth, no bands.
//     float thick = mix(2.4, 0.78, pow(z, 0.9));
//     float3 col = mix(exp(-absorb * thick), base * mix(0.6, 1.05, z), 0.35);
//
//     // Light soaking through and gathering low and slightly to one side,
//     // as one soft uneven glow rather than a ring.
//     float vy = clamp(p.y / H, 0.0, 1.0);
//     float2 gc = float2(W * (sent > 0.5 ? 0.42 : 0.58), H * 0.92);
//     float2 gq = (p - gc) / float2(max(W * 0.55, 30.0 * dp), H * 0.55);
//     float soak = exp(-dot(gq, gq)) * (0.75 + wob * 0.5);
//     float down = smoothstep(-0.1, 0.9, n.y) * (1.0 - z * 0.6);
//     col = mix(col, glowC, clamp(soak * 0.45 + down * 0.40, 0.0, 0.85));
//
//     // Top-lit roundness, kept gentle.
//     float lam = dot(n, normalize(float3(-0.3, -0.7, 0.65)));
//     col *= mix(0.80, 1.10, smoothstep(-0.4, 0.95, lam));
//
//     // Reflections come from the environment through the curved surface, so
//     // they bend, pool and fade on their own; Fresnel makes the edges glossier.
//     float3 rr = 2.0 * n.z * n - float3(0.0, 0.0, 1.0);
//     float F = 0.06 + 0.94 * pow(1.0 - n.z, 4.0);
//     float onStem = smoothstep(0.5 * dp, 4.0 * dp, bodySdf(p) - d);
//     float3 refl = envLight(rr, glowC, wob);
//     col += refl * mix(0.30, 1.0, F) * (1.0 - onStem * 0.7);
//     col = clamp(col, 0.0, 1.0);
//
//     float cov = clamp(0.5 - d, 0.0, 1.0);
//     float aIn = cov * mix(0.90, 0.97, z);
//
//     // Outside: contact shadow, coloured light cast below, wide soft halo.
//     float ds = shapeSdf(p - float2(0.0, 2.5 * dp));
//     float shadow = (1.0 - smoothstep(-3.0 * dp, 6.0 * dp, ds)) * 0.12;
//     float dc = shapeSdf(p - float2(0.0, 5.0 * dp));
//     float below = smoothstep(H * 0.35, H, p.y);
//     float caustic = (1.0 - smoothstep(-6.0 * dp, 10.0 * dp, dc)) * 0.50 * below * (0.8 + wob * 0.4);
//     float dh = shapeSdf(p - float2(0.0, 6.0 * dp));
//     float halo = (1.0 - smoothstep(-2.0 * dp, 12.0 * dp, dh)) * 0.20;
//     float outA = clamp(shadow + caustic + halo, 0.0, 1.0) * (1.0 - cov);
//     float3 outRgb = glowC * (caustic + halo) * (1.0 - cov);
//
//     float3 rgb = col * aIn + outRgb * (1.0 - aIn);
//     return half4(half3(rgb), half(clamp(aIn + outA * (1.0 - aIn), 0.0, 1.0)));
// }
// """

// ---- v5 shader (commit 66a007b), kept for reference, no longer used ----
//         private const val AGSL = """uniform float2 size;
// uniform float3 baseColor;
// uniform float dp;
// uniform float sent;
//
// // Coordinates are the view's own: (0,0)-(size). The bubble fills that box except
// // the stem column; glow and shadow are drawn OUTSIDE it (parents don't clip).
//
// float sdRoundBox(float2 p, float2 c, float2 b, float r) {
//     float2 q = abs(p - c) - b + r;
//     return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
// }
//
// float cro(float2 a, float2 b) { return a.x * b.y - a.y * b.x; }
//
// // Tapered capsule (iq): thick where it joins the body, thin at the tip.
// float sdUnevenCapsule(float2 p, float2 pa, float2 pb, float ra, float rb) {
//     p -= pa; pb -= pa;
//     float h = dot(pb, pb);
//     float2 q = float2(dot(p, float2(pb.y, -pb.x)), dot(p, pb)) / h;
//     q.x = abs(q.x);
//     float b = ra - rb;
//     float2 c = float2(sqrt(max(h - b * b, 1e-4)), b);
//     float k = cro(c, q);
//     float m = dot(c, q);
//     float n = dot(q, q);
//     if (k < 0.0) return sqrt(h * n) - ra;
//     if (k > c.x) return sqrt(h * (q.x * q.x + (q.y - 1.0) * (q.y - 1.0))) - rb;
//     return m - ra;
// }
//
// float smin(float a, float b, float k) {
//     float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
//     return mix(b, a, h) - k * h * (1.0 - h);
// }
//
// float radiusFor(float bh) { return min(bh * 0.5, 40.0 * dp); }
//
// float bodySdf(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = 9.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     return sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), radiusFor(H));
// }
//
// float shapeSdf(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = 9.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     float r = radiusFor(H);
//     float d = sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), r);
//     // Stem grows out of the lower corner like a drip of the same gel.
//     float sr = min(r * 0.55, 11.0 * dp);
//     float stem;
//     if (sent > 0.5) {
//         stem = sdUnevenCapsule(p, float2(x1 - sr * 1.1, H - sr * 1.05), float2(W - 2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
//     } else {
//         stem = sdUnevenCapsule(p, float2(x0 + sr * 1.1, H - sr * 1.05), float2(2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
//     }
//     return smin(d, stem, 9.0 * dp);
// }
//
// half4 main(float2 p) {
//     float W = size.x; float H = size.y;
//     float R = radiusFor(H);
//
//     float d = shapeSdf(p);
//     float e = 1.0 * dp;
//     float2 g = float2(shapeSdf(p + float2(e, 0.0)) - shapeSdf(p - float2(e, 0.0)),
//                       shapeSdf(p + float2(0.0, e)) - shapeSdf(p - float2(0.0, e)));
//     g = g / (length(g) + 1e-5);
//
//     // Inflated cross-section over the full radius: the whole body is curved.
//     float s = clamp(-d / R, 0.0, 1.0);
//     float z = sqrt(max(1.0 - (1.0 - s) * (1.0 - s), 1e-4));
//     float slope = (1.0 - s) / z;
//     float3 n = normalize(float3(g * slope, 1.0));
//
//     float3 base = clamp(baseColor, 0.02, 1.0);
//     float3 absorb = -log(base);
//     float3 glowC = clamp(base * 1.35 + float3(0.18), 0.0, 1.0);
//
//     // Body: deep saturated rim, luminous core (hue held, not drifting).
//     float3 deep = mix(exp(-absorb * 2.6), base * 0.62, 0.45);
//     float3 core = exp(-absorb * 0.75);
//     float3 col = mix(deep, core, smoothstep(0.0, 0.85, s));
//
//     // Volume: soft diffuse from an overhead light across the whole dome,
//     // so the middle reads as rounded, darker under the top, brighter low down.
//     float3 L = normalize(float3(-0.25, -0.75, 0.62));
//     float lam = dot(n, L);
//     col *= mix(0.62, 1.20, smoothstep(-0.3, 0.95, lam));
//     float vy = clamp(p.y / H, 0.0, 1.0);
//     col = mix(col, glowC, smoothstep(0.45, 1.0, vy) * 0.38 * smoothstep(0.1, 0.8, s));
//
//     // Shade under the top bulge: the middle-low body dips darker before the
//     // lower glow, which is what makes the surface read as rounded, not flat.
//     float dip = exp(-pow((vy - 0.62) / 0.16, 2.0)) * smoothstep(0.3, 0.9, s);
//     col *= 1.0 - dip * 0.18;
//
//     // Light focused inside the lower edge (caustic) and catching the bottom rim.
//     float lower = smoothstep(0.05, 0.9, g.y);
//     col = mix(col, glowC, exp(-pow((s - 0.22) / 0.10, 2.0)) * lower * 0.70);
//     col = mix(col, glowC, exp(-pow((s - 0.035) / 0.03, 2.0)) * lower * 0.45);
//
//     // Reflections. Broad soft sheen across the upper body (window reflection),
//     // wide soft arcs wrapping both rounded ends, small hot spots, and a fine
//     // edge line. All sized from the radius, so they lengthen but never widen.
//     float upper = smoothstep(-0.10, -0.85, g.y);
//     float sheen = exp(-pow((s - 0.42) / 0.20, 2.0)) * upper * smoothstep(0.0, 0.25, vy) * (1.0 - smoothstep(0.30, 0.55, vy));
//     float arc = exp(-pow((s - 0.17) / 0.10, 2.0));
//     float wrapL = arc * smoothstep(0.30, 0.92, dot(g, normalize(float2(-0.82, -0.57))));
//     float wrapR = arc * smoothstep(0.45, 0.95, dot(g, normalize(float2(0.82, -0.57))));
//     float hotL = exp(-pow((s - 0.20) / 0.06, 2.0)) * smoothstep(0.90, 0.99, dot(g, normalize(float2(-0.72, -0.69))));
//     float hotR = exp(-pow((s - 0.20) / 0.05, 2.0)) * smoothstep(0.93, 0.995, dot(g, normalize(float2(0.72, -0.69))));
//     float edgeLine = exp(-pow((s - 0.05) / 0.03, 2.0)) * upper;
//     // No reflections on the stem: it is thin, so they read as a white sliver.
//     float onStem = smoothstep(0.5 * dp, 4.0 * dp, bodySdf(p) - d);
//     // Glossy streak from the curvature itself: wherever the surface tilts up
//     // toward the light. Follows the dome, wraps the ends, never stretches wide.
//     float3 Hs = normalize(normalize(float3(-0.10, -0.80, 0.59)) + float3(0.0, 0.0, 1.0));
//     float gloss = pow(max(dot(n, Hs), 0.0), 26.0);
//     float topGrad = (1.0 - smoothstep(0.05, 0.48, vy)) * smoothstep(0.0, 0.10, vy) * smoothstep(0.15, 0.6, s);
//     float refl = (1.0 - onStem) * (gloss * 0.55 + topGrad * 0.22 + sheen * 0.30 + wrapL * 0.38 + wrapR * 0.30 + hotL * 0.85 + hotR * 0.70 + edgeLine * 0.40);
//     col = mix(col, float3(1.0), clamp(refl, 0.0, 1.0));
//
//     // Keep the outermost pixel deep on the top half so the silhouette is crisp.
//     col = mix(col, deep, exp(-pow(s / 0.02, 2.0)) * 0.6 * (1.0 - lower));
//     // Stem: same gel as the lower body (lit, not dark), fading in from the join.
//     col = mix(col, mix(base * 0.95, glowC, 0.25), onStem * 0.45);
//     col = clamp(col, 0.0, 1.0);
//
//     float cov = clamp(0.5 - d, 0.0, 1.0);
//     float aIn = cov * mix(0.90, 0.97, z);
//
//     // Outside: contact shadow, tight coloured caustic, and a wide soft halo.
//     float ds = shapeSdf(p - float2(0.0, 2.5 * dp));
//     float shadow = (1.0 - smoothstep(-3.0 * dp, 6.0 * dp, ds)) * 0.13;
//     float dc = shapeSdf(p - float2(0.0, 5.0 * dp));
//     float below = smoothstep(H * 0.35, H, p.y);
//     float caustic = (1.0 - smoothstep(-6.0 * dp, 10.0 * dp, dc)) * 0.55 * below;
//     float dh = shapeSdf(p - float2(0.0, 6.0 * dp));
//     float halo = (1.0 - smoothstep(-2.0 * dp, 12.0 * dp, dh)) * 0.22;   // reaches 0 inside the drawn area
//     float outA = clamp(shadow + caustic + halo, 0.0, 1.0) * (1.0 - cov);
//     float3 outRgb = glowC * (caustic + halo) * (1.0 - cov);
//
//     float3 rgb = col * aIn + outRgb * (1.0 - aIn);
//     return half4(half3(rgb), half(clamp(aIn + outA * (1.0 - aIn), 0.0, 1.0)));
// }
// """

// ---- v4 shader (commit c2ee5ad), kept for reference, no longer used ----
//         private const val AGSL = """uniform float2 size;
// uniform float3 baseColor;
// uniform float dp;
// uniform float sent;
//
// // Coordinates are the view's own: (0,0)-(size). The bubble fills that box except
// // the stem column; glow and shadow are drawn OUTSIDE it (parents don't clip).
//
// float sdRoundBox(float2 p, float2 c, float2 b, float r) {
//     float2 q = abs(p - c) - b + r;
//     return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
// }
//
// float cro(float2 a, float2 b) { return a.x * b.y - a.y * b.x; }
//
// // Tapered capsule (iq): thick where it joins the body, thin at the tip.
// float sdUnevenCapsule(float2 p, float2 pa, float2 pb, float ra, float rb) {
//     p -= pa; pb -= pa;
//     float h = dot(pb, pb);
//     float2 q = float2(dot(p, float2(pb.y, -pb.x)), dot(p, pb)) / h;
//     q.x = abs(q.x);
//     float b = ra - rb;
//     float2 c = float2(sqrt(max(h - b * b, 1e-4)), b);
//     float k = cro(c, q);
//     float m = dot(c, q);
//     float n = dot(q, q);
//     if (k < 0.0) return sqrt(h * n) - ra;
//     if (k > c.x) return sqrt(h * (q.x * q.x + (q.y - 1.0) * (q.y - 1.0))) - rb;
//     return m - ra;
// }
//
// float smin(float a, float b, float k) {
//     float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
//     return mix(b, a, h) - k * h * (1.0 - h);
// }
//
// float radiusFor(float bh) { return min(bh * 0.5, 40.0 * dp); }
//
// float bodySdf(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = 9.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     return sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), radiusFor(H));
// }
//
// float shapeSdf(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = 9.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     float r = radiusFor(H);
//     float d = sdRoundBox(p, float2((x0 + x1) * 0.5, H * 0.5), float2((x1 - x0) * 0.5, H * 0.5), r);
//     // Stem grows out of the lower corner like a drip of the same gel.
//     float sr = min(r * 0.55, 11.0 * dp);
//     float stem;
//     if (sent > 0.5) {
//         stem = sdUnevenCapsule(p, float2(x1 - sr * 1.1, H - sr * 1.05), float2(W - 2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
//     } else {
//         stem = sdUnevenCapsule(p, float2(x0 + sr * 1.1, H - sr * 1.05), float2(2.6 * dp, H - 2.4 * dp), sr, 2.4 * dp);
//     }
//     return smin(d, stem, 9.0 * dp);
// }
//
// half4 main(float2 p) {
//     float W = size.x; float H = size.y;
//     float R = radiusFor(H);
//
//     float d = shapeSdf(p);
//     float e = 1.0 * dp;
//     float2 g = float2(shapeSdf(p + float2(e, 0.0)) - shapeSdf(p - float2(e, 0.0)),
//                       shapeSdf(p + float2(0.0, e)) - shapeSdf(p - float2(0.0, e)));
//     g = g / (length(g) + 1e-5);
//
//     // Inflated cross-section over the full radius: the whole body is curved.
//     float s = clamp(-d / R, 0.0, 1.0);
//     float z = sqrt(max(1.0 - (1.0 - s) * (1.0 - s), 1e-4));
//     float slope = (1.0 - s) / z;
//     float3 n = normalize(float3(g * slope, 1.0));
//
//     float3 base = clamp(baseColor, 0.02, 1.0);
//     float3 absorb = -log(base);
//     float3 glowC = clamp(base * 1.35 + float3(0.18), 0.0, 1.0);
//
//     // Body: deep saturated rim, luminous core (hue held, not drifting).
//     float3 deep = mix(exp(-absorb * 2.6), base * 0.62, 0.45);
//     float3 core = exp(-absorb * 0.75);
//     float3 col = mix(deep, core, smoothstep(0.0, 0.85, s));
//
//     // Volume: soft diffuse from an overhead light across the whole dome,
//     // so the middle reads as rounded, darker under the top, brighter low down.
//     float3 L = normalize(float3(-0.25, -0.75, 0.62));
//     float lam = dot(n, L);
//     col *= mix(0.80, 1.08, smoothstep(-0.2, 0.9, lam));
//     float vy = clamp(p.y / H, 0.0, 1.0);
//     col = mix(col, glowC, smoothstep(0.45, 1.0, vy) * 0.38 * smoothstep(0.1, 0.8, s));
//
//     // Light focused inside the lower edge (caustic) and catching the bottom rim.
//     float lower = smoothstep(0.05, 0.9, g.y);
//     col = mix(col, glowC, exp(-pow((s - 0.22) / 0.10, 2.0)) * lower * 0.70);
//     col = mix(col, glowC, exp(-pow((s - 0.035) / 0.03, 2.0)) * lower * 0.45);
//
//     // Reflections. Broad soft sheen across the upper body (window reflection),
//     // wide soft arcs wrapping both rounded ends, small hot spots, and a fine
//     // edge line. All sized from the radius, so they lengthen but never widen.
//     float upper = smoothstep(-0.10, -0.85, g.y);
//     float sheen = exp(-pow((s - 0.42) / 0.20, 2.0)) * upper * smoothstep(0.0, 0.25, vy) * (1.0 - smoothstep(0.30, 0.55, vy));
//     float arc = exp(-pow((s - 0.17) / 0.10, 2.0));
//     float wrapL = arc * smoothstep(0.30, 0.92, dot(g, normalize(float2(-0.82, -0.57))));
//     float wrapR = arc * smoothstep(0.45, 0.95, dot(g, normalize(float2(0.82, -0.57))));
//     float hotL = exp(-pow((s - 0.20) / 0.06, 2.0)) * smoothstep(0.90, 0.99, dot(g, normalize(float2(-0.72, -0.69))));
//     float hotR = exp(-pow((s - 0.20) / 0.05, 2.0)) * smoothstep(0.93, 0.995, dot(g, normalize(float2(0.72, -0.69))));
//     float edgeLine = exp(-pow((s - 0.05) / 0.03, 2.0)) * upper;
//     // No reflections on the stem: it is thin, so they read as a white sliver.
//     float onStem = smoothstep(0.5 * dp, 4.0 * dp, bodySdf(p) - d);
//     float refl = (1.0 - onStem) * (sheen * 0.30 + wrapL * 0.38 + wrapR * 0.30 + hotL * 0.85 + hotR * 0.70 + edgeLine * 0.40);
//     col = mix(col, float3(1.0), clamp(refl, 0.0, 1.0));
//
//     // Keep the outermost pixel deep on the top half so the silhouette is crisp.
//     col = mix(col, deep, exp(-pow(s / 0.02, 2.0)) * 0.6 * (1.0 - lower));
//     // Stem: same gel as the lower body (lit, not dark), fading in from the join.
//     col = mix(col, mix(base * 0.95, glowC, 0.25), onStem * 0.45);
//     col = clamp(col, 0.0, 1.0);
//
//     float cov = clamp(0.5 - d, 0.0, 1.0);
//     float aIn = cov * mix(0.90, 0.97, z);
//
//     // Outside: contact shadow, tight coloured caustic, and a wide soft halo.
//     float ds = shapeSdf(p - float2(0.0, 2.5 * dp));
//     float shadow = (1.0 - smoothstep(-3.0 * dp, 6.0 * dp, ds)) * 0.13;
//     float dc = shapeSdf(p - float2(0.0, 5.0 * dp));
//     float below = smoothstep(H * 0.35, H, p.y);
//     float caustic = (1.0 - smoothstep(-6.0 * dp, 10.0 * dp, dc)) * 0.55 * below;
//     float dh = shapeSdf(p - float2(0.0, 6.0 * dp));
//     float halo = (1.0 - smoothstep(-2.0 * dp, 12.0 * dp, dh)) * 0.22;   // reaches 0 inside the drawn area
//     float outA = clamp(shadow + caustic + halo, 0.0, 1.0) * (1.0 - cov);
//     float3 outRgb = glowC * (caustic + halo) * (1.0 - cov);
//
//     float3 rgb = col * aIn + outRgb * (1.0 - aIn);
//     return half4(half3(rgb), half(clamp(aIn + outA * (1.0 - aIn), 0.0, 1.0)));
// }
// """

// ---- v3 shader (commit 6c19b0c), kept for reference, no longer used ----
//         private const val AGSL = """uniform float2 size;
// uniform float3 baseColor;
// uniform float dp;
// uniform float sent;
//
// float sdRoundBox(float2 p, float2 c, float2 b, float r) {
//     float2 q = abs(p - c) - b + r;
//     return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
// }
//
// float sdTri(float2 p, float2 a, float2 b, float2 c) {
//     float2 e0 = b - a; float2 e1 = c - b; float2 e2 = a - c;
//     float2 v0 = p - a; float2 v1 = p - b; float2 v2 = p - c;
//     float2 q0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
//     float2 q1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
//     float2 q2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
//     float s = sign(e0.x * e2.y - e0.y * e2.x);
//     float2 d = min(min(float2(dot(q0, q0), s * (v0.x * e0.y - v0.y * e0.x)),
//                        float2(dot(q1, q1), s * (v1.x * e1.y - v1.y * e1.x))),
//                        float2(dot(q2, q2), s * (v2.x * e2.y - v2.y * e2.x)));
//     return -sqrt(d.x) * sign(d.y);
// }
//
// float cro(float2 a, float2 b) { return a.x * b.y - a.y * b.x; }
//
// // Tapered capsule (iq): rounded stem, thick at the body, thin at the tip.
// float sdUnevenCapsule(float2 p, float2 pa, float2 pb, float ra, float rb) {
//     p -= pa; pb -= pa;
//     float h = dot(pb, pb);
//     float2 q = float2(dot(p, float2(pb.y, -pb.x)), dot(p, pb)) / h;
//     q.x = abs(q.x);
//     float b = ra - rb;
//     float2 c = float2(sqrt(max(h - b * b, 1e-4)), b);
//     float k = cro(c, q);
//     float m = dot(c, q);
//     float n = dot(q, q);
//     if (k < 0.0) return sqrt(h * n) - ra;
//     if (k > c.x) return sqrt(h * (q.x * q.x + (q.y - 1.0) * (q.y - 1.0))) - rb;
//     return m - ra;
// }
//
// float smin(float a, float b, float k) {
//     float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
//     return mix(b, a, h) - k * h * (1.0 - h);
// }
//
// // Layout shared by every function: margins are fixed in dp.
// float tailW() { return 10.0 * dp; }
// float bodyBottom() { return size.y - 10.0 * dp; }
// float bodyTop() { return 2.0 * dp; }
//
// float shapeSdf(float2 p) {
//     float W = size.x;
//     float t = tailW();
//     float y0 = bodyTop(); float y1 = bodyBottom();
//     float x0 = sent > 0.5 ? 1.0 * dp : t;
//     float x1 = sent > 0.5 ? W - t : W - 1.0 * dp;
//     float bh = y1 - y0;
//     float r = min(26.0 * dp, bh * 0.5);
//     float d = sdRoundBox(p, float2((x0 + x1) * 0.5, (y0 + y1) * 0.5), float2((x1 - x0) * 0.5, bh * 0.5), r);
//     float stem;
//     if (sent > 0.5) {
//         stem = sdUnevenCapsule(p, float2(x1 - 9.0 * dp, y1 - 9.0 * dp), float2(W - 3.5 * dp, y1 - 1.0 * dp), 8.0 * dp, 2.2 * dp);
//     } else {
//         stem = sdUnevenCapsule(p, float2(x0 + 9.0 * dp, y1 - 9.0 * dp), float2(3.5 * dp, y1 - 1.0 * dp), 8.0 * dp, 2.2 * dp);
//     }
//     return smin(d, stem, 6.0 * dp);
// }
//
// half4 main(float2 p) {
//     float W = size.x; float H = size.y;
//     float y0 = bodyTop(); float y1 = bodyBottom();
//     float bh = y1 - y0;
//     float R = min(26.0 * dp, bh * 0.5);
//
//     float d = shapeSdf(p);
//     float e = 1.0 * dp;
//     float2 g = float2(shapeSdf(p + float2(e, 0.0)) - shapeSdf(p - float2(e, 0.0)),
//                       shapeSdf(p + float2(0.0, e)) - shapeSdf(p - float2(0.0, e)));
//     g = g / (length(g) + 1e-5);
//
//     // Inflated pill: round cross-section across the whole bevel.
//     float s = clamp(-d / R, 0.0, 1.0);
//     float z = sqrt(max(1.0 - (1.0 - s) * (1.0 - s), 1e-4));
//     float slope = (1.0 - s) / z;
//     float3 n = normalize(float3(g * slope, 1.0));
//
//     float3 base = clamp(baseColor, 0.02, 1.0);
//     float3 light = mix(base, float3(1.0), 0.55);
//     float3 glowC = clamp(base * 1.35 + float3(0.18), 0.0, 1.0);   // saturated, luminous
//
//     // Zone shading by distance from the edge (s: 0 at edge, 1 inside the bevel).
//     // Edges run deep and saturated, the centre glows (Beer-Lambert style).
//     float3 absorb = -log(base);
//     // Absorption alone drifts pink toward crimson and blue toward indigo, so the
//     // deep tone is pulled back toward the picked colour to keep its hue.
//     float3 deep = mix(exp(-absorb * 2.6), base * 0.62, 0.45);
//     float3 col = mix(deep, exp(-absorb * 0.80), smoothstep(0.0, 1.0, s));
//
//     // Light enters from above, exits low: lower body brightens.
//     float vy = clamp((p.y - y0) / bh, 0.0, 1.0);
//     col = mix(col, glowC, smoothstep(0.45, 1.0, vy) * 0.30 * s);
//
//     // Internal caustic: a glowing band set in from the lower edge.
//     float lower = smoothstep(0.05, 0.9, g.y);
//     float caus = exp(-pow((s - 0.22) / 0.09, 2.0)) * lower;
//     col = mix(col, glowC, caus * 0.65);
//     // Transmitted light catching the very bottom rim.
//     float rimB = exp(-pow((s - 0.035) / 0.03, 2.0)) * lower;
//     col = mix(col, glowC, rimB * 0.40);
//
//     // Top: thin specular line riding the edge, crescents on the rounded ends,
//     // and a faint broad glare. Widths are in dp, so nothing widens when stretched.
//     float upper = smoothstep(-0.25, -0.95, g.y);
//     float topLine = exp(-pow((s - 0.07) / 0.035, 2.0)) * upper;
//     float arcBand = exp(-pow((s - 0.13) / 0.06, 2.0));
//     float crescL = arcBand * smoothstep(0.55, 0.95, dot(g, normalize(float2(-0.80, -0.60))));
//     float crescR = arcBand * smoothstep(0.70, 0.97, dot(g, normalize(float2(0.80, -0.60))));
//     float glare = exp(-pow((s - 0.45) / 0.25, 2.0)) * upper * 0.12;
//     col = mix(col, float3(1.0), clamp(topLine * 0.70 + crescL * 0.95 + crescR * 0.75 + glare, 0.0, 1.0));
//     // Keep the outermost pixel deep so the silhouette reads crisp.
//     col = mix(col, deep, exp(-pow(s / 0.02, 2.0)) * 0.6 * (1.0 - lower));
//     col = clamp(col, 0.0, 1.0);
//
//     float cov = clamp(0.5 - d, 0.0, 1.0);
//     float aIn = cov * mix(0.90, 0.97, z);
//
//     // Outside: soft contact shadow + coloured light cast through the gel.
//     float ds = shapeSdf(p - float2(0.0, 2.0 * dp));
//     float shadow = exp(-max(ds, 0.0) / (2.0 * dp)) * 0.14;
//     float dc = shapeSdf(p - float2(0.0, 5.0 * dp));
//     float caustic = exp(-max(dc, 0.0) / (3.6 * dp)) * 0.85 * smoothstep(y0 + bh * 0.5, y1, p.y);
//     float fade = smoothstep(0.0, 2.5 * dp, min(min(p.x, W - p.x), min(p.y, H - p.y))) * (1.0 - cov);
//     shadow *= fade; caustic *= fade;
//     float3 cc = glowC;
//     float aOut = clamp(shadow + caustic, 0.0, 1.0);
//     float3 outRgb = cc * caustic;   // shadow is black, contributes alpha only
//
//     float3 rgb = col * aIn + outRgb * (1.0 - aIn);
//     return half4(half3(rgb), half(clamp(aIn + aOut * (1.0 - aIn), 0.0, 1.0)));
// }
// """

// ---- v2 shader (commit 77211fe), kept for reference, no longer used ----
//         private const val AGSL = """
// uniform float2 size;
// uniform float3 baseColor;
// uniform float dp;
// uniform float sent;
//
// float sdRoundBox(float2 p, float2 c, float2 b, float r) {
//     float2 q = abs(p - c) - b + r;
//     return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
// }
//
// float sdTri(float2 p, float2 a, float2 b, float2 c) {
//     float2 e0 = b - a; float2 e1 = c - b; float2 e2 = a - c;
//     float2 v0 = p - a; float2 v1 = p - b; float2 v2 = p - c;
//     float2 q0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
//     float2 q1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
//     float2 q2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
//     float s = sign(e0.x * e2.y - e0.y * e2.x);
//     float2 d = min(min(float2(dot(q0, q0), s * (v0.x * e0.y - v0.y * e0.x)),
//                        float2(dot(q1, q1), s * (v1.x * e1.y - v1.y * e1.x))),
//                        float2(dot(q2, q2), s * (v2.x * e2.y - v2.y * e2.x)));
//     return -sqrt(d.x) * sign(d.y);
// }
//
// float smin(float a, float b, float k) {
//     float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
//     return mix(b, a, h) - k * h * (1.0 - h);
// }
//
// float shapeSdf(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = min(10.0 * dp, W * 0.15);
//     float y1 = H - 4.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     float r = min(20.0 * dp, y1 * 0.5);
//     float d = sdRoundBox(p, float2((x0 + x1) * 0.5, y1 * 0.5), float2((x1 - x0) * 0.5, y1 * 0.5), r);
//     float tri;
//     if (sent > 0.5) {
//         tri = sdTri(p, float2(x1 - r * 0.9, y1 - r * 1.1), float2(x1 - r * 0.9, y1), float2(W - dp, y1));
//     } else {
//         tri = sdTri(p, float2(x0 + r * 0.9, y1 - r * 1.1), float2(dp, y1), float2(x0 + r * 0.9, y1));
//     }
//     return smin(d, tri - 1.2 * dp, r * 0.75);
// }
//
// half4 main(float2 p) {
//     float W = size.x; float H = size.y;
//     float t = min(10.0 * dp, W * 0.15);
//     float y1 = H - 4.0 * dp;
//     float x0 = sent > 0.5 ? 0.0 : t;
//     float x1 = sent > 0.5 ? W - t : W;
//     float bh = y1;
//     float r = min(20.0 * dp, bh * 0.5);
//     float R = r * 0.95;
//
//     float d = shapeSdf(p);
//     float e = 1.2 * dp;
//     float2 g = float2(shapeSdf(p + float2(e, 0.0)) - shapeSdf(p - float2(e, 0.0)),
//                       shapeSdf(p + float2(0.0, e)) - shapeSdf(p - float2(0.0, e)));
//     g = g / (length(g) + 1e-5);
//
//     // Inflated cross-section: circular dome over the bevel width, flat beyond it.
//     float s = clamp(-d / R, 0.0, 1.0);
//     float z = sqrt(max(1.0 - (1.0 - s) * (1.0 - s), 1e-4));
//     float slope = (1.0 - s) / z;
//     float3 n = normalize(float3(g * slope, 1.0));
//
//     float3 base = baseColor;
//     float lum = dot(base, float3(0.299, 0.587, 0.114));
//     float3 deep = clamp(mix(float3(lum), base, 1.35) * 0.62, 0.0, 1.0);
//     float3 light = mix(base, float3(1.0), 0.45);
//     float3 col = mix(deep, base * 1.04, smoothstep(0.0, 1.0, z));
//
//     // Transmitted light pooling along the lower inside edge.
//     float pool = smoothstep(0.05, 0.85, clamp(n.y, 0.0, 1.0)) * pow(max(1.0 - z, 0.0), 0.6);
//     col = mix(col, light, pool * 0.75);
//
//     // Broad inner glow low in the body.
//     float iy = (p.y - (y1 - bh * 0.28)) / (bh * 0.30);
//     col += (light - base) * exp(-iy * iy) * z * 0.45;
//
//     // Top highlight: inset box with fixed pixel margins, so its ends never stretch.
//     float hx0 = x0 + R * 0.32; float hx1 = x1 - R * 0.32;
//     float hy0 = R * 0.10; float hy1 = bh * 0.56;
//     float hd = sdRoundBox(p, float2((hx0 + hx1) * 0.5, (hy0 + hy1) * 0.5),
//                           float2((hx1 - hx0) * 0.5, (hy1 - hy0) * 0.5), min(R * 0.85, (hy1 - hy0) * 0.5));
//     float hmask = smoothstep(2.5 * dp, -7.0 * dp, hd);
//     float vfade = clamp(1.0 - (p.y - hy0) / (hy1 - hy0), 0.0, 1.0);
//     col = mix(col, float3(1.0), hmask * (0.05 + 0.72 * pow(vfade, 2.2)));
//
//     // Sharp specular rim.
//     float3 Hh = normalize(normalize(float3(-0.35, -0.75, 0.56)) + float3(0.0, 0.0, 1.0));
//     col += pow(max(dot(n, Hh), 0.0), 40.0) * 0.55;
//     col = clamp(col, 0.0, 1.0);
//
//     float cov = clamp(0.5 - d, 0.0, 1.0);
//     float aIn = cov * mix(0.88, 0.97, z);
//
//     // Soft coloured glow under the bubble, faded out before the view edge.
//     float dg = shapeSdf(p - float2(0.0, 1.5 * dp));
//     float edge = smoothstep(0.0, 2.5 * dp, min(min(p.x, W - p.x), min(p.y, H - p.y)));
//     float glow = exp(-max(dg, 0.0) / (1.8 * dp)) * 0.40 * (1.0 - cov) * edge;
//
//     float3 rgb = col * aIn + base * 0.9 * glow;
//     return half4(half3(rgb), half(clamp(aIn + glow, 0.0, 1.0)));
// }
// """

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
