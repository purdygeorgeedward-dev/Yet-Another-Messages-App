package org.fossify.messages.helpers

import org.fossify.messages.R

data class GelPalette(val nameRes: Int, val sent: Int, val received: Int, val avatar: Int, val accent: Int)

val GEL_PALETTES = listOf(
    GelPalette(R.string.gel_palette_candy, DEFAULT_GEL_BUBBLE_SENT_COLOR,
        DEFAULT_GEL_BUBBLE_RECEIVED_COLOR, DEFAULT_GEL_AVATAR_COLOR, DEFAULT_GEL_ACCENT_COLOR),
    GelPalette(R.string.gel_palette_tropical, 0xFFFF4F93.toInt(), 0xFF00B890.toInt(),
        0xFFFFB300.toInt(), 0xFF00B8D4.toInt()),
    GelPalette(R.string.gel_palette_sunset, 0xFFF04E31.toInt(), 0xFFAA44F2.toInt(),
        0xFFFF4B99.toInt(), 0xFFFFA000.toInt()),
    GelPalette(R.string.gel_palette_ocean, 0xFF006FE6.toInt(), 0xFF00B6BA.toInt(),
        0xFF984FFF.toInt(), 0xFF28B9F0.toInt())
)

/** Includes theme and hue so a visible thread can refresh every affected surface. */
fun Config.gelStyleSignature(): String = listOf(gelBubbleTheme, gelBubbleSentColor,
    gelBubbleReceivedColor, gelAvatarColor, gelAccentColor, gelBubbleHueShift).joinToString(":")
