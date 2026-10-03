package com.regolith.ui.util

import com.regolith.domain.playback.AmbientLight

/**
 * What a light does, in one sentence, for Settings and the player's playback
 * sheet, which offer the same three choices. "Mirror" and "Color bleed" mean
 * nothing until you have seen them, so the words say what you will see — and
 * the two live lights also say what they cost.
 */
val AmbientLight.note: String
    get() = when (this) {
        AmbientLight.OFF -> "A still glow from the film's artwork. Nothing follows the picture, which saves a little battery."
        AmbientLight.MIRROR -> "A soft, blurred copy of the picture fills the screen around it and follows the film. Costs a little battery."
        AmbientLight.COLOR_BLEED -> "The colours at the edges of the picture shine outward, like a light strip behind a TV. Costs a little battery."
    }
