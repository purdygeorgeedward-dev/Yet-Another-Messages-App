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

/** The selected hue remains shared by the conversation and the Settings preview. */
fun Context.getGelBubbleEffectiveColor(baseColor: Int): Int =
    baseColor.rotateHue(config.gelBubbleHueShift) or (0xFF shl 24)

private fun mix(color: Int, other: Int, amount: Float) =
    ColorUtils.blendARGB(color, other, amount)

fun Context.getGelBubbleTextColor(baseColor: Int): Int {
    val body = mix(getGelBubbleEffectiveColor(baseColor), Color.BLACK, 0.20f)
    return if (ColorUtils.calculateContrast(Color.WHITE, body) >= 4.5) Color.WHITE else Color.BLACK
}

fun Context.createGelBubbleDrawable(baseColor: Int, isSent: Boolean): Drawable =
    GlassGelDrawable(getGelBubbleEffectiveColor(baseColor), resources.displayMetrics.density)

fun Context.createGelSurfaceDrawable(baseColor: Int, pale: Boolean = false, round: Boolean = false, inset: Boolean = false): Drawable =
    GlassGelDrawable(baseColor, resources.displayMetrics.density, pale, round, inset)

/** Letter placeholders share the bubble material. Real contact photos remain unchanged. */
fun Context.createGelContactIcon(name: String, original: Bitmap): Bitmap {
    // Supersample the small clipped reflections to avoid jagged avatar edges.
    val size = original.width * 2
    val hsv = FloatArray(3)
    Color.colorToHSV(getGelBubbleEffectiveColor(original.getPixel(original.width / 2, (original.height / 8).coerceAtLeast(1))), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.90f)
    hsv[2] = hsv[2].coerceAtLeast(0.96f)
    val color = Color.HSVToColor(hsv)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    GlassGelDrawable(color, resources.displayMetrics.density * 2f, round = true).apply {
        setBounds(0, 0, size, size)
        draw(canvas)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = if (ColorUtils.calculateContrast(Color.WHITE, mix(color, Color.BLACK, 0.2f)) >= 4.5) Color.WHITE else Color.BLACK
        textSize = size * 0.46f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setShadowLayer(size * 0.025f, 0f, size * 0.015f, 0x70002550)
    }
    canvas.drawText(name.getNameLetter(), size / 2f,
        size / 2f - (paint.ascent() + paint.descent()) / 2f - size * 0.02f, paint)
    return bitmap
}

/**
 * Layered glass material: colored shadow, rounded body, edge absorption,
 * inner bevel, curved upper reflection, side glints and a lower reflected lip.
 * All reflections scale with the bounds; no bitmap backgrounds or fixed blobs.
 */
private class GlassGelDrawable(
    private val color: Int,
    private val density: Float,
    private val pale: Boolean = false,
    private val round: Boolean = false,
    private val inset: Boolean = false
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var drawableAlpha = 255
    private var drawableFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val margin = min(4f * density, min(w, h) * 0.065f)
        val rect = RectF(margin, margin * 0.55f, w - margin, h - margin * 1.65f)
        val radius = if (round) min(rect.width(), rect.height()) / 2f else min(32f * density, rect.height() / 2f)
        fun outline(r: RectF) {
            path.reset()
            if (round) path.addOval(r, Path.Direction.CW)
            else path.addRoundRect(r, radius, radius, Path.Direction.CW)
        }
        fun gradient(colors: IntArray, positions: FloatArray? = null) {
            paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, colors, positions, Shader.TileMode.CLAMP)
        }
        fun fill() {
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }
        paint.colorFilter = drawableFilter
        paint.alpha = drawableAlpha

        // Several translucent contours produce a soft colored shadow without
        // requiring a software layer on every scrolling message view.
        val shadow = RectF(rect).apply { offset(0f, margin * 0.75f) }
        outline(shadow)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        for (spread in 5 downTo 1) {
            paint.strokeWidth = margin * spread * 0.38f
            paint.color = ColorUtils.setAlphaComponent(color, (9 * drawableAlpha / 255))
            canvas.drawPath(path, paint)
        }
        outline(rect)
        paint.alpha = drawableAlpha
        gradient(if (pale) intArrayOf(0xFFF9FEFF.toInt(), mix(color, Color.WHITE, 0.42f),
            mix(color, Color.WHITE, 0.65f), mix(color, Color.WHITE, 0.22f))
        else intArrayOf(mix(color, Color.BLACK, 0.23f), mix(color, Color.WHITE, 0.12f),
            color, mix(color, Color.BLACK, 0.22f), mix(color, Color.BLACK, 0.38f)),
            if (pale) null else floatArrayOf(0f, 0.12f, 0.35f, 0.76f, 1f))
        fill()

        val clipped = canvas.save()
        canvas.clipPath(path)
        // Bright interior with darker curved edges, like light through colored gel.
        paint.shader = RadialGradient(w * 0.48f, rect.centerY(), maxOf(rect.width() * 0.57f, rect.height() * 0.72f),
            intArrayOf(Color.argb(if (pale) 15 else 5, 0, 15, 55),
                Color.argb(if (pale) 15 else 90, 0, 15, 55)),
            floatArrayOf(0.60f, 1f), Shader.TileMode.CLAMP)
        fill()

        val top = rect.top + rect.height() * 0.035f
        val depth = if (round) rect.height() * 0.36f else min(rect.height() * 0.34f, 20f * density)
        val reflection = Path().apply {
            moveTo(rect.left + rect.width() * 0.07f, top + depth * 0.52f)
            cubicTo(rect.left + rect.width() * 0.09f, top - depth * 0.12f,
                rect.right - rect.width() * 0.09f, top - depth * 0.12f,
                rect.right - rect.width() * 0.07f, top + depth * 0.52f)
            cubicTo(rect.right - rect.width() * 0.08f, top + depth * 1.18f,
                rect.left + rect.width() * 0.08f, top + depth * 1.18f,
                rect.left + rect.width() * 0.07f, top + depth * 0.52f)
            close()
        }
        paint.shader = LinearGradient(0f, top, 0f, top + depth * 1.1f,
            intArrayOf(Color.argb(250, 255, 255, 255), Color.argb(125, 230, 250, 255),
                Color.argb(12, 255, 255, 255)), floatArrayOf(0f, 0.57f, 1f), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawPath(reflection, paint)

        // Small vertical glints hug both ends instead of covering the lettering.
        for (right in listOf(false, true)) {
            val x = if (right) rect.right - margin * 0.65f else rect.left + margin * 0.65f
            val glint = RectF(x - margin * 0.38f, rect.top + rect.height() * 0.37f,
                x + margin * 0.38f, rect.top + rect.height() * 0.70f)
            paint.shader = LinearGradient(0f, glint.top, 0f, glint.bottom,
                intArrayOf(Color.TRANSPARENT, Color.argb(230, 225, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            canvas.drawOval(glint, paint)
        }
        canvas.restoreToCount(clipped)

        // Outer colored rim and inset light bevel give the shape a glass wall.
        outline(rect)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = min(1.1f * density, margin * 0.55f)
        gradient(if (pale) intArrayOf(mix(color, Color.WHITE, 0.3f), Color.WHITE, mix(color, Color.WHITE, 0.15f))
            else intArrayOf(mix(color, Color.BLACK, 0.3f), mix(color, Color.BLACK, 0.55f), mix(color, Color.BLACK, 0.20f)))
        canvas.drawPath(path, paint)
        val inner = RectF(rect).apply { inset(margin * 0.7f, margin * 0.7f) }
        outline(inner)
        paint.strokeWidth = min(1.25f * density, margin * 0.6f)
        gradient(intArrayOf(Color.argb(if (inset) 90 else 245, 255, 255, 255),
            Color.argb(25, 255, 255, 255), Color.argb(180, 170, 240, 255)),
            floatArrayOf(0f, 0.53f, 1f))
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { drawableFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
