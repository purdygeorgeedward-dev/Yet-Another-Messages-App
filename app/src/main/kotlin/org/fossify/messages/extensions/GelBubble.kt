package org.fossify.messages.extensions

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import org.fossify.commons.extensions.darkenColor
import org.fossify.commons.extensions.lightenColor
import org.fossify.commons.extensions.rotateHue

/**
 * The color a gel bubble actually renders with, after the configured hue
 * shift - callers need this (not the raw base color) when computing text
 * contrast color, since computing contrast from the pre-shift color while
 * the bubble itself renders the shifted one risks a readability mismatch:
 * hue rotation preserves saturation/value exactly, but human-perceived
 * brightness isn't purely a function of V (yellow reads brighter than blue
 * at the same V), so a shift can move perceived brightness enough to matter
 * even though it looks like a small, safe change on paper.
 */
fun Context.getGelBubbleEffectiveColor(baseColor: Int): Int = baseColor.rotateHue(config.gelBubbleHueShift)

/**
 * Builds a glossy "gel" bubble drawable from a single base color - a
 * top-to-bottom gradient (light -> base -> dark), a darker rim stroke for
 * edge definition, and a soft highlight blob near the top, styled after
 * classic glossy-sphere UI (rounded specular highlight, darker lower rim).
 *
 * Takes only a color and a "which corner has the tail" flag - it doesn't
 * replace or bypass the app's existing color customization (the caller
 * still decides what color to pass in, same as the current flat bubbles
 * do with getProperPrimaryColor()), it just renders that color with gloss
 * and gradient instead of a flat fill. The configured hue shift (a quick
 * "shift the whole conversation's mood" control, separate from the two
 * precise per-bubble color pickers) is applied via getGelBubbleEffectiveColor()
 * - callers computing a contrast text color must use that same function on
 * the same base color, not compute contrast from the raw input, or the text
 * color and the actual rendered background can disagree.
 *
 * Built entirely from GradientDrawable/LayerDrawable, no bitmap assets -
 * works at any bubble size and reflows correctly with variable message
 * lengths.
 */
fun Context.createGelBubbleDrawable(baseColor: Int, isSent: Boolean): LayerDrawable {
    val shiftedColor = getGelBubbleEffectiveColor(baseColor)
    val bigRadius = resources.getDimension(org.fossify.commons.R.dimen.big_margin)
    val smallRadius = resources.getDimension(org.fossify.commons.R.dimen.small_margin)

    // Matches the existing item_sent_background/item_received_background
    // corner pattern: the "tail" corner (bottom-right for sent, bottom-left
    // for received) stays sharp, the other three stay rounded.
    val cornerRadii = if (isSent) {
        floatArrayOf(
            bigRadius, bigRadius,       // top-left
            bigRadius, bigRadius,       // top-right
            smallRadius, smallRadius,   // bottom-right
            bigRadius, bigRadius        // bottom-left
        )
    } else {
        floatArrayOf(
            bigRadius, bigRadius,       // top-left
            bigRadius, bigRadius,       // top-right
            bigRadius, bigRadius,       // bottom-right
            smallRadius, smallRadius    // bottom-left
        )
    }

    val lightColor = shiftedColor.lightenColor(30)
    val darkColor = shiftedColor.darkenColor(18)
    val rimColor = shiftedColor.darkenColor(32)
    val strokeWidthPx = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.tiny_margin)

    val body = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(lightColor, shiftedColor, darkColor)
    ).apply {
        this.cornerRadii = cornerRadii
        setStroke(strokeWidthPx, rimColor)
    }

    // A soft, semi-transparent white oval near the top - the "specular
    // highlight" that reads as glossy/wet rather than flat. Fixed size
    // (not stretched to the bubble's height) since message bubbles vary a
    // lot in height with content length, but a gloss highlight should stay
    // a compact accent near the top regardless, matching how it looks on a
    // reference glossy sphere at any size.
    val highlightColor = Color.argb(110, 255, 255, 255)
    val highlight = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(highlightColor, Color.TRANSPARENT)
    ).apply {
        shape = GradientDrawable.OVAL
    }

    val highlightWidth = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.big_margin) * 3
    val highlightHeight = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.big_margin) * 2
    val highlightTopInset = strokeWidthPx + resources.getDimensionPixelSize(org.fossify.commons.R.dimen.tiny_margin)

    return LayerDrawable(arrayOf(body, highlight)).apply {
        setLayerSize(1, highlightWidth, highlightHeight)
        setLayerGravity(1, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        setLayerInsetTop(1, highlightTopInset)
    }
}
