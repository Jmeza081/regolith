package com.regolith.ui.components

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Draws the one-letter label of a scrubber flag ("A" or "B"): 9dp bold
 * white text, centred on [centerX], sitting on [baselineY].
 *
 * Compose's own text drawing needs a measured layout per label, which is a
 * different text path from the one the phone has always used and would
 * move pixels. So each platform draws it with its native canvas: Android's
 * `Paint` (the code the phone already shipped), and Skia's `Font` on the
 * desktop, which is what Compose for Desktop draws with underneath.
 */
internal expect fun DrawScope.drawFlagLabel(label: String, centerX: Float, baselineY: Float)
