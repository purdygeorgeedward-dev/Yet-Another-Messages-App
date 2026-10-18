package org.fossify.messages.extensions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import org.fossify.commons.extensions.rotateHue
import kotlin.math.min

fun Context.getGelBubbleEffectiveColor(baseColor: Int): Int =
    baseColor.rotateHue(config.gelBubbleHueShift)

/**
 * Size-independent translucent gel material. Geometry and tail are a single clipped path.
 * Android 13+ uses AGSL for curved lighting; older versions use vector gradients.
 */
fun Context.createGelBubbleDrawable(baseColor: Int, isSent: Boolean): Drawable =
    GelBubbleDrawable(getGelBubbleEffectiveColor(baseColor), isSent, resources.displayMetrics.density)

private class GelBubbleDrawable(
    private val color: Int,
    private val sent: Boolean,
    private val density: Float
) : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val rect = RectF()
    private var shader: android.graphics.RuntimeShader? = null
    private var shaderFailed = false

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val tail = min(9f * density, w * .12f)
        val radius = min(min(19f * density, h * .47f), (w - tail) * .40f)
        val left = if (sent) 0f else tail
        val right = if (sent) w - tail else w
        rect.set(left, 0f, right, h)
        outline.reset()
        outline.addRoundRect(rect, radius, radius, Path.Direction.CW)
        // Continuous curved tail, joined to the bottom of the body.
        val stem = Path()
        if (sent) {
            stem.moveTo(right - radius * .55f, h - 1f)
            stem.quadTo(right + tail * .20f, h - 1f, w, h - 1f)
            stem.quadTo(right + tail * .22f, h - radius * .52f, right - radius * .12f, h - radius * .95f)
        } else {
            stem.moveTo(left + radius * .55f, h - 1f)
            stem.quadTo(left - tail * .20f, h - 1f, 0f, h - 1f)
            stem.quadTo(left - tail * .22f, h - radius * .52f, left + radius * .12f, h - radius * .95f)
        }
        stem.close()
        outline.addPath(stem)

        // The translucent shadow is restrained so adjacent messages remain readable.
        body.reset()
        body.isAntiAlias = true
        body.color = Color.argb(35, Color.red(color), Color.green(color), Color.blue(color))
        body.setShadowLayer(3f * density, 0f, 1.5f * density, Color.argb(55, 0, 0, 0))
        canvas.drawPath(outline, body)
        body.clearShadowLayer()

        canvas.save()
        canvas.clipPath(outline)
        if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
            try {
                if (shader == null) shader = android.graphics.RuntimeShader(AGSL)
                val s = requireNotNull(shader)
                s.setFloatUniform("size", w, h)
                s.setFloatUniform("baseColor", Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
                body.shader = s
                canvas.drawRect(0f, 0f, w, h, body)
                body.shader = null
            } catch (_: Exception) {
                shaderFailed = true
                drawFallback(canvas, w, h)
            }
        } else {
            drawFallback(canvas, w, h)
        }

        // Narrow rim light follows the true bubble silhouette, including its tail.
        detail.reset()
        detail.isAntiAlias = true
        detail.style = Paint.Style.STROKE
        detail.strokeWidth = 1.25f * density
        detail.color = Color.argb(75, 255, 255, 255)
        canvas.drawPath(outline, detail)
        canvas.restore()
        canvas.restoreToCount(saved)
    }

    private fun drawFallback(canvas: Canvas, w: Float, h: Float) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        body.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.rgb(mix(r,255,.32f), mix(g,255,.32f), mix(b,255,.32f)),
                color, Color.rgb(mix(r,0,.24f), mix(g,0,.24f), mix(b,0,.24f))),
            floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, body)
        body.shader = RadialGradient(w * .38f, h * .03f, maxOf(w * .66f, h * .50f),
            intArrayOf(Color.argb(95,255,255,255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, body)
        body.shader = null
    }

    private fun mix(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0,255)
    override fun setAlpha(alpha: Int) { body.alpha = alpha; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { body.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        // A gently inflated cross-section with diffuse transmitted light,
        // rim darkening and broad specular reflections (no painted oval).
        private const val AGSL = """
uniform float2 size;
uniform float3 baseColor;
half4 main(float2 p) {
    float2 uv = p / max(size, float2(1.0));
    float edge = min(min(uv.x, 1.0-uv.x), min(uv.y, 1.0-uv.y));
    float rim = 1.0 - smoothstep(0.0, 0.16, edge);
    float dome = sqrt(max(0.0, 1.0 - pow(2.0*uv.y-1.0, 2.0)));
    float topLight = exp(-pow((uv.y-0.08)/0.23, 2.0));
    float reflection = exp(-pow((uv.x-0.28)/0.46, 2.0) - pow((uv.y-0.055)/0.12, 2.0));
    float lowerBounce = exp(-pow((uv.y-0.94)/0.10, 2.0));
    float3 c = baseColor * (0.77 + 0.24*dome + 0.12*topLight - 0.22*rim);
    c = mix(c, float3(1.0), 0.24*reflection + 0.10*lowerBounce);
    c += 0.08*pow(max(0.0, 1.0-uv.x), 2.0);
    return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
}
"""
    }
}
