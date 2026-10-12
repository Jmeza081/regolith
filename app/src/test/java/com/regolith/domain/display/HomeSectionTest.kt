package com.regolith.domain.display

import com.regolith.domain.display.HomeSection.CONTINUE_WATCHING
import com.regolith.domain.display.HomeSection.MOMENTS
import com.regolith.domain.display.HomeSection.NEWLY_ADDED
import com.regolith.domain.display.HomeSection.ON_THIS_DEVICE
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionTest {
    @Test fun `nothing kept is the default order, the way Home always was`() {
        assertEquals(listOf(CONTINUE_WATCHING, NEWLY_ADDED, ON_THIS_DEVICE, MOMENTS), HomeSection.orderOf(null))
        assertEquals(HomeSection.entries, HomeSection.orderOf(""))
    }

    @Test fun `a kept order reads back as itself`() {
        val order = listOf(MOMENTS, CONTINUE_WATCHING, ON_THIS_DEVICE, NEWLY_ADDED)
        assertEquals(order, HomeSection.orderOf(HomeSection.store(order)))
    }

    @Test fun `a section the kept order leaves out comes after the rest, in its default place`() {
        // As a section added in a later version would be, to an order kept before it.
        assertEquals(listOf(MOMENTS, NEWLY_ADDED, CONTINUE_WATCHING, ON_THIS_DEVICE), HomeSection.orderOf("MOMENTS,NEWLY_ADDED"))
    }

    @Test fun `names it no longer knows are dropped, and a repeat counts once`() {
        assertEquals(
            listOf(ON_THIS_DEVICE, MOMENTS, CONTINUE_WATCHING, NEWLY_ADDED),
            HomeSection.orderOf("ON_THIS_DEVICE,RECENTLY_PLAYED,MOMENTS,ON_THIS_DEVICE"),
        )
    }

    @Test fun `stray spaces around a name don't lose it`() {
        assertEquals(listOf(MOMENTS, CONTINUE_WATCHING, NEWLY_ADDED, ON_THIS_DEVICE), HomeSection.orderOf(" MOMENTS , CONTINUE_WATCHING"))
    }
}
