package org.fossify.messages.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import org.fossify.commons.extensions.getNameLetter
import org.fossify.commons.extensions.rotateHue
import kotlin.math.min

/** Keep user-selected hues, but give the gel finish a vivid, luminous body. */
fun Context.getGelBubbleEffectiveColor(baseColor: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(baseColor.rotateHue(config.gelBubbleHueShift), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.78f)
    hsv[2] = hsv[2].coerceAtLeast(0.96f)
    return Color.HSVToColor(hsv)
}

private fun mix(color: Int, other: Int, amount: Float) =
    ColorUtils.blendARGB(color, other, amount)

private fun gelColors(color: Int) = intArrayOf(
    mix(color, Color.WHITE, 0.18f),
    color,
    mix(color, Color.BLACK, 0.12f),
    color,
    mix(color, Color.BLACK, 0.24f)
)

/** Choose the text with the best worst-case contrast across the body gradient. */
fun Context.getGelBubbleTextColor(baseColor: Int): Int {
    val colors = gelColors(getGelBubbleEffectiveColor(baseColor))
    fun contrast(text: Int) = colors.minOf { ColorUtils.calculateContrast(text, it) }
    return if (contrast(Color.BLACK) >= contrast(Color.WHITE)) Color.BLACK else Color.WHITE
}

fun Context.createGelBubbleDrawable(baseColor: Int, isSent: Boolean): Drawable =
    GelDrawable(getGelBubbleEffectiveColor(baseColor), resources.displayMetrics.density, isSent)

/** Render the same gel material behind the existing contact initial. Photos stay photos. */
fun Context.createGelContactIcon(name: String, original: Bitmap): Bitmap {
    val size = original.width
    val base = original.getPixel(size / 2, (original.height / 8).coerceAtLeast(1))
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    GelDrawable(getGelBubbleEffectiveColor(base), resources.displayMetrics.density, null).apply {
        setBounds(0, 0, size, size)
        draw(canvas)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = getGelBubbleTextColor(base)
        textSize = size * 0.5f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    canvas.drawText(name.getNameLetter(), size / 2f,
        size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    return bitmap
}

/**
 * Resolution-independent soft gel. Reflections follow the surface rather than
 * floating as a fixed oval over the text. All drawing is clipped to the silhouette.
 */
private class GelDrawable(
    private val color: Int,
    private val density: Float,
    private val sent: Boolean?
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val rect = RectF()
    private var opacity = 255
    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val edge = min(density * 0.8f, min(w, h) * 0.02f)
        rect.set(edge, edge, w - edge, h - edge)
        shape.reset()
        if (sent == null) {
            shape.addOval(rect, Path.Direction.CW)
        } else {
            val radius = min(22f * density, h / 2f)
            val tail = min(7f * density, radius)
            val radii = floatArrayOf(radius, radius, radius, radius,
                if (sent) tail else radius, if (sent) tail else radius,
                if (sent) radius else tail, if (sent) radius else tail)
            shape.addRoundRect(rect, radii, Path.Direction.CW)
        }
        canvas.clipPath(shape)
        paint.alpha = opacity
        paint.colorFilter = filter
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h, gelColors(color),
            floatArrayOf(0f, 0.22f, 0.56f, 0.82f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)

        // A broad internal glow gives the material depth without a gray wash.
        paint.shader = RadialGradient(w * 0.52f, h * 0.8f, maxOf(w * 0.55f, h),
            intArrayOf(Color.argb(65, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)

        // Curved upper reflection; shallow on balloons, fuller on round avatars.
        val reflection = Path()
        val top = if (sent == null) h * 0.1f else density * 2f
        val depth = if (sent == null) h * 0.38f else min(h * 0.23f, density * 10f)
        reflection.moveTo(w * 0.08f, top + depth * 0.45f)
        reflection.cubicTo(w * 0.18f, top - depth * 0.2f,
            w * 0.78f, top - depth * 0.2f, w * 0.92f, top + depth * 0.45f)
        reflection.cubicTo(w * 0.8f, top + depth * 1.05f,
            w * 0.22f, top + depth * 1.05f, w * 0.08f, top + depth * 0.45f)
        reflection.close()
        paint.shader = LinearGradient(0f, top, 0f, top + depth,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(12, 255, 255, 255)),
            null, Shader.TileMode.CLAMP)
        canvas.drawPath(reflection, paint)

        // Fine colored edge, with a separate reflected light along the lower lip.
        paint.shader = null
        paint.color = mix(color, Color.BLACK, 0.3f)
        paint.alpha = opacity
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = edge * 2f
        canvas.drawPath(shape, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.argb(180, 255, 255, 255), Color.TRANSPARENT,
                Color.argb(120, 255, 255, 255)), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP)
        paint.strokeWidth = edge
        canvas.drawPath(shape, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
