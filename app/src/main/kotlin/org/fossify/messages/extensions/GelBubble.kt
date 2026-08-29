package org.fossify.messages.extensions

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import org.fossify.commons.extensions.darkenColor
import org.fossify.commons.extensions.lightenColor

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
 * and gradient instead of a flat fill.
 *
 * Built entirely from GradientDrawable/LayerDrawable, no bitmap assets -
 * works at any bubble size and reflows correctly with variable message
 * lengths.
 */
fun Context.createGelBubbleDrawable(baseColor: Int, isSent: Boolean): LayerDrawable {
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

    val lightColor = baseColor.lightenColor(30)
    val darkColor = baseColor.darkenColor(18)
    val rimColor = baseColor.darkenColor(32)
    val strokeWidthPx = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.tiny_margin)

    val body = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(lightColor, baseColor, darkColor)
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
